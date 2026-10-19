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
        if (actions.size() != 2 || !text(actions.get(0).getAsJsonObject(), "method").equals("turn/steer") || !text(actions.get(1).getAsJsonObject(), "method").equals("turn/interrupt")) { throw new AssertionError("The guard did not interrupt after reconnect: " + actions); }
        if (!chat.get("error").contains("Loop guard stopped")) { throw new AssertionError("The stop reason was not visible: " + chat.snapshot()); }
        var goal = service.rpc("thread/goal/get", object("threadId", thread)).get(10, TimeUnit.SECONDS);
        if (!text(obj(goal, "goal"), "objective").equals("Fixture goal must remain unfinished")) { throw new AssertionError("The guard changed the goal"); }
        emit(service, thread, "turn/started", object("turn", object("id", "automatic-retry")));
        if (actions(service).size() != 3) { throw new AssertionError("An automatic continuation bypassed the stop"); }
        send(service, chat.id);
        if (!obj(chat.snapshot(), "localLoopGuard").isEmpty() || !chat.get("error").isBlank()) { throw new AssertionError("A new user instruction did not reset the guard"); }

        int consumed = textLoop(project, service);
        Files.writeString(output.resolve("loop-guard-result.json"), GSON.toJson(object("warnedAtThree", true, "interruptedAtFive", true, "compactionKeptCounts", true, "reconnectKeptCounts", true, "goalKept", true, "automaticRetryStopped", true, "manualRecovery", true, "apiTextLoopInterrupted", true, "progressResetsRecovery", true, "streamCharactersBeforeStop", consumed)));
    }
    private static int textLoop(Project project, CodexService service) throws Exception {
        var entry = new ApiConnection(); entry.id = "loop_fixture"; entry.name = "Loop fixture";
        entry.baseUrl = "http://127.0.0.1:1/v1"; entry.models.add("glm-fixture"); entry.selectedModel = "glm-fixture";
        service.settings().apiConnections.add(entry); service.settings().apiConnectionId = entry.id;
        service.reconnect();
        var chat = service.create(); send(service, chat.id);
        if (!chat.get("modelProvider").equals(entry.providerId())) { throw new AssertionError("Text fixture did not use the API connection"); }
        String thread = chat.get("threadId");
        service.rpc("thread/goal/set", object("threadId", thread, "objective", "Text loop goal must stay unfinished")).get(10, TimeUnit.SECONDS);
        emit(service, thread, "turn/started", object("turn", object("id", "text-loop")));
        String sample = Files.readString(Path.of(System.getProperty("codex.smoke.loopText")));
        int consumed = 0;
        var recoveryMessages = new java.util.HashSet<String>();
        for (int attempt = 0; attempt < 3; attempt++) {
            String activeTurn = chat.get("turnId");
            if (activeTurn.isBlank()) { throw new AssertionError("Recovery did not start another turn"); }
            for (int offset = 0; offset < sample.length(); offset += 113) {
                consumed = Math.min(offset + 113, sample.length());
                emit(service, thread, "item/reasoning/textDelta", object("turnId", activeTurn, "itemId", "looping-reasoning-" + attempt, "delta", sample.substring(offset, consumed)));
                if (chat.get("loopRecovery").equals("true") || !chat.get("turnId").equals(activeTurn) || chat.get("errorKind").equals("loopGuardStopped")) { break; }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline && (chat.get("loopRecovery").equals("true") || (attempt < 2 && (chat.get("turnId").isBlank() || chat.get("turnId").equals(activeTurn))))) { Thread.sleep(20); }
            var recorded = actions(service);
            if (attempt < 2) {
                int index = attempt * 2;
                if (recorded.size() != index + 2 || !text(recorded.get(index).getAsJsonObject(), "method").equals("turn/interrupt")
                    || !text(recorded.get(index + 1).getAsJsonObject(), "method").equals("turn/start")) {
                    throw new AssertionError("Recovery did not interrupt then send a new message: " + recorded);
                }
                String message = text(array(obj(recorded.get(index + 1).getAsJsonObject(), "params"), "input").get(0).getAsJsonObject(), "text");
                if (!recoveryMessages.add(message) || !chat.items().toString().contains(message.split("\n")[0])) { throw new AssertionError("The unique recovery message was not visible"); }
            }
        }
        var actions = actions(service);
        if (actions.size() != 5 || chat.busy() || !chat.get("error").contains("repeated text")) {
            throw new AssertionError("Two recoveries did not end in a persistent-loop stop: " + actions);
        }
        var goal = service.rpc("thread/goal/get", object("threadId", thread)).get(10, TimeUnit.SECONDS);
        if (!text(obj(goal, "goal"), "objective").equals("Text loop goal must stay unfinished")) { throw new AssertionError("Text loop changed the goal"); }
        emit(service, thread, "turn/started", object("turn", object("id", "text-retry")));
        if (actions(service).size() != 6 || !chat.get("error").contains("repeated text")) { throw new AssertionError("Automatic retry lost the text stop reason"); }
        checkCancelledRecovery(service, sample);
        checkProgressRecovery(service, sample);
        ApplicationManager.getApplication().invokeAndWait(() -> ChatFiles.open(project, chat.id, false));
        return consumed;
    }
    private static void checkProgressRecovery(CodexService service, String sample) throws Exception {
        var chat = service.create(); send(service, chat.id);
        String thread = chat.get("threadId");
        emit(service, thread, "turn/started", object("turn", object("id", "progress-loop")));
        for (int attempt = 1; attempt <= 2; attempt++) {
            recover(service, chat, sample, "before-progress-" + attempt);
            if (obj(chat.snapshot(), "localLoopGuard").get("recoveryAttempts").getAsInt() != attempt) { throw new AssertionError("An unsuccessful recovery reset its own counter"); }
        }
        emit(service, thread, "item/completed", object("turnId", chat.get("turnId"), "item", object("id", "useful-result", "type", "commandExecution", "command", "read new packet fixture", "status", "completed", "exitCode", 0, "aggregatedOutput", "new packet evidence")));
        if (obj(chat.snapshot(), "localLoopGuard").has("recoveryAttempts")) { throw new AssertionError("Useful progress did not renew recovery"); }
        recover(service, chat, sample, "after-progress");
        var stats = service.rpc("fixture/stats", object()).get(15, TimeUnit.SECONDS);
        String correction = text(array(obj(stats, "lastTurn"), "input").get(0).getAsJsonObject(), "text");
        if (!correction.startsWith("Loop guard recovery 1/2")) { throw new AssertionError("A later loop inherited the previous recovery count"); }
        String turn = chat.get("turnId");
        emit(service, thread, "item/completed", object("turnId", turn, "item", object("id", "recovered-reply", "type", "agentMessage", "text", "The packet parser now passes the focused check.")));
        emit(service, thread, "turn/completed", object("turn", object("id", turn, "status", "completed")));
        if (obj(chat.snapshot(), "localLoopGuard").has("recoveryAttempts") || chat.busy()) { throw new AssertionError("A completed normal reply did not renew recovery"); }
    }
    private static void recover(CodexService service, Conversation chat, String sample, String item) throws Exception {
        String turn = chat.get("turnId");
        emit(service, chat.get("threadId"), "item/reasoning/textDelta", object("turnId", turn, "itemId", item, "delta", sample));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline && (chat.get("loopRecovery").equals("true") || chat.get("turnId").isBlank() || chat.get("turnId").equals(turn))) { Thread.sleep(20); }
        if (chat.get("turnId").isBlank() || chat.get("turnId").equals(turn) || chat.get("errorKind").equals("loopGuardStopped")) { throw new AssertionError("The model did not receive its next recovery turn"); }
    }
    private static void checkCancelledRecovery(CodexService service, String sample) throws Exception {
        var chat = service.create(); send(service, chat.id);
        String thread = chat.get("threadId");
        emit(service, thread, "turn/started", object("turn", object("id", "cancel-recovery")));
        service.rpc("fixture/holdInterrupt", object("threadId", thread, "enabled", true)).get(15, TimeUnit.SECONDS);
        var before = array(service.rpc("fixture/stats", object()).get(15, TimeUnit.SECONDS), "turnRequests").size();
        emit(service, thread, "item/reasoning/textDelta", object("turnId", "cancel-recovery", "itemId", "cancel-text", "delta", sample));
        if (!chat.get("loopRecovery").equals("true")) { throw new AssertionError("The recovery did not wait for interruption"); }
        service.stop(chat.id).get(15, TimeUnit.SECONDS);
        emit(service, thread, "item/reasoning/textDelta", object("turnId", "cancel-recovery", "itemId", "late-text", "delta", sample));
        emit(service, thread, "turn/completed", object("turn", object("id", "cancel-recovery", "status", "interrupted")));
        service.rpc("fixture/holdInterrupt", object("threadId", thread, "enabled", false)).get(15, TimeUnit.SECONDS);
        var after = array(service.rpc("fixture/stats", object()).get(15, TimeUnit.SECONDS), "turnRequests").size();
        if (after != before || chat.busy() || chat.get("loopRecovery").equals("true")) { throw new AssertionError("Stop allowed a delayed correction to resume the chat"); }
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
