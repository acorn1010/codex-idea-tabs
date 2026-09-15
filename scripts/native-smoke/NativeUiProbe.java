package com.acorn.codextabs.smoke;

import com.acorn.codextabs.ChatEditor;
import com.acorn.codextabs.ChatFiles;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.openapi.util.SystemInfo;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import javax.swing.SwingUtilities;
import static com.acorn.codextabs.core.Json.*;

/** Disposable-profile probe checks native cursor state with the actual mouse, beyond CSS assertions. */
public final class NativeUiProbe {
    public static void start(Project project) {
        if (!Boolean.getBoolean("codex.smoke.ui.probe")) { return; }
        Thread.startVirtualThread(() -> {
            var directory = Path.of(System.getProperty("idea.log.path"));
            var request = directory.resolve("ui-probe-request.json");
            String previous = "";
            while (!project.isDisposed()) {
                try {
                    Thread.sleep(100);
                    if (!Files.exists(request)) { continue; }
                    var command = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
                    if (text(command, "id").equals(previous)) { continue; }
                    previous = text(command, "id");
                    var result = object("id", previous);
                    try {
                        var target = new Component[1];
                        var browserPanel = new javax.swing.JComponent[1];
                        var mouse = new Point[1];
                        ApplicationManager.getApplication().invokeAndWait(() -> {
                            try {
                                for (var file : FileEditorManager.getInstance(project).getOpenFiles()) {
                                    if (!(file instanceof ChatFiles.ChatFile chat) || !chat.id.equals(text(command, "chatId"))) { continue; }
                                    var editor = (ChatEditor) FileEditorManager.getInstance(project).getEditors(file)[0];
                                    var field = ChatEditor.class.getDeclaredField("browser");
                                    field.setAccessible(true);
                                    var browser = (JBCefBrowser) field.get(editor);
                                    target[0] = browser.getCefBrowser().getUIComponent();
                                    browserPanel[0] = browser.getComponent();
                                    var window = SwingUtilities.getWindowAncestor(target[0]);
                                    window.toFront();
                                    var origin = target[0].getLocationOnScreen();
                                    double scale = target[0].getWidth() / command.get("viewportWidth").getAsDouble();
                                    mouse[0] = new Point(origin.x + (int) (command.get("x").getAsDouble() * scale), origin.y + (int) (command.get("y").getAsDouble() * scale));
                                    result.addProperty("component", target[0].getClass().getName());
                                    return;
                                }
                                throw new IllegalStateException("Test chat is not open");
                            } catch (Exception error) { throw new RuntimeException(error); }
                        });
                        if (text(command, "shortcut").equals("text-navigation")) {
                            ApplicationManager.getApplication().invokeAndWait(() -> {
                                int key = command.get("keyCode").getAsInt();
                                int modifiers = command.get("modifiers").getAsInt();
                                var stroke = javax.swing.KeyStroke.getKeyStroke(key, modifiers);
                                var matches = com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(browserPanel[0]).stream()
                                    .filter(action -> java.util.Arrays.stream(action.getShortcutSet().getShortcuts()).anyMatch(shortcut ->
                                        shortcut instanceof com.intellij.openapi.actionSystem.KeyboardShortcut keyboard
                                            && keyboard.getFirstKeyStroke().equals(stroke) && keyboard.getSecondKeyStroke() == null))
                                    .toList();
                                result.addProperty("matchingActions", matches.size());
                                for (var action : matches) {
                                    com.intellij.openapi.actionSystem.ex.ActionUtil.invokeAction(action, browserPanel[0], "CodexNavigationSmoke",
                                        new KeyEvent(target[0], KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED), null);
                                }
                            });
                            Files.writeString(directory.resolve("ui-probe-result.json"), GSON.toJson(result));
                            continue;
                        }
                        var robot = new Robot();
                        robot.mouseMove(mouse[0].x, mouse[0].y);
                        if (text(command, "shortcut").equals("ctrl-enter") || text(command, "shortcut").equals("up") && flag(command, "focus")) {
                            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                            robot.delay(100);
                        }
                        if (text(command, "shortcut").equals("up")) { robot.keyPress(KeyEvent.VK_UP); robot.keyRelease(KeyEvent.VK_UP); }
                        if (text(command, "shortcut").equals("ctrl-enter")) {
                            robot.keyPress(KeyEvent.VK_CONTROL);
                            robot.keyPress(KeyEvent.VK_ENTER);
                            robot.keyRelease(KeyEvent.VK_ENTER);
                            robot.keyRelease(KeyEvent.VK_CONTROL);
                        }
                        Thread.sleep(250);
                        ApplicationManager.getApplication().invokeAndWait(() -> {
                            result.addProperty("cursor", target[0].getCursor().getType());
                            result.addProperty("parentCursor", target[0].getParent().getCursor().getType());
                            result.addProperty("mouseX", mouse[0].x);
                            result.addProperty("mouseY", mouse[0].y);
                            if (SystemInfo.isWindows) { result.add("windows", windowsCursor()); }
                        });
                    } catch (Throwable error) { result.addProperty("error", error.toString()); }
                    Files.writeString(directory.resolve("ui-probe-result.json"), GSON.toJson(result));
                } catch (Exception ignored) { /* A writer may still be replacing the request file. */ }
            }
        });
    }
    private static JsonObject windowsCursor() {
        var user32 = NativeLibrary.getInstance("user32");
        try (var info = new Memory(16 + Native.POINTER_SIZE)) {
            info.setInt(0, (int) info.size());
            if (user32.getFunction("GetCursorInfo").invokeInt(new Object[]{info}) == 0) { throw new IllegalStateException("GetCursorInfo failed"); }
            long cursor = Pointer.nativeValue(info.getPointer(8));
            long text = Pointer.nativeValue(user32.getFunction("LoadCursorW").invokePointer(new Object[]{null, new Pointer(32513)}));
            long hand = Pointer.nativeValue(user32.getFunction("LoadCursorW").invokePointer(new Object[]{null, new Pointer(32649)}));
            return object("text", cursor == text, "hand", cursor == hand, "x", info.getInt(8 + Native.POINTER_SIZE), "y", info.getInt(12 + Native.POINTER_SIZE));
        }
    }
}
