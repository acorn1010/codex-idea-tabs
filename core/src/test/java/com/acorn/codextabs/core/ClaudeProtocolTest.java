package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ClaudeProtocolTest {
    @Test void streamingAndCompleteMessagesShareIdentityAndKeepToolResults() {
        var chat = new Conversation(UUID.randomUUID().toString(), "/project"); chat.set("provider", "claude");
        var protocol = new ClaudeProtocol(chat); protocol.start("turn", GSON.toJsonTree(List.of(object("type", "text", "text", "Question"))).getAsJsonArray());
        stream(protocol, object("type", "message_start", "message", object("id", "message")));
        stream(protocol, object("type", "content_block_start", "index", 0, "content_block", object("type", "text", "text", "")));
        stream(protocol, object("type", "content_block_delta", "index", 0, "delta", object("type", "text_delta", "text", "Hello")));
        assertEquals("Hello", text(chat.items().get(1).getAsJsonObject(), "text")); assertTrue(chat.busy());
        var blocks = List.of(object("type", "text", "text", "Hello there"), object("type", "tool_use", "id", "tool", "name", "Bash", "input", object("command", "pwd")));
        protocol.accept(object("type", "assistant", "message", object("id", "message", "content", blocks)));
        protocol.accept(object("type", "user", "message", object("content", List.of(object("type", "tool_result", "tool_use_id", "tool", "content", List.of(object("type", "text", "text", "/project")))))));
        protocol.accept(object("type", "assistant", "message", object("id", "message", "content", blocks)));
        assertEquals(3, chat.items().size());
        assertEquals("Hello there", text(chat.items().get(1).getAsJsonObject(), "text"));
        assertEquals("/project", text(chat.items().get(2).getAsJsonObject(), "aggregatedOutput"));
        assertEquals("completed", text(chat.items().get(2).getAsJsonObject(), "status"));
        protocol.accept(object("type", "result", "session_id", "session", "is_error", false));
        assertFalse(chat.busy()); assertEquals("session", chat.get("threadId"));
        assertEquals("claude", text(Conversation.restore(chat.snapshot()).summary(), "provider"));
    }
    @Test void splitCompletedFramesUseTheSameBlockIndexesAsStreamingAndHistory() {
        var live = new Conversation("live", "/project"); var stream = new ClaudeProtocol(live);
        var restored = new Conversation("restored", "/project"); var history = new ClaudeProtocol(restored);
        var contents = List.of(object("type", "thinking", "thinking", "Checking the lock scope."),
            object("type", "text", "text", "Checking the shared lock."),
            object("type", "tool_use", "id", "tool", "name", "Bash", "input", object("command", "pwd")),
            object("type", "text", "text", "Checking the shared lock."));
        stream(stream, object("type", "message_start", "message", object("id", "message")));
        var frames = new ArrayList<JsonObject>();
        for (int index = 0; index < contents.size(); index++) {
            var content = contents.get(index);
            stream(stream, object("type", "content_block_start", "index", index, "content_block", content));
            stream(stream, object("type", "content_block_stop", "index", index));
            var frame = object("type", "assistant", "uuid", "frame-" + index, "message", object("id", "message", "content", List.of(content)));
            frames.add(frame); stream.accept(frame); history.accept(frame);
            assertEquals(restored.items(), live.items(), "Completed block must update its streamed item at index " + index);
            assertEquals(index + 1, live.items().size());
        }
        // Replayed frames keep their IDs, while equal text in separate blocks stays separate.
        frames.forEach(stream::accept); frames.forEach(history::accept);
        assertEquals(restored.items(), live.items()); assertEquals(4, live.items().size());
        assertEquals("reasoning", text(live.items().get(0).getAsJsonObject(), "type"));
        assertEquals("message:1", text(live.items().get(1).getAsJsonObject(), "id"));
        assertEquals("message:3", text(live.items().get(3).getAsJsonObject(), "id"));
        var result = object("type", "user", "message", object("content", List.of(object("type", "tool_result", "tool_use_id", "tool", "content", "/project"))));
        stream.accept(result); history.accept(result); stream.accept(frames.get(2));
        assertEquals(restored.items(), live.items());
        assertEquals("completed", text(live.items().get(2).getAsJsonObject(), "status"));
    }
    @Test void completedChunksKeepIndependentOffsetsForEachMessage() {
        var chat = new Conversation("id", "/project"); var protocol = new ClaudeProtocol(chat);
        for (String message : List.of("first", "second")) {
            stream(protocol, object("type", "message_start", "message", object("id", message)));
            stream(protocol, object("type", "content_block_start", "index", 0, "content_block", object("type", "thinking", "thinking", "Plan")));
            stream(protocol, object("type", "content_block_start", "index", 1, "content_block", object("type", "text", "text", "Same text")));
        }
        // Completed chunks can arrive after a different message has started streaming.
        for (String message : List.of("first", "second")) {
            protocol.accept(object("type", "assistant", "uuid", message + "-thinking", "message", object("id", message, "content", List.of(object("type", "thinking", "thinking", "Plan")))));
            protocol.accept(object("type", "assistant", "uuid", message + "-text", "message", object("id", message, "content", List.of(object("type", "text", "text", "Same text"), object("type", "text", "text", "A separate block")))));
        }
        assertEquals(6, chat.items().size());
        assertEquals(2, chat.items().asList().stream().map(JsonElement::getAsJsonObject).filter(item -> text(item, "type").equals("reasoning")).count());
        assertEquals(2, chat.items().asList().stream().map(JsonElement::getAsJsonObject).filter(item -> text(item, "text").equals("Same text")).count());
    }
    @Test void partialToolInputAndNestedAgentTextDoNotCorruptTheMainReply() {
        var chat = new Conversation("id", "/project"); var protocol = new ClaudeProtocol(chat); protocol.start("turn", new JsonArray());
        stream(protocol, object("type", "message_start", "message", object("id", "m")));
        stream(protocol, object("type", "content_block_start", "index", 0, "content_block", object("type", "tool_use", "id", "tool", "name", "Read", "input", new JsonObject())));
        stream(protocol, object("type", "content_block_delta", "index", 0, "delta", object("type", "input_json_delta", "partial_json", "{\"file_path\":")));
        stream(protocol, object("type", "content_block_delta", "index", 0, "delta", object("type", "input_json_delta", "partial_json", "\"README.md\"}")));
        stream(protocol, object("type", "content_block_stop", "index", 0));
        protocol.accept(object("type", "assistant", "parent_tool_use_id", "parent", "message", object("id", "nested", "content", List.of(object("type", "text", "text", "Nested answer")))));
        var tool = chat.items().get(1).getAsJsonObject();
        assertEquals("README.md", text(obj(tool, "arguments"), "file_path")); assertEquals(2, chat.items().size());
        protocol.accept(object("type", "user", "message", object("content", List.of(object("type", "tool_result", "tool_use_id", "tool", "content", "Denied", "is_error", true)))));
        assertEquals("failed", text(chat.items().get(1).getAsJsonObject(), "status"));
    }
    @Test void failedTurnClearsBlockingApprovalsAndReportsItsError() {
        var chat = new Conversation("id", "/project"); var protocol = new ClaudeProtocol(chat); protocol.start("turn", new JsonArray());
        var choices = List.of(object("decision", "accept", "label", "Allow once"), object("decision", "always", "label", "Always allow"));
        chat.event(object("id", "approval", "method", "claude/toolApproval", "params", object("turnId", "turn", "approvalChoices", choices)));
        assertEquals(2, array(array(chat.snapshot(), "requests").get(0).getAsJsonObject(), "approvalChoices").size());
        protocol.accept(object("type", "result", "is_error", true, "errors", List.of("Please sign in.")));
        assertEquals("Please sign in.", chat.get("error")); assertFalse(chat.busy()); assertTrue(array(chat.snapshot(), "requests").isEmpty());
    }
    @Test void exactApprovalMatchingIncludesCheckoutAndCanonicalNestedInput() {
        var first = object("tool_name", "Bash", "input", object("command", "git status", "options", object("a", 1, "b", 2)));
        var reordered = object("tool_name", "Bash", "input", object("options", object("b", 2, "a", 1), "command", "git status"));
        assertEquals(ClaudeProtocol.approvalKey("/one", first), ClaudeProtocol.approvalKey("/one", reordered));
        assertNotEquals(ClaudeProtocol.approvalKey("/one", first), ClaudeProtocol.approvalKey("/two", first));
        obj(reordered, "input").addProperty("command", "git reset --hard");
        assertNotEquals(ClaudeProtocol.approvalKey("/one", first), ClaudeProtocol.approvalKey("/one", reordered));
        assertFalse(ClaudeProtocol.approvalKey("/one", first).contains("git"));
    }
    @Test void nativeAndWslCommandsKeepArgumentsOutOfShellText() {
        String session = UUID.randomUUID().toString(), original = UUID.randomUUID().toString();
        var nativeProcess = ClaudeClient.process("/a space/claude", "/tmp", "", session, original, true);
        assertEquals("/a space/claude", nativeProcess.command().getFirst()); assertEquals("/tmp", nativeProcess.directory().toString());
        assertTrue(nativeProcess.command().contains("--resume=" + original)); assertTrue(nativeProcess.command().contains("--session-id=" + session)); assertTrue(nativeProcess.command().contains("--fork-session"));
        assertFalse(nativeProcess.command().contains("--dangerously-skip-permissions"));
        var wsl = ClaudeClient.process("/bin/claude $(whoami)", "/a folder/$(id)", "Ubuntu", session, "", false).command();
        assertEquals("cd -- \"$1\" && shift && exec \"$@\"", wsl.get(6));
        assertEquals("/a folder/$(id)", wsl.get(8)); assertEquals("/bin/claude $(whoami)", wsl.get(9));
        assertThrows(IllegalArgumentException.class, () -> ClaudeClient.process("claude", "/tmp", "", session, "--bad", false));
    }
    @Test void oldChatsRemainCodexAndClaudeGuidanceKeepsOriginalSources() {
        assertEquals("codex", Conversation.restore(object("id", "old", "cwd", "/project")).get("provider"));
        var source = new SharedGuidance.Source("/root", "/root/AGENTS.md", "Use shared rules.", "/root/.agents/skills");
        assertTrue(source.claudeContext().contains("Use shared rules.")); assertTrue(source.claudeContext().contains("/root/.agents/skills"));
        assertTrue(source.claudeContext().contains("CLAUDE.md")); assertFalse(source.claudeContext().contains("skills are registered"));
    }
    @Test void approvalsDefaultToDenyAndQuestionsPreserveToolInput() {
        var request = object("tool_name", "Bash", "input", object("command", "pwd"));
        assertEquals("deny", text(ClaudeProtocol.approvalResponse(request, new JsonObject()), "behavior"));
        assertEquals("pwd", text(obj(ClaudeProtocol.approvalResponse(request, object("decision", "always")), "updatedInput"), "command"));
        assertThrows(IllegalArgumentException.class, () -> ClaudeProtocol.approvalResponse(request, object("decision", "invented")));
        var question = object("tool_name", "AskUserQuestion", "input", object("questions", List.of(object("question", "Which layout?", "header", "Layout", "options", List.of(object("label", "Compact"))))));
        assertThrows(IllegalArgumentException.class, () -> ClaudeProtocol.approvalResponse(question, new JsonObject()));
        var answer = ClaudeProtocol.approvalResponse(question, object("answers", object("0", object("answers", List.of("Compact")))));
        assertEquals("allow", text(answer, "behavior"));
        assertEquals("Compact", text(obj(obj(answer, "updatedInput"), "answers"), "Which layout?"));
        assertEquals(1, array(obj(answer, "updatedInput"), "questions").size());
    }
    private static void stream(ClaudeProtocol protocol, JsonObject event) { protocol.accept(object("type", "stream_event", "event", event)); }
}
