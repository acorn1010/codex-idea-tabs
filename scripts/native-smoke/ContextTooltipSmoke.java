package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Open a native local chat with known usage for the hover tooltip browser check. */
final class ContextTooltipSmoke {
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
        chat.set("title", "Context tooltip fixture");
        service.rpc("fixture/event", object("event", object("method", "thread/tokenUsage/updated", "params", object("threadId", chat.get("threadId"),
            "tokenUsage", object("total", object("totalTokens", 1736000), "last", object("totalTokens", 23347), "modelContextWindow", 93388))))).get(15, TimeUnit.SECONDS);
        service.rpc("fixture/stats", object()).get(15, TimeUnit.SECONDS);
        // Reopen only this fixture chat after JCEF has started during the first editor load.
        Thread.sleep(2000);
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            for (var file : manager.getOpenFiles()) {
                if (file instanceof ChatFiles.ChatFile saved && saved.id.equals(chat.id)) { manager.closeFile(file); }
            }
            ChatFiles.open(project, chat.id, false);
        });
        Files.writeString(output.resolve("context-tooltip-ready.txt"), chat.id);
    }
    private ContextTooltipSmoke() {}
}
