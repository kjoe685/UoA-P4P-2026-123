package engine.provider;

import engine.agent.*;
import engine.chat.*;
import engine.config.ModelConfig;
import engine.transcript.*;
import engine.utils.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OutboundIsolationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @Test void blindEvaluatorWireContainsOnlyPublicEvidenceEvenWhenAdapterWasUsedByAnAgent() throws Exception {
        engine.TestFixtures.copyResources(root); var resources=engine.evaluation.llm.LlmEvaluationResources.load(root);
        var source=engine.application.LlmEvaluationTest.evidence(); var payloads=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/",exchange -> {
            String payload=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); payloads.add(payload);
            String content=payloads.size()==1 ? "Public agent words" : Json.write(engine.application.LlmEvaluationTest.answer("topic-1",source.events(),source.roster(),resources.rubric()));
            byte[] response=Json.write(Map.of("model","gpt-4o-mini","choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try {
            var provider=new OpenAIChatManager("KEY_SENTINEL",HttpClient.newHttpClient(),URI.create("http://localhost:"+server.getAddress().getPort()));
            var model=new ModelConfig("openai","gpt-4o-mini",1d,null,4096,5);
            new Agent(source.roster().get(0),AdversarialStrategy.STRAW_MAN,provider,"PRIVATE_SETUP_SENTINEL HIDDEN_TREATMENT_SENTINEL",List.of("PRIVATE_GROUNDING_SENTINEL"),model).speak(List.of(),"Opening");
            var report=new engine.evaluation.LLMEvaluator(provider,model,resources).evaluate(source);
            assertEquals(engine.evaluation.EvaluationStatus.OK,report.topics().get(0).status()); assertEquals(2,payloads.size());
            assertTrue(payloads.get(0).contains("PRIVATE_SETUP_SENTINEL"));
            for (String forbidden:List.of("PRIVATE_SETUP_SENTINEL","HIDDEN_TREATMENT_SENTINEL","PRIVATE_GROUNDING_SENTINEL","KEY_SENTINEL","STRAW_MAN")) assertFalse(payloads.get(1).contains(forbidden));
            assertTrue(payloads.get(1).contains("turn-10")); assertTrue(payloads.get(1).contains("Order!"));
            assertFalse(Json.write(report).contains("SENTINEL"));
        } finally { server.stop(0); }
    }
    @Test void sharedStatelessAdapterCannotLeakAnotherRecipientsPrivateSetup() throws Exception {
        List<Map<?,?>> payloads=new ArrayList<>();
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/",exchange -> {
            payloads.add((Map<?,?>)Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] response="{\"model\":\"gpt-4o-mini\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Public words\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var provider=new OpenAIChatManager("KEY_SENTINEL",HttpClient.newHttpClient(),URI.create("http://localhost:"+server.getAddress().getPort()));
            var model=new ModelConfig("openai","gpt-4o-mini",1d,null,1024,5);
            var ordinary=new Agent(new Participant("a","Ordinary MP",Party.LABOUR,"Labour"),AdversarialStrategy.NONE,provider,
                    "ORDINARY_PRIVATE_SENTINEL",List.of("ORDINARY_GROUNDING_SENTINEL"),model);
            var adversary=new Agent(new Participant("b","Other MP",Party.NATIONAL,"National"),AdversarialStrategy.STRAW_MAN,provider,
                    "HIDDEN_STRATEGY_SENTINEL OTHER_GROUNDING_SENTINEL",List.of("OTHER_GROUNDING_SENTINEL"),model);
            List<PublicEvent> events=List.of(new PublicEvent("turn-1","topic-1",PublicEvent.Type.TOPIC,null,"Housing"));
            ordinary.speak(events,"Opening"); adversary.speak(events,"Opening"); ordinary.speak(events,"Follow up");
            for (int index=0;index<payloads.size();index++) {
                String payload=Json.write(payloads.get(index));
                if (index==1) { assertTrue(payload.contains("HIDDEN_STRATEGY_SENTINEL")); assertFalse(payload.contains("ORDINARY_PRIVATE_SENTINEL")); }
                else { assertTrue(payload.contains("ORDINARY_PRIVATE_SENTINEL")); assertFalse(payload.contains("HIDDEN_STRATEGY_SENTINEL")); assertFalse(payload.contains("OTHER_GROUNDING_SENTINEL")); }
                assertFalse(payload.contains("KEY_SENTINEL")); assertFalse(payload.contains("STRAW_MAN"));
                assertEquals(false,payloads.get(index).get("store"));
            }
            assertFalse(Json.write(events).contains("SENTINEL"));
            assertThrows(IllegalArgumentException.class,() -> Json.write(ordinary));
            assertFalse(provider.toString().contains("KEY_SENTINEL"));
        } finally { server.stop(0); }
    }
}
