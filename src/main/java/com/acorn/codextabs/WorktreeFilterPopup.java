package com.acorn.codextabs;

import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

/** A native worktree picker matches the chat menus while keeping IDEA's keyboard and popup behavior. */
final class WorktreeFilterPopup {
    record Option(String path, String label) {}

    static void show(JComponent trigger, List<Option> options, String selected, Consumer<String> choose) {
        var list = new JBList<>(options);
        list.setExpandableItemsEnabled(false);
        list.setBackground(SessionTheme.MENU);
        list.setFixedCellWidth(JBUI.scale(260));
        list.setFixedCellHeight(JBUI.scale(32));
        list.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        list.getAccessibleContext().setAccessibleName("Filter chats by worktree");
        var pressed = new int[]{-1};
        list.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) { pressed[0] = list.locationToIndex(event.getPoint()); list.repaint(); }
            }
            @Override public void mouseReleased(MouseEvent event) { pressed[0] = -1; list.repaint(); }
            @Override public void mouseExited(MouseEvent event) { pressed[0] = -1; list.repaint(); }
        });
        list.setCellRenderer((component, option, index, focused, focus) -> {
            var row = new JBLabel(option.label(), option.path().equals(selected) ? SessionIcons.icon("check") : null, SwingConstants.LEFT) {
                @Override protected void paintComponent(Graphics graphics) {
                    if (focused) {
                        var g = (Graphics2D) graphics.create();
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(pressed[0] == index ? SessionTheme.MENU_PRESSED : SessionTheme.MENU_HOVER);
                        g.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(12), JBUI.scale(12));
                        g.dispose();
                    }
                    super.paintComponent(graphics);
                }
            };
            row.setForeground(SessionTheme.TEXT);
            row.setFont(trigger.getFont().deriveFont(trigger.getFont().getSize2D() - 1));
            row.setIconTextGap(JBUI.scale(8));
            row.setBorder(JBUI.Borders.empty(6, option.path().equals(selected) ? 10 : 34, 6, 10));
            row.setToolTipText(option.label());
            row.getAccessibleContext().setAccessibleName(option.label() + (option.path().equals(selected) ? ", selected" : ""));
            return row;
        });
        var popup = JBPopupFactory.getInstance().createListPopupBuilder(list)
            .setSelectedValue(options.stream().filter(option -> option.path().equals(selected)).findFirst().orElse(options.getFirst()), true)
            .setItemChosenCallback(option -> choose.accept(option.path()))
            .setNamerForFiltering(Option::label)
            .setVisibleRowCount(Math.min(8, options.size()))
            .setRequestFocus(true)
            .setAccessibleName("Filter chats by worktree")
            .createPopup();
        // The row renderer owns selection and hover. Swing's list UI does not add a second highlight.
        list.setUI(new javax.swing.plaf.basic.BasicListUI());
        // The popup builder replaces list insets, so apply the final gutter after it runs.
        list.setBorder(JBUI.Borders.empty(4));
        popup.getContent().setBackground(SessionTheme.MENU);
        popup.show(new RelativePoint(trigger, new Point(0, trigger.getHeight() + JBUI.scale(6))));
    }

    private WorktreeFilterPopup() {}
}
