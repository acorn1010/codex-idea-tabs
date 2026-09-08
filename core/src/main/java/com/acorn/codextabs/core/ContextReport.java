package com.acorn.codextabs.core;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Reads recorded context without counting transcript display events as additional model input. */
public final class ContextReport {
    private static final int MAX_BLOCK_CHARS = 100_000;
    private static final int MAX_REPORT_CHARS = 2_000_000;
    private static final int MAX_BLOCKS = 2_000;
    private final List<JsonObject> blocks = new ArrayList<>();
    private final Map<String, String> calls = new HashMap<>();
    private final Set<String> notices = new LinkedHashSet<>();
    private int characters;
    private int sequence;
    private int compactions;
    private int opaqueItems;
    private String turn = "";
    private String base = "";
    private JsonObject usage = new JsonObject();

    /** Reconstruct the latest recorded history window. This is not an HTTP request capture. */
    public static JsonObject rollout(BufferedReader reader, String source, boolean tailOnly) throws IOException {
        var report = new ContextReport();
        report.notices.add("Reconstructed from saved records. Runtime changes, tool definitions, and hidden content may be absent. This is not an exact request capture.");
        if (tailOnly) { report.notices.add("Only the last 64 MB of this session file were read. Earlier records may be missing."); }
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) { continue; }
            try {
                var record = JsonParser.parseString(line).getAsJsonObject();
                var payload = obj(record, "payload");
                switch (text(record, "type")) {
                    case "session_meta" -> {
                        var instructions = payload.get("base_instructions");
                        report.base = instructions == null ? "" : instructions.isJsonPrimitive() ? instructions.getAsString() : text(instructions.getAsJsonObject(), "text");
                        report.add("instructions", "Base instructions at thread creation", "system", report.base, "Recorded at thread creation");
                    }
                    case "turn_context" -> report.turn = text(payload, "turn_id");
                    case "response_item" -> report.item(payload, "Recorded item");
                    case "compacted" -> report.compact(payload);
                    case "event_msg" -> {
                        if (text(payload, "type").equals("token_count")) { report.usage = obj(payload, "info"); }
                    }
                    default -> { /* Display events and usage mirrors are not extra context. */ }
                }
            } catch (JsonParseException | IllegalStateException error) {
                report.notices.add("An incomplete or unsupported record was skipped. Refresh after the current write finishes.");
            }
        }
        return report.finish("recorded", source);
    }

    /** Inspect a new CLI startup snapshot independently of an existing chat's recorded history. */
    public static JsonObject startup(JsonArray input, String source) {
        var report = new ContextReport();
        report.notices.add("Fresh CLI startup input using this workspace and selected model. It is not the current chat and excludes its history, draft, and IDE context. Server-side instructions and tool definitions are not included.");
        for (var entry : input) { if (entry.isJsonObject()) { report.item(entry.getAsJsonObject(), "Fresh startup input"); } }
        return report.finish("startup", source);
    }

    /** Fall back to the loaded transcript if this Codex version has no readable session file. */
    public static JsonObject transcript(JsonArray items, String reason) {
        var report = new ContextReport();
        report.notices.add(reason);
        report.notices.add("Only the transcript already loaded in this tab is shown. Startup instructions, earlier history, and compaction state are not available here.");
        for (var entry : items) {
            var item = entry.getAsJsonObject();
            String type = text(item, "type");
            if (type.equals("userMessage")) { report.message(item, "user", "Loaded transcript"); }
            else if (type.equals("agentMessage")) { report.add("messages", "Assistant message", "assistant", text(item, "text"), "Loaded transcript"); }
            else if (type.equals("commandExecution")) { report.add("tools", text(item, "command", "Command output"), "tool", text(item, "aggregatedOutput"), "Loaded transcript"); }
            else { report.add("tools", type, "tool", GSON.toJson(item), "Loaded transcript"); }
        }
        return report.finish("transcript", "Loaded chat history");
    }

    private void compact(JsonObject payload) {
        compactions++;
        blocks.clear(); calls.clear(); characters = 0; opaqueItems = 0;
        add("instructions", "Base instructions at thread creation", "system", base, "Recorded at thread creation");
        if (payload.get("replacement_history") instanceof JsonArray replacement) {
            for (var entry : replacement) { if (entry.isJsonObject()) { item(entry.getAsJsonObject(), "Compaction replacement"); } }
        } else {
            add("summary", "Compaction summary", "assistant", text(payload, "message"), "Compaction record");
            notices.add("This compaction did not record replacement history. Earlier retained messages cannot be reconstructed.");
        }
    }
    private void item(JsonObject item, String origin) {
        String type = text(item, "type");
        switch (type) {
            case "message" -> message(item, text(item, "role"), origin);
            case "function_call", "custom_tool_call" -> {
                String name = text(item, "name", "Tool call");
                String arguments = text(item, type.equals("function_call") ? "arguments" : "input");
                calls.put(text(item, "call_id"), name + "\n" + arguments);
                add("tools", name, "assistant", arguments, origin + " · tool call");
            }
            case "function_call_output", "custom_tool_call_output" -> {
                String call = calls.getOrDefault(text(item, "call_id"), "Tool output");
                String content = content(item.get("output"));
                String kind = call.contains("SKILL.md") ? "skills" : call.contains("AGENTS.md") || call.contains(".agents/rules/") ? "instructions" : "tools";
                int previousBlocks = blocks.size();
                add(kind, call.lines().findFirst().orElse("Tool output") + " result", "tool", content, origin + " · tool result");
                if (blocks.size() > previousBlocks) { blocks.getLast().addProperty("call", shorten(call, 4_000)); }
            }
            case "reasoning", "compaction" -> {
                String content = content(item.get("summary"));
                if (!content.isBlank()) { add("summary", type.equals("compaction") ? "Compaction summary" : "Reasoning summary", "assistant", content, origin); }
                if (item.has("encrypted_content") && !item.get("encrypted_content").isJsonNull()) { opaqueItems++; }
            }
            default -> {
                // Unknown payloads may contain huge encoded images or encrypted data. Do not serialize them.
                opaqueItems++;
            }
        }
    }
    private void message(JsonObject message, String role, String origin) {
        for (var part : array(message, "content")) {
            if (!part.isJsonObject()) { continue; }
            var value = part.getAsJsonObject();
            String type = text(value, "type");
            if (Set.of("input_text", "output_text", "text").contains(type)) {
                String text = text(value, "text");
                String kind = role.equals("developer") || role.equals("system") ? "instructions" : "messages";
                if (text.stripLeading().startsWith("# AGENTS.md instructions") || text.stripLeading().startsWith("<user_instructions>")) { kind = "instructions"; }
                if (text.contains("## Available skills") || text.contains("### Available skills") || text.contains("<skills_instructions>")) { kind = "skills"; }
                String label = switch (kind) { case "instructions" -> "Rules and instructions"; case "skills" -> "Skill instructions and catalog"; default -> role.equals("user") ? "User message" : "Assistant message"; };
                add(kind, label, role, text, origin);
            } else if (type.contains("image") || type.equals("localImage")) {
                String path = text(value, "path", text(value, "image_url", text(value, "url")));
                add("files", "Image attachment", role, path.startsWith("data:") ? "[Image bytes omitted]" : path, origin + " · image tokens excluded");
            } else { opaqueItems++; }
        }
    }
    private static String content(JsonElement value) {
        if (value == null || value.isJsonNull()) { return ""; }
        if (value.isJsonPrimitive()) { return value.getAsString(); }
        if (value.isJsonArray()) {
            var parts = new ArrayList<String>();
            for (var part : value.getAsJsonArray()) {
                if (part.isJsonObject()) { parts.add(text(part.getAsJsonObject(), "text", "[Non-text tool content omitted]")); }
                else if (part.isJsonPrimitive()) { parts.add(part.getAsString()); }
            }
            return String.join("\n", parts);
        }
        // Tool output wrappers can contain text, but never expose opaque reasoning payloads.
        return text(value.getAsJsonObject(), "text", "[Structured non-text content omitted]");
    }
    private void add(String kind, String label, String role, String text, String origin) {
        if (text.isBlank()) { return; }
        int remaining = MAX_REPORT_CHARS - characters;
        if (remaining <= 0 || blocks.size() >= MAX_BLOCKS) { notices.add("The inspector reached its display limit. Counts and duplicate checks cover the displayed text only."); return; }
        String shown = shorten(text, Math.min(MAX_BLOCK_CHARS, remaining));
        boolean truncated = shown.length() != text.length();
        if (truncated) { notices.add("Some large blocks were shortened. Counts and duplicate checks cover the displayed text only."); }
        blocks.add(object("id", "block-" + sequence++, "kind", kind, "label", shorten(label, 100), "role", role, "text", shown, "origin", origin, "turnId", turn, "truncated", truncated));
        characters += shown.length();
    }
    private JsonObject finish(String kind, String source) {
        if (opaqueItems > 0) { notices.add(opaqueItems + " non-text or encrypted items were omitted. Their tokens are not included in text estimates."); }
        return object("kind", kind, "source", source, "capturedAt", System.currentTimeMillis(), "blocks", blocks, "notices", notices, "compactions", compactions, "recordedUsage", usage);
    }
    private static String shorten(String value, int limit) {
        if (value.length() <= limit) { return value; }
        int end = limit > 0 && Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end);
    }
    private ContextReport() {}
}
