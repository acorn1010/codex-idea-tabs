package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Check local config forwarding and Claude image results through the real native service. */
final class CompactionImageSmoke {
    static void run(Project project, Path output) throws Exception {
        ModelSwitchSmoke.run(project, output);
        var service = CodexService.get(project);
        service.settings().claudeBinary = System.getProperty("codex.smoke.claude.binary");
        service.settings().provider = "claude";
        var chat = service.create();
        chat.set("title", "Claude image reader fixture");
        ApplicationManager.getApplication().invokeAndWait(() -> ChatFiles.open(project, chat.id, false));
        service.send(chat.id, object("text", "image-reader", "permissions", "ask", "model", "", "effort", "")).get(60, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (chat.busy() && System.nanoTime() < deadline) { Thread.sleep(50); }
        if (chat.busy() || !chat.get("error").isBlank()) { throw new AssertionError("Image fixture failed: " + chat.get("error")); }
        check(chat.items());
        check(array(service.transcript(chat.id).get(30, TimeUnit.SECONDS), "items"));
        service.reconnect(); service.load(chat.id).get(60, TimeUnit.SECONDS);
        check(chat.items());
        Files.writeString(output.resolve("compaction-image-result.json"), GSON.toJson(object("chat", chat.id,
            "configForwarding", true, "liveImage", true, "restoredImage", true, "resizeNoteHidden", true)));
    }
    private static void check(com.google.gson.JsonArray items) {
        var image = items.asList().stream().map(value -> value.getAsJsonObject()).filter(value -> text(value, "id").equals("image-read-tool")).findFirst().orElseThrow();
        if (!text(obj(array(obj(image, "result"), "content").get(0).getAsJsonObject(), "source"), "media_type").equals("image/png")) { throw new AssertionError("Image bytes were lost"); }
        if (items.asList().stream().map(value -> value.getAsJsonObject()).anyMatch(value -> text(value, "id").equals("image-note-frame"))) { throw new AssertionError("Generated image note became a user message"); }
    }
    private CompactionImageSmoke() {}
}
