package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.*;
import com.intellij.openapi.project.Project;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static com.acorn.codextabs.core.Json.*;

/** Use the installed WSL Codex and plugin service to call the bundled MCP server without inference. */
final class ReadinessSmoke {
    static void run(Project project, Path output) throws Exception {
        var probes = new AtomicInteger();
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/health", exchange -> {
            int status = probes.incrementAndGet() >= 6 ? 200 : 503;
            exchange.sendResponseHeaders(status, -1); exchange.close();
        });
        http.createContext("/down", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        var modelRequests = new AtomicInteger();
        var timeoutMode = new java.util.concurrent.atomic.AtomicBoolean();
        http.createContext("/v1/responses", exchange -> {
            var request = com.google.gson.JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            Files.writeString(output.resolve("readiness-model-request.json"), GSON.toJson(request));
            int index = modelRequests.incrementAndGet();
            com.google.gson.JsonObject item;
            if (index == 1 || timeoutMode.get()) {
                String name = "";
                for (var definition : array(request, "tools")) {
                    var tool = definition.getAsJsonObject();
                    if (text(tool, "type").equals("function") && text(tool, "name").contains("wait_http")) { name = text(tool, "name"); }
                }
                if (name.isBlank()) { throw new AssertionError("The readiness tool is not exposed as a function: " + array(request, "tools")); }
                item = object("type", "function_call", "id", "fixture-call-" + index, "call_id", "fixture-call-" + index, "name", name,
                    "arguments", GSON.toJson(object("url", "http://127.0.0.1:" + http.getAddress().getPort() + (timeoutMode.get() ? "/down" : "/health"), "environment", "windows", "timeout_seconds", timeoutMode.get() ? 1 : 9, "interval_seconds", 1)));
            } else {
                item = object("type", "message", "id", "fixture-done", "role", "assistant", "content", java.util.List.of(object("type", "output_text", "text", "READINESS_OK")));
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                for (var event : java.util.List.of(
                    object("type", "response.created", "response", object("id", "fixture-" + index)),
                    object("type", "response.output_item.done", "item", item),
                    object("type", "response.completed", "response", object("id", "fixture-" + index, "status", "completed", "output", java.util.List.of(item), "usage", object("input_tokens", 100, "output_tokens", 20, "total_tokens", 120))))) {
                    body.write(("event: " + text(event, "type") + "\ndata: " + GSON.toJson(event) + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        });
        http.start();
        try {
            var service = CodexService.get(project);
            var settings = service.settings();
            settings.localModelEnabled = true; settings.sharedGuidanceEnabled = false;
            settings.localModelUrl = "http://127.0.0.1:" + http.getAddress().getPort() + "/v1";
            settings.provider = "codex"; settings.model = LocalModel.MODEL; settings.modelSelectionSaved = true;
            service.restoreReady().get(15, TimeUnit.SECONDS);
            service.reconnect();
            var chat = service.create();
            service.send(chat.id, object("model", LocalModel.MODEL, "permissions", "full", "text", "Run the bounded readiness fixture.")).get(60, TimeUnit.SECONDS);
            long turnDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            while (chat.busy() && System.nanoTime() < turnDeadline) { Thread.sleep(100); }
            Files.writeString(output.resolve("readiness-chat.json"), GSON.toJson(chat.snapshot()));
            if (chat.busy() || !chat.get("error").isBlank() || probes.get() != 6) { throw new AssertionError("The normal model tool turn failed: " + chat.get("error")); }
            String thread = chat.get("threadId");
            if (thread.isBlank()) { throw new AssertionError("The real local Codex thread did not start"); }
            String url = "http://127.0.0.1:" + http.getAddress().getPort();
            com.google.gson.JsonObject success = null;
            for (var value : array(chat.snapshot(), "items")) {
                var item = value.getAsJsonObject();
                if (text(item, "type").equals("mcpToolCall") && text(item, "server").equals("codex_tabs_readiness")) { success = obj(item, "result"); }
            }
            if (success == null || !flag(obj(success, "structuredContent"), "ready")) { throw new AssertionError("The real model tool call did not return readiness"); }
            if (!obj(chat.snapshot(), "localLoopGuard").isEmpty()) { throw new AssertionError("Successful readiness entered loop history"); }
            var timeout = service.rpc("mcpServer/tool/call", object("threadId", thread, "server", "codex_tabs_readiness", "tool", "wait_http",
                "arguments", object("url", url + "/down", "environment", "windows", "timeout_seconds", 1))).get(15, TimeUnit.SECONDS);
            if (!flag(timeout, "isError") || !text(obj(timeout, "structuredContent"), "reason").equals("timeout")) { throw new AssertionError("Missing bounded timeout: " + timeout); }
            timeoutMode.set(true);
            service.send(chat.id, object("model", LocalModel.MODEL, "permissions", "full", "text", "Run the repeated timeout fixture.")).get(30, TimeUnit.SECONDS);
            turnDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            while (chat.busy() && System.nanoTime() < turnDeadline) { Thread.sleep(100); }
            Files.writeString(output.resolve("readiness-timeout-chat.json"), GSON.toJson(chat.snapshot()));
            if (chat.busy() || !flag(obj(chat.snapshot(), "localLoopGuard"), "stopped") || !chat.get("errorKind").equals("loopGuardStopped")) {
                service.stop(chat.id);
                throw new AssertionError("Repeated real model timeouts did not stop: " + chat.get("error"));
            }
            timeoutMode.set(false);
            service.reconnect();
            service.load(chat.id).get(60, TimeUnit.SECONDS);
            var resumed = service.rpc("mcpServer/tool/call", object("threadId", thread, "server", "codex_tabs_readiness", "tool", "wait_http",
                "arguments", object("url", url + "/health", "environment", "windows", "timeout_seconds", 2))).get(15, TimeUnit.SECONDS);
            if (!flag(obj(resumed, "structuredContent"), "ready")) { throw new AssertionError("The tool was lost on reconnect"); }
            Files.writeString(output.resolve("readiness-result.json"), GSON.toJson(object("realCodexMcp", true, "modelToolTurn", true, "windowsLoopbackFromWsl", true,
                "sixStartupProbes", true, "readyExempt", true, "fiveTimeoutsStop", true, "reconnect", true, "timeoutResult", timeout)));
        } finally { http.stop(0); }
    }
    private ReadinessSmoke() {}
}
