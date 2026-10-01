package engine.application;

import engine.agent.*;
import engine.utils.Hashes;
import java.util.*;

/** One validated, atomic corpus operation for browser, guided menu and commands. */
public final class CorpusService {
    public static final String PATH="data/hansard/excerpts.json";
    private final AssetService assets;
    public CorpusService(AssetService assets) { this.assets=assets; }
    public Map<String,Object> status() { return describe(assets.read(PATH)); }
    public Map<String,Object> importCorpus(Map<String,Object> body) {
        if (!Set.of("text","save","expectedSha256").containsAll(body.keySet()) || !(body.get("text") instanceof String text)
                || !(body.get("save") instanceof Boolean save)) throw new IllegalArgumentException("Provide corpus text and save boolean");
        if (body.containsKey("expectedSha256") && !(body.get("expectedSha256") instanceof String)) throw new IllegalArgumentException("expectedSha256 needs a string");
        var result=new LinkedHashMap<>(describe(text));
        if (((Map<?,?>)result.get("provenance")).isEmpty()) throw new IllegalArgumentException("Corpus import needs source and selection provenance; legacy assets remain readable");
        synchronized (assets) {
            String current=Hashes.sha256(assets.read(PATH));
            if (save && !current.equals(body.get("expectedSha256"))) throw new IllegalArgumentException("Corpus changed since validation; validate again before importing");
            assets.update(PATH,text,save);
            result.put("previousSha256",current); result.put("saved",save); result.put("valid",true);
        }
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,Object> describe(String text) {
        var corpus=new HansardExcerpts(text); List<Map<String,Object>> parties=new ArrayList<>(); int total=0;
        for (Party party:Party.values()) {
            var entries=corpus.select(party,-1); total+=entries.size();
            var dates=entries.stream().map(entry -> (String)entry.get("date")).sorted().toList();
            parties.add(Map.of("id",party.name(),"name",party.getDisplayName(),"excerpts",entries.size(),
                    "speakers",entries.stream().map(entry -> entry.get("speaker")).distinct().count(),
                    "earliestDate",dates.isEmpty() ? "" : dates.get(0),"latestDate",dates.isEmpty() ? "" : dates.get(dates.size()-1)));
        }
        return Map.of("sha256",Hashes.sha256(text),"totalExcerpts",total,"parties",parties,"provenance",corpus.provenance(),
                "validation","Structure, text hashes, unique text/source rows and metadata; source claims require independent verification");
    }
}
