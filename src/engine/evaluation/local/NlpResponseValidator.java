package engine.evaluation.local;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Checks scores and exact evidence coverage before any external result can enter a report. */
public final class NlpResponseValidator {
    private static final Set<String> ERRORS = Set.of("unknown_method", "no_speeches", "model_unavailable", "item_failed",
            "no_evaluable_items", "no_policy_target", "input_budget_exceeded", "inference_failed", "no_text");
    private NlpResponseValidator() { }

    public static void validate(NlpRequest request, NlpResponse response) {
        require(response.schemaVersion() == 1 && response.methods().size() == request.methods().size());
        Set<String> methods = new HashSet<>();
        for (var method : response.methods()) {
            require(request.methods().contains(method.methodId()) && methods.add(method.methodId()));
            status(method.status(), method.error());
            latency(method.latencyMillis());
            boolean stance = method.methodId().equals("deberta-stance");
            boolean vader = method.methodId().equals("vader-sentiment");
            if (method.provenance() == null) {
                require(method.items().isEmpty() && (method.status().equals("failed")
                        || method.status().equals("insufficient_evidence") && request.turns().isEmpty()));
                continue;
            }
            var provenance = method.provenance();
            require(nonblank(provenance.modelId()) && nonblank(provenance.revision()) && nonblank(provenance.implementationVersion())
                    && provenance.configSha256() != null && provenance.configSha256().matches("[0-9a-f]{64}")
                    && nonblank(provenance.device()) && nonblank(provenance.scoreSemantics()) && nonblank(provenance.segmentation()));
            Map<Key, NlpRequest.Turn> expected = new HashMap<>();
            for (var turn : request.turns()) {
                if (stance && !turn.targets().isEmpty()) {
                    for (var target : turn.targets()) expected.put(new Key(turn.turnId(), target.id()), turn);
                } else expected.put(new Key(turn.turnId(), null), turn);
            }
            boolean failed = false;
            boolean allInsufficient = true;
            for (var item : method.items()) {
                var turn = expected.remove(new Key(item.turnId(), item.targetId()));
                require(turn != null);
                status(item.status(), item.error());
                latency(item.latencyMillis());
                failed |= item.status().equals("failed");
                allInsufficient &= item.status().equals("insufficient_evidence");
                if (!item.status().equals("ok")) {
                    require(item.chunks().isEmpty());
                    continue;
                }
                require(!item.chunks().isEmpty() && (!stance || item.targetId() != null));
                int previous = 0;
                int sentence = -1;
                int length = turn.text().codePointCount(0, turn.text().length());
                for (var chunk : item.chunks()) {
                    require(chunk.start() >= previous && chunk.end() > chunk.start() && chunk.end() <= length
                            && chunk.sentenceIndex() >= sentence);
                    require(whitespace(slice(turn.text(), previous, chunk.start())));
                    require(slice(turn.text(), chunk.start(), chunk.end()).equals(chunk.text()));
                    previous = chunk.end();
                    sentence = chunk.sentenceIndex();
                    Set<String> labels = stance ? Set.of("support", "oppose", "unrelated") : Set.of("negative", "neutral", "positive");
                    require(chunk.scores().keySet().equals(labels));
                    double total = 0;
                    for (Double score : chunk.scores().values()) { require(score != null); unit(score); total += score; }
                    require(Math.abs(total - 1) <= (vader ? .002 : .00001));
                    require(labels.contains(chunk.label()) || (!vader && "uncertain".equals(chunk.label())));
                    if (vader) {
                        require(chunk.compound() != null && Double.isFinite(chunk.compound()) && Math.abs(chunk.compound()) <= 1
                                && chunk.uncertainty() == null);
                        String label = chunk.compound() >= .05 ? "positive" : chunk.compound() <= -.05 ? "negative" : "neutral";
                        require(label.equals(chunk.label()));
                    } else {
                        require(chunk.compound() == null && chunk.uncertainty() != null);
                        var doubt = chunk.uncertainty();
                        unit(doubt.maxScore()); unit(doubt.margin()); unit(doubt.entropy());
                        var ordered = chunk.scores().values().stream().sorted(java.util.Comparator.reverseOrder()).toList();
                        require(Math.abs(doubt.maxScore() - ordered.get(0)) < .00001
                                && Math.abs(doubt.margin() - (ordered.get(0) - ordered.get(1))) < .00001);
                        require(doubt.abstained() == "uncertain".equals(chunk.label()));
                        if (!doubt.abstained()) require(Math.abs(chunk.scores().get(chunk.label()) - ordered.get(0)) < .00001);
                    }
                }
                require(whitespace(slice(turn.text(), previous, length)));
            }
            require(expected.isEmpty());
            require(method.status().equals(failed ? "failed" : allInsufficient ? "insufficient_evidence" : "ok"));
        }
    }

    private record Key(long turnId, String targetId) { }
    private static String slice(String text, int start, int end) {
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }
    private static boolean nonblank(String text) { return text != null && !text.isBlank(); }
    // Match Python str.isspace(), including NBSP and NEXT LINE, when checking omitted separators.
    private static boolean whitespace(String text) {
        return text.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85);
    }
    private static void unit(double value) { require(Double.isFinite(value) && value >= 0 && value <= 1); }
    private static void latency(double value) { require(Double.isFinite(value) && value >= 0); }
    private static void status(String status, String error) {
        require(Set.of("ok", "failed", "insufficient_evidence").contains(status));
        require(status.equals("ok") ? error == null : error != null && ERRORS.contains(error));
    }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("Local NLP returned invalid evidence or scores");
    }
}
