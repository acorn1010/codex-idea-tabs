package com.acorn.codextabs.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonObject;
import static com.acorn.codextabs.core.Json.*;

/** Bundle a bounded readiness MCP server beside the plugin without editing global Codex settings. */
public final class ReadinessTool {
    private static Path script;
    private ReadinessTool() {}

    /** Configure Python and curl in the same environment as the Codex process. */
    public static JsonObject config(String distro, boolean windows) {
        boolean wsl = windows && !distro.isBlank();
        String path = script().toString();
        var args = new java.util.ArrayList<String>();
        args.add(wsl ? Paths.linux(path) : path);
        if (wsl) {
            args.add("--windows-curl");
            args.add(Paths.linux(System.getenv().getOrDefault("SystemRoot", "C:\\Windows")) + "/System32/curl.exe");
        }
        return object("command", windows && !wsl ? "python" : "python3", "args", args,
            "startup_timeout_sec", 10, "tool_timeout_sec", 310);
    }

    private static synchronized Path script() {
        if (script != null) { return script; }
        try (var source = ReadinessTool.class.getResourceAsStream("/readiness-mcp.py")) {
            if (source == null) { throw new IOException("The readiness MCP resource is missing."); }
            Path extracted = Files.createTempFile("codex-tabs-readiness-", ".py");
            Files.copy(source, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();
            script = extracted;
            return script;
        } catch (IOException error) { throw new IllegalStateException("Could not install the bundled readiness tool.", error); }
    }
}
