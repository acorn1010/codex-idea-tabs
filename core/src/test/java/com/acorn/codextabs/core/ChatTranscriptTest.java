package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatTranscriptTest {
    @Test void readsEveryPageInOrderAndKeepsTheVisibleLiveTextWithoutChangingTheChat() {
        var local = object("title", "A chat", "threadId", "thread", "archived", true, "draft", "Do not copy this draft", "historyCursor", "stale",
            "items", new Object[]{object("id", "latest", "type", "agentMessage", "text", "Live reply so far")});
        var before = local.deepCopy();
        var cursors = new ArrayList<String>();
        var exported = ChatTranscript.read(local, cursor -> {
            cursors.add(cursor);
            return cursor.isEmpty() ? object("data", new Object[]{object("item", object("id", "latest", "type", "agentMessage", "text", "Older saved reply")), object("item", object("id", "middle", "type", "userMessage", "text", "Middle"))}, "nextCursor", "older")
                : object("data", new Object[]{object("item", object("id", "middle", "type", "userMessage", "text", "Overlap")), object("item", object("id", "first", "type", "userMessage", "text", "First"))}, "nextCursor", null);
        });
        assertEquals(List.of("", "older"), cursors);
        var items = array(exported, "items");
        assertEquals(3, items.size());
        assertEquals("first", text(items.get(0).getAsJsonObject(), "id"));
        assertEquals("Middle", text(items.get(1).getAsJsonObject(), "text"));
        assertEquals("Live reply so far", text(items.get(2).getAsJsonObject(), "text"));
        assertEquals(before, local);
        assertFalse(exported.has("draft"));
    }
    @Test void keepsUnpersistedItemsAndDoesNotContactTheServerForANewChat() {
        var local = object("title", "New chat", "items", new Object[]{object("id", "local", "type", "userMessage", "text", "Hello")});
        var exported = ChatTranscript.read(local, cursor -> { fail("A local chat has no server history"); return null; });
        assertEquals(array(local, "items"), array(exported, "items"));
    }
    @Test void failsInsteadOfCopyingTruncatedHistoryOrLoopingOnACursor() {
        var local = object("threadId", "thread");
        assertThrows(IllegalStateException.class, () -> ChatTranscript.read(local, cursor -> {
            if (!cursor.isEmpty()) { throw new IllegalStateException("Disconnected"); }
            return object("data", new Object[]{}, "nextCursor", "older");
        }));
        assertThrows(IllegalStateException.class, () -> ChatTranscript.read(local, cursor -> object("data", new Object[]{}, "nextCursor", "same")));
    }
}
