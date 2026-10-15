package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Exercise real service notifications, steering, interruption, reconnect and manual recovery. */
final class LoopGuardSmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project);
        service.settings().localModelEnabled = true;
        service.settings().sharedGuidanceEnabled = false;
        service.settings().provider = "codex";
        service.restoreReady().get(15, TimeUnit.SECONDS);
        service.reconnect();
        var chat = service.create();
        ApplicationManager.getApplication().invokeAndWait(() -> ChatFiles.open(project, chat.id, false));
        send(service, chat.id);
        String thread = chat.get("threadId");
        emit(service, thread, "turn/started", object("turn", object("id", "loop-turn")));
        for (int i = 1; i <= 3; i++) { command(service, thread, "loop-turn", i); }
        var actions = actions(service);
        if (actions.size() != 1 || !text(actions.get(0).getAsJsonObject(), "method").equals("turn/steer")) { throw new AssertionError("The guard did not steer after three repeats: " + actions); }
        emit(service, thread, "item/completed", object("turnId", "loop-turn", "item", object("id", "compaction", "type", "contextCompaction")));
        service.reconnect();
        service.load(chat.id).get(60, TimeUnit.SECONDS);
        service.rpc("thread/goal/set", object("threadId", thread, "objective", "Fixture goal must remain unfinished")).get(10, TimeUnit.SECONDS);
        emit(service, thread, "turn/started", object("turn", object("id", "resumed-loop")));
        for (int i = 4; i <= 5; i++) { command(service, thread, "resumed-loop", i); }
        actions = actions(service);
        if (actions.size() != 1 || !text(actions.get(0).getAsJsonObject(), "method").equals("turn/interrupt")) { throw new AssertionError("The guard did not interrupt after reconnect: " + actions); }
        if (!chat.get("error").contains("Loop guard stopped")) { throw new AssertionError("The stop reason was not visible: " + chat.snapshot()); }
        var goal = service.rpc("thread/goal/get", object("threadId", thread)).get(10, TimeUnit.SECONDS);
        if (!text(obj(goal, "goal"), "objective").equals("Fixture goal must remain unfinished")) { throw new AssertionError("The guard changed the goal"); }
        emit(service, thread, "turn/started", object("turn", object("id", "automatic-retry")));
        if (actions(service).size() != 2) { throw new AssertionError("An automatic continuation bypassed the stop"); }
        send(service, chat.id);
        if (!obj(chat.snapshot(), "localLoopGuard").isEmpty() || !chat.get("error").isBlank()) { throw new AssertionError("A new user instruction did not reset the guard"); }
        Files.writeString(output.resolve("loop-guard-result.json"), GSON.toJson(object("warnedAtThree", true, "interruptedAtFive", true, "compactionKeptCounts", true, "reconnectKeptCounts", true, "goalKept", true, "automaticRetryStopped", true, "manualRecovery", true)));
        // Leave the stopped notice visible for the native browser check.
        emit(service, thread, "turn/started", object("turn", object("id", "visible-stop")));
        for (int i = 1; i <= 5; i++) { command(service, thread, "visible-stop", 100 + i); }
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            for (var file : manager.getOpenFiles()) {
                if (file instanceof ChatFiles.ChatFile saved && saved.id.equals(chat.id)) { manager.closeFile(file); }
            }
            ChatFiles.open(project, chat.id, false);
        });
    }
    private static void send(CodexService service, String id) throws Exception {
        service.send(id, object("model", LocalModel.MODEL, "permissions", "ask", "text", "MODEL_SWITCH_FIXTURE")).get(30, TimeUnit.SECONDS);
    }
    private static void command(CodexService service, String thread, String turn, int index) throws Exception {
        emit(service, thread, "item/completed", object("turnId", turn, "item", object("id", "loop-" + index, "type", "commandExecution", "command", "cargo test --test fixture", "cwd", "/fixture", "status", "completed", "exitCode", 1, "aggregatedOutput", "Chunk ID: " + index + "\nWall time: " + index + ".1 seconds\nProcess exited with code 1\nOutput:\nsame fixture failure")));
    }
    private static void emit(CodexService service, String thread, String method, JsonObject params) throws Exception {
        params.addProperty("threadId", thread);
        service.rpc("fixture/event", object("event", object("method", method, "params", params))).get(15, TimeUnit.SECONDS);
        // The fixture handles requests in order. This barrier also drains the guard's RPC.
        service.rpc("fixture/stats", object()).get(15, TimeUnit.SECONDS);
    }
    private static com.google.gson.JsonArray actions(CodexService service) throws Exception {
        return array(service.rpc("fixture/stats", object()).get(15, TimeUnit.SECONDS), "loopGuardActions");
    }
    private LoopGuardSmoke() {}
}
