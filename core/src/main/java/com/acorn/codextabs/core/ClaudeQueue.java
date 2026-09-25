package com.acorn.codextabs.core;

import com.google.gson.*;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Share the chat lock across queue controllers so cancellation and reconnect cannot lose prompts. */
public final class ClaudeQueue {
    private final Conversation chat;
    public ClaudeQueue(Conversation chat) { this.chat = chat; }
    public JsonObject add(JsonObject payload, JsonArray input) {
        synchronized (chat) {
            var queued = array(chat.snapshot(), "claudeQueue").deepCopy();
            var entry = object("id", UUID.randomUUID().toString(), "payload", payload, "input", input);
            queued.add(entry); chat.set("claudeQueue", queued); return entry;
        }
    }
    public JsonObject take() {
        synchronized (chat) {
            var queued = array(chat.snapshot(), "claudeQueue").deepCopy();
            if (queued.isEmpty()) { return null; }
            var next = queued.remove(0).getAsJsonObject(); chat.set("claudeQueue", queued); return next;
        }
    }
    public void retry(JsonObject entry) {
        synchronized (chat) {
            var queued = new JsonArray(); queued.add(entry.deepCopy()); queued.addAll(array(chat.snapshot(), "claudeQueue")); chat.set("claudeQueue", queued);
        }
    }
    public void remove(String id) {
        synchronized (chat) {
            var queued = array(chat.snapshot(), "claudeQueue").deepCopy();
            for (int i = queued.size() - 1; i >= 0; i--) { if (text(queued.get(i).getAsJsonObject(), "id").equals(id)) { queued.remove(i); } }
            chat.set("claudeQueue", queued);
        }
    }
    public boolean hasNext() { synchronized (chat) { return !array(chat.snapshot(), "claudeQueue").isEmpty(); } }
}
