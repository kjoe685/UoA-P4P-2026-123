package engine.application;

import engine.utils.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, durable jobs. Recovery retains partial reports and never reruns work. */
public final class JobService implements AutoCloseable {
    @FunctionalInterface public interface Work { boolean run(Context context) throws Exception; }
    public final class Context {
        private final String id;
        private Context(String id) { this.id=id; }
        public void checkCancelled() { if (Thread.currentThread().isInterrupted() || find(id).terminal()) throw new CancellationException(); }
        public void update(String progress,Object result) { checkCancelled(); transition(id,BackgroundJob.State.RUNNING,progress,result); }
    }
    private final Path root;
    private final Map<String,BackgroundJob> jobs=new ConcurrentHashMap<>();
    private final Map<String,Future<?>> futures=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),task -> {
        Thread thread=new Thread(task,"parliament-analysis"); thread.setDaemon(true); return thread;
    });
    public JobService(Path root) {
        this.root=root;
        if (!Files.isDirectory(root)) return;
        try (var directories=Files.list(root)) {
            for (var directory:directories.filter(Files::isDirectory).toList()) {
                try {
                    var job=Json.read(Files.readString(directory.resolve("job.json")),BackgroundJob.class);
                    if (!directory.getFileName().toString().equals(UUID.fromString(job.id()).toString())) continue;
                    jobs.put(job.id(),job);
                    if (!job.terminal()) transition(job.id(),BackgroundJob.State.INTERRUPTED,"Interrupted by backend restart; partial results retained",job.result());
                } catch (RuntimeException | IOException e) { System.err.println("A saved background job could not be read"); }
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot list background jobs"); }
    }
    public synchronized BackgroundJob submit(String kind,String runId,Object input,Work work) {
        if (executor.getActiveCount()+executor.getQueue().size()>=5) throw new IllegalStateException("Background job limit reached");
        String id=UUID.randomUUID().toString();
        var job=new BackgroundJob(id,kind,runId,System.currentTimeMillis(),null,BackgroundJob.State.QUEUED,"Waiting",null);
        AtomicFiles.write(root.resolve(id).resolve("input.json"),Json.write(input)); save(job); jobs.put(id,job);
        try {
            futures.put(id,executor.submit(() -> {
                if (find(id).terminal()) return;
                try {
                    transition(id,BackgroundJob.State.RUNNING,"Starting",null);
                    boolean success=work.run(new Context(id));
                    transition(id,success ? BackgroundJob.State.COMPLETE : BackgroundJob.State.FAILED,
                            success ? "Complete" : "Completed with method failures; inspect retained results",find(id).result());
                } catch (CancellationException | InterruptedException e) {
                    Thread.interrupted(); transition(id,BackgroundJob.State.CANCELLED,"Cancelled; partial results retained",find(id).result());
                } catch (Exception e) {
                    Thread.interrupted(); transition(id,BackgroundJob.State.FAILED,"Operation failed; check local setup and configuration",find(id).result());
                } finally {
                    synchronized (JobService.this) {
                        var current=find(id);
                        if (!current.terminal()) jobs.put(id,new BackgroundJob(id,current.kind(),current.runId(),current.createdAt(),
                                System.currentTimeMillis(),BackgroundJob.State.FAILED,"Could not save job progress; committed partial results remain on disk",current.result()));
                    }
                    futures.remove(id);
                }
            }));
        } catch (RejectedExecutionException e) { transition(id,BackgroundJob.State.FAILED,"Background queue unavailable",null); throw new IllegalStateException("Background job limit reached"); }
        return find(id);
    }
    public BackgroundJob find(String id) {
        var job=jobs.get(id); if (job==null) throw new NoSuchElementException("No saved background job"); return job;
    }
    public List<BackgroundJob> list() { return jobs.values().stream().sorted(Comparator.comparingLong(BackgroundJob::createdAt).reversed()).toList(); }
    private void save(BackgroundJob job) { AtomicFiles.write(root.resolve(job.id()).resolve("job.json"),Json.write(job)); }
    private synchronized void transition(String id,BackgroundJob.State state,String progress,Object result) {
        var previous=find(id); if (previous.terminal()) return;
        var next=new BackgroundJob(id,previous.kind(),previous.runId(),previous.createdAt(),
                state==BackgroundJob.State.QUEUED || state==BackgroundJob.State.RUNNING ? null : System.currentTimeMillis(),state,progress,result);
        save(next); jobs.put(id,next);
    }
    public synchronized void cancel(String id) {
        var previous=find(id); if (previous.terminal()) return;
        transition(id,BackgroundJob.State.CANCELLED,"Cancelled; partial results retained",previous.result());
        var future=futures.remove(id); if (future!=null) future.cancel(true); executor.purge();
    }
    @Override public void close() {
        jobs.values().stream().filter(job -> !job.terminal()).map(BackgroundJob::id).toList().forEach(this::cancel);
        executor.shutdownNow();
    }
}
