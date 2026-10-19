package com.acorn.codextabs.core;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.function.LongSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Pair reported output counts with observed response time, excluding tool and approval waits. */
final class GenerationSpeed {
    private static final double NANOS_PER_SECOND = 1_000_000_000d;
    private static final double NANOS_PER_MILLISECOND = 1_000_000d;
    private final LongSupplier clock;
    private boolean running;
    private boolean responseStream;
    private long started;
    private long elapsed;

    GenerationSpeed(LongSupplier clock) { this.clock = clock; }
    void reset() { running = false; responseStream = false; elapsed = 0; }
    private void output() {
        if (!running) { started = clock.getAsLong(); running = true; }
    }
    private void pause() {
        if (running) { elapsed += Math.max(0, clock.getAsLong() - started); running = false; }
    }
    // Null keeps the last sample. An empty object clears it when a new turn starts.
    JsonObject event(String method, JsonObject params, String activeTurn) {
        if (method.equals("turn/started")) { reset(); return object(); }
        if (method.equals("turn/completed") || method.equals("thread/closed")) { reset(); return null; }
        if (activeTurn.isBlank() || !activeTurn.equals(text(params, "turnId"))) { return null; }
        if (method.equals("model/responseStarted")) { reset(); responseStream = true; output(); }
        if (Set.of("item/agentMessage/delta", "item/reasoning/summaryTextDelta", "item/reasoning/textDelta").contains(method)) { output(); }
        if (method.equals("item/started")) {
            String type = text(obj(params, "item"), "type");
            if (Set.of("agentMessage", "reasoning").contains(type)) { output(); }
            // Claude announces a tool while still generating its arguments. Its response-end event stops the clock.
            else if (!responseStream && !type.equals("userMessage")) { pause(); }
        }
        if (method.endsWith("/requestApproval") || Set.of("claude/toolApproval", "item/tool/requestUserInput", "mcpServer/elicitation/request").contains(method)) { pause(); }
        if (method.equals("thread/tokenUsage/updated")) { return sample(obj(obj(params, "tokenUsage"), "last")); }
        if (method.equals("model/responseCompleted")) { return sample(params); }
        return null;
    }
    private JsonObject sample(JsonObject usage) {
        pause();
        long duration = elapsed; reset();
        var value = usage.get("outputTokens");
        if (duration <= 0 || value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) { return null; }
        double tokens = value.getAsDouble();
        if (!Double.isFinite(tokens) || tokens <= 0 || tokens != Math.floor(tokens)) { return null; }
        double rate = tokens / (duration / NANOS_PER_SECOND);
        if (!Double.isFinite(rate)) { return null; }
        return object("outputTokens", tokens, "durationMs", duration / NANOS_PER_MILLISECOND, "tokensPerSecond", rate);
    }
}
