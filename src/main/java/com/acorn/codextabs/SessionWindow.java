package com.acorn.codextabs;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.*;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.ui.components.*;
import com.intellij.ui.content.ContentFactory;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;
import static com.acorn.codextabs.core.Json.*;

/** A lightweight session list exposes attention states without creating hidden browser instances. */
public final class SessionWindow implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(Project project, ToolWindow window) {
        var view = new Sessions(project);
        var content = ContentFactory.getInstance().createContent(view.panel, "", false);
        content.setDisposer(view);
        window.getContentManager().addContent(content);
    }
    private static final class Sessions implements Disposable {
        final JPanel panel = new JPanel(new BorderLayout(0, 8));
        private final CodexService service;
        private final Project project;
        private final DefaultListModel<JsonObject> model = new DefaultListModel<>();
        private final JBList<JsonObject> list = new JBList<>(model);
        private final JBTextField search = new JBTextField();
        private final Consumer<String> listener;
        private final javax.swing.Timer timer;
        private volatile boolean dirty = true;
        private boolean attentionOnly;
        private int hoverIndex = -1;
        private int pressedIndex = -1;
        private String rendered = "";
        Sessions(Project project) {
            this.project = project; service = CodexService.get(project);
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            panel.setPreferredSize(new Dimension(280, 600));
            var top = new JPanel(new BorderLayout(0, 8));
            var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            var add = new SessionButton("+ New chat", false, () -> ChatFiles.open(project, service.create().id, false));
            var attention = new SessionButton("Needs you", true, () -> { attentionOnly = !attentionOnly; dirty = true; });
            var refresh = new SessionButton("↻", false, this::refresh);
            refresh.setToolTipText("Load recent Codex conversations for this project");
            refresh.getAccessibleContext().setAccessibleName("Refresh conversations");
            toolbar.add(add); toolbar.add(attention); toolbar.add(refresh);
            top.add(toolbar, BorderLayout.NORTH); search.getEmptyText().setText("Find a conversation…"); top.add(search, BorderLayout.SOUTH);
            search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { dirty = true; }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { dirty = true; }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { dirty = true; }
            });
            panel.add(top, BorderLayout.NORTH); panel.add(new JBScrollPane(list), BorderLayout.CENTER);
            list.setCellRenderer((component, value, index, selected, focus) -> {
                var row = new JPanel(new BorderLayout(10, 2)); row.setBorder(BorderFactory.createEmptyBorder(9, 6, 9, 6));
                row.setBackground(index == pressedIndex ? new com.intellij.ui.JBColor(0xB9CCE4, 0x3C5270) : selected ? list.getSelectionBackground() : index == hoverIndex ? new com.intellij.ui.JBColor(0xE3E9F2, 0x303844) : list.getBackground());
                var label = new JBLabel(text(value, "title", "New chat"), ChatFiles.icon(text(value, "status")), SwingConstants.LEFT);
                label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
                row.add(label, BorderLayout.CENTER);
                var status = new JBLabel(switch (text(value, "status")) { case "attention" -> "Needs you"; case "working" -> "Working"; case "complete" -> "Done"; default -> ""; });
                status.setForeground(selected ? list.getSelectionForeground() : switch (text(value, "status")) {
                    case "attention" -> new com.intellij.ui.JBColor(0x946018, 0xE4B467);
                    case "working" -> new com.intellij.ui.JBColor(0x32639B, 0x8FBFFA);
                    case "complete" -> new com.intellij.ui.JBColor(0x2F714D, 0x88C9AA);
                    default -> UIManager.getColor("Label.disabledForeground");
                });
                row.add(status, BorderLayout.SOUTH); return row;
            });
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e) && rowAt(e.getPoint()) >= 0) { open(); } }
                @Override public void mousePressed(MouseEvent e) { pressedIndex = rowAt(e.getPoint()); list.repaint(); popup(e); }
                @Override public void mouseReleased(MouseEvent e) { pressedIndex = -1; list.repaint(); popup(e); }
                @Override public void mouseExited(MouseEvent e) { hoverIndex = -1; pressedIndex = -1; list.repaint(); }
            });
            list.addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int index = rowAt(e.getPoint());
                    if (hoverIndex != index) { hoverIndex = index; list.setCursor(Cursor.getPredefinedCursor(index >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR)); list.repaint(); }
                }
            });
            list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openChat");
            list.getActionMap().put("openChat", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { open(); } });
            listener = id -> dirty = true; service.listen(listener);
            timer = new javax.swing.Timer(300, event -> { if (dirty) { dirty = false; render(); } }); timer.start();
            refresh();
        }
        private void open() { if (list.getSelectedValue() != null) { ChatFiles.open(project, text(list.getSelectedValue(), "id"), false); } }
        private int rowAt(Point point) {
            int index = list.locationToIndex(point);
            return index >= 0 && list.getCellBounds(index, index).contains(point) ? index : -1;
        }
        private void popup(MouseEvent event) {
            if (!event.isPopupTrigger()) { return; }
            int index = rowAt(event.getPoint());
            if (index < 0) { return; }
            list.setSelectedIndex(index);
            var item = model.get(index);
            var chat = service.chat(text(item, "id"));
            var menu = new JPopupMenu();
            var rename = new JMenuItem("Rename…");
            rename.addActionListener(ignored -> {
                String name = com.intellij.openapi.ui.Messages.showInputDialog(project, "Conversation name", "Rename Chat", null, chat.get("title"), null);
                if (name != null && !name.isBlank()) { service.rename(chat.id, name.strip()); }
            });
            var pin = new JMenuItem(flag(item, "pinned") ? "Unpin from top" : "Pin to top");
            pin.addActionListener(ignored -> { chat.set("pinned", !flag(item, "pinned")); service.changed(chat.id); });
            var hide = new JMenuItem("Hide from list");
            hide.setEnabled(!chat.status().equals("working") && !chat.status().equals("attention"));
            hide.addActionListener(ignored -> { chat.set("hidden", true); service.changed(chat.id); });
            menu.add(rename); menu.add(pin); menu.addSeparator(); menu.add(hide); menu.show(list, event.getX(), event.getY());
        }
        private void refresh() {
            service.history("", "").thenAccept(result -> {
                for (var value : array(result, "data")) { service.importThread(value.getAsJsonObject()); }
            });
        }
        private void render() {
            var summaries = service.summaries();
            String next = summaries.toString() + attentionOnly + search.getText();
            if (next.equals(rendered)) { return; }
            rendered = next;
            String selected = list.getSelectedValue() == null ? "" : text(list.getSelectedValue(), "id");
            model.clear();
            for (var value : summaries) {
                var item = value.getAsJsonObject();
                if (attentionOnly && !text(item, "status").equals("attention")) { continue; }
                if (!text(item, "title").toLowerCase().contains(search.getText().toLowerCase())) { continue; }
                model.addElement(item);
                if (text(item, "id").equals(selected)) { list.setSelectedIndex(model.size() - 1); }
            }
        }
        @Override public void dispose() { timer.stop(); service.unlisten(listener); }
    }
}
