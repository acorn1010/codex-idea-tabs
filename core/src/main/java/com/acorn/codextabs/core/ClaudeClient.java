package com.acorn.codextabs.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static com.acorn.codextabs.core.Json.*;

/** Claude Code's stream-json transport. Each process owns one resumable conversation. */
public final class ClaudeClient implements AutoCloseable {
    private final Process process;
    private final BufferedWriter writer;
    private final ConcurrentMap<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    // Native process pipes can occupy virtual-thread carriers indefinitely, starving sends and reconnects.
    private final ExecutorService workers = Executors.newFixedThreadPool(3, Thread.ofPlatform().daemon().name("codex-tabs-claude-io-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<String> disconnected;
    private final StringBuilder stderr = new StringBuilder();
    private final ArrayBlockingQueue<String> outbound = new ArrayBlockingQueue<>(256);

    public ClaudeClient(Process process, Consumer<JsonObject> events, Consumer<String> disconnected) {
        this.process = process; this.disconnected = disconnected;
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        workers.submit(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) { continue; }
                    var event = JsonParser.parseString(line).getAsJsonObject();
                    if (text(event, "type").equals("control_response")) {
                        var response = obj(event, "response");
                        var future = pending.remove(text(response, "request_id"));
                        if (future == null) { continue; }
                        if (text(response, "subtype").equals("error")) { future.completeExceptionally(new IOException(text(response, "error", "Claude request failed"))); }
                        else { future.complete(obj(response, "response")); }
                    } else { events.accept(event); }
                }
                fail("Claude disconnected. Reconnect to resume this chat." + diagnostics());
            } catch (Exception error) { fail("Claude connection failed: " + error.getMessage() + diagnostics()); }
        });
        // A child that stops reading must not block callers before their request timeout starts.
        workers.submit(() -> {
            try {
                while (!closed.get()) {
                    writer.write(outbound.take()); writer.newLine(); writer.flush();
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (IOException error) { fail("Could not write to Claude: " + error.getMessage()); }
        });
        // Keep a small diagnostic in memory, never write conversation data to IDE logs.
        workers.submit(() -> {
            try (var input = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[1024]; int count;
                while ((count = input.read(buffer)) >= 0) {
                    synchronized (stderr) { stderr.append(buffer, 0, count); if (stderr.length() > 4000) { stderr.delete(0, stderr.length() - 4000); } }
                }
            } catch (IOException ignored) {}
        });
    }
    private String diagnostics() { synchronized (stderr) { return stderr.isEmpty() ? "" : "\n" + stderr.toString().strip(); } }
    /** Startup must fail visibly when an old CLI cannot answer the control protocol. */
    public CompletableFuture<JsonObject> initialize() { return initialize(30, TimeUnit.SECONDS); }
    CompletableFuture<JsonObject> initialize(long timeout, TimeUnit unit) {
        return control(object("subtype", "initialize"), timeout, unit).exceptionallyCompose(error -> {
            while (error instanceof CompletionException && error.getCause() != null) { error = error.getCause(); }
            if (error instanceof TimeoutException) {
                return CompletableFuture.failedFuture(new IOException("Claude did not answer the startup handshake. Update Claude Code or select its current executable in Settings > Tools > Codex Tabs, then reconnect.", error));
            }
            return CompletableFuture.failedFuture(error);
        });
    }
    public CompletableFuture<JsonObject> control(JsonObject request) { return control(request, 90, TimeUnit.SECONDS); }
    private CompletableFuture<JsonObject> control(JsonObject request, long timeout, TimeUnit unit) {
        String id = UUID.randomUUID().toString();
        var future = new CompletableFuture<JsonObject>(); pending.put(id, future);
        try { write(object("type", "control_request", "request_id", id, "request", request)); }
        catch (RuntimeException error) { pending.remove(id); future.completeExceptionally(error); }
        return future.orTimeout(timeout, unit).whenComplete((value, error) -> pending.remove(id));
    }
    public void respond(String id, JsonObject response) { write(object("type", "control_response", "response", object("subtype", "success", "request_id", id, "response", response))); }
    public void reject(String id, String reason) { write(object("type", "control_response", "response", object("subtype", "error", "request_id", id, "error", reason))); }
    public void write(JsonObject message) {
        if (!isAlive()) { throw new IllegalStateException("Claude is disconnected. Reconnect and try again."); }
        if (!outbound.offer(GSON.toJson(message))) { throw new IllegalStateException("Claude is not reading requests. Reconnect and try again."); }
    }
    public boolean isAlive() { return !closed.get() && process.isAlive(); }
    private void fail(String reason) {
        if (!closed.compareAndSet(false, true)) { return; }
        pending.values().forEach(future -> future.completeExceptionally(new IOException(reason))); pending.clear();
        outbound.clear();
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> { if (process.isAlive()) { process.destroyForcibly(); } });
        process.destroy();
        workers.shutdownNow(); disconnected.accept(reason);
    }
    @Override public void close() { fail("Disconnected"); }

    /** Find native and npm installations without depending on the desktop IDE's shell PATH. */
    public static String executable(String configured) {
        var home = Path.of(System.getProperty("user.home"));
        return executable(configured, Objects.toString(System.getenv("PATH"), ""), home, List.of(
            home.resolve(".local/bin/claude"), home.resolve(".claude/local/claude"), Path.of("/opt/homebrew/bin/claude"), Path.of("/usr/local/bin/claude")));
    }
    static String executable(String configured, String path, Path home, List<Path> fallbacks) {
        if (!configured.isBlank() && !configured.equals("claude")) { return configured; }
        var candidates = new ArrayList<Path>();
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (!directory.isBlank() && Path.of(directory).isAbsolute()) { candidates.add(Path.of(directory, "claude")); }
        }
        candidates.addAll(fallbacks);
        var nvm = home.resolve(".nvm/versions/node");
        if (Files.isDirectory(nvm)) {
            try (var versions = Files.list(nvm)) {
                versions.filter(Files::isDirectory).filter(version -> version.getFileName().toString().matches("v\\d+\\.\\d+\\.\\d+"))
                    // Compare numeric components so 22.14.0 comes before 22.3.0.
                    .sorted(Comparator.comparing((Path version) -> java.lang.module.ModuleDescriptor.Version.parse(version.getFileName().toString().substring(1))).reversed())
                    .map(version -> version.resolve("bin/claude")).forEach(candidates::add);
            } catch (IOException ignored) {}
        }
        return candidates.stream().filter(candidate -> Files.isRegularFile(candidate) && Files.isExecutable(candidate)).map(Path::toString).findFirst().orElse("claude");
    }
    /** Arguments remain separate from shell text, including WSL paths and user-selected models. */
    public static ProcessBuilder process(String binary, String cwd, String distro, String session, String resume, boolean fork) {
        return process(binary, cwd, distro, session, resume, fork, "", "");
    }
    public static ProcessBuilder process(String binary, String cwd, String distro, String session, String resume, boolean fork, String anchor, String guidance) {
        UUID.fromString(session);
        if (!resume.isBlank()) { UUID.fromString(resume); }
        var arguments = new ArrayList<>(List.of(binary, "--print", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages", "--permission-prompt-tool", "stdio"));
        if (!resume.isBlank()) { arguments.add("--resume=" + resume); }
        if (resume.isBlank() || fork) { arguments.add("--session-id=" + session); }
        if (fork) { arguments.add("--fork-session"); }
        if (!anchor.isBlank()) { UUID.fromString(anchor); arguments.add("--resume-session-at=" + anchor); }
        if (!guidance.isBlank()) { arguments.add("--append-system-prompt"); arguments.add(guidance); }
        ProcessBuilder builder;
        if (!distro.isBlank()) {
            var command = new ArrayList<>(List.of("wsl.exe", "--distribution", distro, "--exec", "/bin/sh", "-lc", "cd -- \"$1\" && shift && exec \"$@\"", "codex-tabs", cwd));
            command.addAll(arguments); builder = new ProcessBuilder(command);
        } else {
            builder = new ProcessBuilder(arguments).directory(Path.of(cwd).toFile());
            // npm's claude entry point uses /usr/bin/env node. Its Node binary is beside it.
            if (Path.of(binary).isAbsolute()) {
                builder.environment().put("PATH", Path.of(binary).getParent() + java.io.File.pathSeparator + Objects.toString(System.getenv("PATH"), ""));
            }
        }
        return builder;
    }
}
