package dev.servermodupdates;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpRetryTest {
    @Test
    void debugPathNeverContainsSensitiveQuery() {
        URI uri = URI.create("https://api.example.test/path/file?api_key=secret&token=hidden");
        assertEquals("/path/file", UpdateChecker.JdkTransport.safePath(uri));
        assertFalse(UpdateChecker.JdkTransport.safePath(uri).contains("secret"));
    }
    @Test
    void retries429OnceAndHonorsZeroRetryAfter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test", exchange -> {
            int call = calls.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            if (call == 1) exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(call == 1 ? 429 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Settings settings = new Settings("", false, false, false, 2, 1, 10, 5, 30);
            var transport = new UpdateChecker.JdkTransport(settings);
            transport.send("GET", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/test"), null, "");
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotRetry401() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            var transport = new UpdateChecker.JdkTransport(new Settings("key", false, false, false));
            UpdateChecker.ApiException error = assertThrows(UpdateChecker.ApiException.class,
                    () -> transport.send("GET", URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort() + "/auth"), null, "key"));
            assertEquals(401, error.status);
            assertEquals(1, calls.get());
            assertFalse(error.getMessage().contains("key"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTransient503ThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transient", exchange -> {
            int call = calls.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(call == 1 ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var transport = new UpdateChecker.JdkTransport(
                    new Settings("", false, false, false, 2, 1, 10, 5, 30));
            transport.send("GET", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/transient"), null, "");
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestTimeoutAndGlobalBudgetAreBounded() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(1_500);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // Expected when the client times out and closes the connection.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var transport = new UpdateChecker.JdkTransport(
                    new Settings("", false, false, false, 0, 1, 10, 1, 1));
            assertThrows(HttpTimeoutException.class, () -> transport.send("GET",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"), null, ""));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
