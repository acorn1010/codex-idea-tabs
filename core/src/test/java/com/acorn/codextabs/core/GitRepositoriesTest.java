package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.GitWorktreesTest.git;

class GitRepositoriesTest {
    @TempDir Path temp;
    private final GitRepositories repositories = new GitRepositories("", false);
    private final GitWorktrees worktrees = new GitWorktrees("", false);

    private Path repository(Path path, String file, String branch) throws Exception {
        Files.createDirectories(path);
        git(path, "init", "-b", branch);
        git(path, "config", "user.name", "Test"); git(path, "config", "user.email", "test@example.invalid");
        Files.writeString(path.resolve(file), "original\n");
        git(path, "add", file); git(path, "commit", "-m", "Initial");
        return path.toRealPath();
    }
    @Test void isolatesNestedRepositoriesAndDeduplicatesLinkedWorktrees() throws Exception {
        Path outer = repository(temp.resolve("project"), "outer.txt", "main");
        Path client = repository(outer.resolve("client"), "client.txt", "client-main");
        Path server = repository(outer.resolve("server"), "server.txt", "server-main");
        var tree = worktrees.create(client.toString(), "task", "client-main", false).workspace();
        assertTrue(Files.isRegularFile(Path.of(tree.path(), ".git")));
        var catalog = repositories.discover(outer.toString(), List.of(tree.path()));
        assertEquals(3, catalog.repositories().size());
        assertTrue(catalog.errors().isEmpty(), catalog.errors().toString());
        assertEquals(client.toString(), catalog.containing(client.resolve("client.txt").toString()).orElseThrow().path());
        assertEquals(server.toString(), catalog.containing(server.toString()).orElseThrow().path());
        assertEquals(client.toString(), catalog.containing(tree.path()).orElseThrow().path());
        assertEquals(2, catalog.containing(tree.path()).orElseThrow().workspaces().size());
        assertEquals(client.toString(), repositories.closestRoot(client.resolve("client.txt").toString()));
        assertEquals(tree.path(), repositories.closestRoot(tree.path() + "/client.txt"));
        assertFalse(Files.exists(Path.of(tree.path(), "outer.txt")));
        assertFalse(Files.exists(Path.of(tree.path(), "server/server.txt")));
        assertEquals(1, worktrees.list(outer.toString()).size());
        assertEquals(1, worktrees.list(server.toString()).size());
        assertFalse(worktrees.branches(server.toString()).contains("codex/task"));
        worktrees.remove(client.toString(), tree.path());
        assertTrue(Files.exists(server.resolve("server.txt")));
    }
    @Test void discoversRepositoriesWithoutAnOuterGitRootAndSkipsDependencies() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("folder"));
        Path nested = repository(folder.resolve("packages/api"), "api.txt", "main");
        repository(folder.resolve("node_modules/dependency"), "dep.txt", "main");
        Path external = repository(temp.resolve("external"), "ext.txt", "main");
        Files.createSymbolicLink(folder.resolve("linked"), external);
        var catalog = repositories.discover(folder.toString(), List.of());
        assertEquals(List.of(nested.toString()), catalog.repositories().stream().map(GitRepositories.Repository::path).toList());
        assertTrue(catalog.containing(folder.toString()).isEmpty());
        assertTrue(catalog.containing(nested + "-other").isEmpty());
        assertEquals(2, repositories.discover(folder.toString(), List.of(external.toString())).repositories().size());
    }
    @Test void resolvesDeeperSelectedRepositoriesAndRetainsMissingWorktrees() throws Exception {
        Path outer = repository(temp.resolve("outer"), "a.txt", "main");
        Path nested = repository(outer.resolve("nested"), "b.txt", "main");
        Path deeper = repository(nested.resolve("deeper"), "c.txt", "main");
        var tree = worktrees.create(nested.toString(), "gone", "main", false).workspace();
        try (var paths = Files.walk(Path.of(tree.path()))) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); }
        }
        var catalog = repositories.discover(outer.toString(), List.of(repositories.closestRoot(deeper.toString())));
        assertEquals(3, catalog.repositories().size());
        assertEquals(deeper.toString(), catalog.containing(deeper.toString()).orElseThrow().path());
        assertTrue(catalog.containing(tree.path()).orElseThrow().workspaces().stream().anyMatch(value -> value.path().equals(tree.path()) && value.missing()));
    }
}
