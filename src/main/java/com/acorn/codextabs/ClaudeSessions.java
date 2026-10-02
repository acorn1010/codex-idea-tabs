package com.acorn.codextabs;

import com.acorn.codextabs.core.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.acorn.codextabs.core.Json.*;

/** Own Claude processes independently so a Codex disconnect cannot interrupt Claude chats. */
final class ClaudeSessions implements AutoCloseable {
    private static final long TURN_SETUP_SECONDS = 15;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Supplier<CodexSettings.State> settings;
    private final Supplier<String> distro;
    private final Supplier<SharedGuidance.Source> guidance;
    private final Consumer<Conversation> changed;

    ClaudeSessions(Supplier<CodexSettings.State> settings, Supplier<String> distro, Supplier<SharedGuidance.Source> guidance, Consumer<Conversation> changed) {
        this.settings = settings; this.distro = distro; this.guidance = guidance; this.changed = changed;
    }
    void load(Conversation chat) { session(chat); }
    ClaudeHistory history() {
        String config = Objects.toString(System.getenv("CLAUDE_CONFIG_DIR"), "");
        if (!distro.get().isBlank()) {
            try {
                var process = new ProcessBuilder("wsl.exe", "--distribution", distro.get(), "--exec", "/bin/sh", "-lc", "printf '%s' \"${CLAUDE_CONFIG_DIR:-$HOME/.claude}\"").start();
                if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Could not find Claude's WSL history folder."); }
                config = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
                if (process.exitValue() != 0 || config.isBlank()) { throw new IllegalStateException("Could not find Claude's WSL history folder."); }
            } catch (java.io.IOException | InterruptedException error) { throw new IllegalStateException(error); }
        }
        if (config.isBlank()) { config = Path.of(System.getProperty("user.home"), ".claude").toString(); }
        return new ClaudeHistory(Path.of(com.acorn.codextabs.core.Paths.host(config, distro.get(), com.intellij.openapi.util.SystemInfo.isWindows)));
    }
    JsonArray frames(Conversation chat) {
        if (chat.get("threadId").isBlank()) { return new JsonArray(); }
        try { return history().read(chat.get("threadId")); }
        catch (java.io.IOException error) { throw new IllegalStateException("Could not read Claude history.", error); }
    }
    void refreshHistory(Conversation chat) {
        if (chat.busy()) { return; }
        var frames = frames(chat);
        if (frames.isEmpty()) { return; }
        var restored = new Conversation(chat.id, chat.get("cwd"));
        var protocol = new ClaudeProtocol(restored);
        frames.forEach(value -> protocol.accept(value.getAsJsonObject()));
        chat.replaceClaudeHistory(restored.items());
    }
    JsonObject transcript(Conversation chat) {
        var copy = Conversation.restore(chat.snapshot());
        refreshHistory(copy);
        return copy.snapshot();
    }
    JsonObject list(Set<String> directories, String search) {
        try { return object("data", history().list(directories, search)); }
        catch (java.io.IOException error) { throw new IllegalStateException("Could not list Claude sessions.", error); }
    }
    JsonObject status(Conversation chat) {
        var result = object("usage", obj(chat.snapshot(), "claudeUsage"), "modelUsage", obj(chat.snapshot(), "claudeModelUsage"),
            "cost", chat.snapshot().get("claudeCost"), "rateLimit", obj(chat.snapshot(), "claudeRateLimit"));
        if (chat.archived()) { result.addProperty("contextError", "Restore this chat to inspect live context."); return result; }
        var client = session(chat).client;
        // Ask the CLI for plan usage without scanning local transcripts for usage attribution.
        var limits = client.control(object("subtype", "get_usage", "skip_behaviors", true)).orTimeout(10, TimeUnit.SECONDS);
        try {
            if (!flag(chat.snapshot(), "claudeSettingsSupported")) { throw new IllegalStateException("This CLI does not advertise current model controls."); }
            result.add("context", client.control(object("subtype", "get_context_usage")).orTimeout(10, TimeUnit.SECONDS).join());
        }
        catch (Exception error) { result.addProperty("contextError", "Context details are unavailable in this Claude version. Update Claude Code. " + CodexService.message(error)); }
        try {
            var usage = limits.join();
            if (!usage.has("rate_limits_available")) { throw new IllegalStateException("Update Claude Code to read subscription limits."); }
            result.addProperty("rateLimitsAvailable", flag(usage, "rate_limits_available"));
            result.add("rateLimits", obj(usage, "rate_limits"));
        }
        catch (Exception error) { result.addProperty("rateLimitsError", "Could not read subscription limits. " + CodexService.message(error)); }
        return result;
    }
    JsonObject mcp(Conversation chat) {
        var response = session(chat).client.control(object("subtype", "mcp_status")).join();
        var data = new JsonArray();
        for (var entry : array(response, "mcpServers")) {
            var server = entry.getAsJsonObject().deepCopy(); var tools = new JsonObject();
            for (var tool : array(server, "tools")) { tools.add(text(tool.getAsJsonObject(), "name"), tool); }
            server.add("tools", tools); server.addProperty("authStatus", text(server, "status")); data.add(server);
        }
        return object("data", data);
    }
    JsonObject skills(Conversation chat) {
        session(chat);
        var skills = new JsonArray();
        for (var value : array(chat.snapshot(), "claudeCommands")) {
            String name = value.isJsonPrimitive() ? value.getAsString() : text(value.getAsJsonObject(), "name");
            if (name.isBlank()) { continue; }
            skills.add(object("name", name, "path", "claude-command:" + name, "description", value.isJsonObject() ? text(value.getAsJsonObject(), "description") : "Claude command", "scope", "user", "enabled", true, "nativeCommand", true));
        }
        var root = guidance.get().skillsRoot();
        if (!root.isBlank()) {
            var directory = Path.of(com.acorn.codextabs.core.Paths.host(root, distro.get(), com.intellij.openapi.util.SystemInfo.isWindows));
            try (var entries = Files.list(directory)) {
                entries.sorted().filter(path -> Files.isRegularFile(path.resolve("SKILL.md"))).forEach(path -> skills.add(object(
                    "name", path.getFileName().toString(), "path", root + "/" + path.getFileName() + "/SKILL.md", "description", "Shared project skill", "scope", "repo", "enabled", true)));
            } catch (java.io.IOException error) { throw new IllegalStateException("Could not list shared skills.", error); }
        }
        return object("data", List.of(object("cwd", chat.get("cwd"), "skills", skills)));
    }
    void cancelQueued(Conversation chat, String id) { new ClaudeQueue(chat).remove(id); changed.accept(chat); }
    void resumeQueued(Conversation chat) { session(chat).next(); }

