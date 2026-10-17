package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ClaudeSteeringTest {
    @Test void interruptedResultsWaitForEverySteerAndRetainStreamingBlocks() {
        var chat = new Conversation("id", "/project"); var protocol = new ClaudeProtocol(chat);
        protocol.start("turn", input("original"));
        protocol.accept(stream(object("type", "message_start", "message", object("id", "reply"))));
        protocol.accept(stream(object("type", "content_block_start", "index", 0, "content_block", object("type", "text", "text", "Before "))));
        protocol.steer("first", input("correction")); protocol.steer("second", input("another correction"));
        protocol.accept(stream(object("type", "content_block_delta", "index", 0, "delta", object("type", "text_delta", "text", "after"))));
        assertEquals("Before after", text(chat.items().get(1).getAsJsonObject(), "text"));
        protocol.accept(object("type", "result", "is_error", true, "result", "Interrupted", "user_message_uuids", List.of("turn")));
        assertTrue(chat.busy()); assertEquals("", chat.get("error"));
        protocol.accept(object("type", "result", "user_message_uuids", List.of("turn", "first")));
        assertTrue(chat.busy());
        protocol.accept(object("type", "result", "user_message_uuids", List.of("second")));
        assertFalse(chat.busy());
    }
    @Test void olderResultsWaitForReplayBeforeCompleting() {
        var chat = new Conversation("id", "/project"); var protocol = new ClaudeProtocol(chat);
        protocol.start("turn", input("original")); protocol.steer("steer", input("visible correction"));
        protocol.accept(object("type", "result", "is_error", true, "result", "Interrupted"));
        assertTrue(chat.busy());
        protocol.accept(object("type", "user", "uuid", "steer", "message", object("content", input("wire-only context"))));
        assertFalse(GSON.toJson(chat.items()).contains("wire-only context"));
        protocol.accept(object("type", "assistant", "message", object("id", "corrected", "content", input("Corrected reply"))));
        protocol.accept(object("type", "result", "result", "Corrected reply"));
        assertFalse(chat.busy());
        assertTrue(GSON.toJson(chat.items()).contains("Corrected reply"));
    }
    @Test void replayCannotReplaceVisibleHandoffInputOrAttachments() {
        var chat = new Conversation("id", "/project"); var protocol = new ClaudeProtocol(chat);
        var input = input("continue here"); input.add(object("type", "localImage", "path", "/project/image.png"));
        protocol.start("turn", input);
        protocol.accept(object("type", "user", "uuid", "turn", "message", object("content", input("hidden provider context"))));
        assertEquals(1, chat.items().size()); assertEquals(input, array(chat.items().get(0).getAsJsonObject(), "content"));
        assertTrue(ClaudeClient.process("claude", "/tmp", "", UUID.randomUUID().toString(), "", false).command().contains("--replay-user-messages"));
    }
    @Test void finalSteeringErrorsStillFinishAndReportFailure() {
        var chat = new Conversation("id", "/project"); var protocol = new ClaudeProtocol(chat);
        protocol.start("turn", input("original")); protocol.steer("steer", input("correction"));
        protocol.accept(object("type", "result", "is_error", true, "result", "Rate limited", "user_message_uuid", "steer"));
        assertFalse(chat.busy()); assertEquals("Rate limited", chat.get("error"));
    }
    private static JsonArray input(String text) { return GSON.toJsonTree(List.of(object("type", "text", "text", text))).getAsJsonArray(); }
    private static JsonObject stream(JsonObject event) { return object("type", "stream_event", "event", event); }
}
