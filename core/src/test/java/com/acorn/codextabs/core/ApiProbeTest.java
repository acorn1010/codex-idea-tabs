package com.acorn.codextabs.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ApiProbeTest {
    @Test void checksMetadataWithoutPromptsAndNeverFollowsCredentialRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var redirected = new AtomicInteger(); var calls = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            calls.incrementAndGet(); assertEquals("GET", exchange.getRequestMethod());
            assertEquals("Bearer fixture-key", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"data\":[{\"id\":\"test-model\"}]}".getBytes();
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/redirect/models", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/secret/models");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/secret/models", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.createContext("/denied/models", exchange -> { exchange.sendResponseHeaders(401, -1); exchange.close(); });
        server.start();
        try {
            var entry = new ApiConnection(); entry.id = "fixture"; entry.name = "Fixture"; entry.hasKey = true; entry.models.add("test-model");
            String base = "http://127.0.0.1:" + server.getAddress().getPort(); entry.baseUrl = base + "/v1";
            var success = ApiProbe.check(entry, "fixture-key"); assertTrue(success.success()); assertTrue(success.message().contains("Models found")); assertEquals(1, calls.get());
            entry.baseUrl = base + "/redirect"; var redirect = ApiProbe.check(entry, "fixture-key"); assertFalse(redirect.success()); assertTrue(redirect.message().contains("HTTP 302")); assertEquals(0, redirected.get());
            entry.baseUrl = base + "/denied"; var denied = ApiProbe.check(entry, "fixture-key"); assertFalse(denied.success()); assertTrue(denied.message().contains("rejected the key"));
            var missingKey = ApiProbe.check(entry, ""); assertFalse(missingKey.success()); assertTrue(missingKey.message().contains("unavailable"));
            entry.baseUrl = base + "/not-supported"; assertFalse(ApiProbe.check(entry, "fixture-key").success());
            entry.baseUrl = base + "/v1"; entry.models.add("not-listed"); assertFalse(ApiProbe.check(entry, "fixture-key").success());
        } finally { server.stop(0); }
    }
}
