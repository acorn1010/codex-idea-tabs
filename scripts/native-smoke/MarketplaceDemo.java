package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.Conversation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingConstants;
import static com.acorn.codextabs.core.Json.*;

/** Prepare real IDE views with fictional demo conversations for public Marketplace screenshots. */
final class MarketplaceDemo {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project);
        service.restoreReady().get(15, TimeUnit.SECONDS);
        service.settings().sharedGuidanceEnabled = false;
        service.settings().permissions = "ask";
        service.history("", "").get(20, TimeUnit.SECONDS);
        var review = chat(service, "Handle an empty search", "codex",
            "Handle an empty search without changing how matching trails are ranked. Add a focused test.",
            "The search now handles blank input before ranking.\n\n- Whitespace is trimmed once at the boundary.\n- An empty query returns the full trail list.\n- Matching queries keep the existing ranking.\n\n```ts\nconst query = input.trim();\nif (query.length === 0) {\n  return trails;\n}\nreturn rankTrails(trails, query);\n```\n\nThe focused tests cover empty input, whitespace, and matching results.");
        var diagram = chat(service, "Map the search flow", "codex",
            "Show how a search reaches the results list. Keep the diagram focused on the main path.",
            "The query is normalized before it reaches the search index. Empty input takes a separate path.\n\n```mermaid\nflowchart TD\n  A[Search input] --> B[Trim whitespace]\n  B --> C{Query empty?}\n  C -->|Yes| D[All trails]\n  C -->|No| E[Search index]\n  E --> F[Rank matches]\n  D --> G[Results list]\n  F --> G\n```\n\nThis keeps blank searches fast and gives ranking one clear entry point.");
        var claude = chat(service, "Review keyboard navigation", "claude",
            "Review the search box keyboard behavior. Check Escape, focus, and arrow-key navigation.",
            "I will check the input and results list together, then verify the focus behavior in the browser.\n\n1. Escape clears the query and keeps focus in the input.\n2. Arrow keys move through visible results.\n3. Enter opens the selected trail.\n\nI am checking the Escape handler first.");
        claude.set("claudeConnection", "connected");
        claude.set("claudeSettingsSupported", true);
        claude.set("claudeModels", array(object("id", "sonnet", "model", "sonnet", "displayName", "Sonnet", "supportedReasoningEfforts", array())));
        claude.set("model", "sonnet");
        claude.set("modelSelectionSaved", true);
        claude.set("working", true);
        claude.set("claudeQueue", array(object("id", "demo-queued", "payload", object("text", "Also check that focus stays in the search box after Escape."), "input", array())));
        claude.set("draft", "Start with keyboard-only navigation.");
        var docs = chat(service, "Document the search API", "codex", "Document the search options.", "The search API notes are ready to review.");
        docs.set("unread", true);
        diagram.set("pinned", true);
        for (var entry : new Conversation[]{review, diagram, claude, docs}) { service.changed(entry.id); }
        Thread.sleep(12000);
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var frame = WindowManager.getInstance().getFrame(project);
            frame.setExtendedState(java.awt.Frame.NORMAL);
            frame.setBounds(60, 60, 1600, 1000);
            frame.setVisible(true);
            var manager = FileEditorManagerEx.getInstanceEx(project);
            manager.closeAllFiles();
            var source = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath() + "/Search.ts");
            if (source == null) { throw new IllegalStateException("Demo source file is missing"); }
            manager.openFile(source, true);
            var fs = VirtualFileManager.getInstance().getFileSystem("codex-chat");
            manager.getCurrentWindow().split(SwingConstants.VERTICAL, true,
                fs.findFileByPath(project.getLocationHash() + "/" + review.id), true, false, true);
            var projectTree = ToolWindowManager.getInstance(project).getToolWindow("Project");
            if (projectTree != null) { projectTree.hide(); }
            var sidebar = ToolWindowManager.getInstance(project).getToolWindow("Codex");
            if (sidebar != null) { sidebar.show(); }
        }, ModalityState.any());
        Files.writeString(output.resolve("marketplace-ready.txt"), "overview\n");
        String previous = "overview";
        while (!project.isDisposed()) {
            Thread.sleep(250);
            var request = output.resolve("marketplace-scene.txt");
            if (!Files.exists(request)) { continue; }
            String scene = Files.readString(request).trim();
            if (scene.equals(previous)) { continue; }
            Conversation selected = switch (scene) { case "diagram" -> diagram; case "claude" -> claude; case "overview" -> review; default -> null; };
            if (selected == null) { continue; }
            ApplicationManager.getApplication().invokeAndWait(() -> ChatFiles.open(project, selected.id, false), ModalityState.any());
            previous = scene;
            Files.writeString(output.resolve("marketplace-ready.txt"), scene + "\n");
        }
    }
    private static Conversation chat(CodexService service, String title, String provider, String prompt, String answer) {
        var chat = service.create();
        chat.set("provider", provider);
        chat.set("title", title);
        chat.set("renamed", true);
        chat.replaceClaudeHistory(array(object("id", "demo-question", "type", "userMessage", "text", prompt),
            object("id", "demo-answer", "type", "agentMessage", "text", answer)));
        chat.set("preview", prompt);
        return chat;
    }
    private static com.google.gson.JsonArray array(com.google.gson.JsonElement... values) {
        var result = new com.google.gson.JsonArray();
        for (var value : values) { result.add(value); }
        return result;
    }
    private MarketplaceDemo() {}
}
