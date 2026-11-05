package com.acorn.codextabs.core;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import static com.acorn.codextabs.core.Json.*;

/** Read complete history for copying without loading it into the editor or resuming an archived chat. */
public final class ChatTranscript {
    private ChatTranscript() {}

    /** Normalize saved Claude frames without changing a live chat or restoring its compacted context. */
    public static JsonObject claude(JsonObject snapshot, JsonArray frames, boolean conversation) {
        var restored = new Conversation(text(snapshot, "id"), text(snapshot, "cwd"));
        var protocol = new ClaudeProtocol(restored, false);
        var hidden = new HashSet<String>(); var hiddenReplies = new HashSet<String>();
        for (var entry : frames) {
            var frame = entry.getAsJsonObject();
            boolean generated = flag(frame, "isMeta") || flag(frame, "isCompactSummary") || flag(frame, "isApiErrorMessage")
                || text(frame, "promptSource").equals("system") || text(frame, "turnOrigin").equals("task_notification");
            if (conversation && generated) {
                hidden.add(text(frame, "uuid")); hiddenReplies.add(text(obj(frame, "message"), "id"));
            } else { protocol.accept(frame); }
        }
        var ordered = new LinkedHashMap<String, JsonObject>();
        for (var entry : array(snapshot, "items")) {
            var item = entry.getAsJsonObject();
            if (item.has("historyProvider")) { ordered.put(text(item, "id"), item); }
        }
        var savedIds = new HashSet<String>();
        for (var entry : restored.items()) {
            var item = ProviderHandoff.visibleItem(entry.getAsJsonObject(), text(snapshot, "providerContext"));
            if (item != null) { String id = text(item, "id"); savedIds.add(id); ordered.put(id, item); }
        }
        var cached = array(snapshot, "items"); int overlap = -1;
        for (int i = 0; i < cached.size(); i++) {
            if (savedIds.contains(text(cached.get(i).getAsJsonObject(), "id"))) { overlap = i; }
        }
        for (int i = 0; i < cached.size(); i++) {
            var item = ProviderHandoff.visibleItem(cached.get(i).getAsJsonObject(), text(snapshot, "providerContext"));
            if (item == null) { continue; }
            String id = text(item, "id"); int separator = id.lastIndexOf(':');
            if (conversation && (hidden.contains(id) || separator > 0 && hiddenReplies.contains(id.substring(0, separator)))) { continue; }
            // Merge live output after the saved tail, without reviving an abandoned cached branch.
            if (ordered.containsKey(id) || i > overlap && (overlap >= 0 || frames.isEmpty() || flag(snapshot, "working"))) { ordered.put(id, item); }
        }
        return object("provider", "claude", "title", text(snapshot, "title"), "items", ordered.values(), "requests", array(snapshot, "requests"));
    }

    /** Merge paginated history with the visible snapshot, retaining live output and omitting unsent drafts. */
    public static JsonObject read(JsonObject snapshot, Function<String, JsonObject> readPage) {
        var newestFirst = new LinkedHashMap<String, JsonObject>();
        if (!text(snapshot, "threadId").isBlank()) {
            String cursor = "";
            var visited = new HashSet<String>();
            do {
                if (!visited.add(cursor)) { throw new IllegalStateException("Could not copy the whole chat: history pagination repeated. Try again."); }
                var page = readPage.apply(cursor);
                if (!page.has("data") || !page.get("data").isJsonArray()) { throw new IllegalStateException("Could not copy the whole chat: history was unavailable. Try again."); }
                for (var entry : array(page, "data")) {
                    var value = entry.getAsJsonObject();
                    var item = ProviderHandoff.visibleItem(value.has("item") ? obj(value, "item") : value, text(snapshot, "providerContext"));
                    if (item == null) { continue; }
                    if (text(item, "id").isBlank()) { throw new IllegalStateException("Could not copy the whole chat: a history item had no ID."); }
                    newestFirst.putIfAbsent(text(item, "id"), item);
                }
                cursor = text(page, "nextCursor");
            } while (!cursor.isBlank());
        }
        var ordered = new LinkedHashMap<String, JsonObject>();
        for (var entry : array(snapshot, "items")) {
            var item = entry.getAsJsonObject();
            if (item.has("historyProvider")) { ordered.put(text(item, "id"), item); }
        }
        newestFirst.reversed().forEach(ordered::put);
        // The server may not have saved the output currently streaming in this tab yet.
        for (var value : array(snapshot, "items")) {
            var item = value.getAsJsonObject();
            ordered.put(text(item, "id"), item);
        }
        return object("provider", text(snapshot, "provider", "codex"), "title", text(snapshot, "title"), "items", ordered.values(), "requests", array(snapshot, "requests"));
    }
}
