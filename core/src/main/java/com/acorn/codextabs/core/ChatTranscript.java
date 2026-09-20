package com.acorn.codextabs.core;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import static com.acorn.codextabs.core.Json.*;

/** Read complete history for copying without loading it into the editor or resuming an archived chat. */
public final class ChatTranscript {
    private ChatTranscript() {}

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
                    var item = value.has("item") ? obj(value, "item") : value;
                    if (text(item, "id").isBlank()) { throw new IllegalStateException("Could not copy the whole chat: a history item had no ID."); }
                    newestFirst.putIfAbsent(text(item, "id"), item);
                }
                cursor = text(page, "nextCursor");
            } while (!cursor.isBlank());
        }
        var ordered = new LinkedHashMap<String, JsonObject>();
        newestFirst.reversed().forEach(ordered::put);
        // The server may not have saved the output currently streaming in this tab yet.
        for (var value : array(snapshot, "items")) {
            var item = value.getAsJsonObject();
            ordered.put(text(item, "id"), item);
        }
        return object("title", text(snapshot, "title"), "items", ordered.values(), "requests", array(snapshot, "requests"));
    }
}
