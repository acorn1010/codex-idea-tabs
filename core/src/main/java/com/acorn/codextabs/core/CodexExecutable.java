package com.acorn.codextabs.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Find the default Mac executable when a desktop IDE has a limited PATH. */
public final class CodexExecutable {
    private CodexExecutable() {}

    public static String resolve(String configured, boolean mac) {
        String home = System.getProperty("user.home");
        return resolve(configured, mac, System.getenv("PATH"), List.of(
            Path.of("/opt/homebrew/bin/codex"), Path.of("/usr/local/bin/codex"),
            Path.of(home, "Applications/Codex.app/Contents/Resources/codex"),
            Path.of("/Applications/Codex.app/Contents/Resources/codex"),
            Path.of(home, "Applications/ChatGPT.app/Contents/Resources/codex"),
            Path.of("/Applications/ChatGPT.app/Contents/Resources/codex")));
    }

    static String resolve(String configured, boolean mac, String path, List<Path> fallbacks) {
        String binary = configured.isBlank() ? "codex" : configured;
        if (!mac || !binary.equals("codex")) { return binary; }
        var candidates = new ArrayList<Path>();
        if (path != null) {
            for (String directory : path.split(":")) {
                // Do not search the current project for an executable.
                if (directory.startsWith("/")) { candidates.add(Path.of(directory, "codex")); }
            }
        }
        candidates.addAll(fallbacks);
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) { return candidate.toString(); }
        }
        return binary;
    }
}
