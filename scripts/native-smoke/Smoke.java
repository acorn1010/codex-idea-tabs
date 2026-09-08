package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.jcef.JBCefBrowser;
import java.nio.file.*;
import javax.swing.SwingConstants;
import static com.acorn.codextabs.core.Json.*;

/** Test-only plugin opens actual native editors against the deterministic app-server fixture. */
public final class Smoke implements StartupActivity.DumbAware {
    @Override public void runActivity(Project project) {
        Thread.startVirtualThread(() -> {
            try {
                var service = CodexService.get(project);
                var settings = service.settings();
                settings.binary = java.util.Objects.requireNonNull(System.getProperty("codex.smoke.binary"), "Set -Dcodex.smoke.binary to scripts/fake-codex.py in the backend filesystem");
                settings.distro = System.getProperty("codex.smoke.distro", "");
                settings.cwd = System.getProperty("codex.smoke.cwd", "/tmp/codex-idea-smoke-project");
                com.intellij.ide.GeneralSettings.getInstance().setConfirmExit(false);
                if (Boolean.getBoolean("codex.smoke.live")) {
                    settings.permissions = "read";
                    if (FileEditorManagerEx.getInstanceEx(project).getOpenFiles().length > 0) { return; }
                    var chat = service.create();
                    chat.set("title", "Native Codex connection check");
                    chat.set("draft", "Do not use tools. Reply with only IDEA_NATIVE_OK.");
                    service.changed(chat.id);
                    ApplicationManager.getApplication().invokeAndWait(() -> ChatFiles.open(project, chat.id, false));
                    return;
                }
                service.history("", "").get();
                for (String id : new String[]{"review", "choices", "image", "build"}) {
                    service.importThread(object("id", id, "name", switch(id) { case "review" -> "Review session recovery"; case "choices" -> "Choose the composer layout"; case "image" -> "Preview the new artwork"; default -> "Check the build"; }, "cwd", settings.cwd));
                }
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    var manager = FileEditorManagerEx.getInstanceEx(project);
                    if (manager.getOpenFiles().length == 0) {
                        ChatFiles.open(project, "review", false);
                        var left = manager.getCurrentWindow();
                        var fs = VirtualFileManager.getInstance().getFileSystem("codex-chat");
                        left.split(SwingConstants.VERTICAL, true, fs.findFileByPath(project.getLocationHash() + "/choices"), true, false, true);
                        var right = manager.getCurrentWindow();
                        right.split(SwingConstants.HORIZONTAL, true, fs.findFileByPath(project.getLocationHash() + "/build"), true, false, true);
                        left.split(SwingConstants.HORIZONTAL, true, fs.findFileByPath(project.getLocationHash() + "/image"), true, false, true);
                    }
                    var window = ToolWindowManager.getInstance(project).getToolWindow("Codex");
                    if (window != null) { window.show(); }
                });
                NativeUiProbe.start(project);
                Thread.sleep(8000);
                var output = Path.of(System.getProperty("idea.log.path"));
                Files.createDirectories(output);
                Files.writeString(output.resolve("smoke-ready.txt"), "Native editor setup completed\n" + service.summaries());
                if (Boolean.getBoolean("codex.smoke.sidebar.check")) { SidebarSmoke.run(project, output); }
            } catch (Throwable error) {
                try { Files.writeString(Path.of(System.getProperty("idea.log.path"), "smoke-error.txt"), error.toString() + "\n" + java.util.Arrays.toString(error.getStackTrace())); }
                catch (Exception ignored) {}
            }
        });
    }
}
