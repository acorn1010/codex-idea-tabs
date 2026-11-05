package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ClaudeTranscriptTest {
    @TempDir Path config;
    private static JsonObject user(String id, String parent, String text) { return object("uuid", id, "parentUuid", parent, "type", "user", "message", object("content", text)); }
    private static JsonObject answer(String id, String parent, String text) { return object("uuid", id, "parentUuid", parent, "type", "assistant", "message", object("id", "m-" + id, "content", List.of(object("type", "text", "text", text)))); }
    private static JsonObject summary(String id, String parent) { var item = user(id, parent, "This session is being continued from a previous conversation that ran out of context."); item.addProperty("isCompactSummary", true); return item; }
    private String save(JsonObject... frames) throws Exception {
        String id = UUID.randomUUID().toString();
        var directory = Files.createDirectories(config.resolve("projects/project"));
        Files.writeString(directory.resolve(id + ".jsonl"), String.join("\n", Arrays.stream(frames).map(GSON::toJson).toList()) + "\n");
        return id;
    }
    private static List<String> ids(JsonArray items) { return items.asList().stream().map(value -> text(value.getAsJsonObject(), "id")).toList(); }
    @Test void exportsOriginalMessagesAcrossMultipleCompactionsWithoutChangingResumeHistory() throws Exception {
        String id = save(user("u1", null, "Original question"), answer("a1", "u1", "Original answer"),
            user("abandoned", "a1", "Discarded branch"), user("u2", "a1", "Second question"), answer("a2", "u2", "Second answer"),
            object("uuid", "c1", "type", "system", "subtype", "compact_boundary", "logicalParentUuid", "a2"), summary("s1", "c1"),
            user("u3", "s1", "Third question"), answer("a3", "u3", "Third answer"),
            object("uuid", "c2", "type", "system", "subtype", "compact_boundary", "logicalParentUuid", "a3"), summary("s2", "c2"),
            user("u4", "s2", "Latest question"), answer("a4", "u4", "Latest answer"));
        var history = new ClaudeHistory(config);
        var snapshot = object("id", "chat", "provider", "claude", "threadId", id, "title", "Complete conversation", "items", new Object[]{});
        var exported = ChatTranscript.claude(snapshot, history.transcript(id), true);
        assertEquals(List.of("u1", "m-a1:0", "u2", "m-a2:0", "u3", "m-a3:0", "u4", "m-a4:0"), ids(array(exported, "items")));
        assertFalse(GSON.toJson(exported).contains("This session is being continued"));
        assertFalse(GSON.toJson(exported).contains("Discarded branch"));
        assertEquals("c2", text(history.read(id).get(0).getAsJsonObject(), "uuid"));
        assertTrue(GSON.toJson(ChatTranscript.claude(snapshot, history.transcript(id), false)).contains("This session is being continued"));
    }
    @Test void keepsOriginalParentsWhenCompactionReappendsPreservedMessages() throws Exception {
        String id = save(user("u1", null, "First"), answer("a1", "u1", "First answer"), user("u2", "a1", "Preserved"), answer("a2", "u2", "Preserved answer"),
            object("uuid", "c", "type", "system", "subtype", "compact_boundary", "logicalParentUuid", "a2"), summary("s", "c"),
            user("u2", "s", "Preserved"), answer("a2", "u2", "Preserved answer"), user("u3", "a2", "Latest"));
        var items = array(ChatTranscript.claude(object("items", new Object[]{}), new ClaudeHistory(config).transcript(id), true), "items");
        assertEquals(List.of("u1", "m-a1:0", "u2", "m-a2:0", "u3"), ids(items));
    }
    @Test void recoversCompactionsWithStaleForkReferencesButRejectsBrokenOrdinaryAncestry() throws Exception {
        String id = save(user("u", null, "Earlier"), answer("a", "u", "Earlier reply"),
            object("uuid", "c", "type", "system", "subtype", "compact_boundary", "logicalParentUuid", "missing-source-uuid"), summary("s", "c"), user("latest", "s", "Latest"));
        var history = new ClaudeHistory(config);
        assertEquals("u", text(history.transcript(id).get(0).getAsJsonObject(), "uuid"));
        assertThrows(IOException.class, () -> history.transcript(save(user("broken", "missing", "Incomplete"))));
        assertThrows(IOException.class, () -> history.transcript(save(user("cycle", "cycle", "Loop"))));
        assertThrows(IOException.class, () -> history.transcript(UUID.randomUUID().toString()));
    }
    @Test void mergesLiveRepliesAndProviderHistoryWithoutRevivingGeneratedOrAbandonedPrompts() {
        var synthetic = user("meta", "u", "Hidden instructions"); synthetic.addProperty("isMeta", true);
        var notification = user("task", "meta", "Background task finished"); notification.addProperty("promptSource", "system");
        var frames = GSON.toJsonTree(List.of(user("u", null, "Actual prompt"), synthetic, notification, answer("a", "task", "Saved answer"))).getAsJsonArray();
        var snapshot = object("provider", "claude", "title", "Chat", "draft", "Unsent", "archived", true, "items", List.of(
            object("id", "carried", "historyProvider", "codex", "type", "agentMessage", "text", "Earlier provider"),
            object("id", "stale", "type", "userMessage", "text", "Old abandoned cache"),
            object("id", "meta", "type", "userMessage", "text", "Hidden instructions"),
            object("id", "m-a:0", "type", "agentMessage", "text", "Live answer"),
            object("id", "live", "type", "userMessage", "text", "Just sent")));
        var before = snapshot.deepCopy();
        var exported = ChatTranscript.claude(snapshot, frames, true);
        assertEquals(List.of("carried", "u", "m-a:0", "live"), ids(array(exported, "items")));
        assertTrue(GSON.toJson(exported).contains("Live answer"));
        assertFalse(GSON.toJson(exported).contains("Background task finished"));
        assertTrue(GSON.toJson(ChatTranscript.claude(snapshot, frames, false)).contains("Background task finished"));
        assertFalse(exported.has("draft")); assertEquals(before, snapshot);
        snapshot.add("items", GSON.toJsonTree(List.of(
            object("id", "carried", "historyProvider", "codex", "type", "agentMessage", "text", "Earlier provider"),
            object("id", "stale", "type", "userMessage", "text", "Old abandoned cache"))));
        assertFalse(GSON.toJson(ChatTranscript.claude(snapshot, frames, true)).contains("Old abandoned cache"));
    }
}
