package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.*;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Send timed fixture events through the native backend and leave the measured chat open for UI checks. */
final class GenerationSpeedSmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project);
        service.settings().localModelEnabled = true;
        service.settings().sharedGuidanceEnabled = false;
        service.settings().provider = "codex";
        service.restoreReady().get(15, TimeUnit.SECONDS);
        service.reconnect();
        var chat = service.create();
        ApplicationManager.getApplication().invokeAndWait(() -> ChatFiles.open(project, chat.id, false));
        service.send(chat.id, object("model", LocalModel.MODEL, "permissions", "ask", "text", "MODEL_SWITCH_FIXTURE")).get(30, TimeUnit.SECONDS);
        chat.set("title", "Generation speed fixture");
        emit(service, chat, "turn/started", object("turn", object("id", "speed-turn")));
        emit(service, chat, "item/agentMessage/delta", object("itemId", "speed-answer", "delta", "Measured response"));
        Thread.sleep(1000);
        emit(service, chat, "item/started", object("item", object("id", "tool", "type", "commandExecution", "command", "fixture", "status", "inProgress")));
        Thread.sleep(1500);
        emit(service, chat, "item/completed", object("item", object("id", "tool", "type", "commandExecution", "command", "fixture", "status", "completed")));
        emit(service, chat, "thread/tokenUsage/updated", object("tokenUsage", object("last", object("outputTokens", 100))));
        var speed = obj(chat.snapshot(), "generationSpeed");
        if (!speed.has("durationMs") || speed.get("durationMs").getAsDouble() < 900 || speed.get("durationMs").getAsDouble() > 2000) {
            throw new IllegalStateException("Expected about one second of output with the tool run excluded: " + speed);
        }
        // Reopen after JCEF starts so the browser check can attach to the actual chat editor.
        Thread.sleep(2000);
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            for (var file : manager.getOpenFiles()) {
                if (file instanceof ChatFiles.ChatFile saved && saved.id.equals(chat.id)) { manager.closeFile(file); }
            }
            ChatFiles.open(project, chat.id, false);
        });
        Files.writeString(output.resolve("generation-speed-result.json"), speed.toString());
    }
    private static void emit(CodexService service, Conversation chat, String method, JsonObject params) throws Exception {
        params.addProperty("threadId", chat.get("threadId")); params.addProperty("turnId", "speed-turn");
        service.rpc("fixture/event", object("event", object("method", method, "params", params))).get(15, TimeUnit.SECONDS);
        service.rpc("fixture/stats", object()).get(15, TimeUnit.SECONDS);
    }
    private GenerationSpeedSmoke() {}
}
