package engine.web;

import com.sun.net.httpserver.*;
import engine.application.*;
import engine.agent.AdversarialStrategy;
import engine.config.*;
import engine.utils.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.*;

/** Thin HTTP adapter for the shared application operations. */
public final class ApiHandler implements HttpHandler {
    private final DebateApplication application;
    public ApiHandler(DebateApplication application) { this.application=application; }
    @Override public void handle(HttpExchange exchange) throws IOException {
        try { route(exchange); }
        catch (NoSuchElementException e) { sendJson(exchange,404,Map.of("error","No saved item with that id or name")); }
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
        if (parts.length==3 && parts[2].equals("corpus")) {
            if (method.equals("GET")) sendJson(exchange,200,application.corpus().status());
            else { require(method,"POST"); sendJson(exchange,200,application.corpus().importCorpus(body(exchange))); }
            return;
        }
        if (parts.length>=3 && parts[2].equals("pilots")) {
            if (parts.length==3) {
                if (method.equals("GET")) sendJson(exchange,200,application.pilots().list());
                else { require(method,"POST"); sendJson(exchange,201,application.pilots().importDataset(body(exchange))); }
                return;
            }
            if (parts.length==4) {
                require(method,"GET"); var dataset=application.pilots().read(parts[3]);
                downloadHeader(exchange,parts[3]+"-pilot.json"); sendJson(exchange,200,dataset); return;
            }
            if (parts.length==5 && parts[4].equals("prepare")) { require(method,"POST"); sendJson(exchange,201,application.pilots().prepare(parts[3],body(exchange))); return; }
            if (parts.length==5 && parts[4].equals("evaluate")) { require(method,"POST"); sendJson(exchange,202,Map.of("id",application.pilots().evaluate(parts[3],body(exchange)).id())); return; }
        }
        if (parts.length==3 && parts[2].equals("ollama-readiness")) {
            require(method,"GET"); sendJson(exchange,200,application.ollamaReadiness()); return;
        }
        if (parts.length==3 && parts[2].equals("ollama-setup")) {
            require(method,"POST"); if (!body(exchange).isEmpty()) throw new IllegalArgumentException("Runtime setup needs an empty object");
            sendJson(exchange,202,Map.of("id",application.setupOllama().id())); return;
        }
        if (parts.length==3 && parts[2].equals("ollama-model-setup")) {
            require(method,"POST"); var value=body(exchange);
            if (!value.keySet().equals(Set.of("modelPreset")) || !(value.get("modelPreset") instanceof String preset))
                throw new IllegalArgumentException("Provide an Ollama model preset");
            sendJson(exchange,202,Map.of("id",application.downloadOllamaModel(preset).id())); return;
        }
        if (parts.length==3 && parts[2].equals("local-readiness")) {
            require(method,"GET"); sendJson(exchange,200,application.localReadiness()); return;
        }
        if (parts.length==3 && parts[2].equals("local-setup")) {
            require(method,"POST"); if (!body(exchange).isEmpty()) throw new IllegalArgumentException("VADER setup needs an empty object");
            sendJson(exchange,202,Map.of("id",application.setupLocalNlp().id())); return;
        }
        if (parts.length==3 && parts[2].equals("local-model-setup")) {
            require(method,"POST"); var value=body(exchange);
            if (!value.keySet().equals(Set.of("method")) || !(value.get("method") instanceof String selected))
                throw new IllegalArgumentException("Provide a local transformer method ID");
            sendJson(exchange,202,Map.of("id",application.setupLocalModel(selected).id())); return;
        }
        if (parts.length==3 && parts[2].equals("jobs")) {
            require(method,"GET"); sendJson(exchange,200,application.background().list().stream().map(job -> {
                Map<String,Object> summary=new LinkedHashMap<>(); summary.put("id",job.id()); summary.put("kind",job.kind());
                summary.put("runId",job.runId()); summary.put("createdAt",job.createdAt()); summary.put("state",job.state()); summary.put("progress",job.progress()); return summary;
            }).toList()); return;
        }
        if (parts.length>=4 && parts[2].equals("jobs")) {
            var job=application.background().find(parts[3]);
            if (parts.length==4) { require(method,"GET"); sendJson(exchange,200,job); return; }
            if (parts.length==5 && parts[4].equals("cancel")) {
                require(method,"POST"); body(exchange); application.background().cancel(job.id()); sendJson(exchange,202,Map.of()); return;
            }
            if (parts.length==5 && parts[4].equals("report")) {
                require(method,"GET"); if (job.result()==null) throw new IllegalStateException("No report yet");
                downloadHeader(exchange,job.id()+"-analysis.json"); sendJson(exchange,200,job.result()); return;
            }
        }
        if (parts.length==3 && parts[2].equals("settings")) {
            require(method,"GET"); sendJson(exchange,200,application.settings().names()); return;
        }
        if (parts.length==4 && parts[2].equals("settings")) {
            if (method.equals("GET")) sendJson(exchange,200,RunSpec.resolve(application.settings().read(parts[3]),application.configuration().config()).settings());
            else {
                require(method,"POST"); sendJson(exchange,200,application.settings().save(parts[3],body(exchange),application.configuration().config()));
            }
            return;
        }
        if (parts.length==3 && parts[2].equals("assets")) {
            if (method.equals("GET")) {
                String query=exchange.getRequestURI().getRawQuery();
                if (query==null) sendJson(exchange,200,application.assets().paths());
                else {
                    if (!query.startsWith("path=")) throw new IllegalArgumentException("Use path for asset selection");
                    String name=java.net.URLDecoder.decode(query.substring(5),StandardCharsets.UTF_8);
                    sendJson(exchange,200,Map.of("path",name,"text",application.assets().read(name)));
                }
            } else {
                require(method,"POST"); var value=body(exchange);
                if (!Set.of("path","text","save").containsAll(value.keySet()) || !(value.get("path") instanceof String name)
                        || !(value.get("text") instanceof String text) || !(value.get("save") instanceof Boolean save))
                    throw new IllegalArgumentException("Provide asset path, text and save boolean");
                application.assets().update(name,text,save); sendJson(exchange,200,Map.of("saved",save,"valid",true));
            }
            return;
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
                case "evaluate" -> { require(method,"POST"); sendJson(exchange,202,Map.of("id",application.evaluate(session.id(),body(exchange)).id())); return; }
                case "evaluate-llm" -> { require(method,"POST"); sendJson(exchange,202,Map.of("id",application.evaluateLlm(session.id(),body(exchange)).id())); return; }
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
        var snapshot=application.configuration(); EngineConfig settings=snapshot.config();
        List<Map<String,Object>> parties=new ArrayList<>();
        settings.parties().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var profile=entry.getValue(); parties.add(Map.of("id",entry.getKey().name(),"name",profile.displayName(),"ideology",profile.ideology(),"groundingAvailable",snapshot.excerpts().getExcerpts(entry.getKey()).size()));
        });
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("credentialHelp","Cloud providers need their own API key. Use Local LLM setup for explicit Ollama runtime and model downloads. See docs/configuration.md.");
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
        String text;
        try { text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Request body must be valid UTF-8"); }
        Object parsed=Json.parse(text);
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
