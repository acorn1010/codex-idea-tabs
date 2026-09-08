package com.acorn.codextabs;

import com.acorn.codextabs.core.*;
import com.google.gson.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.acorn.codextabs.core.Json.*;

/** Project-owned sessions continue when their editor tabs are hidden or closed. */
@Service(Service.Level.PROJECT)
public final class CodexService implements Disposable {
    private final Project project;
    private final ConcurrentMap<String, Conversation> chats = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> sends = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> resumes = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService persistence = Executors.newSingleThreadScheduledExecutor();
    private final java.nio.file.Path cache;
    private volatile boolean dirty;
    private volatile boolean disposed;
    private volatile RpcClient client;
    private CompletableFuture<RpcClient> connection;
    private volatile long connectionGeneration;
    private volatile JsonObject account = new JsonObject();
    private volatile JsonArray models = new JsonArray();
    private volatile String connectionError = "";
    private volatile String connectionStatus = "disconnected";
    private String attachmentRoot;
    private final ConcurrentMap<String, String> tabPresentations = new ConcurrentHashMap<>();

    public CodexService(Project project) {
        this.project = project;
        cache = java.nio.file.Path.of(PathManager.getConfigPath(), "codex-tabs", project.getLocationHash(), "chats.json");
        io.submit(() -> {
            try {
                var legacy = java.nio.file.Path.of(PathManager.getSystemPath(), "codex-tabs", project.getLocationHash(), "chats.json");
                var saved = Files.exists(cache) ? cache : legacy;
                if (Files.exists(saved)) {
                    for (var entry : JsonParser.parseString(Files.readString(saved)).getAsJsonArray()) {
                        var chat = Conversation.restore(entry.getAsJsonObject());
                        chats.compute(chat.id, (id, existing) -> existing == null || (existing.get("threadId").isBlank() && existing.get("draft").isBlank() && existing.items().isEmpty()) ? chat : existing);
                    }
                    changed("");
                }
            } catch (Exception error) { connectionError = "Saved chat cache could not be read. Codex history is still available."; }
        });
        persistence.scheduleWithFixedDelay(this::save, 2, 2, TimeUnit.SECONDS);
        persistence.scheduleWithFixedDelay(this::refreshTabs, 0, 300, TimeUnit.MILLISECONDS);
    }
    public static CodexService get(Project project) { return project.getService(CodexService.class); }
    public CodexSettings.State settings() { return project.getService(CodexSettings.class).getState(); }
    /** Save a deliberate selection before a message is sent, independently of active turns. */
    public synchronized void rememberModel(JsonObject payload) {
        var options = settings();
        options.model = text(payload, "model");
        options.effort = text(payload, "effort");
        options.modelSelectionSaved = true;
        changed("");
    }
    private synchronized void initializeModelPreferences() {
        if (models.isEmpty()) { return; }
        var options = settings();
        var selection = ModelPreferences.initial(models, options.model, options.effort, options.modelSelectionSaved);
        options.model = selection.model();
        options.effort = selection.effort();
        options.modelSelectionSaved = true;
    }
    public String distro() {
        var settings = settings();
        return settings.distro.isBlank() ? com.acorn.codextabs.core.Paths.distro(Objects.toString(project.getBasePath(), "")) : settings.distro;
    }
    public String cwd() {
        String path = settings().cwd.isBlank() ? Objects.toString(project.getBasePath(), System.getProperty("user.home")) : settings().cwd;
        return distro().isBlank() ? path : com.acorn.codextabs.core.Paths.linux(path);
    }
    public Conversation chat(String id) { return chats.computeIfAbsent(id, key -> new Conversation(key, cwd())); }
    public Conversation create() {
        var chat = chat(UUID.randomUUID().toString());
        changed(chat.id);
        return chat;
    }
    public JsonArray summaries() {
        return summaries(false);
    }
    public JsonArray summaries(boolean archived) {
        var result = new JsonArray();
        chats.values().stream().filter(chat -> chat.archived() == archived).map(Conversation::summary)
            .sorted(Comparator.<JsonObject>comparingInt(item -> text(item, "status").equals("attention") ? 0 : flag(item, "pinned") ? 1 : text(item, "status").equals("working") ? 2 : 3)
                .thenComparing(Comparator.comparingLong((JsonObject item) -> item.get("updatedAt").getAsLong()).reversed()))
            .forEach(result::add);
        return result;
    }
    public JsonObject snapshot(String id) {
        return snapshot(id, -1);
    }
    public JsonObject snapshot(String id, long revision) {
        return object("chat", chat(id).changes(revision), "sessions", summaries(), "models", models, "account", account, "connection", connectionStatus,
            "error", connectionError, "settings", settings(), "distro", distro(), "cwd", cwd(), "project", project.getName());
    }
    public void listen(Consumer<String> listener) { listeners.add(listener); }
    public void unlisten(Consumer<String> listener) { listeners.remove(listener); }
    public void rename(String id, String title) {
        var chat = chat(id);
        chat.set("title", title); chat.set("renamed", true); changed(id);
        if (!chat.get("threadId").isBlank()) {
            rpc("thread/name/set", object("threadId", chat.get("threadId"), "name", title)).exceptionally(error -> { chat.set("error", message(error)); changed(id); return null; });
        }
    }
    public void changed(String id) {
        dirty = true;
        for (var listener : listeners) { listener.accept(id); }
    }
    public synchronized CompletableFuture<RpcClient> connect() {
        if (disposed) { return CompletableFuture.failedFuture(new IllegalStateException("Project is closed")); }
        if (connection != null && !connection.isCompletedExceptionally() && (client == null || client.isAlive())) { return connection; }
        connectionError = "";
        connectionStatus = "connecting";
        changed("");
        long generation = ++connectionGeneration;
        connection = CompletableFuture.supplyAsync(() -> {
            try {
                var builder = new ProcessBuilder(com.acorn.codextabs.core.Paths.command(settings().binary, distro(), SystemInfo.isWindows));
                if (distro().isBlank() && Files.isDirectory(java.nio.file.Path.of(cwd()))) { builder.directory(java.nio.file.Path.of(cwd()).toFile()); }
                // A configured WSL executable is passed as one argument, even when its path contains spaces.
                var rpc = new RpcClient(builder.start(), this::event, message -> {
                    if (generation != connectionGeneration) { return; }
                    connectionStatus = "disconnected";
                    connectionError = disposed ? "" : message;
                    chats.values().forEach(Conversation::disconnected);
                    resumes.clear(); changed("");
                });
                if (disposed || generation != connectionGeneration) { rpc.close(); throw new IllegalStateException("Connection was replaced"); }
                client = rpc;
                rpc.request("initialize", object("clientInfo", object("name", "codex_idea_tabs", "title", "Codex Tabs for IDEA", "version", "0.1.1"), "capabilities", object("experimentalApi", true))).join();
                rpc.notify("initialized", new JsonObject());
                connectionStatus = "connected";
                rpc.request("account/read", object("refreshToken", false)).thenAccept(value -> { account = value; changed(""); });
                rpc.request("model/list", object("limit", 100)).thenAccept(value -> { models = array(value, "data"); initializeModelPreferences(); changed(""); });
                changed("");
                return rpc;
            } catch (Exception error) {
                if (generation != connectionGeneration) { throw new CompletionException(error); }
                if (client != null) { client.close(); }
                connectionError = "Could not start Codex. Check the executable and WSL distribution in Settings. " + message(error);
                connectionStatus = "disconnected";
                changed("");
                throw new CompletionException(error);
            }
        }, io);
        return connection;
    }
    public CompletableFuture<JsonObject> rpc(String method, JsonObject params) { return connect().thenCompose(client -> client.request(method, params)); }
    public void reconnect() {
        synchronized (this) { connectionGeneration++; if (client != null) { client.close(); } client = null; connection = null; resumes.clear(); attachmentRoot = null; chats.values().forEach(Conversation::disconnected); }
        connect();
    }
    public CompletableFuture<JsonObject> history(String search, String cursor) {
        return history(search, cursor, false);
    }
    public CompletableFuture<JsonObject> history(String search, String cursor, boolean archived) {
        var params = object("limit", 100, "cwd", cwd(), "sortKey", "updated_at", "useStateDbOnly", true, "archived", archived);
        if (!search.isBlank()) { params.addProperty("searchTerm", search); }
        if (!cursor.isBlank()) { params.addProperty("cursor", cursor); }
        return rpc("thread/list", params);
    }
    public Conversation importThread(JsonObject thread) {
        String threadId = text(thread, "id");
        var chat = chats.values().stream().filter(value -> value.get("threadId").equals(threadId)).findFirst().orElseGet(() -> chat(threadId));
        chat.hydrate(thread, chat.revision());
        if (!text(thread, "cwd").isBlank()) { chat.set("cwd", text(thread, "cwd")); }
        changed(chat.id);
        return chat;
    }
    public CompletableFuture<Void> load(String id) {
        var chat = chat(id);
        if (chat.get("threadId").isBlank()) { return connect().thenApply(ignored -> null); }
        // Reading an archived chat must not resume or restore its backend session.
        if (chat.archived()) { return older(id, "").thenApply(ignored -> null); }
        return resumes.computeIfAbsent(id, key -> {
            long revision = chat.revision();
            return rpc("thread/resume", object("threadId", chat.get("threadId"), "excludeTurns", true)).thenCompose(result -> {
                chat.hydrate(obj(result, "thread"), revision);
                changed(id);
                return older(id, "").thenApply(ignored -> (Void) null);
            }).whenComplete((result, error) -> {
                if (error != null) { chat.set("error", message(error)); changed(id); }
            });
        });
    }
    public CompletableFuture<JsonObject> older(String id, String cursor) {
        var chat = chat(id);
        long startedRevision = chat.revision();
        var params = object("threadId", chat.get("threadId"), "limit", 100, "sortDirection", "desc");
        if (!cursor.isBlank()) { params.addProperty("cursor", cursor); }
        return rpc("thread/items/list", params).thenApply(page -> {
            chat.historyPage(array(page, "data"), text(page, "nextCursor"), startedRevision); changed(id); return page;
        });
    }
    public CompletableFuture<JsonObject> send(String id, JsonObject payload) {
        var chat = chat(id);
        var result = new CompletableFuture<JsonObject>();
        sends.compute(id, (key, previous) -> (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((v, e) -> null))
            .thenRunAsync(() -> {
                try {
                    if (chat.archived()) { throw new IllegalStateException("Restore this chat before sending a message."); }
                    String prompt = text(payload, "text");
                    var input = array(payload, "input").deepCopy();
                    if (!prompt.isBlank()) { input.add(object("type", "text", "text", prompt)); }
                    if (input.isEmpty()) { throw new IllegalArgumentException("Write a message or attach a file first."); }
                    var rpc = connect().join();
                    var options = settings();
                    String model = text(payload, "model", options.model);
                    String effort = text(payload, "effort", options.effort);
                    String permissions = text(payload, "permissions", options.permissions);
                    if (chat.get("threadId").isBlank()) {
                        var start = object("cwd", chat.get("cwd"), "sandbox", permissions.equals("read") ? "read-only" : "workspace-write", "approvalPolicy", "on-request", "approvalsReviewer", permissions.equals("auto") ? "auto_review" : "user");
                        if (!model.isBlank()) { start.addProperty("model", model); }
                        var thread = obj(rpc.request("thread/start", start).join(), "thread");
                        chat.hydrate(thread, chat.revision());
                        if (chat.get("title").equals("New chat") || chat.get("title").isBlank()) { chat.set("title", prompt.lines().findFirst().orElse("New chat").substring(0, Math.min(prompt.lines().findFirst().orElse("New chat").length(), 80))); }
                        resumes.put(id, CompletableFuture.completedFuture(null));
                    } else { load(id).join(); }
                    var params = object("threadId", chat.get("threadId"), "input", input);
                    String active = chat.get("turnId");
                    if (!active.isBlank()) {
                        params.addProperty("expectedTurnId", active);
                        result.complete(rpc.request("turn/steer", params).join());
                    } else {
                        if (!model.isBlank()) { params.addProperty("model", model); }
                        if (!effort.isBlank()) { params.addProperty("effort", effort); }
                        params.addProperty("approvalPolicy", "on-request");
                        params.addProperty("approvalsReviewer", permissions.equals("auto") ? "auto_review" : "user");
                        params.add("sandboxPolicy", permissions.equals("read") ? object("type", "readOnly") : object("type", "workspaceWrite", "writableRoots", new String[]{chat.get("cwd")}, "networkAccess", false, "excludeTmpdirEnvVar", false, "excludeSlashTmp", false));
                        var response = rpc.request("turn/start", params).join();
                        String returnedTurn = text(obj(response, "turn"), "id");
                        if (chat.get("turnId").isBlank() && !chat.get("completedTurnId").equals(returnedTurn) && !text(obj(response, "turn"), "status").equals("completed")) {
                            // Completion can precede the reply, even after the user has marked the chat read.
                            chat.event(object("method", "turn/started", "params", response));
                        }
                        result.complete(response);
                    }
                    if (chat.get("draft").equals(prompt)) { chat.set("draft", ""); }
                    chat.set("draftInput", new JsonArray());
                    chat.set("error", "");
                    options.permissions = permissions;
                    changed(id);
                } catch (Exception error) { chat.set("error", message(error)); changed(id); result.completeExceptionally(error); }
            }, io));
        return result;
    }
    /** Continue an edited turn in a new chat, leaving the source conversation and its active work intact. */
    public CompletableFuture<JsonObject> editMessage(String id, JsonObject payload) {
        return CompletableFuture.supplyAsync(() -> {
            var source = chat(id);
            String itemId = text(payload, "itemId");
            String threadId = source.get("threadId");
            if (threadId.isBlank() || itemId.isBlank()) { throw new IllegalArgumentException("Choose a saved user message to edit."); }
            var rpc = connect().join();
            var turns = new JsonArray();
            String cursor = "";
            boolean found = false;
            do {
                var params = object("threadId", threadId, "limit", 50, "sortDirection", "asc", "itemsView", "full");
                if (!cursor.isBlank()) { params.addProperty("cursor", cursor); }
                var page = rpc.request("thread/turns/list", params).join();
                for (var turn : array(page, "data")) {
                    turns.add(turn);
                    for (var item : array(turn.getAsJsonObject(), "items")) {
                        if (text(item.getAsJsonObject(), "id").equals(itemId)) { found = true; break; }
                    }
                    if (found) { break; }
                }
                cursor = text(page, "nextCursor");
            } while (!found && !cursor.isBlank());
            var edit = MessageEdit.prepare(turns, itemId, text(payload, "text"));
            String permissions = text(payload, "permissions", settings().permissions);
            var params = object("cwd", source.get("cwd"), "sandbox", permissions.equals("read") ? "read-only" : "workspace-write", "approvalPolicy", "on-request", "approvalsReviewer", permissions.equals("auto") ? "auto_review" : "user");
            if (!text(payload, "model").isBlank()) { params.addProperty("model", text(payload, "model")); }
            String method = "thread/start";
            if (!edit.lastTurnId().isBlank()) {
                method = "thread/fork";
                params.addProperty("threadId", threadId);
                params.addProperty("lastTurnId", edit.lastTurnId());
                params.addProperty("excludeTurns", true);
            }
            var branch = obj(rpc.request(method, params).join(), "thread");
            var revised = importThread(branch);
            revised.set("title", source.get("title") + " (edited)");
            revised.set("renamed", true);
            revised.set("answeredQuestions", array(source.snapshot(), "answeredQuestions"));
            // Keep a retryable draft even if the first turn cannot start after the fork succeeds.
            var retained = new JsonArray();
            var prompt = new ArrayList<String>();
            for (var part : edit.input()) {
                if (text(part.getAsJsonObject(), "type").equals("text")) { prompt.add(text(part.getAsJsonObject(), "text")); }
                else { retained.add(part.deepCopy()); }
            }
            revised.set("draft", String.join("\n", prompt));
            revised.set("draftInput", retained);
            changed(revised.id);
            var sendPayload = payload.deepCopy();
            sendPayload.addProperty("text", revised.get("draft"));
            sendPayload.add("input", retained);
            try { send(revised.id, sendPayload).join(); }
            catch (CompletionException error) {
                // The new tab displays the error and keeps the entire edited input ready for retry.
                revised.set("error", message(error));
            }
            changed(revised.id);
            return object("id", revised.id);
        }, io);
    }
    /** Serialize archive and restore with sends so queued messages cannot be lost to an archive. */
    public CompletableFuture<JsonObject> archive(String id, boolean archived) {
        var chat = chat(id);
        var result = new CompletableFuture<JsonObject>();
        sends.compute(id, (key, previous) -> (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((v, e) -> null))
            .thenRunAsync(() -> {
                try {
                    if (archived && !chat.canArchive()) { throw new IllegalStateException("Finish the active work and answer pending requests before archiving."); }
                    if (chat.archived() == archived) { result.complete(new JsonObject()); return; }
                    String threadId = chat.get("threadId");
                    if (!threadId.isBlank() && (archived || chat.get("archived").equals("true") && !chat.get("hidden").equals("true"))) {
                        rpc(archived ? "thread/archive" : "thread/unarchive", object("threadId", threadId)).join();
                    }
                    chat.archive(archived);
                    resumes.remove(id);
                    changed(id);
                    result.complete(new JsonObject());
                } catch (Exception error) { result.completeExceptionally(error); }
            }, io));
        return result;
    }
    public String connectionStatus() { return connectionStatus; }
    public CompletableFuture<JsonObject> answer(String id, JsonObject payload) {
        var chat = chat(id);
        String key = text(payload, "key");
        var pending = chat.request(key);
        if (!pending.has("rpcId")) {
            if (flag(payload, "dismiss")) { chat.resolve(key); changed(id); return CompletableFuture.completedFuture(new JsonObject()); }
            var answers = obj(payload, "answers");
            var reply = new StringBuilder();
            for (var q : array(pending, "questions")) {
                var question = q.getAsJsonObject();
                reply.append(text(question, "question")).append("\n");
                for (var value : array(obj(answers, text(question, "id")), "answers")) { reply.append(value.getAsString()).append("\n"); }
            }
            return send(id, object("text", reply.toString())).thenApply(result -> { chat.resolve(key); changed(id); return result; });
        }
        return connect().thenApply(rpc -> {
            String method = text(pending, "method");
            JsonObject response;
            if (method.equals("item/tool/requestUserInput")) { response = object("answers", obj(payload, "answers")); }
            else if (method.equals("mcpServer/elicitation/request")) { response = object("action", text(payload, "decision").equals("accept") ? "accept" : "decline", "content", obj(payload, "content")); }
            else if (method.equals("item/permissions/requestApproval")) { response = object("permissions", text(payload, "decision").equals("accept") ? obj(pending, "permissions") : new JsonObject(), "scope", "turn"); }
            else { response = object("decision", text(payload, "decision", "decline")); }
            rpc.respond(pending.get("rpcId"), response);
            chat.resolve(key); changed(id);
            return new JsonObject();
        });
    }
    public CompletableFuture<JsonObject> stop(String id) {
        var chat = chat(id);
        return rpc("turn/interrupt", object("threadId", chat.get("threadId"), "turnId", chat.get("turnId")));
    }
    public CompletableFuture<JsonObject> attachment(JsonObject input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = Base64.getDecoder().decode(text(input, "data"));
                if (bytes.length > 50 * 1024 * 1024) { throw new IllegalArgumentException("Files must be smaller than 50 MB."); }
                String name = text(input, "name", "attachment.txt").replaceAll("[^\\p{L}\\p{N}._ -]", "_");
                if (name.isBlank() || name.equals(".") || name.equals("..")) { name = "attachment"; }
                String root = attachmentRoot();
                String dir = root + "/" + UUID.randomUUID();
                var rpc = connect().join();
                rpc.request("fs/createDirectory", object("path", dir, "recursive", true)).join();
                String path = dir + "/" + name;
                rpc.request("fs/writeFile", object("path", path, "dataBase64", text(input, "data"))).join();
                return object("name", name, "path", path, "mime", text(input, "mime"), "size", bytes.length);
            } catch (Exception error) { throw new CompletionException(error); }
        }, io);
    }
    private synchronized String attachmentRoot() throws Exception {
        if (attachmentRoot != null) { return attachmentRoot; }
        String home = System.getProperty("user.home");
        if (SystemInfo.isWindows && !distro().isBlank()) {
            var process = new ProcessBuilder("wsl.exe", "-d", distro(), "--exec", "printenv", "HOME").start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroy(); throw new IllegalStateException("WSL did not return its home directory."); }
            home = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!home.startsWith("/")) { throw new IllegalStateException("WSL home directory is unavailable."); }
        }
        attachmentRoot = home.replace('\\', '/') + "/.codex/codex-idea-tabs/attachments";
        return attachmentRoot;
    }
    private void event(JsonObject event) {
        var params = obj(event, "params");
        String threadId = text(params, "threadId", text(obj(params, "thread"), "id"));
        if (text(event, "method").equals("account/updated") || text(event, "method").equals("account/login/completed")) {
            if (client != null) { client.request("account/read", object("refreshToken", false)).thenAccept(value -> { account = value; changed(""); }); }
        }
        var target = chats.values().stream().filter(chat -> !threadId.isBlank() && chat.get("threadId").equals(threadId)).findFirst();
        if (target.isPresent()) {
            var chat = target.get(); chat.event(event); changed(chat.id);
            if (Set.of("thread/archived", "thread/unarchived").contains(text(event, "method"))) { resumes.remove(chat.id); }
            if (event.has("id") && !Set.of("item/tool/requestUserInput", "item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval", "mcpServer/elicitation/request").contains(text(event, "method"))) {
                client.reject(event.get("id"), "This client does not yet support " + text(event, "method"));
            }
        } else if (event.has("id") && client != null) { client.reject(event.get("id"), "No open conversation owns this request."); }
    }
    private void refreshTabs() {
        if (disposed || project.isDisposed()) { return; }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed()) { return; }
            var manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            for (var file : manager.getOpenFiles()) {
                if (file instanceof ChatFiles.ChatFile chatFile) {
                    var chat = chat(chatFile.id);
                    String presentation = chat.status() + "\n" + chat.get("title");
                    if (!presentation.equals(tabPresentations.put(chatFile.id, presentation))) { manager.updateFilePresentation(file); }
                }
            }
        });
    }
    private synchronized void save() {
        if (!dirty) { return; }
        dirty = false;
        try {
            Files.createDirectories(cache.getParent());
            var state = new JsonArray();
            chats.values().forEach(chat -> state.add(chat.snapshot()));
            var temporary = cache.resolveSibling("chats.tmp");
            Files.writeString(temporary, GSON.toJson(state));
            Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception error) { dirty = true; }
    }
    public static String message(Throwable error) {
        while (error.getCause() != null) { error = error.getCause(); }
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }
    @Override public void dispose() {
        disposed = true;
        persistence.shutdownNow();
        save();
        if (client != null) { client.close(); }
        io.shutdownNow();
        listeners.clear();
    }
}
