package engine.application;

import engine.evaluation.local.*;
import engine.transcript.Transcript;
import engine.utils.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.BiFunction;

/** Freeze public evidence/settings at submission, persist each batch before reporting progress. */
public final class LocalEvaluationService {
    private final Path root;
    private final JobService jobs;
    private final BiFunction<LocalEvaluationConfig,String,NlpClient> clients;
    public LocalEvaluationService(Path root,JobService jobs,Function<LocalEvaluationConfig,NlpClient> clients) {
        this(root,jobs,(config,settings) -> clients.apply(config));
    }
    public LocalEvaluationService(Path root,JobService jobs,BiFunction<LocalEvaluationConfig,String,NlpClient> clients) {
        this.root=root; this.jobs=jobs; this.clients=clients;
    }
    public LocalEvaluationConfig configuration() {
        try { return Json.read(Files.readString(root.resolve("config/local-evaluation.json")),LocalEvaluationConfig.class); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot read local evaluation settings"); }
    }
    public BackgroundJob start(Transcript transcript,Map<String,Object> body) {
        if (!Set.of("methods").containsAll(body.keySet())) throw new IllegalArgumentException("Unknown evaluation setting");
        var defaults=configuration(); List<String> methods=defaults.methods();
        if (body.containsKey("methods")) {
            if (!(body.get("methods") instanceof List<?> raw) || raw.stream().anyMatch(item -> !(item instanceof String)))
                throw new IllegalArgumentException("methods must be a list of method IDs");
            methods=raw.stream().map(String.class::cast).toList();
        }
        var config=new LocalEvaluationConfig(1,defaults.endpoint(),defaults.timeoutSeconds(),defaults.batchSize(),methods);
        String nlpSettings;
        try { nlpSettings=Files.readString(root.resolve("nlp/config/models.json")); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot read local model configuration"); }
        var requests=new LinkedHashMap<String,List<NlpRequest>>();
        for (String method:methods) requests.put(method,NlpInputMapper.batches(transcript,method,config));
        String transcriptHash=Hashes.sha256(Json.write(transcript)), configHash=Hashes.sha256(Json.writeCanonical(Map.of("evaluation",config,"localModelSettings",nlpSettings))); long captured=System.currentTimeMillis();
        return jobs.submit("local-evaluation",transcript.runId(),Map.of("transcript",transcript,"configuration",config,"localModelSettings",nlpSettings),context -> {
            List<LocalReport.MethodResult> completed=new ArrayList<>(); NlpClient client=null; boolean success=true;
            for (var entry:requests.entrySet()) {
                context.checkCancelled(); List<NlpResponse.Method> batches=new ArrayList<>(); String method=entry.getKey();
                String status=entry.getValue().isEmpty() ? "insufficient_evidence" : "ok", error=entry.getValue().isEmpty() ? "no_speeches" : null;
                for (NlpRequest request:entry.getValue()) {
                    context.checkCancelled(); NlpResponse.Method result;
                    try {
                        if (client==null) client=clients.apply(config,nlpSettings);
                        var response=client.analyze(request); context.checkCancelled(); NlpResponseValidator.validate(request,response); result=response.methods().get(0);
                        validateProvenance(result,method,nlpSettings);
                    } catch (java.util.concurrent.CancellationException e) { throw e; }
                    catch (RuntimeException e) {
                        context.checkCancelled(); result=new NlpResponse.Method(method,"failed","transport_or_response_failed",null,List.of(),0);
                    }
                    batches.add(result);
                    if (result.status().equals("failed")) { status="failed"; error="method_or_batch_failed"; success=false; }
                    else if (!status.equals("failed") && batches.stream().allMatch(batch -> batch.status().equals("insufficient_evidence"))) {
                        status="insufficient_evidence"; error="no_evaluable_speeches_or_targets";
                    } else if (!status.equals("failed")) { status="ok"; error=null; }
                    List<LocalReport.MethodResult> partial=new ArrayList<>(completed); partial.add(new LocalReport.MethodResult(method,status,error,batches));
                    context.update("Analyzing "+method+": "+batches.size()+" / "+entry.getValue().size()+" batches",new LocalReport(1,transcript.runId(),transcriptHash,configHash,captured,partial));
                }
                completed.add(new LocalReport.MethodResult(method,status,error,batches));
                context.update("Finished "+method,new LocalReport(1,transcript.runId(),transcriptHash,configHash,captured,completed));
            }
            return success;
        });
    }
    static void validateProvenance(NlpResponse.Method result,String method,String settings) {
        var provenance=result.provenance(); if (provenance==null) return;
        String model="vaderSentiment", revision="3.3.2";
        if (!method.equals("vader-sentiment")) {
            var source=(Map<?,?>)Json.parse(settings); var spec=(Map<?,?>)source.get(method.equals("cardiff-sentiment") ? "cardiff" : "deberta");
            model=(String)spec.get("modelId"); revision=(String)spec.get("revision");
        }
        if (!model.equals(provenance.modelId()) || !revision.equals(provenance.revision())
                || !NlpResponse.IMPLEMENTATION_VERSION.equals(provenance.implementationVersion())
                || !Hashes.sha256(settings).equals(provenance.configSha256()))
            throw new IllegalStateException("Local response provenance does not match captured configuration");
    }
}
