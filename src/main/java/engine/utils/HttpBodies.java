package engine.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** Bounded UTF-8 responses and interruptible body deadlines, including stalls after headers. */
public final class HttpBodies {
    public static final int JSON_LIMIT = 16 * 1024 * 1024;
    private static final ScheduledExecutorService WATCHER=Executors.newSingleThreadScheduledExecutor(task -> {
        var thread=new Thread(task,"parliament-http-deadlines"); thread.setDaemon(true); return thread;
    });
    public record Response(int statusCode,HttpHeaders headers,String body) { }
    public static final class ResponseTooLargeException extends IOException {
        private ResponseTooLargeException() { super("HTTP response exceeds size limit"); }
    }
    @FunctionalInterface public interface Reader<T,E extends Exception> { T read(InputStream input) throws E; }

    public static Response send(HttpClient client,HttpRequest request,Duration timeout,int limit)
            throws IOException,InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("HTTP timeout must be positive");
        long deadline=System.nanoTime()+timeout.toNanos();
        checkInterrupted();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (var input=response.body()) {
            checkInterrupted();
            // Error bodies can contain echoed private inputs. Close without reading or parsing them.
            if (response.statusCode()!=200) return new Response(response.statusCode(),response.headers(),"");
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
        Thread worker=Thread.currentThread();
        var guard=WATCHER.scheduleWithFixedDelay(() -> {
            if (worker.isInterrupted() || System.nanoTime()>=deadline)
                try { input.close(); } catch (IOException ignored) { }
        },0,100,TimeUnit.MILLISECONDS);
        try {
            check.run(); checkInterrupted();
            T result=reader.read(input);
            check.run(); checkInterrupted();
            if (System.nanoTime()>=deadline) throw new HttpTimeoutException("HTTP response timed out");
            return result;
        } catch (IOException e) {
            check.run(); checkInterrupted();
            if (System.nanoTime()>=deadline) throw new HttpTimeoutException("HTTP response timed out");
            throw e;
        } finally { guard.cancel(false); }
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
