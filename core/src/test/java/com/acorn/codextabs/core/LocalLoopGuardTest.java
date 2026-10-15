package com.acorn.codextabs.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalLoopGuardTest {
    @Test void warnsThenStopsWhenTheSameCommandKeepsReturningTheSameFailure() {
        var chat = local();
        for (int i = 1; i <= 5; i++) {
            completed(chat, "call-" + i, "cargo test", "Chunk ID: " + i + "\nWall time: " + i + ".12 seconds\nProcess exited with code 1\nOriginal token count: 4\nOutput:\nsame failure", 1);
            assertEquals(i >= 3, flag(memory(chat), "warned"));
            assertEquals(i == 5, flag(memory(chat), "stopped"));
        }
        assertTrue(chat.get("error").contains("Loop guard"));
        assertEquals("loopGuardStopped", chat.get("errorKind"));
        assertEquals(5, array(memory(chat), "recent").size());
    }
    @Test void catchesAlternatingReadAndFailedEditCycles() {
        var chat = local();
        for (int i = 0; i < 5; i++) {
            completed(chat, "read-" + i, "cat file.rs", "unchanged source", 0);
            completed(chat, "edit-" + i, "apply_patch patch.txt", "expected lines not found", 1);
        }
        assertTrue(flag(memory(chat), "stopped"));
    }
    @Test void changingResultsResetThatCommandsRepeatCount() {
        var chat = local();
        completed(chat, "1", "cargo test", "failure A", 1);
        completed(chat, "2", "cargo test", "failure A", 1);
        completed(chat, "3", "cargo test", "failure B", 1);
        completed(chat, "4", "cargo test", "failure B", 1);
        assertFalse(flag(memory(chat), "warned"));
        assertFalse(flag(memory(chat), "stopped"));
    }
    @Test void ignoresCloudModelsInFlightPollsDuplicateEventsAndOldTurns() {
        var cloud = new Conversation("cloud", "/fixture");
        cloud.event(start("turn"));
        for (int i = 0; i < 6; i++) {
            completed(cloud, "cloud-" + i, "cargo test", "same failure", 1);
        }
        assertTrue(memory(cloud).isEmpty());
        var chat = local();
        for (int i = 0; i < 6; i++) {
            chat.event(object("method", "item/completed", "params", object("turnId", "turn", "item", object("id", "poll-" + i, "type", "commandExecution", "command", "long task", "status", "inProgress", "aggregatedOutput", "still running"))));
            completed(chat, "same-id", "cargo test", "failure", 1);
            chat.event(object("method", "item/completed", "params", object("turnId", "old", "item", object("id", "late-" + i, "type", "commandExecution", "command", "cargo test", "status", "completed", "exitCode", 1, "aggregatedOutput", "failure"))));
        }
        assertFalse(flag(memory(chat), "warned"));
    }
    @Test void keepsCountsAcrossCompactionAndSavedConversationRestore() {
        var chat = local();
        completed(chat, "1", "cargo test", "same failure", 1);
        completed(chat, "2", "cargo test", "same failure", 1);
        chat.event(object("method", "item/completed", "params", object("item", object("id", "compact", "type", "contextCompaction"))));
        var restored = Conversation.restore(chat.snapshot());
        restored.event(start("turn"));
        completed(restored, "3", "cargo test", "same failure", 1);
        assertTrue(flag(memory(restored), "warned"));
    }
    @Test void boundsSavedStateAndStoresHashesInsteadOfToolContents() {
        var chat = local();
        for (int i = 0; i < 100; i++) {
            completed(chat, "call-" + i, "cat secret-" + i, "sensitive tool output " + i, 0);
        }
        assertEquals(24, array(memory(chat), "recent").size());
        assertFalse(memory(chat).toString().contains("sensitive tool output"));
        assertFalse(memory(chat).toString().contains("cat secret"));
    }
    @Test void comparesMcpArgumentsWithoutDependingOnJsonFieldOrderAndSkipsStatusPolls() {
        var chat = local();
        for (int i = 0; i < 6; i++) {
            chat.event(object("method", "item/completed", "params", object("turnId", "turn", "item", object("id", "status-" + i, "type", "mcpToolCall", "server", "fixture", "tool", "get_status", "arguments", object(), "status", "completed", "result", object("text", "pending")))));
        }
        assertFalse(flag(memory(chat), "warned"));
        for (int i = 0; i < 3; i++) {
            chat.event(object("method", "item/completed", "params", object("turnId", "turn", "item", object("id", "mcp-" + i, "type", "mcpToolCall", "server", "fixture", "tool", "read_file", "arguments", i % 2 == 0 ? object("path", "a", "limit", 10) : object("limit", 10, "path", "a"), "status", "completed", "result", object("text", "unchanged")))));
        }
        assertTrue(flag(memory(chat), "warned"));
    }
    @Test void clearsAWarningAfterSuccessfulCompletionButKeepsTheStopReason() {
        var chat = local();
        for (int i = 1; i <= 3; i++) { completed(chat, "warning-" + i, "cargo test", "same failure", 1); }
        assertTrue(chat.get("error").contains("Loop guard"));
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "turn", "status", "completed"))));
        assertEquals("", chat.get("error"));
        chat.event(start("turn"));
        for (int i = 4; i <= 5; i++) { completed(chat, "stop-" + i, "cargo test", "same failure", 1); }
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "turn", "status", "completed"))));
        assertTrue(chat.get("error").startsWith("Loop guard stopped"));
    }
    @Test void anMcpStatusChangeCountsAsProgressEvenWithoutAResultPayload() {
        var chat = local();
        for (int i = 0; i < 3; i++) {
            chat.event(object("method", "item/completed", "params", object("turnId", "turn", "item", object("id", "mcp-status-" + i, "type", "mcpToolCall", "server", "fixture", "tool", "apply_edit", "arguments", object("path", "a"), "status", i == 2 ? "completed" : "failed"))));
        }
        assertFalse(flag(memory(chat), "warned"));
    }
    @Test void stopsLongerCyclesEvenWhenTheirFirstCallsLeaveTheRecentWindow() {
        var chat = local();
        for (int cycle = 0; cycle < 5; cycle++) {
            completed(chat, "repeat-" + cycle, "cargo test", "same failure", 1);
            for (int step = 0; step < 7; step++) {
                completed(chat, "other-" + cycle + "-" + step, "inspect " + cycle + "-" + step, "different inspection", 0);
            }
        }
        assertTrue(flag(memory(chat), "stopped"));
    }
    private static Conversation local() {
        var chat = new Conversation("local", "/fixture");
        chat.set("modelProvider", LocalModel.PROVIDER);
        chat.set("threadId", "thread");
        chat.event(start("turn"));
        return chat;
    }
    private static JsonObject start(String turn) { return object("method", "turn/started", "params", object("turn", object("id", turn))); }
    private static JsonObject memory(Conversation chat) { return obj(chat.snapshot(), "localLoopGuard"); }
    private static void completed(Conversation chat, String id, String command, String output, int exit) {
        chat.event(object("method", "item/completed", "params", object("threadId", "thread", "turnId", "turn", "item", object("id", id, "type", "commandExecution", "command", command, "cwd", "/fixture", "aggregatedOutput", output, "exitCode", exit, "status", "completed"))));
    }
}
