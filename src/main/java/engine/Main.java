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
            case "local" -> {
                requireArgs(args,2);
                switch (args[1]) {
                    case "status" -> output.println(get("/api/local-readiness"));
                    case "setup" -> output.println(post("/api/local-setup","{}"));
                    case "download" -> { requireArgs(args,3); output.println(post("/api/local-model-setup",Json.write(Map.of("method",args[2])))); }
                    default -> throw new IllegalArgumentException("local status | setup | download METHOD");
                }
            }
            case "evaluate" -> {
                requireArgs(args,2); Map<String,Object> settings=args.length>2 ? Map.of("methods",Arrays.asList(args[2].split(","))) : Map.of();
                output.println(post(runPath(args[1])+"/evaluate",Json.write(settings)));
            }
            case "jobs" -> output.println(get("/api/jobs"));
            case "evaluate-llm" -> {
                requireArgs(args,2); output.println(post(runPath(args[1])+"/evaluate-llm",Json.write(args.length>2 ? Map.of("modelPreset",args[2]) : Map.of())));
            }
            case "job" -> { requireArgs(args,2); output.println(get("/api/jobs/"+encode(args[1]))); }
            case "cancel-job" -> { requireArgs(args,2); output.println(post("/api/jobs/"+encode(args[1])+"/cancel","{}")); }
            case "report" -> {
                requireArgs(args,2); String report=get("/api/jobs/"+encode(args[1])+"/report");
                if (args.length>2) Files.writeString(Path.of(args[2]),report); else output.println(report);
            }
            case "settings" -> {
                requireArgs(args,2);
                switch (args[1]) {
                    case "list" -> output.println(get("/api/settings"));
                    case "show" -> { requireArgs(args,3); output.println(get("/api/settings/"+encode(args[2]))); }
                    case "save" -> { requireArgs(args,4); output.println(post("/api/settings/"+encode(args[2]),Files.readString(Path.of(args[3])))); }
                    default -> throw new IllegalArgumentException("settings list | show NAME | save NAME FILE");
                }
            }
            case "assets" -> {
                requireArgs(args,2);
                switch (args[1]) {
                    case "list" -> output.println(get("/api/assets"));
                    case "show" -> { requireArgs(args,3); output.println(get("/api/assets?path="+encode(args[2]))); }
                    case "validate", "save" -> {
                        requireArgs(args,4); output.println(post("/api/assets",Json.write(Map.of("path",args[2],"text",Files.readString(Path.of(args[3])),"save",args[1].equals("save")))));
                    }
                    default -> throw new IllegalArgumentException("assets list | show PATH | validate PATH FILE | save PATH FILE");
                }
            }
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
            default -> throw new IllegalArgumentException("Commands: config, settings, assets, local status/setup, evaluate ID [METHODS], jobs, job ID, cancel-job ID, report ID [FILE], runs, start SETTINGS.json, import TRANSCRIPT.json, watch ID, ruling ID TEXT, cancel ID, transcript ID [FILE], export ID [FILE]");
        }
    }
    void menu() throws Exception {
        while (true) {
            output.println("\n1 Start sitting\n2 Saved sittings\n3 Watch sitting\n4 Chair ruling\n5 Adjourn\n6 Export JSON\n7 Export text\n8 Import transcript\n9 Saved settings\n10 Advanced assets\n11 Start from saved settings\n12 Local evaluation setup\n13 Evaluate sitting\n14 Analysis jobs/reports\n15 LLM rubric evaluation\n0 Exit");
            String choice=ask("Choice: ");
            if (choice.equals("0") || choice.isEmpty() && !input.hasNextLine()) return;
            try {
                switch (choice) {
                    case "15" -> {
                        String id=ask("Sitting id: "), preset=ask("Evaluator model preset (blank = sitting's saved choice; cloud evaluation uses configured credentials): ");
                        output.println(post(runPath(id)+"/evaluate-llm",Json.write(preset.isBlank() ? Map.of() : Map.of("modelPreset",preset))));
                    }
                    case "12" -> {
                        output.println(get("/api/local-readiness"));
                        String selected=ask("Type setup for VADER dependencies, or cardiff-sentiment / deberta-stance for CPU dependencies and pinned weights (blank = return): ");
                        if (selected.equals("setup")) output.println(post("/api/local-setup","{}"));
                        else if (!selected.isBlank()) output.println(post("/api/local-model-setup",Json.write(Map.of("method",selected))));
                    }
                    case "13" -> {
                        String id=ask("Sitting id: "), methods=ask("Method IDs separated by commas (blank = defaults): ");
                        output.println(post(runPath(id)+"/evaluate",Json.write(methods.isBlank() ? Map.of() : Map.of("methods",Arrays.asList(methods.split(","))))));
                    }
                    case "14" -> {
                        output.println(get("/api/jobs")); String id=ask("Job id (blank = return): "); if (id.isBlank()) break;
                        output.println(get("/api/jobs/"+encode(id))); String action=ask("Action: report, cancel, or blank to return: ");
                        if (action.equals("cancel")) output.println(post("/api/jobs/"+encode(id)+"/cancel","{}"));
                        if (action.equals("report")) {
                            String file=ask("Output JSON file (blank = print): "), report=get("/api/jobs/"+encode(id)+"/report");
                            if (file.isBlank()) output.println(report); else Files.writeString(Path.of(file),report);
                        }
                    }
                    case "1" -> { String response=post("/api/debates",Json.write(guidedSettings())); output.println(response);
                        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)Json.parse(response);
                        output.println("Watch this sitting with choice 3: "+result.get("id")); }
                    case "2" -> output.println(get("/api/debates"));
                    case "3" -> watch(ask("Sitting id: "));
                    case "4" -> output.println(post(runPath(ask("Sitting id: "))+"/speaker",Json.write(Map.of("message",ask("Ruling: ")))));
                    case "5" -> output.println(post(runPath(ask("Sitting id: "))+"/adjourn","{}"));
                    case "8" -> output.println(post("/api/debates/import",Files.readString(Path.of(ask("Transcript JSON file: ")))));
                    case "9" -> {
                        output.println(get("/api/settings"));
                        String name=ask("Settings name (blank = return): ");
                        if (name.isBlank()) break;
                        String file=ask("Settings JSON file to save (blank = show existing): ");
                        output.println(file.isBlank() ? get("/api/settings/"+encode(name)) : post("/api/settings/"+encode(name),Files.readString(Path.of(file))));
                    }
                    case "10" -> {
                        output.println(get("/api/assets"));
                        String path=ask("Listed asset path (blank = return): ");
                        if (path.isBlank()) break;
                        output.println(get("/api/assets?path="+encode(path)));
                        String file=ask("Edited UTF-8 file (blank = return): ");
                        if (file.isBlank()) break;
                        String text=Files.readString(Path.of(file));
                        output.println(post("/api/assets",Json.write(Map.of("path",path,"text",text,"save",false))));
                        if (ask("Save validated contents? Type save: ").equals("save"))
                            output.println(post("/api/assets",Json.write(Map.of("path",path,"text",text,"save",true))));
                    }
                    case "11" -> {
                        output.println(get("/api/settings")); String name=ask("Settings name: ");
                        String overrides=ask("Overrides JSON file (blank = none): ");
                        @SuppressWarnings("unchecked") Map<String,Object> values=overrides.isBlank() ? new LinkedHashMap<>()
                                : new LinkedHashMap<>((Map<String,Object>)Json.parse(Files.readString(Path.of(overrides))));
                        values.put("settingsName",name); output.println(post("/api/debates",Json.write(values)));
                    }
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
        List<String> titles=Arrays.stream(topic.split("\\|")).map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (titles.isEmpty()) titles=List.of((String)config.get("defaultTopic"));
        List<Map<String,Object>> topics=new ArrayList<>();
        for (String title:titles) {
            String target=ask("Optional policy proposition for '"+title+"' (blank = none): ");
            Map<String,Object> value=new LinkedHashMap<>(); value.put("title",title); value.put("policyTarget",target.isBlank() ? null : target); topics.add(value);
        }
        settings.put("topics",topics);
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
    private static String encode(String value) { return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private static void requireArgs(String[] args,int count) { if (args.length<count) throw new IllegalArgumentException("Missing command argument"); }
}
