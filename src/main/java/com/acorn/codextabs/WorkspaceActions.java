package com.acorn.codextabs;

import com.acorn.codextabs.core.GitWorktrees;
import com.intellij.diff.*;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.requests.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import java.nio.file.Path;
import java.util.*;

/** Native review, terminal and project actions always target the chat's checkout. Review and terminal actions run on the UI thread. Project opening runs in the background. */
final class WorkspaceActions {
    private WorkspaceActions() {}
    static void review(Project project, List<GitWorktrees.Change> changes) {
        if (changes.isEmpty()) { com.intellij.openapi.ui.Messages.showInfoMessage(project, "No changes in this worktree.", "Review Worktree"); return; }
        var factory = DiffContentFactory.getInstance();
        var requests = new ArrayList<DiffRequest>();
        for (var change : changes) {
            requests.add(change.binary() ? new MessageDiffRequest(change.path(), "Binary or large file. Open the checkout in IDEA to review it.")
                : new SimpleDiffRequest(change.path(), factory.create(change.before()), factory.create(change.after()), "Starting version", "Worktree · " + change.status()));
        }
        DiffManager.getInstance().showDiff(project, new SimpleDiffRequestChain(requests), com.intellij.diff.DiffDialogHints.DEFAULT);
    }
    static void terminal(Project project, String cwd, String distro, String label) {
        try {
            // The optional Terminal dependency supplies its classes through this plugin's own loader.
            var managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager", true, WorkspaceActions.class.getClassLoader());
            var manager = managerClass.getMethod("getInstance", Project.class).invoke(null, project);
            String host = com.acorn.codextabs.core.Paths.host(cwd, distro, SystemInfo.isWindows);
            if (SystemInfo.isWindows && !distro.isBlank()) {
                // Load the user's Bash setup, then select the checkout. Typed commands can be lost during PTY startup.
                String startup = "exec /bin/bash --rcfile <(printf '%s\\n' '[ -f ~/.bashrc ] && . ~/.bashrc' 'builtin cd -- \"$CODEX_TABS_WORKTREE\"' 'unset CODEX_TABS_WORKTREE') -i";
                var command = List.of("wsl.exe", "--distribution", distro, "--exec", "/usr/bin/env", "CODEX_TABS_WORKTREE=" + cwd, "/bin/bash", "-c", startup);
                managerClass.getMethod("createNewSession", String.class, String.class, List.class, boolean.class, boolean.class).invoke(manager, host, label, command, true, false);
            } else { managerClass.getMethod("createShellWidget", String.class, String.class, boolean.class, boolean.class).invoke(manager, host, label, true, false); }
        } catch (ClassNotFoundException error) { throw new IllegalStateException("Enable IDEA's Terminal plugin to use this action.", error); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Could not open IDEA's terminal.", error); }
    }
    static void openProject(Project source, String cwd, String distro) {
        var path = Path.of(com.acorn.codextabs.core.Paths.host(cwd, distro, SystemInfo.isWindows));
        var options = new com.intellij.ide.impl.OpenProjectTaskBuilder();
        options.setForceOpenInNewFrame(true);
        options.setNewProject(!java.nio.file.Files.isDirectory(path.resolve(".idea")));
        try {
            // IDEA 2026.3 changed this public property's type from boolean to Boolean.
            var valueType = options.getClass().getMethod("getRunConfigurators").getReturnType();
            options.getClass().getMethod("setRunConfigurators", valueType).invoke(options, true);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Could not configure the worktree project for IDEA.", error); }
        var opened = com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx().openProject(path, options.build(builder -> kotlin.Unit.INSTANCE));
        if (opened == null || opened == source) { return; }
        var settings = opened.getService(CodexSettings.class);
        var current = settings.getState();
        // Seed a new IDEA workspace. Keep any preferences already saved in an existing project.
        if (current.binary.equals("codex") && current.distro.isBlank() && current.cwd.isBlank() && !current.modelSelectionSaved && current.model.isBlank() && current.effort.isBlank()) {
            var json = com.acorn.codextabs.core.Json.GSON;
            var copy = json.fromJson(json.toJson(CodexService.get(source).settings()), CodexSettings.State.class);
            copy.cwd = cwd; copy.distro = distro;
            settings.loadState(copy);
            CodexService.get(opened).reconnect();
        }
    }
}
