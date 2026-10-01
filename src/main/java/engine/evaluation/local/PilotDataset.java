package engine.evaluation.local;

import java.net.URI;
import java.text.Normalizer;
import java.util.*;

/** Owner-supplied labels and source claims. No generated gold labels or inferred reviewer identity. */
public record PilotDataset(int schemaVersion,String evidenceType,Source source,List<Row> rows,Preparation preparation) {
    public PilotDataset(int schemaVersion,String evidenceType,Source source,List<Row> rows) { this(schemaVersion,evidenceType,source,rows,null); }
    public record Preparation(String sourceDatasetSha256,long seed,String groundingExclusionSha256,String policy) {
        public Preparation {
            if (sourceDatasetSha256==null || !sourceDatasetSha256.matches("[0-9a-f]{64}") || groundingExclusionSha256==null || !groundingExclusionSha256.matches("[0-9a-f]{64}")
                    || !"shuffle-items-then-source-debates-v1".equals(policy)) throw new IllegalArgumentException("Invalid pilot preparation provenance");
        }
    }
    public static final Set<String> SENTIMENT=Set.of("negative","neutral","positive"), STANCE=Set.of("support","oppose","unrelated");
    public record Source(String title,String doi,String url,String fileSha256) {
        public Source {
            text(title,300); if (doi==null || url==null || fileSha256==null || !fileSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid pilot source");
            if (!doi.isEmpty() && !doi.matches("10\\.\\d{4,9}/[^\\s]+")) throw new IllegalArgumentException("Invalid pilot DOI");
            if (!url.isEmpty()) {
                URI uri=URI.create(url);
                if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                    throw new IllegalArgumentException("Pilot source URL must be HTTPS without credentials or query");
            }
        }
    }
    public record Row(String id,String sourceDebateId,String sourceSpeechId,String sourceSpeechSha256,String text,
                      NlpRequest.Target target,String split,String sentiment,String stance,String reviewer) {
        public Row {
            PilotDataset.text(id,100); PilotDataset.text(sourceDebateId,300); PilotDataset.text(sourceSpeechId,150); PilotDataset.text(text,2000);
            if (sourceSpeechSha256==null || !sourceSpeechSha256.matches("[0-9a-f]{64}") || target==null)
                throw new IllegalArgumentException("Pilot row needs source speech hash and policy target");
            PilotDataset.text(target.id(),100); PilotDataset.text(target.proposition(),1000);
            if (split==null || !Set.of("","calibration","held_out").contains(split) || sentiment==null || stance==null || reviewer==null || reviewer.length()>100)
                throw new IllegalArgumentException("Invalid pilot review fields");
        }
        public Row prepared(String value) { return new Row(id,sourceDebateId,sourceSpeechId,sourceSpeechSha256,text,target,value,"","",""); }
    }
    public PilotDataset {
        if (schemaVersion!=1 || !Set.of("unreviewed","human_reviewed","synthetic_fixture").contains(evidenceType) || source==null || rows==null || rows.isEmpty() || rows.size()>1000)
            throw new IllegalArgumentException("Provide a version1 pilot with 1–1000 rows and explicit evidenceType");
        rows=List.copyOf(rows); boolean reviewed=!evidenceType.equals("unreviewed");
        if (!evidenceType.equals("synthetic_fixture") && (source.doi().isBlank() || source.url().isBlank())) throw new IllegalArgumentException("Genuine-source pilot needs a DOI and URL");
        if (reviewed && rows.size()<200) throw new IllegalArgumentException("Reviewed pilot needs at least 200 items");
        Set<String> ids=new HashSet<>(), texts=new HashSet<>(), calibration=new HashSet<>(), held=new HashSet<>();
        Map<String,String> debates=new HashMap<>(), hashes=new HashMap<>(), targets=new HashMap<>();
        for (var row:rows) {
            if (!ids.add(row.id()) || !texts.add(normalized(row.text()))) throw new IllegalArgumentException("Duplicate pilot ID or text");
            same(debates,row.sourceSpeechId(),row.sourceDebateId(),"A source speech belongs to multiple debates");
            same(hashes,row.sourceSpeechId(),row.sourceSpeechSha256(),"Inconsistent source speech hash");
            same(targets,row.target().id(),row.target().proposition(),"Inconsistent policy target");
            if (reviewed) {
                if (row.reviewer().isBlank() || !SENTIMENT.contains(row.sentiment()) || !STANCE.contains(row.stance()) || row.split().isBlank())
                    throw new IllegalArgumentException("Every reviewed item needs a reviewer pseudonym, split and both labels");
            } else if (!row.reviewer().isEmpty() || !row.sentiment().isEmpty() || !row.stance().isEmpty()) throw new IllegalArgumentException("Unreviewed preparation cannot contain gold labels or reviewers");
            if (row.split().equals("calibration")) calibration.add(row.sourceDebateId());
            if (row.split().equals("held_out")) held.add(row.sourceDebateId());
        }
        if (!Collections.disjoint(calibration,held) || reviewed && (calibration.isEmpty() || held.isEmpty()))
            throw new IllegalArgumentException("Calibration and held-out source debates must be disjoint and nonempty");
    }
    public static String normalized(String text) { return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("(?U)\\s+"," ").strip().toLowerCase(Locale.ROOT); }
    private static void text(String text,int max) { if (text==null || text.isBlank() || text.length()>max || text.indexOf('\0')>=0) throw new IllegalArgumentException("Invalid pilot text field"); }
    private static void same(Map<String,String> values,String key,String value,String error) { String old=values.putIfAbsent(key,value); if (old!=null && !old.equals(value)) throw new IllegalArgumentException(error); }
}
