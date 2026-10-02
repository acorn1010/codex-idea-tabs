package com.acorn.codextabs.smoke;

import com.acorn.codextabs.core.ClaudeClient;
import com.acorn.codextabs.core.SharedGuidance;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        String root = run(new ProcessBuilder("wsl.exe", "--distribution", distro, "--exec", "mktemp", "-d", "/tmp/codex-tabs-claude-wsl.XXXXXX")).strip();
        Path host = Path.of("\\\\wsl.localhost\\" + distro + root.replace('/', '\\'));
        Path fixture = host.resolve("claude-fixture");
        try {
            Files.writeString(fixture, """
                #!/usr/bin/python3
                import json, os, sys
                from pathlib import Path
                args = sys.argv[1:]
                prompt = Path(args[args.index('--append-system-prompt-file') + 1]).read_text(encoding='utf-8')
                for line in sys.stdin:
                    request = json.loads(line)
                    response = {'cwd': os.getcwd(), 'args': args, 'guidance': prompt}
                    print(json.dumps({'type': 'control_response', 'response': {'request_id': request['request_id'], 'subtype': 'success', 'response': response}}), flush=True)
                """);
            run(new ProcessBuilder("wsl.exe", "--distribution", distro, "--exec", "chmod", "+x", root + "/claude-fixture"));
            String instructions = "Use \"quoted text\" and 'single quotes'.\n---\nKeep $HOME, $(printf changed), `printf changed`, C:\\notes\\, and café as text.\n";
            for (String content : List.of(instructions, instructions.repeat(700))) {
                String guidance = new SharedGuidance.Source(root, root + "/AGENTS.md", content, root + "/.agents/skills").claudeContext();
                String session = UUID.randomUUID().toString();
                var builder = ClaudeClient.process(root + "/claude-fixture", root, distro, session, "", false);
                Path promptFile;
                try (var client = ClaudeClient.start(builder, distro, guidance, ignored -> {}, ignored -> {})) {
                    promptFile = Path.of(com.acorn.codextabs.core.Paths.host(builder.command().getLast(), distro, true));
                    var output = client.initialize().get(20, TimeUnit.SECONDS);
                    var actual = output.getAsJsonArray("args").asList().stream().map(value -> value.getAsString()).toList();
                    var expected = List.of("--print", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                        "--include-partial-messages", "--permission-prompt-tool", "stdio", "--session-id=" + session,
                        "--append-system-prompt-file", builder.command().getLast());
                    if (!root.equals(output.get("cwd").getAsString()) || !expected.equals(actual) || !guidance.equals(output.get("guidance").getAsString())) {
                        throw new AssertionError("WSL changed Claude flags, working directory, or shared guidance");
                    }
                }
                if (Files.exists(promptFile)) { throw new AssertionError("Closing Claude left its temporary guidance file behind"); }
                System.out.println("PASS: WSL preserved " + guidance.length() + " characters of guidance and removed the temporary file");
            }
            if (args.length == 2) {
                var builder = ClaudeClient.process(args[1], root, distro, UUID.randomUUID().toString(), "", false);
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
