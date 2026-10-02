package engine.application;

import java.io.*;
import java.net.http.*;
import java.util.concurrent.*;
import engine.utils.HttpBodies;

/** Body deadlines and cancellation apply after headers, including a stalled download. */
final class RuntimeDownload {
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
        return HttpBodies.consume(input,deadline,check,reader::read);
    }
    static byte[] bounded(InputStream input,int limit) throws IOException {
        return HttpBodies.bounded(input,limit);
    }
    private RuntimeDownload() { }
}
