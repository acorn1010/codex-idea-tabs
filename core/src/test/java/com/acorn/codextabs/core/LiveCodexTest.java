package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

/** Opt-in subscription checks for independent chats and edited history using tiny read-only turns. */
@EnabledIfEnvironmentVariable(named = "CODEX_TEST_BINARY", matches = ".+")
class LiveCodexTest {
    @Test void editedBranchKeepsEarlierContextAndLeavesOriginalHistoryUntouched() throws Exception {
        var events = new LinkedBlockingQueue<JsonObject>();
        var root = Files.createTempDirectory("codex-tabs-edit-live-");
        try (var rpc = new RpcClient(new ProcessBuilder(System.getenv("CODEX_TEST_BINARY"), "app-server").start(), events::offer, ignored -> {})) {
            var created = new java.util.ArrayList<String>();
            try {
                rpc.request("initialize", object("clientInfo", object("name", "codex_tabs_edit_test", "version", "0.1.1"), "capabilities", object("experimentalApi", true))).get(45, TimeUnit.SECONDS);
                rpc.notify("initialized", new JsonObject());
                // History pagination requires persisted threads. Archive these isolated test chats below.
                var options = object("cwd", root.toString(), "sandbox", "read-only", "approvalPolicy", "never", "developerInstructions", "This is an isolated integration check. Never use tools. Follow the user's exact reply format.");
                String source = text(obj(rpc.request("thread/start", options).get(45, TimeUnit.SECONDS), "thread"), "id");
                created.add(source);
                rpc.request("turn/start", object("threadId", source, "input", new Object[]{object("type", "text", "text", "Remember the marker EARLIER_CONTEXT. Reply only READY.")})).get(45, TimeUnit.SECONDS);
                awaitTurn(rpc, events, source);
                rpc.request("turn/start", object("threadId", source, "input", new Object[]{object("type", "text", "text", "The old instruction is OLD_REQUEST. Reply only OLD.")})).get(45, TimeUnit.SECONDS);
                awaitTurn(rpc, events, source);
                var listParams = object("threadId", source, "itemsView", "full", "sortDirection", "asc", "limit", 10);
                var history = array(rpc.request("thread/turns/list", listParams).get(45, TimeUnit.SECONDS), "data");
                assertEquals(2, history.size());
                var second = history.get(1).getAsJsonObject();
                String itemId = array(second, "items").asList().stream().map(value -> value.getAsJsonObject()).filter(item -> text(item, "type").equals("userMessage")).map(item -> text(item, "id")).findFirst().orElseThrow();
                var edit = MessageEdit.prepare(history, itemId, "Reply only with the marker I asked you to remember earlier.");
                var forkParams = options.deepCopy();
                forkParams.addProperty("threadId", source);
                forkParams.addProperty("lastTurnId", edit.lastTurnId());
                String branch = text(obj(rpc.request("thread/fork", forkParams).get(45, TimeUnit.SECONDS), "thread"), "id");
                created.add(branch);
                assertNotEquals(source, branch);
                rpc.request("turn/start", object("threadId", branch, "input", edit.input())).get(45, TimeUnit.SECONDS);
                assertEquals("EARLIER_CONTEXT", awaitTurn(rpc, events, branch));
                var original = array(rpc.request("thread/turns/list", listParams).get(45, TimeUnit.SECONDS), "data");
                assertEquals(2, original.size());
                assertTrue(GSON.toJson(original).contains("OLD_REQUEST"));
                listParams.addProperty("threadId", branch);
                var revised = array(rpc.request("thread/turns/list", listParams).get(45, TimeUnit.SECONDS), "data");
                assertEquals(2, revised.size());
                assertFalse(GSON.toJson(revised).contains("OLD_REQUEST"));
            } finally {
                for (String threadId : created) { rpc.request("thread/archive", object("threadId", threadId)).get(15, TimeUnit.SECONDS); }
            }
        } finally { Files.deleteIfExists(root); }
    }

