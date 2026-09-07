package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ConversationTest {
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
