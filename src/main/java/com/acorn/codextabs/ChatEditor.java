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
import com.intellij.ui.jcef.*;
import com.intellij.diff.*;
import com.intellij.diff.requests.SimpleDiffRequest;
import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private boolean disposed;
    private long renderedRevision = -1;

    public ChatEditor(Project project, ChatFiles.ChatFile file) {
        this.project = project; this.file = file; service = CodexService.get(project);
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
                CompletableFuture.runAsync(() -> {
                    try { handle(text(request, "method"), obj(request, "params")).whenComplete((result, error) -> reply(request.get("id"), result, error)); }
                    catch (Exception error) { reply(request.get("id"), null, error); }
                });
            } catch (Exception ignored) { return new JBCefJSQuery.Response(null, 400, "Invalid chat request"); }
            return null;
        });
        String nonce = UUID.randomUUID().toString();
        String boot = "window.__codexSend=function(value){" + bridge.inject("value") + "};";
        String html = "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'nonce-" + nonce + "'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'\">"
            + "<style>" + resource("web/index.css") + "</style></head><body><div id='root'></div><script nonce='" + nonce + "'>" + boot + "</script>"
            + "<script type='module' nonce='" + nonce + "'>" + resource("web/app.js").replace("</script", "<\\/script") + "</script></body></html>";
        browser.loadHTML(html);
        panel.removeAll(); panel.add(browser.getComponent(), BorderLayout.CENTER); panel.revalidate();
    }
    private CompletableFuture<JsonObject> handle(String method, JsonObject params) {
        var chat = service.chat(file.id);
        switch (method) {
            case "ready": ready = true; dirty = true; service.load(file.id); return completed(service.snapshot(file.id));
            case "send": return service.send(file.id, params);
            case "editMessage": return service.editMessage(file.id, params).thenApply(result -> {
                ui(() -> ChatFiles.open(project, text(result, "id"), false));
                return result;
            });
            case "answer": return service.answer(file.id, params);
            case "stop": return service.stop(file.id);
            case "restore": return service.archive(file.id, false);
            case "draft": chat.set("draft", text(params, "text")); if (params.has("attachments")) { chat.set("draftAttachments", array(params, "attachments")); } service.changed(file.id); return completed(new JsonObject());
            case "older": return service.older(file.id, text(params, "cursor"));
            case "seen": chat.set("unread", false); service.changed(file.id); return completed(new JsonObject());
            case "pin": chat.set("pinned", flag(params, "pinned")); service.changed(file.id); return completed(new JsonObject());
            case "rename": chat.set("title", text(params, "title")); chat.set("renamed", true); service.changed(file.id); return completed(new JsonObject());
            case "history": return service.history(text(params, "search"), text(params, "cursor"));
            case "openThread": {
                String id = params.has("thread") ? service.importThread(obj(params, "thread")).id : text(params, "id");
                ui(() -> ChatFiles.open(project, id, false)); return completed(new JsonObject());
            }
            case "new": ui(() -> ChatFiles.open(project, service.create().id, flag(params, "split"))); return completed(new JsonObject());
            case "settings": ui(() -> ShowSettingsUtil.getInstance().showSettingsDialog(project, CodexConfigurable.class)); return completed(new JsonObject());
            case "reconnect": service.reconnect(); return service.load(file.id).thenApply(ignored -> new JsonObject());
            case "login": return service.rpc("account/login/start", object("type", "chatgptDeviceCode"));
            case "attachment": return service.attachment(params);
            case "chooseFiles": return chooseFiles();
            case "context": return ideContext();
            case "copy": return uiResult(() -> com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection(text(params, "text"))));
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
    private CompletableFuture<JsonObject> chooseFiles() {
        var future = new CompletableFuture<JsonObject>();
        ui(() -> {
            var descriptor = new FileChooserDescriptor(true, false, false, false, false, true).withTitle("Add context to Codex");
            var selected = FileChooser.chooseFiles(descriptor, project, null);
            CompletableFuture.runAsync(() -> {
                try {
                    var files = new JsonArray();
                    for (var value : selected) {
                        if (value.getLength() > 50 * 1024 * 1024) { throw new IllegalArgumentException(value.getName() + " exceeds 50 MB."); }
                        String mime = Files.probeContentType(java.nio.file.Path.of(value.getPath()));
                        var attachment = service.attachment(object("name", value.getName(), "mime", mime == null ? "application/octet-stream" : mime, "data", Base64.getEncoder().encodeToString(value.contentsToByteArray()))).join();
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
                if (!(open instanceof ChatFiles.ChatFile)) { files.add(service.distro().isBlank() ? open.getPath() : com.acorn.codextabs.core.Paths.linux(open.getPath())); }
            }
            var editor = manager.getSelectedTextEditor();
            result.complete(object("files", files, "selection", editor == null ? "" : Objects.toString(editor.getSelectionModel().getSelectedText(), "")));
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
    @Override public void dispose() { disposed = true; updates.stop(); service.unlisten(listener); }

    public static final class Provider implements FileEditorProvider, DumbAware {
        @Override public boolean accept(Project project, VirtualFile file) { return file instanceof ChatFiles.ChatFile; }
        @Override public FileEditor createEditor(Project project, VirtualFile file) { return new ChatEditor(project, (ChatFiles.ChatFile) file); }
        @Override public String getEditorTypeId() { return "codex-chat-editor"; }
        @Override public FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }
    }
}
