package com.acorn.codextabs.core;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Compare login metadata without reading, storing, or watching authentication tokens. */
public record ClaudeAccount(boolean loggedIn, String method, String email, String organization, String provider) {
    public static Optional<ClaudeAccount> read(ProcessBuilder builder) {
        Process process = null;
        Path output = null;
        try {
            // A file avoids filling a process pipe before exit or waiting for a helper to close stdout.
            output = Files.createTempFile("codex-tabs-claude-account-", ".json");
            process = builder.redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { return Optional.empty(); }
            try (var stream = Files.newInputStream(output)) {
                byte[] bytes = stream.readNBytes(65537);
                if (bytes.length > 65536) { return Optional.empty(); }
                return parse(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
            // Older CLIs and unavailable credential stores must not break existing chats.
            return Optional.empty();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); }
            if (output != null) {
                try { Files.deleteIfExists(output); }
                catch (IOException ignored) { output.toFile().deleteOnExit(); }
            }
        }
    }
    static Optional<ClaudeAccount> parse(String value) {
        try {
            var status = JsonParser.parseString(value).getAsJsonObject();
            if (!status.has("loggedIn") || !status.get("loggedIn").isJsonPrimitive() || !status.getAsJsonPrimitive("loggedIn").isBoolean()) { return Optional.empty(); }
            boolean loggedIn = flag(status, "loggedIn");
            String method = text(status, "authMethod"), email = text(status, "email").strip().toLowerCase(Locale.ROOT);
            String organization = text(status, "orgId"), provider = text(status, "apiProvider");
            if (loggedIn && method.isBlank()) { return Optional.empty(); }
            return Optional.of(new ClaudeAccount(loggedIn, method, email, organization, provider));
        } catch (RuntimeException ignored) { return Optional.empty(); }
    }
}
