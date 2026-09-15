package com.acorn.codextabs.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Discover independent repositories without walking dependencies or following directory symlinks. */
public final class GitRepositories {
    private static final int MAX_DEPTH = 6;
    private static final int MAX_DIRECTORIES = 5000;
    private static final Set<String> SKIP = Set.of("node_modules", "vendor", "build", "dist", "target", "venv", "out");
    public record Repository(String path, String name, List<GitWorktrees.Workspace> workspaces) {}
    public record Catalog(List<Repository> repositories, List<String> errors) {
        public Optional<Repository> containing(String path) {
            return repositories.stream().filter(repo -> repo.workspaces.stream().anyMatch(tree -> GitWorktrees.contains(tree.path(), path)))
                .max(Comparator.comparingInt(repo -> repo.workspaces.stream().filter(tree -> GitWorktrees.contains(tree.path(), path)).mapToInt(tree -> tree.path().length()).max().orElse(0)));
        }
    }
    private final GitWorktrees git;
    private final String distro;
    public GitRepositories(String distro, boolean windows) { this.git = new GitWorktrees(distro, windows); this.distro = distro; }

    /** Resolve the nearest Git marker, including a linked worktree's .git file. No Git process runs here. */
    public String closestRoot(String path) {
        for (Path directory = git.hostPath(path); directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve(".git"))) { return executionPath(directory); }
        }
        return "";
    }
    private String executionPath(Path path) { return distro.isBlank() ? path.toString().replace('\\', '/') : Paths.linux(path.toString()); }

    public Catalog discover(String folder, Collection<String> knownDirectories) {
        var candidates = new LinkedHashSet<String>();
        var errors = new ArrayList<String>();
        String root = closestRoot(folder);
        if (!root.isBlank()) { candidates.add(root); }
        Path start = git.hostPath(folder);
        int[] visited = {0};
        try {
            Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (++visited[0] > MAX_DIRECTORIES) {
                        errors.add("Some folders were not scanned. Open a file in a repository to add it.");
                        return FileVisitResult.TERMINATE;
                    }
                    if (directory.equals(start)) { return FileVisitResult.CONTINUE; }
                    String name = directory.getFileName().toString();
                    if (name.startsWith(".") || name.endsWith(".worktrees") || SKIP.contains(name)) { return FileVisitResult.SKIP_SUBTREE; }
                    if (Files.exists(directory.resolve(".git"))) {
                        candidates.add(executionPath(directory));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, IOException error) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException error) { errors.add("Could not scan the project folder: " + error.getMessage()); }
        for (String directory : knownDirectories) {
            String known = closestRoot(directory);
            if (!known.isBlank()) { candidates.add(known); }
        }
        var repositories = new LinkedHashMap<String, Repository>();
        for (String candidate : candidates) {
            if (repositories.values().stream().anyMatch(repo -> repo.workspaces.stream().anyMatch(tree -> GitWorktrees.same(tree.path(), candidate)))) { continue; }
            try {
                var trees = git.list(candidate);
                if (trees.isEmpty()) { continue; }
                String primary = trees.getFirst().path();
                repositories.putIfAbsent(primary, new Repository(primary, trees.getFirst().name(), trees));
            } catch (RuntimeException error) { errors.add(candidate + ": " + error.getMessage()); }
        }
        return new Catalog(repositories.values().stream().sorted(Comparator.comparing(Repository::path)).toList(), List.copyOf(errors));
    }
}
