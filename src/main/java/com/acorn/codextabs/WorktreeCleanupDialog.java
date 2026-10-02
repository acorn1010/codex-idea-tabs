package com.acorn.codextabs;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Key;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** One project-wide cleanup view is reachable from both the sidebar and chat workspace picker. */
public final class WorktreeCleanupDialog extends DialogWrapper {
    private static final Key<WorktreeCleanupDialog> OPEN = Key.create("codex.tabs.worktree.cleanup");
    private final Project project;
    private final CodexService service;
    private final JPanel content = new JPanel(new BorderLayout(0, JBUI.scale(10)));
    private final JPanel list = new Entries();
    private final JBLabel status = label("Checking worktrees…", SessionTheme.MUTED);
    private final JButton refresh = new JButton("Refresh");
    private final JButton selectClean = new JButton("Select clean");
    private final JButton clear = new JButton("Clear selection");
    private final List<Row> rows = new ArrayList<>();
    private boolean scanning;
    private boolean removing;
    private boolean confirming;
    private final JTextArea warning = new JTextArea();
    private final JBScrollPane confirmation = new JBScrollPane(warning);

    private static final class Entries extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return JBUI.size(660, 400); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return JBUI.scale(24); }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(JBUI.scale(24), visible.height - JBUI.scale(24)); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static final class Row {
        final CodexService.CleanupWorktree tree;
        final JCheckBox selected;
        final JBLabel state;
        final JTextArea detail;
        final JButton inspect = new JButton("Review files");
        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6))) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        boolean removed;
        Row(CodexService.CleanupWorktree tree) {
            this.tree = tree;
            String name = tree.branch().isBlank() ? tree.name() : tree.branch().replaceFirst("^codex/", "");
            selected = new JCheckBox(name, tree.blocked().isBlank() && tree.files().isEmpty());
            selected.putClientProperty("html.disable", true);
            selected.getAccessibleContext().setAccessibleName("Select worktree " + tree.path());
            selected.setEnabled(tree.blocked().isBlank());
            state = label(tree.blocked().isBlank() ? tree.files().isEmpty() ? "Clean" : tree.files().size() + " local entries" : "Unavailable", tree.blocked().isBlank() ? SessionTheme.MUTED : SessionTheme.ATTENTION);
            detail = new JTextArea(tree.blocked().isBlank() ? fileDetails(tree) : tree.blocked());
            detail.setEditable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true);
            detail.setFont(selected.getFont().deriveFont(selected.getFont().getSize2D() - 1));
            detail.getAccessibleContext().setAccessibleName("Worktree details " + tree.path());
            var details = new JBScrollPane(detail);
            details.setPreferredSize(JBUI.size(500, 125)); details.setVisible(false);
            inspect.setText(tree.blocked().isBlank() ? "Review files" : "Details");
            inspect.setVisible(!tree.blocked().isBlank() || !tree.files().isEmpty());
            inspect.getAccessibleContext().setAccessibleName("Review worktree " + tree.path());
            inspect.addActionListener(event -> { details.setVisible(!details.isVisible()); panel.revalidate(); panel.repaint(); });
            var heading = new JPanel(new BorderLayout(JBUI.scale(8), 0)); heading.setOpaque(false);
            heading.add(selected, BorderLayout.CENTER);
            var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)); actions.setOpaque(false);
            actions.add(state); actions.add(inspect); heading.add(actions, BorderLayout.EAST);
            var path = label(tree.path(), SessionTheme.MUTED); path.setToolTipText(tree.path());
            path.setFont(path.getFont().deriveFont(path.getFont().getSize2D() - 1));
            var summary = new JPanel(new BorderLayout(0, JBUI.scale(2))); summary.setOpaque(false);
            summary.add(heading, BorderLayout.NORTH); summary.add(path, BorderLayout.CENTER);
            summary.add(label(tree.archivedChats() + " archived chat" + (tree.archivedChats() == 1 ? "" : "s"), SessionTheme.MUTED), BorderLayout.SOUTH);
            panel.setOpaque(false); panel.setBorder(JBUI.Borders.empty(6, 8));
            panel.add(summary, BorderLayout.NORTH); panel.add(details, BorderLayout.CENTER);
            panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        private static String fileDetails(CodexService.CleanupWorktree tree) {
            var text = new StringBuilder("Removing this worktree will permanently delete these local files, including ignored files.\n\n");
            tree.files().stream().limit(500).forEach(file -> text.append(file.status()).append("  ").append(file.path()).append('\n'));
            if (tree.files().size() > 500) { text.append("\nAnd ").append(tree.files().size() - 500).append(" more entries. All local files will be deleted."); }
            return text.toString();
        }
    }

    private WorktreeCleanupDialog(Project project) {
        super(project, false, IdeModalityType.MODELESS);
        this.project = project; service = CodexService.get(project);
        setTitle("Clean up worktrees"); setResizable(true);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        var top = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        top.add(label("Worktrees with no unarchived chats. Branches and chats are kept.", SessionTheme.MUTED), BorderLayout.NORTH);
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        actions.add(selectClean); actions.add(clear); actions.add(refresh); top.add(actions, BorderLayout.SOUTH);
        selectClean.addActionListener(event -> { rows.forEach(row -> row.selected.setSelected(!row.removed && row.tree.blocked().isBlank() && row.tree.files().isEmpty())); updateActions(); });
        clear.addActionListener(event -> { rows.forEach(row -> row.selected.setSelected(false)); updateActions(); });
        refresh.addActionListener(event -> scan());
        var scroll = new JBScrollPane(list); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(JBUI.size(660, 400));
        warning.setEditable(false); warning.setLineWrap(true); warning.setWrapStyleWord(true);
        warning.setFont(status.getFont()); warning.setForeground(SessionTheme.ERROR);
        warning.getAccessibleContext().setAccessibleName("Confirm discarded worktree files");
        confirmation.setPreferredSize(JBUI.size(660, 120)); confirmation.setVisible(false);
        var footer = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        footer.add(confirmation, BorderLayout.CENTER); footer.add(status, BorderLayout.SOUTH);
        content.add(top, BorderLayout.NORTH); content.add(scroll, BorderLayout.CENTER); content.add(footer, BorderLayout.SOUTH);
        init(); setCancelButtonText("Close"); setOKButtonText("Remove selected (0)"); setOKActionEnabled(false);
        scan();
    }
    public static void open(Project project) {
        var existing = project.getUserData(OPEN);
        if (existing != null && !existing.isDisposed()) { existing.getWindow().setVisible(true); existing.getWindow().toFront(); return; }
        var dialog = new WorktreeCleanupDialog(project); project.putUserData(OPEN, dialog); dialog.show();
    }
    @Override protected JComponent createCenterPanel() { return content; }
    @Override protected String getDimensionServiceKey() { return "CodexTabs.WorktreeCleanup"; }
    private void scan() {
        scanning = true; rows.clear(); list.removeAll(); setErrorText(null); status.setText("Checking worktrees…"); updateActions();
        list.revalidate(); list.repaint();
        service.cleanupCandidates().whenComplete((result, error) -> ui(() -> {
            scanning = false;
            if (error != null) { status.setText("Could not check worktrees. Refresh to try again."); setErrorText(CodexService.message(error)); updateActions(); return; }
            String repository = "";
            for (var tree : result.worktrees()) {
                if (!tree.repositoryPath().equals(repository)) {
                    repository = tree.repositoryPath(); var group = label(tree.repository(), SessionTheme.TEXT); group.setToolTipText(repository);
                    group.setFont(group.getFont().deriveFont(Font.BOLD)); group.setBorder(JBUI.Borders.empty(12, 8, 4, 8));
                    group.setAlignmentX(Component.LEFT_ALIGNMENT); list.add(group);
                }
                var row = new Row(tree); rows.add(row); list.add(row.panel);
                row.selected.addActionListener(event -> updateActions());
            }
            list.add(Box.createVerticalGlue());
            status.setText(rows.isEmpty() ? "No worktrees to clean up." : rows.size() + " worktrees checked. Review your selection before removing.");
            if (!result.warnings().isEmpty()) { setErrorText(String.join("\n", result.warnings())); }
            list.revalidate(); list.repaint(); updateActions();
        }));
    }
    private void updateActions() {
        boolean busy = scanning || removing || confirming;
        long selected = rows.stream().filter(row -> row.selected.isSelected() && !row.removed).count();
        setOKButtonText(removing ? "Removing…" : confirming ? "Discard changes and remove (" + selected + ")" : "Remove selected (" + selected + ")");
        setOKActionEnabled(!scanning && !removing && selected > 0);
        refresh.setEnabled(!busy); selectClean.setEnabled(!busy && !rows.isEmpty()); clear.setEnabled(!busy && selected > 0);
        rows.forEach(row -> row.selected.setEnabled(!busy && !row.removed && row.tree.blocked().isBlank()));
        setCancelButtonText(removing ? "Hide" : confirming ? "Cancel" : "Close");
    }
    @Override protected void doOKAction() {
        if (scanning || removing) { return; }
        var selected = rows.stream().filter(row -> row.selected.isSelected() && !row.removed && row.tree.blocked().isBlank()).toList();
        if (selected.isEmpty()) { return; }
        var dirty = selected.stream().filter(row -> !row.tree.files().isEmpty()).toList();
        if (!dirty.isEmpty() && !confirming) {
            warning.setText("Permanently discard all local changes, untracked files, and ignored files in these worktrees?\n\n"
                + String.join("\n", dirty.stream().map(row -> row.tree.path()).toList()) + "\n\nBranches and archived chats will be kept.");
            warning.setCaretPosition(0); confirmation.setVisible(true); confirming = true;
            status.setText("Review the worktrees above, then confirm discard or cancel.");
            content.revalidate(); updateActions(); return;
        }
        confirming = false; confirmation.setVisible(false);
        removing = true; setErrorText(null); updateActions();
        // The batch outlives a hidden dialog. Git work and waits never run on the event thread.
        CompletableFuture.runAsync(() -> {
            int removed = 0;
            for (int index = 0; index < selected.size(); index++) {
                if (project.isDisposed()) { break; }
                var row = selected.get(index); int current = index + 1;
                ui(() -> { row.state.setText("Removing…"); status.setText("Removing " + current + " of " + selected.size() + ". You can hide this window."); });
                try {
                    service.removeUnusedWorktree(row.tree.path(), !row.tree.files().isEmpty()).join(); removed++;
                    ui(() -> { row.removed = true; row.selected.setSelected(false); row.state.setText("Removed"); row.state.setForeground(SessionTheme.SUCCESS); row.detail.setText("Worktree removed. The branch and archived chats were kept."); row.inspect.setText("Details"); });
                } catch (Exception error) {
                    String reason = CodexService.message(error);
                    ui(() -> { row.selected.setSelected(false); row.state.setText("Could not remove"); row.state.setForeground(SessionTheme.ERROR); row.detail.setText(reason); row.inspect.setText("Details"); row.inspect.setVisible(true); });
                }
            }
            int count = removed;
            ui(() -> { removing = false; status.setText("Removed " + count + " of " + selected.size() + " worktrees." + (count < selected.size() ? " Review the errors or refresh to try again." : "")); updateActions(); });
        });
    }
    @Override public void doCancelAction() {
        if (removing) { getWindow().setVisible(false); }
        else if (confirming) {
            confirming = false; confirmation.setVisible(false); content.revalidate();
            status.setText("Nothing was removed. Review your selection."); updateActions();
        } else { super.doCancelAction(); }
    }
    @Override protected void dispose() {
        if (project.getUserData(OPEN) == this) { project.putUserData(OPEN, null); }
        super.dispose();
    }
    private void ui(Runnable action) { ApplicationManager.getApplication().invokeLater(() -> { if (!isDisposed() && !project.isDisposed()) { action.run(); } }); }
    private static JBLabel label(String text, Color color) {
        var label = new JBLabel(text); label.putClientProperty("html.disable", true); label.setForeground(color); return label;
    }
}
