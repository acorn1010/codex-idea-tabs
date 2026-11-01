package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.acorn.codextabs.core.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFileManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Restore real editor URLs after IDEA changes the project cache key, without starting a model. */
public final class ChatRestoreSmoke implements StartupActivity.DumbAware {
    @Override public void runActivity(Project project) {
        Thread.startVirtualThread(() -> {
            var editors = new ArrayList<ChatEditor>();
            Path output = Path.of(System.getProperty("idea.log.path"), "chat-restore-result.json");
            try {
                Path root = Path.of(PathManager.getConfigPath(), "codex-tabs");
                var saved = new Conversation("restored-chat", "/fixture");
                saved.set("provider", "claude"); saved.set("threadId", "saved-thread"); saved.set("title", "Saved conversation");
                saved.event(object("method", "item/completed", "params", object("item", object("id", "reply", "type", "agentMessage", "text", "Saved reply"))));
                var withDraft = Conversation.restore(object("id", "draft-chat", "cwd", "/fixture", "provider", "claude", "threadId", "draft-thread", "title", "Saved draft conversation"));
                var current = new Conversation("existing-chat", "/fixture"); current.set("title", "Current conversation"); current.set("threadId", "current-thread");
                var previous = new Conversation("existing-chat", "/fixture"); previous.set("title", "Old conversation"); previous.set("threadId", "old-thread");
                new ConversationStore(root.resolve("previous-project/chats.json")).save(List.of(saved, withDraft, previous));
                var draft = new Conversation("draft-chat", "/fixture"); draft.set("draft", "Keep my new draft");
                new ConversationStore(root.resolve(project.getLocationHash()).resolve("chats.json")).save(List.of(new Conversation(saved.id, "/fixture"), draft, current));
                var service = CodexService.get(project);
                service.restoreReady().get(20, TimeUnit.SECONDS);
                var fs = VirtualFileManager.getInstance().getFileSystem("codex-chat");
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    for (String id : List.of("restored-chat", "draft-chat", "existing-chat")) {
                        editors.add(new ChatEditor(project, (ChatFiles.ChatFile) fs.findFileByPath("previous-project/" + id)));
                    }
                }, ModalityState.any());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                while ((!service.chat(saved.id).get("threadId").equals("saved-thread") || !service.chat("draft-chat").get("threadId").equals("draft-thread")) && System.nanoTime() < deadline) { Thread.sleep(50); }
                check(service.chat(saved.id).get("title").equals("Saved conversation"), "Restored URL opened a New chat placeholder");
                check(service.chat(saved.id).items().size() == 1, "Restored transcript was lost");
                check(service.chat(saved.id).get("provider").equals("claude"), "Restored provider was lost");
                check(service.chat("draft-chat").get("draft").equals("Keep my new draft"), "Recovery replaced a new draft");
                check(service.chat("draft-chat").get("threadId").equals("draft-thread"), "New draft prevented session recovery");
                check(service.chat("existing-chat").get("threadId").equals("current-thread"), "Recovery overwrote an existing conversation");
                Path invalid = root.resolve("invalid-project/chats/index.json");
                Files.createDirectories(invalid.getParent()); Files.writeString(invalid, "not json");
                service.restoreReady("invalid-project", "existing-chat").get(2, TimeUnit.SECONDS);
                Files.writeString(output, GSON.toJson(object("passed", true, "oldProjectUrl", true, "transcript", true, "newDraft", true, "existingChat", true, "fallbackOnly", true)));
            } catch (Throwable error) {
                try { Files.writeString(output, GSON.toJson(object("passed", false, "error", error.toString()))); } catch (Exception ignored) { error.printStackTrace(); }
            } finally {
                ApplicationManager.getApplication().invokeAndWait(() -> editors.forEach(Disposer::dispose), ModalityState.any());
            }
        });
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
