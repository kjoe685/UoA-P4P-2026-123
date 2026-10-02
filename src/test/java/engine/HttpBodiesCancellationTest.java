package engine;

import com.sun.net.httpserver.HttpServer;
import engine.utils.HttpBodies;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HttpBodiesCancellationTest {
    @Test void cancellationAfterARealBodyByteDoesNotDependOnTheStreamKeepingTheInterruptFlag() throws Exception {
        var read=new CountDownLatch(1); var finished=new CountDownLatch(1); var release=new CountDownLatch(1); var thread=new AtomicReference<Thread>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var worker=Executors.newSingleThreadExecutor();
        server.createContext("/",exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write('x'); exchange.getResponseBody().flush();
                try { release.await(8,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }); server.start();
        try {
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).build(),HttpResponse.BodyHandlers.ofInputStream());
            var future=worker.submit(() -> {
                thread.set(Thread.currentThread());
                try (var input=response.body()) {
                    assertThrows(InterruptedException.class,() -> HttpBodies.consume(input,Long.MAX_VALUE,() -> {},stream -> {
                        try { assertEquals('x',stream.read()); read.countDown(); return stream.read(); }
                        finally { finished.countDown(); }
                    }));
                    assertTrue(Thread.currentThread().isInterrupted());
                    assertEquals(0,finished.getCount(),"Reader resources close before the caller is released");
                }
                return "interrupted";
            });
            assertTrue(read.await(3,TimeUnit.SECONDS)); thread.get().interrupt();
            assertEquals("interrupted",future.get(3,TimeUnit.SECONDS));
            assertEquals("released",worker.submit(() -> "released").get(1,TimeUnit.SECONDS));
        } finally { release.countDown(); server.stop(0); worker.shutdownNow(); worker.awaitTermination(3,TimeUnit.SECONDS); }
    }
    @Test void guardedReaderPreservesCheckedExceptionsAndRefusesWorkAfterCancellation() throws Exception {
        class FixtureException extends Exception { }
        var expected=new FixtureException();
        assertSame(expected,assertThrows(FixtureException.class,() -> HttpBodies.consume(new ByteArrayInputStream(new byte[0]),Long.MAX_VALUE,() -> {},input -> { throw expected; })));
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class,() -> HttpBodies.consume(new ByteArrayInputStream(new byte[0]),Long.MAX_VALUE,() -> {},input -> { fail("No read after cancellation"); return null; }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
