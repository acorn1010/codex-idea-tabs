package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class GitWorktreesTest {
    @TempDir Path temp;
    final GitWorktrees worktrees = new GitWorktrees("", false);
    Path repository() throws Exception {
        var root = Files.createDirectory(temp.resolve("project with spaces"));
        git(root, "init", "-b", "main"); git(root, "config", "user.email", "test@example.invalid"); git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("a.txt"), "original\n"); Files.writeString(root.resolve(".gitignore"), "ignored\n");
        git(root, "add", "."); git(root, "commit", "-m", "Initial"); return root;
    }
    @Test void createsFromChosenBaseAndCopiesWithoutChangingOriginal() throws Exception {
        var root = repository();
        Files.writeString(root.resolve("a.txt"), "staged\n"); git(root, "add", "a.txt"); Files.writeString(root.resolve("a.txt"), "unstaged\n");
        Files.write(root.resolve("binary"), new byte[]{0,1,2}); Files.writeString(root.resolve("ignored"), "private");
        String before = git(root, "status", "--porcelain");
        var created = worktrees.create(root.toString(), "first-task", "main", true);
        assertEquals("", created.warning()); var path = Path.of(created.workspace().path());
        assertEquals("unstaged\n", Files.readString(path.resolve("a.txt")));
        assertArrayEquals(new byte[]{0,1,2}, Files.readAllBytes(path.resolve("binary")));
        assertFalse(Files.exists(path.resolve("ignored")));
        assertEquals(before, git(root, "status", "--porcelain"));
        assertEquals("main", git(root, "branch", "--show-current").strip());
        assertEquals("codex/first-task", created.workspace().branch());
        assertEquals(2, worktrees.list(root.toString()).size());
        assertTrue(worktrees.removalBlock(root.toString(), path.toString()).contains("changed"));
        assertEquals(2, worktrees.changes(path.toString(), "main").size());
    }
    @Test void protectsUnmergedAndLockedTreesAndKeepsBranchOnRemoval() throws Exception {
        var root = repository();
        var created = worktrees.create(root.toString(), "branch", "HEAD", false).workspace(); var path = Path.of(created.path());
        git(root, "worktree", "lock", path.toString()); assertTrue(worktrees.removalBlock(root.toString(), path.toString()).contains("locked"));
        git(root, "worktree", "unlock", path.toString());
        Files.writeString(path.resolve("b.txt"), "new\n"); git(path, "add", "."); git(path, "commit", "-m", "Task");
        assertTrue(worktrees.removalBlock(root.toString(), path.toString()).contains("commits"));
        assertThrows(IllegalStateException.class, () -> worktrees.remove(root.toString(), path.toString()));
        git(root, "merge", "--ff-only", "codex/branch"); worktrees.remove(root.toString(), path.toString());
        assertFalse(Files.exists(path)); assertTrue(worktrees.branches(root.toString()).contains("codex/branch"));
        assertTrue(worktrees.removalBlock(root.toString(), root.toString()).contains("primary"));
    }
    @Test void rejectsInvalidNamesAndLeavesFailedCopyRecoverable() throws Exception {
        var root = repository();
        assertThrows(IllegalArgumentException.class, () -> worktrees.create(root.toString(), "../escape", "main", false));
        assertThrows(IllegalStateException.class, () -> worktrees.create(root.toString(), "valid", "--help", false));
        Files.writeString(root.resolve("a.txt"), "changed\n");
        git(root, "checkout", "-b", "different"); git(root, "add", "."); git(root, "commit", "-m", "Different");
        Files.writeString(root.resolve("a.txt"), "changed again\n");
        var result = worktrees.create(root.toString(), "conflict", "main", true);
        assertFalse(result.warning().isEmpty()); assertTrue(Files.isDirectory(Path.of(result.workspace().path())));
        assertEquals("changed again\n", Files.readString(root.resolve("a.txt")));
    }
    @Test void contextMembershipUsesDirectoryBoundaries() {
        assertTrue(GitWorktrees.contains("/repo/tree", "/repo/tree/src/a.ts"));
        assertFalse(GitWorktrees.contains("/repo/tree", "/repo/tree-two/src/a.ts"));
        assertEquals("fix-layout", GitWorktrees.slug("Fix layout!?"));
    }
    static String git(Path root, String... args) throws Exception {
        var command = new java.util.ArrayList<String>(java.util.List.of("git", "-C", root.toString())); command.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start(); var output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output); return output;
    }
}
