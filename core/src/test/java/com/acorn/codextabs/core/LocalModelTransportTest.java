package com.acorn.codextabs.core;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Check local settings through a real Codex process and an isolated Responses server. */
@EnabledIfEnvironmentVariable(named = "CODEX_RETRY_TEST_BINARY", matches = ".+")
class LocalModelTransportTest {
    @TempDir Path root;
    @Test void sendsBothGenericAndGlmModelsThroughTheSameLocalTransport() throws Exception {
        var requests = new LinkedBlockingQueue<JsonObject>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            try (exchange) {
                requests.offer(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                var item = object("type", "message", "id", "message", "role", "assistant", "content", new Object[]{object("type", "output_text", "text", "LOCAL_OK", "annotations", new JsonArray())});
                String body = "data: " + GSON.toJson(object("type", "response.output_item.done", "output_index", 0, "item", item)) + "\n\n"
                    + "data: " + GSON.toJson(object("type", "response.completed", "response", object("id", "response", "status", "completed", "output", new Object[]{item}))) + "\n\n";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var process = new ProcessBuilder(Paths.command(System.getenv("CODEX_RETRY_TEST_BINARY"), "", false,
                LocalModel.overrides("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"))).directory(root.toFile());
            process.environment().put("CODEX_HOME", Files.createDirectory(root.resolve("codex")).toString());
            var events = new LinkedBlockingQueue<JsonObject>();
            var backend = process.start();
            try (var rpc = new RpcClient(backend, events::offer, ignored -> {})) {
                rpc.request("initialize", object("clientInfo", object("name", "local_model_test", "version", "1"), "capabilities", object("experimentalApi", true))).get(20, TimeUnit.SECONDS);
                rpc.notify("initialized", object());
                for (String model : new String[]{"qwen-coder", LocalModel.MODEL}) {
                    int context = model.equals(LocalModel.MODEL) ? 98304 : 32768;
                    var params = object("cwd", root.toString(), "sandbox", "read-only", "approvalPolicy", "never");
                    LocalModel.apply(params, model, context, "auto");
                    var started = rpc.request("thread/start", params).get(20, TimeUnit.SECONDS);
                    assertEquals(model, text(started, "model"));
                    assertEquals(LocalModel.PROVIDER, text(started, "modelProvider"));
                    String thread = text(obj(started, "thread"), "id");
                    rpc.request("turn/start", object("threadId", thread, "input", new Object[]{object("type", "text", "text", "Reply LOCAL_OK without tools.")})).get(20, TimeUnit.SECONDS);
                    var request = requests.poll(20, TimeUnit.SECONDS); assertNotNull(request);
                    assertEquals(model, text(request, "model"));
                    assertFalse(request.has("service_tier"));
                    assertFalse(array(request, "tools").isEmpty());
                    for (var value : array(request, "tools")) {
                        var tool = value.getAsJsonObject();
                        if (text(tool, "type").equals("namespace")) {
                            for (var nested : array(tool, "tools")) { assertEquals("function", text(nested.getAsJsonObject(), "type")); }
                        } else { assertEquals("function", text(tool, "type")); }
                    }
                    boolean completed = false; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (!completed && System.nanoTime() < deadline) {
                        var event = events.poll(1, TimeUnit.SECONDS);
                        if (event == null) { continue; }
                        if (text(event, "method").equals("turn/completed")) {
                            assertEquals("completed", text(obj(obj(event, "params"), "turn"), "status"), event.toString()); completed = true;
                        }
                    }
                    assertTrue(completed, "Local turn did not finish");
                }
            } finally {
                if (!backend.waitFor(5, TimeUnit.SECONDS)) { backend.destroyForcibly(); assertTrue(backend.waitFor(5, TimeUnit.SECONDS)); }
            }
        } finally { server.stop(0); }
    }
}
