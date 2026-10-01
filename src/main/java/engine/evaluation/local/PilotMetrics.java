package engine.evaluation.local;

import java.util.*;

/** Standard classification metrics per method/split, without a combined evaluation score. */
public final class PilotMetrics {
    public record Observation(String id,String split,String gold,String predicted,String error,double latencyMillis,NlpResponse.Method analysis) { }
    private PilotMetrics() { }
    public static Map<String,Object> calculate(List<Observation> observations,List<String> labels) {
        var matrix=new LinkedHashMap<String,Map<String,Integer>>(); var predictions=new ArrayList<>(labels); predictions.addAll(List.of("uncertain","failed"));
        for (String label:labels) { var cells=new LinkedHashMap<String,Integer>(); predictions.forEach(predicted -> cells.put(predicted,0)); matrix.put(label,cells); }
        for (var item:observations) {
            if (!matrix.containsKey(item.gold()) || !predictions.contains(item.predicted()) || !Double.isFinite(item.latencyMillis()) || item.latencyMillis()<0)
                throw new IllegalArgumentException("Invalid pilot metric observation");
            matrix.get(item.gold()).compute(item.predicted(),(key,value) -> value+1);
        }
        double f1=0;
        for (String label:labels) {
            int tp=matrix.get(label).get(label), fp=0, fn=0;
            for (String other:labels) if (!other.equals(label)) fp+=matrix.get(other).get(label);
            for (String prediction:predictions) if (!prediction.equals(label)) fn+=matrix.get(label).get(prediction);
            f1+=2*tp+fp+fn==0 ? 0 : (2d*tp)/(2*tp+fp+fn);
        }
        var latency=observations.stream().map(Observation::latencyMillis).sorted().toList(); int count=observations.size();
        return Map.of("count",count,"macroF1",labels.isEmpty() ? 0 : f1/labels.size(),"confusionMatrix",matrix,
                "coverage",count==0 ? 0d : observations.stream().filter(item -> labels.contains(item.predicted())).count()/(double)count,
                "failures",observations.stream().filter(item -> item.predicted().equals("failed")).count(),
                "abstentions",observations.stream().filter(item -> item.predicted().equals("uncertain")).count(),
                "meanLatencyMillis",latency.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                "p95LatencyMillis",count==0 ? 0d : latency.get(Math.max(0,(int)Math.ceil(.95*count)-1)));
    }
}
