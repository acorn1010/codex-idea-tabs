package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static com.acorn.codextabs.core.Json.*;

/** Repeated layout passes must not retain a new component tree for every chat row. */
final class SidebarRendererSmoke {
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void run(Project project, Path output) throws Exception {
        if (!System.getProperty("codex.smoke.binary", "").endsWith("fake-codex.py")) { throw new IllegalStateException("Use the fake backend"); }
        var service = CodexService.get(project);
        for (int i = 0; i < 60; i++) {
            var chat = service.importThread(object("id", "renderer-" + i, "name", "Renderer fixture " + i, "cwd", service.cwd()));
            chat.set("pinned", i % 2 == 0);
        }
        service.changed("");
        JList list = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (list == null || list.getModel().getSize() < 60) {
            list = ui(() -> {
                var window = ToolWindowManager.getInstance(project).getToolWindow("Codex");
                if (window == null || window.getContentManager().getContentCount() == 0) { return null; }
                return find(window.getContentManager().getContent(0).getComponent());
            });
            if (System.nanoTime() > deadline) { throw new AssertionError("Sidebar did not load fixture rows"); }
            Thread.sleep(50);
        }
        var target = list;
        var result = ui(() -> {
            var model = (DefaultListModel) target.getModel();
            target.setSize(360, 600);
            var renderer = target.getCellRenderer();
            Component previous = null;
            int changedRoots = 0, maximumChildren = 0;
            int passes = 200, calls = 0;
            long started = System.nanoTime();
            var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().threadId(), allocated = bean.getThreadAllocatedBytes(thread);
            for (int pass = 0; pass < passes; pass++) {
                // A summary change invalidates variable row heights, including while the sidebar is hidden.
                model.set(0, model.get(0));
                target.getCellBounds(0, model.size() - 1);
                for (int row = 0; row < model.size(); row++) {
                    var component = renderer.getListCellRendererComponent(target, model.get(row), row, pass % 2 == 0, false);
                    if (component != previous) { changedRoots++; }
                    previous = component; calls++;
                }
                for (var child : target.getComponents()) {
                    if (child instanceof CellRendererPane pane) { maximumChildren = Math.max(maximumChildren, pane.getComponentCount()); }
                }
            }
            return object("layoutPasses", passes, "renderCalls", calls, "changedRendererRoots", changedRoots,
                "maximumRetainedRenderers", maximumChildren, "allocatedBytes", bean.getThreadAllocatedBytes(thread) - allocated,
                "elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        });
        Files.writeString(output.resolve("sidebar-renderer-result.json"), GSON.toJson(result));
        if (result.get("changedRendererRoots").getAsInt() != 1 || result.get("maximumRetainedRenderers").getAsInt() > 1) {
            throw new AssertionError("Sidebar retained fresh renderer components: " + result);
        }
    }
    private static JList<?> find(Component component) {
        if (component instanceof JList<?> list && list.getAccessibleContext().getAccessibleName() != null
            && list.getAccessibleContext().getAccessibleName().startsWith("Conversations.")) { return list; }
        if (component instanceof Container container) {
            for (var child : container.getComponents()) { var list = find(child); if (list != null) { return list; } }
        }
        return null;
    }
    private static <T> T ui(Callable<T> call) throws Exception {
        var task = new FutureTask<>(call); ApplicationManager.getApplication().invokeAndWait(task); return task.get();
    }
    private SidebarRendererSmoke() {}
}
