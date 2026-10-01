package engine.web;

import com.sun.net.httpserver.*;
import engine.application.*;
import engine.agent.AdversarialStrategy;
import engine.config.*;
import engine.utils.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Thin HTTP adapter for the shared application operations. */
public final class ApiHandler implements HttpHandler {
    private final DebateApplication application;
    public ApiHandler(DebateApplication application) { this.application=application; }
    @Override public void handle(HttpExchange exchange) throws IOException {
        try { route(exchange); }
        catch (NoSuchElementException e) { sendJson(exchange,404,Map.of("error","No saved sitting with that id")); }
        catch (IllegalArgumentException e) { sendJson(exchange,400,Map.of("error",e.getMessage()==null ? "Invalid settings" : e.getMessage())); }
        catch (IllegalStateException e) { sendJson(exchange,409,Map.of("error","Operation unavailable. Check run status, configuration and storage.")); }
        catch (RuntimeException e) { sendJson(exchange,500,Map.of("error","The operation failed. Check the server and configuration.")); }
        finally { exchange.close(); }
    }
    private void route(HttpExchange exchange) throws IOException {
        String method=exchange.getRequestMethod();
        String[] parts=exchange.getRequestURI().getPath().replaceAll("/+$","").split("/");
        if (parts.length==3 && parts[2].equals("config")) {
            require(method,"GET"); sendJson(exchange,200,config()); return;
        }
        if (parts.length==3 && parts[2].equals("debates")) {
            if (method.equals("GET")) {
                sendJson(exchange,200,application.runs().stream().map(run -> Map.of("id",run.runId(),"startedAt",run.startedAt(),
                        "outcome",run.outcome(),"topics",run.topics(),"speeches",run.events().stream().filter(event -> event.speaker()!=null).count())).toList());
            } else {
                require(method,"POST");
                var session=application.start(body(exchange)); sendJson(exchange,201,Map.of("id",session.id()));
            }
            return;
        }
        if (parts.length==4 && parts[2].equals("debates") && parts[3].equals("import")) {
            require(method,"POST");
            var source=Json.read(Json.write(body(exchange)),engine.transcript.Transcript.class);
            var session=application.importTranscript(source);
            sendJson(exchange,201,Map.of("id",session.id())); return;
        }
        if (parts.length==5 && parts[2].equals("debates")) {
            var session=application.find(parts[3]);
            switch (parts[4]) {
                case "events" -> { require(method,"GET"); stream(exchange,session); return; }
                case "transcript" -> { require(method,"GET"); downloadHeader(exchange,session.id()+".json"); sendJson(exchange,200,session.transcript()); return; }
                case "export" -> { require(method,"GET"); downloadHeader(exchange,session.id()+".txt"); sendText(exchange,application.textExport(session.id())); return; }
                case "speaker" -> {
                    require(method,"POST"); Object text=body(exchange).get("message");
                    if (!(text instanceof String ruling)) throw new IllegalArgumentException("A ruling needs text");
                    application.ruling(session.id(),ruling); sendJson(exchange,202,Map.of()); return;
                }
                case "adjourn" -> {
                    require(method,"POST"); body(exchange); application.cancel(session.id()); sendJson(exchange,202,Map.of()); return;
                }
            }
        }
        sendJson(exchange,404,Map.of("error","Unknown API endpoint"));
    }
    private Map<String,Object> config() {
        EngineConfig settings=application.configuration().config();
        boolean key=engine.openAi.OpenAIKeyReader.configured();
        List<Map<String,Object>> parties=new ArrayList<>();
        settings.parties().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var profile=entry.getValue(); parties.add(Map.of("id",entry.getKey().name(),"name",profile.displayName(),"ideology",profile.ideology()));
        });
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("apiKeyConfigured",key);
        result.put("apiKeyProblem",key ? null : "Set OPENAI_API_KEY or keys/openAi/OpenAI_Key.txt for OpenAI.");
        result.put("defaultProvider",settings.agentModel().provider()); result.put("defaultTopic",settings.defaultTopic());
        result.put("defaultRounds",settings.defaultRounds()); result.put("maxRounds",10); result.put("maxTopics",10);
        result.put("parties",parties);
        result.put("strategies",Arrays.stream(AdversarialStrategy.values()).map(value -> Map.of("id",value.name(),"instruction",value.getInstruction())).toList());
        result.put("models",settings.models()); result.put("agentModelPreset",settings.agentModelPreset()); result.put("evaluatorModelPreset",settings.evaluatorModelPreset());
        return result;
    }
    private void stream(HttpExchange exchange,RunSession session) throws IOException {
        int next=0;
        String last=exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (last!=null) try {
            long index=Long.parseLong(last.trim())+1;
            if (index>=0 && index<Integer.MAX_VALUE) next=(int)index;
        } catch (NumberFormatException ignored) { }
        exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-cache");
        exchange.sendResponseHeaders(200,0);
        try {
            OutputStream output=exchange.getResponseBody();
            while (session.hasMoreEvents(next)) {
                List<String> batch=session.awaitEvents(next,15000);
                StringBuilder text=new StringBuilder();
                if (batch.isEmpty()) text.append(": keep-alive\n\n");
                for (String event:batch) text.append("id: ").append(next++).append("\ndata: ").append(event).append("\n\n");
                output.write(text.toString().getBytes(StandardCharsets.UTF_8)); output.flush();
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException e) { /* A disconnected reader does not stop the sitting. */ }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> body(HttpExchange exchange) throws IOException {
        String contentType=exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType==null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json"))
            throw new IllegalArgumentException("Use application/json");
        byte[] data=exchange.getRequestBody().readNBytes(2*1024*1024+1);
        if (data.length>2*1024*1024) throw new IllegalArgumentException("Request body is too large");
        Object parsed=Json.parse(new String(data,StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?,?>)) throw new IllegalArgumentException("Request body must be an object");
        return (Map<String,Object>)parsed;
    }
    private static void require(String actual,String expected) {
        if (!actual.equals(expected)) throw new IllegalArgumentException("Use "+expected+" for this endpoint");
    }
    private static void sendJson(HttpExchange exchange,int status,Object value) throws IOException {
        byte[] data=Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.sendResponseHeaders(status,data.length); exchange.getResponseBody().write(data);
    }
    private static void sendText(HttpExchange exchange,String text) throws IOException {
        byte[] data=text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data);
    }
    private static void downloadHeader(HttpExchange exchange,String filename) {
        if ("download=1".equals(exchange.getRequestURI().getRawQuery()))
            exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=\"parliament-"+filename+"\"");
    }
}
