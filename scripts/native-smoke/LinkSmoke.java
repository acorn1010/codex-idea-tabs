package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import java.nio.file.*;
import java.util.concurrent.*;
import static com.acorn.codextabs.core.Json.*;

/** Exercise file links in a disposable profile whose browser executable only records its arguments. */
final class LinkSmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project); service.restoreReady().get(10, TimeUnit.SECONDS); service.connect().get(10, TimeUnit.SECONDS);
        var root = Path.of(service.cwd()); var html = root.resolve("page with #.html"); var source = root.resolve("notes.txt");
        Files.writeString(html, "<!doctype html><h1 id='overview'>Overview</h1>");
        Files.writeString(source, "first line\nsecond line\nthird line\n");
        var chat = service.create();
        var file = (ChatFiles.ChatFile) VirtualFileManager.getInstance().getFileSystem("codex-chat").findFileByPath(project.getLocationHash() + "/" + chat.id);
        var editor = ui(() -> new ChatEditor(project, file));
        var handle = ChatEditor.class.getDeclaredMethod("handle", String.class, JsonObject.class); handle.setAccessible(true);
        try {
            String browserUrl = html.toUri().toASCIIString() + "?view=wide#overview";
            ((CompletableFuture<?>) handle.invoke(editor, "openLink", object("path", browserUrl))).get(10, TimeUnit.SECONDS);
            var capture = root.resolve("opened-url.json");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(capture)) { if (System.nanoTime() > deadline) { throw new AssertionError("Test browser was not invoked"); } Thread.sleep(50); }
            var args = JsonParser.parseString(Files.readString(capture)).getAsJsonArray();
            check(args.asList().stream().anyMatch(value -> value.getAsString().equals(browserUrl)), "The browser lost the query, anchor, or encoded filename: " + args);
            ((CompletableFuture<?>) handle.invoke(editor, "openLink", object("path", html.toString(), "source", true))).get(10, TimeUnit.SECONDS);
            check(ui(() -> java.util.Arrays.stream(FileEditorManager.getInstance(project).getOpenFiles()).anyMatch(open -> open.getPath().equals(html.toString()))), "Explicit source links must keep literal filenames and open HTML in the IDE");
            ((CompletableFuture<?>) handle.invoke(editor, "openLink", object("path", source + ":3:2"))).get(10, TimeUnit.SECONDS);
            check(ui(() -> FileEditorManager.getInstance(project).getSelectedTextEditor().getCaretModel().getLogicalPosition().line) == 2, "Source line navigation failed");
            check(ui(() -> FileEditorManager.getInstance(project).getSelectedTextEditor().getCaretModel().getLogicalPosition().column) == 1, "Source column navigation failed");
            ((CompletableFuture<?>) handle.invoke(editor, "openLink", object("path", source + "#L1"))).get(10, TimeUnit.SECONDS);
            check(ui(() -> FileEditorManager.getInstance(project).getSelectedTextEditor().getCaretModel().getLogicalPosition().line) == 0, "First-line link retained the previous cursor position");
            try {
                ((CompletableFuture<?>) handle.invoke(editor, "openLink", object("path", root.resolve("missing.html") + "#overview"))).get(10, TimeUnit.SECONDS);
                throw new AssertionError("Missing file did not report an error");
            } catch (ExecutionException error) {
                check(error.getCause().getMessage().equals("File not found: " + root.resolve("missing.html")), "File error includes URL suffix: " + error);
            }
            check(service.connectionStatus().equals("connected") && chat.get("error").isBlank(), "Link failure changed the chat connection");
            Files.writeString(output.resolve("links-result.json"), GSON.toJson(object("htmlBrowserAnchorAndQuery", true, "encodedFilename", true, "literalSourceFilename", true, "sourceLineAndColumn", true, "firstLine", true, "missingFile", true, "connectionKept", true)));
        } finally { ui(() -> { editor.dispose(); return null; }); }
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static <T> T ui(Callable<T> action) throws Exception { var task = new FutureTask<T>(action); ApplicationManager.getApplication().invokeAndWait(task); return task.get(); }
    private LinkSmoke() {}
}
