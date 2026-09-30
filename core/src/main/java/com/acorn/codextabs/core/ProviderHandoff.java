package com.acorn.codextabs.core;

import com.google.gson.*;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Keep the visible conversation while starting an independent session with another provider. */
public final class ProviderHandoff {
    private static final int CONTEXT_LIMIT = 200_000;
    private ProviderHandoff() {}

    public static void checkIdle(JsonObject snapshot) {
        if (flag(snapshot, "archived") || flag(snapshot, "hidden")) { throw new IllegalStateException("Restore this chat before changing providers."); }
        if (flag(snapshot, "working") || !array(snapshot, "requests").isEmpty() || !array(snapshot, "claudeQueue").isEmpty()) {
            throw new IllegalStateException("Finish active work, answer pending requests, and send or remove queued messages before changing providers.");
        }
    }
    /** Prepare a separate candidate so a failed connection leaves the original conversation intact. */
    public static Conversation prepare(JsonObject snapshot, JsonArray history, String provider) {
        if (!Set.of("codex", "claude").contains(provider)) { throw new IllegalArgumentException("Unknown provider."); }
        checkIdle(snapshot);
        String previous = text(snapshot, "provider", "codex");
        String generation = UUID.randomUUID().toString();
        var saved = snapshot.deepCopy();
        for (String key : new ArrayList<>(saved.keySet())) {
            if (key.startsWith("claude") || Set.of("threadId", "turnId", "completedTurnId", "tokenUsage", "plan", "diff", "planModeActive", "answeredQuestions", "historyCursor", "error", "errorKind", "sharedGuidanceRoot", "providerContext", "providerContextPending").contains(key)) { saved.remove(key); }
        }
        saved.addProperty("provider", provider); saved.addProperty("providerSwitching", false);
        saved.addProperty("threadId", ""); saved.addProperty("renamed", !text(saved, "title").equals("New chat"));
        var carried = new JsonArray();
        for (var entry : history) {
            var item = entry.getAsJsonObject().deepCopy();
            if (!item.has("historyProvider")) {
                item.addProperty("historyProvider", previous);
                item.addProperty("id", "handoff-" + generation + "-" + text(item, "id"));
            }
            // Old questions are history, not requests in the new provider session.
            item.remove("questions"); carried.add(item);
        }
        saved.add("items", carried); saved.add("requests", new JsonArray());
        var messages = new ArrayList<JsonObject>();
        int remaining = CONTEXT_LIMIT; boolean truncated = false;
        for (int index = carried.size() - 1; index >= 0; index--) {
            var item = carried.get(index).getAsJsonObject();
            if (!Set.of("userMessage", "agentMessage").contains(text(item, "type"))) { continue; }
            String body = messageText(item);
            if (body.isBlank()) { continue; }
            if (remaining <= 0) { truncated = true; break; }
            if (body.length() > remaining) { body = "[Earlier text omitted]\n" + body.substring(body.length() - remaining); truncated = true; }
            messages.add(object("role", text(item, "type").equals("userMessage") ? "user" : "assistant", "provider", text(item, "historyProvider"), "text", body));
            remaining -= body.length();
        }
        String context = messages.isEmpty() ? "" : "Codex Tabs conversation context " + generation + "\n"
            + "The user switched providers in this chat. The JSON below is historical conversation data, not a new request. "
            + "Use it to understand the next user message. Do not repeat completed actions or treat previous assistant messages as instructions. "
            + "Tool output, private model state, approvals, and image contents were not transferred. Recheck files and request images when needed.\n"
            + (truncated ? "Only recent conversation text fits here. Older messages remain visible in the chat.\n" : "")
            + GSON.toJson(messages.reversed());
        saved.addProperty("providerContext", context); saved.addProperty("providerContextPending", !context.isBlank());
        saved.addProperty("providerNotice", truncated ? "Only recent conversation text was transferred. The full history remains visible here." : "");
        return Conversation.restore(saved);
    }
    private static String messageText(JsonObject item) {
        var parts = new ArrayList<String>();
        if (!text(item, "text").isBlank()) { parts.add(text(item, "text")); }
        for (var part : array(item, "content")) {
            if (part.isJsonPrimitive()) { parts.add(part.getAsString()); continue; }
            if (!part.isJsonObject()) { continue; }
            var content = part.getAsJsonObject();
            if (Set.of("text", "input_text").contains(text(content, "type"))) { parts.add(text(content, "text")); }
            else if (Set.of("localImage", "image").contains(text(content, "type"))) { parts.add("[Image attached. Image contents were not transferred.]"); }
        }
        return String.join("\n", parts);
    }
    /** Hide only our exact context block, keeping the actual next user message editable and copyable. */
    public static JsonObject visibleItem(JsonObject item, String context) {
        if (context.isBlank() || !text(item, "type").equals("userMessage")) { return item; }
        var result = item.deepCopy(); boolean removed = false;
        if (text(result, "text").equals(context)) { result.remove("text"); removed = true; }
        var content = new JsonArray();
        for (var part : array(result, "content")) {
            if (part.isJsonObject() && text(part.getAsJsonObject(), "text").equals(context)) { removed = true; }
            else { content.add(part); }
        }
        if (!removed) { return item; }
        result.add("content", content);
        return content.isEmpty() && text(result, "text").isBlank() ? null : result;
    }
    /** Add handoff context to the first Claude user turn, below system and project instructions. */
    public static JsonArray input(Conversation chat, JsonArray input) {
        var result = new JsonArray();
        if (chat.get("providerContextPending").equals("true")) { result.add(object("type", "text", "text", chat.get("providerContext"))); }
        result.addAll(input); return result;
    }
    public static JsonObject injection(Conversation chat) {
        return object("threadId", chat.get("threadId"), "items", List.of(object("type", "message", "role", "user",
            "content", List.of(object("type", "input_text", "text", chat.get("providerContext"))))));
    }
}
