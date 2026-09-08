package com.acorn.codextabs.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Git worktree operations run in the same host or WSL environment as the chat. No shell parses user input. */
public final class GitWorktrees {
    private static final int OUTPUT_LIMIT = 32 * 1024 * 1024;
    private final String distro;
    private final boolean windows;
    public record Workspace(String path, String branch, String head, boolean main, boolean locked, boolean missing) {
        public String name() { return path.replace('\\', '/').replaceAll("/$", "").replaceAll(".*/", ""); }
    }
    public record Change(String path, String status, String before, String after, boolean binary) {}
    public record Created(Workspace workspace, String warning) {}
    private record Result(int code, byte[] bytes, String error) {
        String text() { return new String(bytes, StandardCharsets.UTF_8).stripTrailing(); }
    }
    public GitWorktrees(String distro, boolean windows) { this.distro = distro; this.windows = windows; }
    public Path hostPath(String path) { return Path.of(Paths.host(path, distro, windows)); }
    public boolean exists(String path) { return Files.isDirectory(hostPath(path)); }
    public String root(String path) { return git(path, "rev-parse", "--show-toplevel").text(); }
    public List<Workspace> list(String cwd) {
        var output = git(cwd, "worktree", "list", "--porcelain", "-z").text();
        var result = new ArrayList<Workspace>();
        String path = "", branch = "", head = "";
        boolean locked = false, missing = false;
        for (var field : (output + "\0\0").split("\0", -1)) {
            if (field.isEmpty()) {
                if (!path.isEmpty()) { result.add(new Workspace(path, branch, head, result.isEmpty(), locked, missing || !exists(path))); }
                path = ""; branch = ""; head = ""; locked = false; missing = false;
            } else if (field.startsWith("worktree ")) { path = field.substring(9); }
            else if (field.startsWith("branch refs/heads/")) { branch = field.substring(18); }
            else if (field.startsWith("HEAD ")) { head = field.substring(5); }
            else if (field.startsWith("locked")) { locked = true; }
            else if (field.startsWith("prunable")) { missing = true; }
        }
        return List.copyOf(result);
    }
    public List<String> branches(String cwd) {
        return git(cwd, "for-each-ref", "--format=%(refname:short)", "refs/heads", "refs/remotes").text().lines()
            .filter(value -> !value.endsWith("/HEAD")).toList();
    }
    public String defaultBase(String cwd) {
        var symbolic = run(cwd, null, "symbolic-ref", "--short", "HEAD");
        return symbolic.code == 0 ? symbolic.text() : git(cwd, "rev-parse", "HEAD").text();
    }
    public String reviewBase(String cwd) {
        var all = list(cwd);
        if (all.isEmpty()) { return "HEAD"; }
        var result = run(cwd, null, "merge-base", "HEAD", all.getFirst().head());
        return result.code == 0 ? result.text() : "HEAD";
    }
    public static String slug(String title) {
        var value = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (value.length() > 40) { value = value.substring(0, 40).replaceAll("-$", ""); }
        return value.isEmpty() || value.equals("new-chat") ? "task" : value;
    }
    /** Create a new branch without changing the original index, files or branch. Partial copies remain recoverable. */
    public Created create(String cwd, String name, String base, boolean includeChanges) {
        if (!name.matches("[a-z0-9][a-z0-9-]{0,47}") || name.endsWith("-")) {
            throw new IllegalArgumentException("Use up to 48 lowercase letters, numbers and hyphens for the name.");
        }
        String commit = git(cwd, "rev-parse", "--verify", "--end-of-options", base + "^{commit}").text();
        var all = list(cwd);
        if (all.isEmpty()) { throw new IllegalStateException("No Git checkout was found."); }
        String main = all.getFirst().path();
        String destination = main + ".worktrees/" + name;
        String branch = "codex/" + name;
        if (Files.exists(hostPath(destination))) { throw new IllegalArgumentException("That worktree directory already exists. Choose another name."); }
        // Capture changes first, including staged changes and binary files. Ignored files stay in the original checkout.
        byte[] patch = includeChanges ? git(cwd, "diff", "--binary", "--full-index", "HEAD", "--").bytes : new byte[0];
        var untracked = includeChanges ? untracked(cwd) : List.<String>of();
        git(cwd, "worktree", "add", "-b", branch, "--", destination, commit);
        String warning = "";
        if (includeChanges) {
            try {
                if (patch.length > 0) { checked(run(destination, patch, "apply", "--binary", "-")); }
                for (String file : untracked) {
                    var source = safeChild(hostPath(cwd), file);
                    var target = safeChild(hostPath(destination), file);
                    Files.createDirectories(target.getParent());
                    // Do not overwrite tracked files from another base or follow an untracked symlink.
                    Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (Exception error) {
                warning = "The worktree was created, but some local changes could not be copied. The original is unchanged. " + error.getMessage();
            }
        }
        var workspace = list(cwd).stream().filter(value -> same(value.path, destination)).findFirst().orElseThrow();
        return new Created(workspace, warning);
    }
    private List<String> untracked(String cwd) {
        return Arrays.stream(git(cwd, "ls-files", "--others", "--exclude-standard", "-z").text().split("\0")).filter(value -> !value.isEmpty()).toList();
    }
    /** Removal is conservative: keep dirty trees, locks, and commits that have not reached the primary checkout. */
    public String removalBlock(String cwd, String target) {
        var all = list(cwd);
        var workspace = all.stream().filter(value -> same(value.path, target)).findFirst().orElseThrow(() -> new IllegalArgumentException("This worktree is no longer registered."));
        if (workspace.main) { return "The primary checkout cannot be removed."; }
        if (workspace.locked) { return "This worktree is locked. Unlock it in Git first."; }
        if (workspace.missing) { return "The directory is missing. Repair or prune its Git entry first."; }
        if (!git(target, "status", "--porcelain", "--untracked-files=all").text().isEmpty()) { return "Commit or move the changed and untracked files first."; }
        if (run(cwd, null, "merge-base", "--is-ancestor", workspace.head, all.getFirst().head).code != 0) {
            return "This worktree has commits that are not in the primary checkout.";
        }
        return "";
    }
    public void remove(String cwd, String target) {
        String reason = removalBlock(cwd, target);
        if (!reason.isEmpty()) { throw new IllegalStateException(reason); }
        git(cwd, "worktree", "remove", "--", target);
    }
    /** Review branch changes, local edits and untracked files against the saved starting commit. */
    public List<Change> changes(String cwd, String base) {
        String commit = git(cwd, "rev-parse", "--verify", "--end-of-options", (base.isBlank() ? "HEAD" : base) + "^{commit}").text();
        var names = new LinkedHashSet<String>();
        Collections.addAll(names, git(cwd, "diff", "--name-only", "-z", commit, "--").text().split("\0"));
        names.addAll(untracked(cwd)); names.remove("");
        if (names.size() > 500) { throw new IllegalStateException("More than 500 files changed. Use IDEA's Git tools to review this checkout."); }
        var changes = new ArrayList<Change>();
        long remainingBytes = 8 * 1024 * 1024;
        for (var name : names) {
            var previous = run(cwd, null, "show", commit + ":" + name);
            var path = safeChild(hostPath(cwd), name);
            byte[] after;
            try {
                if (Files.isSymbolicLink(path)) { after = Files.readSymbolicLink(path).toString().getBytes(StandardCharsets.UTF_8); }
                else if (!Files.exists(path)) { after = new byte[0]; }
                else if (Files.isDirectory(path) || Files.size(path) > 1024 * 1024) { changes.add(new Change(name, "modified", "", "", true)); continue; }
                else { after = Files.readAllBytes(path); }
            } catch (IOException error) { throw new IllegalStateException("Could not read " + name, error); }
            byte[] before = previous.code == 0 ? previous.bytes : new byte[0];
            boolean binary = before.length > 1024 * 1024 || before.length + after.length > remainingBytes || containsZero(before) || containsZero(after);
            if (!binary) { remainingBytes -= before.length + after.length; }
            changes.add(new Change(name, previous.code != 0 ? "added" : !Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? "deleted" : "modified",
                binary ? "" : new String(before, StandardCharsets.UTF_8), binary ? "" : new String(after, StandardCharsets.UTF_8), binary));
        }
        return changes;
    }
    private static boolean containsZero(byte[] bytes) { for (byte value : bytes) { if (value == 0) { return true; } } return false; }
    private static Path safeChild(Path root, String name) {
        var normalized = root.toAbsolutePath().normalize();
        var child = normalized.resolve(name).normalize();
        if (!child.startsWith(normalized)) { throw new IllegalArgumentException("The file is outside this worktree."); }
        // A parent symlink could otherwise copy to, or read from, another checkout.
        for (var parent = child.getParent(); parent != null && !parent.equals(normalized); parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) { throw new IllegalArgumentException("A parent directory is a symlink: " + name); }
        }
        return child;
    }
    public static boolean same(String left, String right) { return normalized(left).equals(normalized(right)); }
    public static boolean contains(String root, String path) { return same(root, path) || normalized(path).startsWith(normalized(root) + "/"); }
    private static String normalized(String path) { return path.replace('\\', '/').replaceAll("/+$", ""); }
    private Result git(String cwd, String... args) { return checked(run(cwd, null, args)); }
    private Result checked(Result result) {
        if (result.code != 0) { throw new IllegalStateException(result.error.isBlank() ? result.text() : result.error.strip()); }
        return result;
    }
    private Result run(String cwd, byte[] input, String... args) {
        var command = new ArrayList<String>();
        if (windows && !distro.isBlank()) { command.addAll(List.of("wsl.exe", "--distribution", distro, "--exec")); }
        command.addAll(List.of("git", "-C", cwd)); command.addAll(List.of(args));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var builder = new ProcessBuilder(command);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            var process = builder.start();
            try {
                var stdout = executor.submit(() -> read(process.getInputStream()));
                var stderr = executor.submit(() -> read(process.getErrorStream()));
                var stdin = executor.submit(() -> { try (var stream = process.getOutputStream()) { if (input != null) { stream.write(input); } } return null; });
                if (!process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS)) { throw new IllegalStateException("Git took too long. Check this checkout and try again."); }
                stdin.get();
                return new Result(process.exitValue(), stdout.get(), new String(stderr.get(), StandardCharsets.UTF_8));
            } finally { process.destroyForcibly(); }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Git was interrupted.", error); }
        catch (IOException | ExecutionException error) { throw new IllegalStateException("Could not run Git: " + error.getMessage(), error); }
    }
    private static byte[] read(InputStream input) throws IOException {
        var result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int size; boolean tooLarge = false;
        while ((size = input.read(buffer)) >= 0) {
            if (result.size() + size <= OUTPUT_LIMIT) { result.write(buffer, 0, size); } else { tooLarge = true; }
        }
        if (tooLarge) { throw new IOException("Git output exceeds 32 MB. Use IDEA's Git tools for this operation."); }
        return result.toByteArray();
    }
}
