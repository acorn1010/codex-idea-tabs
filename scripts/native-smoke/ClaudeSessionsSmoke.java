package com.acorn.codextabs;

import com.acorn.codextabs.core.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Exercise the actual session controller with a tool-free subprocess fixture. */
public final class ClaudeSessionsSmoke {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("claude-session-smoke-");
        var settings = new CodexSettings.State(); settings.claudeBinary = Path.of(args[0]).toAbsolutePath().toString();
        var chat = new Conversation(UUID.randomUUID().toString(), root.toString()); chat.set("provider", "claude");
        try (var sessions = new ClaudeSessions(() -> settings, () -> "", () -> new SharedGuidance.Source("", "", "", ""), ignored -> {})) {
            sessions.load(chat);
            check(flag(chat.snapshot(), "claudeSettingsSupported"), "Capabilities");
            sessions.send(chat, options(), input("hold first"));
            var nextOptions = options(); nextOptions.addProperty("effort", "high"); nextOptions.addProperty("permissions", "auto"); nextOptions.addProperty("fast", true);
            check(flag(sessions.send(chat, nextOptions, input("second")), "queued"), "Follow-up queued");
            await(() -> countUsers(root) == 1);
            check(users(root).size() == 1, "Queued input did not reach CLI early");
            Files.writeString(root.resolve("release-turn"), "go");
            await(() -> !chat.busy() && array(chat.snapshot(), "claudeQueue").isEmpty() && countUsers(root) == 2);
            check(ClaudeHistory.prompt(obj(users(root).get(1).getAsJsonObject(), "message").get("content")).equals("second"), "Queue order");
            check(wire(root).asList().stream().map(JsonElement::getAsJsonObject).anyMatch(value -> text(obj(value, "request"), "subtype").equals("apply_flag_settings") && flag(obj(obj(value, "request"), "settings"), "fastMode")), "Queued settings applied");
            Files.delete(root.resolve("release-turn"));
            sessions.send(chat, options(), input("hold stopped")); sessions.send(chat, options(), input("after stop"));
            sessions.stop(chat).get(5, TimeUnit.SECONDS);
            await(() -> !chat.busy());
            check(countUsers(root) == 3 && array(chat.snapshot(), "claudeQueue").size() == 1, "Stop preserves queue");
            sessions.resumeQueued(chat); await(() -> !chat.busy() && countUsers(root) == 4);
            sessions.send(chat, options(), input("hold reconnect")); sessions.send(chat, options(), input("after reconnect"));
            sessions.release(chat.id); sessions.load(chat);
            check(flag(chat.snapshot(), "claudeQueuePaused") && array(chat.snapshot(), "claudeQueue").size() == 1 && countUsers(root) == 5, "Reconnect preserves paused queue");
            sessions.resumeQueued(chat); await(() -> !chat.busy() && countUsers(root) == 6);
            sessions.send(chat, options(), input("approval")); await(() -> !array(chat.snapshot(), "requests").isEmpty());
            var request = array(chat.snapshot(), "requests").get(0).getAsJsonObject();
            sessions.answer(chat, object("key", text(request, "key"), "decision", "always")); await(() -> !chat.busy());
            check(settings.claudeApprovals.isEmpty(), "Native rules do not create exact-match fallbacks");
            check(wire(root).asList().stream().map(JsonElement::getAsJsonObject).anyMatch(value -> GSON.toJson(value).contains("localSettings")), "Native rule persisted through CLI");
            sessions.send(chat, options(), input("question")); await(() -> !array(chat.snapshot(), "requests").isEmpty());
            request = array(chat.snapshot(), "requests").get(0).getAsJsonObject();
            sessions.answer(chat, object("key", text(request, "key"), "answers", object("0", object("answers", List.of("A", "B"))))); await(() -> !chat.busy());
            check(text(obj(sessions.status(chat), "context"), "totalTokens").equals("512"), "Context status");
            check(array(sessions.mcp(chat), "data").size() == 1, "MCP status");
            check(array(array(sessions.skills(chat), "data").get(0).getAsJsonObject(), "skills").size() == 1, "Commands");
            var edited = new Conversation(UUID.randomUUID().toString(), root.toString()); edited.set("provider", "claude");
            String anchor = UUID.randomUUID().toString(); edited.set("claudeResumeId", UUID.randomUUID().toString()); edited.set("claudeResumeAt", anchor);
            sessions.load(edited); sessions.send(edited, options(), input("edited")); await(() -> !edited.busy());
            sessions.release(edited.id); sessions.load(edited);
            var starts = Files.readAllLines(root.resolve("starts.jsonl"));
            check(starts.get(starts.size() - 2).contains("--resume-session-at=" + anchor), "First edited fork uses cutoff");
            check(!starts.getLast().contains("--resume-session-at="), "Reopening edited chat preserves later turns");
            var original = new Conversation(UUID.randomUUID().toString(), root.toString());
            original.event(object("method", "item/completed", "params", object("item", object("id", "old", "type", "userMessage", "text", "Earlier requirement"))));
            var switched = ProviderHandoff.prepare(original.snapshot(), original.items(), "claude");
            sessions.load(switched); sessions.send(switched, object("model", "opus", "permissions", "ask"), input("Continue here")); await(() -> !switched.busy());
            String firstSession = switched.get("threadId");
            var sent = users(root).get(users(root).size() - 1).getAsJsonObject();
            check(GSON.toJson(sent).contains("Earlier requirement") && GSON.toJson(sent).contains("Continue here"), "Prior context reaches Claude with new user input");
            check(!GSON.toJson(switched.items()).contains("Codex Tabs conversation context"), "Context stays out of visible messages");
            sessions.release(switched.id); sessions.load(switched);
            sessions.send(switched, options(), input("After reconnect")); await(() -> !switched.busy());
            sent = users(root).get(users(root).size() - 1).getAsJsonObject();
            check(!GSON.toJson(sent).contains("Earlier requirement"), "Reconnect does not resend context");
            sessions.release(switched.id);
            var back = ProviderHandoff.prepare(switched.snapshot(), switched.items(), "codex");
            var again = ProviderHandoff.prepare(back.snapshot(), back.items(), "claude");
            sessions.load(again); sessions.send(again, options(), input("Switch back again")); await(() -> !again.busy());
            check(!firstSession.equals(again.get("threadId")), "Switching back starts a fresh Claude session");
            check(wire(root).asList().stream().map(JsonElement::getAsJsonObject).anyMatch(value -> text(obj(value, "request"), "subtype").equals("set_model") && text(obj(value, "request"), "model").equals("opus")), "Selected Opus model reaches the CLI");
            System.out.println("Claude session checks passed: handoff, repeated switching, context, Opus model,  queue order, settings, stop, reconnect, permissions, questions, context, MCP, commands.");
        } finally {
            try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); } }
        }
    }
    private static JsonObject options() { return object("model", "", "permissions", "ask", "effort", "", "fast", false); }
    private static JsonArray input(String text) { return GSON.toJsonTree(List.of(object("type", "text", "text", text))).getAsJsonArray(); }
    private static JsonArray wire(Path root) throws Exception {
        var result = new JsonArray();
        for (var line : Files.readAllLines(root.resolve("wire.jsonl"))) { try { result.add(JsonParser.parseString(line)); } catch (JsonParseException ignored) {} }
        return result;
    }
    private static JsonArray users(Path root) throws Exception {
        var result = new JsonArray(); for (var frame : wire(root)) { if (text(frame.getAsJsonObject(), "type").equals("user")) { result.add(frame); } } return result;
    }
    private static int countUsers(Path root) { try { return users(root).size(); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) { throw new AssertionError("Session did not reach the expected state"); } Thread.sleep(10); }
    }
}
