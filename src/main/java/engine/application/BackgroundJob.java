package engine.application;

/** Operational progress and analysis reports never enter public debate evidence. */
public record BackgroundJob(String id,String kind,String runId,long createdAt,Long endedAt,State state,String progress,Object result) {
    public enum State { QUEUED,RUNNING,COMPLETE,FAILED,CANCELLED,INTERRUPTED }
    public BackgroundJob {
        if (id==null || !java.util.UUID.fromString(id).toString().equals(id) || kind==null || kind.isBlank()
                || state==null || progress==null || progress.isBlank() || createdAt<0
                || (state==State.QUEUED || state==State.RUNNING) != (endedAt==null))
            throw new IllegalArgumentException("Invalid saved background job");
    }
    public boolean terminal() { return state!=State.QUEUED && state!=State.RUNNING; }
}