    private void recoverUnsentHandoff(Conversation chat) {
        // Older versions saved startup IDs for handoffs that had never sent a Claude message.
        // Clear only that case. Missing sessions with actual Claude history still need to report an error.
        if (chat.get("threadId").isBlank() || !chat.get("providerContextPending").equals("true") || !chat.get("claudeResumeId").isBlank()) { return; }
        if (chat.items().asList().stream().anyMatch(item -> text(item.getAsJsonObject(), "historyProvider").isBlank())) { return; }
        if (frames(chat).isEmpty()) { chat.set("threadId", ""); }
    }

    private Session session(Conversation chat) {
        var session = sessions.computeIfAbsent(chat.id, ignored -> new Session(chat));
        synchronized (session) {
            if (session.client != null && session.client.isAlive() && session.initialized) { return session; }
            session.close(); session.closed = false;
            long generation = session.generation.get();
            chat.set("claudeConnection", "connecting"); changed.accept(chat);
            try {
                String binary = distro.get().isBlank() ? ClaudeClient.executable(settings.get().claudeBinary) : settings.get().claudeBinary;
                if (binary.isBlank()) { binary = "claude"; }
                recoverUnsentHandoff(chat);
                String resume = chat.get("threadId").isBlank() ? chat.get("claudeResumeId") : chat.get("threadId");
                boolean fork = chat.get("threadId").isBlank() && !resume.isBlank();
                session.sessionId = chat.get("threadId").isBlank() ? UUID.randomUUID().toString() : chat.get("threadId");
                refreshHistory(chat);
                var shared = guidance.get();
                var builder = ClaudeClient.process(binary, chat.get("cwd"), distro.get(), session.sessionId, resume, fork, fork ? chat.get("claudeResumeAt") : "");
                session.client = ClaudeClient.start(builder, distro.get(), shared.claudeContext(), event -> session.event(event, generation), reason -> {
                    if (session.closed || session.generation.get() != generation) { return; }
                    chat.disconnected(); chat.set("claudeConnection", "disconnected"); chat.loadFailed(reason); changed.accept(chat);
                });
                if (session.closed || session.generation.get() != generation) { session.client.close(); throw new CancellationException("Claude connection was replaced"); }
                var initialized = session.client.initialize().join();
                if (!session.client.isAlive()) { throw new IllegalStateException("Claude exited during startup."); }
                if (session.closed || session.generation.get() != generation) { throw new CancellationException("Claude connection was replaced"); }
                chat.set("claudeModels", ClaudeProtocol.models(initialized));
                chat.set("claudeCommands", array(initialized, "commands"));
                boolean modern = java.util.stream.StreamSupport.stream(array(initialized, "models").spliterator(), false).anyMatch(value -> flag(value.getAsJsonObject(), "supportsEffort"));
                boolean settingsSupported = false;
                if (modern) {
                    try { session.client.control(object("subtype", "apply_flag_settings", "settings", new JsonObject())).orTimeout(5, TimeUnit.SECONDS).join(); settingsSupported = true; }
                    catch (Exception ignored) { /* Older CLIs expose model metadata before live settings support. */ }
                }
                chat.set("claudeSettingsSupported", settingsSupported);
                chat.set("sharedGuidanceRoot", shared.root());
                chat.set("claudeAccount", object("account", object("type", "claude", "email", text(obj(initialized, "account"), "email"), "planType", text(obj(initialized, "account"), "subscriptionType"))));
                chat.set("claudeConnection", "connected"); chat.loaded(); session.initialized = true; changed.accept(chat);
                return session;
            } catch (Exception error) {
                if (session.generation.get() != generation) { throw new CancellationException("Claude connection was replaced"); }
                session.close(); chat.set("claudeConnection", "disconnected");
                String reason = "Could not start Claude. Check the Claude executable in Settings > Tools > Codex Tabs and sign in by running claude in a terminal. " + CodexService.message(error);
                chat.loadFailed(reason); changed.accept(chat); throw new IllegalStateException(reason, error);
            }
        }
    }
    JsonObject send(Conversation chat, JsonObject payload, JsonArray input) {
        if (payload.has("goalObjective")) { throw new IllegalArgumentException("Goals are available only in Codex chats."); }
        var session = session(chat);
        content(input); // Validate attachments before accepting a queued prompt.
        synchronized (session) {
            validateOptions(chat, payload);
            if (chat.busy() || session.queue.hasNext() || session.starting) {
                var queued = session.queue.add(payload, input); changed.accept(chat);
                return object("queued", true, "id", text(queued, "id"));
            }
            session.paused = false; chat.set("claudeQueuePaused", false);
            return start(session, payload, input);
        }
    }
    private void validateOptions(Conversation chat, JsonObject payload) {
        if ((!text(payload, "effort").isBlank() || flag(payload, "fast")) && !flag(chat.snapshot(), "claudeSettingsSupported")) {
            throw new IllegalArgumentException("Update Claude Code to use effort and Fast controls.");
        }
        String model = text(payload, "model", settings.get().claudeModel);
        var selected = java.util.stream.StreamSupport.stream(array(chat.snapshot(), "claudeModels").spliterator(), false).map(JsonElement::getAsJsonObject)
            .filter(value -> text(value, "model").equals(model)).findFirst().orElse(new JsonObject());
        if (text(payload, "permissions", settings.get().claudePermissions).equals("auto") && !flag(selected, "supportsAutoMode")) {
            throw new IllegalArgumentException("Auto approval is unavailable for this Claude model or CLI version.");
        }
    }
    private JsonObject start(Session session, JsonObject payload, JsonArray input) {
        var chat = session.chat;
        String permissions = payload.has("reviewTarget") ? "read" : text(payload, "permissions", settings.get().claudePermissions);
        String mode = switch (permissions) { case "ask" -> "default"; case "edit" -> "acceptEdits"; case "read" -> "plan"; case "auto" -> "auto"; default -> throw new IllegalArgumentException("Choose a Claude permission mode."); };
        String model = text(payload, "model", settings.get().claudeModel);
        var client = session.client;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TURN_SETUP_SECONDS);
        try {
            configure(client, object("subtype", "set_model", "model", model.isBlank() ? null : model), deadline);
            configure(client, object("subtype", "set_permission_mode", "mode", mode), deadline);
            if (flag(chat.snapshot(), "claudeSettingsSupported")) {
                configure(client, object("subtype", "apply_flag_settings", "settings", object("effortLevel", text(payload, "effort").isBlank() ? null : text(payload, "effort"), "fastMode", flag(payload, "fast"))), deadline);
            }
        } catch (TimeoutException error) {
            // End preparation before enabling retry. A late settings reply must never submit the old draft.
            session.close(); changed.accept(chat);
            throw new IllegalStateException("Claude did not finish preparing this message. Your message was not sent. Reconnect and try again.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Message preparation was interrupted. Your message was not sent.");
        } catch (ExecutionException error) { throw new CompletionException(error); }
        String turn = UUID.randomUUID().toString();
        var body = content(ProviderHandoff.input(chat, input));
        session.protocol.start(turn, input);
        try { session.client.write(object("type", "user", "uuid", turn, "session_id", session.sessionId, "parent_tool_use_id", null, "message", object("role", "user", "content", body))); }
        catch (RuntimeException error) { chat.disconnected(); throw error; }
        chat.set("providerContextPending", false);
        if (chat.get("threadId").isBlank()) { chat.set("threadId", session.sessionId); }
        return object("turn", object("id", turn));
    }
    private static void configure(ClaudeClient client, JsonObject request, long deadline) throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { throw new TimeoutException(); }
        client.control(request).get(remaining, TimeUnit.NANOSECONDS);
    }
    private JsonArray content(JsonArray input) {
        var result = new JsonArray();
        for (var entry : input) {
            var part = entry.getAsJsonObject();
            switch (text(part, "type")) {
                case "text" -> result.add(object("type", "text", "text", text(part, "text")));
                case "skill", "mention" -> result.add(object("type", "text", "text", "Read and use " + text(part, "name") + " at " + text(part, "path")));
                case "localImage", "image" -> {
                    String path = text(part, "path", text(part, "url"));
                    if (path.startsWith("data:image/")) {
                        int separator = path.indexOf(";base64,");
                        if (separator < 0) { throw new IllegalArgumentException("Image must use base64 encoding."); }
                        result.add(object("type", "image", "source", object("type", "base64", "media_type", path.substring(5, separator), "data", path.substring(separator + 8))));
                    } else {
                        try {
                            var local = Path.of(com.acorn.codextabs.core.Paths.host(path, distro.get(), com.intellij.openapi.util.SystemInfo.isWindows));
                            byte[] bytes;
                            try (var stream = Files.newInputStream(local)) { bytes = stream.readNBytes(20 * 1024 * 1024 + 1); }
                            if (bytes.length > 20 * 1024 * 1024) { throw new IllegalArgumentException("Claude image attachments must be smaller than 20 MB."); }
                            String mime = Files.probeContentType(local);
                            if (!Set.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(Objects.toString(mime, ""))) { throw new IllegalArgumentException("Claude supports PNG, JPEG, GIF, and WebP images."); }
                            result.add(object("type", "image", "source", object("type", "base64", "media_type", mime, "data", Base64.getEncoder().encodeToString(bytes))));
                        } catch (java.io.IOException error) { throw new IllegalArgumentException("Could not read attached image: " + path, error); }
                    }
                }
                default -> throw new IllegalArgumentException("Claude does not support this attachment type: " + text(part, "type"));
            }
        }
        return result;
    }
    JsonObject answer(Conversation chat, JsonObject payload) {
        var session = sessions.get(chat.id);
        if (session == null || !session.initialized || !session.client.isAlive()) { throw new IllegalStateException("Claude disconnected. Reconnect before answering."); }
        var pending = chat.request(text(payload, "key"));
        var request = obj(pending, "claudeRequest");
        var response = ClaudeProtocol.approvalResponse(request, payload);
        String decision = text(payload, "decision", "decline");
        String key = ClaudeProtocol.approvalKey(chat.get("cwd"), request);
        if (decision.equals("session") && !response.has("updatedPermissions")) { session.allowed.add(key); }
        if (decision.equals("always") && !response.has("updatedPermissions")) { synchronized (settings.get()) { if (!settings.get().claudeApprovals.contains(key)) { settings.get().claudeApprovals.add(key); } } }
        session.client.respond(text(pending, "rpcId"), response);
        chat.resolve(text(payload, "key")); changed.accept(chat); return new JsonObject();
    }
    CompletableFuture<JsonObject> stop(Conversation chat) {
        var session = sessions.get(chat.id);
        if (session == null) { return CompletableFuture.completedFuture(new JsonObject()); }
        session.paused = true;
        return CompletableFuture.supplyAsync(() -> {
            synchronized (session) {
                session.paused = true; chat.set("claudeQueuePaused", true); changed.accept(chat);
                return session.client.isAlive() ? session.client.control(object("subtype", "interrupt")).join() : new JsonObject();
            }
        });
    }
    void release(String id) { var session = sessions.remove(id); if (session != null) { session.close(); changed.accept(session.chat); } }
    @Override public void close() { sessions.keySet().forEach(this::release); }

    private final class Session implements AutoCloseable {
        final Conversation chat;
        final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
        final ClaudeProtocol protocol;
        final ClaudeQueue queue;
        volatile boolean starting;
        volatile boolean paused = true;
        final Set<String> allowed = ConcurrentHashMap.newKeySet();
        volatile ClaudeClient client;
        volatile boolean initialized;
        volatile boolean closed;
        String sessionId;
        Session(Conversation chat) { this.chat = chat; protocol = new ClaudeProtocol(chat); queue = new ClaudeQueue(chat); }
        void next() { next(true); }
        void next(boolean explicit) {
            CompletableFuture.runAsync(() -> {
                synchronized (this) {
                    if (closed || !initialized || chat.archived() || chat.busy() || starting || (paused && !explicit)) { return; }
                    paused = false; chat.set("claudeQueuePaused", false);
                    var entry = queue.take(); if (entry == null) { return; }
                    starting = true;
                    try { validateOptions(chat, obj(entry, "payload")); start(this, obj(entry, "payload"), array(entry, "input")); }
                    catch (Exception error) {
                        // Keep a failed send available to retry, without an automatic retry loop.
                        queue.retry(entry);
                        paused = true; chat.set("claudeQueuePaused", true); chat.set("error", CodexService.message(error));
                    } finally { starting = false; changed.accept(chat); }
                }
            });
        }
        void event(JsonObject event, long expectedGeneration) {
            if (closed || generation.get() != expectedGeneration) { return; }
            String type = text(event, "type");
            if (type.equals("control_request")) {
                var request = obj(event, "request"); String id = text(event, "request_id");
                if (!text(request, "subtype").equals("can_use_tool")) { client.reject(id, "Unsupported Claude control request: " + text(request, "subtype")); return; }
                String name = text(request, "tool_name");
                String approval = ClaudeProtocol.approvalKey(chat.get("cwd"), request);
                boolean remembered;
                synchronized (settings.get()) { remembered = settings.get().claudeApprovals.contains(approval); }
                if (!name.equals("AskUserQuestion") && (allowed.contains(approval) || remembered)) { client.respond(id, object("behavior", "allow", "updatedInput", obj(request, "input"))); return; }
                var params = object("turnId", chat.get("turnId"), "claudeRequest", request);
                boolean nativeRules = !name.equals("AskUserQuestion") && ClaudeProtocol.approvalResponse(request, object("decision", "always")).has("updatedPermissions");
                String rules = ClaudeProtocol.permissionDescription(request);
                if (name.equals("AskUserQuestion")) {
                    var questions = new JsonArray();
                    for (var entry : array(obj(request, "input"), "questions")) {
                        var question = entry.getAsJsonObject().deepCopy(); question.addProperty("id", Integer.toString(questions.size())); questions.add(question);
                    }
                    params.add("questions", questions);
                    chat.event(object("id", id, "method", "item/tool/requestUserInput", "params", params));
                } else {
                    params.addProperty("reason", "Allow Claude to use " + name + "?");
                    params.addProperty("command", GSON.toJson(obj(request, "input")));
                    params.add("approvalChoices", GSON.toJsonTree(List.of(
                        object("decision", "decline", "label", "Decline", "description", "Decline this action and let Claude continue."),
                        object("decision", "session", "label", "Allow for session", "description", nativeRules ? "Allow the suggested rule for this Claude session: " + rules : "Allow this exact tool and input until this chat reconnects."),
                        object("decision", "always", "label", "Always allow", "scope", "always", "description", nativeRules ? "Save this Claude permission rule in local project settings: " + rules : "Remember this exact " + name + " request in this checkout. Other tool inputs still need approval."),
                        object("decision", "accept", "label", "Allow once", "description", "Allow this action once."))));
                    chat.event(object("id", id, "method", "claude/toolApproval", "params", params));
                }
            } else if (type.equals("control_cancel_request")) { chat.resolve(new JsonPrimitive(text(event, "request_id")).toString()); }
            else { protocol.accept(event); }
            if (type.equals("result") && flag(event, "is_error")) { paused = true; chat.set("claudeQueuePaused", true); }
            changed.accept(chat);
            if (type.equals("result") && !paused && queue.hasNext()) { next(false); }
        }
        @Override public void close() {
            closed = true; paused = true; chat.set("claudeQueuePaused", true); generation.incrementAndGet(); initialized = false; allowed.clear();
            if (client != null) { client.close(); }
            chat.disconnected(); chat.set("claudeConnection", "disconnected");
        }
    }
}
