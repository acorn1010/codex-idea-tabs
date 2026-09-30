package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderHandoffTest {
    private static Conversation source() {
        var chat = new Conversation("tab", "/checkout");
        chat.set("threadId", "codex-thread"); chat.set("title", "My chat"); chat.set("draft", "Unsent text");
        chat.set("draftAttachments", List.of(object("path", "/picture.png"))); chat.set("workspaceBase", "main"); chat.set("pinned", true);
        chat.event(object("method", "item/completed", "params", object("item", object("id", "question", "type", "userMessage", "content", List.of(object("type", "text", "text", "Keep this requirement"))))));
        chat.event(object("method", "item/completed", "params", object("item", object("id", "answer", "type", "agentMessage", "text", "A previous answer"))));
        chat.event(object("method", "item/completed", "params", object("item", object("id", "tool", "type", "commandExecution", "aggregatedOutput", "private tool output"))));
        return chat;
    }
    @Test void preparesIndependentSessionWithoutMutatingOriginalOrLosingDrafts() {
        var old = source(); var before = old.snapshot();
        var next = ProviderHandoff.prepare(before, old.items(), "claude");
        assertEquals(before, old.snapshot());
        assertEquals(old.id, next.id); assertEquals("/checkout", next.get("cwd"));
        assertEquals("My chat", next.get("title")); assertEquals("Unsent text", next.get("draft"));
        assertEquals("main", next.get("workspaceBase")); assertEquals("true", next.get("pinned"));
        assertEquals(array(before, "draftAttachments"), array(next.snapshot(), "draftAttachments"));
        assertEquals("", next.get("threadId")); assertEquals("claude", next.get("provider"));
        assertEquals(3, next.items().size());
        assertTrue(next.get("providerContext").contains("Keep this requirement"));
        assertTrue(next.get("providerContext").contains("A previous answer"));
        assertFalse(next.get("providerContext").contains("private tool output"));
        assertFalse(next.get("providerContext").contains("Unsent text"));
        assertEquals("true", next.get("providerContextPending"));
        next.replaceAfter(old.revision()); assertFalse(flag(next.changes(old.revision()), "partial"));
    }
    @Test void survivesRepeatedSwitchesHistoryRefreshAndRestartWithOriginalOrdering() {
        var old = source(); var claude = ProviderHandoff.prepare(old.snapshot(), old.items(), "claude");
        String carriedId = text(claude.items().get(0).getAsJsonObject(), "id");
        var protocol = new ClaudeProtocol(claude);
        protocol.start("new-user", input("Next question"));
        protocol.accept(object("type", "assistant", "message", object("id", "answer", "content", List.of(object("type", "text", "text", "Claude answer")))));
        protocol.accept(object("type", "result", "subtype", "success"));
        claude.replaceClaudeHistory(GSON.toJsonTree(List.of(object("id", "new-user", "type", "userMessage", "content", input("Next question")), object("id", "answer:0", "type", "agentMessage", "text", "Claude answer"))).getAsJsonArray());
        assertEquals(5, claude.items().size()); assertEquals(carriedId, text(claude.items().get(0).getAsJsonObject(), "id"));
        var codex = ProviderHandoff.prepare(claude.snapshot(), claude.items(), "codex");
        assertEquals(5, codex.items().size()); assertEquals("claude", text(codex.items().get(4).getAsJsonObject(), "historyProvider"));
        assertEquals("codex", text(codex.items().get(1).getAsJsonObject(), "historyProvider"));
        codex.set("threadId", "new-codex");
        var latest = object("id", "answer", "type", "agentMessage", "text", "New Codex answer");
        var page = GSON.toJsonTree(List.of(latest)).getAsJsonArray();
        codex.historyPage(page, "older"); assertEquals(6, codex.items().size());
        var restarted = Conversation.restore(codex.snapshot());
        restarted.historyPage(page, "");
        assertEquals(6, restarted.items().size()); assertEquals(carriedId, text(restarted.items().get(0).getAsJsonObject(), "id"));
        var exported = ChatTranscript.read(restarted.snapshot(), cursor -> object("data", page, "nextCursor", ""));
        assertEquals(restarted.items(), array(exported, "items"));
        assertEquals("codex", text(exported, "provider"));
    }
    @Test void hidesInjectedContextInLiveEventsHistoryAndMarkdownExport() {
        var old = source(); var chat = ProviderHandoff.prepare(old.snapshot(), old.items(), "claude");
        var wireInput = ProviderHandoff.input(chat, input("Actual next message"));
        assertEquals(2, wireInput.size());
        var protocol = new ClaudeProtocol(chat);
        protocol.accept(object("type", "user", "uuid", "next", "message", object("content", wireInput)));
        assertEquals(4, chat.items().size());
        assertEquals("Actual next message", text(array(chat.items().get(3).getAsJsonObject(), "content").get(0).getAsJsonObject(), "text"));
        chat.set("providerContextPending", false);
        assertEquals(input("Second"), ProviderHandoff.input(chat, input("Second")));
        var contextOnly = object("id", "injected", "type", "userMessage", "content", List.of(object("type", "text", "text", chat.get("providerContext"))));
        chat.set("threadId", "thread");
        var page = GSON.toJsonTree(List.of(contextOnly)).getAsJsonArray();
        var exported = ChatTranscript.read(chat.snapshot(), cursor -> object("data", page));
        assertEquals(4, array(exported, "items").size());
        assertFalse(GSON.toJson(array(exported, "items")).contains("Codex Tabs conversation context"));
        chat.historyPage(page, ""); assertEquals(3, chat.items().size());
        var injected = array(ProviderHandoff.injection(chat), "items").get(0).getAsJsonObject();
        assertEquals("user", text(injected, "role"));
        assertEquals(chat.get("providerContext"), text(array(injected, "content").get(0).getAsJsonObject(), "text"));
    }
    @Test void rejectsBusyArchivedPendingAndQueuedChats() {
        for (String field : List.of("working", "archived", "hidden")) {
            var chat = source(); chat.set(field, true);
            assertThrows(IllegalStateException.class, () -> ProviderHandoff.prepare(chat.snapshot(), chat.items(), "claude"));
        }
        var chat = source(); chat.set("claudeQueue", List.of(object("id", "queued")));
        assertThrows(IllegalStateException.class, () -> ProviderHandoff.prepare(chat.snapshot(), chat.items(), "codex"));
        chat.set("claudeQueue", new JsonArray());
        chat.event(object("method", "item/tool/requestUserInput", "params", object("itemId", "question")));
        assertThrows(IllegalStateException.class, () -> ProviderHandoff.prepare(chat.snapshot(), chat.items(), "claude"));
        assertThrows(IllegalArgumentException.class, () -> ProviderHandoff.prepare(source().snapshot(), new JsonArray(), "unknown"));
    }
    @Test void boundsTransferredTextWhilePreservingAllVisibleHistory() {
        var old = source();
        old.event(object("method", "item/completed", "params", object("item", object("id", "large", "type", "agentMessage", "text", "x".repeat(210_000) + "Newest details"))));
        var next = ProviderHandoff.prepare(old.snapshot(), old.items(), "claude");
        assertTrue(next.get("providerContext").length() < 202_000);
        assertTrue(next.get("providerContext").contains("Newest details"));
        assertFalse(next.get("providerNotice").isBlank());
        assertEquals(text(old.items().get(3).getAsJsonObject(), "text"), text(next.items().get(3).getAsJsonObject(), "text"));
    }
    private static JsonArray input(String text) { return GSON.toJsonTree(List.of(object("type", "text", "text", text))).getAsJsonArray(); }
}
