package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.intellij.openapi.project.Project;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Check disconnect recovery through the real project service using disposable CLI fixtures. */
final class ProviderRecoverySmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project);
        service.restoreReady().get(10, TimeUnit.SECONDS);
        service.settings().claudeBinary = System.getProperty("codex.smoke.claudeBinary");
        service.settings().sharedGuidanceEnabled = false;
        service.settings().provider = "claude";
        var chat = service.create();
        chat.set("draft", "Keep this draft");
        service.editorOpened(chat.id);
        try {
            service.load(chat.id).get(10, TimeUnit.SECONDS);
            check(text(service.snapshot(chat.id), "connection").equals("connected"), "Initial Claude connection");
            disconnect(Path.of(service.cwd()));
            await(() -> text(service.snapshot(chat.id), "connection").equals("disconnected"));
            service.load(chat.id).get(10, TimeUnit.SECONDS);
            check(text(service.snapshot(chat.id), "connection").equals("connected"), "A disconnected Claude chat reused its completed load instead of reconnecting");
            service.reconnect(chat.id);
            service.load(chat.id).get(10, TimeUnit.SECONDS);
            check(text(service.snapshot(chat.id), "connection").equals("connected"), "Explicit reconnect");
            disconnect(Path.of(service.cwd()));
            await(() -> text(service.snapshot(chat.id), "connection").equals("disconnected"));
            var switched = service.selectProvider(chat.id, "codex").get(10, TimeUnit.SECONDS);
            check(text(obj(switched, "chat"), "provider").equals("codex") && text(switched, "connection").equals("connected"), "Switch away from disconnected Claude");
            check(text(obj(switched, "chat"), "draft").equals("Keep this draft"), "Switch lost the draft");
            switched = service.selectProvider(chat.id, "claude").get(10, TimeUnit.SECONDS);
            check(text(switched, "connection").equals("connected"), "Switch back to Claude");
            check(service.chat(chat.id).get("threadId").isBlank(), "Unsent switch must not save a startup session ID");
            service.reconnect(chat.id);
            service.load(chat.id).get(10, TimeUnit.SECONDS);
            check(text(service.snapshot(chat.id), "connection").equals("connected") && service.chat(chat.id).get("threadId").isBlank(), "Reconnect before the first Claude message");
            Path root = Path.of(service.cwd());
            Files.writeString(root.resolve("hold-model"), "pause turn setup");
            var send = service.send(chat.id, object("text", "Keep this draft", "permissions", "ask"));
            await(() -> Files.exists(root.resolve("model-waiting")));
            try {
                send.get(20, TimeUnit.SECONDS);
                throw new AssertionError("An unresponsive Claude accepted the message");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(CodexService.message(expected).contains("message was not sent"), "Setup timeout must explain whether the draft was sent");
            } finally { Files.deleteIfExists(root.resolve("hold-model")); }
            check(text(service.snapshot(chat.id), "connection").equals("disconnected"), "Timed-out setup must release the stalled connection");
            check(service.chat(chat.id).get("draft").equals("Keep this draft"), "Failed send lost the draft");
            check(Files.readAllLines(root.resolve("wire.jsonl")).stream().noneMatch(line -> text(com.google.gson.JsonParser.parseString(line).getAsJsonObject(), "type").equals("user")), "Failed setup sent a user message");
            service.send(chat.id, object("text", "Keep this draft", "permissions", "ask")).get(10, TimeUnit.SECONDS);
            await(() -> !service.chat(chat.id).busy());
            check(service.chat(chat.id).get("draft").isEmpty(), "Successful retry did not clear the draft");
            check(Files.readAllLines(root.resolve("wire.jsonl")).stream().filter(line -> text(com.google.gson.JsonParser.parseString(line).getAsJsonObject(), "type").equals("user")).count() == 1, "Retry must send the message exactly once");
            Files.writeString(output.resolve("provider-recovery-result.json"), GSON.toJson(object(
                "unsentHandoffReconnect", true, "failedSendDraftAndRetry", true, "reloadAfterExit", true, "explicitReconnect", true, "switchFromDisconnected", true, "draftKept", true, "switchBack", true)));
        } finally { service.editorClosed(chat.id); }
    }
    private static void disconnect(Path root) throws Exception {
        var process = ProcessHandle.of(Long.parseLong(Files.readString(root.resolve("claude-pid")))).orElseThrow();
        check(process.destroyForcibly(), "Fixture process could not exit");
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(10); }
        check(condition.getAsBoolean(), "Connection state did not settle");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private ProviderRecoverySmoke() {}
}
