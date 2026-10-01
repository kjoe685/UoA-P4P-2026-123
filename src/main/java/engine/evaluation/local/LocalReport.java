package engine.evaluation.local;

import java.util.List;

/** Separate method scales, evidence and provenance. No combined or normalized score. */
public record LocalReport(int schemaVersion,String runId,String transcriptSha256,String configurationSha256,
                          long capturedAt,List<MethodResult> methods) {
    public LocalReport { methods=List.copyOf(methods); }
    public record MethodResult(String methodId,String status,String error,List<NlpResponse.Method> batches) {
        public MethodResult { batches=List.copyOf(batches); }
    }
}
