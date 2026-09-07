package com.acorn.codextabs.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static com.acorn.codextabs.core.Json.*;

/** Owns one process connection across all chat views. Reading and writing never block the IDE UI thread. */
public final class RpcClient implements AutoCloseable {
    private final Process process;
    private final BufferedWriter writer;
    private final ConcurrentMap<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<JsonObject> events;
    private final Consumer<String> disconnected;
    private final ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor();
    private final ArrayBlockingQueue<String> outbound = new ArrayBlockingQueue<>(256);

    public RpcClient(Process process, Consumer<JsonObject> events, Consumer<String> disconnected) {
        this.process = process;
        this.events = events;
        this.disconnected = disconnected;
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader.submit(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) { continue; }
                    var message = JsonParser.parseString(line).getAsJsonObject();
                    if (message.has("id") && !message.has("method")) {
                        var future = pending.remove(message.get("id").toString());
                        if (future == null) { continue; }
                        if (message.has("error")) {
                            future.completeExceptionally(new IOException(text(obj(message, "error"), "message", "Codex request failed")));
                        } else { future.complete(obj(message, "result")); }
                    } else { events.accept(message); }
                }
                fail("Codex disconnected. Reconnect to resume your chats.");
            } catch (Exception error) { fail("Codex connection failed: " + error.getMessage()); }
        });
        reader.submit(() -> {
            try {
                while (!closed.get()) {
                    writer.write(outbound.take());
                    writer.newLine();
                    writer.flush();
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (IOException error) { fail("Could not write to Codex: " + error.getMessage()); }
        });
        // Drain stderr without including private commands or conversation content in IDE logs.
        reader.submit(() -> { try (var stream = process.getErrorStream()) { stream.transferTo(OutputStream.nullOutputStream()); } catch (IOException ignored) {} });
    }

    public CompletableFuture<JsonObject> request(String method, JsonObject params) {
        long id = sequence.incrementAndGet();
        var future = new CompletableFuture<JsonObject>();
        pending.put(Long.toString(id), future);
        try { send(object("id", id, "method", method, "params", params)); }
        catch (Exception error) { pending.remove(Long.toString(id)); future.completeExceptionally(error); }
        future.orTimeout(90, TimeUnit.SECONDS).whenComplete((value, error) -> pending.remove(Long.toString(id)));
        return future;
    }
    public void notify(String method, JsonObject params) { send(object("method", method, "params", params)); }
    public void respond(JsonElement id, JsonObject result) { send(object("id", id, "result", result)); }
    public void reject(JsonElement id, String message) { send(object("id", id, "error", object("code", -32601, "message", message))); }
    public boolean isAlive() { return !closed.get() && process.isAlive(); }

    private void send(JsonObject message) {
        if (closed.get() || !outbound.offer(GSON.toJson(message))) { throw new IllegalStateException("Codex connection is unavailable or busy. Retry shortly."); }
    }
    private void fail(String message) {
        if (!closed.compareAndSet(false, true)) { return; }
        pending.values().forEach(future -> future.completeExceptionally(new IOException(message)));
        pending.clear();
        process.destroy();
        reader.shutdownNow();
        disconnected.accept(message);
    }
    @Override public void close() { fail("Disconnected"); }
}
