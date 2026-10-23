package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ClaudeClientTest {
    @Test void routesControlResponsesApprovalsAndDisconnectsWithoutHanging() throws Exception {
        var process = new FixtureProcess(); var events = new LinkedBlockingQueue<JsonObject>();
        try (var client = new ClaudeClient(process, events::offer, ignored -> {})) {
            var init = client.control(object("subtype", "initialize"));
            var sent = process.written.poll(2, TimeUnit.SECONDS); assertNotNull(sent);
            process.emit(object("type", "control_response", "response", object("request_id", text(sent, "request_id"), "subtype", "success", "response", object("models", new JsonArray()))));
            assertTrue(init.get(2, TimeUnit.SECONDS).has("models"));
            process.emit(object("type", "control_request", "request_id", "approval", "request", object("subtype", "can_use_tool", "tool_name", "Bash", "input", object("command", "pwd"))));
            assertEquals("approval", text(events.poll(2, TimeUnit.SECONDS), "request_id"));
            client.respond("approval", object("behavior", "deny", "message", "Declined"));
            var reply = process.written.poll(2, TimeUnit.SECONDS);
            assertEquals("deny", text(obj(obj(reply, "response"), "response"), "behavior"));
            var failed = client.control(object("subtype", "set_model", "model", "bad"));
            sent = process.written.poll(2, TimeUnit.SECONDS);
            process.emit(object("type", "control_response", "response", object("request_id", text(sent, "request_id"), "subtype", "error", "error", "Unknown model")));
            assertEquals("Unknown model", assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS)).getCause().getMessage());
            var pending = client.control(object("subtype", "interrupt"));
            process.finish();
            assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertFalse(client.isAlive());
            assertThrows(IllegalStateException.class, () -> client.write(object("type", "user")));
        }
    }
    @Test void reportsUnsupportedStartupAndAllowsRetry() throws Exception {
        var process = new FixtureProcess();
        try (var client = new ClaudeClient(process, ignored -> {}, ignored -> {})) {
            var init = client.initialize(50, TimeUnit.MILLISECONDS);
            var sent = process.written.poll(2, TimeUnit.SECONDS); assertNotNull(sent);
            assertEquals("initialize", text(obj(sent, "request"), "subtype"));
            var error = assertThrows(ExecutionException.class, () -> init.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("startup handshake"));
            assertTrue(error.getCause().getMessage().contains("current executable"));
            assertInstanceOf(TimeoutException.class, error.getCause().getCause());
            // A late response to a timed-out request must not complete the next attempt.
            var retry = client.initialize();
            process.emit(object("type", "control_response", "response", object("request_id", text(sent, "request_id"), "subtype", "success", "response", object("old", true))));
            sent = process.written.poll(2, TimeUnit.SECONDS); assertNotNull(sent);
            process.emit(object("type", "control_response", "response", object("request_id", text(sent, "request_id"), "subtype", "success", "response", object("models", new JsonArray()))));
            assertTrue(retry.get(2, TimeUnit.SECONDS).has("models"));
        }
    }
    @Test void blockedStdinDoesNotBlockStartupTimeoutOrDisconnect() throws Exception {
        var process = new FixtureProcess(); process.writesAllowed = new CountDownLatch(1);
        var disconnected = new LinkedBlockingQueue<String>();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor();
             var client = new ClaudeClient(process, ignored -> {}, disconnected::offer)) {
            var call = calls.submit(() -> client.initialize(50, TimeUnit.MILLISECONDS));
            try {
                var initialized = call.get(2, TimeUnit.SECONDS);
                var error = assertThrows(ExecutionException.class, () -> initialized.get(2, TimeUnit.SECONDS));
                assertTrue(error.getCause().getMessage().contains("startup handshake"));
                assertTrue(process.written.isEmpty(), "The fixture must not read stdin");
                client.close();
                assertNotNull(disconnected.poll(2, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> client.write(object("type", "user")));
            } finally { process.writesAllowed.countDown(); }
        }
    }
    @Test void findsNewestNvmVersionWithLimitedDesktopPath(@TempDir Path home) throws Exception {
        var versions = home.resolve(".nvm/versions/node");
        executable(versions.resolve("v22.3.0/bin/claude"));
        executable(versions.resolve("v9.11.0/bin/claude"));
        var newest = executable(versions.resolve("v22.14.0/bin/claude"));
        Files.createDirectories(versions.resolve("v24.0.0"));
        Files.createDirectories(versions.resolve("not-a-version"));
        assertEquals(newest.toString(), ClaudeClient.executable("claude", "relative/path", home, List.of()));
        Files.delete(newest);
        assertTrue(ClaudeClient.executable("", "", home, List.of()).endsWith("v22.3.0/bin/claude"));
    }
    @Test void keepsExplicitExecutablePathAndNativeInstallPriority(@TempDir Path home) throws Exception {
        var shell = executable(home.resolve("shell/bin/claude"));
        var nativeInstall = executable(home.resolve(".local/bin/claude"));
        executable(home.resolve(".nvm/versions/node/v22.14.0/bin/claude"));
        assertEquals("/custom/claude", ClaudeClient.executable("/custom/claude", "", home, List.of(nativeInstall)));
        assertEquals(shell.toString(), ClaudeClient.executable("", shell.getParent().toString(), home, List.of(nativeInstall)));
        assertEquals(nativeInstall.toString(), ClaudeClient.executable("", "", home, List.of(nativeInstall)));
    }
    @Test void passesPermissionRulesAsJsonForNativeAndWslLaunches() {
        String rule = "Bash(curl --output 'a file.png' https://isilwy.com/a,b.png)";
        String source = "  WebFetch(domain:isilwy.com)  \r\n\n" + rule + "\n" + rule;
        assertEquals(List.of("WebFetch(domain:isilwy.com)", rule), ClaudeClient.permissionRules(source));
        for (String distro : List.of("", "Ubuntu")) {
            var command = ClaudeClient.process("claude", "/tmp", distro, java.util.UUID.randomUUID().toString(), "", false, "", source).command();
            int index = command.indexOf("--settings"); assertTrue(index >= 0);
            var settings = JsonParser.parseString(command.get(index + 1)).getAsJsonObject();
            assertEquals(List.of("WebFetch(domain:isilwy.com)", rule), array(obj(settings, "permissions"), "allow").asList().stream().map(JsonElement::getAsString).toList());
            assertEquals(1, settings.size()); assertEquals(1, obj(settings, "permissions").size());
            assertTrue(command.contains("--permission-prompt-tool"));
            assertFalse(command.contains("--dangerously-skip-permissions"));
            assertFalse(command.contains("--setting-sources"));
            if (!distro.isBlank()) { assertEquals("cd -- \"$1\" && shift && exec \"$@\"", command.get(6)); }
        }
        assertFalse(ClaudeClient.process("claude", "/tmp", "", java.util.UUID.randomUUID().toString(), "", false).command().contains("--settings"));
    }
    @Test void rejectsMalformedRulesWithoutTreatingThemAsCliOptions() {
        for (String value : List.of("--dangerously-skip-permissions", "Bash(curl", "WebFetch(domain:example.com) extra", "Bash(curl\u0000host)")) {
            assertThrows(IllegalArgumentException.class, () -> ClaudeClient.permissionRules(value));
        }
        String rule = "Bash(echo '$(id)' \"quoted, text\")";
        var command = ClaudeClient.process("claude", "/tmp", "Ubuntu", java.util.UUID.randomUUID().toString(), "", false, "", rule).command();
        var settings = JsonParser.parseString(command.get(command.indexOf("--settings") + 1)).getAsJsonObject();
        assertEquals(rule, array(obj(settings, "permissions"), "allow").get(0).getAsString());
    }
    @Test void answersPostToolHooksBeforeForwardingTranscriptEvents() throws Exception {
        var process = new FixtureProcess(); var events = new LinkedBlockingQueue<JsonObject>();
        try (var client = new ClaudeClient(process, events::offer, ignored -> {})) {
            var initialized = client.initialize(true);
            var sent = process.written.poll(2, TimeUnit.SECONDS); assertNotNull(sent);
            assertEquals(ClaudeResponseHeaders.hooks(), obj(obj(sent, "request"), "hooks"));
            process.emit(object("type", "control_response", "response", object("request_id", text(sent, "request_id"), "subtype", "success", "response", object())));
            initialized.get(2, TimeUnit.SECONDS);
            process.emit(object("type", "control_request", "request_id", "filter", "request", object("subtype", "hook_callback", "callback_id", ClaudeResponseHeaders.CALLBACK,
                "input", object("hook_event_name", "PostToolUse", "tool_name", "WebFetch", "tool_response", object("headers", object("x-robots-tag", "noai", "Server", "test"), "body", "unchanged")))));
            var reply = process.written.poll(2, TimeUnit.SECONDS); assertNotNull(reply);
            var output = obj(obj(obj(obj(reply, "response"), "response"), "hookSpecificOutput"), "updatedToolOutput");
            assertEquals(object("Server", "test"), obj(output, "headers")); assertEquals("unchanged", text(output, "body"));
            assertTrue(events.isEmpty(), "SDK callbacks must not become chat permission cards");
        }
    }
    private static Path executable(Path path) throws IOException {
        Files.createDirectories(path.getParent()); Files.writeString(path, "#!/bin/sh\nexit 0\n");
        assertTrue(path.toFile().setExecutable(true)); return path;
    }
    private static final class FixtureProcess extends Process {
        final LinkedBlockingQueue<JsonObject> written = new LinkedBlockingQueue<>();
        final PipedInputStream output = new PipedInputStream();
        final PipedOutputStream server = new PipedOutputStream(output);
        volatile boolean alive = true;
        volatile CountDownLatch writesAllowed = new CountDownLatch(0);
        final OutputStream input = new ByteArrayOutputStream() {
            @Override public synchronized void flush() throws IOException {
                try { writesAllowed.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                String data = toString(StandardCharsets.UTF_8); reset();
                for (String line : data.lines().toList()) { written.offer(JsonParser.parseString(line).getAsJsonObject()); }
            }
        };
        FixtureProcess() throws IOException {}
        void emit(JsonObject event) throws IOException { server.write((GSON.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8)); server.flush(); }
        void finish() throws IOException { alive = false; server.close(); }
        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { if (alive) { throw new IllegalThreadStateException(); } return 0; }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { try { finish(); } catch (IOException ignored) {} }
    }
}
