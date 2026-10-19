package com.acorn.codextabs;

import com.acorn.codextabs.core.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.xmlb.XmlSerializer;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Exercise the project settings form and actual service requests in a disposable IDE profile. */
public final class WritableFoldersSmoke {
    public static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project); service.restoreReady().get(10, TimeUnit.SECONDS);
        service.settings().sharedGuidanceEnabled = false;
        String extra = service.cwd() + "-extra folder";
        String host = com.acorn.codextabs.core.Paths.host(extra, service.distro(), SystemInfo.isWindows);
        Files.createDirectories(Path.of(host));
        var config = new CodexConfigurable(project);
        JComponent root = ui(config::createComponent);
        var frame = ui(() -> { var value = new JFrame("Codex Tabs writable folders check"); value.setContentPane(root); value.setSize(900, 720); value.setVisible(true); return value; });
        try {
            var inputField = config.getClass().getDeclaredField("additionalWritableFolders"); inputField.setAccessible(true);
            var input = (JTextArea) inputField.get(config);
            check(!ui(config::isModified), "Opening settings changed saved folders");
            ui(() -> { tabs(root).setSelectedIndex(3); input.setText(host); input.setCaretPosition(0); return null; });
            check(ui(config::isModified), "Editing folders did not enable Apply");
            capture(root, output.resolve("writable-folders-settings.png"));
            ui(() -> { config.apply(); return null; });
            var chat = service.create(); await(() -> !flag(service.snapshot(chat.id), "settingsPending"));
            check(!ui(config::isModified), "Apply left folders modified");
            var restored = XmlSerializer.deserialize(XmlSerializer.serialize(service.settings()), CodexSettings.State.class);
            check(restored.additionalWritableFolders.equals(host), "Settings serialization lost the folder");
            send(service, chat.id, "ask");
            var stats = service.rpc("fixture/stats", object()).get(10, TimeUnit.SECONDS);
            check(array(obj(obj(stats, "lastStart"), "config"), "sandbox_workspace_write.writable_roots").contains(new com.google.gson.JsonPrimitive(extra)), "New thread lost the configured folder");
            check(array(obj(obj(stats, "lastTurn"), "sandboxPolicy"), "writableRoots").contains(new com.google.gson.JsonPrimitive(extra)), "Turn lost the configured folder");
            service.reconnect(); service.load(chat.id).get(10, TimeUnit.SECONDS);
            stats = service.rpc("fixture/stats", object()).get(10, TimeUnit.SECONDS);
            check(array(obj(obj(stats, "lastResume"), "config"), "sandbox_workspace_write.writable_roots").contains(new com.google.gson.JsonPrimitive(extra)), "Resume lost the configured folder");
            send(service, chat.id, "read");
            stats = service.rpc("fixture/stats", object()).get(10, TimeUnit.SECONDS);
            check(text(obj(obj(stats, "lastTurn"), "sandboxPolicy"), "type").equals("readOnly"), "Extra folders broadened read-only mode");
            ui(() -> { input.setText("../relative");
                try { config.apply(); throw new AssertionError("Relative folder was accepted"); }
                catch (com.intellij.openapi.options.ConfigurationException expected) { check(expected.getMessage().contains("absolute"), "Wrong folder validation error"); }
                config.reset(); return null; });
            check(ui(input::getText).equals(host), "Reset lost the saved folder");
            service.send(chat.id, object("text", "FIXTURE_HOLD_APPROVALS", "permissions", "ask")).get(15, TimeUnit.SECONDS);
            await(chat::busy); var original = service.connect().get();
            ui(() -> { input.setText(""); config.apply(); return null; });
            check(flag(service.snapshot(chat.id), "settingsPending") && original.isAlive() && service.connect().get() == original, "Folder change interrupted the current turn");
            service.stop(chat.id).get(15, TimeUnit.SECONDS); await(() -> !chat.busy());
            await(() -> !flag(service.snapshot(chat.id), "settingsPending"));
            service.load(chat.id).get(10, TimeUnit.SECONDS);
            stats = service.rpc("fixture/stats", object()).get(10, TimeUnit.SECONDS);
            check(array(obj(obj(stats, "lastResume"), "config"), "sandbox_workspace_write.writable_roots").isEmpty(), "Resume kept a removed folder");
            send(service, chat.id, "ask");
            stats = service.rpc("fixture/stats", object()).get(10, TimeUnit.SECONDS);
            check(array(obj(obj(stats, "lastTurn"), "sandboxPolicy"), "writableRoots").equals(GSON.toJsonTree(new String[]{chat.get("cwd")})), "Turn kept a removed folder");
            ui(() -> { input.setText(host); config.disposeUIResources(); return null; });
            check(service.settings().additionalWritableFolders.isBlank(), "Cancel saved a draft folder");
            Files.writeString(output.resolve("writable-folders-result.json"), GSON.toJson(object("settingsForm", true, "saved", true, "start", true, "turn", true, "resume", true, "readOnly", true, "removal", true, "validation", true, "deferredUntilIdle", true, "cancel", true)));
        } finally { ui(() -> { config.disposeUIResources(); frame.dispose(); return null; }); }
    }
    private static void send(CodexService service, String id, String permission) throws Exception {
        service.send(id, object("text", "MODEL_SWITCH_FIXTURE", "model", "gpt-5", "permissions", permission)).get(15, TimeUnit.SECONDS);
        await(() -> !service.chat(id).busy());
    }
    private static JTabbedPane tabs(Container parent) {
        for (var child : parent.getComponents()) {
            if (child instanceof JTabbedPane tabs) { return tabs; }
            if (child instanceof Container container) { var found = tabs(container); if (found != null) { return found; } }
        }
        return null;
    }
    private static void capture(JComponent component, Path target) throws Exception {
        ui(() -> { var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics(); component.paint(graphics); graphics.dispose(); ImageIO.write(image, "png", target.toFile()); return null; });
    }
    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) { throw new AssertionError("Timed out waiting for folder settings"); } Thread.sleep(30); }
    }
    private static <T> T ui(Callable<T> action) throws Exception { var task = new FutureTask<T>(action); ApplicationManager.getApplication().invokeAndWait(task); return task.get(); }
    private WritableFoldersSmoke() {}
}
