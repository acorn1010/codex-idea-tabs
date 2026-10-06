package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.Conversation;
import com.google.gson.*;
import com.intellij.openapi.project.Project;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Verify the real service against a local fixture, without executing tools or calling a model. */
final class SessionApprovalSmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project);
        service.restoreReady().get(10, TimeUnit.SECONDS);
        service.settings().provider = "codex"; service.settings().permissions = "auto";
        var chat = service.create(); service.editorOpened(chat.id); service.editorOpened(chat.id);
        service.rememberPermissions(chat.id, object("permissions", "session"));
        check(service.settings().permissions.equals("auto") && text(obj(service.snapshot(service.create().id), "settings"), "permissions").equals("auto"), "Session approval must not become a default for new chats");
        start(service, chat);
        var turn = obj(stats(service), "lastTurn");
        check(text(turn, "approvalsReviewer").equals("user") && text(turn, "approvalPolicy").equals("on-request") && text(obj(turn, "sandboxPolicy"), "type").equals("workspaceWrite"), "Use Ask me with the usual sandbox");
        for (String type : List.of("commandExecution", "fileChange", "permissions")) {
            request(service, chat, type, "item/" + type + "/requestApproval", object("permissions", object("network", object("enabled", true))));
        }
        request(service, chat, "connector", "mcpServer/elicitation/request", object("mode", "form", "requestedSchema", object("type", "object", "properties", object()), "_meta", object("persist", List.of("session", "always"))));
        await(() -> array(stats(service), "approvalReplies").size() == 4);
        for (var entry : array(stats(service), "approvalReplies")) {
            var reply = entry.getAsJsonObject(); var result = obj(reply, "result");
            var expected = switch (text(reply, "id")) {
                case "permissions" -> object("permissions", object("network", object("enabled", true)), "scope", "turn");
                case "connector" -> object("action", "accept", "content", object());
                default -> object("decision", "accept");
            };
            check(result.equals(expected), "Auto approval changed scope: " + reply);
        }
        check(array(chat.snapshot(), "requests").isEmpty(), "Auto-approved requests must not leave cards behind");
        service.rpc("fixture/event", object("event", object("method", "turn/completed", "params", object("threadId", chat.get("threadId"), "turn", object("id", chat.get("turnId"), "status", "completed"))))).get(5, TimeUnit.SECONDS);
        service.rpc("fixture/event", object("event", object("method", "turn/started", "params", object("threadId", chat.get("threadId"), "turn", object("id", "goal-continuation"))))).get(5, TimeUnit.SECONDS);
        request(service, chat, "continuation", "item/commandExecution/requestApproval", object());
        await(() -> array(stats(service), "approvalReplies").size() == 5);
        request(service, chat, "question", "item/tool/requestUserInput", object("questions", List.of(object("id", "q", "question", "Which?"))));
        await(() -> array(chat.snapshot(), "requests").size() == 1);
        var question = array(chat.snapshot(), "requests").get(0).getAsJsonObject();
        service.answer(chat.id, object("key", text(question, "key"), "answers", object())).get(5, TimeUnit.SECONDS);
        request(service, chat, "form", "mcpServer/elicitation/request", object("mode", "form", "requestedSchema", object("type", "object", "properties", object("name", object("type", "string")))));
        decline(service, chat);
        service.rememberPermissions(chat.id, object("permissions", "ask"));
        check(!chat.approvals.active(), "Turning off must take effect immediately");
        request(service, chat, "disabled", "item/commandExecution/requestApproval", object()); decline(service, chat);
        service.rememberPermissions(chat.id, object("permissions", "session"));
        check(!chat.approvals.active(), "Enabling during a turn must wait for the next turn");
        service.stop(chat.id).get(5, TimeUnit.SECONDS); await(() -> !chat.busy());
        start(service, chat); check(chat.approvals.active(), "Next turn must activate the selected mode");
        service.editorClosed(chat.id); check(chat.approvals.active(), "Closing one of two editors must preserve the grant");
        service.editorClosed(chat.id); check(!chat.approvals.active() && mode(service, chat).equals("ask"), "Closing the last editor must expire the grant even while working");
        service.editorOpened(chat.id); service.stop(chat.id).get(5, TimeUnit.SECONDS); await(() -> !chat.busy());
        service.rememberPermissions(chat.id, object("permissions", "session")); service.reconnect(chat.id);
        check(mode(service, chat).equals("ask"), "Reconnect must expire the grant");
        start(service, chat); check(!chat.approvals.active(), "A stale composer must not restore an expired grant");
        request(service, chat, "reconnected", "item/commandExecution/requestApproval", object()); decline(service, chat);
        service.stop(chat.id).get(5, TimeUnit.SECONDS); await(() -> !chat.busy()); service.editorClosed(chat.id);
        Files.writeString(output.resolve("session-approval-result.json"), GSON.toJson(object("askBackend", true, "oneOffReplies", true, "goalContinuation", true, "manualQuestionsAndForms", true, "disableImmediate", true, "enableNextTurn", true, "lastEditorClose", true, "reconnectExpiry", true, "staleSend", true, "unchangedDefaults", true)));
    }
    private static String mode(CodexService service, Conversation chat) { return text(obj(service.snapshot(chat.id), "settings"), "permissions"); }
    private static JsonObject stats(CodexService service) { return service.rpc("fixture/stats", object()).join(); }
    private static void start(CodexService service, Conversation chat) throws Exception {
        service.send(chat.id, object("text", "FIXTURE_HOLD_APPROVALS", "permissions", "session")).get(10, TimeUnit.SECONDS);
        await(chat::busy);
    }
    private static void request(CodexService service, Conversation chat, String id, String method, JsonObject params) throws Exception {
        params.addProperty("threadId", chat.get("threadId")); params.addProperty("turnId", chat.get("turnId")); params.addProperty("itemId", id);
        service.rpc("fixture/event", object("event", object("id", id, "method", method, "params", params))).get(5, TimeUnit.SECONDS);
    }
    private static void decline(CodexService service, Conversation chat) throws Exception {
        await(() -> array(chat.snapshot(), "requests").size() == 1);
        var pending = array(chat.snapshot(), "requests").get(0).getAsJsonObject();
        service.answer(chat.id, object("key", text(pending, "key"), "decision", "decline")).get(5, TimeUnit.SECONDS);
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) { throw new AssertionError("Timed out waiting for session approval state"); } Thread.sleep(20); }
    }
    private SessionApprovalSmoke() {}
}
