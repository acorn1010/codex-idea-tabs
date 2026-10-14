package com.acorn.codextabs.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** Give a WSL Codex process a private URL for the Windows-local model API. */
public final class WslModelRelay implements AutoCloseable {
    private final Process process;
    private final Path script;
    private final String baseUrl;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    public WslModelRelay(String distro, String upstream, String windowsRoot) throws Exception {
        script = Files.createTempFile("codex-tabs-model-", ".py");
        Process started = null;
        try {
            try (var source = WslModelRelay.class.getResourceAsStream("/wsl-model-relay.py")) {
                if (source == null) { throw new IOException("The local model relay resource is missing."); }
                Files.copy(source, script, StandardCopyOption.REPLACE_EXISTING);
            }
            started = new ProcessBuilder("wsl.exe", "--distribution", distro, "--exec", "python3", Paths.linux(script.toString()),
                upstream, Paths.linux(windowsRoot) + "/System32/curl.exe").redirectErrorStream(true).start();
            process = started;
            var ready = new CompletableFuture<String>();
            Thread.ofPlatform().daemon().name("codex-tabs-model-relay").start(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String first = lines.readLine();
                    if (first == null || !first.startsWith("http://127.0.0.1:")) {
                        throw new IOException("The WSL model relay could not start. Check that Python 3 is installed in WSL. " + first);
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
    /** This loopback URL belongs to the same WSL environment as the harness. */
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
