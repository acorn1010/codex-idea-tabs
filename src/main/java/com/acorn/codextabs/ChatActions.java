package com.acorn.codextabs;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.options.ShowSettingsUtil;
import static com.acorn.codextabs.core.Json.*;
import java.util.concurrent.CompletableFuture;
import com.acorn.codextabs.core.Conversation;
import com.intellij.openapi.application.ApplicationManager;

/** Native actions remain available during project indexing. */
public final class ChatActions {
    private ChatActions() {}
    private static CompletableFuture<Conversation> createForContext(AnActionEvent event) {
        var project = java.util.Objects.requireNonNull(event.getProject());
        var service = CodexService.get(project);
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file == null) {
            var selected = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedFiles();
            if (selected.length > 0) { file = selected[0]; }
        }
        return file instanceof ChatFiles.ChatFile chat ? CompletableFuture.completedFuture(service.createInWorkspace(chat.id))
            : file == null ? CompletableFuture.completedFuture(service.create()) : service.createForPathAsync(file.getPath());
    }
    public static final class NewChat extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent event) {
            var project = event.getProject();
            if (project != null) { createForContext(event).thenAccept(chat -> ApplicationManager.getApplication().invokeLater(() -> { if (!project.isDisposed()) { ChatFiles.open(project, chat.id, false); } })); }
        }
    }
    public static final class NewChatToSide extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent event) {
            var project = event.getProject();
            if (project != null) { createForContext(event).thenAccept(chat -> ApplicationManager.getApplication().invokeLater(() -> { if (!project.isDisposed()) { ChatFiles.open(project, chat.id, true); } })); }
        }
    }
    public static final class SendSelection extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent event) {
            var project = event.getProject();
            var editor = event.getData(CommonDataKeys.EDITOR);
            var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
            if (project == null || editor == null) { return; }
            var service = CodexService.get(project);
            var chat = createForContext(event);
            String path = file == null ? "Selected code" : file.getPath();
            if (!service.distro().isBlank()) { path = com.acorn.codextabs.core.Paths.linux(path); }
            String selected = editor.getSelectionModel().getSelectedText();
            String draft = "Context from " + path + ":\n\n" + java.util.Objects.toString(selected, "") + "\n\n";
            chat.thenAccept(created -> {
                created.set("draft", draft); service.changed(created.id);
                ApplicationManager.getApplication().invokeLater(() -> { if (!project.isDisposed()) { ChatFiles.open(project, created.id, true); } });
            });
        }
        @Override public void update(AnActionEvent event) {
            var editor = event.getData(CommonDataKeys.EDITOR);
            event.getPresentation().setEnabledAndVisible(event.getProject() != null && editor != null && editor.getSelectionModel().hasSelection());
        }
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    }
    public static final class Attention extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent event) {
            var project = event.getProject();
            if (project == null) { return; }
            for (var value : CodexService.get(project).summaries()) {
                var item = value.getAsJsonObject();
                if (text(item, "status").equals("attention")) { ChatFiles.open(project, text(item, "id"), false); return; }
            }
        }
    }
    public static final class Settings extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent event) {
            if (event.getProject() != null) { ShowSettingsUtil.getInstance().showSettingsDialog(event.getProject(), CodexConfigurable.class); }
        }
    }
}
