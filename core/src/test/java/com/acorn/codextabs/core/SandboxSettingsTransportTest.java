package com.acorn.codextabs.core;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Check the real Codex sandbox policy with a local model fixture and an empty Codex home. */
@EnabledIfEnvironmentVariable(named = "CODEX_RETRY_TEST_BINARY", matches = ".+")
class SandboxSettingsTransportTest {
    @TempDir Path root;
    @Test void addsFoldersKeepsThemForContinuationsAndRemovesThemOnResume() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                var item = object("type", "message", "id", "message", "role", "assistant", "content", new Object[]{object("type", "output_text", "text", "OK", "annotations", new JsonArray())});
                String body = "data: " + GSON.toJson(object("type", "response.output_item.done", "output_index", 0, "item", item)) + "\n\n"
                    + "data: " + GSON.toJson(object("type", "response.completed", "response", object("id", "response", "status", "completed", "output", new Object[]{item}))) + "\n\n";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            String cwd = Files.createDirectory(root.resolve("checkout")).toString();
            String extra = Files.createDirectory(root.resolve("extra checkout")).toString();
            var entry = new ApiConnection(); entry.id = "folders"; entry.name = "Folders fixture";
            entry.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; entry.models.add("fixture");
            var process = new ProcessBuilder(Paths.command(System.getenv("CODEX_RETRY_TEST_BINARY"), "", false, entry.arguments())).directory(root.toFile());
            process.environment().put("CODEX_HOME", Files.createDirectory(root.resolve("codex")).toString());
            var events = new LinkedBlockingQueue<JsonObject>();
            var backend = process.start();
            try (var rpc = new RpcClient(backend, events::offer, ignored -> {})) {
                rpc.request("initialize", object("clientInfo", object("name", "folders_test", "version", "1"), "capabilities", object("experimentalApi", true))).get(20, TimeUnit.SECONDS);
                rpc.notify("initialized", object());
                var params = object("cwd", cwd, "sandbox", "workspace-write", "approvalPolicy", "never", "config", object("web_search", "disabled"));
                entry.configure(params, "fixture"); SandboxSettings.configureThread(params, List.of(extra));
                var started = rpc.request("thread/start", params).get(20, TimeUnit.SECONDS);
                assertTrue(array(obj(started, "sandbox"), "writableRoots").contains(new JsonPrimitive(extra)), started.toString());
                String thread = text(obj(started, "thread"), "id");
                for (int attempt = 0; attempt < 2; attempt++) {
                    var turn = object("threadId", thread, "input", new Object[]{object("type", "text", "text", attempt == 0 ? "Initial request" : "Loop guard correction")});
                    if (attempt == 0) { turn.add("sandboxPolicy", SandboxSettings.policy("ask", cwd, List.of(extra))); }
                    rpc.request("turn/start", turn).get(20, TimeUnit.SECONDS);
                    boolean completed = false; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (!completed && System.nanoTime() < deadline) {
                        var event = events.poll(1, TimeUnit.SECONDS);
                        if (event == null) { continue; }
                        assertFalse(event.has("id"), "The fixture must not request tools or approval");
                        if (text(event, "method").equals("turn/completed")) {
                            assertEquals("completed", text(obj(obj(event, "params"), "turn"), "status"), event.toString()); completed = true;
                        }
                    }
                    assertTrue(completed);
                }
                rpc.request("thread/unsubscribe", object("threadId", thread)).get(20, TimeUnit.SECONDS);
                var resumed = rpc.request("thread/resume", object("threadId", thread)).get(20, TimeUnit.SECONDS);
                assertTrue(array(obj(resumed, "sandbox"), "writableRoots").contains(new JsonPrimitive(extra)), resumed.toString());
                rpc.request("thread/unsubscribe", object("threadId", thread)).get(20, TimeUnit.SECONDS);
                var resume = object("threadId", thread); SandboxSettings.configureThread(resume, List.of());
                resumed = rpc.request("thread/resume", resume).get(20, TimeUnit.SECONDS);
                assertFalse(array(obj(resumed, "sandbox"), "writableRoots").contains(new JsonPrimitive(extra)), resumed.toString());
                var read = params.deepCopy(); read.addProperty("sandbox", "read-only");
                var readOnly = rpc.request("thread/start", read).get(20, TimeUnit.SECONDS);
                assertEquals("readOnly", text(obj(readOnly, "sandbox"), "type"), readOnly.toString());
            } finally {
                // Wait for the fixture process before JUnit removes its temporary Codex home.
                if (!backend.waitFor(5, TimeUnit.SECONDS)) {
                    backend.destroyForcibly();
                    assertTrue(backend.waitFor(5, TimeUnit.SECONDS), "The test Codex process did not stop");
                }
            }
        } finally { server.stop(0); }
    }
}
