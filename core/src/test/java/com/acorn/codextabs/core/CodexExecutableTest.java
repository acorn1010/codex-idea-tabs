package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodexExecutableTest {
    @TempDir Path temp;

    private Path executable(String name) throws Exception {
        Path path = temp.resolve(name).resolve("codex");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/bin/sh\nexit 0\n");
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    @Test void findsAppWithRestrictedDesktopPath() throws Exception {
        Path app = executable("Applications/ChatGPT.app/Contents/Resources");
        assertEquals(app.toString(), CodexExecutable.resolve("codex", true, "/usr/bin:/bin", List.of(app)));
        assertEquals(app.toString(), CodexExecutable.resolve("", true, null, List.of(app)));
    }

    @Test void prefersPathAndPreservesCustomExecutables() throws Exception {
        Path cli = executable("cli with spaces");
        Path app = executable("app");
        assertEquals(cli.toString(), CodexExecutable.resolve("codex", true, cli.getParent().toString(), List.of(app)));
        assertEquals("/custom/missing codex", CodexExecutable.resolve("/custom/missing codex", true, "", List.of(app)));
        assertEquals("custom-codex", CodexExecutable.resolve("custom-codex", true, "", List.of(app)));
        assertEquals("codex", CodexExecutable.resolve("codex", false, "", List.of(app)));
    }

    @Test void skipsInvalidCandidatesAndKeepsMissingExecutableError() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("directory"));
        Path plain = Files.writeString(temp.resolve("plain"), "not executable");
        assertEquals("codex", CodexExecutable.resolve("codex", true, "", List.of(directory, plain, temp.resolve("missing"))));
    }
}
