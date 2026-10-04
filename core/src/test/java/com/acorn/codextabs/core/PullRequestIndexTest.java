package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class PullRequestIndexTest {
    @Test void matchesExactNumbersRepositoriesAndHosts() {
        var index = new PullRequestIndex();
        index.add("Created [PR](https://GitHub.com/Acme/Widget/pull/123/files#diff-a). Also acme/other#123 and PR #456.");
        for (String query : List.of("123", "#123", "PR 123", "pr:123", "ACME/WIDGET#123", "https://github.com/acme/widget/pull/123?x=1")) {
            assertFalse(index.match(PullRequestIndex.query(query)).isEmpty(), query);
        }
        for (String query : List.of("#12", "#1234", "other/widget#123", "https://git.example/acme/widget/pull/123")) {
            assertTrue(index.match(PullRequestIndex.query(query)).isEmpty(), query);
        }
        assertFalse(index.match(PullRequestIndex.query("#456")).isEmpty());
        assertFalse(PullRequestIndex.query("fix schema lock").isPullRequest());
    }
    @Test void avoidsIssueNumbersImagesAndReasoningButIncludesToolResults() {
        var index = new PullRequestIndex();
        index.add("Issue #12, https://github.com/acme/repo/issues/13 and build 14.");
        index.addItem(object("type", "userMessage", "content", new Object[]{object("type", "image", "image_url", "https://github.com/acme/repo/pull/15")}));
        index.addItem(object("type", "reasoning", "text", "PR #16"));
        for (int number = 12; number <= 16; number++) { assertTrue(index.match(PullRequestIndex.query("#" + number)).isEmpty()); }
        index.addItem(object("type", "mcpToolCall", "result", object("content", new Object[]{object("type", "text", "text", "https://git.example/acme/repo/pull/17")})));
        index.addItem(object("type", "commandExecution", "aggregatedOutput", "https://github.com/acme/repo/pull/18\n"));
        assertFalse(index.match(PullRequestIndex.query("#17")).isEmpty());
        assertFalse(index.match(PullRequestIndex.query("#18")).isEmpty());
    }
    @Test void olderMessagesRemainSearchableAfterPreviewChangesAndArchive() {
        for (String provider : List.of("codex", "claude")) {
            var chat = new Conversation(provider, "/repo"); chat.set("provider", provider);
            complete(chat, "pr", "Created https://github.com/acme/repo/pull/123");
            complete(chat, "newer", "Done with the follow-up"); chat.archive(true);
            assertEquals("Done with the follow-up", chat.get("preview"));
            var found = chat.searchSummary(PullRequestIndex.query("#123"));
            assertNotNull(found); assertTrue(flag(found, "archived"));
            assertEquals("github.com/acme/repo#123", text(found, "matchedPullRequest"));
            assertNotNull(Conversation.restore(chat.snapshot()).searchSummary(PullRequestIndex.query("#123")));
        }
    }
    @Test void scansCompletedMessagesRatherThanEachStreamingUpdate() {
        var chat = new Conversation("chat", "/repo");
        chat.event(object("method", "item/agentMessage/delta", "params", object("itemId", "pr", "delta", "https://github.com/acme/repo/pull/123")));
        assertNull(chat.searchSummary(PullRequestIndex.query("#123")));
        complete(chat, "pr", "https://github.com/acme/repo/pull/123");
        assertNotNull(chat.searchSummary(PullRequestIndex.query("#123")));
        assertFalse(chat.summary().has("pullRequestIndex"), "Do not send the index with every sidebar refresh");
        assertFalse(chat.changes(chat.revision()).has("pullRequestIndex"));
    }
    @Test void indexesImportedHistoryAndDropsReplacedClaudeHistory() {
        var chat = new Conversation("chat", "/repo");
        chat.hydrate(object("id", "thread", "turns", new Object[]{object("items", new Object[]{message("old", "PR #321")})}), chat.revision());
        assertNotNull(chat.searchSummary(PullRequestIndex.query("#321")));
        var page = new JsonArray(); page.add(message("older", "acme/repo#322")); chat.historyPage(page, "");
        assertNotNull(chat.searchSummary(PullRequestIndex.query("#322")));
        chat.replaceClaudeHistory(new JsonArray());
        chat.set("preview", "");
        assertNull(chat.searchSummary(PullRequestIndex.query("#321")));
        assertNull(chat.searchSummary(PullRequestIndex.query("#322")));
    }
    @Test void backfillsOldCacheOnceAndReusesPersistedIndex(@TempDir Path root) throws Exception {
        var cache = root.resolve("chats.json"); var chat = new Conversation("chat", "/repo");
        complete(chat, "pr", "https://github.com/acme/repo/pull/123");
        new ConversationStore(cache).save(List.of(chat));
        Path saved;
        try (var files = Files.list(root.resolve("chats"))) { saved = files.filter(path -> !path.getFileName().toString().equals("index.json")).findFirst().orElseThrow(); }
        var old = JsonParser.parseString(Files.readString(saved)).getAsJsonObject(); old.remove("pullRequestIndex"); Files.writeString(saved, GSON.toJson(old));
        var store = new ConversationStore(cache); var restored = store.load(root.resolve("missing"));
        assertNotNull(restored.getFirst().searchSummary(PullRequestIndex.query("#123"))); store.save(restored);
        var indexed = JsonParser.parseString(Files.readString(saved)).getAsJsonObject();
        assertEquals(1, array(obj(indexed, "pullRequestIndex"), "references").size());
        Files.setLastModifiedTime(saved, FileTime.fromMillis(1000));
        var reopened = new ConversationStore(cache); reopened.save(reopened.load(root.resolve("missing")));
        assertEquals(FileTime.fromMillis(1000), Files.getLastModifiedTime(saved), "Restart must not rebuild or rewrite the index");
        // Stored references are sufficient even without the body. Search never needs transcript content.
        indexed.add("items", new JsonArray()); indexed.addProperty("preview", "");
        assertNotNull(Conversation.restore(indexed).searchSummary(PullRequestIndex.query("#123")));
    }
    @Test void handoffIndexesEarlierHistoryFetchedAfterTheSavedSnapshot() {
        var source = new Conversation("source", "/repo");
        complete(source, "recent", "PR #100");
        var history = source.items(); history.add(message("older", "https://github.com/acme/repo/pull/99"));
        var switched = ProviderHandoff.prepare(source.snapshot(), history, "claude");
        assertNotNull(switched.searchSummary(PullRequestIndex.query("#99")));
        assertNotNull(switched.searchSummary(PullRequestIndex.query("#100")));
        switched.set("title", "Review PR #101"); switched.replaceClaudeHistory(new JsonArray());
        assertNotNull(switched.searchSummary(PullRequestIndex.query("#101")));
    }
    private static JsonObject message(String id, String text) { return object("id", id, "type", "agentMessage", "text", text); }
    private static void complete(Conversation chat, String id, String text) { chat.event(object("method", "item/completed", "params", object("item", message(id, text)))); }
}
