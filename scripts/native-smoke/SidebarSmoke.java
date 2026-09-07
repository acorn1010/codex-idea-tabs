package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Optional native component checks exercise the real native sidebar in the isolated fixture profile. */
final class SidebarSmoke {
    static void run(Project project, Path output) throws Exception {
        if (!System.getProperty("codex.smoke.binary", "").endsWith("fake-codex.py")) { throw new IllegalStateException("Sidebar checks require the test fixture"); }
        var service = CodexService.get(project);
        service.load("choices").get();
        waitFor(() -> {
            try { return text(array(service.chat("choices").request("fixture-choice"), "questions").get(0).getAsJsonObject(), "id").equals("fixture-choice-0"); }
            catch (IllegalArgumentException ignored) { return false; }
        });
        var chat = service.importThread(object("id", "archive", "name", "Archive workflow check", "cwd", service.cwd()));
        if (chat.archived()) { service.archive(chat.id, false).get(); }
        chat.set("draft", "Keep this draft when archived"); service.changed(chat.id);
        var root = ui(() -> ToolWindowManager.getInstance(project).getToolWindow("Codex").getContentManager().getContent(0).getComponent());
        waitFor(() -> index(root, "Archive workflow check") >= 0);
        if (ui(() -> ((com.intellij.ui.components.JBList<?>) find(root, JList.class, "Conversations.")).getExpandableItemsHandler().isEnabled())) {
            throw new AssertionError("Hover expansion must stay disabled");
        }
        click(root, "Show unanswered questions and approvals (Ctrl+Alt+A)");
        waitFor(() -> index(root, "Choose the composer layout") >= 0 && index(root, "Check the build") < 0);
        click(root, "Show all active chats");
        waitFor(() -> index(root, "Check the build") >= 0);
        // Archive is rejected even when an invocation bypasses the disabled menu item.
        try { service.archive("build", true).get(); throw new AssertionError("Archived working chat"); }
        catch (java.util.concurrent.ExecutionException expected) { if (service.chat("build").archived()) { throw new AssertionError("Working chat hidden"); } }
        hoverRow(root, "Archive workflow check", 1);
        capture(root, output.resolve("sidebar-archive-hover.png"));
        press(root, true);
        capture(root, output.resolve("sidebar-archive-pressed.png"));
        press(root, false);
        waitFor(chat::archived);
        waitFor(() -> index(root, "Archive workflow check") < 0);
        click(root, "Restore the last archived chat");
        waitFor(() -> !chat.archived() && index(root, "Archive workflow check") >= 0);
        hoverRow(root, "Archive workflow check", 1);
        press(root, true); press(root, false);
        waitFor(chat::archived);
        waitFor(() -> index(root, "Archive workflow check") < 0);
        Thread.sleep(300);
        click(root, "Browse archived chats and restore them");
        waitFor(() -> index(root, "Archive workflow check") >= 0 && index(root, "Check the build") < 0);
        hoverRow(root, "Archive workflow check", 0);
        press(root, true); press(root, false);
        if (!chat.get("draft").equals("Keep this draft when archived")) { throw new AssertionError("Archived draft lost"); }
        capture(root, output.resolve("sidebar-archived.png"));
        Files.writeString(output.resolve("sidebar-result.json"), "{\"attentionFilter\":true,\"archive\":true,\"undo\":true,\"archiveView\":true,\"activeWorkProtected\":true,\"draftKept\":true}");
    }
    private static void hoverRow(JComponent root, String title, int action) throws Exception {
        var point = ui(() -> {
            var list = find(root, JList.class, "Conversations.");
            int index = rowIndex(list, title);
            list.ensureIndexIsVisible(index);
            var bounds = list.getCellBounds(index, index);
            int scale = Math.max(1, list.getFont().getSize() / 13);
            return new Point(action == 1 ? list.getWidth() - 48 * scale : 60 * scale, bounds.y + bounds.height - 25 * scale);
        });
        ui(() -> {
            var list = find(root, JList.class, "Conversations.");
            list.putClientProperty("smoke.point", point);
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, point.x, point.y, 0, false));
            return null;
        });
        Thread.sleep(250);
    }
    private static void click(JComponent root, String name) throws Exception {
        ui(() -> { find(root, AbstractButton.class, name).doClick(); return null; });
    }
    private static void press(JComponent root, boolean down) throws Exception {
        ui(() -> {
            var list = find(root, JList.class, "Conversations.");
            var point = (Point) list.getClientProperty("smoke.point");
            list.dispatchEvent(new MouseEvent(list, down ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), down ? InputEvent.BUTTON1_DOWN_MASK : 0, point.x, point.y, 1, false, MouseEvent.BUTTON1));
            return null;
        });
    }
    private static int index(JComponent root, String title) {
        try { return ui(() -> rowIndex(find(root, JList.class, "Conversations."), title)); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static int rowIndex(JList<?> list, String title) {
        for (int i = 0; i < list.getModel().getSize(); i++) { if (list.getModel().getElementAt(i).toString().contains(title)) { return i; } }
        return -1;
    }
    private static <T extends Component> T find(Component component, Class<T> type, String name) {
        if (type.isInstance(component) && component.getAccessibleContext() != null && java.util.Objects.toString(component.getAccessibleContext().getAccessibleName(), "").startsWith(name)) { return type.cast(component); }
        if (component instanceof Container container) {
            for (var child : container.getComponents()) { var found = find(child, type, name); if (found != null) { return found; } }
        }
        return null;
    }
    private static void capture(JComponent root, Path path) throws Exception {
        var image = ui(() -> {
            var buffer = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = buffer.createGraphics(); root.printAll(graphics); graphics.dispose(); return buffer;
        });
        ImageIO.write(image, "png", path.toFile());
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) { throw new AssertionError("Sidebar check timed out"); } Thread.sleep(100); }
    }
    private static <T> T ui(Callable<T> call) throws Exception {
        var task = new FutureTask<>(call); ApplicationManager.getApplication().invokeAndWait(task); return task.get();
    }
    private SidebarSmoke() {}
}
