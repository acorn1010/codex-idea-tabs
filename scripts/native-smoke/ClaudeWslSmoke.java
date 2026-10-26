package com.acorn.codextabs.smoke;

import com.acorn.codextabs.core.ClaudeClient;
import com.acorn.codextabs.core.SharedGuidance;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Check real Windows-to-WSL startup without sending a prompt to a model. */
final class ClaudeWslSmoke {
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows") || args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Run on Windows with the WSL distribution and optional Claude executable");
        }
        String distro = args[0];
        String base = run(new ProcessBuilder("wsl.exe", "--distribution", distro, "--exec", "mktemp", "-d", "/tmp/codex-tabs-claude-wsl.XXXXXX")).strip();
        String root = base + "/project with spaces";
        Path host = Path.of("\\\\wsl.localhost\\" + distro + root.replace('/', '\\'));
        Files.createDirectory(host);
        Path fixture = host.resolve("claude-fixture");
        try {
            Files.writeString(fixture, """
                #!/usr/bin/python3
                import json, os, sys
                from pathlib import Path
                args = sys.argv[1:]
                prompt = Path(args[args.index('--append-system-prompt-file') + 1]).read_text(encoding='utf-8') if '--append-system-prompt-file' in args else ''
                value = args[args.index('--settings') + 1]
                settings = json.loads(value if value.startswith('{') else Path(value).read_text(encoding='utf-8'))
                for line in sys.stdin:
                    request = json.loads(line)
                    response = {'cwd': os.getcwd(), 'args': args, 'guidance': prompt, 'settings': settings}
                    print(json.dumps({'type': 'control_response', 'response': {'request_id': request['request_id'], 'subtype': 'success', 'response': response}}), flush=True)
                """);
            run(new ProcessBuilder("wsl.exe", "--distribution", distro, "--exec", "chmod", "+x", root + "/claude-fixture"));
            String instructions = "Use \"quoted text\" and 'single quotes'.\n---\nKeep $HOME, $(printf changed), `printf changed`, C:\\notes\\, and café as text.\n";
            String rules = "WebFetch(domain:example.com)\nBash(curl --output 'a file.png' https://example.com/a,b.png)";
            for (String content : List.of("", instructions, instructions.repeat(700))) {
                String guidance = content.isEmpty() ? "" : new SharedGuidance.Source(root, root + "/AGENTS.md", content, root + "/.agents/skills").claudeContext();
                String session = UUID.randomUUID().toString();
                var builder = ClaudeClient.process(root + "/claude-fixture", root, distro, session, "", false, "", rules, true);
                var files = new ArrayList<Path>();
                try (var client = ClaudeClient.start(builder, distro, guidance, ignored -> {}, ignored -> {})) {
                    files.add(Path.of(com.acorn.codextabs.core.Paths.host(builder.command().get(builder.command().indexOf("--settings") + 1), distro, true)));
                    if (!guidance.isEmpty()) { files.add(Path.of(com.acorn.codextabs.core.Paths.host(builder.command().getLast(), distro, true))); }
                    var output = client.initialize().get(20, TimeUnit.SECONDS);
                    var actual = output.getAsJsonArray("args").asList().stream().map(value -> value.getAsString()).toList();
                    var expected = new ArrayList<>(List.of("--print", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                        "--include-partial-messages", "--replay-user-messages", "--permission-prompt-tool", "stdio",
                        "--settings", builder.command().get(builder.command().indexOf("--settings") + 1), "--session-id=" + session));
                    if (!guidance.isEmpty()) { expected.addAll(List.of("--append-system-prompt-file", builder.command().getLast())); }
                    if (!root.equals(output.get("cwd").getAsString()) || !expected.equals(actual) || !guidance.equals(output.get("guidance").getAsString())
                        || !ClaudeClient.permissionSettings(rules, true).equals(output.getAsJsonObject("settings"))) {
                        throw new AssertionError("WSL changed Claude flags, working directory, or shared guidance");
                    }
                }
                if (files.stream().anyMatch(Files::exists)) { throw new AssertionError("Closing Claude left a temporary launch file behind"); }
                System.out.println("PASS: WSL preserved " + guidance.length() + " characters of guidance, JSON settings, and removed the temporary files");
            }
            var failed = new ProcessBuilder(host.resolve("missing-claude.exe").toString(), "--settings", ClaudeClient.permissionSettings(rules, true).toString());
            try {
                ClaudeClient.start(failed, "", instructions, ignored -> {}, ignored -> {}).close();
                throw new AssertionError("Missing Claude executable did not fail startup");
            } catch (java.io.IOException expected) {
                for (String flag : List.of("--settings", "--append-system-prompt-file")) {
                    if (Files.exists(Path.of(failed.command().get(failed.command().indexOf(flag) + 1)))) {
                        throw new AssertionError("Failed startup left a temporary launch file behind");
                    }
                }
                System.out.println("PASS: failed startup removed settings and guidance files");
            }
            if (args.length == 2) {
                var builder = ClaudeClient.process(args[1], root, distro, UUID.randomUUID().toString(), "", false, "", rules, true);
                builder.command().addAll(List.of("--setting-sources=", "--strict-mcp-config"));
                String guidance = new SharedGuidance.Source(root, root + "/AGENTS.md", instructions, "").claudeContext();
                try (var client = ClaudeClient.start(builder, distro, guidance, ignored -> {}, ignored -> {})) {
                    var initialized = client.initialize().get(35, TimeUnit.SECONDS);
                    if (!initialized.has("models") || initialized.getAsJsonArray("models").isEmpty()) {
                        throw new AssertionError("Claude startup did not return available models");
                    }
                    System.out.println("PASS: installed Claude CLI initialized through Windows and WSL with shared guidance");
                }
            }
        } finally {
            Files.deleteIfExists(fixture);
            Files.deleteIfExists(host);
            Files.deleteIfExists(host.getParent());
        }
    }
    private static String run(ProcessBuilder builder) throws Exception {
        var process = builder.redirectErrorStream(true).start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) { throw new AssertionError("WSL setup process did not finish"); }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) { throw new AssertionError("WSL setup failed: " + output); }
            return output;
        } finally { process.destroyForcibly(); }
    }
}
