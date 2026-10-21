package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Check that chat tabs bypass text indexing and still open through their native editor provider. */
final class ChatFileSmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project);
        service.restoreReady().get(10, TimeUnit.SECONDS);
        var chat = service.create();
        String path = project.getLocationHash() + "/" + chat.id;
        var fs = VirtualFileManager.getInstance().getFileSystem("codex-chat");
        var file = fs.findFileByPath(path);
        check(file != null && file.getFileType().isBinary(), "Chat was exposed as source text");
        check(!file.isWritable() && file.getFileType().isReadOnly(), "Chat file is writable");
        ApplicationManager.getApplication().runReadAction(() ->
            check(FileDocumentManager.getInstance().getDocument(file) == null, "Chat acquired a source document"));
        // Reconstruct the URL as IDEA does when restoring editor tabs after a restart.
        var restored = new ChatFiles().findFileByPath(path);
        check(restored != null && restored.getUrl().equals(file.getUrl()) && restored.getFileType() == file.getFileType(), "Restored chat lost its URL or file type");
        check(new ChatEditor.Provider().accept(project, restored), "Restored chat lost its custom editor");
        var kubernetes = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.kubernetes"));
        boolean kubernetesChecked = kubernetes != null && kubernetes.isEnabled();
        if (kubernetesChecked) {
            var config = Class.forName("com.intellij.kubernetes.api.settings.KubeConfig", true, kubernetes.getPluginClassLoader());
            check(Boolean.FALSE.equals(config.getMethod("isLikeKubeConfig", Project.class, VirtualFile.class).invoke(null, project, file)), "Kubernetes did not skip the chat");
        }
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var manager = FileEditorManager.getInstance(project);
            for (int attempt = 0; attempt < 3; attempt++) {
                ChatFiles.open(project, chat.id, false);
                check(manager.getSelectedEditor(file) instanceof ChatEditor, "Chat did not open in the custom editor");
                manager.closeFile(file);
            }
            ChatFiles.open(project, chat.id, false);
            check(manager.getSelectedEditor(file) instanceof ChatEditor, "Chat did not reopen");
        }, ModalityState.any());
        Files.writeString(output.resolve("chat-file-result.json"), GSON.toJson(object(
            "noTextDocument", true, "restoredUrl", true, "openCloseReopen", true, "kubernetesChecked", kubernetesChecked)));
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private ChatFileSmoke() {}
}
