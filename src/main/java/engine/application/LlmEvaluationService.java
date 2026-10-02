package engine.application;

import engine.ChatManager;
import engine.config.ModelConfig;
import engine.config.OllamaConfig;
import engine.evaluation.*;
import engine.evaluation.llm.*;
import engine.transcript.Transcript;
import engine.utils.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.function.BiFunction;

/** A blind job receives public evidence and evaluator settings, never agent setup. */
public final class LlmEvaluationService {
    private final Path root;
    private final JobService jobs;
    private final BiFunction<ModelConfig,OllamaConfig,ChatManager> providers;
    public LlmEvaluationService(Path root,JobService jobs,Function<ModelConfig,ChatManager> providers) {
        this(root,jobs,(model,config) -> providers.apply(model));
    }
    public LlmEvaluationService(Path root,JobService jobs,BiFunction<ModelConfig,OllamaConfig,ChatManager> providers) {
        this.root=root; this.jobs=jobs; this.providers=providers;
    }
    public BackgroundJob start(Transcript transcript,ModelConfig model) {
        return start(transcript,model,OllamaConfig.load(root));
    }
    public BackgroundJob start(Transcript transcript,ModelConfig model,OllamaConfig ollama) {
        if (model.provider().equals("demo")) throw new IllegalArgumentException("The deterministic demonstration cannot evaluate debate quality; choose an evaluator preset");
        var resources=LlmEvaluationResources.load(root);
        String evidenceHash=Hashes.sha256(Json.write(transcript));
        String settingsHash=Hashes.sha256(Json.writeCanonical(Map.of("model",model,"resources",resources,"localRuntime",ollama)));
        long captured=System.currentTimeMillis();
        return jobs.submit("llm-evaluation",transcript.runId(),Map.of("transcript",transcript,"model",model,"resources",resources,"localRuntime",ollama),context -> {
            var evaluator=new LLMEvaluator(request -> providers.apply(model,ollama).complete(request),model,resources);
            var result=evaluator.evaluate(transcript,partial -> {
                context.checkCancelled();
                context.update("Evaluated "+partial.topics().size()+" / "+transcript.topics().size()+" topics",
                        new LlmReport(1,transcript.runId(),evidenceHash,settingsHash,captured,partial));
            });
            return result.topics().stream().noneMatch(topic -> topic.status()==EvaluationStatus.FAILED);
        });
    }
}
