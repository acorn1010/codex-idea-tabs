package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.*;
import com.google.gson.*;
import com.intellij.openapi.project.Project;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import jdk.jfr.Recording;
import static com.acorn.codextabs.core.Json.*;

/** Fixed, content-free workload matching a large local chat library. Never starts a model. */
final class PerformanceSmoke {
    static void run(Project project, Path output) throws Exception {
        var task = new FutureTask<Void>(() -> { measure(project, output); return null; });
        Thread.ofPlatform().name("codex-tabs-performance").start(task);
        task.get(180, TimeUnit.SECONDS);
    }
    @SuppressWarnings("unchecked")
    private static void measure(Project project, Path output) throws Exception {
        var service = CodexService.get(project); service.restoreReady().get(10, TimeUnit.SECONDS);
        ((ScheduledExecutorService) field(service, "persistence")).shutdownNow();
        var chats = (Map<String, Conversation>) field(service, "chats");
        String body = "Synthetic tool output for repeatable performance checks. ".repeat(87);
        if (Boolean.getBoolean("codex.smoke.performance.restore")) {
            if (chats.size() != 223 || !chats.get("perf-0").get("draft").equals("Unsent fixture draft")) { throw new AssertionError("Saved chats or draft missing"); }
            for (int i = 0; i < 223; i++) {
                var chat = chats.get("perf-" + i);
                if (chat.archived() != (i >= 64) || !chat.get("cwd").equals("/fixture/repo.worktrees/task-" + (i % 40))) { throw new AssertionError("Saved chat metadata changed"); }
                var items = chat.items();
                if (items.size() != 213) { throw new AssertionError("Saved history is incomplete"); }
                for (int j = 0; j < items.size(); j++) {
                    var item = items.get(j).getAsJsonObject();
                    if (!text(item, "id").equals("item-" + j) || !text(item, "aggregatedOutput").equals(body)) { throw new AssertionError("Saved history changed"); }
                }
            }
            Files.writeString(output.resolve("performance-restore.json"), GSON.toJson(object("chats", 223, "items", 223 * 213, "draft", true, "metadata", true, "allHistory", true)));
            return;
        }
        if (!chats.isEmpty()) { throw new IllegalStateException("Performance checks require an empty disposable profile"); }
        for (int i = 0; i < 223; i++) {
            var items = new JsonArray();
            for (int j = 0; j < 213; j++) { items.add(object("id", "item-" + j, "type", "commandExecution", "aggregatedOutput", body)); }
            var saved = object("id", "perf-" + i, "provider", "codex", "cwd", "/fixture/repo.worktrees/task-" + (i % 40),
                "title", "Fixture chat " + i, "updatedAt", 1_000_000L + i, "archived", i >= 64, "items", items);
            chats.put("perf-" + i, Conversation.restore(saved));
        }
        var workspaces = new ArrayList<GitWorktrees.Workspace>();
        for (int i = 0; i < 156; i++) { workspaces.add(new GitWorktrees.Workspace("/fixture/repo.worktrees/task-" + i, "codex/task-" + i, "abc", false, false, false)); }
        var catalog = new GitRepositories.Catalog(List.of(new GitRepositories.Repository("/fixture/repo", "repo", workspaces)), List.of());
        var update = CodexService.class.getDeclaredMethod("updateRepositories", GitRepositories.Catalog.class); update.setAccessible(true); update.invoke(service, catalog);
        long[] revisions = new long[4];
        for (int i = 0; i < 4; i++) { revisions[i] = chats.get("perf-" + i).revision(); }
        var result = object("chats", chats.size(), "items", 223 * 213, "worktrees", 156, "visibleChats", 4);
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(GSON.toJson(service.summaries()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update(GSON.toJson(service.summaries(true)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < 4; i++) { digest.update(GSON.toJson(service.snapshot("perf-" + i, revisions[i])).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        result.addProperty("refreshDigest", HexFormat.of().formatHex(digest.digest()));
        try (var recording = new Recording(jdk.jfr.Configuration.getConfiguration("profile"))) {
            recording.start();
            result.add("refresh", bench(30, 30, () -> {
                int bytes = GSON.toJson(service.summaries()).length() + GSON.toJson(service.summaries(true)).length();
                for (int i = 0; i < 4; i++) {
                    var snapshot = service.snapshot("perf-" + i, revisions[i]);
                    if (array(snapshot, "sessions").size() != 64 || !array(obj(snapshot, "chat"), "items").isEmpty()) { throw new AssertionError("Incremental refresh changed content"); }
                    bytes += GSON.toJson(snapshot).length();
                }
                return bytes;
            }));
            var save = CodexService.class.getDeclaredMethod("save"); save.setAccessible(true);
            result.add("saveDraft", bench(1, 5, () -> {
                chats.get("perf-0").set("draft", "Unsent fixture draft"); service.changed("perf-0"); save.invoke(service);
                return 1;
            }));
            var cache = (Path) field(service, "cache");
            try (var files = Files.walk(cache.getParent())) {
                long bytes = 0;
                for (var path : files.filter(Files::isRegularFile).toList()) { bytes += Files.size(path); }
                result.addProperty("cacheBytes", bytes);
            }
            // Re-read the cache through the service's normal constructor in a second launch.
            result.addProperty("expectedDraft", "Unsent fixture draft");
            recording.stop(); recording.dump(output.resolve("performance.jfr"));
        }
        Files.writeString(output.resolve("performance-result.json"), GSON.toJson(result));
    }
    private static JsonObject bench(int warmup, int rounds, Callable<Integer> work) throws Exception {
        for (int i = 0; i < warmup; i++) { work.call(); }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        var wall = new JsonArray(); var cpu = new JsonArray(); var allocated = new JsonArray(); int checksum = 0;
        for (int i = 0; i < rounds; i++) {
            long start = System.nanoTime(), beforeCpu = bean.getCurrentThreadCpuTime(), beforeBytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            checksum = work.call();
            wall.add((System.nanoTime() - start) / 1_000_000.0); cpu.add((bean.getCurrentThreadCpuTime() - beforeCpu) / 1_000_000.0);
            allocated.add(bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeBytes);
        }
        return object("wallMs", wall, "cpuMs", cpu, "allocatedBytes", allocated, "outputCheck", checksum);
    }
    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
}
