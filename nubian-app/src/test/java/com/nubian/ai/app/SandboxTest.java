package com.nubian.ai.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SandboxTest {

    @Test
    void write_file_posts_to_memory_files_endpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/agent/memory/files", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(readBody(exchange));
            sendJson(exchange, 200, "{\"ok\":true,\"path\":\"/workspace/x.md\",\"size\":2}");
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/agent";
            Sandbox sandbox = new Sandbox(baseUrl, 5000);

            Sandbox.Response response = sandbox.writeFile("/workspace/x.md", "ok");

            assertTrue(response.ok());
            assertEquals(200, response.status());
            assertEquals("/agent/memory/files", requestPath.get());
            assertNotNull(requestBody.get());
            assertTrue(requestBody.get().contains("\"path\":\"/workspace/x.md\""), requestBody.get());
            assertTrue(requestBody.get().contains("\"content\":\"ok\""), requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void evidence_bundle_reads_eyes_observe_not_removed_eyes_evidence() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestQuery = new AtomicReference<>();
        server.createContext("/agent/eyes/observe", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestQuery.set(exchange.getRequestURI().getRawQuery());
            sendJson(exchange, 200, """
                    {"ok":true,"running_windows":[{"title":"Google Chrome","bbox":[1,2,3,4]}]}
                    """);
        });
        server.createContext("/agent/eyes/evidence", exchange ->
                sendJson(exchange, 404, "{\"ok\":false,\"error\":\"not found\"}"));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/agent";
            Sandbox sandbox = new Sandbox(baseUrl, 5000);

            Sandbox.Response response = sandbox.evidenceBundle(null, null);

            assertTrue(response.ok());
            assertEquals(200, response.status());
            assertEquals("/agent/eyes/observe", requestPath.get());
            assertNotNull(requestQuery.get());
            assertTrue(requestQuery.get().contains("clipboard=true"), requestQuery.get());
            assertEquals("Google Chrome", response.json().path("running_windows").path(0).path("title").asText());
        } finally {
            server.stop(0);
        }
    }

    private static String readBody(HttpExchange exchange) throws java.io.IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
