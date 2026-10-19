package com.acorn.codextabs.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class SandboxSettingsTest {
    @Test void convertsWslAndWindowsPathsWithoutSplittingSpaces() {
        assertEquals(List.of("/home/acorn/JS project", "/mnt/c/Work/JS"), SandboxSettings.folders(
            "  \\\\wsl.localhost\\Ubuntu\\home\\acorn\\JS project\n/home/acorn/JS project/\nC:\\Work\\JS\n\n", "Ubuntu", true));
    }
    @Test void rejectsRelativePathsAndAnotherWslDistribution() {
        for (String path : List.of("../js", "~/js", "C:js", "\\\\wsl.localhost\\Debian\\home\\acorn\\js")) {
            assertThrows(IllegalArgumentException.class, () -> SandboxSettings.folders(path, "Ubuntu", true));
        }
        assertThrows(IllegalArgumentException.class, () -> SandboxSettings.folders("/home/acorn/js", "", true));
    }
    @Test void acceptsNativePathsAndEmptySettings() {
        assertEquals(List.of("C:/Work/JS", "//server/share/JS"), SandboxSettings.folders("C:\\Work\\JS\n\\\\server\\share\\JS", "", true));
        assertEquals(List.of("/home/acorn/js"), SandboxSettings.folders("/home/acorn/js", "", false));
        assertEquals(List.of(), SandboxSettings.folders("\n  \n", "Ubuntu", true));
    }
    @Test void mergesFoldersWithoutBroadeningReadOnlyOrNetworkAccess() {
        var folders = List.of("/repo", "/other repo");
        var policy = SandboxSettings.policy("ask", "/repo", folders);
        assertEquals(GSON.toJsonTree(folders), array(policy, "writableRoots"));
        assertFalse(flag(policy, "networkAccess"));
        assertEquals(object("type", "readOnly"), SandboxSettings.policy("read", "/repo", folders));
        assertEquals(policy, SandboxSettings.policy("session", "/repo", folders));
    }
    @Test void threadOverridesPreserveOtherConfigAndCanRemoveFolders() {
        var params = object("config", object("model_reasoning_effort", "high", "sandbox_workspace_write.writable_roots", new String[]{"/old"}));
        SandboxSettings.configureThread(params, List.of("/new"));
        assertEquals("high", text(obj(params, "config"), "model_reasoning_effort"));
        assertEquals(GSON.toJsonTree(List.of("/new")), array(obj(params, "config"), "sandbox_workspace_write.writable_roots"));
        SandboxSettings.configureThread(params, List.of());
        assertTrue(array(obj(params, "config"), "sandbox_workspace_write.writable_roots").isEmpty());
    }
}
