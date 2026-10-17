package com.acorn.codextabs;

import com.acorn.codextabs.core.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Check steering through the actual Claude controller without using a model. */
public final class ClaudeSteeringSmoke {
    static void run(String binary) throws Exception {
        var root = Files.createTempDirectory("claude-steering-smoke-");
        var settings = new CodexSettings.State(); settings.claudeBinary = Path.of(binary).toAbsolutePath().toString();
        var chat = new Conversation(UUID.randomUUID().toString(), root.toString()); chat.set("provider", "claude");
        try (var sessions = new ClaudeSessions(() -> settings, () -> "", () -> new SharedGuidance.Source("", "", "", ""), ignored -> {})) {
            sessions.send(chat, options(), input("hold original"));
            sessions.send(chat, options(), input("keep queued"));
            var steer = options(); steer.addProperty("steer", true);
            check(!flag(sessions.send(chat, steer, input("change direction")), "queued"), "Steer must bypass the local queue while Claude is working");
            await(() -> users(root).size() == 2 && hasMessage(chat, "change direction"));
            check(text(users(root).get(1).getAsJsonObject(), "priority").equals("now"), "Steer reaches Claude at priority now");
            check(chat.busy() && chat.get("error").isBlank() && !flag(chat.snapshot(), "claudeQueuePaused"), "Interrupted result must not finish or pause the steered turn");
            check(array(chat.snapshot(), "claudeQueue").size() == 1, "Other messages remain queued");
            var attachment = input("selected queue entry"); attachment.add(object("type", "image", "url", "data:image/png;base64,aGVsbG8="));
            var queued = sessions.send(chat, options(), attachment);
            int controlsBefore = controls(root);
            sessions.steerQueued(chat, text(queued, "id"));
            await(() -> users(root).size() == 3);
            check(controls(root) == controlsBefore, "Steering must keep the active model and permission settings");
            check(array(chat.snapshot(), "claudeQueue").size() == 1 && hasMessage(chat, "selected queue entry"), "Only the selected queue entry is sent");
            check(GSON.toJson(users(root).get(2)).contains("aGVsbG8="), "Steering preserves attached image bytes");
            sessions.steerQueued(chat, text(queued, "id"));
            check(array(chat.snapshot(), "claudeQueue").size() == 1, "A repeated click cannot send another queued entry");
            Files.writeString(root.resolve("release-turn"), "go");
            await(() -> !chat.busy() && users(root).size() == 4 && array(chat.snapshot(), "claudeQueue").isEmpty());
            check(prompt(users(root).get(3)).equals("keep queued"), "Ordinary queued messages run after steering finishes");
            Files.delete(root.resolve("release-turn"));

            sessions.send(chat, options(), input("approval"));
            await(() -> !array(chat.snapshot(), "requests").isEmpty());
            var approval = array(chat.snapshot(), "requests").get(0).getAsJsonObject();
            sessions.send(chat, steer, input("steer while awaiting approval"));
            await(() -> users(root).size() == 6);
            check(text(users(root).get(5).getAsJsonObject(), "priority").equals("later"), "Steering must not cancel an open permission request");
            sessions.answer(chat, object("key", text(approval, "key"), "decision", "accept"));
            await(() -> !chat.busy());

            sessions.send(chat, options(), input("hold idle boundary"));
            sessions.send(chat, options(), input("preserved first"));
            var second = sessions.send(chat, options(), input("send selected after stop"));
            sessions.stop(chat).get(5, TimeUnit.SECONDS); await(() -> !chat.busy());
            sessions.steerQueued(chat, text(second, "id"));
            await(() -> !chat.busy() && users(root).size() == 9);
            check(prompt(users(root).get(7)).equals("send selected after stop") && prompt(users(root).get(8)).equals("preserved first"), "An idle Steer sends the selected entry before the remaining queue");

            sessions.send(chat, options(), input("hold retry"));
            sessions.send(chat, options(), input("first retained"));
            Path image = root.resolve("attachment.png"); Files.write(image, new byte[]{1, 2, 3});
            var imageInput = input("image to retry"); imageInput.add(object("type", "localImage", "path", image.toString()));
            var failing = sessions.send(chat, options(), imageInput);
            var retained = array(chat.snapshot(), "claudeQueue").deepCopy(); Files.delete(image);
            try { sessions.steerQueued(chat, text(failing, "id")); throw new AssertionError("Missing image should fail before submission"); }
            catch (IllegalArgumentException expected) { check(array(chat.snapshot(), "claudeQueue").equals(retained), "Failed steering preserves the full queue and its order"); }
            sessions.stop(chat).get(5, TimeUnit.SECONDS); await(() -> !chat.busy());
            System.out.println("Claude steering checks passed: priority input, queue promotion, attachments, permissions, completion, idle boundary, retry.");
        } finally {
            try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); } }
        }
    }
    private static JsonArray wire(Path root) {
        try {
            var result = new JsonArray();
            for (var line : Files.readAllLines(root.resolve("wire.jsonl"))) { try { result.add(JsonParser.parseString(line)); } catch (JsonParseException ignored) {} }
            return result;
        } catch (java.io.IOException error) { return new JsonArray(); }
    }
    private static List<JsonElement> users(Path root) { return wire(root).asList().stream().filter(value -> text(value.getAsJsonObject(), "type").equals("user")).toList(); }
    private static int controls(Path root) { return (int) wire(root).asList().stream().filter(value -> text(value.getAsJsonObject(), "type").equals("control_request")).count(); }
    private static String prompt(JsonElement user) { return ClaudeHistory.prompt(obj(user.getAsJsonObject(), "message").get("content")); }
    private static boolean hasMessage(Conversation chat, String prompt) { return chat.items().asList().stream().anyMatch(value -> GSON.toJson(value).contains(prompt)); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) { throw new AssertionError("Steering did not reach the expected state"); } Thread.sleep(10); }
    }
    private static JsonObject options() { return object("model", "", "permissions", "ask", "effort", "", "fast", false); }
    private static JsonArray input(String text) { return GSON.toJsonTree(List.of(object("type", "text", "text", text))).getAsJsonArray(); }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
