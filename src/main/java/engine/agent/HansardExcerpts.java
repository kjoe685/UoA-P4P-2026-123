package engine.agent;

import engine.utils.Json;
import engine.utils.Hashes;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;

/** Validated owner-side corpus, frozen at run start. Only excerpt text enters a prompt. */
public final class HansardExcerpts {
    private static final Set<String> RECORD_FIELDS=Set.of("speaker","date","text","textSha256","sourceRow","sourceParty",
            "agenda","speechNumber","fullTextSha256","charStart","charEnd");
    private final Map<Party, List<String>> excerptsByParty;
    private final Map<Party,List<Map<String,Object>>> recordsByParty;
    private final Map<String,Object> provenance;

    public HansardExcerpts(String json) {
        if (json==null || json.length()>1_000_000) throw new IllegalArgumentException("Corpus exceeds 1000000 characters");
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("Hansard must be an object");
        Set<String> keys=new HashSet<>(Set.of("_corpus"));
        for (Party party:Party.values()) keys.add(party.getDisplayName());
        if (!keys.containsAll(root.keySet())) throw new IllegalArgumentException("Unknown corpus party or metadata field");
        provenance=root.containsKey("_corpus") ? CorpusProvenance.validate(root.get("_corpus")) : Map.of();
        Map<Party, List<String>> result = new EnumMap<>(Party.class);
        Map<Party,List<Map<String,Object>>> records=new EnumMap<>(Party.class);
        Set<String> seen=new HashSet<>(); Set<Integer> rows=new HashSet<>();
        for (Party party : Party.values()) {
            Object raw = root.get(party.getDisplayName());
            if (!(raw instanceof List<?> entries) || entries.size()>1000) {
                throw new IllegalArgumentException("Missing Hansard sample for " + party);
            }
            List<Map<String,Object>> items=new ArrayList<>();
            for (Object item:entries) {
                var entry=CorpusProvenance.object(item,"Hansard excerpt");
                if (!RECORD_FIELDS.containsAll(entry.keySet())) throw new IllegalArgumentException("Unknown Hansard excerpt field");
                String text=CorpusProvenance.string(entry,"text",10000);
                CorpusProvenance.string(entry,"speaker",300); String date=CorpusProvenance.string(entry,"date",10);
                try { if (!LocalDate.parse(date).toString().equals(date)) throw new IllegalArgumentException(); }
                catch (RuntimeException e) { throw new IllegalArgumentException("Excerpt date must be YYYY-MM-DD"); }
                String normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("(?U)\\s+"," ").strip().toLowerCase(Locale.ROOT);
                if (!seen.add(normalized)) throw new IllegalArgumentException("Duplicate grounding text across the corpus");
                Map<String,Object> metadata=new LinkedHashMap<>(entry); String hash=Hashes.sha256(text);
                if (entry.containsKey("textSha256") && !hash.equals(entry.get("textSha256"))) throw new IllegalArgumentException("Excerpt text hash does not match its contents");
                metadata.put("textSha256",hash);
                if (!provenance.isEmpty()) {
                    if (!entry.keySet().equals(RECORD_FIELDS)) throw new IllegalArgumentException("Provenance corpus needs complete excerpt metadata");
                    if (!party.getDisplayName().equals(CorpusProvenance.string(entry,"sourceParty",100))) throw new IllegalArgumentException("Excerpt source party mismatch");
                    int maxRows=CorpusProvenance.integer(CorpusProvenance.object(provenance.get("source"),"source"),"rows",1,10_000_000);
                    int row=CorpusProvenance.integer(entry,"sourceRow",1,maxRows);
                    if (!rows.add(row)) throw new IllegalArgumentException("Duplicate source row");
                    CorpusProvenance.string(entry,"agenda",2000); CorpusProvenance.integer(entry,"speechNumber",1,100000);
                    CorpusProvenance.sha(entry,"fullTextSha256");
                    int start=CorpusProvenance.integer(entry,"charStart",0,10_000_000), end=CorpusProvenance.integer(entry,"charEnd",1,10_000_000);
                    if (end-start!=text.codePointCount(0,text.length())) throw new IllegalArgumentException("Excerpt character span does not match its text");
                }
                items.add(Collections.unmodifiableMap(metadata));
            }
            if (!provenance.isEmpty()) {
                var selection=CorpusProvenance.object(provenance.get("selection"),"selection");
                if (items.size()!=CorpusProvenance.integer(selection,"perParty",1,1000)) throw new IllegalArgumentException("Excerpt count differs from declared selection");
                int min=CorpusProvenance.integer(selection,"minCharacters",1,10000), max=CorpusProvenance.integer(selection,"maxCharacters",1,10000);
                int words=CorpusProvenance.integer(selection,"minWords",1,2000);
                for (var item:items) {
                    String text=(String)item.get("text"); int length=text.codePointCount(0,text.length());
                    if (length<min || length>max || text.strip().split("(?U)\\s+").length<words)
                        throw new IllegalArgumentException("Excerpt does not meet declared selection bounds");
                }
            }
            result.put(party,items.stream().map(item -> (String)item.get("text")).toList());
            records.put(party,List.copyOf(items));
        }
        excerptsByParty = Map.copyOf(result);
        recordsByParty=Map.copyOf(records);
    }

    public List<String> getExcerpts(Party party) { return excerptsByParty.get(party); }
    public Map<String,Object> provenance() { return provenance; }
    public List<Map<String,Object>> select(Party party,int count) {
        var available=recordsByParty.get(party); int chosen=count==-1 ? available.size() : count;
        if (chosen<0 || chosen>available.size()) throw new IllegalArgumentException("Requested "+chosen+" grounding excerpts for "+party+"; corpus has "+available.size());
        return List.copyOf(available.subList(0,chosen));
    }
}
