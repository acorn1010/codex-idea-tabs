package com.acorn.codextabs;

import com.acorn.codextabs.core.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Test native settings and live service routing in an isolated IDE, using only a fake agent. */
public final class ApiSettingsSmoke {
    public static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project); service.restoreReady().get(10, TimeUnit.SECONDS);
        service.settings().sharedGuidanceEnabled = false;
        var config = new CodexConfigurable(project);
        JComponent root = ui(config::createComponent);
        var frame = ui(() -> { var window = new JFrame("Codex Tabs settings check"); window.setContentPane(root); window.setSize(900, 820); window.setVisible(true); return window; });
        try {
            check(!ui(config::isModified), "Opening settings changed stored values");
            ui(() -> { button(root, "Add Routeway").doClick();
                ((JCheckBox) field(config, "localModelEnabled")).doClick();
                ((JTextField) field(config, "localModelUrl")).setText("http://127.0.0.1:30002/v1"); return null; });
            check(ui(config::isModified), "Adding a connection did not enable Apply");
            var panel = field(config, "connections");
            var entry = ui(() -> { var method = panel.getClass().getDeclaredMethod("connections"); method.setAccessible(true); return ((java.util.List<ApiConnection>) method.invoke(panel)).getFirst(); });
            check(entry.models.contains("glm-5.3-flash-uncensored") && entry.baseUrl.equals("https://api.routeway.ai/v1"), "Routeway preset is wrong");
            check(service.settings().apiConnections.isEmpty(), "Draft connection escaped before Apply");
            checkApiStatus(panel, root, output);
            ui(() -> { ((JComboBox<?>) field(panel, "preferred")).setSelectedIndex(2); return null; });
            capture(root, output.resolve("api-settings.png"));
            ui(() -> { config.apply(); return null; });
            var waiting = service.create(); await(() -> !flag(service.snapshot(waiting.id), "settingsPending"));
            check(!ui(config::isModified), "Apply left the form modified");
            check(service.settings().localModelEnabled && service.settings().localModelUrl.equals("http://127.0.0.1:30002/v1"), "Apply lost local GLM settings");
            check(service.settings().apiConnectionId.equals(entry.id), "New chat preference was not saved");
            var chat = service.create(); check(chat.get("apiConnectionId").equals(entry.id), "New chat lost the API connection");
            String originalPermissions = service.settings().permissions;
            service.rememberPermissions(chat.id, object("permissions", "read"));
            service.rememberComposer(chat.id, object("planMode", true));
            check(service.settings().permissions.equals(originalPermissions) && !service.settings().planMode, "API preferences changed the built-in Codex preferences");
            service.rememberPermissions(chat.id, object("permissions", "ask")); service.rememberComposer(chat.id, object("planMode", false));
            var snapshot = service.snapshot(chat.id);
            check(!flag(obj(snapshot, "account"), "requiresOpenaiAuth"), "API chat requested ChatGPT login");
            check(text(obj(snapshot, "settings"), "permissions").equals("ask"), "API chat inherited unsupported automatic review");
            check(text(array(snapshot, "models").get(0).getAsJsonObject(), "model").equals(entry.models.getFirst()), "API chat used the built-in model list");
            service.send(chat.id, object("text", "Hello fixture", "model", "stale-gpt", "effort", "xhigh", "fast", false, "permissions", "auto")).get(10, TimeUnit.SECONDS);
            var initialChat = chat; await(() -> !initialChat.busy());
            var stats = service.rpc("fixture/stats", object()).get(10, TimeUnit.SECONDS);
            check(text(obj(stats, "lastStart"), "modelProvider").equals(entry.providerId()), "Thread started with the wrong API provider");
            check(text(obj(stats, "lastTurn"), "model").equals(entry.models.getFirst()), "Stale GPT model overrode custom model");
            check(text(obj(stats, "lastTurn"), "approvalsReviewer").equals("user"), "Custom provider invoked automatic review");
            check(!obj(stats, "lastTurn").has("summary") && !obj(stats, "lastTurn").has("serviceTierForTurn"), "Custom turn inherited OpenAI-only settings");
            chat.set("draft", "Keep this draft");
            service.selectProvider(chat.id, "codex").get(10, TimeUnit.SECONDS);
            check(service.chat(chat.id).get("apiConnectionId").isBlank() && service.chat(chat.id).get("draft").equals("Keep this draft"), "Built-in switch lost the draft or retained custom routing");
            service.selectProvider(chat.id, "api:" + entry.id).get(10, TimeUnit.SECONDS);
            chat = service.chat(chat.id); var active = chat;
            service.send(chat.id, object("text", "FIXTURE_HOLD_APPROVALS", "permissions", "ask")).get(10, TimeUnit.SECONDS);
            await(active::busy); var original = service.connect().get();
            service.settingsApplied(false, true);
            check(flag(service.snapshot(chat.id), "settingsPending"), "Active turn did not defer reconnect");
            check(service.connect().get() == original && original.isAlive(), "Apply interrupted the active backend");
            service.stop(chat.id).get(10, TimeUnit.SECONDS); await(() -> !active.busy());
            await(() -> !flag(service.snapshot(active.id), "settingsPending"));
            check(service.connect().get() != original, "Idle backend was not replaced");
            service.load(chat.id).get(10, TimeUnit.SECONDS);
            stats = service.rpc("fixture/stats", object()).get(10, TimeUnit.SECONDS);
            check(text(obj(stats, "lastResume"), "modelProvider").equals(entry.providerId()), "Resume lost custom routing");
            var custom = service.settings().apiConnections.getFirst(); custom.models.add(LocalModel.MODEL);
            var aliased = service.create();
            service.send(aliased.id, object("text", "Remote model with a local alias", "model", LocalModel.MODEL, "permissions", "ask")).get(10, TimeUnit.SECONDS);
            await(() -> !aliased.busy());
            stats = service.rpc("fixture/stats", object()).get(10, TimeUnit.SECONDS);
            var started = obj(stats, "lastStart");
            check(text(started, "modelProvider").equals(entry.providerId()) && !obj(started, "config").has("model_context_window"), "Remote alias inherited local GLM routing or context");
            service.selectProvider(aliased.id, "codex").get(10, TimeUnit.SECONDS);
            check(array(service.snapshot(aliased.id), "models").asList().stream().anyMatch(value -> flag(value.getAsJsonObject(), "isLocal")), "Custom connection hid local GLM from Codex");
            var xml = XmlSerializer.serialize(service.settings()); var restored = XmlSerializer.deserialize(xml, CodexSettings.State.class);
            check(restored.apiConnections.size() == 1 && restored.apiConnections.getFirst().equals(service.settings().apiConnections.getFirst()), "IDE settings serialization lost connection metadata");
            JDOMUtil.write(xml, output.resolve("api-settings.xml"));
            ui(() -> { config.reset(); button(root, "Add custom API").doClick(); config.disposeUIResources(); return null; });
            check(service.settings().apiConnections.size() == 1, "Cancel saved a draft connection");
            Files.writeString(output.resolve("api-settings-result.json"), GSON.toJson(object("apiCheckIcons", true, "preset", true, "localSettings", true, "localAliasIsolation", true, "applyWithoutRestart", true, "cancel", true, "customModels", true,
                "noChatGptLogin", true, "perChatRouting", true, "staleModelGuard", true, "handoffDraft", true, "deferredReconnect", true, "resume", true, "settingsRoundTrip", true)));
        } finally { ui(() -> { config.disposeUIResources(); frame.dispose(); return null; }); }
    }
    private static void checkApiStatus(Object panel, JComponent root, Path output) throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"glm-5.3-flash-uncensored\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/denied/models", exchange -> { exchange.sendResponseHeaders(401, -1); exchange.close(); });
        server.start();
        var url = (JTextField) field(panel, "url"); var status = (JLabel) field(panel, "status"); var button = (JButton) field(panel, "check");
        String original = ui(url::getText); String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            ui(() -> { url.setText(base + "/ok"); button.doClick(); return null; });
            await(() -> status.getText().contains("Models found"));
            check(ui(() -> status.getIcon() != null), "Successful API check has no icon");
            var success = ui(status::getIcon); capture(root, output.resolve("api-check-success.png"));
            ui(() -> { url.setText(base + "/denied"); return null; });
            check(ui(() -> status.getIcon() == null && status.getText().isBlank()), "Editing the connection kept the previous result");
            ui(() -> { button.doClick(); check(status.getIcon() == null, "Checking kept the previous result icon"); return null; });
            await(() -> status.getText().contains("rejected the key"));
            check(ui(() -> status.getIcon() != null && status.getIcon() != success), "Failed API check has no distinct icon");
            capture(root, output.resolve("api-check-failure.png"));
            ui(() -> { url.setText("invalid-url"); button.doClick(); return null; });
            check(ui(() -> status.getIcon() != null && status.getText().contains("API base URL")), "Invalid settings have no failure icon");
        } finally { server.stop(0); ui(() -> { url.setText(original); return null; }); }
    }
    private static Object field(Object owner, String name) throws Exception { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static JButton button(Container parent, String text) {
        for (var child : parent.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) { return button; }
            if (child instanceof Container container) { var found = button(container, text); if (found != null) { return found; } }
        }
        return null;
    }
    private static void capture(JComponent component, Path target) throws Exception {
        ui(() -> { var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics(); component.paint(graphics); graphics.dispose(); ImageIO.write(image, "png", target.toFile()); return null; });
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) { throw new AssertionError("Timed out waiting for settings transition"); } Thread.sleep(30); }
    }
    private static <T> T ui(Callable<T> action) throws Exception { var task = new FutureTask<T>(action); ApplicationManager.getApplication().invokeAndWait(task); return task.get(); }
    private ApiSettingsSmoke() {}
}
