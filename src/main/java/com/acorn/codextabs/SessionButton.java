package com.acorn.codextabs;

import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** Compact native controls share the sidebar's primary, quiet, hover, and pressed treatment. */
final class SessionButton extends JToggleButton {
    private final boolean primary;
    SessionButton(String label, String icon, boolean primary, Runnable action) {
        super(label);
        this.primary = primary;
        if (icon != null) { setIcon(SessionIcons.icon(icon)); }
        setIconTextGap(JBUI.scale(6));
        setRolloverEnabled(true); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false);
        setBorder(JBUI.Borders.empty(5, label.isEmpty() ? 6 : 10));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setForeground(primary ? Color.WHITE : SessionTheme.TEXT);
        addActionListener(event -> { setSelected(false); action.run(); });
        getModel().addChangeListener(event -> repaint());
    }
    @Override public Dimension getPreferredSize() {
        var metrics = getFontMetrics(getFont());
        int icon = getIcon() == null ? 0 : getIcon().getIconWidth() + (getText().isEmpty() ? 0 : getIconTextGap());
        return new Dimension(metrics.stringWidth(getText()) + icon + JBUI.scale(getText().isEmpty() ? 12 : 20), JBUI.scale(30));
    }
    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        boolean pressed = isEnabled() && getModel().isArmed() && getModel().isPressed();
        boolean hover = isEnabled() && getModel().isRollover();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color fill = primary ? pressed ? SessionTheme.PRIMARY_PRESSED : hover ? SessionTheme.PRIMARY_HOVER : SessionTheme.PRIMARY
            : pressed ? SessionTheme.PRESSED : isSelected() ? hover ? SessionTheme.PRESSED : SessionTheme.SELECTED : hover ? SessionTheme.HOVER : null;
        if (fill != null) {
            g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(8), JBUI.scale(8));
            if (primary && !pressed) {
                g.setColor(new Color(255, 255, 255, 32)); g.drawLine(JBUI.scale(5), 1, getWidth() - JBUI.scale(5), 1);
                g.setColor(new Color(0, 0, 0, 45)); g.drawLine(JBUI.scale(5), getHeight() - 1, getWidth() - JBUI.scale(5), getHeight() - 1);
            }
        }
        if (hasFocus()) { g.setColor(SessionTheme.ACCENT); g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, JBUI.scale(8), JBUI.scale(8)); }
        if (!isEnabled()) { g.setComposite(AlphaComposite.SrcOver.derive(0.45f)); }
        if (pressed) { g.translate(0, JBUI.scale(1)); }
        super.paintComponent(g); g.dispose();
    }
}
