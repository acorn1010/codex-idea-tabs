package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ConversationActivityTest {
    @Test void reasoningStartedAfterRestoreRecoversWorkingState() {
        var original = new Conversation("chat", "/project");
        original.event(object("method", "turn/started", "params", object("turn", object("id", "turn"))));
        var chat = Conversation.restore(original.snapshot());
        assertFalse(chat.busy());
        long before = chat.revision();
        chat.event(object("method", "item/started", "params", object("turnId", "turn", "item", object("id", "thinking", "type", "reasoning"))));
        assertTrue(chat.busy());
        assertEquals("turn", chat.get("turnId"));
        assertEquals("working", text(chat.summary(), "status"));
        assertTrue(flag(chat.changes(before), "working"));
        assertFalse(chat.canArchive());
    }

    @ParameterizedTest
    @ValueSource(strings = {"item/reasoning/summaryTextDelta", "item/reasoning/textDelta", "item/agentMessage/delta", "item/commandExecution/outputDelta"})
    void streamingWithoutTurnStartedRecoversWorkingState(String method) {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", method, "params", object("turnId", "turn", "itemId", "item", "delta", "Progress")));
        assertTrue(chat.busy());
        assertEquals("working", chat.status());
        assertEquals("turn", chat.get("turnId"));
    }

    @Test void delayedIdleResumeCannotHideLiveReasoning() {
        var chat = new Conversation("chat", "/project");
        long beforeResume = chat.revision();
        chat.event(object("method", "item/reasoning/summaryTextDelta", "params", object("turnId", "turn", "itemId", "thinking", "delta", "Progress")));
        chat.resumed(object("id", "thread", "status", object("type", "idle")), beforeResume);
        assertEquals("working", chat.status());
        assertEquals("turn", chat.get("turnId"));
    }

    @Test void lateActivityFromCompletedTurnDoesNotReviveIt() {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "old", "status", "completed"))));
        chat.event(object("method", "item/started", "params", object("turnId", "old", "item", object("id", "thinking", "type", "reasoning"))));
        chat.event(object("method", "item/reasoning/textDelta", "params", object("turnId", "old", "itemId", "thinking", "delta", "Late text")));
        assertFalse(chat.busy());
        assertEquals("complete", chat.status());
        assertEquals("", chat.get("turnId"));
    }

    @Test void lateActivityCannotReplaceAnotherActiveTurn() {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", "turn/started", "params", object("turn", object("id", "new"))));
        chat.event(object("method", "item/agentMessage/delta", "params", object("turnId", "old", "itemId", "answer", "delta", "Late text")));
        assertEquals("new", chat.get("turnId"));
        assertEquals("working", chat.status());
    }

    @Test void historicalThinkingDoesNotMarkAnIdleChatWorking() {
        var chat = new Conversation("chat", "/project");
        chat.hydrate(object("id", "thread", "turns", new Object[]{object("id", "old", "status", "completed", "items", new Object[]{object("id", "thinking", "type", "reasoning")})}), chat.revision());
        assertEquals("idle", chat.status());
        assertFalse(chat.busy());
    }

    @Test void recoveredTurnFinishesAndStillKeepsUnansweredChoicesVisible() {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", "item/reasoning/textDelta", "params", object("turnId", "turn", "itemId", "thinking", "delta", "Progress")));
        chat.event(object("method", "item/tool/requestUserInput", "params", object("turnId", "turn", "itemId", "choice", "isBlocking", false)));
        assertEquals("attention", chat.status());
        assertTrue(chat.busy());
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "turn", "status", "completed"))));
        assertFalse(chat.busy());
        assertEquals("attention", chat.status());
        chat.resolve("choice");
        assertEquals("complete", chat.status());
    }
}
