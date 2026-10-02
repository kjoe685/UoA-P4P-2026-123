package engine.application;

import java.io.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Body deadlines and cancellation apply after headers, including a stalled download. */
final class RuntimeDownload {
    private static final ScheduledExecutorService WATCHER=Executors.newSingleThreadScheduledExecutor(task -> {
        var thread=new Thread(task,"parliament-download-deadlines"); thread.setDaemon(true); return thread;
    });
    @FunctionalInterface interface Reader<T> { T read(InputStream input) throws Exception; }
    static <T> T read(HttpClient client,HttpRequest request,int timeoutSeconds,Runnable check,Reader<T> reader) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (var input=response.body()) {
            if (response.statusCode()!=200) throw new IOException("Local operation returned an unsuccessful HTTP status");
            return consume(input,deadline,check,reader);
        }
    }
    static <T> T consume(InputStream input,long deadline,Runnable check,Reader<T> reader) throws Exception {
        Thread worker=Thread.currentThread();
        var guard=WATCHER.scheduleWithFixedDelay(() -> {
            if (worker.isInterrupted() || System.nanoTime()>=deadline) try { input.close(); } catch (IOException ignored) { }
        },0,100,TimeUnit.MILLISECONDS);
        try {
            check.run(); T result=reader.read(input); check.run();
            if (System.nanoTime()>=deadline) throw new IOException("Local download timed out");
            return result;
        } finally { guard.cancel(false); }
    }
    static byte[] bounded(InputStream input,int limit) throws IOException {
        byte[] value=input.readNBytes(limit+1);
        if (value.length>limit) throw new IOException("Local response exceeds size limit");
        return value;
    }
    private RuntimeDownload() { }
}
