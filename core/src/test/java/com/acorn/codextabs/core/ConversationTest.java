package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonArray;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ConversationTest {
    @Test void successfulResumeClearsConnectionErrorButKeepsFailedTurn() {
        var chat = new Conversation("chat", "/project");
        chat.loadFailed("Disconnected during resume"); assertEquals("error", chat.status());
        chat.loaded(); assertEquals("idle", chat.status()); assertEquals("", chat.get("error"));
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "failed", "status", "failed", "error", object("message", "Usage limit exceeded")))));
        chat.loaded(); assertEquals("error", chat.status()); assertEquals("Usage limit exceeded", chat.get("error"));
    }
    @Test void streamingAfterAutomaticRetryClearsWarning() {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", "turn/started", "params", object("turn", object("id", "turn"))));
        chat.event(object("method", "error", "params", object("turnId", "turn", "willRetry", true, "error", object("message", "Reconnecting"))));
        assertEquals("Reconnecting", chat.get("error"));
        chat.event(object("method", "item/agentMessage/delta", "params", object("itemId", "answer", "delta", "Recovered")));
        assertEquals("", chat.get("error")); assertEquals("working", chat.status());
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "turn", "status", "completed"))));
        assertEquals("complete", chat.status());
    }
    @Test void completionClearsRetryWithoutRequiringAnotherMessage() {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", "error", "params", object("willRetry", true, "error", object("message", "Retrying"))));
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "turn", "status", "completed"))));
        assertEquals("", chat.get("error")); assertEquals("complete", chat.status());
    }
    @Test void lateErrorFromOldTurnCannotPoisonCurrentWork() {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", "turn/started", "params", object("turn", object("id", "new"))));
        chat.event(object("method", "error", "params", object("turnId", "old", "willRetry", false, "error", object("message", "Old error"))));
        assertEquals("", chat.get("error"));
    }
    @Test void resumedActiveThreadIsRetainedButDelayedStatusCannotReviveCompletedTurn() {
        var chat = new Conversation("chat", "/project");
        chat.resumed(object("id", "thread", "status", object("type", "active")), chat.revision());
        assertTrue(chat.busy());
        long started = chat.revision();
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "turn", "status", "completed"))));
        chat.resumed(object("id", "thread", "status", object("type", "active")), started);
        assertFalse(chat.busy());
    }
    @Test void messageQuestionsNeedAttentionAndDoNotReturnAfterAnswerAndHistoryReload() {
        var chat = new Conversation("chat", "/project");
        var message = object("id", "question", "type", "agentMessage", "delivery", "async", "text", "Choose a layout", "questions", new Object[]{object("title", "Where should it open?", "options", new String[]{"Beside this chat", "Here"})});
        chat.event(object("method", "item/completed", "params", object("item", message)));
        assertEquals("attention", chat.status());
        assertEquals("Where should it open?", text(array(chat.request("question"), "questions").get(0).getAsJsonObject(), "question"));
        var restored = Conversation.restore(chat.snapshot());
        assertEquals("attention", restored.status());
        restored.resolve("question");
        var page = new JsonArray(); page.add(object("item", message));
        restored.historyPage(page, "");
        assertEquals("idle", restored.status());
        assertTrue(array(Conversation.restore(restored.snapshot()).snapshot(), "requests").isEmpty());
    }
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
    @Test void restartLoadsTheLatestPageAtTheEndInsteadOfAppendingOldCache() {
        var chat = new Conversation("chat", "/project");
        for (int i = 0; i < 140; i++) { chat.event(object("method", "item/completed", "params", object("item", historyItem(i)))); }
        var restored = Conversation.restore(chat.snapshot());
        restored.historyPage(historyEntries(40, 145), "earlier", restored.revision());
        assertEquals("message-144", text(restored.items().get(restored.items().size() - 1).getAsJsonObject(), "id"));
        assertEquals(105, restored.items().size());
        assertEquals("message-40", text(restored.items().get(0).getAsJsonObject(), "id"));
        assertEquals("earlier", restored.get("historyCursor"));
        assertEquals("Reply 144", text(restored.summary(), "preview"));
    }
    @Test void refreshRepairsAlreadyMisorderedCacheAndPublishesAFullSnapshot() {
        var chat = new Conversation("chat", "/project");
        for (int i : new int[]{4, 5, 0, 1, 2, 3}) { chat.event(object("method", "item/completed", "params", object("item", historyItem(i)))); }
        var restored = Conversation.restore(chat.snapshot());
        long before = restored.revision();
        restored.historyPage(historyEntries(2, 7), "earlier", before);
        assertFalse(flag(restored.changes(before), "partial"));
        for (int i = 0; i < 5; i++) { assertEquals("message-" + (i + 2), text(restored.items().get(i).getAsJsonObject(), "id")); }
        assertEquals("Reply 6", text(restored.summary(), "preview"));
    }
    @Test void latestPageKeepsMessagesStreamedWhileHistoryWasLoading() {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", "item/completed", "params", object("item", historyItem(0))));
        long started = chat.revision();
        chat.event(object("method", "item/completed", "params", object("item", historyItem(3))));
        chat.historyPage(historyEntries(1, 3), "older", started);
        assertEquals(3, chat.items().size());
        assertEquals("message-3", text(chat.items().get(2).getAsJsonObject(), "id"));
    }
    @Test void earlierPagesPrependWithoutDroppingTheLatestMessages() {
        var chat = new Conversation("chat", "/project");
        chat.historyPage(historyEntries(100, 140), "100");
        chat.historyPage(historyEntries(0, 100), "", chat.revision(), false);
        assertEquals(140, chat.items().size());
        for (int i = 0; i < 140; i++) { assertEquals("message-" + i, text(chat.items().get(i).getAsJsonObject(), "id")); }
        assertEquals("", chat.get("historyCursor"));
        assertEquals("Reply 139", text(chat.summary(), "preview"));
    }
    private static com.google.gson.JsonObject historyItem(int index) {
        return object("id", "message-" + index, "type", "agentMessage", "text", "Reply " + index);
    }
    private static JsonArray historyEntries(int from, int to) {
        var entries = new JsonArray();
        for (int i = to - 1; i >= from; i--) { entries.add(object("item", historyItem(i))); }
        return entries;
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
