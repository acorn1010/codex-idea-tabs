package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.*;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Exercise delayed recovery and cancellation through the actual native service with a fake backend. */
final class ApiRetrySmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project); service.restoreReady().get(15, TimeUnit.SECONDS);
        service.settings().sharedGuidanceEnabled = false; service.settings().localModelEnabled = false;
        var entry = new ApiConnection(); entry.id = "retry-fixture"; entry.name = "Routeway";
        entry.baseUrl = "https://api.routeway.ai/v1"; entry.models.add("fixture");
        service.settings().apiConnections.add(entry); service.settings().apiConnectionId = entry.id; service.settings().provider = "codex";
        service.reconnect();
        var chat = service.create();
        hold(service, chat); chat.set("draft", "Keep this draft");
        chat.set("goal", object("objective", "Keep this goal")); chat.set("localLoopGuard", object("marker", "keep"));
        fail(service, chat);
        check(chat.busy() && !chat.canArchive(), "Pending retry did not retain the active chat");
        long began = System.nanoTime();
        await(() -> !chat.busy(), 12);
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) >= 4000, "The retry skipped its wait");
        var stats = stats(service);
        check(array(obj(stats, "lastTurn"), "input").isEmpty(), "Retry duplicated the user's input");
        check(array(stats, "turnRequests").size() == 2, "The failed turn was retried more than once");
        check(chat.get("error").isBlank() && chat.get("draft").equals("Keep this draft"), "Recovery lost the draft or retained the error");
        check(text(obj(chat.snapshot(), "goal"), "objective").equals("Keep this goal"), "Retry reset the goal");
        check(text(obj(chat.snapshot(), "localLoopGuard"), "marker").equals("keep"), "Retry reset the loop guard");
        hold(service, chat); fail(service, chat);
        service.stop(chat.id).get(15, TimeUnit.SECONDS);
        check(!chat.busy(), "Stop left the retry active");
        Thread.sleep(5500);
        check(array(stats(service), "turnRequests").size() == 3, "Stopped retry still sent a turn");
        hold(service, chat); fail(service, chat);
        service.send(chat.id, object("text", "Manual follow-up", "model", "fixture", "permissions", "read")).get(15, TimeUnit.SECONDS);
        Thread.sleep(5500);
        check(array(stats(service), "turnRequests").size() == 5, "A manual message did not replace the pending retry");
        hold(service, chat); fail(service, chat);
        service.reconnect(chat.id);
        check(!chat.snapshot().has("apiRetry"), "Reconnect kept a scheduled retry");
        service.load(chat.id).get(15, TimeUnit.SECONDS);
        Thread.sleep(5500);
        check(array(stats(service), "turnRequests").isEmpty(), "A retry escaped into the replacement process");
        hold(service, chat);
        // Start beyond the old cap, then exercise the real timer and normal service request path.
        var attempts = Conversation.class.getDeclaredField("apiRetryAttempts");
        attempts.setAccessible(true); attempts.setLong(chat, 5);
        fail(service, chat);
        var continuing = obj(chat.snapshot(), "apiRetry");
        check(continuing.get("attempt").getAsInt() == 6 && continuing.get("delaySeconds").getAsInt() == 60,
            "The retry did not continue at the 60-second cap");
        long continuedAt = System.nanoTime();
        await(() -> !chat.busy(), 75);
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - continuedAt) >= 59000, "The capped retry skipped its 60-second wait");
        check(array(stats(service), "turnRequests").size() == 2, "Attempt six did not send exactly one continuation");
        check(chat.get("error").isBlank(), "Attempt six did not recover");
        // Leave an active turn for the browser to stop before checking the countdown.
        hold(service, chat);
        chat.set("title", "API retry fixture");
        ApplicationManager.getApplication().invokeAndWait(() -> ChatFiles.open(project, chat.id, false));
        Thread.sleep(2000);
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            for (var file : manager.getOpenFiles()) {
                if (file instanceof ChatFiles.ChatFile saved && saved.id.equals(chat.id)) { manager.closeFile(file); }
            }
            ChatFiles.open(project, chat.id, false);
        });
        Files.writeString(output.resolve("api-retry-result.json"), object("chatId", chat.id, "threadId", chat.get("threadId"), "turnId", chat.get("turnId"), "passed", true).toString());
    }
    private static void hold(CodexService service, Conversation chat) throws Exception {
        service.send(chat.id, object("text", "FIXTURE_HOLD_APPROVALS", "model", "fixture", "permissions", "read")).get(15, TimeUnit.SECONDS);
        await(() -> !chat.get("turnId").isBlank(), 5);
    }
    private static void fail(CodexService service, Conversation chat) throws Exception {
        var turn = object("id", chat.get("turnId"), "status", "failed", "error", object("message", "exceeded retry limit, last status: 429 Too Many Requests",
            "codexErrorInfo", object("responseTooManyFailedAttempts", object("httpStatusCode", 429))));
        service.rpc("fixture/event", object("event", object("method", "turn/completed", "params", object("threadId", chat.get("threadId"), "turn", turn)))).get(15, TimeUnit.SECONDS);
        stats(service);
        check(chat.snapshot().has("apiRetry"), "HTTP 429 did not schedule recovery");
    }
    private static JsonObject stats(CodexService service) throws Exception { return service.rpc("fixture/stats", object()).get(15, TimeUnit.SECONDS); }
    private static void await(BooleanSupplier ready, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(25); }
        check(ready.getAsBoolean(), "Native retry check timed out");
    }
    private static void check(boolean passed, String message) { if (!passed) { throw new IllegalStateException(message); } }
    private ApiRetrySmoke() {}
}
