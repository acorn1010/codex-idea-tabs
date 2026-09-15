package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.*;
import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Exercise repository routing with real Git and a local fake app server in a disposable IDEA profile. */
final class RepositorySmoke {
    static void run(Project project, Path output) throws Exception {
        if (!System.getProperty("codex.smoke.binary", "").endsWith("fake-codex.py")) { throw new IllegalStateException("Use the deterministic fixture."); }
        var service = CodexService.get(project);
        service.restoreReady().get();
        String root = service.cwd(), client = root + "/client", server = root + "/server";
        String suffix = Long.toString(System.currentTimeMillis());
        var selected = service.createForPathAsync(client + "/client.txt").get();
        check(selected.get("cwd").equals(client), "File selection chose the outer repository");
        var choices = service.workspaces(selected.id).get();
        check(array(choices, "repositories").size() == 3, "Expected outer, client, and server repositories");
        check(text(choices, "repository").equals(client), "Wrong selected repository");
        check(array(choices, "branches").contains(new JsonPrimitive("client-main")), "Missing client branch");
        check(!array(choices, "branches").contains(new JsonPrimitive("server-main")), "Branch list included another repository");
        check(service.workspaceLabel(client).startsWith("client · "), "Repository missing from workspace label");
        var git = new GitWorktrees("", false);
        var created = service.changeWorkspace(selected.id, object("create", true, "name", "client-" + suffix, "base", "client-main", "includeChanges", true)).get(30, TimeUnit.SECONDS);
        var checkout = service.chat(text(created, "id"));
        check(checkout.id.equals(selected.id), "An unsent chat should stay in its tab");
        check(Files.readString(Path.of(checkout.get("cwd"), "client.txt")).equals("local client changes\n"), "Local changes did not reach the client worktree");
        check(!Files.exists(Path.of(checkout.get("cwd"), "outer.txt")), "Worktree belongs to outer repository");
        check(git.list(root).size() == 1 && git.list(server).size() == 1, "Another repository gained a worktree");
        check(service.workspaceChanges(checkout.id).get().stream().anyMatch(change -> change.path().equals("client.txt")), "Review did not use the selected checkout");
        check(text(service.inspectWorktree(checkout.get("cwd")).get(), "blocked").contains("changed"), "Dirty worktree was not protected");
        String clean = git.create(server, "clean-" + suffix, "server-main", false).workspace().path();
        service.removeWorktree(clean).get();
        check(!Files.exists(Path.of(clean)), "Removal did not use the server repository");
        var original = service.importThread(object("id", "repository-source-" + suffix, "name", "Repository selection", "cwd", root));
        original.set("draft", "Keep original draft");
        service.load(original.id).get(30, TimeUnit.SECONDS);
        var result = service.changeWorkspace(original.id, object("path", client, "draft", "Carry this draft", "attachments", new JsonArray())).get(30, TimeUnit.SECONDS);
        var fork = service.chat(text(result, "id"));
        check(!fork.id.equals(original.id), "Established chat was replaced");
        check(original.get("cwd").equals(root) && original.get("draft").equals("Keep original draft"), "Original chat was changed");
        check(fork.get("cwd").equals(client) && fork.get("draft").equals("Carry this draft"), "New repository lost checkout or draft");
        check(service.createInWorkspace(fork.id).get("cwd").equals(client), "New chat did not inherit repository");
        service.editorOpened(fork.id);
        service.send(fork.id, object("text", "Check selected repository", "permissions", "auto")).get(30, TimeUnit.SECONDS);
        var stats = service.rpc("fixture/stats", new JsonObject()).get();
        check(text(obj(stats, "cwds"), fork.get("threadId")).equals(client), "Codex used the outer working directory");
        check(array(obj(obj(stats, "lastTurn"), "sandboxPolicy"), "writableRoots").contains(new JsonPrimitive(client)), "Sandbox used the outer working directory");
        service.editorClosed(fork.id);
        service.workspaces(fork.id).get(); service.history("", "").get();
        var history = array(obj(service.rpc("fixture/stats", new JsonObject()).get(), "lastList"), "cwd");
        check(history.contains(new JsonPrimitive(server)) && history.contains(new JsonPrimitive(checkout.get("cwd"))), "History omitted another repository or worktree");
        var restored = Conversation.restore(fork.snapshot());
        check(restored.get("cwd").equals(client), "Saved chat lost repository");
        String subfolder = client + "/src";
        Files.createDirectories(Path.of(subfolder));
        service.settings().cwd = subfolder;
        try {
            var fromServer = service.createForPathAsync(server + "/server.txt").get();
            var moved = service.changeWorkspace(fromServer.id, object("path", subfolder)).get();
            check(service.chat(text(moved, "id")).get("cwd").equals(subfolder), "Project subfolder could not be selected");
        } finally { service.settings().cwd = root; }
        ApplicationManager.getApplication().invokeAndWait(() -> { ChatFiles.open(project, original.id, false); ChatFiles.open(project, fork.id, true); });
        Files.createDirectories(output);
        Files.writeString(output.resolve("repositories-result.json"), GSON.toJson(object("root", root, "client", client, "server", server, "original", original.id, "fork", fork.id, "worktree", checkout.get("cwd"),
            "fileSelection", true, "branchesIsolated", true, "worktreeIsolated", true, "reviewRouted", true, "removalRouted", true, "draftPreserved", true, "sandboxRouted", true, "historyComplete", true, "savedRepository", true)));
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private RepositorySmoke() {}
}
