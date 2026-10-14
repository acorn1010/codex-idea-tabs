package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Opt-in test of the actual plugin service, WSL relay, local tool loop and reconnect. */
final class LocalModelSmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project);
        var settings = service.settings();
        settings.localModelEnabled = true;
        settings.localModelUrl = System.getProperty("codex.smoke.localModel.url", "http://127.0.0.1:30001/v1");
        settings.model = LocalModel.MODEL; settings.modelSelectionSaved = true;
        settings.provider = "codex"; settings.sharedGuidanceEnabled = false;
        service.restoreReady().get(15, TimeUnit.SECONDS);
        service.reconnect();
        var chat = service.create();
        ApplicationManager.getApplication().invokeAndWait(() -> ChatFiles.open(project, chat.id, false));
        service.load(chat.id).get(60, TimeUnit.SECONDS);
        if (array(service.snapshot(chat.id), "models").asList().stream().noneMatch(value -> flag(value.getAsJsonObject(), "isLocal"))) { throw new AssertionError("The local model is missing from the picker"); }
        var fixture = Path.of(System.getProperty("codex.smoke.localModel.fixture"));
        Files.writeString(fixture.resolve("input.txt"), "Local harness marker: PLUM-HARBOR-7286\n");
        Files.deleteIfExists(fixture.resolve("output.txt"));
        service.send(chat.id, object("model", LocalModel.MODEL, "effort", "xhigh", "permissions", "auto", "fast", true,
            "text", "Use a shell tool to read input.txt. Then use a shell tool to create output.txt with only the marker value from input.txt. Do not guess its contents. Finally reply only LOCAL_TOOL_LOOP_OK.")).get(90, TimeUnit.SECONDS);
        waitForTurn(service, chat.id);
        if (!Files.readString(fixture.resolve("output.txt")).strip().equals("PLUM-HARBOR-7286")) { throw new AssertionError("The model did not read and write the marker"); }
        if (!chat.get("modelProvider").equals(LocalModel.PROVIDER)) { throw new AssertionError("The local thread provider was not saved"); }
        var usage = obj(chat.snapshot(), "tokenUsage");
        if (usage.get("modelContextWindow").getAsLong() != 98304L * 95 / 100) { throw new AssertionError("The harness used the wrong context limit"); }
        service.reconnect();
        service.load(chat.id).get(90, TimeUnit.SECONDS);
        if (!chat.get("modelProvider").equals(LocalModel.PROVIDER)) { throw new AssertionError("Reconnect changed the local provider"); }
        Files.writeString(output.resolve("local-model-result.json"), GSON.toJson(object("toolLoop", true, "picker", true,
            "serverContext", 98304, "harnessUsableContext", usage.get("modelContextWindow"), "provider", chat.get("modelProvider"), "reconnect", true, "thread", chat.get("threadId"))));
    }
    private static void waitForTurn(CodexService service, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
        var chat = service.chat(id);
        while (chat.busy() && System.nanoTime() < deadline) {
            for (var entry : array(chat.snapshot(), "requests")) {
                var request = entry.getAsJsonObject();
                if (text(request, "method").equals("item/commandExecution/requestApproval")) {
                    service.answer(id, object("key", text(request, "key"), "decision", "accept")).get(30, TimeUnit.SECONDS);
                } else { throw new AssertionError("Unexpected fixture approval: " + text(request, "method")); }
            }
            Thread.sleep(250);
        }
        if (chat.busy() || !chat.get("error").isBlank()) { throw new AssertionError("Local turn failed: " + chat.get("error")); }
    }
    private LocalModelSmoke() {}
}
