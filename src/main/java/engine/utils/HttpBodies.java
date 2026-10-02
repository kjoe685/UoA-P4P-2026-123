package engine.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.IntUnaryOperator;

/** Bounded UTF-8 responses and interruptible body deadlines, including stalls after headers. */
public final class HttpBodies {
    public static final int JSON_LIMIT = 16 * 1024 * 1024;
    private static final ExecutorService READERS=Executors.newCachedThreadPool(task -> {
        var thread=new Thread(task,"parliament-http-body"); thread.setDaemon(true); return thread;
    });
    public record Response(int statusCode,HttpHeaders headers,String body) { }
    public static final class ResponseTooLargeException extends IOException {
        private ResponseTooLargeException() { super("HTTP response exceeds size limit"); }
    }
    @FunctionalInterface public interface Reader<T,E extends Exception> { T read(InputStream input) throws E; }

    public static Response send(HttpClient client,HttpRequest request,Duration timeout,int limit)
            throws IOException,InterruptedException {
        return send(client,request,timeout,status -> status==200 ? limit : 0);
    }
    /** A zero status limit closes the body unread; other statuses receive their explicit byte cap. */
    public static Response send(HttpClient client,HttpRequest request,Duration timeout,IntUnaryOperator limits)
            throws IOException,InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("HTTP timeout must be positive");
        long deadline=System.nanoTime()+timeout.toNanos();
        checkInterrupted();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (var input=response.body()) {
            checkInterrupted();
            // Buffer only explicitly selected statuses; providers close all error bodies unread.
            int limit=limits.applyAsInt(response.statusCode());
            if (limit==0) return new Response(response.statusCode(),response.headers(),"");
            String body=consume(input,deadline,() -> { },stream ->
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bounded(stream,limit))).toString());
            return new Response(response.statusCode(),response.headers(),body);
        } catch (IOException e) {
            checkInterrupted();
            throw e;
        }
    }

    public static <T,E extends Exception> T consume(InputStream input,long deadline,Runnable check,Reader<T,E> reader)
            throws E,IOException,InterruptedException {
        check.run(); checkInterrupted();
        var finished=new CountDownLatch(1);
        var task=new FutureTask<T>(() -> reader.read(input)) {
            @Override public void run() { try { super.run(); } finally { finished.countDown(); } }
        };
        // Java17's HTTP stream clears interrupts inside its blocking queue read. Keep the
        // caller interruptible while a daemon reader consumes the body; close it on failure.
        READERS.execute(task); boolean completed=false;
        try {
            while (true) {
                check.run(); checkInterrupted();
                long remaining=deadline==Long.MAX_VALUE ? Long.MAX_VALUE : deadline-System.nanoTime();
                if (remaining<=0) throw new HttpTimeoutException("HTTP response timed out");
                try {
                    T result=task.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);
                    check.run(); checkInterrupted();
                    if (deadline!=Long.MAX_VALUE && System.nanoTime()>=deadline) throw new HttpTimeoutException("HTTP response timed out");
                    completed=true; return result;
                } catch (TimeoutException ignored) { /* Recheck deadline and job state. */ }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw e;
        } catch (ExecutionException e) {
            check.run(); checkInterrupted();
            if (deadline!=Long.MAX_VALUE && System.nanoTime()>=deadline) throw new HttpTimeoutException("HTTP response timed out");
            Throwable cause=e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException interruption) { Thread.currentThread().interrupt(); throw interruption; }
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            @SuppressWarnings("unchecked") E checked=(E)cause;
            throw checked;
        } finally {
            if (!completed) {
                try { input.close(); } catch (IOException ignored) { }
                task.cancel(true);
            }
            // Let file-writing callbacks close their resources before a cancelled operation
            // releases its caller. Bound cleanup even for a callback that ignores cancellation.
            boolean interrupted=Thread.interrupted();
            long cleanupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while (finished.getCount()!=0 && System.nanoTime()<cleanupDeadline) {
                try { finished.await(cleanupDeadline-System.nanoTime(),TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted=true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public static byte[] bounded(InputStream input,int limit) throws IOException {
        if (limit<1 || limit==Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid response size limit");
        byte[] value=input.readNBytes(limit+1);
        if (value.length>limit) throw new ResponseTooLargeException();
        return value;
    }
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
    private HttpBodies() { }
}
