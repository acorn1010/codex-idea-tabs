package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ConversationReasoningTest {
    @Test void completedReasoningReplacesPartialStreamWithFullText() {
        for (String field : new String[]{"summary", "content"}) {
            var chat = new Conversation("chat", "/project");
            chat.event(object("method", "item/reasoning/textDelta", "params", object("itemId", "thinking", "delta", "second half")));
            chat.event(object("method", "item/completed", "params", object("item", object("id", "thinking", "type", "reasoning", field, new String[]{"First half and second half"}))));
            var item = chat.items().get(0).getAsJsonObject();
            assertEquals("", text(item, "text"), "Partial text must not hide the complete reasoning " + field);
            assertEquals("First half and second half", array(item, field).get(0).getAsString());
        }
    }

    @Test void emptyCompletionKeepsAvailableStreamedReasoning() {
        var chat = new Conversation("chat", "/project");
        chat.event(object("method", "item/reasoning/summaryTextDelta", "params", object("itemId", "thinking", "delta", "Keep this visible text")));
        chat.event(object("method", "item/completed", "params", object("item", object("id", "thinking", "type", "reasoning", "summary", new String[]{}, "content", new String[]{}))));
        assertEquals("Keep this visible text", text(chat.items().get(0).getAsJsonObject(), "text"));
    }
}
