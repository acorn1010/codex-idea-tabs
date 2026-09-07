package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ConversationTest {
    @Test void archivePreservesHistoryDraftAndAttachmentsAcrossRestart() {
        var chat = new Conversation("chat", "/project");
        chat.set("draft", "Next step");
        chat.set("draftAttachments", new Object[]{object("path", "/project/image.png", "name", "image.png")});
        chat.event(object("method", "item/completed", "params", object("item", object("id", "answer", "type", "agentMessage", "text", "Keep this reply"))));
        chat.archive(true);
        var restored = Conversation.restore(chat.snapshot());
        assertTrue(restored.archived());
        assertTrue(flag(restored.summary(), "hasDraft"));
        assertEquals("Next step", restored.get("draft"));
        assertEquals(1, array(restored.snapshot(), "draftAttachments").size());
        assertEquals("Keep this reply", text(restored.items().get(0).getAsJsonObject(), "text"));
        restored.archive(false);
        assertFalse(restored.archived());
        assertEquals(1, restored.items().size());
    }
    @Test void archiveCannotHideWorkingChatsOrPendingQuestions() {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", "turn/started", "params", object("turn", object("id", "turn"))));
        assertThrows(IllegalStateException.class, () -> chat.archive(true));
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "turn"))));
        chat.event(object("method", "item/tool/requestUserInput", "params", object("itemId", "choice", "isBlocking", false)));
        assertThrows(IllegalStateException.class, () -> chat.archive(true));
        assertFalse(chat.archived());
        chat.resolve("choice");
        assertDoesNotThrow(() -> chat.archive(true));
    }
    @Test void previouslyHiddenChatsCanBeFoundAndRestored() {
        var chat = new Conversation("chat", "/project");
        chat.set("hidden", true);
        chat = Conversation.restore(chat.snapshot());
        assertTrue(flag(chat.summary(), "archived"));
        chat.archive(false);
        assertFalse(chat.archived());
        assertEquals("false", chat.get("hidden"));
    }
    @Test void historyRefreshReplacesCachedItemsButKeepsNewerStreamedText() {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", "item/completed", "params", object("item", object("id", "answer", "type", "agentMessage", "text", "Cached"))));
        var page = new com.google.gson.JsonArray();
        page.add(object("item", object("id", "answer", "type", "agentMessage", "text", "Server")));
        chat.historyPage(page, "", chat.revision());
        assertEquals("Server", text(chat.items().get(0).getAsJsonObject(), "text"));
        long started = chat.revision();
        chat.event(object("method", "item/agentMessage/delta", "params", object("itemId", "answer", "delta", " plus live text")));
        chat.historyPage(page, "", started);
        assertEquals("Server plus live text", text(chat.items().get(0).getAsJsonObject(), "text"));
    }
    @Test void streamingChangesAreBoundedByTheChangedItem() {
        var chat = new Conversation("local", "/project");
        for (int i = 0; i < 1000; i++) {
            chat.event(object("method", "item/completed", "params", object("item", object("id", "m" + i, "type", "agentMessage", "text", "Long history ".repeat(50)))));
        }
        long previous = chat.revision();
        chat.event(object("method", "item/agentMessage/delta", "params", object("itemId", "current", "delta", "One new token")));
        var update = chat.changes(previous);
        assertEquals(1, array(update, "items").size());
        assertTrue(GSON.toJson(update).length() < 1500);
        assertTrue(flag(update, "partial"));
    }
    @Test void asyncQuestionOutlivesTurnAndRestart() {
        var chat = new Conversation("local", "/project");
        chat.event(object("method", "turn/started", "params", object("turn", object("id", "t1"))));
        chat.event(object("method", "item/tool/requestUserInput", "params", object("itemId", "q1", "isBlocking", false)));
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "t1"))));
        assertEquals("attention", chat.status());
        var restored = Conversation.restore(chat.snapshot());
        assertEquals("attention", restored.status());
        restored.resolve("q1");
        assertEquals("complete", restored.status());
    }
    @Test void staleCompletionDoesNotEndNewTurn() {
        var chat = new Conversation("local", "/project");
        chat.event(object("method", "turn/started", "params", object("turn", object("id", "new"))));
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "old"))));
        assertEquals("working", chat.status());
    }
    @Test void streamingUpdatesOneItemAndCompletionReplacesIt() {
        var chat = new Conversation("local", "/project");
        chat.event(object("method", "item/agentMessage/delta", "params", object("itemId", "m", "delta", "Hello ")));
        chat.event(object("method", "item/agentMessage/delta", "params", object("itemId", "m", "delta", "world")));
        assertEquals("Hello world", text(chat.items().get(0).getAsJsonObject(), "text"));
        chat.event(object("method", "item/completed", "params", object("item", object("id", "m", "type", "agentMessage", "text", "Hello world!"))));
        assertEquals(1, chat.items().size());
        assertEquals("Hello world!", text(chat.items().get(0).getAsJsonObject(), "text"));
    }
    @Test void disconnectDropsDeadCallbacksButKeepsAsyncQuestions() {
        var chat = new Conversation("local", "/project");
        chat.event(object("id", 3, "method", "item/commandExecution/requestApproval", "params", object("itemId", "command")));
        chat.event(object("method", "item/tool/requestUserInput", "params", object("itemId", "async", "isBlocking", false)));
        chat.disconnected();
        assertEquals(1, array(chat.snapshot(), "requests").size());
        assertEquals("attention", chat.status());
    }
    @Test void newerLiveEventsWinOverHistoryResponse() {
        var chat = new Conversation("local", "/project");
        long revision = chat.revision();
        chat.event(object("method", "item/agentMessage/delta", "params", object("itemId", "m", "delta", "New")));
        chat.hydrate(object("id", "thread", "turns", new Object[]{object("items", new Object[]{object("id", "m", "type", "agentMessage", "text", "Old")})}), revision);
        assertEquals("New", text(chat.items().get(0).getAsJsonObject(), "text"));
    }
}
