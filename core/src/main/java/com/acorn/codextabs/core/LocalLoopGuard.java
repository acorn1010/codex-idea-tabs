package com.acorn.codextabs.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Compare completed calls and results without putting another tool transcript in model context. */
public final class LocalLoopGuard {
    private static final int WINDOW = 24;
    private static final int WARN_AT = 3;
    private static final int STOP_AT = 5;
    private LocalLoopGuard() {}

    /** Record one terminal tool result and return a steering or interruption request when needed. */
    public static JsonObject observe(JsonObject memory, JsonObject item) {
        if (flag(memory, "stopped")) { return object(); }
        String id = text(item, "id");
        if (id.isBlank() || !Set.of("completed", "failed").contains(text(item, "status"))) { return object(); }
        var recent = array(memory, "recent");
        for (var value : recent) { if (text(value.getAsJsonObject(), "id").equals(id)) { return object(); } }
        JsonObject input;
        JsonElement result;
        switch (text(item, "type")) {
            case "commandExecution" -> {
                // A yielded process and write_stdin polls have no terminal exit code.
                if (!item.has("exitCode") || item.get("exitCode").isJsonNull()) { return object(); }
                input = object("type", "command", "command", text(item, "command").trim(), "cwd", text(item, "cwd"));
                String output = text(item, "aggregatedOutput").replace("\r\n", "\n")
                    .replaceAll("(?m)^(?:Chunk ID:.*|Wall time:.*|Original token count:.*)\n?", "").strip();
                result = object("exitCode", item.get("exitCode"), "output", output);
            }
            case "mcpToolCall" -> {
                String tool = text(item, "tool");
                if (tool.matches("(?i)(?:.*[._/])?(?:wait(?:_.*)?|poll(?:_.*)?|get_status|check_status|watch(?:_.*)?)")) { return object(); }
                input = object("type", "mcp", "server", text(item, "server"), "tool", tool, "arguments", item.get("arguments"));
                result = object("status", text(item, "status"), "result", item.get("result"), "error", item.get("error"));
            }
            default -> { return object(); }
        }
        String key = hash(input);
        String fingerprint = hash(result);
        // A changed result is evidence of progress for this command, even if a test still fails.
        boolean changed = false;
        int repeats = 1;
        for (var value : recent) {
            var entry = value.getAsJsonObject();
            if (!text(entry, "key").equals(key)) { continue; }
            if (!text(entry, "result").equals(fingerprint)) { changed = true; }
            else { repeats = entry.has("repeats") ? Math.max(repeats, entry.get("repeats").getAsInt() + 1) : repeats + 1; }
        }
        if (changed) {
            var iterator = recent.iterator();
            while (iterator.hasNext()) { if (text(iterator.next().getAsJsonObject(), "key").equals(key)) { iterator.remove(); } }
            repeats = 1;
        }
        // Carry the tally forward so a longer cycle cannot outlive the bounded history.
        recent.add(object("id", id, "key", key, "result", fingerprint, "repeats", repeats));
        while (recent.size() > WINDOW) { recent.remove(0); }
        memory.add("recent", recent);
        if (repeats >= STOP_AT) {
            memory.addProperty("stopped", true);
            return stopped();
        }
        if (repeats == WARN_AT) {
            memory.addProperty("warned", true);
            return object("stop", false, "message", "Loop guard: a tool call returned an unchanged result three times. Review the failure and choose a different approach. Do not repeat the same calls without new evidence. Keep the task unfinished until its work is done.");
        }
        return object();
    }
    /** Keep automatic goal continuations stopped until the user sends a new instruction. */
    public static JsonObject stopped() {
        return object("stop", true, "message", "Loop guard stopped this turn after repeated tool calls returned unchanged results. Your chat and goal are kept. Send a new instruction to try a different approach.");
    }
    private static String hash(JsonElement value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
    private static JsonElement canonical(JsonElement value) {
        if (value == null || value.isJsonNull()) { return JsonNull.INSTANCE; }
        if (value.isJsonObject()) {
            var result = new JsonObject();
            for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) { result.add(key, canonical(value.getAsJsonObject().get(key))); }
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray();
            for (var entry : value.getAsJsonArray()) { result.add(canonical(entry)); }
            return result;
        }
        return value;
    }
}
