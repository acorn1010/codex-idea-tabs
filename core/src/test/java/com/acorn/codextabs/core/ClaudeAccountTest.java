package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeAccountTest {
    @Test void detectsOrganizationAndAuthChangesWithoutTrackingTokenRefreshes() {
        var first = ClaudeAccount.parse("{\"loggedIn\":true,\"authMethod\":\"claude.ai\",\"email\":\"USER@example.test\",\"orgId\":\"one\",\"apiProvider\":\"firstParty\"}").orElseThrow();
        var refreshed = ClaudeAccount.parse("{\"loggedIn\":true,\"authMethod\":\"claude.ai\",\"email\":\"user@example.test\",\"orgId\":\"one\",\"apiProvider\":\"firstParty\",\"accessToken\":\"not-stored\"}").orElseThrow();
        assertEquals(first, refreshed);
        assertFalse(refreshed.toString().contains("not-stored"));
        assertNotEquals(first, new ClaudeAccount(true, "claude.ai", first.email(), "two", first.provider()));
        assertNotEquals(first, new ClaudeAccount(true, "api_key", first.email(), first.organization(), first.provider()));
        assertFalse(ClaudeAccount.parse("{\"loggedIn\":false}").orElseThrow().loggedIn());
        for (String invalid : List.of("", "Unsupported command", "{}", "{\"loggedIn\":\"false\"}", "{\"loggedIn\":true}")) { assertTrue(ClaudeAccount.parse(invalid).isEmpty()); }
    }
    @Test void authProbeUsesTheChatEnvironmentAndNeverStartsStreaming(@TempDir Path cwd) {
        var local = ClaudeClient.authentication("/some/node/bin/claude", cwd.toString(), "");
        assertEquals(List.of("/some/node/bin/claude", "auth", "status", "--json"), local.command());
        assertEquals(cwd.toFile(), local.directory());
        assertTrue(local.environment().get("PATH").startsWith("/some/node/bin"));
        var wsl = ClaudeClient.authentication("/home/me/claude", "/work/a b", "Ubuntu").command();
        assertEquals("wsl.exe", wsl.getFirst());
        assertEquals(List.of("/home/me/claude", "auth", "status", "--json"), wsl.subList(wsl.size() - 4, wsl.size()));
        assertTrue(wsl.contains("/work/a b"));
    }
    @Test void oversizedProbeOutputCannotFillAPipeAndStallSending() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
            assertTrue(ClaudeAccount.read(new ProcessBuilder("python3", "-c", "print('x' * 100000)")).isEmpty()));
    }
    @Test void unsupportedAndMalformedProbesAreUnavailableRatherThanSignOut() {
        assertTrue(ClaudeAccount.read(new ProcessBuilder("/does-not-exist/claude")).isEmpty());
        assertTrue(ClaudeAccount.read(new ProcessBuilder("python3", "-c", "print('not JSON')")).isEmpty());
        assertFalse(ClaudeAccount.read(new ProcessBuilder("python3", "-c", "import sys; print('{\"loggedIn\":false}'); sys.exit(1)")).orElseThrow().loggedIn());
    }
}
