package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ClaudeFeaturesTest {
    @TempDir Path temporary;
    private static JsonArray values(Object... entries) { return GSON.toJsonTree(entries).getAsJsonArray(); }
    @Test void readsOnlyTheCurrentMainBranchAndIgnoresIncompleteLines() throws Exception {
        var directory = Files.createDirectories(temporary.resolve("projects/project"));
        String id = UUID.randomUUID().toString();
        var frames = List.of(
            object("uuid", "u1", "type", "user", "cwd", "/project", "message", object("content", "First question")),
            object("uuid", "a1", "parentUuid", "u1", "type", "assistant", "message", object("id", "m1", "content", values(object("type", "text", "text", "First answer")))),
            object("uuid", "stale", "parentUuid", "a1", "type", "user", "message", object("content", "Old branch")),
            object("uuid", "new", "parentUuid", "a1", "type", "user", "message", object("content", values(object("type", "text", "text", "New branch")))),
            object("uuid", "side", "parentUuid", "new", "isSidechain", true, "type", "assistant", "message", object("content", "Subagent")),
            object("type", "custom-title", "customTitle", "Named session"));
        Files.writeString(directory.resolve(id + ".jsonl"), frames.stream().map(GSON::toJson).reduce("", (all, line) -> all + line + "\n") + "{incomplete");
        var history = new ClaudeHistory(temporary);
        var loaded = history.read(id);
        assertEquals(List.of("u1", "a1", "new"), loaded.asList().stream().map(value -> text(value.getAsJsonObject(), "uuid")).toList());
        assertEquals("a1", text(ClaudeHistory.editPoint(loaded, "new"), "anchor"));
        assertEquals("", text(ClaudeHistory.editPoint(loaded, "u1"), "anchor"));
        assertThrows(IllegalArgumentException.class, () -> ClaudeHistory.editPoint(loaded, "stale"));
        assertEquals(1, history.list(Set.of("/project"), "NAMED").size());
        assertTrue(history.list(Set.of("/elsewhere"), "").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> history.read("../secret"));
        var chat = new Conversation("c", "/project"); var protocol = new ClaudeProtocol(chat);
        loaded.forEach(value -> protocol.accept(value.getAsJsonObject()));
        assertEquals(List.of("userMessage", "agentMessage", "userMessage"), chat.items().asList().stream().map(value -> text(value.getAsJsonObject(), "type")).toList());
    }
    @Test void keepsImagesAndDoesNotDisplayInjectedGuidance() {
        var frame = object("message", object("content", values(object("type", "text", "text", "<codex_tabs_shared_guidance>rules</codex_tabs_shared_guidance>"), object("type", "text", "text", "User request"), object("type", "image", "source", object("type", "base64", "media_type", "image/png", "data", "AAAA")))));
        assertEquals("User request", ClaudeHistory.prompt(obj(frame, "message").get("content")));
        var input = ClaudeHistory.input(frame); assertEquals(2, input.size());
        assertEquals("data:image/png;base64,AAAA", text(input.get(1).getAsJsonObject(), "url"));
    }
    @Test void queueSurvivesRestartAndCancellationPreservesOtherPrompts() {
        var chat = new Conversation("id", "/project"); var queue = new ClaudeQueue(chat);
        var first = queue.add(object("text", "first", "effort", "high"), values(object("type", "text", "text", "context")));
        var second = queue.add(object("text", "second"), new JsonArray());
        var restored = Conversation.restore(chat.snapshot()); var after = new ClaudeQueue(restored);
        after.remove(text(first, "id"));
        assertEquals(text(second, "id"), text(after.take(), "id")); assertNull(after.take()); assertFalse(after.hasNext());
        assertTrue(queue.hasNext());
    }
    @Test void queueControllersShareAtomicUpdates() throws Exception {
        var chat = new Conversation("id", "/project");
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 50; i++) { futures.add(workers.submit(() -> new ClaudeQueue(chat).add(object("text", "prompt"), new JsonArray()))); }
            for (var future : futures) { future.get(); }
        }
        assertEquals(50, array(chat.snapshot(), "claudeQueue").size());
    }
    @Test void persistentPermissionsUseOnlySuggestedAllowRulesAndLocalScope() {
        var request = object("tool_name", "Bash", "input", object("command", "npm test"), "permission_suggestions", values(
            object("type", "addRules", "behavior", "allow", "destination", "userSettings", "rules", values(object("toolName", "Bash", "ruleContent", "npm test"))),
            object("type", "setMode", "mode", "bypassPermissions"),
            object("type", "addDirectories", "directories", values("/"))));
        var always = ClaudeProtocol.approvalResponse(request, object("decision", "always"));
        var updates = array(always, "updatedPermissions"); assertEquals(1, updates.size());
        assertEquals("localSettings", text(updates.get(0).getAsJsonObject(), "destination"));
        assertEquals("session", text(array(ClaudeProtocol.approvalResponse(request, object("decision", "session")), "updatedPermissions").get(0).getAsJsonObject(), "destination"));
        assertFalse(ClaudeProtocol.approvalResponse(request, object("decision", "accept")).has("updatedPermissions"));
        assertFalse(ClaudeProtocol.approvalResponse(request, object("decision", "decline")).has("updatedPermissions"));
        assertEquals("Bash(npm test)", ClaudeProtocol.permissionDescription(request));
    }
    @Test void multiSelectAnswersRetainEveryChoice() {
        var question = object("tool_name", "AskUserQuestion", "input", object("questions", values(object("question", "Which?", "multiSelect", true))));
        var response = ClaudeProtocol.approvalResponse(question, object("answers", object("0", object("answers", values("First", "Second", "Custom")))));
        assertEquals(values("First", "Second", "Custom"), obj(obj(response, "updatedInput"), "answers").get("Which?"));
    }
    @Test void mapsAdvertisedModelsAndLiveUsageWithoutGuessingCapabilities() {
        var models = ClaudeProtocol.models(object("models", values(object("value", "default", "supportsEffort", true, "supportedEffortLevels", values("low", "high"), "supportsFastMode", true, "supportsAutoMode", true), object("value", "old"))));
        var model = models.get(0).getAsJsonObject(); assertTrue(flag(model, "isDefault")); assertTrue(flag(model, "supportsAutoMode")); assertEquals(2, array(model, "supportedReasoningEfforts").size());
        assertFalse(flag(models.get(1).getAsJsonObject(), "supportsAutoMode"));
        var chat = new Conversation("id", "/project"); var protocol = new ClaudeProtocol(chat); var input = values(object("type", "text", "text", "Hello"));
        protocol.start("uuid", input); protocol.accept(object("type", "user", "uuid", "uuid", "message", object("content", input)));
        assertEquals(1, chat.items().size());
        protocol.accept(object("type", "result", "usage", object("input_tokens", 100), "total_cost_usd", 0.1));
        assertEquals("100", text(obj(chat.snapshot(), "claudeUsage"), "input_tokens")); assertFalse(chat.busy());
        protocol.accept(object("type", "system", "subtype", "commands_changed", "commands", values(object("name", "skill"))));
        assertEquals(1, array(chat.snapshot(), "claudeCommands").size());
    }
    @Test void cutoffStaysSeparateFromShellArguments() {
        String id = UUID.randomUUID().toString(), resume = UUID.randomUUID().toString(), anchor = UUID.randomUUID().toString();
        var process = ClaudeClient.process("claude", "/project", "Ubuntu", id, resume, true, anchor);
        assertTrue(process.command().contains("--resume-session-at=" + anchor));
    }
}
