package com.acorn.codextabs;

import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** A consistent 16px line icon set avoids font-dependent toolbar glyphs. */
final class SessionIcons {
    static Icon icon(String name) {
        return new Icon() {
            @Override public int getIconWidth() { return JBUI.scale(16); }
            @Override public int getIconHeight() { return JBUI.scale(16); }
            @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
                var g = (Graphics2D) graphics.create();
                g.translate(x, y); g.scale(getIconWidth() / 16.0, getIconHeight() / 16.0);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(component.getForeground()); g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (name) {
                    case "branch" -> { g.drawLine(4, 2, 4, 10); g.drawOval(2, 10, 4, 4); g.drawOval(10, 2, 4, 4); g.drawArc(4, 4, 8, 8, 270, 90); }
                    case "plus" -> { g.drawLine(8, 3, 8, 13); g.drawLine(3, 8, 13, 8); }
                    case "refresh" -> { g.drawArc(3, 3, 10, 10, 45, 285); g.drawPolyline(new int[]{10, 14, 14}, new int[]{5, 5, 1}, 3); }
                    case "archive", "restore" -> {
                        g.drawPolyline(new int[]{2, 2, 14, 14}, new int[]{9, 14, 14, 9}, 4);
                        g.drawPolyline(new int[]{2, 5, 6, 10, 11, 14}, new int[]{9, 9, 11, 11, 9, 9}, 6);
                        g.drawLine(8, 2, 8, 8);
                        g.drawPolyline(new int[]{5, 8, 11}, new int[]{5, name.equals("restore") ? 2 : 8, 5}, 3);
                    }
                    case "more" -> { for (int i = 3; i <= 13; i += 5) { g.fillOval(i - 1, 7, 2, 2); } }
                    case "back" -> { g.drawLine(3, 8, 13, 8); g.drawPolyline(new int[]{7, 3, 7}, new int[]{4, 8, 12}, 3); }
                    case "pin" -> { g.drawPolyline(new int[]{5, 11, 10, 10, 12, 4, 6, 6, 5}, new int[]{2, 2, 4, 7, 9, 9, 7, 4, 2}, 9); g.drawLine(8, 9, 8, 14); }
                    default -> { g.drawOval(2, 2, 11, 11); g.drawLine(8, 5, 8, 8); g.fillOval(7, 10, 2, 2); }
                }
                g.dispose();
            }
        };
    }
    private SessionIcons() {}
}
