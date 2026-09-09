package com.acorn.codextabs;

import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileChooser.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.dnd.*;
import com.acorn.codextabs.core.AttachmentFiles;
import com.intellij.ui.jcef.*;
import com.intellij.diff.*;
import com.intellij.diff.requests.SimpleDiffRequest;
import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.acorn.codextabs.core.Json.*;

/** A lazily created chat renderer. Backend requests and drafts belong to the project service. */
public final class ChatEditor extends UserDataHolderBase implements FileEditor {
    private final Project project;
    private final ChatFiles.ChatFile file;
    private final CodexService service;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final Consumer<String> listener;
    private final javax.swing.Timer updates;
    private JBCefBrowser browser;
    private JBCefJSQuery bridge;
    private volatile boolean ready;
    private volatile boolean dirty = true;
    private volatile boolean disposed;
    private long renderedRevision = -1;
    private final Map<String, java.util.List<Path>> droppedFiles = new ConcurrentHashMap<>();

    public ChatEditor(Project project, ChatFiles.ChatFile file) {
        this.project = project; this.file = file; service = CodexService.get(project);
        service.editorOpened(file.id);
        panel.add(new JLabel("Opening chat…", SwingConstants.CENTER));
        listener = id -> { dirty = true; };
        service.listen(listener);
        updates = new javax.swing.Timer(100, event -> {
            if (panel.isShowing()) {
                ensureBrowser();
                if (ready && dirty) { dirty = false; publish(); }
            }
        });
        updates.start();
    }
    private void ensureBrowser() {
        if (browser != null || disposed) { return; }
        if (!JBCefApp.isSupported()) {
            panel.removeAll(); panel.add(new JLabel("Codex Tabs requires IDEA's bundled JetBrains Runtime with JCEF.", SwingConstants.CENTER));
            updates.stop(); panel.revalidate(); return;
        }
        // Restored tabs can be the first JCEF user. Initialize this service outside JCEF's static initializer.
        com.intellij.util.net.HttpConfigurable.getInstance();
        browser = new JBCefBrowser();
        Disposer.register(this, browser);
        bridge = JBCefJSQuery.create((JBCefBrowserBase) browser);
        Disposer.register(this, bridge);
        bridge.addHandler(raw -> {
            try {
                var request = JsonParser.parseString(raw).getAsJsonObject();
                if (text(request, "method").equals("cursor")) {
                    updateCursor(text(obj(request, "params"), "value"));
                    return null;
                }
                CompletableFuture.runAsync(() -> {
                    try { handle(text(request, "method"), obj(request, "params")).whenComplete((result, error) -> reply(request.get("id"), result, error)); }
                    catch (Exception error) { reply(request.get("id"), null, error); }
                });
            } catch (Exception ignored) { return new JBCefJSQuery.Response(null, 400, "Invalid chat request"); }
            return null;
        });
        String nonce = UUID.randomUUID().toString();
        String boot = "window.__codexSend=function(value){" + bridge.inject("value") + "};";
        if (org.cef.CefApp.isRemoteEnabled()) { boot += "window.__codexNativeCursor=true;"; }
        String html = "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'nonce-" + nonce + "'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'\">"
            + "<style>" + resource("web/index.css") + "</style></head><body><div id='root'></div><script nonce='" + nonce + "'>" + boot + "</script>"
            + "<script type='module' nonce='" + nonce + "'>" + resource("web/app.js").replace("</script", "<\\/script") + "</script></body></html>";
        installFileDrops(browser.getComponent());
        if (browser.getCefBrowser().getUIComponent() instanceof JComponent surface && surface != browser.getComponent()) {
            installFileDrops(surface);
        }
        browser.loadHTML(html);
        panel.removeAll(); panel.add(browser.getComponent(), BorderLayout.CENTER); panel.revalidate();
    }
    /** Native Explorer and project-tree drops do not reliably become DOM File objects in remote JCEF. */
    private void installFileDrops(JComponent component) {
        DnDSupport.createBuilder(component).disableAsSource().enableAsNativeTarget().setDisposableParent(this)
            .setTargetChecker(event -> {
                boolean accepted = ready && !disposed && (FileCopyPasteUtil.isFileListFlavorAvailable(event)
                    || event.getAttachedObject() instanceof FileFlavorProvider);
                event.setDropPossible(accepted);
                if (accepted) { event.updateAction(DnDAction.COPY); fileDropEvent("over", event, ""); }
                return false;
            })
            .setDropHandler(event -> {
                if (!ready || disposed) { return; }
                var files = FileCopyPasteUtil.getFileListFromAttachedObject(event.getAttachedObject());
                if (files.isEmpty()) { files = FileCopyPasteUtil.getFileList(event); }
                String token = UUID.randomUUID().toString();
                droppedFiles.put(token, files == null ? java.util.List.of() : files.stream().map(java.io.File::toPath).distinct().toList());
                // The webview can consume only this drop, never an arbitrary host path.
                var pending = droppedFiles;
                CompletableFuture.delayedExecutor(1, TimeUnit.MINUTES).execute(() -> pending.remove(token));
                fileDropEvent("drop", event, token);
            })
            .setCleanUpOnLeaveCallback(() -> fileDropEvent("leave", null, ""))
            .install();
        component.getDropTarget().setDefaultActions(java.awt.dnd.DnDConstants.ACTION_COPY);
    }
    private void fileDropEvent(String phase, DnDEvent event, String token) {
        var surface = browser.getCefBrowser().getUIComponent();
        var point = event == null ? new Point() : event.getPointOn(surface);
        var detail = object("phase", phase, "token", token, "x", point.x, "y", point.y,
            "width", surface.getWidth(), "height", surface.getHeight());
        execute("window.dispatchEvent(new CustomEvent('codex-file-drop',{detail:" + GSON.toJson(detail) + "}));");
    }
    private CompletableFuture<JsonObject> attachDroppedFiles(JsonObject params) {
        var paths = droppedFiles.remove(text(params, "token"));
        if (paths == null) { return CompletableFuture.failedFuture(new IllegalArgumentException("This drop expired. Drop the files again.")); }
        if (flag(params, "discard")) { return completed(object("files", new JsonArray(), "errors", new JsonArray())); }
        return CompletableFuture.supplyAsync(() -> {
            var files = new JsonArray();
            var errors = new JsonArray();
            if (paths.isEmpty()) { errors.add("Could not read the dropped files. Try the Attach files button."); }
            for (var path : paths) {
                if (disposed) { break; }
                try { files.add(service.attachment(AttachmentFiles.read(path, flag(params, "imagesOnly"))).join()); }
                catch (Exception error) {
                    var cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    errors.add(Objects.toString(cause.getMessage(), "Could not attach " + path.getFileName()));
                }
            }
            return object("files", files, "errors", errors);
        });
    }
    /** Remote JCEF omits cursor-change callbacks. Apply DOM cursor changes directly to its AWT view. */
    private void updateCursor(String value) {
        int type = switch (value) {
            case "text", "vertical-text" -> Cursor.TEXT_CURSOR;
            case "pointer", "grab", "grabbing" -> Cursor.HAND_CURSOR;
            case "crosshair" -> Cursor.CROSSHAIR_CURSOR;
            case "wait", "progress" -> Cursor.WAIT_CURSOR;
            case "move", "all-scroll" -> Cursor.MOVE_CURSOR;
            case "n-resize", "ns-resize", "row-resize" -> Cursor.N_RESIZE_CURSOR;
            case "s-resize" -> Cursor.S_RESIZE_CURSOR;
            case "e-resize", "ew-resize", "col-resize" -> Cursor.E_RESIZE_CURSOR;
            case "w-resize" -> Cursor.W_RESIZE_CURSOR;
            case "ne-resize", "nesw-resize" -> Cursor.NE_RESIZE_CURSOR;
            case "nw-resize", "nwse-resize" -> Cursor.NW_RESIZE_CURSOR;
            case "se-resize" -> Cursor.SE_RESIZE_CURSOR;
            case "sw-resize" -> Cursor.SW_RESIZE_CURSOR;
            default -> Cursor.DEFAULT_CURSOR;
        };
        ui(() -> {
            if (browser == null) { return; }
            var cursor = Cursor.getPredefinedCursor(type);
            browser.getComponent().setCursor(cursor);
            browser.getCefBrowser().getUIComponent().setCursor(cursor);
        });
    }
    private CompletableFuture<JsonObject> handle(String method, JsonObject params) {
        if (disposed) { return CompletableFuture.failedFuture(new IllegalStateException("Chat was closed")); }
        var chat = service.chat(file.id);
        switch (method) {
            case "ready": return service.restoreReady().thenApply(ignored -> {
                ready = true; dirty = true; service.load(file.id); return service.snapshot(file.id);
            });
            case "send": return service.send(file.id, params);
            case "accountLimits": return service.rpc("account/rateLimits/read", new JsonObject()).orTimeout(15, java.util.concurrent.TimeUnit.SECONDS);
            case "skills": return service.rpc("skills/list", object("cwds", new String[]{chat.get("cwd")}, "forceReload", true));
            case "composerPreferences": service.settings().fast = flag(params, "fast"); service.settings().planMode = flag(params, "planMode"); service.changed(""); return completed(new JsonObject());
            case "getGoal": return chat.get("threadId").isBlank() ? completed(object("goal", JsonNull.INSTANCE)) : service.rpc("thread/goal/get", object("threadId", chat.get("threadId")));
            case "clearGoal": return chat.get("threadId").isBlank() ? completed(object("cleared", false)) : service.rpc("thread/goal/clear", object("threadId", chat.get("threadId")));
            case "setGoal": { var payload = params.deepCopy(); payload.addProperty("goalObjective", text(params, "objective")); return service.send(file.id, payload); }
            case "review": { var payload = params.deepCopy(); payload.add("reviewTarget", obj(params, "target")); return service.send(file.id, payload); }
            case "mcpStatus": return service.mcpStatus();
            case "feedback": {
                String reason = text(params, "reason").strip();
                if (reason.isBlank() || reason.length() > 4000) { return CompletableFuture.failedFuture(new IllegalArgumentException("Write feedback of up to 4,000 characters.")); }
                return service.rpc("feedback/upload", object("classification", "other", "reason", reason, "includeLogs", false, "threadId", chat.get("threadId").isBlank() ? null : chat.get("threadId")));
            }
            case "modelPreferences": service.rememberModel(params); return completed(new JsonObject());
            case "editMessage": return service.editMessage(file.id, params).thenApply(result -> {
                ui(() -> ChatFiles.open(project, text(result, "id"), false));
                return result;
            });
            case "answer": return service.answer(file.id, params);
            case "stop": return service.stop(file.id);
            case "restore": return service.archive(file.id, false);
            case "draft": chat.set("draft", text(params, "text")); if (params.has("skills")) { chat.set("draftSkills", array(params, "skills")); } if (params.has("attachments")) { chat.set("draftAttachments", array(params, "attachments")); } service.changed(file.id); return completed(new JsonObject());
            case "older": return service.older(file.id, text(params, "cursor"));
            case "seen": chat.set("unread", false); service.changed(file.id); return completed(new JsonObject());
            case "pin": chat.set("pinned", flag(params, "pinned")); service.changed(file.id); return completed(new JsonObject());
            case "rename": chat.set("title", text(params, "title")); chat.set("renamed", true); service.changed(file.id); return completed(new JsonObject());
            case "history": return service.history(text(params, "search"), text(params, "cursor"));
            case "openThread": {
                String id = params.has("thread") ? service.importThread(obj(params, "thread")).id : text(params, "id");
                ui(() -> ChatFiles.open(project, id, false)); return completed(new JsonObject());
            }
            case "new": ui(() -> ChatFiles.open(project, service.createInWorkspace(file.id).id, flag(params, "split"))); return completed(new JsonObject());
            case "workspaces": return service.workspaces(file.id);
            case "changeWorkspace": return service.changeWorkspace(file.id, params).thenApply(result -> {
                if (!text(result, "id").equals(file.id)) { ui(() -> ChatFiles.open(project, text(result, "id"), false)); }
                return result;
            });
            case "inspectWorktree": return service.inspectWorktree(text(params, "path"));
            case "removeWorktree": return service.removeWorktree(text(params, "path"));
            case "workspaceReview": return service.workspaceChanges(file.id).thenCompose(changes -> uiResult(() -> WorkspaceActions.review(project, changes)));
            case "workspaceTerminal": return uiResult(() -> WorkspaceActions.terminal(project, chat.get("cwd"), service.distro(), service.workspaceLabel(chat.get("cwd"))));
            case "workspaceProject": return CompletableFuture.supplyAsync(() -> { WorkspaceActions.openProject(project, chat.get("cwd"), service.distro()); return new JsonObject(); });
            case "dismissWorkspaceNotice": chat.set("workspaceNotice", ""); service.changed(file.id); return completed(new JsonObject());
            case "settings": ui(() -> ShowSettingsUtil.getInstance().showSettingsDialog(project, CodexConfigurable.class)); return completed(new JsonObject());
            case "reconnect": service.reconnect(); return service.load(file.id).thenApply(ignored -> new JsonObject());
            case "login": return service.rpc("account/login/start", object("type", "chatgptDeviceCode"));
            case "attachment": return service.attachment(params);
            case "droppedFiles": return attachDroppedFiles(params);
            case "chooseFiles": return chooseFiles(false);
            case "chooseImages": return chooseFiles(true);
            case "context": return ideContext();
            case "inspectContext": return ContextInspection.recorded(service, file.id);
            case "inspectStartup": return ContextInspection.startup(service, file.id, params);
            case "contextExport": return uiResult(() -> FileEditorManager.getInstance(project).openFile(
                new com.intellij.testFramework.LightVirtualFile("Codex context snapshot.txt", com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE, text(params, "text")), true));
            case "copy": return uiResult(() -> com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection(text(params, "text"))));
            case "copyImage": {
                try {
                    var image = com.acorn.codextabs.core.ClipboardImage.fromPng(text(params, "data"));
                    return uiResult(() -> com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(image));
                } catch (Exception error) { return CompletableFuture.failedFuture(error); }
            }
            case "openLink": return openLink(text(params, "path"));
            case "readImage": {
                String path = com.acorn.codextabs.core.Paths.link(text(params, "path"), chat.get("cwd"));
                if (!service.distro().isBlank()) { path = com.acorn.codextabs.core.Paths.linux(path); }
                return service.rpc("fs/readFile", object("path", path)).thenApply(result -> {
                    String data = text(result, "dataBase64");
                    byte[] bytes = Base64.getDecoder().decode(data);
                    if (bytes.length > 25 * 1024 * 1024) { throw new IllegalArgumentException("Image is too large to preview. Open it in the editor."); }
                    String prefix = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.UTF_8);
                    String mime = prefix.contains("<svg") ? "image/svg+xml" : bytes.length > 3 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 ? "image/png" : bytes.length > 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 ? "image/jpeg" : bytes.length > 12 && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP") ? "image/webp" : "image/gif";
                    return object("url", "data:" + mime + ";base64," + data);
                });
            }
            case "diff": ui(() -> {
                var factory = DiffContentFactory.getInstance();
                DiffManager.getInstance().showDiff(project, new SimpleDiffRequest("Codex changes", factory.create(""), factory.create(chat.get("diff")), "", "Unified diff"));
            }); return completed(new JsonObject());
            default: throw new IllegalArgumentException("Unknown chat action: " + method);
        }
    }
    private CompletableFuture<JsonObject> chooseFiles(boolean imagesOnly) {
        var future = new CompletableFuture<JsonObject>();
        ui(() -> {
            var descriptor = new FileChooserDescriptor(true, false, false, false, false, true)
                .withTitle(imagesOnly ? "Attach images to edited message" : "Add context to Codex")
                .withFileFilter(file -> !imagesOnly || !AttachmentFiles.imageMime(file.getExtension()).isBlank());
            var selected = FileChooser.chooseFiles(descriptor, project, null);
            CompletableFuture.runAsync(() -> {
                try {
                    var files = new JsonArray();
                    for (var value : selected) {
                        var attachment = service.attachment(AttachmentFiles.read(value.toNioPath(), imagesOnly)).join();
                        files.add(attachment);
                    }
                    future.complete(object("files", files));
                } catch (Exception error) { future.completeExceptionally(error); }
            });
        });
        return future;
    }
    private CompletableFuture<JsonObject> ideContext() {
        var result = new CompletableFuture<JsonObject>();
        ui(() -> {
            var manager = FileEditorManager.getInstance(project);
            var files = new JsonArray();
            for (var open : manager.getOpenFiles()) {
                if (!(open instanceof ChatFiles.ChatFile)) {
                    String path = service.distro().isBlank() ? open.getPath() : com.acorn.codextabs.core.Paths.linux(open.getPath());
                    if (com.acorn.codextabs.core.GitWorktrees.contains(service.chat(file.id).get("cwd"), path)) { files.add(path); }
                }
            }
            var editor = manager.getSelectedTextEditor();
            var selectedFile = editor == null ? null : FileDocumentManager.getInstance().getFile(editor.getDocument());
            String selectedPath = selectedFile == null ? "" : service.distro().isBlank() ? selectedFile.getPath() : com.acorn.codextabs.core.Paths.linux(selectedFile.getPath());
            boolean belongs = com.acorn.codextabs.core.GitWorktrees.contains(service.chat(file.id).get("cwd"), selectedPath);
            result.complete(object("files", files, "selection", editor == null || !belongs ? "" : Objects.toString(editor.getSelectionModel().getSelectedText(), "")));
        });
        return result;
    }
    private CompletableFuture<JsonObject> openLink(String value) {
        if (value.startsWith("https://") || value.startsWith("http://")) { return uiResult(() -> BrowserUtil.browse(value)); }
        String path = com.acorn.codextabs.core.Paths.link(value, service.chat(file.id).get("cwd"));
        var match = java.util.regex.Pattern.compile("^(.*?)(?::(\\d+)(?::(\\d+))?|#L(\\d+))$").matcher(path);
        int line = 0, column = 0;
        if (match.matches()) {
            path = match.group(1); line = Integer.parseInt(match.group(2) != null ? match.group(2) : match.group(4)) - 1;
            column = match.group(3) == null ? 0 : Integer.parseInt(match.group(3)) - 1;
        }
        String hostPath = com.acorn.codextabs.core.Paths.host(path, service.distro(), SystemInfo.isWindows);
        int targetLine = line, targetColumn = column;
        // WSL file refresh can block. Only editor navigation belongs on the UI thread.
        return CompletableFuture.supplyAsync(() -> {
            var target = LocalFileSystem.getInstance().refreshAndFindFileByPath(hostPath);
            if (target == null) { throw new IllegalArgumentException("File not found: " + hostPath); }
            return target;
        }).thenCompose(target -> uiResult(() -> {
            if (targetLine > 0 || targetColumn > 0) { new OpenFileDescriptor(project, target, targetLine, targetColumn).navigate(true); }
            else { FileEditorManager.getInstance(project).openFile(target, true); }
        }));
    }
    private void publish() {
        var value = service.snapshot(file.id, renderedRevision);
        renderedRevision = obj(value, "chat").get("revision").getAsLong();
        var background = panel.getBackground();
        value.addProperty("theme", (background.getRed() + background.getGreen() + background.getBlue()) < 384 ? "dark" : "light");
        execute("window.dispatchEvent(new CustomEvent('codex-state',{detail:" + GSON.toJson(value) + "}));");
    }
    private void reply(JsonElement id, JsonObject result, Throwable error) {
        execute("window.dispatchEvent(new CustomEvent('codex-reply',{detail:" + GSON.toJson(object("id", id, "result", result, "error", error == null ? null : CodexService.message(error))) + "}));");
    }
    private void execute(String script) { ui(() -> { if (browser != null && !disposed) { browser.getCefBrowser().executeJavaScript(script, browser.getCefBrowser().getURL(), 0); } }); }
    private static String resource(String name) {
        try (var input = ChatEditor.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) { throw new IllegalStateException("Missing plugin UI resource: " + name); }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private static CompletableFuture<JsonObject> completed(JsonObject value) { return CompletableFuture.completedFuture(value); }
    private CompletableFuture<JsonObject> uiResult(Runnable task) {
        var result = new CompletableFuture<JsonObject>();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed()) { result.completeExceptionally(new IllegalStateException("Chat was closed")); return; }
            try { task.run(); result.complete(new JsonObject()); }
            catch (Exception error) { result.completeExceptionally(error); }
        });
        return result;
    }
    private void ui(Runnable task) { ApplicationManager.getApplication().invokeLater(() -> { if (!disposed && !project.isDisposed()) { task.run(); } }); }
    @Override public JComponent getComponent() { return panel; }
    @Override public JComponent getPreferredFocusedComponent() { return browser == null ? panel : browser.getComponent(); }
    @Override public String getName() { return "Codex"; }
    @Override public VirtualFile getFile() { return file; }
    @Override public void setState(FileEditorState state) {}
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return !disposed; }
    @Override public void addPropertyChangeListener(PropertyChangeListener listener) {}
    @Override public void removePropertyChangeListener(PropertyChangeListener listener) {}
    @Override public void selectNotify() { dirty = true; service.chat(file.id).set("unread", false); service.changed(file.id); if (ready) { service.load(file.id); } }
    @Override public void dispose() {
        if (disposed) { return; }
        disposed = true; droppedFiles.clear(); updates.stop(); service.unlisten(listener); service.editorClosed(file.id);
    }

    public static final class Provider implements FileEditorProvider, DumbAware {
        @Override public boolean accept(Project project, VirtualFile file) { return file instanceof ChatFiles.ChatFile; }
        @Override public FileEditor createEditor(Project project, VirtualFile file) { return new ChatEditor(project, (ChatFiles.ChatFile) file); }
        @Override public String getEditorTypeId() { return "codex-chat-editor"; }
        @Override public FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }
    }
}
