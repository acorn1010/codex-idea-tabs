package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

/** Opt-in checks use isolated folders, no tools or project settings, and tiny subscription turns. */
@EnabledIfEnvironmentVariable(named = "CLAUDE_TEST_BINARY", matches = ".+")
class LiveClaudeTest {
    @Test void streamsResumesAndForksIntoAnotherCheckout() throws Exception {
        var root = Files.createTempDirectory("claude-tabs-live-");
        var other = Files.createTempDirectory("claude-tabs-fork-");
        String session = UUID.randomUUID().toString(), fork = UUID.randomUUID().toString();
        try {
            assertTrue(turn(root, session, "", false, "Remember the marker CLAUDE_TABS_CONTEXT. Reply only READY.").contains("READY"));
            assertTrue(turn(root, session, session, false, "Reply only with the marker I asked you to remember.").contains("CLAUDE_TABS_CONTEXT"));
            assertTrue(turn(other, fork, session, true, "Reply only with the marker I asked you to remember.").contains("CLAUDE_TABS_CONTEXT"));
        } finally { Files.deleteIfExists(root); Files.deleteIfExists(other); }
    }
    private static String turn(Path cwd, String session, String resume, boolean fork, String prompt) throws Exception {
        var frames = new LinkedBlockingQueue<JsonObject>();
        var builder = ClaudeClient.process(System.getenv("CLAUDE_TEST_BINARY"), cwd.toString(), "", session, resume, fork);
        builder.command().addAll(List.of("--setting-sources", "", "--strict-mcp-config", "--tools", "", "--model", "haiku"));
        try (var client = new ClaudeClient(builder.start(), frames::offer, reason -> frames.offer(object("type", "disconnected", "reason", reason)))) {
            var init = client.control(object("subtype", "initialize")).get(45, TimeUnit.SECONDS);
            assertFalse(array(init, "models").isEmpty());
            client.control(object("subtype", "set_permission_mode", "mode", "default")).get(15, TimeUnit.SECONDS);
            client.control(object("subtype", "set_model", "model", "haiku")).get(15, TimeUnit.SECONDS);
            var chat = new Conversation(session, cwd.toString()); var protocol = new ClaudeProtocol(chat);
            var input = GSON.toJsonTree(List.of(object("type", "text", "text", prompt))).getAsJsonArray();
            protocol.start("turn", input);
            client.write(object("type", "user", "session_id", session, "message", object("role", "user", "content", input)));
            boolean streamed = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline) {
                var frame = frames.poll(5, TimeUnit.SECONDS); if (frame == null) { continue; }
                assertNotEquals("disconnected", text(frame, "type"), GSON.toJson(frame));
                if (text(frame, "type").equals("stream_event")) { streamed = true; }
                if (text(frame, "type").equals("control_request")) { client.reject(text(frame, "request_id"), "Tools disabled for this check."); }
                protocol.accept(frame);
                if (text(frame, "type").equals("result")) {
                    assertFalse(flag(frame, "is_error"), GSON.toJson(frame)); assertTrue(streamed); assertFalse(chat.busy()); assertEquals(session, chat.get("threadId"));
                    return chat.items().asList().stream().map(JsonElement::getAsJsonObject).filter(item -> text(item, "type").equals("agentMessage")).map(item -> text(item, "text")).reduce("", String::concat);
                }
            }
            throw new AssertionError("Claude did not complete the test turn in time.");
        }
    }
}
