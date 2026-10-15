package com.acorn.codextabs.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** Give Codex a private local model URL with compaction and tool compatibility. */
public final class LocalModelRelay implements AutoCloseable {
    private final Process process;
    private final Path script;
    private final String baseUrl;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    public LocalModelRelay(String distro, String upstream, String windowsRoot, boolean windows) throws Exception {
        script = Files.createTempFile("codex-tabs-model-", ".py");
        Process started = null;
        try {
            try (var source = LocalModelRelay.class.getResourceAsStream("/wsl-model-relay.py")) {
                if (source == null) { throw new IOException("The local model relay resource is missing."); }
                Files.copy(source, script, StandardCopyOption.REPLACE_EXISTING);
            }
            boolean wsl = windows && !distro.isBlank();
            var command = new java.util.ArrayList<String>();
            if (wsl) { command.addAll(java.util.List.of("wsl.exe", "--distribution", distro, "--exec")); }
            command.add(windows && !wsl ? "python" : "python3");
            command.add(wsl ? Paths.linux(script.toString()) : script.toString());
            command.add(upstream);
            command.add(windows ? (wsl ? Paths.linux(windowsRoot) : windowsRoot) + "/System32/curl.exe" : "curl");
            started = new ProcessBuilder(command).redirectErrorStream(true).start();
            process = started;
            var ready = new CompletableFuture<String>();
            Thread.ofPlatform().daemon().name("codex-tabs-model-relay").start(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String first = lines.readLine();
                    if (first == null || !first.startsWith("http://127.0.0.1:")) {
                        throw new IOException("The local model relay could not start. Check that Python 3 is installed in the Codex environment. " + first);
                    }
                    ready.complete(first);
                    while (lines.readLine() != null) { /* Drain HTTP request logs so the relay cannot block on its pipe. */ }
                } catch (Exception error) { ready.completeExceptionally(error); }
            });
            baseUrl = ready.get(15, TimeUnit.SECONDS);
        } catch (Exception error) {
            if (started != null) { started.destroy(); }
            Files.deleteIfExists(script);
            throw error;
        }
    }
    /** This loopback URL belongs to the same environment as the harness. */
    public String baseUrl() { return baseUrl; }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        try { process.getOutputStream().close(); } catch (IOException ignored) { process.destroy(); }
        Thread.ofPlatform().daemon().name("codex-tabs-model-relay-close").start(this::finishClose);
    }
    private void finishClose() {
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
        try { Files.deleteIfExists(script); } catch (IOException ignored) { script.toFile().deleteOnExit(); }
    }
}
