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
    private static final int RECOVERY_LIMIT = 2;
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
        boolean successful;
        switch (text(item, "type")) {
            case "commandExecution" -> {
                // A yielded process and write_stdin polls have no terminal exit code.
                if (!item.has("exitCode") || item.get("exitCode").isJsonNull()) { return object(); }
                input = object("type", "command", "command", text(item, "command").trim(), "cwd", text(item, "cwd"));
                String output = text(item, "aggregatedOutput").replace("\r\n", "\n")
                    .replaceAll("(?m)^(?:Chunk ID:.*|Wall time:.*|Original token count:.*)\n?", "").strip();
                result = object("exitCode", item.get("exitCode"), "output", output);
                successful = text(item, "status").equals("completed") && item.get("exitCode").getAsInt() == 0;
            }
            case "mcpToolCall" -> {
                String tool = text(item, "tool");
                boolean readiness = text(item, "server").equals("codex_tabs_readiness") && tool.equals("wait_http");
                if (readiness) {
                    // Internal probes are bounded. Count failed waits so retrying a timeout cannot loop forever.
                    if (text(item, "status").equals("completed") && flag(obj(obj(item, "result"), "structuredContent"), "ready")) { return object(); }
                } else if (tool.matches("(?i)(?:.*[._/])?(?:wait(?:_.*)?|poll(?:_.*)?|get_status|check_status|watch(?:_.*)?)")) { return object(); }
                input = object("type", "mcp", "server", text(item, "server"), "tool", tool, "arguments", item.get("arguments"));
                result = object("status", text(item, "status"), "result", item.get("result"), "error", item.get("error"));
                successful = text(item, "status").equals("completed") && item.has("result") && !item.get("result").isJsonNull()
                    && !flag(obj(item, "result"), "isError") && (!item.has("error") || item.get("error").isJsonNull());
            }
            case "fileChange" -> {
                if (array(item, "changes").isEmpty()) { return object(); }
                input = object("type", "fileChange", "changes", item.get("changes"));
                result = object("status", text(item, "status"));
                successful = text(item, "status").equals("completed");
            }
            default -> { return object(); }
        }
        String key = hash(input);
        String fingerprint = hash(result);
        // A changed result is evidence of progress for this command, even if a test still fails.
        boolean changed = false;
        boolean seen = false;
        int repeats = 1;
        for (var value : recent) {
            var entry = value.getAsJsonObject();
            if (!text(entry, "key").equals(key)) { continue; }
            seen = true;
            if (!text(entry, "result").equals(fingerprint)) { changed = true; }
            else { repeats = entry.has("repeats") ? Math.max(repeats, entry.get("repeats").getAsInt() + 1) : repeats + 1; }
        }
        if (changed) {
            var iterator = recent.iterator();
            while (iterator.hasNext()) { if (text(iterator.next().getAsJsonObject(), "key").equals(key)) { iterator.remove(); } }
            repeats = 1;
        }
        if (changed || (!seen && successful)) { progress(memory); }
        // Carry the tally forward so a longer cycle cannot outlive the bounded history.
        recent.add(object("id", id, "key", key, "result", fingerprint, "repeats", repeats));
        while (recent.size() > WINDOW) { recent.remove(0); }
        memory.add("recent", recent);
        if (repeats >= STOP_AT) {
            memory.addProperty("stopped", true);
            return stopped(memory);
        }
        if (repeats >= WARN_AT) { return recovery(memory, false); }
        return object();
    }
    /** Renew recovery after new evidence, keeping unchanged-call history and final stops intact. */
    static void progress(JsonObject memory) {
        if (flag(memory, "stopped")) { return; }
        memory.remove("recoveryAttempts"); memory.remove("warned");
    }
    /** Use a different correction on each attempt and keep the limit across automatic continuations. */
    static JsonObject recovery(JsonObject memory, boolean textLoop) {
        int attempt = memory.has("recoveryAttempts") ? memory.get("recoveryAttempts").getAsInt() + 1 : 1;
        if (attempt > RECOVERY_LIMIT) {
            memory.addProperty("stopped", true); memory.addProperty("stopReason", textLoop ? "text" : "tools");
            return stopped(memory);
        }
        memory.addProperty("warned", true); memory.addProperty("recoveryAttempts", attempt);
        String correction;
        if (textLoop) {
            correction = attempt == 1
                ? "Your response repeated plans without taking an action. Stop repeating the plan. Take one concrete step allowed by the user's request and the active instructions, then use its result. If you cannot act, state the blocker briefly and end this turn."
                : "The previous recovery did not break the text loop. Drop the repeated approach. Choose a smaller next step that gives new evidence and do it once. If no such step is available, give a short blocker report instead of generating more plans.";
        } else {
            correction = attempt == 1
                ? "The same tool call returned an unchanged result three times. Use the result you already have. Inspect a different source or fix the cause before retrying. For an HTTP server, use wait_http with a deadline."
                : "The repeated tool call still has not produced new information. Do not run it again unchanged. Try a different check or make a targeted fix first. If you cannot make progress, explain the blocker briefly and end this turn.";
        }
        String message = "Loop guard recovery " + attempt + "/" + RECOVERY_LIMIT + " [" + UUID.randomUUID() + "]\n"
            + correction + " Do not mark unfinished work complete.";
        return object("stop", false, "restart", textLoop, "message", message);
    }
    /** Keep automatic goal continuations stopped until the user sends a new instruction. */
    public static JsonObject stopped(JsonObject memory) {
        if (text(memory, "stopReason").equals("text")) {
            return object("stop", true, "message", "Loop guard stopped this turn after repeated text continued without a tool result. Your chat and goal are kept. Send a new instruction to try a different approach.");
        }
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
