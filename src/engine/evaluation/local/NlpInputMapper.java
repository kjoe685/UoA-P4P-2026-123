package engine.evaluation.local;

import engine.transcript.EventType;
import engine.transcript.Transcript;
import java.util.ArrayList;
import java.util.List;

/** Projects public events into identity-free bounded requests; topic labels are not policy targets. */
public final class NlpInputMapper {
    private NlpInputMapper() { }

    public static List<NlpRequest> batches(Transcript transcript, String method, LocalEvaluationConfig config) {
        List<NlpRequest> batches = new ArrayList<>();
        List<NlpRequest.Turn> batch = new ArrayList<>();
        int characters = 0;
        for (var event : transcript.events()) {
            if (event.type() == EventType.TOPIC_ANNOUNCEMENT) continue;
            int length = event.text().codePointCount(0, event.text().length());
            if (length > 100_000) throw new IllegalArgumentException("Speech exceeds the NLP input limit");
            if (!batch.isEmpty() && (batch.size() == config.batchSize() || characters + length > 500_000)) {
                batches.add(new NlpRequest(1, List.of(method), batch));
                batch = new ArrayList<>();
                characters = 0;
            }
            var targets = method.equals("deberta-stance") ? config.policyTargets().stream()
                    .filter(target -> target.topicIndex() == event.topicIndex())
                    .map(target -> new NlpRequest.Target(target.id(), target.proposition())).toList() : List.<NlpRequest.Target>of();
            batch.add(new NlpRequest.Turn(event.turnId(), event.text(), targets));
            characters += length;
        }
        if (!batch.isEmpty()) batches.add(new NlpRequest(1, List.of(method), batch));
        return List.copyOf(batches);
    }
}
