package com.acorn.codextabs.core;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the installed Claude CLI against a fake local model API. No real model or account is used. */
@EnabledIfEnvironmentVariable(named = "CLAUDE_CONFIG_TEST_BINARY", matches = ".+")
class ClaudeHeaderTransportTest {
    @Test void installedCliKeepsDefaultSafetyRulesWithDownloadException(@TempDir Path root) throws Exception {
        var defaults = effectiveConfig(root, null);
        var settings = ClaudeClient.permissionSettings("", true);
        var effective = effectiveConfig(root, settings);
        for (String field : List.of("hard_deny", "soft_deny", "environment")) {
            assertEquals(defaults.get(field), effective.get(field), "Download policy changed " + field);
        }
        var allowed = array(effective, "allow").asList();
        assertTrue(allowed.containsAll(array(defaults, "allow").asList()));
        assertTrue(allowed.stream().anyMatch(value -> value.getAsString().startsWith("Web downloads:")));
        assertFalse(array(effective, "hard_deny").isEmpty(), "Actual safety boundaries must still exist");
    }
    private static JsonObject effectiveConfig(Path root, JsonObject settings) throws Exception {
        var command = new ArrayList<>(List.of(System.getenv("CLAUDE_CONFIG_TEST_BINARY")));
        if (settings != null) { command.addAll(List.of("--settings", GSON.toJson(settings))); }
        command.addAll(List.of("auto-mode", "config"));
        var output = Files.createTempFile(root, "config-", ".json");
        var errors = Files.createTempFile(root, "config-", ".err");
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
        builder.environment().put("CLAUDE_CONFIG_DIR", root.resolve("config").toString());
        builder.environment().put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Config inspection timed out");
            assertEquals(0, process.exitValue(), Files.readString(errors));
            return JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        } finally { process.destroyForcibly(); }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void actualNextModelRequestRespectsHeaderSetting(boolean filterHeaders, @TempDir Path root) throws Exception {
        var received = new CompletableFuture<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String command = "printf '%s\\n' 'HTTP/1.1 200 OK' 'X-Robots-Tag: noai, noimageai' 'TDM-Reservation: 1' 'X-Keep: retained' '' 'HEADER_BODY_RETAINED'";
        server.createContext("/", exchange -> {
            try {
                var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (exchange.getRequestURI().getPath().endsWith("count_tokens")) {
                    byte[] bytes = "{\"input_tokens\":20}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); return;
                }
                String result = null;
                for (var message : array(request, "messages")) {
                    var content = message.getAsJsonObject().get("content");
                    if (content == null || !content.isJsonArray()) { continue; }
                    for (var block : content.getAsJsonArray()) {
                        if (block.isJsonObject() && text(block.getAsJsonObject(), "type").equals("tool_result")) { result = GSON.toJson(block.getAsJsonObject().get("content")); }
                    }
                }
                boolean tool = result == null && GSON.toJson(request).contains("HEADER_FILTER_FIXTURE");
                if (result != null) { received.complete(result); }
                var block = tool ? object("type", "tool_use", "id", "toolu_header_fixture", "name", "Bash", "input", object("command", command)) : object("type", "text", "text", "Fixture complete");
                String stop = tool ? "tool_use" : "end_turn";
                var message = object("id", "msg_fixture", "type", "message", "role", "assistant", "model", "claude-sonnet-4-6", "content", List.of(block),
                    "stop_reason", stop, "stop_sequence", null, "usage", object("input_tokens", 20, "output_tokens", 10));
                if (!flag(request, "stream")) {
                    byte[] bytes = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); return;
                }
                var start = message.deepCopy(); start.add("content", new JsonArray()); start.add("stop_reason", JsonNull.INSTANCE);
                var emptyBlock = block.deepCopy(); if (tool) { emptyBlock.add("input", object()); } else { emptyBlock.addProperty("text", ""); }
                var delta = tool ? object("type", "input_json_delta", "partial_json", GSON.toJson(obj(block, "input"))) : object("type", "text_delta", "text", "Fixture complete");
                var events = List.of(object("type", "message_start", "message", start), object("type", "content_block_start", "index", 0, "content_block", emptyBlock),
                    object("type", "content_block_delta", "index", 0, "delta", delta), object("type", "content_block_stop", "index", 0),
                    object("type", "message_delta", "delta", object("stop_reason", stop, "stop_sequence", null), "usage", object("output_tokens", 10)), object("type", "message_stop"));
                var body = new StringBuilder();
                for (var event : events) { body.append("event: ").append(text(event, "type")).append("\ndata: ").append(GSON.toJson(event)).append("\n\n"); }
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Exception error) { received.completeExceptionally(error); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var builder = ClaudeClient.process(System.getenv("CLAUDE_CONFIG_TEST_BINARY"), root.toString(), "", UUID.randomUUID().toString(), "", false, "", "Bash(printf *)");
            builder.command().addAll(List.of("--permission-mode", "default", "--model", "claude-sonnet-4-6", "--no-session-persistence"));
            builder.environment().putAll(Map.of("CLAUDE_CONFIG_DIR", root.resolve("config").toString(), "ANTHROPIC_API_KEY", "local-fixture-key",
                "ANTHROPIC_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort(), "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1",
                "DISABLE_AUTOUPDATER", "1", "DISABLE_TELEMETRY", "1", "DISABLE_ERROR_REPORTING", "1"));
            builder.environment().remove("CLAUDECODE"); builder.environment().remove("ANTHROPIC_AUTH_TOKEN");
            var failure = new AtomicReference<String>();
            try (var client = new ClaudeClient(builder.start(), event -> {}, failure::set)) {
                client.initialize(filterHeaders).get(30, TimeUnit.SECONDS);
                client.write(object("type", "user", "message", object("role", "user", "content", "HEADER_FILTER_FIXTURE")));
                String result;
                try { result = received.get(45, TimeUnit.SECONDS); }
                catch (TimeoutException error) { throw new AssertionError("No tool result reached the local API. CLI status: " + failure.get(), error); }
                assertEquals(!filterHeaders, result.toLowerCase(Locale.ROOT).contains("x-robots-tag"), result);
                assertEquals(!filterHeaders, result.toLowerCase(Locale.ROOT).contains("tdm-reservation"), result);
                assertTrue(result.contains("X-Keep: retained"), result); assertTrue(result.contains("HEADER_BODY_RETAINED"), result);
            }
        } finally { server.stop(0); }
    }
}
