package engine.agent;

import java.net.URI;
import java.util.*;

/** Strict non-secret source metadata. Imported claims are structurally checked, not independently attested. */
final class CorpusProvenance {
    private CorpusProvenance() { }
    static Map<String,Object> validate(Object raw) {
        var value=object(raw,"Corpus metadata");
        if (!value.keySet().equals(Set.of("schemaVersion","source","selection")) || integer(value,"schemaVersion",1,1)!=1)
            throw new IllegalArgumentException("Unsupported corpus metadata schema");
        var source=object(value.get("source"),"Corpus source");
        if (!source.keySet().equals(Set.of("title","doi","version","license","fileName","fileId","url","bytes","md5","sha256","rows","chamber","country")))
            throw new IllegalArgumentException("Provide complete allowlisted corpus source metadata");
        for (String name:List.of("title","doi","version","license","fileName","url","chamber","country")) string(source,name,500);
        if (!string(source,"doi",100).matches("10\\.\\d{4,9}/[^\\s]+")) throw new IllegalArgumentException("Invalid source DOI");
        if (!string(source,"md5",32).matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid source MD5");
        sha(source,"sha256"); integer(source,"rows",1,10_000_000); integer(source,"fileId",1,Integer.MAX_VALUE); integer(source,"bytes",1,Integer.MAX_VALUE);
        URI uri;
        try { uri=URI.create((String)source.get("url")); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid source URL"); }
        if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null)
            throw new IllegalArgumentException("Source URL must be HTTPS without credentials, query or fragment");
        var selection=object(value.get("selection"),"Corpus selection");
        if (!selection.keySet().equals(Set.of("policy","perParty","candidateMultiplier","maxCharacters","minCharacters","minWords","chairExcluded","deduplication","excerptPolicy","script")))
            throw new IllegalArgumentException("Provide complete allowlisted selection metadata");
        for (String name:List.of("policy","deduplication","excerptPolicy","script")) string(selection,name,300);
        integer(selection,"perParty",1,1000); integer(selection,"candidateMultiplier",1,100); integer(selection,"maxCharacters",1,10000);
        integer(selection,"minCharacters",1,10000); integer(selection,"minWords",1,2000);
        if (!(selection.get("chairExcluded") instanceof Boolean)) throw new IllegalArgumentException("chairExcluded needs a boolean");
        return Map.of("schemaVersion",1,"source",Map.copyOf(source),"selection",Map.copyOf(selection));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object raw,String label) {
        if (!(raw instanceof Map<?,?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) throw new IllegalArgumentException(label+" must be an object");
        return (Map<String,Object>)map;
    }
    static String string(Map<String,Object> value,String name,int max) {
        if (!(value.get(name) instanceof String text) || text.isBlank() || text.length()>max || text.indexOf('\0')>=0)
            throw new IllegalArgumentException("Invalid corpus "+name);
        return text;
    }
    static int integer(Map<String,Object> value,String name,int min,int max) {
        if (!(value.get(name) instanceof Integer number) || number<min || number>max) throw new IllegalArgumentException("Invalid corpus "+name);
        return number;
    }
    static void sha(Map<String,Object> value,String name) {
        if (!string(value,name,64).matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid corpus "+name);
    }
}
