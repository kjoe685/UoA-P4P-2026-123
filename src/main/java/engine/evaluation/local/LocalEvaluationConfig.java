package engine.evaluation.local;

import java.net.URI;
import java.util.*;

public record LocalEvaluationConfig(int schemaVersion,URI endpoint,int timeoutSeconds,int batchSize,List<String> methods) {
    public static final Set<String> METHODS=Set.of("vader-sentiment","cardiff-sentiment","deberta-stance");
    public LocalEvaluationConfig {
        methods=List.copyOf(methods);
        if (schemaVersion!=1 || timeoutSeconds<1 || timeoutSeconds>3600 || batchSize<1 || batchSize>100
                || methods.isEmpty() || !METHODS.containsAll(methods) || new HashSet<>(methods).size()!=methods.size())
            throw new IllegalArgumentException("Invalid local evaluation settings");
        if (endpoint==null || endpoint.getHost()==null || !"http".equals(endpoint.getScheme()) || endpoint.getUserInfo()!=null
                || endpoint.getQuery()!=null || endpoint.getFragment()!=null || !"/v1/analyze".equals(endpoint.getPath())
                || !Set.of("localhost","127.0.0.1","[::1]").contains(endpoint.getHost()))
            throw new IllegalArgumentException("Local evaluation needs a loopback HTTP analysis endpoint");
    }
}
