package engine.application;

import engine.evaluation.local.LocalEvaluationConfig;
import engine.utils.Json;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** Optional developer smoke against an already started service; never analyzes or downloads. */
public final class NlpReadinessSmoke {
    public static void main(String[] args) {
        var config=new LocalEvaluationConfig(1,URI.create("http://127.0.0.1:"+Integer.parseInt(args[1])+"/v1/analyze"),5,1,List.of("vader-sentiment"));
        try (var nlp=new ManagedNlp(Path.of(args[0]))) {
            var ready=nlp.readiness(config);
            if (!Boolean.TRUE.equals(ready.get("running"))) throw new IllegalStateException("NLP readiness protocol smoke failed");
            System.out.println(Json.write(ready));
        }
    }
}
