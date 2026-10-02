package engine.application;

import engine.agent.*;
import engine.evaluation.local.*;
import engine.utils.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;

/** Explicit pilot import/preparation/scoring; source claims and labels never go to classifiers. */
public final class PilotService {
    private final Path root, directory;
    private final JobService jobs;
    private final BiFunction<LocalEvaluationConfig,String,NlpClient> clients;
    private final LocalEvaluationService evaluation;
    public PilotService(Path root,JobService jobs,LocalEvaluationService evaluation,BiFunction<LocalEvaluationConfig,String,NlpClient> clients) {
        this.root=root; this.directory=root.resolve("runs/pilots"); this.jobs=jobs; this.evaluation=evaluation; this.clients=clients;
    }
    private Path path(String id) {
        if (id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid pilot id");
        return directory.resolve(id+".json");
    }
    public PilotDataset read(String id) {
        try { return decode(Json.parse(Files.readString(path(id)))); }
        catch (java.io.IOException e) { throw new NoSuchElementException("No saved pilot"); }
    }
    public List<Map<String,Object>> list() {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files=Files.list(directory)) {
            var result=new ArrayList<Map<String,Object>>();
            for (var file:files.filter(Files::isRegularFile).sorted().toList()) {
                String name=file.getFileName().toString();
                if (!name.matches("[0-9a-f-]{36}\\.json")) continue;
                String id=name.substring(0,36);
                try { result.add(summary(id,read(id))); } catch (RuntimeException e) { System.err.println("A saved pilot could not be read"); }
            }
            return List.copyOf(result);
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot list pilot data"); }
    }
    public Map<String,Object> importDataset(Map<String,Object> body) {
        var dataset=decode(body);
        if (!dataset.evidenceType().equals("unreviewed")) rejectGrounding(dataset,grounding());
        return save(dataset);
    }
    private static PilotDataset decode(Object raw) {
        if (!(raw instanceof Map<?,?> source)) throw new IllegalArgumentException("Pilot needs a JSON object");
        var value=new LinkedHashMap<Object,Object>(source); value.putIfAbsent("preparation",null);
        return Json.read(Json.write(value),PilotDataset.class);
    }
    private Map<String,Object> save(PilotDataset dataset) {
        String text=Json.write(dataset); if (text.length()>1_000_000) throw new IllegalArgumentException("Pilot exceeds 1000000 characters");
        String id=UUID.randomUUID().toString(); AtomicFiles.write(path(id),text); return summary(id,dataset);
    }
    private Map<String,Object> summary(String id,PilotDataset dataset) {
        return Map.of("id",id,"evidenceType",dataset.evidenceType(),"sourceTitle",dataset.source().title(),"items",dataset.rows().size(),
                "datasetSha256",Hashes.sha256(Json.writeCanonical(dataset)),"reviewStatus",dataset.evidenceType().equals("human_reviewed") ? "uploader-declared human labels" : dataset.evidenceType());
    }
    public Map<String,Object> prepare(String id,Map<String,Object> body) {
        if (!Set.of("seed").containsAll(body.keySet())) throw new IllegalArgumentException("Unknown pilot preparation setting");
        Object raw=body.getOrDefault("seed",123); if (!(raw instanceof Integer) && !(raw instanceof Long)) throw new IllegalArgumentException("seed needs an integer");
        long seed=((Number)raw).longValue(); var source=read(id);
        if (source.evidenceType().equals("synthetic_fixture")) throw new IllegalArgumentException("Synthetic fixtures cannot prepare a human-review pilot");
        var excluded=grounding(); var candidates=new ArrayList<>(source.rows().stream().filter(row -> !overlap(row,excluded)).toList());
        if (candidates.size()<200) throw new IllegalArgumentException("Need 200 distinct non-grounding items; do not invent or duplicate data");
        var random=new Random(seed); Collections.shuffle(candidates,random); var chosen=candidates.subList(0,200);
        var debates=new ArrayList<>(chosen.stream().map(PilotDataset.Row::sourceDebateId).distinct().sorted().toList());
        if (debates.size()<2) throw new IllegalArgumentException("Pilot needs at least two source debates");
        Collections.shuffle(debates,random); var calibration=new HashSet<>(debates.subList(0,Math.max(1,debates.size()/4)));
        var prepared=chosen.stream().map(row -> row.prepared(calibration.contains(row.sourceDebateId()) ? "calibration" : "held_out")).toList();
        return save(new PilotDataset(1,"unreviewed",source.source(),prepared,new PilotDataset.Preparation(
                Hashes.sha256(Json.writeCanonical(source)),seed,exclusionHash(excluded),"shuffle-items-then-source-debates-v1")));
    }
    private record Grounding(Set<String> ids,Set<String> hashes) { }
    private Grounding grounding() {
        String text;
        try { text=Files.readString(root.resolve(CorpusService.PATH)); } catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot read grounding corpus"); }
        var corpus=new HansardExcerpts(text); Set<String> ids=new TreeSet<>(), hashes=new TreeSet<>();
        if (corpus.provenance().isEmpty()) throw new IllegalArgumentException("Pilot exclusion requires grounding source provenance");
        String sha=(String)((Map<?,?>)corpus.provenance().get("source")).get("sha256");
        for (Party party:Party.values()) for (var entry:corpus.select(party,-1)) {
            ids.add(sha+":"+entry.get("sourceRow")); hashes.add((String)entry.get("fullTextSha256"));
        }
        return new Grounding(Set.copyOf(ids),Set.copyOf(hashes));
    }
    private static boolean overlap(PilotDataset.Row row,Grounding excluded) { return excluded.ids().contains(row.sourceSpeechId()) || excluded.hashes().contains(row.sourceSpeechSha256()); }
    private static void rejectGrounding(PilotDataset dataset,Grounding excluded) {
        if (dataset.rows().stream().anyMatch(row -> overlap(row,excluded))) throw new IllegalArgumentException("Pilot overlaps a grounding source speech");
    }
    private static String exclusionHash(Grounding excluded) {
        return Hashes.sha256(Json.writeCanonical(Map.of("ids",excluded.ids().stream().sorted().toList(),"hashes",excluded.hashes().stream().sorted().toList())));
    }
    public BackgroundJob evaluate(String id,Map<String,Object> body) {
        if (!Set.of("methods").containsAll(body.keySet())) throw new IllegalArgumentException("Unknown pilot evaluation setting");
        var dataset=read(id); if (dataset.evidenceType().equals("unreviewed")) throw new IllegalArgumentException("Complete real human review before scoring; preparation never invents labels");
        var excluded=grounding(); rejectGrounding(dataset,excluded);
        var defaults=evaluation.configuration(); List<String> methods=defaults.methods();
        if (body.containsKey("methods")) {
            if (!(body.get("methods") instanceof List<?> raw) || raw.stream().anyMatch(item -> !(item instanceof String))) throw new IllegalArgumentException("methods needs method IDs");
            methods=raw.stream().map(String.class::cast).toList();
        }
        var config=new LocalEvaluationConfig(1,defaults.endpoint(),defaults.timeoutSeconds(),defaults.batchSize(),methods);
        String settings;
        try { settings=Files.readString(root.resolve("nlp/config/models.json")); } catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot read local model settings"); }
        String datasetHash=Hashes.sha256(Json.writeCanonical(dataset)), configHash=Hashes.sha256(Json.writeCanonical(Map.of("evaluation",config,"localModelSettings",settings))),
                excludedHash=exclusionHash(excluded);
        return jobs.submit("pilot-evaluation",null,Map.of("pilotId",id,"dataset",dataset,"configuration",config,"localModelSettings",settings,"grounding",excluded),context -> {
            Map<String,List<PilotMetrics.Observation>> observations=new LinkedHashMap<>(); NlpClient client=null; boolean success=true;
            for (String method:config.methods()) {
                var values=new ArrayList<PilotMetrics.Observation>(); observations.put(method,values);
                for (var row:dataset.rows()) {
                    context.checkCancelled(); long start=System.nanoTime(); NlpResponse.Method analysis;
                    var targets=method.equals("deberta-stance") ? List.of(row.target()) : List.<NlpRequest.Target>of();
                    var request=new NlpRequest(2,List.of(method),List.of(new NlpRequest.Turn(row.id(),row.text(),targets)));
                    try {
                        if (client==null) client=clients.apply(config,settings);
                        var response=client.analyze(request); context.checkCancelled(); NlpResponseValidator.validate(request,response);
                        analysis=response.methods().get(0); LocalEvaluationService.validateProvenance(analysis,method,settings);
                    } catch (java.util.concurrent.CancellationException e) { throw e; }
                    catch (RuntimeException e) { context.checkCancelled(); analysis=new NlpResponse.Method(method,"failed","transport_or_response_failed",null,List.of(),(System.nanoTime()-start)/1_000_000d); }
                    String predicted="failed", error=analysis.error(); double latency=analysis.latencyMillis();
                    if (analysis.items().size()==1) {
                        var item=analysis.items().get(0); latency=item.latencyMillis(); error=item.error();
                        if (item.status().equals("ok") && item.chunks().size()==1) predicted=item.chunks().get(0).label();
                        else if (item.status().equals("ok")) error="pilot_unit_segmented";
                    }
                    if (predicted.equals("failed")) success=false;
                    values.add(new PilotMetrics.Observation(row.id(),row.split(),method.equals("deberta-stance") ? row.stance() : row.sentiment(),predicted,error,latency,analysis));
                    context.update("Pilot "+method+": "+values.size()+" / "+dataset.rows().size(),report(id,dataset,datasetHash,configHash,excludedHash,observations));
                }
            }
            return success;
        });
    }
    private static Map<String,Object> report(String id,PilotDataset dataset,String datasetHash,String configHash,String excludedHash,Map<String,List<PilotMetrics.Observation>> observations) {
        List<Map<String,Object>> methods=new ArrayList<>();
        for (var entry:observations.entrySet()) for (String split:List.of("calibration","held_out")) {
            var values=entry.getValue().stream().filter(item -> split.equals(item.split())).toList();
            var labels=entry.getKey().equals("deberta-stance") ? List.of("support","oppose","unrelated") : List.of("negative","neutral","positive");
            methods.add(Map.of("methodId",entry.getKey(),"split",split,"plannedItems",dataset.rows().stream().filter(row -> row.split().equals(split)).count(),
                    "metrics",PilotMetrics.calculate(values,labels),"evidence",values));
        }
        return Map.of("schemaVersion",1,"pilotId",id,"evidenceType",dataset.evidenceType(),"source",dataset.source(),"preparation",dataset.preparation()==null ? Map.of() : dataset.preparation(),"datasetSha256",datasetHash,"configurationSha256",configHash,
                "groundingExclusionSha256",excludedHash,"methods",methods,"interpretation",dataset.evidenceType().equals("synthetic_fixture")
                        ? "Synthetic fixture verifies software only; no human research accuracy or suitability claim"
                        : "Labels and source provenance are uploader declarations; no research pass threshold or model suitability is inferred");
    }
}
