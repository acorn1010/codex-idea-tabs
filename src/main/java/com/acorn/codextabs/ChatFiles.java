package com.acorn.codextabs;

import com.intellij.openapi.vfs.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import java.awt.*;

/** Stable virtual URLs let IDEA restore chats into its own editor groups. No project files are created. */
public final class ChatFiles extends DeprecatedVirtualFileSystem {
    private final ConcurrentHashMap<String, ChatFile> files = new ConcurrentHashMap<>();
    @Override public String getProtocol() { return "codex-chat"; }
    @Override public VirtualFile findFileByPath(String path) {
        if (!path.matches("[^/]+/[A-Za-z0-9_-]+")) { return null; }
        return files.computeIfAbsent(path, value -> new ChatFile(value, this));
    }
    @Override public void refresh(boolean asynchronous) {}
    @Override public VirtualFile refreshAndFindFileByPath(String path) { return findFileByPath(path); }
    @Override public boolean isReadOnly() { return true; }

    public static void open(Project project, String id, boolean split) {
        CodexService.get(project).changed(id);
        var fs = (ChatFiles) VirtualFileManager.getInstance().getFileSystem("codex-chat");
        var file = fs.findFileByPath(project.getLocationHash() + "/" + id);
        var manager = FileEditorManagerEx.getInstanceEx(project);
        if (split && manager.getCurrentWindow() != null) {
            manager.getCurrentWindow().split(SwingConstants.VERTICAL, true, file, true, false, true);
        } else { manager.openFile(file, true); }
        var window = manager.getCurrentWindow();
        if (window != null) { window.setFilePinned(file, true); }
    }
    public static final class ChatFile extends LightVirtualFile {
        public final String id;
        private final String path;
        private final ChatFiles fs;
        private ChatFile(String path, ChatFiles fs) {
            super("Codex", PlainTextFileType.INSTANCE, "");
            this.path = path; this.fs = fs; id = path.substring(path.indexOf('/') + 1);
        }
        @Override public String getPath() { return path; }
        @Override public VirtualFileSystem getFileSystem() { return fs; }
        @Override public boolean isWritable() { return false; }
    }
    public static final class Titles implements EditorTabTitleProvider {
        @Override public String getEditorTabTitle(Project project, VirtualFile file) {
            if (!(file instanceof ChatFile chat)) { return null; }
            return CodexService.get(project).chat(chat.id).get("title");
        }
        @Override public String getEditorTabTooltipText(Project project, VirtualFile file) {
            if (!(file instanceof ChatFile chat)) { return null; }
            var state = CodexService.get(project).chat(chat.id);
            return state.get("title") + " · " + state.status() + " · " + state.get("cwd");
        }
    }
    public static final class Icons implements FileIconProvider {
        @Override public Icon getIcon(VirtualFile file, int flags, Project project) {
            if (!(file instanceof ChatFile chat) || project == null) { return null; }
            return icon(CodexService.get(project).chat(chat.id).status());
        }
    }
    public static Icon icon(String status) {
        return new Icon() {
            @Override public int getIconWidth() { return 16; }
            @Override public int getIconHeight() { return 16; }
            @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(switch (status) { case "working" -> new Color(0x62AEF7); case "attention" -> new Color(0xE0AB57); case "complete" -> new Color(0x6BC49A); case "error" -> new Color(0xE97B7B); default -> new Color(0x9DA6B6); });
                g.setStroke(new BasicStroke(1.7f));
                if (status.equals("working")) { g.drawArc(x + 2, y + 2, 11, 11, 25, 280); g.fillOval(x + 11, y + 2, 3, 3); }
                else if (status.equals("attention")) { g.drawOval(x + 2, y + 2, 11, 11); g.drawLine(x + 8, y + 5, x + 8, y + 8); g.fillOval(x + 7, y + 10, 2, 2); }
                else if (status.equals("complete")) { g.drawLine(x + 3, y + 8, x + 6, y + 11); g.drawLine(x + 6, y + 11, x + 13, y + 4); }
                else { g.drawPolyline(new int[]{x + 6, x + 2, x + 6}, new int[]{y + 3, y + 8, y + 13}, 3); g.drawPolyline(new int[]{x + 10, x + 14, x + 10}, new int[]{y + 3, y + 8, y + 13}, 3); }
                g.dispose();
            }
        };
    }
}
