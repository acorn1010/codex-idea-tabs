package com.acorn.codextabs;

import com.intellij.ui.JBColor;
import java.awt.Color;

/** Neutral surfaces and semantic accents keep the chat list legible in either IDE theme. */
final class SessionTheme {
    static final Color TEXT = new JBColor(0x252A34, 0xDFE3EB);
    static final Color MUTED = new JBColor(0x687181, 0x969EAC);
    static final Color MENU = new JBColor(0xECEEF2, 0x292C31);
    static final Color MENU_HOVER = new JBColor(0xF7F8FA, 0x202226);
    static final Color MENU_PRESSED = new JBColor(0xD9DDE5, 0x363B43);
    static final Color HOVER = new JBColor(0xE8ECF2, 0x2B303A);
    static final Color PRESSED = new JBColor(0xCFDAEC, 0x3D4A61);
    static final Color SELECTED = new JBColor(0xE0E8F6, 0x303C51);
    static final Color LINE = new JBColor(0xDFE3EA, 0x323741);
    static final Color ACCENT = new JBColor(0x3665C4, 0x90B6FF);
    static final Color ATTENTION = new JBColor(0x976313, 0xE7B869);
    static final Color SUCCESS = new JBColor(0x327555, 0x85C5A2);
    static final Color ERROR = new JBColor(0xAC3D3D, 0xEA9393);
    static final Color PRIMARY = new JBColor(0x3769C7, 0x3E68BC);
    static final Color PRIMARY_HOVER = new JBColor(0x4277D9, 0x4C79D0);
    static final Color PRIMARY_PRESSED = new JBColor(0x2C55A8, 0x33579C);
    private SessionTheme() {}
}
