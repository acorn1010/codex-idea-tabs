package com.acorn.codextabs.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Translate Claude messages into the shared transcript, retaining stable IDs across partial and complete frames. */
public final class ClaudeProtocol {
    private final Conversation chat;
    private final Map<String, JsonObject> tools = new HashMap<>();
    private final Map<Integer, JsonObject> blocks = new HashMap<>();
    private String messageId = "";
    private String turnId = "";
    public ClaudeProtocol(Conversation chat) { this.chat = chat; }

    public synchronized void start(String turn, JsonArray input) {
        turnId = turn; blocks.clear();
        event("turn/started", object("turn", object("id", turn)));
        item(object("id", turn, "type", "userMessage", "content", input), true);
    }
    public synchronized void accept(JsonObject frame) {
        String session = text(frame, "session_id");
        if (!session.isBlank()) { chat.set("threadId", session); }
        // Nested agents have their own message streams. Their parent's tool result carries the result.
        if (!text(frame, "parent_tool_use_id").isBlank()) { return; }
        switch (text(frame, "type")) {
            case "stream_event" -> stream(obj(frame, "event"));
            case "assistant" -> {
                var message = obj(frame, "message");
                String id = text(message, "id", text(frame, "uuid"));
                int index = 0;
                for (var content : array(message, "content")) { block(id + ":" + index++, content.getAsJsonObject(), true); }
            }
            case "user" -> {
                var userInput = ClaudeHistory.input(frame);
                if (!userInput.isEmpty() && !text(frame, "uuid").isBlank()) { item(object("id", text(frame, "uuid"), "type", "userMessage", "content", userInput), true); }
                for (var content : array(obj(frame, "message"), "content")) {
                    var result = content.getAsJsonObject();
                    if (!text(result, "type").equals("tool_result")) { continue; }
                    var tool = tools.get(text(result, "tool_use_id"));
                    if (tool == null) { continue; }
                    var completed = tool.deepCopy();
                    completed.addProperty("status", flag(result, "is_error") ? "failed" : "completed");
                    if (text(tool, "type").equals("commandExecution")) { completed.addProperty("aggregatedOutput", contentText(result.get("content"))); }
                    else { completed.add("result", object("content", result.get("content"), "isError", flag(result, "is_error"))); }
                    tools.put(text(result, "tool_use_id"), completed); item(completed, true);
                }
            }
            case "system" -> {
                if (text(frame, "subtype").equals("init")) {
                    chat.set("claudeCommands", array(frame, "slash_commands"));
                }
                if (text(frame, "subtype").equals("local_command_output")) { item(object("id", text(frame, "uuid"), "type", "agentMessage", "text", text(frame, "content")), true); }
                if (text(frame, "subtype").equals("commands_changed")) { chat.set("claudeCommands", array(frame, "commands")); }
                if (text(frame, "subtype").equals("notification")) { chat.set("claudeNotice", text(frame, "text", text(frame, "message"))); }
            }
            case "rate_limit_event" -> chat.set("claudeRateLimit", obj(frame, "rate_limit_info"));
            case "result" -> {
                chat.set("claudeUsage", obj(frame, "usage"));
                chat.set("claudeModelUsage", obj(frame, "modelUsage"));
                if (frame.has("total_cost_usd")) { chat.set("claudeCost", frame.get("total_cost_usd")); }
                String error = "";
                if (flag(frame, "is_error")) { error = contentText(frame.get("errors")); if (error.isBlank()) { error = text(frame, "result", "Claude could not finish this turn."); } }
                event("turn/completed", object("turn", object("id", turnId, "status", error.isBlank() ? "completed" : "failed", "error", error.isBlank() ? null : object("message", error))));
            }
            default -> {}
        }
    }
    private void stream(JsonObject event) {
        int index = event.has("index") ? event.get("index").getAsInt() : 0;
        switch (text(event, "type")) {
            case "message_start" -> { messageId = text(obj(event, "message"), "id"); blocks.clear(); }
            case "content_block_start" -> {
                var content = obj(event, "content_block").deepCopy(); blocks.put(index, content);
                block(messageId + ":" + index, content, false);
            }
            case "content_block_delta" -> {
                var content = blocks.get(index); if (content == null) { return; }
                var delta = obj(event, "delta");
                String field = switch (text(delta, "type")) { case "text_delta" -> "text"; case "thinking_delta" -> "thinking"; case "input_json_delta" -> "partial_json"; default -> ""; };
                if (field.isBlank()) { return; }
                content.addProperty(field, text(content, field) + text(delta, field));
                if (!field.equals("partial_json")) { block(messageId + ":" + index, content, false); }
            }
            case "content_block_stop" -> {
                var content = blocks.get(index); if (content == null) { return; }
                if (content.has("partial_json")) {
                    try { content.add("input", JsonParser.parseString(text(content, "partial_json"))); }
                    catch (JsonParseException ignored) { /* A complete assistant frame can still supply valid tool input. */ }
                }
                block(messageId + ":" + index, content, true);
            }
            default -> {}
        }
    }
    private void block(String id, JsonObject content, boolean complete) {
        switch (text(content, "type")) {
            case "text" -> item(object("id", id, "type", "agentMessage", "text", text(content, "text")), complete);
            case "thinking" -> item(object("id", id, "type", "reasoning", "text", text(content, "thinking")), complete);
            case "tool_use" -> {
                String toolId = text(content, "id"), name = text(content, "name");
                var input = obj(content, "input");
                var tool = name.equals("Bash")
                    ? object("id", toolId, "type", "commandExecution", "command", text(input, "command"), "status", "inProgress")
                    : object("id", toolId, "type", "mcpToolCall", "server", "Claude", "tool", name, "arguments", input, "status", "inProgress");
                // A late complete assistant frame must not erase an already received tool result.
                var previous = tools.get(toolId);
                if (previous != null && !text(previous, "status").equals("inProgress")) { return; }
                tools.put(toolId, tool); item(tool, false);
            }
            default -> {}
        }
    }
    private void item(JsonObject item, boolean complete) { event(complete ? "item/completed" : "item/started", object("item", item)); }
    private void event(String method, JsonObject params) {
        params.addProperty("turnId", turnId); chat.event(object("method", method, "params", params));
    }
    private static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) { return ""; }
        if (content.isJsonPrimitive()) { return content.getAsString(); }
        if (content.isJsonArray()) {
            var parts = new ArrayList<String>();
            for (var part : content.getAsJsonArray()) { parts.add(part.isJsonObject() && part.getAsJsonObject().has("text") ? text(part.getAsJsonObject(), "text") : contentText(part)); }
            return String.join("\n", parts);
        }
        return GSON.toJson(content);
    }
    /** A remembered approval matches exactly this checkout, tool, and input, never a broader command prefix. */
    public static String approvalKey(String cwd, JsonObject request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((cwd + "\n" + text(request, "tool_name") + "\n" + canonical(obj(request, "input"))).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var sorted = new JsonObject();
            value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> sorted.add(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (value.isJsonArray()) { var sorted = new JsonArray(); value.getAsJsonArray().forEach(item -> sorted.add(canonical(item))); return sorted; }
        return value;
    }
    /** Reject malformed decisions and map question answers back to Claude tool input. */
    public static JsonObject approvalResponse(JsonObject request, JsonObject payload) {
        var input = obj(request, "input").deepCopy();
        String decision = text(payload, "decision", "decline");
        if (text(request, "tool_name").equals("AskUserQuestion")) {
            var answers = new JsonObject(); int index = 0;
            for (var question : array(input, "questions")) {
                var selected = array(obj(obj(payload, "answers"), Integer.toString(index++)), "answers");
                if (selected.isEmpty() || selected.get(0).getAsString().isBlank()) { throw new IllegalArgumentException("Answer each question first."); }
                if (flag(question.getAsJsonObject(), "multiSelect")) { answers.add(text(question.getAsJsonObject(), "question"), selected.deepCopy()); }
                else { answers.addProperty(text(question.getAsJsonObject(), "question"), selected.get(0).getAsString()); }
            }
            input.add("answers", answers); decision = "accept";
        }
        if (!Set.of("accept", "session", "always", "decline").contains(decision)) { throw new IllegalArgumentException("Unknown approval choice."); }
        if (decision.equals("decline")) { return object("behavior", "deny", "message", "The user declined this action."); }
        var response = object("behavior", "allow", "updatedInput", input);
        if (decision.equals("session") || decision.equals("always")) {
            var updates = new JsonArray();
            for (var value : array(request, "permission_suggestions")) {
                var rule = value.getAsJsonObject().deepCopy();
                // Persist only permission rules, never a suggested mode or directory-wide expansion.
                if (!text(rule, "type").equals("addRules") || !text(rule, "behavior").equals("allow")) { continue; }
                rule.addProperty("destination", decision.equals("always") ? "localSettings" : "session"); updates.add(rule);
            }
            if (!updates.isEmpty()) { response.add("updatedPermissions", updates); }
        }
        return response;
    }
    public static String permissionDescription(JsonObject request) {
        var descriptions = new ArrayList<String>();
        for (var suggestion : array(request, "permission_suggestions")) {
            var update = suggestion.getAsJsonObject();
            if (!text(update, "type").equals("addRules") || !text(update, "behavior").equals("allow")) { continue; }
            for (var value : array(update, "rules")) {
                var rule = value.getAsJsonObject(); String pattern = text(rule, "ruleContent");
                descriptions.add(text(rule, "toolName") + (pattern.isBlank() ? " (all inputs)" : "(" + pattern + ")"));
            }
        }
        return String.join(", ", descriptions);
    }
    public static JsonArray models(JsonObject initialization) {
        var models = new JsonArray();
        for (var entry : array(initialization, "models")) {
            var model = entry.getAsJsonObject(); String value = text(model, "value");
            var efforts = new JsonArray();
            for (var effort : array(model, "supportedEffortLevels")) { efforts.add(object("reasoningEffort", effort.getAsString(), "description", "")); }
            models.add(object("id", value, "model", value.equals("default") ? "" : value, "isDefault", value.equals("default"), "displayName", text(model, "displayName", value),
                "defaultReasoningEffort", "", "supportedReasoningEfforts", efforts, "supportsAutoMode", flag(model, "supportsAutoMode"),
                "additionalSpeedTiers", flag(model, "supportsFastMode") ? new String[]{"fast"} : new String[]{}));
        }
        return models;
    }
}
