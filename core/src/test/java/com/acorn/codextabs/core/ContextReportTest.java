package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ContextReportTest {
    private JsonObject message(String role, String text) { return object("type", "message", "role", role, "content", new Object[]{object("type", "input_text", "text", text)}); }
    private JsonObject record(String type, JsonObject payload) { return object("type", type, "payload", payload); }
    private JsonObject read(JsonObject... records) throws IOException {
        var lines = new StringBuilder();
        for (var record : records) { lines.append(GSON.toJson(record)).append('\n'); }
        return ContextReport.rollout(new BufferedReader(new StringReader(lines.toString())), "session.jsonl", false);
    }
    @Test void displayEventsAreNotCountedAsExtraModelMessages() throws Exception {
        var input = message("user", "Read the project rules");
        var report = read(record("response_item", input), record("event_msg", object("type", "item_completed", "item", input)), record("world_state", object("state", input)));
        assertEquals(1, array(report, "blocks").size());
        assertEquals("Read the project rules", text(array(report, "blocks").get(0).getAsJsonObject(), "text"));
    }
    @Test void compactionReplacesEarlierRecordedHistoryAndKeepsOriginalBaseLabel() throws Exception {
        var report = read(record("session_meta", object("base_instructions", object("text", "Initial base instructions"))),
            record("response_item", message("user", "Old content must leave this window")),
            record("compacted", object("replacement_history", new Object[]{message("developer", "Retained project rules"), message("user", "Compact summary")})),
            record("response_item", message("user", "Newest message")));
        assertEquals(4, array(report, "blocks").size());
        assertFalse(GSON.toJson(report).contains("Old content must leave"));
        assertTrue(GSON.toJson(report).contains("Compaction replacement"));
        assertEquals(1, report.get("compactions").getAsInt());
    }
    @Test void missingReplacementHistoryIsExplicitlyIncomplete() throws Exception {
        var report = read(record("response_item", message("user", "Old text")), record("compacted", object("message", "Summary only")));
        assertEquals(1, array(report, "blocks").size());
        assertTrue(GSON.toJson(array(report, "notices")).contains("cannot be reconstructed"));
    }
    @Test void toolResultsKeepTheirCallAndSkipOpaquePayloads() throws Exception {
        var report = read(record("response_item", object("type", "custom_tool_call", "name", "functions.exec", "call_id", "c1", "input", "cat /project/.agents/skills/review/SKILL.md")),
            record("response_item", object("type", "custom_tool_call_output", "call_id", "c1", "output", "Review instructions read from the file")),
            record("response_item", object("type", "reasoning", "summary", new Object[]{object("text", "Visible summary")}, "encrypted_content", "NEVER_EXPORT_OPAQUE_DATA")));
        var block = array(report, "blocks").get(1).getAsJsonObject();
        assertEquals("skills", text(block, "kind"));
        assertTrue(text(block, "call").contains("SKILL.md"));
        assertFalse(GSON.toJson(report).contains("NEVER_EXPORT_OPAQUE_DATA"));
        assertTrue(GSON.toJson(report).contains("non-text or encrypted"));
    }
    @Test void startupClassifiesCatalogAndOmitsInlineImageBytes() {
        var input = new JsonArray();
        input.add(message("user", "# AGENTS.md instructions for /project\nKeep changes focused."));
        input.add(message("developer", "### Available skills\n- review: /skills/review/SKILL.md"));
        input.add(object("type", "message", "role", "user", "content", new Object[]{object("type", "input_image", "image_url", "data:image/png;base64,ENCODED_IMAGE")}));
        var report = ContextReport.startup(input, "/project");
        assertEquals("instructions", text(array(report, "blocks").get(0).getAsJsonObject(), "kind"));
        assertEquals("skills", text(array(report, "blocks").get(1).getAsJsonObject(), "kind"));
        assertFalse(GSON.toJson(report).contains("ENCODED_IMAGE"));
        assertTrue(GSON.toJson(report).contains("not the current chat"));
    }
    @Test void malformedTailDoesNotDiscardEarlierContext() throws Exception {
        var content = GSON.toJson(record("response_item", message("user", "Saved text"))) + "\n{\"type\":";
        var report = ContextReport.rollout(new BufferedReader(new StringReader(content)), "session.jsonl", true);
        assertEquals(1, array(report, "blocks").size());
        assertTrue(GSON.toJson(report).contains("incomplete or unsupported"));
        assertTrue(GSON.toJson(report).contains("64 MB"));
    }
    @Test void largeBlocksAndReportHaveExplicitBounds() throws Exception {
        var records = new JsonObject[30];
        for (int i = 0; i < records.length; i++) { records[i] = record("response_item", message("user", "x".repeat(150_000))); }
        var report = read(records);
        int total = 0;
        for (var entry : array(report, "blocks")) {
            var block = entry.getAsJsonObject();
            assertTrue(text(block, "text").length() <= 100_000);
            assertTrue(flag(block, "truncated"));
            total += text(block, "text").length();
        }
        assertTrue(total <= 2_000_000);
        assertTrue(GSON.toJson(report).contains("display limit"));
    }
}
