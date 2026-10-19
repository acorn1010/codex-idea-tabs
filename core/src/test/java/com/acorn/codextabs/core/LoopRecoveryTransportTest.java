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

/** Verify corrections reach actual model requests and chat history while the original stream never finishes. */
@EnabledIfEnvironmentVariable(named = "CODEX_RETRY_TEST_BINARY", matches = ".+")
class LoopRecoveryTransportTest {
    @TempDir Path root;

    @Test void interruptingEndlessStreamsDeliversTwoDistinctCorrections() throws Exception {
        String sample;
        try (var stream = getClass().getResourceAsStream("/loop-guard/repeated-send.txt")) {
            assertNotNull(stream); sample = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var bodies = new CopyOnWriteArrayList<JsonObject>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        server.createContext("/v1/responses", exchange -> {
            try (exchange) {
                bodies.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                String id = "message-" + bodies.size();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                var events = new JsonObject[]{
                    object("type", "response.created", "response", object("id", "response-" + id, "status", "in_progress", "output", new JsonArray())),
                    object("type", "response.output_item.added", "output_index", 0, "item", object("id", id, "type", "message", "role", "assistant", "content", new JsonArray())),
                    object("type", "response.content_part.added", "item_id", id, "output_index", 0, "content_index", 0, "part", object("type", "output_text", "text", "", "annotations", new JsonArray())),
                    object("type", "response.output_text.delta", "item_id", id, "output_index", 0, "content_index", 0, "delta", sample),
                };
                var output = exchange.getResponseBody();
                for (var event : events) { output.write(("data: " + GSON.toJson(event) + "\n\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }
                // This response cannot complete by itself. Steering alone would stay queued forever.
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100); output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                }
            } catch (java.io.IOException ignored) { /* Interrupt closes the model stream. */ }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        server.start();
        try {
            var entry = new ApiConnection(); entry.id = "loop"; entry.name = "Loop fixture";
            entry.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; entry.models.add("fixture");
            var process = new ProcessBuilder(Paths.command(System.getenv("CODEX_RETRY_TEST_BINARY"), "", false, entry.arguments())).directory(root.toFile());
            process.environment().put("CODEX_HOME", Files.createDirectory(root.resolve("codex")).toString());
            var notifications = new LinkedBlockingQueue<JsonObject>();
            try (var rpc = new RpcClient(process.start(), notifications::offer, ignored -> {})) {
                rpc.request("initialize", object("clientInfo", object("name", "loop_recovery_test", "version", "1"), "capabilities", object("experimentalApi", true))).get(20, TimeUnit.SECONDS);
                rpc.notify("initialized", object());
                var params = object("cwd", root.toString(), "ephemeral", true, "sandbox", "read-only", "approvalPolicy", "never", "config", object("web_search", "disabled"));
                entry.configure(params, "fixture");
                var started = rpc.request("thread/start", params).get(20, TimeUnit.SECONDS);
                var chat = new Conversation("loop", root.toString()); chat.set("apiConnectionId", entry.id); chat.hydrate(obj(started, "thread"), chat.revision());
                rpc.request("turn/start", object("threadId", chat.get("threadId"), "input", new Object[]{object("type", "text", "text", "ORIGINAL_REQUEST")})).get(20, TimeUnit.SECONDS);
                var corrections = new ArrayList<String>(); var pending = object(); boolean stopped = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
                while (!stopped && System.nanoTime() < deadline) {
                    var event = notifications.poll(5, TimeUnit.SECONDS);
                    if (event == null) { continue; }
                    assertFalse(event.has("id"), "This fixture must not execute a tool");
                    var data = obj(event, "params");
                    if (!text(data, "threadId").equals(chat.get("threadId"))) { continue; }
                    chat.event(event);
                    var action = chat.takeLocalLoopAction();
                    if (!action.isEmpty()) {
                        pending = action;
                        rpc.request("turn/interrupt", object("threadId", chat.get("threadId"), "turnId", chat.get("turnId"))).get(20, TimeUnit.SECONDS);
                    }
                    if (text(event, "method").equals("turn/completed") && !pending.isEmpty()) {
                        if (flag(pending, "stop")) { stopped = true; }
                        else {
                            String message = text(pending, "message"); corrections.add(message);
                            rpc.request("turn/start", object("threadId", chat.get("threadId"), "input", new Object[]{object("type", "text", "text", message)})).get(20, TimeUnit.SECONDS);
                        }
                        pending = object();
                    }
                }
                assertTrue(stopped); assertEquals(3, bodies.size()); assertEquals(2, new HashSet<>(corrections).size());
                for (int index = 0; index < corrections.size(); index++) {
                    String correction = corrections.get(index);
                    assertTrue(array(bodies.get(index + 1), "input").toString().contains(GSON.toJson(correction)));
                    assertEquals(1, chat.items().asList().stream().filter(item -> item.toString().contains(GSON.toJson(correction))).count(), "Each delivered correction must appear once in chat history");
                }
                assertFalse(chat.busy()); assertTrue(flag(obj(chat.snapshot(), "localLoopGuard"), "stopped"));
            }
        } finally { server.stop(0); workers.shutdownNow(); }
    }
}
