package com.acorn.codextabs.core;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ClaudeActivityTest {
    @Test void tasksOutliveTheReplyWithoutBlockingAnotherMessage() {
        var chat = new Conversation("chat", "/project"); var protocol = new ClaudeProtocol(chat);
        protocol.start("turn", new JsonArray());
        protocol.accept(task("task_started", "build", "description", "Build images"));
        protocol.accept(task("task_started", "tests", "description", "Run tests"));
        protocol.accept(object("type", "result"));
        assertFalse(chat.busy()); assertTrue(chat.hasBackgroundWork()); assertEquals("working", chat.status());
        assertEquals(2, tasks(chat).size());
        long revision = chat.revision();
        protocol.accept(task("task_progress", "build", "description", "Build images"));
        assertEquals(revision, chat.revision(), "Unchanged progress must not refresh the transcript");
        protocol.accept(task("task_progress", "build", "description", "Upload images"));
        assertEquals("Upload images", text(tasks(chat).getFirst().getAsJsonObject(), "description"));
        protocol.accept(task("task_notification", "build", "status", "completed"));
        assertEquals(1, tasks(chat).size());
        protocol.accept(task("task_updated", "tests", "patch", object("status", "failed")));
        assertFalse(chat.hasBackgroundWork()); assertEquals("complete", chat.status());
        protocol.accept(task("task_progress", "build", "description", "Late progress"));
        assertFalse(chat.hasBackgroundWork(), "Late progress must not revive a finished task");
    }
    @Test void terminalUpdatesAndOutOfOrderToolResultsDoNotLeaveStaleTasks() {
        var chat = new Conversation("chat", "/project"); var protocol = new ClaudeProtocol(chat);
        for (String status : List.of("completed", "failed", "stopped", "killed")) {
            protocol.accept(task("task_started", status, "description", "Task"));
            protocol.accept(task("task_updated", status, "status", status));
        }
        protocol.accept(task("task_notification", "shell", "status", "completed"));
        tool(protocol, "bash", "Bash", object("command", "build"));
        result(protocol, "bash", object("backgroundTaskId", "shell"), "Running", false);
        assertFalse(chat.hasBackgroundWork());
        tool(protocol, "agent", "Agent", new JsonObject());
        result(protocol, "agent", object("status", "async_launched", "agentId", "agent-1", "description", "Review"), "Started", false);
        protocol.accept(task("task_started", "agent-1", "description", "Review"));
        assertEquals(1, tasks(chat).size(), "Tool results and task events must share one task");
        tool(protocol, "stop", "TaskStop", object("task_id", "agent-1"));
        result(protocol, "stop", new JsonObject(), "Stopped", false);
        assertFalse(chat.hasBackgroundWork());
    }
    @Test void successfulWakeupUsesConfirmedTimeAndResumedReplyGetsAFreshTurn() {
        var now = new AtomicLong(1_000); var chat = new Conversation("chat", "/project");
        var protocol = new ClaudeProtocol(chat, true, now::get); protocol.start("first", new JsonArray());
        tool(protocol, "wake", "ScheduleWakeup", object("reason", "Wait for tests", "delaySeconds", 60));
        result(protocol, "wake", object("scheduledFor", 63_000), "Scheduled", false);
        protocol.accept(object("type", "result"));
        assertFalse(chat.busy()); assertTrue(chat.hasBackgroundWork());
        assertEquals(63_000, wakeup(chat).get("scheduledFor").getAsLong());
        now.set(64_000);
        protocol.accept(object("type", "stream_event", "event", object("type", "message_start", "message", object("id", "resumed"))));
        assertTrue(chat.busy()); assertFalse(chat.hasBackgroundWork()); assertNotEquals("first", chat.get("turnId"));
        protocol.accept(object("type", "result"));
        assertFalse(chat.busy()); assertEquals("complete", chat.status());
    }
    @Test void textOnlyWakeupCanBeReplacedOrCancelledAndFailedToolsDoNotChangeIt() {
        var chat = new Conversation("chat", "/project"); var protocol = new ClaudeProtocol(chat, true, () -> 1_000);
        tool(protocol, "wake", "ScheduleWakeup", object("reason", "Wait", "delaySeconds", 1800));
        result(protocol, "wake", new JsonObject(), "Next wakeup scheduled for 14:25:00 (in 1837s). More text.", false);
        assertEquals(1_838_000, wakeup(chat).get("scheduledFor").getAsLong(), "Use reported delay including jitter");
        tool(protocol, "failed", "ScheduleWakeup", object("stop", true));
        result(protocol, "failed", new JsonObject(), "Denied", true);
        assertTrue(chat.hasBackgroundWork());
        tool(protocol, "replace", "ScheduleWakeup", object("reason", "New wait"));
        result(protocol, "replace", object("scheduledFor", 99_000), "Scheduled", false);
        assertEquals(99_000, wakeup(chat).get("scheduledFor").getAsLong());
        tool(protocol, "stop", "ScheduleWakeup", object("stop", true));
        result(protocol, "stop", new JsonObject(), "Cancelled", false);
        assertFalse(chat.hasBackgroundWork());
    }
    @Test void reconnectAndHistoryReplayCannotClaimOldWorkIsStillRunning() {
        var chat = new Conversation("chat", "/project"); var protocol = new ClaudeProtocol(chat);
        protocol.accept(task("task_started", "build", "description", "Build"));
        assertFalse(Conversation.restore(chat.snapshot()).hasBackgroundWork());
        chat.disconnected(); assertFalse(chat.hasBackgroundWork());
        assertTrue(obj(chat.changes(chat.revision() - 1), "claudeActivity").isEmpty());
        var history = new ClaudeProtocol(chat, false);
        history.accept(task("task_started", "old", "description", "Old task"));
        tool(history, "wake", "ScheduleWakeup", object("reason", "Old wakeup"));
        result(history, "wake", object("scheduledFor", Long.MAX_VALUE), "Scheduled", false);
        history.accept(object("type", "stream_event", "event", object("type", "message_start", "message", object("id", "old"))));
        assertFalse(chat.hasBackgroundWork()); assertFalse(chat.busy());
    }
    @Test void nestedAgentMessagesCannotStartAForegroundReply() {
        var chat = new Conversation("chat", "/project"); var protocol = new ClaudeProtocol(chat);
        protocol.accept(object("type", "stream_event", "parent_tool_use_id", "parent", "event", object("type", "message_start", "message", object("id", "child"))));
        assertFalse(chat.busy());
    }
    private static List<JsonElement> tasks(Conversation chat) { return array(obj(chat.snapshot(), "claudeActivity"), "tasks").asList(); }
    private static JsonObject wakeup(Conversation chat) { return obj(obj(chat.snapshot(), "claudeActivity"), "wakeup"); }
    private static JsonObject task(String subtype, String id, String key, Object value) { return object("type", "system", "subtype", subtype, "task_id", id, key, value); }
    private static void tool(ClaudeProtocol protocol, String id, String name, JsonObject input) {
        protocol.accept(object("type", "assistant", "uuid", id, "message", object("id", id, "content", List.of(object("type", "tool_use", "id", id, "name", name, "input", input)))));
    }
    private static void result(ClaudeProtocol protocol, String id, JsonObject data, String output, boolean error) {
        protocol.accept(object("type", "user", "tool_use_result", data, "message", object("content", List.of(object("type", "tool_result", "tool_use_id", id, "content", output, "is_error", error)))));
    }
}
