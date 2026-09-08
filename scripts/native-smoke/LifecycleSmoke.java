package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Exercises real editor disposal and RPC traffic in an isolated IDEA profile, without model calls. */
final class LifecycleSmoke {
    static void run(Project project, Path output) throws Exception {
        if (!System.getProperty("codex.smoke.binary", "").endsWith("fake-codex.py")) { throw new IllegalStateException("Lifecycle checks require the test fixture"); }
        Files.createDirectories(output);
        var service = CodexService.get(project);
        String suffix = Long.toString(System.currentTimeMillis());
        String idle = "lifecycle-idle-" + suffix;
        String active = "lifecycle-active-" + suffix;
        service.importThread(object("id", idle, "name", "Idle close and reopen", "cwd", service.cwd()));
        service.importThread(object("id", active, "name", "Finish in background", "cwd", service.cwd()));
        open(project, idle); open(project, active);
        service.load(idle).get(15, TimeUnit.SECONDS); service.load(active).get(15, TimeUnit.SECONDS);
        service.chat(idle).set("draft", "Keep this draft");
        close(project, idle);
        waitFor(() -> !subscribed(service, idle));
        check(subscribed(service, active), "Closing one tab released another chat");
        open(project, idle); service.load(idle).get(15, TimeUnit.SECONDS);
        check(subscribed(service, idle), "Reopened chat did not subscribe");
        check(obj(stats(service), "resumes").get(idle).getAsInt() == 2, "Reopened chat reused an old resume");
        check(service.chat(idle).get("draft").equals("Keep this draft"), "Draft was lost");
        check(!array(service.chat(idle).snapshot(), "items").isEmpty(), "History was lost");

        notify(service, "turn/started", active, object("turn", object("id", "background-turn", "status", "inProgress")));
        close(project, active);
        Thread.sleep(300);
        check(subscribed(service, active), "Closing an active tab interrupted its subscription");
        notify(service, "error", active, object("turnId", "background-turn", "willRetry", true, "error", object("message", "Connection needs a retry")));
        check(service.chat(active).get("error").contains("needs a retry"), "Retry error was not shown");
        notify(service, "item/agentMessage/delta", active, object("turnId", "background-turn", "itemId", "recovered", "delta", "Connected again"));
        check(service.chat(active).get("error").isBlank(), "Progress did not clear retry error");
        notify(service, "turn/completed", active, object("turn", object("id", "background-turn", "status", "completed")));
        waitFor(() -> !subscribed(service, active));
        check(service.chat(active).status().equals("complete"), "Background result was not retained");

        String retry = "lifecycle-retry-" + suffix;
        service.importThread(object("id", retry, "name", "Recover a failed resume", "cwd", service.cwd()));
        service.editorOpened(retry);
        try { service.load(retry).get(15, TimeUnit.SECONDS); throw new AssertionError("Fixture did not fail its first resume"); }
        catch (ExecutionException expected) { check(service.chat(retry).status().equals("error"), "Load failure was not shown"); }
        service.load(retry).get(15, TimeUnit.SECONDS);
        check(service.chat(retry).get("error").isBlank(), "Successful resume kept its error");
        service.editorClosed(retry);
        waitFor(() -> !subscribed(service, retry));

        // Reconnect must resume every open editor, including ones that are not selected.
        open(project, active); service.load(active).get(15, TimeUnit.SECONDS);
        service.reconnect();
        waitFor(() -> subscribed(service, idle) && subscribed(service, active));
        close(project, active); close(project, idle);
        waitFor(() -> !subscribed(service, idle) && !subscribed(service, active));

        String preview = "lifecycle-preview-" + suffix;
        String title = "Inspect the game servers and identify the largest memory wins without changing any of the running services";
        var chat = service.importThread(object("id", preview, "name", title, "cwd", service.cwd()));
        chat.set("renamed", true);
        chat.set("draft", "Check this attachment before sending.");
        var attachments = new JsonArray();
        attachments.add(object("name", "preview.svg", "path", service.cwd() + "/preview.svg", "mime", "image/svg+xml", "size", 400));
        chat.set("draftAttachments", attachments);
        open(project, preview); service.load(preview).get(15, TimeUnit.SECONDS);
        var file = VirtualFileManager.getInstance().getFileSystem("codex-chat").findFileByPath(project.getLocationHash() + "/" + preview);
        var titles = new ChatFiles.Titles();
        String displayed = titles.getEditorTabTitle(project, file);
        check(displayed.codePointCount(0, displayed.length()) <= 40 && displayed.endsWith("…"), "Tab title was not capped");
        check(titles.getEditorTabTooltipText(project, file).startsWith(title), "Tooltip lost the full title");
        Files.writeString(output.resolve("lifecycle-result.json"), GSON.toJson(object("idleReleased", true, "reopened", true, "draftAndHistoryKept", true,
            "backgroundWorkFinished", true, "retryCleared", true, "resumeRetried", true, "allEditorsReconnected", true, "title", displayed, "previewId", preview, "stats", stats(service))));
        NativeUiProbe.start(project);
    }
    private static void open(Project project, String id) { ApplicationManager.getApplication().invokeAndWait(() -> ChatFiles.open(project, id, false)); }
    private static void close(Project project, String id) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var manager = FileEditorManager.getInstance(project);
            for (var file : manager.getOpenFiles()) { if (file instanceof ChatFiles.ChatFile chat && chat.id.equals(id)) { manager.closeFile(file); } }
        });
    }
    private static JsonObject stats(CodexService service) { return service.rpc("fixture/stats", new JsonObject()).join(); }
    private static boolean subscribed(CodexService service, String id) { return array(stats(service), "subscriptions").contains(new JsonPrimitive(id)); }
    private static void notify(CodexService service, String method, String id, JsonObject params) {
        params.addProperty("threadId", id);
        service.rpc("fixture/event", object("event", object("method", method, "params", params))).join();
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) { throw new AssertionError("Lifecycle check timed out"); } Thread.sleep(50); }
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private LifecycleSmoke() {}
}
