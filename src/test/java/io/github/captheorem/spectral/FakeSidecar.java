package io.github.captheorem.spectral;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

final class FakeSidecar implements AutoCloseable {
    record Request(String method, String path, Map<String, String> query, String contentType, String body) {
    }

    record Reply(int status, String contentType, String body) {
        static Reply json(int status, String body) {
            return new Reply(status, "application/json", body);
        }

        static Reply text(int status, String body) {
            return new Reply(status, "text/plain; charset=utf-8", body);
        }

        static Reply empty(int status) {
            return new Reply(status, null, null);
        }
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    FakeSidecar(Function<Request, Reply> responder) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    parseQuery(exchange.getRequestURI().getRawQuery()),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body);
            requests.add(request);

            Reply reply = responder.apply(request);
            if (reply.body() == null) {
                exchange.sendResponseHeaders(reply.status(), -1);
                exchange.close();
                return;
            }

            byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reply.contentType());
            exchange.sendResponseHeaders(reply.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    SpectralClient client() {
        return SpectralClient.builder().addr(url()).timeout(Duration.ofSeconds(2)).build();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> query = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            query.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return query;
    }
}
