package engine.evaluation.local;

import engine.transcript.Transcript;
import java.util.*;

/** Projects only completed public speech; identities, chair metadata and private setup are excluded. */
public final class NlpInputMapper {
    private NlpInputMapper() { }
    public static List<NlpRequest> batches(Transcript transcript,String method,LocalEvaluationConfig config) {
        List<NlpRequest> batches=new ArrayList<>(); List<NlpRequest.Turn> batch=new ArrayList<>(); int characters=0;
        var topics=new HashMap<String,engine.transcript.Topic>(); transcript.topics().forEach(topic -> topics.put(topic.id(),topic));
        for (var event:transcript.events()) {
            if (event.speaker()==null) continue;
            int length=event.text().codePointCount(0,event.text().length());
            if (length>100_000) throw new IllegalArgumentException("Speech exceeds the local analysis input limit");
            if (!batch.isEmpty() && (batch.size()==config.batchSize() || characters+length>500_000)) {
                batches.add(new NlpRequest(2,List.of(method),batch)); batch=new ArrayList<>(); characters=0;
            }
            var topic=topics.get(event.topicId());
            List<NlpRequest.Target> targets=method.equals("deberta-stance") && topic.policyTarget()!=null
                    ? List.of(new NlpRequest.Target(topic.id(),topic.policyTarget())) : List.of();
            batch.add(new NlpRequest.Turn(event.id(),event.text(),targets)); characters+=length;
        }
        if (!batch.isEmpty()) batches.add(new NlpRequest(2,List.of(method),batch));
        return List.copyOf(batches);
    }
}
