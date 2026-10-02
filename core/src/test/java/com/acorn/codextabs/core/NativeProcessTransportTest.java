package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

/** Real OS pipes must not prevent unrelated virtual tasks from starting. */
class NativeProcessTransportTest {
    @Test void idleClaudeProcessesDoNotStarveBackgroundWork(@TempDir Path root) throws Exception { check("claude", root); }
    @Test void idleCodexProcessesDoNotStarveBackgroundWork(@TempDir Path root) throws Exception { check("codex", root); }

    private static void check(String provider, Path root) throws Exception {
        var log = root.resolve("result.log");
        var process = javaProcess(Harness.class, provider)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Native pipe check did not finish");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
    private static ProcessBuilder javaProcess(Class<?> main, String... args) throws Exception {
        var entries = new LinkedHashSet<String>();
        for (var type : List.of(NativeProcessTransportTest.class, ClaudeClient.class, com.google.gson.Gson.class)) {
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djdk.virtualThreadScheduler.parallelism=2", "-Djdk.virtualThreadScheduler.maxPoolSize=2",
            "-cp", String.join(File.pathSeparator, entries), main.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command);
    }
    public static final class Harness {
        public static void main(String[] args) throws Exception {
            var clients = new ArrayList<AutoCloseable>();
            var processes = new ArrayList<Process>();
            var jobs = Executors.newVirtualThreadPerTaskExecutor();
            try {
                for (int i = 0; i < 3; i++) {
                    var process = javaProcess(Fixture.class).start(); processes.add(process);
                    if (args[0].equals("claude")) { clients.add(new ClaudeClient(process, ignored -> {}, ignored -> {})); }
                    else { clients.add(new RpcClient(process, ignored -> {}, ignored -> {})); }
                }
                // Exercise the same shared scheduler used by sends, attachments, and reconnects.
                for (int round = 0; round < 10; round++) {
                    jobs.submit(() -> "background work").get(2, TimeUnit.SECONDS);
                    for (var client : clients) {
                        var result = client instanceof ClaudeClient claude
                            ? claude.control(object("subtype", "initialize"))
                            : ((RpcClient) client).request("initialize", object());
                        if (!flag(result.get(2, TimeUnit.SECONDS), "ok")) { throw new AssertionError("Missing pipe response"); }
                    }
                }
                System.out.println(args[0] + ": three live processes, background work and requests passed");
            } catch (TimeoutException error) {
                throw new AssertionError(args[0] + " process pipes starved the shared virtual-thread scheduler", error);
            } finally {
                for (var client : clients) { client.close(); }
                for (var process : processes) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
                jobs.shutdownNow();
            }
        }
    }
    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            try (var input = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = input.readLine()) != null) {
                    var request = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                    var response = request.has("request_id")
                        ? object("type", "control_response", "response", object("subtype", "success", "request_id", text(request, "request_id"), "response", object("ok", true)))
                        : object("id", request.get("id"), "result", object("ok", true));
                    System.out.println(GSON.toJson(response));
                    System.out.flush();
                }
            }
        }
    }
}
