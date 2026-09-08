package com.acorn.codextabs;

import com.acorn.codextabs.core.ContextReport;
import com.acorn.codextabs.core.Paths;
import com.google.gson.*;
import com.intellij.openapi.util.SystemInfo;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static com.acorn.codextabs.core.Json.*;

/** On-demand diagnostics use their own read tasks and never resume, steer, or compact a chat. */
final class ContextInspection {
    private static final long MAX_ROLLOUT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_STARTUP_BYTES = 2 * 1024 * 1024;

    static CompletableFuture<JsonObject> recorded(CodexService service, String id) {
        var chat = service.chat(id);
        if (chat.get("threadId").isBlank()) {
            return CompletableFuture.completedFuture(ContextReport.transcript(chat.items(), "This chat has no saved session yet. Use Startup to inspect its workspace instructions."));
        }
        return service.rpc("thread/read", object("threadId", chat.get("threadId"), "includeTurns", false)).orTimeout(20, TimeUnit.SECONDS)
            .thenApplyAsync(response -> {
                String source = text(obj(response, "thread"), "path");
                if (source.isBlank()) { throw new CompletionException(new IOException("Codex did not provide a session file path")); }
                var path = java.nio.file.Path.of(Paths.host(source, service.distro(), SystemInfo.isWindows));
                try (var channel = Files.newByteChannel(path)) {
                    long size = channel.size();
                    boolean tailOnly = size > MAX_ROLLOUT_BYTES;
                    if (tailOnly) { channel.position(size - MAX_ROLLOUT_BYTES); }
                    // Freeze the byte boundary so a busy chat cannot keep this scan alive indefinitely.
                    try (var reader = new BufferedReader(new InputStreamReader(new LimitedInputStream(Channels.newInputStream(channel), Math.min(size, MAX_ROLLOUT_BYTES)), StandardCharsets.UTF_8))) {
                        if (tailOnly) { reader.readLine(); }
                        return ContextReport.rollout(reader, source, tailOnly);
                    }
                } catch (IOException error) { throw new CompletionException(error); }
            }).exceptionally(error -> ContextReport.transcript(chat.items(), "Saved context could not be read: " + CodexService.message(error)));
    }

    static CompletableFuture<JsonObject> startup(CodexService service, String id, JsonObject params) {
        String cwd = service.chat(id).get("cwd");
        String binary = service.settings().binary.isBlank() ? "codex" : service.settings().binary;
        String model = text(params, "model", service.settings().model);
        String effort = text(params, "effort", service.settings().effort);
        String distro = service.distro();
        return CompletableFuture.supplyAsync(() -> {
            var command = new ArrayList<String>();
            if (SystemInfo.isWindows && !distro.isBlank()) {
                // Only this fixed script is shell text. The workspace, binary, and settings are positional arguments.
                command.addAll(List.of("wsl.exe", "--distribution", distro, "--exec", "/bin/sh", "-lc", "cd \"$1\" && shift && exec \"$@\"", "codex-tabs-context", cwd));
            }
            command.addAll(List.of(binary, "debug", "prompt-input"));
            if (!model.isBlank()) { command.addAll(List.of("-c", "model=" + GSON.toJson(model))); }
            if (!effort.isBlank()) { command.addAll(List.of("-c", "model_reasoning_effort=" + GSON.toJson(effort))); }
            Process process = null;
            try {
                var builder = new ProcessBuilder(command);
                if (!(SystemInfo.isWindows && !distro.isBlank())) { builder.directory(java.nio.file.Path.of(cwd).toFile()); }
                process = builder.start();
                process.getOutputStream().close();
                var running = process;
                var stdout = CompletableFuture.supplyAsync(() -> read(running.getInputStream(), MAX_STARTUP_BYTES));
                var stderr = CompletableFuture.supplyAsync(() -> read(running.getErrorStream(), 64 * 1024));
                if (!process.waitFor(45, TimeUnit.SECONDS)) { throw new IOException("Startup inspection timed out. The chat is unchanged."); }
                String output = stdout.get(5, TimeUnit.SECONDS);
                String errors = stderr.get(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0) { throw new IOException("Codex could not build startup input. " + errors.strip()); }
                var report = ContextReport.startup(JsonParser.parseString(output).getAsJsonArray(), cwd);
                report.addProperty("model", model);
                report.addProperty("effort", effort);
                return report;
            } catch (Exception error) {
                if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                throw new CompletionException(error);
            } finally {
                if (process != null) {
                    process.descendants().forEach(child -> { if (child.isAlive()) { child.destroy(); } });
                    if (process.isAlive()) { process.destroyForcibly(); }
                }
            }
        });
    }
    private static String read(InputStream input, int limit) {
        try (input) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) { throw new IOException("Context snapshot exceeded its size limit."); }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException error) { throw new CompletionException(error); }
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            if (remaining == 0) { return -1; }
            int value = in.read(); if (value >= 0) { remaining--; } return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) { return -1; }
            int count = in.read(bytes, offset, (int) Math.min(length, remaining)); if (count > 0) { remaining -= count; } return count;
        }
    }
    private ContextInspection() {}
}
