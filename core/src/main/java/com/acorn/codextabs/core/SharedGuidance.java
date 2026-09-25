package com.acorn.codextabs.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shares project guidance from its original location without changing a chat's Git checkout. */
public final class SharedGuidance {
    private static final int MAX_INSTRUCTION_BYTES = 128 * 1024;
    private SharedGuidance() {}

    public record Source(String root, String instructionPath, String instructions, String skillsRoot) {
        public static Source empty() { return new Source("", "", "", ""); }

        /** Use a user-role context item so shared files never replace developer or built-in instructions. */
        public String context() {
            if (root.isBlank()) { return "Codex Tabs shared guidance is now disabled or unavailable. Stop applying earlier Codex Tabs shared guidance blocks. Keep native project instructions and the user's explicit requests."; }
            String context = """
                <codex_tabs_shared_guidance>
                Shared guidance selected by the user in Codex Tabs. This block replaces earlier Codex Tabs shared guidance blocks.
                Source folder: %s
                The current checkout remains the working directory. This is supplemental workspace guidance,
                separate from Codex’s automatically discovered checkout AGENTS.md chain. Native notices about
                replacing AGENTS.md instructions replace that native chain only. Keep this supplemental block
                active alongside the current checkout instructions until a newer shared guidance block replaces it.
                Apply the user's explicit precedence rules. Otherwise, more specific instructions in the current checkout
                take precedence over these shared defaults. Current user requests take precedence over project guidance.
                Keep native project instructions. Resolve relative paths in the shared file against its source folder,
                not the checkout. Resolve links inside skills and rules against each file's original directory.
                Read shared files and linked resources from their original paths. Do not copy them into the checkout.
                This selection adds guidance only and does not grant write access to the shared folder.
                """.formatted(root);
            if (!instructionPath.isBlank()) {
                context += "\nSource file: " + instructionPath + "\n<shared_project_instructions>\n"
                    + instructions + "\n</shared_project_instructions>\n";
            }
            if (!skillsRoot.isBlank()) { context += "\nShared skills are registered from: " + skillsRoot + "\n"; }
            return context + "</codex_tabs_shared_guidance>";
        }

        /** Supply the same selected guidance as user context without claiming native Claude skill registration. */
        public String claudeContext() {
            String context = context().replace("Codex’s automatically discovered checkout AGENTS.md chain", "Claude’s automatically discovered checkout CLAUDE.md chain")
                .replace("Shared skills are registered from:", "Shared skill files are available at:");
            if (!skillsRoot.isBlank()) {
                context += "\nDiscover shared skills by listing this folder. Read a relevant skill's SKILL.md and follow its links at their original paths before applying it.\n";
            }
            return context;
        }

        /** Append context without adding it to the user's editable message or changing the thread's settings. */
        public com.google.gson.JsonObject injection(String threadId) {
            return Json.object("threadId", threadId, "items", new Object[]{Json.object("type", "message", "role", "user",
                "content", new Object[]{Json.object("type", "input_text", "text", context())})});
        }

    }

    /** Empty override detects only the IDEA project folder. Paths returned to Codex use its execution environment. */
    public static Source resolve(boolean enabled, String configured, String projectPath, String distro, boolean windows) {
        if (!enabled) { return Source.empty(); }
        boolean automatic = configured.isBlank();
        String selected = automatic ? projectPath : configured.trim();
        if (selected.isBlank()) { return Source.empty(); }
        if (selected.equals("~") || selected.startsWith("~/")) {
            if (!distro.isBlank()) { throw new IllegalArgumentException("Use an absolute WSL path for the shared guidance folder."); }
            selected = System.getProperty("user.home") + selected.substring(1);
        }
        String executionPath = distro.isBlank() ? selected : Paths.linux(selected);
        var host = Path.of(Paths.host(executionPath, distro, windows)).normalize();
        if (!host.isAbsolute() || !Files.isDirectory(host)) {
            if (automatic) { return Source.empty(); }
            throw new IllegalArgumentException("Shared guidance folder must be an existing absolute directory: " + selected);
        }
        String root = distro.isBlank() ? host.toString() : Paths.linux(host.toString());
        try {
            Path instruction = null;
            String contents = "";
            for (String name : new String[]{"AGENTS.override.md", "AGENTS.md"}) {
                var candidate = host.resolve(name);
                if (!Files.exists(candidate)) { continue; }
                try (var input = Files.newInputStream(candidate)) {
                    byte[] bytes = input.readNBytes(MAX_INSTRUCTION_BYTES + 1);
                    if (bytes.length > MAX_INSTRUCTION_BYTES) { throw new IOException("Shared instruction file exceeds 128 KiB: " + candidate); }
                    contents = new String(bytes, StandardCharsets.UTF_8);
                }
                if (!contents.isBlank()) { instruction = candidate; break; }
            }
            boolean skills = Files.isDirectory(host.resolve(".agents/skills"));
            if (automatic && instruction == null && !Files.isDirectory(host.resolve(".agents"))) { return Source.empty(); }
            String prefix = root.replaceAll("[/\\\\]+$", "") + "/";
            return new Source(root, instruction == null ? "" : prefix + instruction.getFileName(), contents,
                skills ? prefix + ".agents/skills" : "");
        } catch (IOException error) {
            throw new IllegalStateException("Could not read shared guidance from " + root + ": " + error.getMessage(), error);
        }
    }
}
