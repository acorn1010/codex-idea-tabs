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
import java.util.concurrent.atomic.AtomicInteger;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Codex transport against a loopback-only API. No account, network model, or shell tool is used. */
@EnabledIfEnvironmentVariable(named = "CODEX_RETRY_TEST_BINARY", matches = ".+")
class ApiRetryTransportTest {
    @TempDir Path root;

    @Test void emptyRetryKeepsCompletedToolOutputAndDoesNotDuplicateUserInput() throws Exception {
        var bodies = new CopyOnWriteArrayList<JsonObject>();
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            try (exchange) {
                bodies.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                int attempt = count.incrementAndGet();
                if (attempt == 2 || attempt == 3) {
                    byte[] body = GSON.toJson(object("error", object("message", "Fixture rate limit", "type", "rate_limit_error"))).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(429, body.length); exchange.getResponseBody().write(body); return;
                }
                JsonObject item = attempt == 1
                    ? object("type", "function_call", "id", "fc_once", "call_id", "call_once", "name", "record_once", "arguments", "{}")
                    : object("type", "message", "id", "msg_recovered", "role", "assistant", "content", new Object[]{object("type", "output_text", "text", "RECOVERED", "annotations", new JsonArray())});
                String body = "data: " + GSON.toJson(object("type", "response.output_item.done", "output_index", 0, "item", item)) + "\n\n"
                    + "data: " + GSON.toJson(object("type", "response.completed", "response", object("id", "resp_" + attempt, "status", "completed", "output", new Object[]{item}, "usage", object("input_tokens", 10, "output_tokens", 1, "total_tokens", 11)))) + "\n\n";
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var entry = new ApiConnection(); entry.id = "retry"; entry.name = "Fixture API";
            entry.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; entry.models.add("fixture");
            var process = new ProcessBuilder(Paths.command(System.getenv("CODEX_RETRY_TEST_BINARY"), "", false, entry.arguments())).directory(root.toFile());
            process.environment().put("CODEX_HOME", Files.createDirectory(root.resolve("codex")).toString());
            var events = new LinkedBlockingQueue<JsonObject>();
            try (var rpc = new RpcClient(process.start(), events::offer, ignored -> {})) {
                rpc.request("initialize", object("clientInfo", object("name", "retry_test", "version", "1"), "capabilities", object("experimentalApi", true))).get(20, TimeUnit.SECONDS);
                rpc.notify("initialized", object());
                var params = object("cwd", root.toString(), "ephemeral", true, "sandbox", "read-only", "approvalPolicy", "never",
                    "dynamicTools", new Object[]{object("type", "function", "name", "record_once", "description", "A harmless fixture tool", "inputSchema", object("type", "object", "properties", object()))},
                    "config", object("web_search", "disabled"));
                entry.configure(params, "fixture");
                var started = rpc.request("thread/start", params).get(20, TimeUnit.SECONDS);
                var chat = new Conversation("retry", root.toString()); chat.set("apiConnectionId", entry.id);
                chat.hydrate(obj(started, "thread"), chat.revision());
                rpc.request("turn/start", object("threadId", chat.get("threadId"), "input", new Object[]{object("type", "text", "text", "UNIQUE_USER_INPUT")})).get(20, TimeUnit.SECONDS);
                int toolCalls = 0, retries = 0;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                boolean completed = false;
                while (!completed && System.nanoTime() < deadline) {
                    var event = events.poll(5, TimeUnit.SECONDS);
                    if (event == null) { continue; }
                    String method = text(event, "method"); var data = obj(event, "params");
                    if (event.has("id")) {
                        assertEquals("item/tool/call", method, event.toString()); toolCalls++;
                        rpc.respond(event.get("id"), object("success", true, "contentItems", new Object[]{object("type", "inputText", "text", "TOOL_RESULT_ONCE")}));
                    }
                    if (!text(data, "threadId").equals(chat.get("threadId"))) { continue; }
                    chat.event(event);
                    if (method.equals("turn/completed")) {
                        var turn = obj(data, "turn");
                        if (text(turn, "status").equals("completed")) { completed = true; }
                        else {
                            var retry = chat.scheduleApiRetry(turn); assertFalse(retry.isEmpty(), turn.toString());
                            assertTrue(chat.beginApiRetry(retry)); retries++;
                            // Native service coverage tests the delays. This check isolates actual CLI history semantics.
                            rpc.request("turn/start", object("threadId", chat.get("threadId"), "input", new JsonArray())).get(20, TimeUnit.SECONDS);
                        }
                    }
                }
                assertTrue(completed); assertEquals(1, toolCalls); assertEquals(2, retries); assertEquals(4, count.get());
                var originalInput = array(bodies.get(1), "input");
                assertTrue(originalInput.toString().contains("TOOL_RESULT_ONCE"));
                for (var body : bodies.subList(2, bodies.size())) {
                    var input = array(body, "input");
                    assertEquals(originalInput, input, "Retries must preserve the complete request history");
                    assertEquals(1, input.asList().stream().filter(value -> value.toString().contains("UNIQUE_USER_INPUT")).count());
                }
                assertFalse(chat.busy()); assertEquals("", chat.get("error"));
            }
        } finally { server.stop(0); }
    }
}
