package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Check the actual service against a fixture that ignores overrides on loaded threads. */
final class ModelSwitchSmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project);
        service.settings().localModelEnabled = true;
        service.settings().sharedGuidanceEnabled = false;
        service.settings().provider = "codex";
        service.restoreReady().get(15, TimeUnit.SECONDS); service.reconnect();
        var chat = service.create();
        ApplicationManager.getApplication().invokeAndWait(() -> ChatFiles.open(project, chat.id, false));
        send(service, chat.id, LocalModel.MODEL);
        check(service, chat.id, LocalModel.PROVIDER, LocalModel.MODEL, true);
        String thread = chat.get("threadId");
        send(service, chat.id, "gpt-6-astra");
        check(service, chat.id, "openai", "gpt-6-astra", false);
        send(service, chat.id, LocalModel.MODEL);
        check(service, chat.id, LocalModel.PROVIDER, LocalModel.MODEL, true);
        send(service, chat.id, "");
        check(service, chat.id, "openai", "gpt-6-astra", false);
        service.reconnect(); service.load(chat.id).get(60, TimeUnit.SECONDS);
        check(service, chat.id, "openai", "gpt-6-astra", false);
        if (!chat.get("threadId").equals(thread) || chat.items().size() < 8) { throw new AssertionError("Switching lost the conversation"); }
        Files.writeString(output.resolve("model-switch-result.json"), GSON.toJson(object("localToCloud", true, "cloudToLocal", true,
            "cloudDefault", true, "reconnect", true, "contextReset", true, "historyKept", true, "automaticReview", true, "localCompactionPrompt", true)));
    }
    private static void send(CodexService service, String id, String model) throws Exception {
        service.send(id, object("model", model, "permissions", "auto", "effort", "xhigh", "fast", false, "text", "MODEL_SWITCH_FIXTURE")).get(60, TimeUnit.SECONDS);
        if (service.chat(id).busy()) { throw new AssertionError("Fixture turn did not complete"); }
        var stats = service.rpc("fixture/stats", object()).get(30, TimeUnit.SECONDS);
        var turn = obj(stats, "lastTurn");
        if (!text(turn, "approvalPolicy").equals("on-request") || !text(turn, "approvalsReviewer").equals("auto_review")) { throw new AssertionError("Automatic review was not sent to the app-server: " + turn); }
    }
    private static void check(CodexService service, String id, String provider, String model, boolean local) throws Exception {
        var chat = service.chat(id);
        var stats = service.rpc("fixture/stats", object()).get(30, TimeUnit.SECONDS);
        var actual = obj(obj(stats, "modelStates"), chat.get("threadId"));
        if (!text(actual, "modelProvider").equals(provider) || !text(actual, "model").equals(model)) { throw new AssertionError("The model backend did not switch: " + actual); }
        if (!chat.get("modelProvider").equals(provider)) { throw new AssertionError("Historical provider metadata replaced the runtime provider"); }
        var config = obj(actual, "config");
        if (local != config.has("model_context_window")) { throw new AssertionError("Local context settings leaked across model switching"); }
        if (local != config.has("compact_prompt")) { throw new AssertionError("Compaction prompt leaked across model switching"); }
        if (local && !text(config, "compact_prompt").contains("Do not call tools")) { throw new AssertionError("Local compaction prompt was not forwarded"); }
        if (local && config.get("model_context_window").getAsInt() != 98304) { throw new AssertionError("Wrong local context window"); }
        if (!chat.get("error").isBlank()) { throw new AssertionError(chat.get("error")); }
    }
    private ModelSwitchSmoke() {}
}
