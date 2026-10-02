package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ConversationStoreTest {
    @TempDir Path root;
    @Test void migratesLegacyHistoryAndOnlyWritesTheChangedChat() throws Exception {
        var cache = root.resolve("chats.json");
        var one = new Conversation("one", "/repo"); one.set("draft", "Keep draft");
        var two = new Conversation("two", "/other"); two.set("provider", "claude"); two.archive(true);
        two.event(object("method", "item/completed", "params", object("item", object("id", "reply", "type", "agentMessage", "text", "Saved answer"))));
        String original = GSON.toJson(List.of(one.snapshot(), two.snapshot())); Files.writeString(cache, original);
        var store = new ConversationStore(cache); var chats = store.load(root.resolve("missing")); store.save(chats);
        assertEquals(original, Files.readString(cache), "Migration must preserve the old cache");
        var directory = root.resolve("chats");
        var stamp = FileTime.fromMillis(1000);
        try (var files = Files.list(directory)) { for (var path : files.toList()) { Files.setLastModifiedTime(path, stamp); } }
        store.save(chats);
        try (var files = Files.list(directory)) { for (var path : files.toList()) { assertEquals(stamp, Files.getLastModifiedTime(path)); } }
        chats.getFirst().set("draft", "New draft"); store.save(chats);
        try (var files = Files.list(directory)) { assertEquals(1, files.filter(path -> { try { return !Files.getLastModifiedTime(path).equals(stamp); } catch (Exception error) { throw new RuntimeException(error); } }).count()); }
        var restored = new ConversationStore(cache).load(root.resolve("missing"));
        var byId = new HashMap<String, Conversation>(); restored.forEach(chat -> byId.put(chat.id, chat));
        assertEquals("New draft", byId.get("one").get("draft"));
        assertEquals("claude", byId.get("two").get("provider")); assertTrue(byId.get("two").archived());
        assertEquals("Saved answer", text(byId.get("two").items().get(0).getAsJsonObject(), "text"));
    }
    @Test void replacementWithTheSameRevisionIsStillSaved() throws Exception {
        var cache = root.resolve("chats.json"); var store = new ConversationStore(cache);
        var first = new Conversation("same", "/one"); first.set("draft", "old"); store.save(List.of(first));
        var replacement = new Conversation("same", "/two"); replacement.set("draft", "new");
        assertEquals(first.revision(), replacement.revision()); store.save(List.of(replacement));
        var restored = new ConversationStore(cache).load(root.resolve("missing")).getFirst();
        assertEquals("/two", restored.get("cwd")); assertEquals("new", restored.get("draft"));
    }
    @Test void retriesIncompleteMigrationAndDoesNotReadUnindexedFiles() throws Exception {
        var cache = root.resolve("chats.json");
        var chat = new Conversation("../outside", "/repo"); chat.set("draft", "kept");
        Files.writeString(cache, GSON.toJson(List.of(chat.snapshot())));
        var store = new ConversationStore(cache); var chats = store.load(root.resolve("missing"));
        Files.createDirectories(root.resolve("chats/index.json.tmp"));
        assertThrows(java.io.IOException.class, () -> store.save(chats));
        assertFalse(Files.exists(root.resolve("chats/index.json")));
        Files.delete(root.resolve("chats/index.json.tmp"));
        var retry = new ConversationStore(cache); var restored = retry.load(root.resolve("missing")); retry.save(restored);
        Files.writeString(root.resolve("chats/" + "f".repeat(64) + ".json"), "not valid json");
        assertEquals("kept", new ConversationStore(cache).load(root.resolve("missing")).getFirst().get("draft"));
        assertFalse(Files.exists(root.resolve("outside")));
    }
}
