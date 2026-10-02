package engine.application;

import engine.TestFixtures;
import engine.evaluation.LLMEvaluator;
import engine.evaluation.EvaluationStatus;
import engine.evaluation.llm.LlmEvaluationResources;
import engine.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EvaluatorEncodingTest {
    @TempDir Path root;
    @Test void malformedEvaluatorSourcesCannotScheduleJobsOrReachProviders() throws Exception {
        TestFixtures.copyResources(root); var calls=new AtomicInteger();
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var service=new LlmEvaluationService(root,jobs,model -> { calls.incrementAndGet(); return request -> { fail(); return null; }; });
            for (String path:List.of("prompts/EvaluatorPrompt.txt","prompts/EvaluatorCue.txt","prompts/EvaluatorRepair.txt","evaluation/rubric.json")) {
                Path file=root.resolve(path); byte[] original=Files.readAllBytes(file); String text=new String(original,StandardCharsets.UTF_8);
                text=path.startsWith("prompts") ? text+"\nPRIVATE_EVALUATOR_ENCODING_SENTINEL <BAD_UTF8>" : text.replace("Consistency of", "PRIVATE_EVALUATOR_ENCODING_SENTINEL <BAD_UTF8> Consistency of");
                Files.write(file,ResourceEncodingTest.malformed(text));
                try {
                    var error=assertThrows(IllegalArgumentException.class,() -> service.start(LlmEvaluationTest.evidence(),LlmEvaluationTest.MODEL));
                    assertTrue(error.getMessage().contains("UTF-8")); assertTrue(error.getMessage().contains(path));
                    assertNull(error.getCause()); assertFalse(error.getMessage().contains("SENTINEL"));
                    assertEquals(0,calls.get()); assertTrue(jobs.list().isEmpty());
                } finally { Files.write(file,original); }
            }
        }
    }
    @Test void malformedEvaluatorAssetCandidatesLeaveExactResourceBytesUntouched() throws Exception {
        TestFixtures.copyResources(root); var assets=new AssetService(root);
        for (String path:List.of("prompts/EvaluatorPrompt.txt","prompts/EvaluatorCue.txt","prompts/EvaluatorRepair.txt")) {
            String text=assets.read(path); byte[] original=Files.readAllBytes(root.resolve(path));
            for (String invalid:List.of(text+"\uD800PRIVATE_EVALUATOR_ENCODING_SENTINEL",text+"\uDC00PRIVATE_EVALUATOR_ENCODING_SENTINEL")) {
                for (boolean save:List.of(false,true)) {
                    var error=assertThrows(IllegalArgumentException.class,() -> assets.update(path,invalid,save));
                    assertNull(error.getCause()); assertFalse(error.getMessage().contains("SENTINEL"));
                    assertArrayEquals(original,Files.readAllBytes(root.resolve(path)));
                }
            }
        }
    }
    @Test void malformedDurableTextCannotReplaceExistingFilesOrCreateMissingParents() throws Exception {
        Path existing=root.resolve("existing.txt"), absent=root.resolve("new-parent/nested/new.txt");
        byte[] original="Māori 😀 original\r\n".getBytes(StandardCharsets.UTF_8); Files.write(existing,original);
        for (String invalid:List.of("\uD800PRIVATE_WRITER_SENTINEL","\uDC00PRIVATE_WRITER_SENTINEL")) {
            for (Path file:List.of(existing,absent)) {
                var error=assertThrows(IllegalArgumentException.class,() -> AtomicFiles.write(file,invalid));
                assertNull(error.getCause()); assertFalse(error.getMessage().contains("SENTINEL"));
                assertArrayEquals(original,Files.readAllBytes(existing)); assertFalse(Files.exists(absent.getParent().getParent()));
                try (var files=Files.list(root)) { assertEquals(List.of(existing),files.toList()); }
            }
        }
    }
    @Test void validEvaluatorTextRetainsExactHashesAndFrozenModelFacingContents() throws Exception {
        TestFixtures.copyResources(root); var replacements=new LinkedHashMap<String,String>();
        for (String path:List.of("prompts/EvaluatorPrompt.txt","prompts/EvaluatorCue.txt","prompts/EvaluatorRepair.txt")) {
            String text=Files.readString(root.resolve(path))+"\r\nMāori 😀 e\u0301 \uFFFD valid evaluator text.\r\n";
            AtomicFiles.write(root.resolve(path),text); assertArrayEquals(text.getBytes(StandardCharsets.UTF_8),Files.readAllBytes(root.resolve(path)));
            replacements.put(path,text);
        }
        var captured=LlmEvaluationResources.load(root); assertEquals(captured,LlmEvaluationResources.load(root,replacements));
        for (var entry:replacements.entrySet()) {
            assertEquals(Hashes.sha256(entry.getValue()),captured.sourceHashes().get(entry.getKey()));
            Files.writeString(root.resolve(entry.getKey()),entry.getValue()+"NEXT_JOB_ONLY");
        }
        assertNotEquals(captured.sourceHashes(),LlmEvaluationResources.load(root).sourceHashes()); var calls=new AtomicInteger();
        var result=new LLMEvaluator(request -> {
            calls.incrementAndGet(); String wire=Json.write(request);
            assertTrue(request.systemInstructions().contains("Māori 😀 e\u0301 \uFFFD valid evaluator text."));
            assertTrue(wire.contains("Māori 😀 e\u0301 \uFFFD valid evaluator text.")); assertFalse(wire.contains("NEXT_JOB_ONLY"));
            return LlmEvaluationTest.fake(request,captured.rubric());
        },LlmEvaluationTest.MODEL,captured).evaluate(LlmEvaluationTest.evidence());
        assertEquals(1,calls.get()); assertEquals(EvaluationStatus.OK,result.topics().get(0).status()); assertEquals(captured.sourceHashes(),result.sourceHashes());
    }
}
