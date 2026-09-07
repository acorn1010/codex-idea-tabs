package com.acorn.codextabs;

import com.intellij.ui.JBColor;
import javax.swing.*;
import java.awt.*;

/** Small native toolbar buttons have distinct idle, hover, pressed, and keyboard-focus states. */
final class SessionButton extends JToggleButton {
    SessionButton(String label, boolean toggle, Runnable action) {
        super(label);
        setRolloverEnabled(true);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addActionListener(event -> { if (!toggle) { setSelected(false); } action.run(); });
        getModel().addChangeListener(event -> repaint());
    }
    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        boolean pressed = getModel().isArmed() && getModel().isPressed();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(pressed ? new JBColor(0xBDCCE0, 0x3D5169) : isSelected() ? new JBColor(0xD3E3F7, 0x30465F) : getModel().isRollover() ? new JBColor(0xE0E6EE, 0x363E4A) : new JBColor(0xF2F4F7, 0x272B32));
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
        g.setColor(hasFocus() || isSelected() ? new JBColor(0x3574C5, 0x77ABEE) : getModel().isRollover() ? new JBColor(0x9BAABE, 0x65748A) : new JBColor(0xC5CBD5, 0x454D5A));
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
        if (pressed) { g.translate(0, 1); }
        super.paintComponent(g);
        g.dispose();
    }
}
