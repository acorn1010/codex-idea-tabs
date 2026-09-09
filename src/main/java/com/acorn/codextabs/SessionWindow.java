package com.acorn.codextabs;

import com.google.gson.JsonObject;
import com.intellij.ide.IdeTooltip;
import com.intellij.ide.IdeTooltipManager;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.project.*;
import com.intellij.openapi.wm.*;
import com.intellij.ui.components.*;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import static com.acorn.codextabs.core.Json.*;

/** A native, virtualized chat list makes attention, ongoing work, and archived history easy to scan. */
public final class SessionWindow implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(Project project, ToolWindow window) {
        var view = new Sessions(project);
        var content = ContentFactory.getInstance().createContent(view.panel, "", false);
        content.setDisposer(view);
        window.getContentManager().addContent(content);
    }
    private record Row(JsonObject chat, String group, int count) {}
    private record HitAreas(int index, Rectangle cell, float scale, Rectangle body, Rectangle archive, Rectangle more) {}

    private static final class Sessions implements Disposable {
        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        private final CodexService service;
        private final Project project;
        private final DefaultListModel<Row> model = new DefaultListModel<>();
        private final JBList<Row> list = new JBList<>(model);
        private final JBTextField search = new JBTextField();
        private final JBLabel title = label("Chats", SessionTheme.TEXT);
        private final JBLabel connection = label("Connecting…", SessionTheme.MUTED);
        private final JPanel filters = plain(new FlowLayout(FlowLayout.LEFT, 2, 0));
        private final JPanel notice = plain(new BorderLayout(4, 0));
        private final JBLabel noticeText = label("", SessionTheme.MUTED);
        private final SessionButton all;
        private final SessionButton attention;
        private final SessionButton archives;
        private final SessionButton refresh;
        private final SessionButton moreHistory;
        private final Consumer<String> listener;
        private final javax.swing.Timer timer;
        private volatile boolean dirty = true;
        private boolean archived;
        private boolean attentionOnly;
        private boolean disposed;
        private boolean refreshing;
        private int hoverIndex = -1;
        private int hoverAction;
        private int pressedIndex = -1;
        private int pressedAction;
        private String pressedId = "";
        private Point hoverPoint;
        private HitAreas hitAreas;
        private IdeTooltip tooltip;
        private String rendered = "";
        private String activeId = "";
        private String cursor = "";
        private int refreshGeneration;
        private String undoId = "";
        private String workspace = "";
        private SessionButton workspaceFilter;

        Sessions(Project project) {
            this.project = project; service = CodexService.get(project);
            panel.setBorder(JBUI.Borders.empty(8, 10, 6, 10));
            panel.setPreferredSize(JBUI.size(320, 600)); panel.setMinimumSize(JBUI.size(240, 100));
            var top = plain(new BorderLayout(0, JBUI.scale(10)));
            var heading = plain(new BorderLayout(8, 0));
            title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2));
            heading.add(title, BorderLayout.CENTER);
            heading.add(button("New chat", "plus", true, "New chat (Ctrl+Alt+N)", () -> {
                if (archived || attentionOnly) { showArchive(false); attentionOnly = false; dirty = true; }
                ChatFiles.open(project, !workspace.isBlank() ? service.createForPath(workspace).id : !activeId.isBlank() ? service.createInWorkspace(activeId).id : service.create().id, false);
            }), BorderLayout.EAST);
            top.add(heading, BorderLayout.NORTH);
            var find = plain(new BorderLayout(JBUI.scale(4), 0));
            search.getEmptyText().setText("Search chats…");
            search.getAccessibleContext().setAccessibleName("Search chats");
            search.setPreferredSize(JBUI.size(150, 30));
            refresh = button("", "refresh", false, "Refresh chats", () -> refresh(false));
            find.add(search, BorderLayout.CENTER); find.add(refresh, BorderLayout.EAST);
            top.add(find, BorderLayout.CENTER);
            all = button("All", null, false, "Show all active chats", () -> { attentionOnly = false; render(); });
            attention = button("Needs you", null, false, "Show unanswered questions and approvals (Ctrl+Alt+A)", () -> { attentionOnly = true; render(); });
            filters.add(all); filters.add(attention);
            workspaceFilter = button("", "branch", false, "Filter chats by checkout", () -> {
                workspaceFilter.setSelected(!workspace.isBlank());
                var options = new ArrayList<WorktreeFilterPopup.Option>();
                options.add(new WorktreeFilterPopup.Option("", "All worktrees"));
                for (var value : service.workspaceEntries()) {
                    var entry = value.getAsJsonObject(); String path = text(entry, "path");
                    options.add(new WorktreeFilterPopup.Option(path, service.workspaceLabel(path)));
                }
                WorktreeFilterPopup.show(workspaceFilter, options, workspace, path -> {
                    workspace = path; workspaceFilter.setSelected(!path.isBlank());
                    workspaceFilter.setToolTipText(path.isBlank() ? "All worktrees" : "Worktree: " + service.workspaceLabel(path));
                    cursor = ""; refresh(false); render();
                });
            });
            workspaceFilter.setPreferredSize(JBUI.size(28, 28)); filters.add(workspaceFilter); top.add(filters, BorderLayout.SOUTH);
            search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { dirty = true; }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { dirty = true; }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { dirty = true; }
            });
            panel.add(top, BorderLayout.NORTH);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            // IDEA's expanded-item popup duplicates a clipped row outside the sidebar on hover.
            list.setExpandableItemsEnabled(false);
            list.setCellRenderer((component, value, index, selected, focus) -> row(value, index, selected));
            list.getAccessibleContext().setAccessibleName("Conversations. Enter opens, Delete archives, Shift+F10 shows actions.");
            list.setVisibleRowCount(12);
            var scroll = new JBScrollPane(list);
            scroll.setBorder(JBUI.Borders.empty()); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            panel.add(scroll, BorderLayout.CENTER);
            var bottom = plain(new BorderLayout(0, JBUI.scale(6)));
            var messages = plain(new BorderLayout(0, 4));
            notice.add(noticeText, BorderLayout.CENTER);
            var undo = button("Undo", null, false, "Restore the last archived chat", () -> {
                if (!undoId.isBlank()) { archive(undoId, false); }
            });
            notice.add(undo, BorderLayout.EAST); notice.setVisible(false);
            moreHistory = button("Load older chats", null, false, "Load the next page of conversations", () -> refresh(true));
            moreHistory.setVisible(false);
            messages.add(notice, BorderLayout.NORTH); messages.add(moreHistory, BorderLayout.SOUTH);
            bottom.add(messages, BorderLayout.NORTH);
            var footer = plain(new BorderLayout(4, 0));
            footer.setBorder(JBUI.Borders.customLine(SessionTheme.LINE, 1, 0, 0, 0));
            archives = button("Archived", "archive", false, "Browse archived chats and restore them", () -> showArchive(!archived));
            connection.setFont(connection.getFont().deriveFont(connection.getFont().getSize2D() - 1));
            footer.add(archives, BorderLayout.WEST); footer.add(connection, BorderLayout.EAST);
            bottom.add(footer, BorderLayout.SOUTH); panel.add(bottom, BorderLayout.SOUTH);
            list.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    pressedIndex = rowAt(event.getPoint()); pressedAction = actionAt(event.getPoint());
                    pressedId = pressedIndex < 0 ? "" : text(model.get(pressedIndex).chat(), "id");
                    hideTooltip(); list.setToolTipText(null); list.repaint(); popup(event);
                }
                @Override public void mouseReleased(MouseEvent event) {
                    int index = rowAt(event.getPoint()), action = actionAt(event.getPoint());
                    if (SwingUtilities.isLeftMouseButton(event) && index >= 0 && text(model.get(index).chat(), "id").equals(pressedId) && action == pressedAction) {
                        list.setSelectedIndex(index);
                        var item = model.get(index).chat();
                        if (action == 1) { archive(text(item, "id"), !archived); }
                        else if (action == 2) { menu(index, event.getX(), event.getY()); }
                        else { open(); }
                    }
                    pressedIndex = -1; pressedAction = 0; pressedId = ""; list.repaint(); popup(event);
                }
                @Override public void mouseExited(MouseEvent event) {
                    hoverPoint = null; hoverIndex = -1; hoverAction = 0; pressedIndex = -1; pressedId = "";
                    hideTooltip(); list.setToolTipText(null); list.repaint();
                }
            });
            list.addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent event) {
                    hoverPoint = event.getPoint(); updateHover();
                }
            });
            list.addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent event) { hitAreas = null; hideTooltip(); updateHover(); }
            });
            key(KeyEvent.VK_ENTER, 0, "open", this::open);
            key(KeyEvent.VK_DELETE, 0, "archive", () -> { if (list.getSelectedValue() != null) { archive(text(list.getSelectedValue().chat(), "id"), !archived); } });
            key(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK, "menu", () -> {
                int index = list.getSelectedIndex();
                if (index >= 0) { var bounds = list.getCellBounds(index, index); menu(index, JBUI.scale(24), bounds.y + bounds.height); }
            });
            search.addActionListener(event -> { if (!model.isEmpty()) { list.setSelectedIndex(0); open(); } });
            project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                @Override public void selectionChanged(FileEditorManagerEvent event) {
                    activeId = event.getNewFile() instanceof ChatFiles.ChatFile file ? file.id : "";
                    list.repaint();
                }
            });
            listener = id -> dirty = true; service.listen(listener);
            timer = new javax.swing.Timer(200, event -> { if (dirty) { dirty = false; render(); } }); timer.start();
            refresh(false);
        }
        private void showArchive(boolean value) {
            archived = value; attentionOnly = false; search.setText(""); cursor = "";
            title.setText(value ? "Archived chats" : "Chats"); filters.setVisible(!value);
            archives.setSelected(value); archives.setText(value ? "Back to chats" : "Archived");
            archives.setIcon(SessionIcons.icon(value ? "back" : "archive"));
            dirty = true; refresh(false);
        }
        private JComponent row(Row value, int index, boolean selected) { return row(value, index, selected, false); }
        private JComponent row(Row value, int index, boolean selected, boolean showActions) {
            var chat = value.chat();
            boolean hover = index == hoverIndex, pressed = index == pressedIndex;
            boolean active = text(chat, "id").equals(activeId);
            var outer = plain(new BorderLayout());
            // IDEA paints list selection under the renderer. Cover it so only the chat body is highlighted.
            outer.setOpaque(true); outer.setBackground(list.getBackground());
            if (!value.group().isBlank()) {
                var group = label(value.group() + "  " + value.count(), groupColor(value.group()));
                group.setFont(group.getFont().deriveFont(Font.BOLD, group.getFont().getSize2D() - 1));
                group.setBorder(JBUI.Borders.empty(10, 8, 5, 8)); outer.add(group, BorderLayout.NORTH);
            }
            var body = new JPanel(new BorderLayout(JBUI.scale(8), 0)) {
                @Override protected void paintComponent(Graphics graphics) {
                    var g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color fill = pressed && pressedAction == 0 ? SessionTheme.PRESSED : hover ? SessionTheme.HOVER : active || selected && list.hasFocus() ? SessionTheme.SELECTED : null;
                    if (fill != null) { g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(8), JBUI.scale(8)); }
                    if (active) { g.setColor(SessionTheme.ACCENT); g.fillRoundRect(0, JBUI.scale(12), JBUI.scale(2), getHeight() - JBUI.scale(24), 2, 2); }
                    g.dispose();
                }
            };
            body.setOpaque(false); body.setBorder(JBUI.Borders.empty(8, 8, 8, 6));
            var icon = label("", SessionTheme.MUTED);
            icon.setIcon(archived ? SessionIcons.icon("archive") : ChatFiles.icon(text(chat, "status")));
            icon.setVerticalAlignment(SwingConstants.TOP); body.add(icon, BorderLayout.WEST);
            var copy = plain(new BorderLayout(0, JBUI.scale(3)));
            var name = label(text(chat, "title", "New chat"), SessionTheme.TEXT);
            if (Set.of("attention", "complete").contains(text(chat, "status"))) { name.setFont(name.getFont().deriveFont(Font.BOLD)); }
            copy.add(name, BorderLayout.NORTH);
            String detail = switch (text(chat, "status")) {
                case "attention" -> "Waiting for your input";
                case "working" -> "Working on this task";
                case "complete" -> "New response";
                case "error" -> "Needs a retry";
                default -> flag(chat, "hasDraft") ? "Draft saved" : text(chat, "preview").isBlank() ? text(chat, "threadId").isBlank() ? "No messages yet" : "Open conversation" : text(chat, "preview");
            };
            detail = text(chat, "workspaceLabel") + " · " + detail;
            var preview = label(detail, text(chat, "status").equals("attention") ? SessionTheme.ATTENTION : SessionTheme.MUTED);
            preview.setFont(preview.getFont().deriveFont(preview.getFont().getSize2D() - 1)); copy.add(preview, BorderLayout.SOUTH);
            body.add(copy, BorderLayout.CENTER);
            var actions = plain(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            actions.setPreferredSize(JBUI.size(56, 28));
            if (showActions || hover || selected && list.hasFocus()) {
                actions.add(actionIcon(archived ? "restore" : "archive", archived || canArchive(chat), hoverAction == 1 && hover, pressedAction == 1 && pressed));
                actions.add(actionIcon("more", true, hoverAction == 2 && hover, pressedAction == 2 && pressed));
            } else {
                var age = label(age(chat), SessionTheme.MUTED);
                age.setFont(age.getFont().deriveFont(age.getFont().getSize2D() - 2));
                if (flag(chat, "pinned")) { age.setIcon(SessionIcons.icon("pin")); }
                actions.add(age);
            }
            body.add(actions, BorderLayout.EAST); outer.add(body, BorderLayout.CENTER);
            outer.getAccessibleContext().setAccessibleName(text(chat, "title") + ", " + detail + (archived ? ", archived" : ""));
            return outer;
        }
        private JComponent actionIcon(String name, boolean enabled, boolean hover, boolean pressed) {
            var icon = new JBLabel(SessionIcons.icon(name)) {
                @Override protected void paintComponent(Graphics graphics) {
                    if (enabled && (hover || pressed)) {
                        var g = (Graphics2D) graphics.create();
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(pressed ? SessionTheme.PRESSED : SessionTheme.SELECTED); g.fillRoundRect(1, 0, getWidth() - 2, getHeight(), 6, 6); g.dispose();
                    }
                    super.paintComponent(graphics);
                }
            };
            icon.setPreferredSize(JBUI.size(28, 28)); icon.setHorizontalAlignment(SwingConstants.CENTER);
            icon.setForeground(enabled ? SessionTheme.TEXT : SessionTheme.MUTED); icon.setEnabled(enabled);
            return icon;
        }
        private void open() { if (list.getSelectedValue() != null) { ChatFiles.open(project, text(list.getSelectedValue().chat(), "id"), false); } }
        // Measure the same renderer used for painting. Font size, cell insets, and UI scaling can
        // change its button positions. Cache one row so pointer movement does not keep laying it out.
        private HitAreas hitAreas(int index) {
            var cell = list.getCellBounds(index, index);
            if (hitAreas != null && hitAreas.index() == index && hitAreas.cell().equals(cell) && hitAreas.scale() == JBUI.scale(1f)) { return hitAreas; }
            var renderer = row(model.get(index), index, false, true);
            renderer.setBounds(0, 0, cell.width, cell.height); renderer.doLayout();
            var body = (JPanel) ((BorderLayout) renderer.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            body.doLayout();
            var actions = (JPanel) ((BorderLayout) body.getLayout()).getLayoutComponent(BorderLayout.EAST);
            actions.doLayout();
            var bodyBounds = new Rectangle(cell.x + body.getX(), cell.y + body.getY(), body.getWidth(), body.getHeight());
            var archive = SwingUtilities.convertRectangle(actions, actions.getComponent(0).getBounds(), renderer);
            var more = SwingUtilities.convertRectangle(actions, actions.getComponent(1).getBounds(), renderer);
            archive.translate(cell.x, cell.y); more.translate(cell.x, cell.y);
            hitAreas = new HitAreas(index, cell, JBUI.scale(1f), bodyBounds, archive, more);
            return hitAreas;
        }
        private int rowAt(Point point) {
            int index = list.locationToIndex(point);
            return index >= 0 && hitAreas(index).body().contains(point) ? index : -1;
        }
        private int actionAt(Point point) {
            int index = rowAt(point);
            if (index < 0) { return 0; }
            var areas = hitAreas(index);
            return areas.archive().contains(point) ? 1 : areas.more().contains(point) ? 2 : 0;
        }
        private void updateHover() {
            int index = hoverPoint == null ? -1 : rowAt(hoverPoint);
            int action = index < 0 ? 0 : actionAt(hoverPoint);
            boolean moved = hoverIndex != index || hoverAction != action;
            hoverIndex = index; hoverAction = action;
            list.setCursor(Cursor.getPredefinedCursor(index >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            String hint = index < 0 || !pressedId.isBlank() ? null : action == 1 ? archived ? "Restore chat" : canArchive(model.get(index).chat()) ? "Archive chat (Delete)" : "Finish the active work and answer requests before archiving"
                : action == 2 ? "More chat actions" : text(model.get(index).chat(), "title");
            if (moved || !Objects.equals(list.getToolTipText(), hint) || tooltip == null && hint != null) {
                hideTooltip(); list.setToolTipText(hint);
                if (hint != null && pressedId.isBlank()) {
                    var areas = hitAreas(index);
                    // IDEA uses its own balloon tooltips, not JComponent.createToolTip(). Keep their
                    // wrapped content to the left of both actions, even for long unbroken file names.
                    int padding = JBUI.scale(8), width = Math.max(1, Math.min(JBUI.scale(280), areas.archive().x - areas.body().x - padding * 4));
                    var content = new JTextArea(hint);
                    content.setFont(list.getFont()); content.setForeground(SessionTheme.TEXT);
                    width = Math.max(1, Math.min(width, content.getFontMetrics(content.getFont()).stringWidth(hint)));
                    content.setOpaque(false); content.setEditable(false); content.setFocusable(false);
                    content.setLineWrap(true); content.setWrapStyleWord(true); content.setBorder(null);
                    content.setSize(width, Short.MAX_VALUE);
                    content.setPreferredSize(new Dimension(width, content.getPreferredSize().height));
                    var point = new Point(areas.body().x + padding * 2 + width / 2, areas.body().y + areas.body().height + padding);
                    tooltip = new IdeTooltip(list, point, content, text(model.get(index).chat(), "id"), action, hint)
                        .setPreferredPosition(Balloon.Position.below).setShowCallout(false).setToCenter(false).setToCenterIfSmall(false)
                        .setBorderInsets(JBUI.insets(8)).setRequestFocus(false);
                    IdeTooltipManager.getInstance().setCustomTooltip(list, tooltip);
                }
            }
            if (moved) { list.repaint(); }
        }
        private void hideTooltip() {
            if (tooltip != null) { tooltip.hide(); tooltip = null; }
            IdeTooltipManager.getInstance().setCustomTooltip(list, null);
        }
        private void popup(MouseEvent event) { if (event.isPopupTrigger()) { int index = rowAt(event.getPoint()); if (index >= 0) { menu(index, event.getX(), event.getY()); } } }
        private void menu(int index, int x, int y) {
            list.setSelectedIndex(index);
            var item = model.get(index).chat(); String id = text(item, "id");
            var menu = new JPopupMenu();
            menuItem(menu, "Open in tab", () -> ChatFiles.open(project, id, false));
            menuItem(menu, "Open to the side", () -> ChatFiles.open(project, id, true));
            menu.addSeparator();
            menuItem(menu, "Rename…", () -> {
                String name = com.intellij.openapi.ui.Messages.showInputDialog(project, "Chat name", "Rename Chat", null, text(item, "title"), null);
                if (name != null && !name.isBlank()) { service.rename(id, name.strip()); }
            });
            menuItem(menu, flag(item, "pinned") ? "Unpin chat" : "Pin chat", () -> { service.chat(id).set("pinned", !flag(item, "pinned")); service.changed(id); });
            menu.addSeparator();
            var archive = menuItem(menu, archived ? "Restore chat" : "Archive chat", () -> archive(id, !archived));
            archive.setIcon(SessionIcons.icon(archived ? "restore" : "archive"));
            archive.setEnabled(archived || canArchive(item));
            if (!archive.isEnabled()) { archive.setToolTipText("Finish the active work and answer pending requests first"); }
            menu.show(list, Math.min(x, Math.max(0, list.getWidth() - JBUI.scale(180))), y);
        }
        private void archive(String id, boolean value) {
            service.archive(id, value).whenComplete((result, error) -> ui(() -> {
                if (error != null) { message(CodexService.message(error), "", true); return; }
                if (value) {
                    var manager = FileEditorManager.getInstance(project);
                    for (var file : manager.getOpenFiles()) { if (file instanceof ChatFiles.ChatFile chat && chat.id.equals(id)) { manager.closeFile(file); } }
                }
                message(value ? "Chat archived" : "Chat restored", value ? id : "", false);
                dirty = true;
            }));
        }
        private void message(String text, String undo, boolean error) {
            noticeText.setText(text); noticeText.setToolTipText(text); noticeText.setForeground(error ? SessionTheme.ERROR : SessionTheme.MUTED);
            undoId = undo; notice.getComponent(1).setVisible(!undo.isBlank()); notice.setVisible(true); panel.revalidate();
        }
        private void refresh(boolean nextPage) {
            int generation = ++refreshGeneration;
            boolean archivePage = archived;
            var archiveStates = new HashMap<String, Boolean>();
            for (boolean view : new boolean[]{false, true}) {
                for (var entry : service.summaries(view)) { archiveStates.put(text(entry.getAsJsonObject(), "threadId"), view); }
            }
            refreshing = true; refresh.setEnabled(false); moreHistory.setEnabled(false); dirty = true;
            service.workspaces("").thenCompose(ignored -> service.history("", nextPage ? cursor : "", archivePage, workspace)).whenComplete((result, error) -> ui(() -> {
                if (generation != refreshGeneration) { return; }
                refreshing = false; refresh.setEnabled(true); moreHistory.setEnabled(true);
                if (error != null) { message("Could not refresh: " + CodexService.message(error), "", true); }
                else {
                    cursor = text(result, "nextCursor");
                    for (var value : array(result, "data")) {
                        var chat = service.importThread(value.getAsJsonObject());
                        // A history response must not undo an archive or restore made while it was loading.
                        String threadId = chat.get("threadId");
                        if (!chat.get("hidden").equals("true") && (!archiveStates.containsKey(threadId) || archiveStates.get(threadId) == chat.archived())) { chat.set("archived", archivePage); }
                    }
                    service.changed("");
                }
                dirty = true;
            }));
        }
        private void render() {
            var summaries = service.summaries(archived);
            int archiveCount = service.summaries(true).size();
            String next = summaries.toString() + archived + attentionOnly + search.getText() + service.connectionStatus() + refreshing + cursor + archiveCount + workspace;
            if (next.equals(rendered)) { return; }
            rendered = next;
            int waiting = 0;
            for (var value : summaries) { if (text(value.getAsJsonObject(), "status").equals("attention")) { waiting++; } }
            workspaceFilter.setSelected(!workspace.isBlank());
            all.setText("All  " + summaries.size()); all.setSelected(!attentionOnly);
            attention.setText("Needs you" + (waiting > 0 ? "  " + waiting : "")); attention.setSelected(attentionOnly);
            attention.setForeground(waiting > 0 ? SessionTheme.ATTENTION : SessionTheme.MUTED);
            archives.setText(archived ? "Back to chats" : "Archived" + (archiveCount == 0 ? "" : "  " + archiveCount));
            archives.setSelected(archived); moreHistory.setVisible(!cursor.isBlank());
            connection.setText(refreshing ? "Refreshing…" : switch (service.connectionStatus()) { case "connected" -> "Connected"; case "connecting" -> "Connecting…"; default -> "Offline"; });
            connection.setToolTipText(service.distro().isBlank() ? "Local Codex" : "Codex in " + service.distro());
            String selected = list.getSelectedValue() == null ? "" : text(list.getSelectedValue().chat(), "id");
            var groups = new LinkedHashMap<String, List<JsonObject>>();
            for (String group : archived ? new String[]{"Archived"} : new String[]{"Needs you", "Working", "Pinned", "Recent"}) { groups.put(group, new ArrayList<>()); }
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            for (var value : summaries) {
                var item = value.getAsJsonObject(); String status = text(item, "status");
                if (!workspace.isBlank() && !com.acorn.codextabs.core.GitWorktrees.contains(workspace, text(item, "cwd"))) { continue; }
                if (attentionOnly && !status.equals("attention")) { continue; }
                if (!(text(item, "title") + " " + text(item, "preview")).toLowerCase(Locale.ROOT).contains(query)) { continue; }
                String group = archived ? "Archived" : status.equals("attention") ? "Needs you" : status.equals("working") ? "Working" : flag(item, "pinned") ? "Pinned" : "Recent";
                groups.get(group).add(item);
            }
            model.clear(); hitAreas = null; pressedIndex = -1;
            groups.forEach((group, items) -> {
                for (int i = 0; i < items.size(); i++) {
                    var item = items.get(i); model.addElement(new Row(item, i == 0 ? group : "", items.size()));
                    if (text(item, "id").equals(selected)) { list.setSelectedIndex(model.size() - 1); }
                    if (text(item, "id").equals(pressedId)) { pressedIndex = model.size() - 1; }
                }
            });
            list.getEmptyText().setText(!query.isBlank() ? "No matching chats" : archived ? "No archived chats" : attentionOnly ? "You're all caught up" : "Start your first chat");
            list.getEmptyText().appendLine(!query.isBlank() ? "Try a different search." : archived ? "Archive a finished chat to keep this list clear." : attentionOnly ? "Questions and approvals will appear here." : "Use New chat to begin a task.");
            panel.revalidate(); updateHover(); list.repaint();
        }
        private void key(int code, int modifiers, String name, Runnable action) {
            list.getInputMap().put(KeyStroke.getKeyStroke(code, modifiers), name);
            list.getActionMap().put(name, new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { action.run(); } });
        }
        private void ui(Runnable action) { ApplicationManager.getApplication().invokeLater(() -> { if (!disposed && !project.isDisposed()) { action.run(); } }); }
        @Override public void dispose() { disposed = true; timer.stop(); hideTooltip(); service.unlisten(listener); }
    }
    private static boolean canArchive(JsonObject chat) { return !Set.of("working", "attention").contains(text(chat, "status")); }
    private static Color groupColor(String group) { return group.equals("Needs you") ? SessionTheme.ATTENTION : group.equals("Working") ? SessionTheme.ACCENT : SessionTheme.MUTED; }
    private static String age(JsonObject chat) {
        long elapsed = Math.max(0, System.currentTimeMillis() - chat.get("updatedAt").getAsLong());
        if (elapsed < 60_000) { return "now"; }
        if (elapsed < 3_600_000) { return elapsed / 60_000 + "m"; }
        if (elapsed < 86_400_000) { return elapsed / 3_600_000 + "h"; }
        if (elapsed < 604_800_000) { return elapsed / 86_400_000 + "d"; }
        return DateTimeFormatter.ofPattern("MMM d").format(Instant.ofEpochMilli(chat.get("updatedAt").getAsLong()).atZone(ZoneId.systemDefault()));
    }
    private static JPanel plain(LayoutManager layout) { var panel = new JPanel(layout); panel.setOpaque(false); return panel; }
    private static JBLabel label(String text, Color color) { var label = new JBLabel(text); label.putClientProperty("html.disable", true); label.setForeground(color); return label; }
    private static SessionButton button(String text, String icon, boolean primary, String tooltip, Runnable action) {
        var button = new SessionButton(text, icon, primary, action); button.setToolTipText(tooltip); button.getAccessibleContext().setAccessibleName(tooltip); return button;
    }
    private static JMenuItem menuItem(JPopupMenu menu, String label, Runnable action) {
        var item = new JMenuItem(label); item.addActionListener(event -> action.run()); menu.add(item); return item;
    }
}
