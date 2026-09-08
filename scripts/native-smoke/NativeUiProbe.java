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
                        new Robot().mouseMove(mouse[0].x, mouse[0].y);
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
