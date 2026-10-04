package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.Conversation;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.acorn.codextabs.core.Json.*;

/** Exercise exact PR search and archived row actions in a disposable IDEA project. */
final class PrSearchSmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project); service.restoreReady().get(10, TimeUnit.SECONDS);
        if (!service.summaries().isEmpty() || service.archiveCount() != 0) { throw new IllegalStateException("Use an empty fixture profile"); }
        var active = chat(service, "Create query fix", "codex", "acme/api", false);
        var archived = chat(service, "Earlier Claude review", "claude", "acme/worker", true);
        chat(service, "Unrelated", "codex", "acme/other", false).replaceClaudeHistory(new com.google.gson.JsonArray());
        check(array(service.searchChats("#8123").get(), "data").size() == 2, "Both providers and archives must be searched");
        check(array(service.searchChats("acme/api#8123").get(), "data").size() == 1, "Repository-qualified match");
        check(array(service.searchChats("#812").get(), "data").isEmpty(), "PR numbers must match exactly");
        ui(() -> { var tool = ToolWindowManager.getInstance(project).getToolWindow("Codex"); tool.setAvailable(true); tool.show(); return null; });
        var root = ui(() -> ToolWindowManager.getInstance(project).getToolWindow("Codex").getContentManager().getContent(0).getComponent());
        var search = ui(() -> find(root, JTextField.class, "Search chats"));
        var list = ui(() -> find(root, JList.class, "Conversations."));
        ui(() -> { search.setText("#8123"); return null; });
        await(() -> ui(() -> list.getModel().getSize() == 2));
        ui(() -> {
            capture(root, output.resolve("pr-search-sidebar.png"));
            int index = index(list, archived.id); list.setSelectedIndex(index);
            check(index >= 0, "Archived chat missing from active search");
            list.getActionMap().get("archive").actionPerformed(new ActionEvent(list, 0, "archive"));
            return null;
        });
        await(() -> !archived.archived());
        check(!active.archived(), "Restoring the archived result changed a different chat");
        ui(() -> { search.setText("https://github.com/acme/api/pull/8123/files"); return null; });
        await(() -> ui(() -> list.getModel().getSize() == 1 && index(list, active.id) == 0));
        ui(() -> { search.setText("#812"); return null; });
        await(() -> ui(() -> list.getModel().getSize() == 0));
        Files.writeString(output.resolve("pr-search-result.json"), GSON.toJson(object("bothProviders", true, "archivedMatches", true, "exactNumbers", true, "repositoryAndUrl", true, "restoreAction", true)));
    }
    private static Conversation chat(CodexService service, String title, String provider, String repo, boolean archived) {
        var chat = service.create(); chat.set("title", title); chat.set("provider", provider);
        chat.event(object("method", "item/completed", "params", object("item", object("id", "pr", "type", "agentMessage", "text", "Created https://github.com/" + repo + "/pull/8123"))));
        chat.set("preview", "All done"); chat.archive(archived); service.changed(chat.id); return chat;
    }
    private static int index(JList<?> list, String id) throws Exception {
        for (int i = 0; i < list.getModel().getSize(); i++) {
            var row = list.getModel().getElementAt(i); var accessor = row.getClass().getDeclaredMethod("chat"); accessor.setAccessible(true);
            if (text((JsonObject) accessor.invoke(row), "id").equals(id)) { return i; }
        }
        return -1;
    }
    private static <T extends JComponent> T find(Component root, Class<T> type, String name) {
        if (type.isInstance(root) && Objects.toString(root.getAccessibleContext().getAccessibleName(), "").startsWith(name)) { return type.cast(root); }
        if (root instanceof Container parent) { for (var child : parent.getComponents()) { try { return find(child, type, name); } catch (NoSuchElementException ignored) {} } }
        throw new NoSuchElementException(name);
    }
    private static <T> T ui(Callable<T> action) throws Exception { var task = new FutureTask<T>(action); ApplicationManager.getApplication().invokeAndWait(task); return task.get(); }
    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.call()) { if (System.nanoTime() > deadline) { throw new AssertionError("PR search did not settle"); } Thread.sleep(50); }
    }
    private static void capture(Component component, Path path) throws Exception {
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); component.paintAll(graphics); graphics.dispose(); ImageIO.write(image, "png", path.toFile());
    }
    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    private PrSearchSmoke() {}
}
