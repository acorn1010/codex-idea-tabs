package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.acorn.codextabs.core.Json.*;

/** Exercises sidebar hit targets and clicks during live list updates in a separate IDEA profile. */
final class SidebarActionsSmoke {
    static void run(Project project, Path output) throws Exception {
        if (!System.getProperty("codex.smoke.binary", "").endsWith("fake-codex.py")) { throw new IllegalStateException("Use the fake backend"); }
        var service = CodexService.get(project);
        Thread.sleep(8000);
        ui(() -> { var window = ToolWindowManager.getInstance(project).getToolWindow("Codex"); window.setAvailable(true); window.activate(null, true, true); return null; });
        var root = ui(() -> ToolWindowManager.getInstance(project).getToolWindow("Codex").getContentManager().getContent(0).getComponent());
        var list = ui(() -> find(root, JList.class, "Conversations."));
        Files.writeString(output.resolve("sidebar-actions-visibility.txt"), ui(() -> {
            StringBuilder text = new StringBuilder();
            for (Component c = list; c != null; c = c.getParent()) { text.append(c.getClass().getName()).append(" visible=").append(c.isVisible()).append(" showing=").append(c.isShowing()).append(" ").append(c.getBounds()).append("\n"); }
            return text.toString();
        }));
        waitFor(() -> uiUnchecked(list::isShowing));
        waitFor(() -> !service.connectionStatus().equals("connecting"));
        Thread.sleep(1000);
        String title = "Complete the sidebar action checks with a very long conversation name " + "longfilename".repeat(20);
        String id = "sidebar-actions-" + System.nanoTime();
        var chat = service.importThread(object("id", id, "name", title, "cwd", service.cwd()));
        chat.set("pinned", true); service.changed(id);
        waitFor(() -> index(list, id) >= 0);
        var result = object();
        ui(() -> {
            int index = index(list, id); list.ensureIndexIsVisible(index);
            var cell = list.getCellBounds(index, index);
            move(list, new Point(cell.x + JBUI.scale(60), cell.y + cell.height - JBUI.scale(20)));
            var buttons = buttons(list, index);
            var archive = buttons[0]; var more = buttons[1];
            result.addProperty("listWidth", list.getWidth()); result.addProperty("cellWidth", cell.width);
            result.addProperty("archiveBounds", archive.toString()); result.addProperty("moreBounds", more.toString());
            boolean targets = true;
            // Sample every pixel across both rendered buttons, including the left half of the ellipsis.
            for (int action = 0; action < buttons.length; action++) {
                var bounds = buttons[action];
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                    var point = new Point(x, bounds.y + bounds.height / 2); move(list, point);
                    String expected = action == 0 ? "Archive chat (Delete)" : "More chat actions";
                    if (!expected.equals(list.getToolTipText())) { targets = false; }
                }
            }
            result.addProperty("alignedTargets", targets);
            move(list, new Point(cell.x + JBUI.scale(60), cell.y + cell.height - JBUI.scale(20)));
            var tip = com.intellij.ide.IdeTooltipManager.getInstance().getCustomTooltip(list);
            var content = tip.getTipComponent(); var size = content.getPreferredSize();
            result.addProperty("tooltipWidth", size.width);
            result.addProperty("boundedTooltip", tip.getPoint().x - size.width / 2 >= cell.x && tip.getPoint().x + size.width / 2 + tip.getBorderInsets().right <= archive.x);
            content.setSize(size); content.doLayout(); capture(content, output.resolve("sidebar-actions-tooltip.png"));
            move(list, new Point(more.x + 2, more.y + more.height / 2));
            capture(root, output.resolve("sidebar-actions-more-hover.png"));
            list.putClientProperty("smoke.archive", new Point(archive.x + archive.width / 2, archive.y + archive.height / 2));
            return null;
        });
        ui(() -> {
            float scale = JBUI.scale(1f); var size = list.getSize(); var font = list.getFont(); var border = list.getBorder();
            boolean aligned = true;
            try {
                for (float nextScale : new float[]{1f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2f}) {
                    com.intellij.ui.scale.JBUIScale.setUserScaleFactorForTest(nextScale);
                    for (int width : new int[]{260, 430}) {
                        list.setBorder(width == 260 ? border : JBUI.Borders.empty(0, 4, 0, 12));
                        list.setSize(JBUI.scale(width), size.height); list.setFont(font.deriveFont(font.getSize2D() * nextScale / scale));
                        int i = index(list, id); var cell = list.getCellBounds(i, i);
                        move(list, new Point(JBUI.scale(60), cell.y + cell.height - JBUI.scale(20)));
                        var buttons = buttons(list, i);
                        for (int action = 0; action < 2; action++) {
                            var b = buttons[action];
                            for (int x = b.x; x < b.x + b.width; x++) {
                                move(list, new Point(x, b.y + b.height / 2));
                                if (!(action == 0 ? "Archive chat (Delete)" : "More chat actions").equals(list.getToolTipText())) { aligned = false; result.addProperty("misalignedAt", nextScale + "/" + width + "/" + action + "/" + x + ":" + b + ":" + list.getToolTipText()); }
                            }
                        }
                    }
                }
            } finally { com.intellij.ui.scale.JBUIScale.setUserScaleFactorForTest(scale); list.setSize(size); list.setFont(font); list.setBorder(border); }
            result.addProperty("alignedAtAllScales", aligned); return null;
        });
        Files.writeString(output.resolve("sidebar-actions-result.json"), GSON.toJson(result));
        Thread.sleep(500);
        ui(() -> {
            int i = index(list, id); var cell = list.getCellBounds(i, i);
            move(list, new Point(JBUI.scale(60), cell.y + cell.height - JBUI.scale(20)));
            var archive = buttons(list, i)[0]; list.putClientProperty("smoke.archive", new Point(archive.x + archive.width / 2, archive.y + archive.height / 2));
            com.intellij.ide.IdeTooltipManager.getInstance().show(com.intellij.ide.IdeTooltipManager.getInstance().getCustomTooltip(list), true, false);
            var tip = com.intellij.ide.IdeTooltipManager.getInstance().getCustomTooltip(list);
            var content = tip.getTipComponent();
            var balloon = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().getParentBalloonFor(content);
            if (balloon == null || !content.isShowing()) { throw new AssertionError("Native title tooltip did not show: list=" + list.isShowing() + ", content=" + content.isShowing() + ", balloon=" + balloon); }
            var tipBounds = SwingUtilities.convertRectangle(content.getParent(), content.getBounds(), list);
            result.addProperty("nativeTooltipAvoidsActions", tipBounds.x >= 0 && tipBounds.x + tipBounds.width < archive.x);
            var window = SwingUtilities.getWindowAncestor(list); capture((JComponent) ((RootPaneContainer) window).getLayeredPane(), output.resolve("sidebar-actions-native-tooltip.png"));
            tip.hide(); return null;
        });
        ui(() -> {
            int i = index(list, id); var cell = list.getCellBounds(i, i);
            move(list, new Point(JBUI.scale(60), cell.y + cell.height - JBUI.scale(20)));
            var more = buttons(list, i)[1]; var point = new Point(more.x + 2, more.y + more.height / 2);
            move(list, point); press(list, point, true); press(list, point, false);
            boolean opened = false;
            for (var element : MenuSelectionManager.defaultManager().getSelectedPath()) {
                if (element instanceof JPopupMenu popup) { opened = popup.isVisible(); }
            }
            result.addProperty("ellipsisLeftOpensMenu", opened && !chat.archived());
            MenuSelectionManager.defaultManager().clearSelectedPath(); return null;
        });
        // The same chat must still archive if a streamed summary rebuilds the list between down and up.
        ui(() -> { var p = (Point) list.getClientProperty("smoke.archive"); move(list, p); press(list, p, true); return null; });
        chat.set("draft", "Refresh while pressed"); service.changed(id);
        waitFor(() -> uiUnchecked(() -> list.getModel().getElementAt(index(list, id)).toString().contains("\"hasDraft\":true")));
        ui(() -> { press(list, (Point) list.getClientProperty("smoke.archive"), false); return null; });
        Thread.sleep(1000);
        result.addProperty("archiveAcrossRefresh", chat.archived());
        if (chat.archived()) {
            ui(() -> { find(root, AbstractButton.class, "Restore the last archived chat").doClick(); return null; });
            waitFor(() -> !chat.archived() && index(list, id) >= 0);
            result.addProperty("undo", true);
        } else { result.addProperty("undo", false); }
        // Replacing the row under a held mouse must never archive the replacement chat.
        var other = service.importThread(object("id", id + "-other", "name", "Other sidebar action check", "cwd", service.cwd()));
        other.set("updatedAt", Long.parseLong(chat.get("updatedAt")) - 1000); other.set("pinned", true); service.changed(other.id);
        waitFor(() -> index(list, other.id) >= 0);
        var point = ui(() -> {
            int i = index(list, id); var cell = list.getCellBounds(i, i); move(list, new Point(JBUI.scale(60), cell.y + cell.height - JBUI.scale(20)));
            var bounds = buttons(list, i)[0]; var p = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2); move(list, p); press(list, p, true); return p;
        });
        int before = index(list, id);
        other.set("updatedAt", Long.parseLong(chat.get("updatedAt")) + 1000); service.changed(other.id);
        waitFor(() -> index(list, other.id) == before);
        ui(() -> { press(list, point, false); return null; });
        Thread.sleep(500);
        result.addProperty("replacementProtected", !chat.archived() && !other.archived());
        Files.writeString(output.resolve("sidebar-actions-result.json"), GSON.toJson(result));
        if (!flag(result, "ellipsisLeftOpensMenu") || !flag(result, "nativeTooltipAvoidsActions") || !flag(result, "alignedAtAllScales") || !flag(result, "alignedTargets") || !flag(result, "boundedTooltip") || !flag(result, "archiveAcrossRefresh") || !flag(result, "undo") || !flag(result, "replacementProtected")) { throw new AssertionError(result.toString()); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Rectangle[] buttons(JList list, int index) {
        var bounds = list.getCellBounds(index, index);
        var renderer = (Container) list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(index), index, true, true);
        renderer.setBounds(0, 0, bounds.width, bounds.height); layout(renderer);
        var body = (Container) ((BorderLayout) renderer.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        var actions = (Container) ((BorderLayout) body.getLayout()).getLayoutComponent(BorderLayout.EAST);
        if (actions.getComponentCount() != 2) { throw new AssertionError("Missing hover actions"); }
        var result = new Rectangle[2];
        for (int i = 0; i < 2; i++) {
            var button = actions.getComponent(i); var point = SwingUtilities.convertPoint(button, 0, 0, renderer);
            result[i] = new Rectangle(bounds.x + point.x, bounds.y + point.y, button.getWidth(), button.getHeight());
        }
        return result;
    }
    private static void layout(Container container) { container.doLayout(); for (var child : container.getComponents()) { if (child instanceof Container nested) { layout(nested); } } }
    private static void move(JList<?> list, Point point) { list.dispatchEvent(event(list, MouseEvent.MOUSE_MOVED, point)); }
    private static MouseEvent event(JList<?> list, int type, Point point) { return new MouseEvent(list, type, System.currentTimeMillis(), 0, point.x, point.y, 0, false); }
    private static void press(JList<?> list, Point p, boolean down) {
        list.dispatchEvent(new MouseEvent(list, down ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), down ? InputEvent.BUTTON1_DOWN_MASK : 0, p.x, p.y, 1, false, MouseEvent.BUTTON1));
    }
    private static int index(JList<?> list, String id) { return uiUnchecked(() -> { for (int i = 0; i < list.getModel().getSize(); i++) { if (list.getModel().getElementAt(i).toString().contains("\"id\":\"" + id + "\"")) { return i; } } return -1; }); }
    private static <T extends Component> T find(Component c, Class<T> type, String name) {
        if (type.isInstance(c) && c.getAccessibleContext() != null && java.util.Objects.toString(c.getAccessibleContext().getAccessibleName(), "").startsWith(name)) { return type.cast(c); }
        if (c instanceof Container container) { for (var child : container.getComponents()) { var found = find(child, type, name); if (found != null) { return found; } } }
        return null;
    }
    private static void capture(JComponent component, Path path) throws Exception {
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); component.printAll(g); g.dispose(); ImageIO.write(image, "png", path.toFile());
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > end) { throw new AssertionError("Sidebar action check timed out"); } Thread.sleep(50); }
    }
    private static <T> T ui(Callable<T> call) throws Exception { if (SwingUtilities.isEventDispatchThread()) { return call.call(); } var task = new FutureTask<>(call); ApplicationManager.getApplication().invokeAndWait(task); return task.get(); }
    private static <T> T uiUnchecked(Callable<T> call) { try { return ui(call); } catch (Exception e) { throw new RuntimeException(e); } }
    private SidebarActionsSmoke() {}
}
