package com.acorn.codextabs.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiRetryTest {
    private static Conversation chat() {
        var chat = new Conversation("api", "/project");
        chat.set("apiConnectionId", "routeway"); chat.set("threadId", "thread");
        chat.set("draft", "Unsent draft");
        chat.set("goal", object("objective", "Finish the task"));
        return chat;
    }
    private static JsonObject failed(Conversation chat, String id, int status) {
        chat.event(object("method", "turn/started", "params", object("turn", object("id", id))));
        var turn = object("id", id, "status", "failed", "error", object("message", "Fixture API failure",
            "codexErrorInfo", object("responseTooManyFailedAttempts", object("httpStatusCode", status))));
        chat.event(object("method", "turn/completed", "params", object("turn", turn)));
        return turn;
    }
    @Test void waitsWithIncreasingDelaysThenKeepsRetryingEveryMinute() {
        var chat = chat();
        int attempt = 0;
        JsonObject lastRetry = object();
        for (int delay : new int[]{5, 10, 20, 40, 60, 60, 60, 60}) {
            var turn = failed(chat, "turn-" + ++attempt, 429);
            var retry = chat.scheduleApiRetry(turn);
            assertFalse(retry.isEmpty(), "Rate limits must keep retrying after attempt five");
            assertEquals(attempt, retry.get("attempt").getAsInt());
            assertEquals(delay, retry.get("delaySeconds").getAsInt());
            lastRetry = retry;
            assertTrue(chat.busy()); assertFalse(chat.canArchive()); assertEquals("working", chat.status());
            assertTrue(chat.scheduleApiRetry(turn).isEmpty(), "Duplicate completion must not add a retry");
            chat.event(object("method", "thread/status/changed", "params", object("status", object("type", "idle"))));
            assertTrue(chat.busy(), "An idle server must not hide the pending retry");
            assertTrue(chat.beginApiRetry(retry)); assertFalse(chat.beginApiRetry(retry));
        }
        assertTrue(chat.busy()); assertEquals("", chat.get("error"));
        chat.cancelApiRetry(false);
        assertFalse(chat.busy()); assertFalse(chat.beginApiRetry(lastRetry));
        assertTrue(chat.scheduleApiRetry(failed(chat, "stopped", 429)).isEmpty());
        assertEquals("Unsent draft", chat.get("draft"));
        assertEquals("Finish the task", text(obj(chat.snapshot(), "goal"), "objective"));
        chat.cancelApiRetry(true);
        assertEquals(1, chat.scheduleApiRetry(failed(chat, "manual", 429)).get("attempt").getAsInt());
    }
    @Test void stopDisconnectAndNewInputInvalidateScheduledWork() {
        for (String action : new String[]{"stop", "disconnect", "input"}) {
            var chat = chat(); var retry = chat.scheduleApiRetry(failed(chat, "first", 429));
            switch (action) {
                case "stop" -> chat.cancelApiRetry(false);
                case "disconnect" -> chat.disconnected();
                default -> chat.cancelApiRetry(true);
            }
            assertFalse(chat.beginApiRetry(retry), action); assertFalse(chat.busy(), action);
            chat.apiRetryFailed(retry, "Stale request failure"); assertEquals("", chat.get("error"));
            if (!action.equals("input")) { assertTrue(chat.scheduleApiRetry(failed(chat, "late", 429)).isEmpty()); }
        }
    }
    @Test void automaticTurnsKeepTheBackoffAndRestoreDoesNotResumeTimers() {
        var chat = chat(); var retry = chat.scheduleApiRetry(failed(chat, "first", 429));
        var restored = Conversation.restore(chat.snapshot());
        assertFalse(restored.busy()); assertFalse(restored.snapshot().has("apiRetry"));
        assertTrue(restored.scheduleApiRetry(failed(restored, "restored", 429)).isEmpty());
        chat.event(object("method", "turn/started", "params", object("turn", object("id", "automatic"))));
        assertFalse(chat.beginApiRetry(retry)); assertFalse(chat.snapshot().has("apiRetry"));
        assertEquals(2, chat.scheduleApiRetry(failed(chat, "second", 429)).get("attempt").getAsInt());
    }
    @Test void onlyCustomApiRateLimitsCanRetry() {
        for (int status : new int[]{400, 401, 403, 500, 503}) {
            var chat = chat(); assertTrue(chat.scheduleApiRetry(failed(chat, "error", status)).isEmpty());
        }
        for (String excluded : new String[]{"codex", "claude", "loop", "stale", "message"}) {
            var chat = chat(); var turn = failed(chat, "error", 429);
            switch (excluded) {
                case "codex" -> chat.set("apiConnectionId", "");
                case "claude" -> chat.set("provider", "claude");
                case "loop" -> chat.set("localLoopGuard", object("stopped", true));
                case "stale" -> chat.event(object("method", "turn/started", "params", object("turn", object("id", "new"))));
                default -> { obj(turn, "error").remove("codexErrorInfo"); obj(turn, "error").addProperty("message", "Tool output says 429 Too Many Requests"); }
            }
            assertTrue(chat.scheduleApiRetry(turn).isEmpty(), excluded);
        }
    }
}
