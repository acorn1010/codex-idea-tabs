package com.acorn.codextabs.core;

import com.google.gson.*;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** View-independent conversation state keeps hidden tabs current without keeping their browser alive. */
public final class Conversation {
    public final String id;
    private final JsonObject state;
    private final LinkedHashMap<String, JsonObject> items = new LinkedHashMap<>();
    private final LinkedHashMap<String, JsonObject> requests = new LinkedHashMap<>();
    private final Map<String, Long> itemRevisions = new HashMap<>();
    private long revision;
    private long historyRevision;
    private long runtimeRevision;

    public Conversation(String id, String cwd) {
        this.id = id;
        state = object("id", id, "cwd", cwd, "title", "New chat", "threadId", "", "turnId", "", "draft", "", "working", false, "unread", false, "pinned", false, "updatedAt", System.currentTimeMillis());
    }
    public static Conversation restore(JsonObject saved) {
        var chat = new Conversation(text(saved, "id"), text(saved, "cwd"));
        for (var entry : saved.entrySet()) { chat.state.add(entry.getKey(), entry.getValue().deepCopy()); }
        chat.state.remove("items"); chat.state.remove("requests");
        for (var item : array(saved, "items")) { chat.put(item.getAsJsonObject()); }
        // Cached items predate every live event in this process.
        chat.itemRevisions.clear();
        for (var request : array(saved, "requests")) {
            var value = request.getAsJsonObject();
            if (!value.has("rpcId")) { chat.requests.put(text(value, "key"), value.deepCopy()); }
        }
        chat.state.addProperty("working", false);
        chat.state.addProperty("turnId", "");
        return chat;
    }
    public synchronized String get(String key) { return text(state, key); }
    public synchronized void set(String key, Object value) {
        state.add(key, GSON.toJsonTree(value));
        if (key.equals("error")) { state.addProperty("errorKind", "operation"); }
        revision++;
    }
    /** A failed resume is recoverable independently of a failed user turn. */
    public synchronized void loadFailed(String message) {
        state.addProperty("error", message); state.addProperty("errorKind", "connection"); revision++;
    }
    public synchronized void loaded() { clearRecoverableError(); revision++; }
    public synchronized boolean busy() { return flag(state, "working") || requests.values().stream().anyMatch(request -> request.has("rpcId")); }
    private void clearRecoverableError() {
        if (Set.of("connection", "retry").contains(get("errorKind"))) { state.addProperty("error", ""); state.addProperty("errorKind", ""); }
    }
    public synchronized long revision() { return revision; }
    public synchronized JsonArray items() {
        var result = new JsonArray();
        items.values().forEach(item -> result.add(item.deepCopy()));
        return result;
    }
    public synchronized String status() {
        if (!requests.isEmpty()) { return "attention"; }
        if (flag(state, "working")) { return "working"; }
        if (!text(state, "error").isEmpty()) { return "error"; }
        return flag(state, "unread") ? "complete" : "idle";
    }
    public synchronized boolean archived() { return flag(state, "archived") || flag(state, "hidden"); }
    public synchronized boolean canArchive() { return !flag(state, "working") && requests.isEmpty(); }
    public synchronized void archive(boolean archived) {
        if (archived && !canArchive()) { throw new IllegalStateException("Finish the active work and answer pending requests before archiving."); }
        state.addProperty("archived", archived);
        state.addProperty("hidden", false);
        revision++;
    }
    public synchronized JsonObject snapshot() {
        var snapshot = state.deepCopy();
        snapshot.add("items", items());
        var pending = new JsonArray();
        requests.values().forEach(request -> pending.add(request.deepCopy()));
        snapshot.add("requests", pending);
        snapshot.addProperty("status", status());
        snapshot.addProperty("revision", revision);
        snapshot.addProperty("archived", archived());
        return snapshot;
    }
    public synchronized JsonObject changes(long since) {
        if (since < 0 || since < historyRevision || since > revision) { return snapshot(); }
        var result = state.deepCopy();
        var changedItems = new JsonArray();
        items.forEach((id, item) -> { if (itemRevisions.getOrDefault(id, 0L) > since) { changedItems.add(item.deepCopy()); } });
        result.add("items", changedItems);
        var pending = new JsonArray();
        requests.values().forEach(value -> pending.add(value.deepCopy()));
        result.add("requests", pending);
        result.addProperty("partial", true); result.addProperty("revision", revision); result.addProperty("status", status());
        result.addProperty("archived", archived());
        return result;
    }
    public synchronized JsonObject summary() {
        var result = object("id", id, "title", get("title"), "threadId", get("threadId"), "cwd", get("cwd"),
            "pinned", flag(state, "pinned"), "updatedAt", state.get("updatedAt"), "archived", archived(),
            "hasDraft", !get("draft").isBlank() || !array(state, "draftAttachments").isEmpty(), "preview", get("preview"));
        result.addProperty("status", status());
        return result;
    }
    public synchronized void hydrate(JsonObject thread, long startedRevision) {
        boolean firstImport = get("threadId").isBlank();
        state.addProperty("threadId", text(thread, "id", get("threadId")));
        String title = text(thread, "name", text(thread, "preview"));
        if (!title.isBlank() && !flag(state, "renamed")) { state.addProperty("title", title.lines().findFirst().orElse(title)); }
        if (thread.has("updatedAt")) {
            long updatedAt = thread.get("updatedAt").getAsLong() * 1000;
            state.addProperty("updatedAt", firstImport ? updatedAt : Math.max(state.get("updatedAt").getAsLong(), updatedAt));
        }
        if (!text(thread, "preview").isBlank()) { state.addProperty("preview", text(thread, "preview").substring(0, Math.min(120, text(thread, "preview").length())).replaceAll("\\s+", " ")); }
        var history = new LinkedHashMap<String, JsonObject>();
        for (var turn : array(thread, "turns")) {
            for (var item : array(turn.getAsJsonObject(), "items")) { history.put(text(item.getAsJsonObject(), "id"), item.getAsJsonObject().deepCopy()); }
        }
        if (startedRevision == revision) { items.putAll(history); }
        else {
            history.putAll(items);
            items.clear(); items.putAll(history);
        }
        revision++;
        if (!history.isEmpty()) { historyRevision = revision; }
        items.values().forEach(this::questions);
    }
    /** Apply live resume state without letting a delayed reply replace newer turn events. */
    public synchronized void resumed(JsonObject thread, long startedRevision) {
        hydrate(thread, startedRevision);
        if (runtimeRevision <= startedRevision && thread.has("status")) { threadStatus(obj(thread, "status")); }
    }
    private void threadStatus(JsonObject status) {
        String type = text(status, "type");
        if (type.equals("active")) { state.addProperty("working", true); }
        else if (type.equals("idle") || type.equals("notLoaded") || type.equals("systemError")) {
            state.addProperty("working", false); state.addProperty("turnId", "");
        }
    }
    public synchronized void historyPage(JsonArray entries, String cursor) {
        historyPage(entries, cursor, revision, true);
    }
    public synchronized void historyPage(JsonArray entries, String cursor, long startedRevision) {
        historyPage(entries, cursor, startedRevision, true);
    }
    /** Refresh from the newest page, or prepend an older page. Live events received during the fetch always win. */
    public synchronized void historyPage(JsonArray entries, String cursor, long startedRevision, boolean latest) {
        var history = new LinkedHashMap<String, JsonObject>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i).getAsJsonObject();
            var item = entry.has("item") ? obj(entry, "item") : entry;
            history.put(text(item, "id"), item.deepCopy());
        }
        items.forEach((id, item) -> {
            if ((!latest && !history.containsKey(id)) || itemRevisions.getOrDefault(id, 0L) > startedRevision) { history.put(id, item); }
        });
        items.clear(); items.putAll(history);
        items.values().forEach(this::questions);
        for (var item : items.reversed().values()) {
            if (text(item, "type").equals("agentMessage") && !text(item, "text").isBlank()) { preview(text(item, "text")); break; }
        }
        state.addProperty("historyCursor", cursor);
        historyRevision = ++revision;
    }
    public synchronized JsonObject request(String key) {
        var value = requests.get(key);
        if (value == null) { throw new IllegalArgumentException("This request has already been answered."); }
        return value.deepCopy();
    }
    public synchronized void resolve(String key) {
        var request = requests.remove(key);
        if (request != null && !request.has("rpcId")) {
            var answered = array(state, "answeredQuestions").deepCopy();
            if (!answered.contains(new JsonPrimitive(key))) { answered.add(key); }
            state.add("answeredQuestions", answered);
        }
        revision++;
    }
    public synchronized void disconnected() {
        requests.values().removeIf(request -> request.has("rpcId"));
        state.addProperty("working", false);
        state.addProperty("turnId", "");
        revision++;
    }
    public synchronized void event(JsonObject event) {
        String method = text(event, "method");
        var params = obj(event, "params");
        boolean liveActivity = false;
        switch (method) {
            case "turn/started" -> {
                state.addProperty("turnId", text(obj(params, "turn"), "id"));
                state.addProperty("working", true);
                state.addProperty("unread", false);
                state.addProperty("error", "");
            }
            case "turn/completed" -> {
                var turn = obj(params, "turn");
                if (!get("turnId").isBlank() && !get("turnId").equals(text(turn, "id"))) { return; }
                state.addProperty("working", false);
                state.addProperty("completedTurnId", text(turn, "id"));
                state.addProperty("turnId", "");
                state.addProperty("unread", true);
                if (turn.has("error") && turn.get("error").isJsonObject()) { state.addProperty("error", text(obj(turn, "error"), "message")); state.addProperty("errorKind", "turn"); }
                else if (text(turn, "status").equals("completed") && !get("errorKind").equals("operation")) { state.addProperty("error", ""); state.addProperty("errorKind", ""); }
                requests.values().removeIf(request -> request.has("rpcId") && text(request, "turnId").equals(text(turn, "id")));
            }
            case "item/started", "item/completed" -> {
                clearRecoverableError();
                if (method.equals("item/started")) { liveActivity = observeActivity(params); }
                var item = obj(params, "item");
                if (text(item, "type").equals("reasoning") && items.containsKey(text(item, "id")) && !item.has("text") && array(item, "summary").isEmpty() && array(item, "content").isEmpty()) {
                    String streamed = text(items.get(text(item, "id")), "text");
                    if (!streamed.isBlank()) { item = item.deepCopy(); item.addProperty("text", streamed); }
                }
                put(item);
                if (method.equals("item/completed") && text(item, "type").equals("agentMessage")) { preview(text(item, "text")); }
            }
            case "item/agentMessage/delta", "item/reasoning/summaryTextDelta", "item/reasoning/textDelta", "item/commandExecution/outputDelta" -> {
                clearRecoverableError();
                liveActivity = observeActivity(params);
                String id = text(params, "itemId");
                String type = method.contains("agentMessage") ? "agentMessage" : method.contains("reasoning") ? "reasoning" : "commandExecution";
                String field = type.equals("commandExecution") ? "aggregatedOutput" : "text";
                var item = items.computeIfAbsent(id, ignored -> object("id", id, "type", type));
                String content = text(item, field) + text(params, "delta");
                // Keep runaway terminal output bounded. The backend retains the complete rollout.
                if (type.equals("commandExecution") && content.length() > 100_000) { content = content.substring(content.length() - 100_000); }
                item.addProperty(field, content);
                itemRevisions.put(id, revision + 1);
            }
            case "item/tool/requestUserInput", "item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval", "mcpServer/elicitation/request" -> {
                var request = params.deepCopy();
                String key = event.has("id") ? event.get("id").toString() : text(params, "itemId");
                request.addProperty("key", key);
                request.addProperty("method", method);
                if (event.has("id")) { request.add("rpcId", event.get("id")); }
                requests.put(key, request);
            }
            case "serverRequest/resolved" -> requests.remove(params.get("requestId") == null ? "" : params.get("requestId").toString());
            case "item/tool/requestUserInput/answered" -> requests.values().removeIf(request -> text(request, "itemId").equals(text(params, "itemId")));
            case "thread/name/updated" -> state.addProperty("title", text(params, "threadName", get("title")));
            case "thread/archived" -> { state.addProperty("archived", true); state.addProperty("hidden", false); }
            case "thread/unarchived" -> { state.addProperty("archived", false); state.addProperty("hidden", false); }
            case "thread/status/changed" -> threadStatus(obj(params, "status"));
            case "thread/closed" -> disconnected();
            case "thread/tokenUsage/updated" -> state.add("tokenUsage", obj(params, "tokenUsage"));
            case "turn/plan/updated" -> state.add("plan", array(params, "plan"));
            case "turn/diff/updated" -> state.addProperty("diff", text(params, "diff"));
            case "error" -> {
                if (!get("turnId").isBlank() && !text(params, "turnId", get("turnId")).equals(get("turnId"))) { return; }
                state.addProperty("error", text(obj(params, "error"), "message", text(params, "message")));
                state.addProperty("errorKind", flag(params, "willRetry") ? "retry" : "turn");
            }
            default -> { return; }
        }
        // Thread loading and metadata changes are not chat activity. Record whole items rather than every streamed token.
        if (!method.startsWith("thread/") && !method.endsWith("Delta") && !method.endsWith("/delta")) {
            state.addProperty("updatedAt", System.currentTimeMillis());
        }
        revision++;
        if (liveActivity || method.startsWith("turn/") || method.equals("thread/status/changed") || method.equals("thread/closed")) { runtimeRevision = revision; }
    }
    /** Live activity recovers a missed turn start. Cached history and late output must not revive finished work. */
    private boolean observeActivity(JsonObject params) {
        String turnId = text(params, "turnId");
        if (turnId.isBlank() || turnId.equals(get("completedTurnId"))) { return false; }
        if (!get("turnId").isBlank() && !turnId.equals(get("turnId"))) { return false; }
        state.addProperty("turnId", turnId);
        state.addProperty("working", true);
        state.addProperty("unread", false);
        return true;
    }
    private void put(JsonObject item) {
        if (!text(item, "id").isBlank()) { items.put(text(item, "id"), item.deepCopy()); itemRevisions.put(text(item, "id"), revision + 1); questions(item); }
    }
    /** Codex sends asynchronous choices on agent messages, separate from blocking RPC requests. */
    private void questions(JsonObject item) {
        String key = text(item, "id");
        if (!text(item, "type").equals("agentMessage") || array(item, "questions").isEmpty() || array(state, "answeredQuestions").contains(new JsonPrimitive(key))) { return; }
        var questions = new JsonArray();
        for (var entry : array(item, "questions")) {
            var source = entry.getAsJsonObject();
            var options = new JsonArray();
            for (var option : array(source, "options")) { options.add(object("label", option.getAsString(), "description", "")); }
            questions.add(object("id", key + "-" + questions.size(), "question", text(source, "title"), "options", options));
        }
        requests.put(key, object("key", key, "itemId", key, "method", "item/tool/requestUserInput", "isBlocking", false, "questions", questions));
    }
    private void preview(String value) {
        state.addProperty("preview", value.substring(0, Math.min(120, value.length())).replaceAll("\\s+", " ").replaceAll("[*`#]", ""));
    }
}
