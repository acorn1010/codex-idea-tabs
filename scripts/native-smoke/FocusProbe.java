package com.acorn.codextabs.smoke;

import com.acorn.codextabs.ChatFiles;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import java.awt.KeyboardFocusManager;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import static com.acorn.codextabs.core.Json.*;

/** Observe native keyboard focus in the disposable profile without moving focus or synthesizing input. */
final class FocusProbe {
    static void start(Project project) {
        if (!Boolean.getBoolean("codex.smoke.focus.check")) { return; }
        Thread.startVirtualThread(() -> {
            while (!project.isDisposed()) {
                try {
                    Thread.sleep(100);
                    var state = object();
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        var editor = FileEditorManager.getInstance(project).getSelectedEditor();
                        var owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                        state.addProperty("chat", editor != null && editor.getFile() instanceof ChatFiles.ChatFile file ? file.id : "");
                        state.addProperty("owner", owner == null ? "" : owner.getClass().getName());
                        state.addProperty("withinEditor", editor != null && owner != null && SwingUtilities.isDescendingFrom(owner, editor.getComponent()));
                    });
                    Files.writeString(Path.of(System.getProperty("idea.log.path"), "focus-state.json"), GSON.toJson(state));
                } catch (Exception ignored) { if (project.isDisposed()) { return; } }
            }
        });
    }
    private FocusProbe() {}
}
