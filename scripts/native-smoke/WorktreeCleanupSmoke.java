package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.Conversation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Exercise bulk cleanup against disposable Git repositories and the actual native dialog. */
final class WorktreeCleanupSmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project); service.restoreReady().get(10, TimeUnit.SECONDS);
        service.settings().sharedGuidanceEnabled = false;
        var root = Path.of(service.cwd());
        var api = root.resolve("api"); var worker = root.resolve("worker");
        for (var repo : List.of(api, worker)) {
            Files.createDirectories(repo); git(repo, "init", "-b", "main");
            git(repo, "config", "user.email", "fixture@example.invalid"); git(repo, "config", "user.name", "Fixture");
            git(repo, "config", "commit.gpgsign", "false"); git(repo, "config", "core.hooksPath", "/dev/null");
            Files.writeString(repo.resolve("tracked.txt"), "original\n"); Files.writeString(repo.resolve(".gitignore"), "ignored/\n");
            git(repo, "add", "tracked.txt", ".gitignore"); git(repo, "commit", "-m", "Fixture");
        }
        var paths = new LinkedHashMap<String, Path>();
        for (String name : List.of("clean", "dirty", "closed-codex", "closed-claude", "locked", "detached", "restore-race", "dirty-race", "other-repo")) {
            var repo = name.equals("other-repo") ? worker : api;
            var path = root.resolve(repo.getFileName() + ".worktrees").resolve(name); paths.put(name, path);
            git(repo, "worktree", "add", "-b", "codex/" + name, path.toString(), "main");
        }
        git(api, "worktree", "lock", paths.get("locked").toString());
        var detached = paths.get("detached"); git(detached, "checkout", "--detach");
        Files.writeString(detached.resolve("tracked.txt"), "detached change\n"); git(detached, "commit", "-am", "Detached work");
        var dirty = paths.get("dirty"); Files.writeString(dirty.resolve("tracked.txt"), "local changes\n");
        Files.writeString(dirty.resolve("untracked.txt"), "keep until discard\n"); Files.createDirectories(dirty.resolve("ignored"));
        Files.writeString(dirty.resolve("ignored/cache.txt"), "ignored local data\n");
        var saved = savedChat(service, paths.get("clean"), "codex", true);
        saved.set("draft", "Keep archived draft");
        savedChat(service, paths.get("dirty"), "claude", true);
        savedChat(service, paths.get("closed-codex"), "codex", false);
        savedChat(service, paths.get("closed-claude"), "claude", false);
        var restored = savedChat(service, paths.get("restore-race"), "codex", true);
        service.changed("");
        var scan = service.cleanupCandidates().get(30, TimeUnit.SECONDS);
        check(scan.warnings().isEmpty(), "Fixture scan warnings");
        var found = new HashMap<String, CodexService.CleanupWorktree>(); scan.worktrees().forEach(tree -> found.put(tree.name(), tree));
        check(found.size() == 7 && !found.containsKey("closed-codex") && !found.containsKey("closed-claude"), "Closed unarchived chats from either provider must protect their worktrees");
        check(found.get("clean").archivedChats() == 1 && found.get("clean").files().isEmpty(), "Archived chat is eligible");
        check(found.get("dirty").files().stream().map(file -> file.status()).collect(java.util.stream.Collectors.toSet()).equals(Set.of("Changed", "Untracked", "Ignored")), "Review all local file types");
        check(found.get("locked").blocked().contains("locked") && found.get("detached").blocked().contains("detached"), "Preserve existing Git protections");
        check(scan.worktrees().stream().map(tree -> tree.repository()).distinct().count() == 2, "Discover both subrepositories");
        service.settings().cwd = paths.get("dirty-race").toString();
        try { service.removeUnusedWorktree(paths.get("dirty-race").toString(), false).get(10, TimeUnit.SECONDS); throw new AssertionError("Removed the current project checkout"); }
        catch (ExecutionException expected) { check(CodexService.message(expected).contains("current IDEA project"), "Current project removal guard"); }
        finally { service.settings().cwd = root.toString(); }

        check(ui(() -> FileEditorManager.getInstance(project).getOpenFiles().length) == 0, "Cleanup should not need a chat tab");
        ui(() -> { var tool = ToolWindowManager.getInstance(project).getToolWindow("Codex"); tool.setAvailable(true); tool.show(); return null; });
        var sidebar = ui(() -> ToolWindowManager.getInstance(project).getToolWindow("Codex").getContentManager().getContent(0).getComponent());
        ui(() -> { button(sidebar, "Chat actions").doClick(); return null; });
        ui(() -> { var menu = MenuSelectionManager.defaultManager().getSelectedPath(); for (var part : menu) { if (part.getComponent() instanceof JPopupMenu popup) { button(popup, "Clean up worktrees…").doClick(); return null; } } throw new AssertionError("Sidebar cleanup menu missing"); });
        Window dialog = awaitDialog("Clean up worktrees");
        await(() -> uiUnchecked(() -> button(dialog, "Remove selected (4)").isEnabled()));
        check(!ui(() -> checkbox(dialog, dirty).isSelected()), "Dirty worktree selected by default");
        check(!ui(() -> checkbox(dialog, paths.get("locked")).isEnabled()), "Locked worktree selectable");
        ui(() -> { button(dialog, "Review worktree " + dirty).doClick(); capture(dialog, output.resolve("cleanup-review.png")); return null; });
        // The preview is stale: one chat is restored and one clean worktree gains a file.
        service.archive(restored.id, false).get(10, TimeUnit.SECONDS);
        Files.writeString(paths.get("dirty-race").resolve("new-file.txt"), "created after preview\n");
        ui(() -> { button(dialog, "Remove selected (4)").doClick(); return null; });
        await(() -> uiUnchecked(() -> hasText(dialog, "Removed 2 of 4 worktrees.")));
        check(!Files.exists(paths.get("clean")) && !Files.exists(paths.get("other-repo")), "Clean selected worktrees not removed");
        check(Files.exists(paths.get("restore-race")) && Files.exists(paths.get("dirty-race")), "Stale preview removed a protected worktree");
        check(saved.archived() && saved.get("draft").equals("Keep archived draft"), "Removal changed the saved chat");
        git(api, "show-ref", "--verify", "refs/heads/codex/clean"); git(worker, "show-ref", "--verify", "refs/heads/codex/other-repo");
        ui(() -> { checkbox(dialog, dirty).doClick(); button(dialog, "Remove selected (1)").doClick(); return null; });
        check(ui(() -> button(dialog, "Discard changes and remove (1)").isEnabled()), "Explicit discard confirmation missing");
        ui(() -> { capture(dialog, output.resolve("cleanup-discard.png")); button(dialog, "Cancel").doClick(); return null; });
        check(Files.exists(dirty.resolve("untracked.txt")), "Cancel discarded files");
        // Hold removal at the workspace lock while testing Hide and reopening the same job.
        var lockField = CodexService.class.getDeclaredField("workspaceLock"); lockField.setAccessible(true);
        var lock = ((java.util.concurrent.locks.ReentrantReadWriteLock) lockField.get(service)).readLock();
        lock.lock();
        try {
            ui(() -> { button(dialog, "Remove selected (1)").doClick(); button(dialog, "Discard changes and remove (1)").doClick(); return null; });
            ui(() -> { button(dialog, "Hide").doClick(); return null; });
            check(!ui(dialog::isShowing), "Hide should release the window while removal continues");
            ui(() -> { WorktreeCleanupDialog.open(project); return null; });
            check(ui(dialog::isShowing), "Reopening cleanup should return to the running job");
        } finally { lock.unlock(); }
        await(() -> !Files.exists(dirty));
        await(() -> uiUnchecked(() -> hasText(dialog, "Removed 1 of 1 worktrees.")));
        git(api, "show-ref", "--verify", "refs/heads/codex/dirty");
        ui(() -> { capture(dialog, output.resolve("cleanup-results.png")); button(dialog, "Close").doClick(); return null; });
        check(Files.exists(paths.get("locked")) && Files.exists(detached) && Files.exists(paths.get("closed-codex")) && Files.exists(paths.get("closed-claude")), "Protected worktree removed");
        var source = service.create(); source.set("provider", "codex");
        ui(() -> { ChatFiles.open(project, source.id, false); return null; });
        var editor = ui(() -> FileEditorManager.getInstance(project).getSelectedEditor());
        var handle = ChatEditor.class.getDeclaredMethod("handle", String.class, com.google.gson.JsonObject.class); handle.setAccessible(true);
        ((CompletableFuture<?>) handle.invoke(editor, "cleanupWorktrees", object())).get(10, TimeUnit.SECONDS);
        var fromChat = awaitDialog("Clean up worktrees");
        ui(() -> { button(fromChat, "Close").doClick(); return null; });

        Files.writeString(output.resolve("cleanup-result.json"), GSON.toJson(object("groupedSubrepos", true, "closedChatsBothProvidersProtected", true, "cleanPreselected", true,
            "dirtyUncheckedAndReviewed", true, "discardConfirmationAndCancel", true, "primaryLockedAndDetachedProtected", true, "restoredChatRechecked", true, "newLocalFilesRechecked", true,
            "partialFailureContinues", true, "hideAndResumeProgress", true, "chatPickerBridge", true, "currentProjectProtected", true, "branchesAndArchivedDraftKept", true, "sidebarWithoutChatTabs", true)));
    }
    private static Conversation savedChat(CodexService service, Path path, String provider, boolean archived) {
        var chat = service.createForPath(path.toString()); chat.set("provider", provider); chat.archive(archived); return chat;
    }
    private static void git(Path cwd, String... arguments) throws Exception {
        var command = new ArrayList<String>(List.of("git", "-C", cwd.toString())); command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) { throw new AssertionError(command + "\n" + output); }
    }
    private static AbstractButton button(Component root, String name) {
        if (root instanceof AbstractButton button && (name.equals(button.getText()) || name.equals(button.getAccessibleContext().getAccessibleName()))) { return button; }
        if (root instanceof Container parent) { for (var child : parent.getComponents()) { try { return button(child, name); } catch (NoSuchElementException ignored) {} } }
        throw new NoSuchElementException(name);
    }
    private static JCheckBox checkbox(Component root, Path path) { return (JCheckBox) button(root, "Select worktree " + path); }
    private static boolean hasText(Component root, String text) {
        if (root instanceof JLabel label && label.getText().startsWith(text)) { return true; }
        if (root instanceof Container parent) { for (var child : parent.getComponents()) { if (hasText(child, text)) { return true; } } }
        return false;
    }
    private static Window awaitDialog(String title) throws Exception {
        var found = new Window[1];
        await(() -> uiUnchecked(() -> { for (Window window : Window.getWindows()) { if (window instanceof Dialog dialog && dialog.isShowing() && dialog.getTitle().equals(title)) { found[0] = window; return true; } } return false; }));
        return found[0];
    }
    private static void capture(Component component, Path path) {
        try { var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics(); component.paintAll(graphics); graphics.dispose(); ImageIO.write(image, "png", path.toFile()); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static <T> T ui(Callable<T> action) throws Exception { var task = new FutureTask<T>(action); ApplicationManager.getApplication().invokeAndWait(task); return task.get(); }
    private static boolean uiUnchecked(Callable<Boolean> action) { try { return ui(action); } catch (NoSuchElementException | ExecutionException error) { return false; } catch (Exception error) { throw new RuntimeException(error); } }
    private static void await(BooleanSupplier condition) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); while (!condition.getAsBoolean()) { if (System.nanoTime() > end) { throw new AssertionError("Cleanup did not reach expected state"); } Thread.sleep(50); } }
    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    private WorktreeCleanupSmoke() {}
}
