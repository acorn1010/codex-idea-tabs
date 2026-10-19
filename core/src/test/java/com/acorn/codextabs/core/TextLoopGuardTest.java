package com.acorn.codextabs.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class TextLoopGuardTest {
    private static final String MESSAGE = "item/agentMessage/delta";
    private static final String REASONING = "item/reasoning/textDelta";
    private static String sample() throws IOException {
        try (var stream = TextLoopGuardTest.class.getResourceAsStream("/loop-guard/repeated-planning.txt")) {
            assertNotNull(stream); return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static Conversation chat(String provider) {
        var chat = new Conversation("text-loop", "/fixture");
        chat.set("modelProvider", provider);
        start(chat, "turn"); return chat;
    }
    private static void start(Conversation chat, String turn) {
        chat.event(object("method", "turn/started", "params", object("turn", object("id", turn))));
    }
    private static void delta(Conversation chat, String method, String turn, String id, String content) {
        chat.event(object("method", method, "params", object("turnId", turn, "itemId", id, "delta", content)));
    }
    @Test void eachPastedLoopGetsTwoDifferentRecoveryMessagesBeforeStopping() throws IOException {
        for (String fixture : new String[]{"repeated-planning.txt", "repeated-send.txt"}) {
            String text;
            try (var stream = getClass().getResourceAsStream("/loop-guard/" + fixture)) {
                assertNotNull(stream); text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String provider : new String[]{LocalModel.PROVIDER, "codex_tabs_routeway"}) {
                for (String method : new String[]{MESSAGE, REASONING, "item/reasoning/summaryTextDelta"}) {
                    for (int chunk : new int[]{1, 113, text.length()}) {
                        var chat = chat(provider); var messages = new java.util.HashSet<String>();
                        for (int attempt = 0; attempt < 3; attempt++) {
                            String turn = "recovery-" + attempt; start(chat, turn);
                            var action = object();
                            for (int offset = 0; offset < text.length(); offset += chunk) {
                                delta(chat, method, turn, "answer-" + attempt, text.substring(offset, Math.min(offset + chunk, text.length())));
                                action = chat.takeLocalLoopAction();
                                if (!action.isEmpty()) { break; }
                            }
                            assertFalse(action.isEmpty(), fixture + " " + method + " " + chunk);
                            assertEquals(attempt == 2, flag(action, "stop"));
                            if (attempt < 2) {
                                assertTrue(flag(action, "restart"), "Text recovery must interrupt the stuck generation");
                                assertTrue(messages.add(text(action, "message")), "Recovery instructions must differ");
                            }
                            delta(chat, method, turn, "answer-" + attempt, text);
                            assertTrue(chat.takeLocalLoopAction().isEmpty(), "Ignore the old stream after requesting recovery");
                        }
                        assertTrue(chat.get("error").contains("repeated text"));
                    }
                }
            }
        }
    }
    @Test void normalProseShortRepeatsAndChangedPassagesDoNotTripTheGuard() throws IOException {
        var chat = chat(LocalModel.PROVIDER);
        var prose = new StringBuilder();
        for (int i = 0; i < 5000; i++) { prose.append("different-word-").append(i).append(' '); }
        delta(chat, REASONING, "turn", "reasoning", prose.toString());
        delta(chat, MESSAGE, "turn", "answer", "Let's patch. Let's patch. Let's patch. ");
        String passage = sample().substring(0, sample().indexOf("Good. Test multi.") + "Good. Test multi.".length());
        delta(chat, MESSAGE, "turn", "answer", passage + "\n" + passage + "\n");
        assertTrue(chat.takeLocalLoopAction().isEmpty());
        assertTrue(obj(chat.snapshot(), "localLoopGuard").isEmpty());
    }
    @Test void channelsDoNotDoubleCountAndToolResultsBreakTextRepetition() throws IOException {
        var chat = chat(LocalModel.PROVIDER);
        String passage = sample().substring(0, sample().indexOf("Good. Test multi.") + "Good. Test multi.".length()) + "\n";
        delta(chat, REASONING, "turn", "reasoning", passage.repeat(2));
        delta(chat, "item/reasoning/summaryTextDelta", "turn", "reasoning", passage.repeat(2));
        delta(chat, MESSAGE, "turn", "answer", passage.repeat(2));
        assertTrue(chat.takeLocalLoopAction().isEmpty());
        chat.event(object("method", "item/completed", "params", object("turnId", "turn", "item", object("id", "tool", "type", "commandExecution", "command", "apply_patch", "status", "completed", "exitCode", 0, "aggregatedOutput", "patch applied"))));
        delta(chat, REASONING, "turn", "next", passage.repeat(2));
        assertTrue(chat.takeLocalLoopAction().isEmpty());
    }
    @Test void ignoresCloudOldTurnsAndCompletedItemReplays() throws IOException {
        var cloud = chat("openai");
        delta(cloud, MESSAGE, "turn", "answer", sample());
        assertTrue(cloud.takeLocalLoopAction().isEmpty());
        var chat = chat(LocalModel.PROVIDER);
        delta(chat, MESSAGE, "old-turn", "old", sample());
        chat.event(object("method", "item/completed", "params", object("turnId", "turn", "item", object("id", "history", "type", "agentMessage", "text", sample()))));
        delta(chat, "item/commandExecution/outputDelta", "turn", "command", sample());
        assertTrue(chat.takeLocalLoopAction().isEmpty());
    }
    @Test void stopSurvivesRestoreAndAutomaticRetryUntilTheUserResetsIt() throws IOException {
        var chat = chat("codex_tabs_routeway");
        for (int attempt = 0; attempt < 3; attempt++) {
            start(chat, "turn-" + attempt);
            delta(chat, MESSAGE, "turn-" + attempt, "answer-" + attempt, sample());
            assertEquals(attempt == 2, flag(chat.takeLocalLoopAction(), "stop"));
        }
        var restored = Conversation.restore(chat.snapshot());
        start(restored, "retry");
        assertTrue(flag(restored.takeLocalLoopAction(), "stop"));
        assertTrue(restored.get("error").contains("repeated text"));
        restored.resetLocalLoopGuard(); start(restored, "manual");
        assertTrue(restored.takeLocalLoopAction().isEmpty());
        assertEquals("", restored.get("error"));
    }
    @Test void lateTextAfterStopCannotStartAnotherRecovery() throws IOException {
        var chat = chat("codex_tabs_routeway");
        chat.suspendLocalLoopGuard();
        delta(chat, MESSAGE, "turn", "late", sample());
        assertTrue(chat.takeLocalLoopAction().isEmpty());
        chat.resetLocalLoopGuard();
        delta(chat, MESSAGE, "turn", "manual", sample());
        assertTrue(flag(chat.takeLocalLoopAction(), "restart"));
    }
    @Test void usefulToolResultsGiveLaterTextLoopsAFreshRecoveryBudget() throws IOException {
        for (var item : new com.google.gson.JsonObject[]{
            object("id", "progress", "type", "commandExecution", "command", "read new source", "status", "completed", "exitCode", 0, "aggregatedOutput", "new evidence"),
            object("id", "progress", "type", "mcpToolCall", "server", "fixture", "tool", "read_file", "arguments", object("path", "new"), "status", "completed", "result", object("text", "new evidence")),
            object("id", "progress", "type", "fileChange", "status", "completed", "changes", new Object[]{object("path", "source", "kind", object("type", "update"), "diff", "+fixed")}),
        }) {
            var chat = afterTwoRecoveries();
            chat.event(object("method", "item/completed", "params", object("turnId", "recovered", "item", item)));
            assertFalse(obj(chat.snapshot(), "localLoopGuard").has("recoveryAttempts"), item.toString());
            delta(chat, MESSAGE, "recovered", "later-loop", sample());
            var action = chat.takeLocalLoopAction();
            assertFalse(flag(action, "stop")); assertTrue(text(action, "message").startsWith("Loop guard recovery 1/2"));
        }
    }
    @Test void failuresPollsAndOldResultsDoNotRenewTheRecoveryBudget() throws IOException {
        for (var item : new com.google.gson.JsonObject[]{
            object("id", "failed", "type", "commandExecution", "command", "bad command", "status", "completed", "exitCode", 1, "aggregatedOutput", "failure"),
            object("id", "poll", "type", "mcpToolCall", "server", "fixture", "tool", "get_status", "status", "completed", "result", object("text", "running")),
            object("id", "failed-mcp", "type", "mcpToolCall", "server", "fixture", "tool", "read_file", "status", "completed", "result", object("isError", true)),
            object("id", "failed-edit", "type", "fileChange", "status", "failed", "changes", new Object[]{object("path", "source", "diff", "+failed")}),
        }) {
            var chat = afterTwoRecoveries();
            chat.event(object("method", "item/completed", "params", object("turnId", "recovered", "item", item)));
            delta(chat, MESSAGE, "recovered", "still-looping", sample());
            assertTrue(flag(chat.takeLocalLoopAction(), "stop"), item.toString());
        }
        var chat = afterTwoRecoveries();
        chat.event(object("method", "item/completed", "params", object("turnId", "old-turn", "item", object("id", "late", "type", "commandExecution", "command", "read", "status", "completed", "exitCode", 0))));
        delta(chat, MESSAGE, "recovered", "still-looping", sample());
        assertTrue(flag(chat.takeLocalLoopAction(), "stop"));
    }
    @Test void anOrdinaryCompletedReplyRenewsRecoveryButInterruptsDoNot() throws IOException {
        for (String status : new String[]{"completed", "interrupted", "failed"}) {
            var chat = afterTwoRecoveries();
            chat.event(object("method", "item/completed", "params", object("turnId", "recovered", "item", object("id", "final", "type", "agentMessage", "text", "The parser now returns the decoded packet."))));
            chat.event(object("method", "turn/completed", "params", object("turn", object("id", "recovered", "status", status))));
            start(chat, "later"); delta(chat, MESSAGE, "later", "later-loop", sample());
            assertEquals(!status.equals("completed"), flag(chat.takeLocalLoopAction(), "stop"), status);
        }
    }
    @Test void aLoopingTurnCannotResetItsBudgetByFinishingBeforeTheInterrupt() throws IOException {
        var chat = afterTwoRecoveries();
        // A warning in this turn disqualifies its completion even if the model supplies a final reply.
        chat.resetLocalLoopGuard(); start(chat, "racing");
        delta(chat, MESSAGE, "racing", "loop", sample()); chat.takeLocalLoopAction();
        chat.event(object("method", "item/completed", "params", object("turnId", "racing", "item", object("id", "final", "type", "agentMessage", "text", "Finished."))));
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "racing", "status", "completed"))));
        assertEquals(1, obj(chat.snapshot(), "localLoopGuard").get("recoveryAttempts").getAsInt());
    }
    private static Conversation afterTwoRecoveries() throws IOException {
        var chat = chat("codex_tabs_routeway");
        for (int attempt = 0; attempt < 2; attempt++) {
            start(chat, "attempt-" + attempt); delta(chat, MESSAGE, "attempt-" + attempt, "loop-" + attempt, sample());
            assertFalse(flag(chat.takeLocalLoopAction(), "stop"));
        }
        start(chat, "recovered"); return chat;
    }
    @Test void newTurnsAndDisconnectsDiscardIncompleteTextComparisons() throws IOException {
        var chat = chat(LocalModel.PROVIDER);
        String passage = sample().substring(0, sample().indexOf("Good. Test multi.") + "Good. Test multi.".length()) + "\n";
        delta(chat, MESSAGE, "turn", "answer", passage.repeat(2));
        chat.disconnected(); start(chat, "resumed");
        delta(chat, MESSAGE, "resumed", "answer-2", passage.repeat(2));
        assertTrue(chat.takeLocalLoopAction().isEmpty());
        start(chat, "next");
        delta(chat, MESSAGE, "next", "answer-3", passage.repeat(2));
        assertTrue(chat.takeLocalLoopAction().isEmpty());
    }
}
