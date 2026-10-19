package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class GenerationSpeedTest {
    private final AtomicLong clock = new AtomicLong();
    private final Conversation chat = new Conversation("chat", "/project", clock::get);
    private void time(long milliseconds) { clock.set(milliseconds * 1_000_000); }
    private void start(String turn) { chat.event(object("method", "turn/started", "params", object("turn", object("id", turn)))); }
    private void output(String turn) { chat.event(object("method", "item/agentMessage/delta", "params", object("turnId", turn, "itemId", "answer", "delta", "Hello"))); }
    private void usage(String turn, Object tokens) { chat.event(object("method", "thread/tokenUsage/updated", "params", object("turnId", turn, "tokenUsage", object("last", object("outputTokens", tokens))))); }
    private void tool(String method, String turn) { chat.event(object("method", method, "params", object("turnId", turn, "item", object("id", "tool", "type", "commandExecution", "status", "inProgress")))); }

    @Test void measuresOutputAfterPrefillAndBeforeToolExecution() {
        start("turn"); time(10_000); output("turn");
        time(12_000); tool("item/started", "turn");
        time(32_000); tool("item/completed", "turn"); usage("turn", 100);
        var rate = obj(chat.snapshot(), "generationSpeed");
        assertEquals(100, rate.get("outputTokens").getAsLong());
        assertEquals(2000, rate.get("durationMs").getAsLong());
        assertEquals(50, rate.get("tokensPerSecond").getAsDouble());
        time(60_000); usage("turn", 100);
        assertEquals(rate, obj(chat.snapshot(), "generationSpeed"), "Duplicate usage must not make a slower sample");
    }
    @Test void eachResponseStartsANewSampleAndApprovalsPauseTiming() {
        start("turn"); output("turn"); time(1000); usage("turn", 40);
        time(20_000); output("turn"); time(22_000);
        chat.event(object("method", "item/commandExecution/requestApproval", "params", object("turnId", "turn", "itemId", "approval")));
        time(62_000); usage("turn", 120);
        assertEquals(60, obj(chat.snapshot(), "generationSpeed").get("tokensPerSecond").getAsDouble());
        start("next"); assertFalse(chat.snapshot().has("generationSpeed"));
    }
    @Test void cachedAndLateEventsCannotCreateARate() {
        usage("old", 100); assertFalse(chat.snapshot().has("generationSpeed"));
        start("turn"); output("old"); time(1000); usage("old", 100);
        assertFalse(chat.snapshot().has("generationSpeed"));
        output("turn"); time(2000); usage("turn", 100);
        var rate = obj(chat.snapshot(), "generationSpeed");
        assertEquals(100, rate.get("tokensPerSecond").getAsDouble());
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "turn", "status", "completed"))));
        time(3000); output("turn"); usage("turn", 500);
        assertEquals(rate, obj(chat.snapshot(), "generationSpeed"));
        var restored = Conversation.restore(chat.snapshot());
        assertEquals(rate, obj(restored.snapshot(), "generationSpeed"));
        var switched = ProviderHandoff.prepare(restored.snapshot(), restored.items(), "claude");
        assertFalse(switched.snapshot().has("generationSpeed"));
    }
    @Test void missingOrInvalidCountsAndZeroDurationStayUnavailable() {
        for (Object count : new Object[]{null, -1, 0, "unknown"}) {
            start("turn"); output("turn"); time(clock.get() / 1_000_000 + 1000); usage("turn", count);
            assertFalse(chat.snapshot().has("generationSpeed"));
        }
        start("turn"); output("turn"); usage("turn", 50);
        assertFalse(chat.snapshot().has("generationSpeed"));
    }
    @Test void disconnectDiscardsAnIncompleteSample() {
        start("turn"); output("turn"); time(2000); chat.disconnected();
        time(20_000); usage("turn", 100);
        assertFalse(chat.snapshot().has("generationSpeed"));
    }
    @Test void claudeCountsToolArgumentGenerationButNotTheToolRun() {
        chat.set("provider", "claude"); var protocol = new ClaudeProtocol(chat);
        protocol.start("turn", new com.google.gson.JsonArray());
        protocol.accept(object("type", "stream_event", "event", object("type", "message_start", "message", object("id", "answer"))));
        time(500); protocol.accept(object("type", "stream_event", "event", object("type", "content_block_start", "index", 0,
            "content_block", object("type", "tool_use", "id", "tool", "name", "Bash", "input", object()))));
        time(1500); protocol.accept(object("type", "stream_event", "event", object("type", "content_block_delta", "index", 0,
            "delta", object("type", "input_json_delta", "partial_json", "{\"command\":\"sleep 20\"}"))));
        time(2000); protocol.accept(object("type", "stream_event", "event", object("type", "message_delta", "usage", object("output_tokens", 100))));
        assertEquals(50, obj(chat.snapshot(), "generationSpeed").get("tokensPerSecond").getAsDouble());
        time(22000); protocol.accept(object("type", "stream_event", "event", object("type", "message_start", "message", object("id", "next"))));
        time(23000); protocol.accept(object("type", "stream_event", "event", object("type", "message_delta", "usage", object("output_tokens", 40))));
        assertEquals(40, obj(chat.snapshot(), "generationSpeed").get("tokensPerSecond").getAsDouble());
    }
    @Test void claudeReportsOutputAtTheEndOfEachStreamedResponse() {
        chat.set("provider", "claude"); var protocol = new ClaudeProtocol(chat);
        protocol.start("turn", new com.google.gson.JsonArray());
        time(15_000); protocol.accept(object("type", "stream_event", "event", object("type", "message_start", "message", object("id", "answer"))));
        time(17_000); protocol.accept(object("type", "stream_event", "event", object("type", "message_delta", "usage", object("output_tokens", 80))));
        assertEquals(40, obj(chat.snapshot(), "generationSpeed").get("tokensPerSecond").getAsDouble());
        var restored = new Conversation("saved", "/project", clock::get); var history = new ClaudeProtocol(restored, false);
        history.accept(object("type", "stream_event", "event", object("type", "message_start", "message", object("id", "history"))));
        time(19_000); history.accept(object("type", "stream_event", "event", object("type", "message_delta", "usage", object("output_tokens", 100))));
        assertFalse(restored.snapshot().has("generationSpeed"));
    }
}
