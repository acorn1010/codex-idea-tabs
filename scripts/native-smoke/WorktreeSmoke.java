package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.GitWorktrees;
import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.ToolWindowManager;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Prove real Git worktree routing through the Windows host, WSL backend and native editor bridge. */
final class WorktreeSmoke {
    static void run(Project project, Path output) throws Exception {
        if (!System.getProperty("codex.smoke.binary", "").endsWith("fake-codex.py")) { throw new IllegalStateException("Use the deterministic fixture."); }
        var service = CodexService.get(project);
        var git = new GitWorktrees(service.distro(), SystemInfo.isWindows);
        String suffix = Long.toString(System.currentTimeMillis());
        var original = service.importThread(object("id", "worktree-source-" + suffix, "name", "Review the shop in a separate checkout", "cwd", service.cwd()));
        original.set("draft", "Keep my original draft");
        service.load(original.id).get(30, TimeUnit.SECONDS);
        var listed = service.workspaces(original.id).get(30, TimeUnit.SECONDS);
        check(!array(listed, "entries").isEmpty(), "No checkout discovered through WSL");
        var changed = service.changeWorkspace(original.id, object("create", true, "name", "native-" + suffix, "base", "main", "includeChanges", true, "draft", "New checkout draft")).get(60, TimeUnit.SECONDS);
        var fork = service.chat(text(changed, "id"));
        check(!fork.id.equals(original.id), "Established conversation was replaced");
        check(original.get("cwd").equals(service.cwd()), "Original checkout changed");
        check(original.get("draft").equals("Keep my original draft"), "Original draft changed");
        check(fork.get("draft").equals("New checkout draft"), "Fork lost the current draft");
        check(Files.readString(git.hostPath(fork.get("cwd") + "/layout.txt")).equals("current local layout\n"), "Uncommitted changes did not cross the WSL boundary");
        var sibling = service.createInWorkspace(fork.id);
        check(sibling.get("cwd").equals(fork.get("cwd")), "New chat did not inherit its workspace");
        service.editorOpened(fork.id);
        service.send(fork.id, object("text", "Verify the worktree path", "permissions", "auto")).get(30, TimeUnit.SECONDS);
        var stats = service.rpc("fixture/stats", new JsonObject()).get();
        check(text(obj(stats, "cwds"), fork.get("threadId")).equals(fork.get("cwd")), "Codex resumed in a different checkout");
        check(array(obj(obj(stats, "lastTurn"), "sandboxPolicy"), "writableRoots").contains(new JsonPrimitive(fork.get("cwd"))), "Sandbox did not target worktree");
        service.workspaces(fork.id).get(); service.history("", "").get();
        check(array(obj(service.rpc("fixture/stats", new JsonObject()).get(), "lastList"), "cwd").contains(new JsonPrimitive(fork.get("cwd"))), "Worktree history was omitted");
        check(service.workspaceChanges(fork.id).get().stream().anyMatch(change -> change.path().equals("layout.txt")), "Native review omitted local edits");
        service.rpc("fixture/event", object("event", object("method", "turn/started", "params", object("threadId", fork.get("threadId"), "turn", object("id", "worktree-active", "status", "inProgress"))))).get();
        check(text(service.inspectWorktree(fork.get("cwd")).get(), "blocked").contains("still working"), "Active worktree could be removed");
        service.rpc("fixture/event", object("event", object("method", "turn/completed", "params", object("threadId", fork.get("threadId"), "turn", object("id", "worktree-active", "status", "completed"))))).get();
        service.editorClosed(fork.id);
        check(text(service.inspectWorktree(fork.get("cwd")).get(), "blocked").contains("changed"), "Dirty worktree could be removed");
        service.changed(fork.id);
        ApplicationManager.getApplication().invokeAndWait(() -> {
            ChatFiles.open(project, original.id, false); ChatFiles.open(project, fork.id, true);
            var window = ToolWindowManager.getInstance(project).getToolWindow("Codex"); if (window != null) { window.show(); }
        });
        NativeUiProbe.start(project);
        Files.createDirectories(output);
        Files.writeString(output.resolve("worktrees-result.json"), GSON.toJson(object("original", original.id, "fork", fork.id, "cwd", fork.get("cwd"), "wslCopy", true, "originalKept", true, "newChatInherited", true,
            "sandboxRouted", true, "historyIncludesWorktrees", true, "reviewIncludesChanges", true, "busyRemovalBlocked", true, "dirtyRemovalBlocked", true)));
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private WorktreeSmoke() {}
}
