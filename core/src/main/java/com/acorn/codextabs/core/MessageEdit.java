package com.acorn.codextabs.core;

import com.google.gson.*;
import static com.acorn.codextabs.core.Json.*;

/** Builds a revised turn without changing the source chat or replaying its later instructions. */
public record MessageEdit(String lastTurnId, JsonArray input) {
    public static MessageEdit prepare(JsonArray turns, String itemId, String replacement) {
        return prepare(turns, itemId, replacement, null);
    }
    /** An explicit image list replaces only this message's images. An omitted list keeps them. */
    public static MessageEdit prepare(JsonArray turns, String itemId, String replacement, JsonArray images) {
        if (images != null) {
            for (var value : images) {
                if (!value.isJsonObject()) { throw new IllegalArgumentException("Invalid image attachment."); }
                var image = value.getAsJsonObject();
                String type = text(image, "type");
                if (!(type.equals("localImage") && !text(image, "path").isBlank()) && !(type.equals("image") && !text(image, "url").isBlank())) {
                    throw new IllegalArgumentException("Choose a valid image attachment.");
                }
            }
        }
        String previousTurn = "";
        for (var entry : turns) {
            var turn = entry.getAsJsonObject();
            var input = new JsonArray();
            for (var value : array(turn, "items")) {
                var item = value.getAsJsonObject();
                boolean selected = text(item, "id").equals(itemId);
                if (!text(item, "type").equals("userMessage")) {
                    if (selected) { throw new IllegalArgumentException("Only your own messages can be edited."); }
                    continue;
                }
                if (selected) {
                    var revised = new JsonArray();
                    if (!replacement.isBlank()) { revised.add(object("type", "text", "text", replacement)); }
                    for (var part : array(item, "content")) {
                        String type = text(part.getAsJsonObject(), "type");
                        if (!type.equals("text") && (images == null || !type.equals("localImage") && !type.equals("image"))) { revised.add(part.deepCopy()); }
                    }
                    if (images != null) { revised.addAll(images.deepCopy()); }
                    if (revised.isEmpty()) { throw new IllegalArgumentException("Write a message or keep an attachment before resending."); }
                    input.addAll(revised);
                    return new MessageEdit(previousTurn, input);
                }
                // Codex forks at turn boundaries. Keep earlier steering input when replaying this turn.
                input.addAll(array(item, "content").deepCopy());
            }
            previousTurn = text(turn, "id");
        }
        throw new IllegalArgumentException("This message is not available in Codex history yet. Try again after it finishes saving.");
    }
}
