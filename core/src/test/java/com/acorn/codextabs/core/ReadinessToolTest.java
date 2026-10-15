package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessToolTest {
    @Test void bundlesTheScriptAndKeepsTimeoutsAboveTheToolDeadline() {
        var config = ReadinessTool.config("", false);
        assertEquals("python3", text(config, "command"));
        assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(array(config, "args").get(0).getAsString())));
        assertEquals(310, config.get("tool_timeout_sec").getAsInt());
    }
    @Test void usesTheExecutionEnvironmentsPythonAndAnExplicitWindowsCurl() {
        var nativeConfig = ReadinessTool.config("", true);
        assertEquals("python", text(nativeConfig, "command"));
        var wslConfig = ReadinessTool.config("Ubuntu", true);
        assertEquals("python3", text(wslConfig, "command"));
        assertEquals("--windows-curl", array(wslConfig, "args").get(1).getAsString());
        assertTrue(array(wslConfig, "args").get(2).getAsString().endsWith("/System32/curl.exe"));
    }
}
