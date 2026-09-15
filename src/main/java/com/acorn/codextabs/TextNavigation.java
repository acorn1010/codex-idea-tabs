package com.acorn.codextabs;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.jcef.JBCefBrowser;
import static com.acorn.codextabs.core.Json.*;

/** Route IDEA's configured navigation shortcuts into the focused browser text field. */
final class TextNavigation {
    private volatile boolean focused;

    TextNavigation(JBCefBrowser browser) {
        for (String id : new String[]{"EditorPreviousWord", "EditorNextWord", "EditorLineStart", "EditorLineEnd"}) {
            register(browser, id);
            register(browser, id + "WithSelection");
        }
    }

    void focus(boolean value) { focused = value; }

    private void register(JBCefBrowser browser, String id) {
        var action = new DumbAwareAction() {
            @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void update(AnActionEvent event) { event.getPresentation().setEnabled(focused && !browser.isDisposed()); }
            @Override public void actionPerformed(AnActionEvent event) {
                if (!focused || browser.isDisposed()) { return; }
                browser.getCefBrowser().executeJavaScript("window.dispatchEvent(new CustomEvent('codex-text-navigation',{detail:"
                    + GSON.toJson(id) + "}));", browser.getCefBrowser().getURL(), 0);
            }
        };
        // The platform shortcut set follows the active keymap, including custom and selection bindings.
        action.registerCustomShortcutSet(ActionManager.getInstance().getAction(id).getShortcutSet(), browser.getComponent(), browser);
    }
}