    private static String awaitTurn(RpcClient rpc, BlockingQueue<JsonObject> events, String threadId) throws Exception {
        String reply = "";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            var event = events.poll(10, TimeUnit.SECONDS);
            if (event == null) { continue; }
            if (event.has("id")) { rpc.reject(event.get("id"), "Tools are disabled for this integration check."); }
            var params = obj(event, "params");
            if (!text(params, "threadId").equals(threadId)) { continue; }
            if (text(event, "method").equals("item/completed") && text(obj(params, "item"), "type").equals("agentMessage")) { reply = text(obj(params, "item"), "text"); }
            if (text(event, "method").equals("turn/completed")) {
                assertEquals("completed", text(obj(params, "turn"), "status"), GSON.toJson(obj(params, "turn")));
                return reply;
            }
        }
        throw new AssertionError("The edited conversation did not finish in time");
    }

    @Test void authenticatesReadsImagesAndStreamsTwoIndependentChats() throws Exception {
        var events = new LinkedBlockingQueue<JsonObject>();
        var root = Files.createTempDirectory("codex-tabs-live-");
        var image = root.resolve("tiny.png");
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        Files.write(image, png);
        try (var rpc = new RpcClient(new ProcessBuilder(System.getenv("CODEX_TEST_BINARY"), "app-server").start(), events::offer, ignored -> {})) {
            rpc.request("initialize", object("clientInfo", object("name", "codex_tabs_test", "version", "0.1.0"), "capabilities", object("experimentalApi", true))).get(45, TimeUnit.SECONDS);
            rpc.notify("initialized", new JsonObject());
            var account = rpc.request("account/read", object("refreshToken", false)).get(15, TimeUnit.SECONDS);
            assertEquals("chatgpt", text(obj(account, "account"), "type"));
            var file = rpc.request("fs/readFile", object("path", image.toString())).get(15, TimeUnit.SECONDS);
            assertArrayEquals(png, java.util.Base64.getDecoder().decode(text(file, "dataBase64")));
            var params = object("cwd", root.toString(), "ephemeral", true, "sandbox", "read-only", "approvalPolicy", "never", "developerInstructions", "This is an isolated UI integration check. Do not use tools. Respond with only the exact marker requested by the user.");
            var a = rpc.request("thread/start", params);
            var b = rpc.request("thread/start", params);
            String first = text(obj(a.get(45, TimeUnit.SECONDS), "thread"), "id");
            String second = text(obj(b.get(45, TimeUnit.SECONDS), "thread"), "id");
            assertNotEquals(first, second);
            rpc.request("turn/start", object("threadId", first, "input", new Object[]{object("type", "text", "text", "Reply with TAB_ONE_OK.")})).get(45, TimeUnit.SECONDS);
            rpc.request("turn/start", object("threadId", second, "input", new Object[]{object("type", "text", "text", "Reply with TAB_TWO_OK.")})).get(45, TimeUnit.SECONDS);
            var responses = new java.util.HashMap<String, String>();
            var completed = new java.util.HashSet<String>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (completed.size() < 2 && System.nanoTime() < deadline) {
                var event = events.poll(10, TimeUnit.SECONDS);
                if (event == null) { continue; }
                var data = obj(event, "params");
                String threadId = text(data, "threadId");
                if (text(event, "method").equals("item/completed") && text(obj(data, "item"), "type").equals("agentMessage")) { responses.put(threadId, text(obj(data, "item"), "text")); }
                if (text(event, "method").equals("turn/completed")) {
                    assertNotEquals("failed", text(obj(data, "turn"), "status"), GSON.toJson(obj(data, "turn")));
                    completed.add(threadId);
                }
                if (event.has("id")) { rpc.reject(event.get("id"), "Tools are disabled for this integration check."); }
            }
            assertEquals(2, completed.size());
            assertEquals("TAB_ONE_OK", responses.get(first));
            assertEquals("TAB_TWO_OK", responses.get(second));
        } finally { Files.deleteIfExists(image); Files.deleteIfExists(root); }
    }
}
