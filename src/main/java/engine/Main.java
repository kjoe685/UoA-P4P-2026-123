package engine;

import engine.application.DebateApplication;
import engine.utils.Json;
import engine.web.WebServer;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Guided and scriptable adapters for the same HTTP application operations as the browser. */
public final class Main {
    private final String base;
    private final HttpClient http=HttpClient.newHttpClient();
    private final Scanner input;
    private final PrintStream output;
    public Main(String base,Scanner input,PrintStream output) { this.base=base; this.input=input; this.output=output; }
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out,true,StandardCharsets.UTF_8));
        String base=System.getenv().getOrDefault("PARLIAMENT_URL","http://localhost:8080");
        Main cli=new Main(base,new Scanner(System.in),System.out);
        DebateApplication owned=null; HttpServer server=null;
        try {
            if (args.length==0) {
                try { cli.get("/api/config"); }
                catch (IOException e) {
                    if (!base.equals("http://localhost:8080")) throw new IllegalStateException("Start the configured server before opening the menu");
                    owned=DebateApplication.local(Path.of(".")); server=WebServer.start(8080,owned);
                    cli.output.println("Started the local backend. It will stop when this menu exits.");
                }
                cli.menu();
            } else cli.command(args);
        } catch (IOException e) { throw new IllegalStateException("Server unavailable. Start run.cmd serve (Windows) or sh run.sh serve (Unix)."); }
        finally {
            if (owned!=null) owned.close();
            if (server!=null) { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    public void command(String[] args) throws Exception {
        switch (args[0]) {
            case "config" -> output.println(get("/api/config"));
            case "runs" -> output.println(get("/api/debates"));
            case "start" -> { requireArgs(args,2); output.println(post("/api/debates",Files.readString(Path.of(args[1])))); }
            case "import" -> { requireArgs(args,2); output.println(post("/api/debates/import",Files.readString(Path.of(args[1])))); }
            case "watch" -> { requireArgs(args,2); watch(args[1]); }
            case "ruling" -> { requireArgs(args,3); output.println(post(runPath(args[1])+"/speaker",Json.write(Map.of("message",String.join(" ",Arrays.copyOfRange(args,2,args.length)))))); }
            case "cancel" -> { requireArgs(args,2); output.println(post(runPath(args[1])+"/adjourn","{}")); }
            case "transcript", "export" -> {
                requireArgs(args,2); String text=get(runPath(args[1])+(args[0].equals("transcript") ? "/transcript" : "/export"));
                if (args.length>2) Files.writeString(Path.of(args[2]),text,StandardCharsets.UTF_8); else output.println(text);
            }
            default -> throw new IllegalArgumentException("Commands: config, runs, start SETTINGS.json, import TRANSCRIPT.json, watch ID, ruling ID TEXT, cancel ID, transcript ID [FILE], export ID [FILE]");
        }
    }
    void menu() throws Exception {
        while (true) {
            output.println("\n1 Start sitting\n2 Saved sittings\n3 Watch sitting\n4 Chair ruling\n5 Adjourn\n6 Export JSON\n7 Export text\n8 Import transcript\n0 Exit");
            String choice=ask("Choice: ");
            if (choice.equals("0") || choice.isEmpty() && !input.hasNextLine()) return;
            try {
                switch (choice) {
                    case "1" -> { String response=post("/api/debates",Json.write(guidedSettings())); output.println(response);
                        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)Json.parse(response);
                        output.println("Watch this sitting with choice 3: "+result.get("id")); }
                    case "2" -> output.println(get("/api/debates"));
                    case "3" -> watch(ask("Sitting id: "));
                    case "4" -> output.println(post(runPath(ask("Sitting id: "))+"/speaker",Json.write(Map.of("message",ask("Ruling: ")))));
                    case "5" -> output.println(post(runPath(ask("Sitting id: "))+"/adjourn","{}"));
                    case "8" -> output.println(post("/api/debates/import",Files.readString(Path.of(ask("Transcript JSON file: ")))));
                    case "6", "7" -> {
                        String id=ask("Sitting id: "), file=ask("Output file (blank = print): ");
                        String text=get(runPath(id)+(choice.equals("6") ? "/transcript" : "/export"));
                        if (file.isBlank()) output.println(text); else { Files.writeString(Path.of(file),text); output.println("Saved "+file); }
                    }
                    default -> output.println("Choose a listed operation.");
                }
            } catch (IllegalArgumentException | IllegalStateException e) { output.println(e.getMessage()); }
        }
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> guidedSettings() throws Exception {
        Map<String,Object> config=(Map<String,Object>)Json.parse(get("/api/config"));
        Map<String,Object> models=(Map<String,Object>)config.get("models");
        output.println("Models: "+String.join(", ",models.keySet()));
        String preset=ask("Agent model (blank = "+config.get("agentModelPreset")+"): ");
        if (preset.isBlank()) preset=(String)config.get("agentModelPreset");
        String judge=ask("Judge model (blank = "+config.get("evaluatorModelPreset")+"): ");
        if (judge.isBlank()) judge=(String)config.get("evaluatorModelPreset");
        output.println("The demo uses fixed speeches and makes no model calls.");
        String topic=ask("Topics separated by | (blank = default): ");
        String rounds=ask("Rounds per topic (blank = "+config.get("defaultRounds")+"): ");
        List<Map<String,Object>> parties=(List<Map<String,Object>>)config.get("parties");
        output.println("Parties: "+parties.stream().map(value -> value.get("id")).toList());
        String selected=ask("Party IDs separated by commas (blank = all): ");
        Set<String> chosen=new HashSet<>(Arrays.asList(selected.split(",")));
        List<Map<String,Object>> members=new ArrayList<>();
        for (var party:parties) {
            String id=(String)party.get("id");
            if (!selected.isBlank() && chosen.stream().noneMatch(value -> value.trim().equals(id))) continue;
            String strategy=ask(id+" strategy NONE, TOPIC_DERAILMENT, STRAW_MAN, PROCEDURAL_MANIPULATION (blank = NONE): ");
            String memberModel=ask(id+" model override (blank = shared): ");
            Map<String,Object> member=new LinkedHashMap<>();
            member.put("party",id); member.put("strategy",strategy.isBlank() ? "NONE" : strategy);
            if (!memberModel.isBlank()) member.put("modelPreset",memberModel);
            members.add(member);
        }
        Map<String,Object> settings=new LinkedHashMap<>();
        settings.put("topics",Arrays.stream(topic.split("\\|")).map(String::trim).filter(value -> !value.isEmpty()).toList());
        settings.put("rounds",rounds.isBlank() ? config.get("defaultRounds") : Integer.parseInt(rounds));
        settings.put("members",members); settings.put("agentModelPreset",preset); settings.put("evaluatorModelPreset",judge);
        return settings;
    }
    private String ask(String prompt) { output.print(prompt); return input.hasNextLine() ? input.nextLine().trim() : ""; }
    private void watch(String id) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+runPath(id)+"/events")).GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode()!=200) { response.body().close(); throw new IllegalStateException("Cannot read sitting events"); }
        try (var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
            String line;
            while ((line=reader.readLine())!=null) if (line.startsWith("data: ")) output.println(line.substring(6));
        }
    }
    private String get(String path) throws Exception { return request(path,null); }
    private String post(String path,String json) throws Exception { return request(path,json); }
    private String request(String path,String json) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(30));
        if (json==null) builder.GET(); else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode()>=400) {
            Object parsed=Json.parse(response.body());
            throw new IllegalStateException(parsed instanceof Map<?,?> body && body.get("error") instanceof String text ? text : "Operation failed");
        }
        return response.body();
    }
    private static String runPath(String id) { return "/api/debates/"+java.net.URLEncoder.encode(id,StandardCharsets.UTF_8); }
    private static void requireArgs(String[] args,int count) { if (args.length<count) throw new IllegalArgumentException("Missing command argument"); }
}
