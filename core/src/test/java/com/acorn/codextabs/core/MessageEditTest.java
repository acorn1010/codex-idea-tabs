package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class MessageEditTest {
    @Test void editingAnOlderMessageBranchesBeforeItsTurnAndKeepsAttachments() {
        var original = user("edit", object("type", "text", "text", "Old prompt", "text_elements", new Object[]{object("old", "range")}), object("type", "localImage", "path", "/project/image.png"));
        var turns = turns(turn("first", user("before", textInput("Earlier context"))), turn("second", original, user("later", textInput("Do not replay this"))), turn("third", user("after", textInput("Later turn"))));
        String saved = GSON.toJson(turns);
        var edit = MessageEdit.prepare(turns, "edit", "New prompt");
        assertEquals("first", edit.lastTurnId());
        assertEquals(2, edit.input().size());
        assertEquals("New prompt", text(edit.input().get(0).getAsJsonObject(), "text"));
        assertFalse(edit.input().get(0).getAsJsonObject().has("text_elements"));
        assertEquals("/project/image.png", text(edit.input().get(1).getAsJsonObject(), "path"));
        assertEquals(saved, GSON.toJson(turns), "The original conversation must not change");
    }

    @Test void editingASteeringMessageReplaysEarlierUserInputsInTheSameTurn() {
        var turns = turns(turn("active", user("initial", textInput("Keep this instruction")), object("id", "tool", "type", "commandExecution"), user("steer", textInput("Old follow-up"), object("type", "image", "url", "https://example.com/image.png")), user("later", textInput("Not included"))));
        var edit = MessageEdit.prepare(turns, "steer", "Revised follow-up");
        assertEquals("", edit.lastTurnId());
        assertEquals(3, edit.input().size());
        assertEquals("Keep this instruction", text(edit.input().get(0).getAsJsonObject(), "text"));
        assertEquals("Revised follow-up", text(edit.input().get(1).getAsJsonObject(), "text"));
        assertEquals("image", text(edit.input().get(2).getAsJsonObject(), "type"));
    }

    @Test void rejectsMissingOrAssistantMessagesAndEmptyTextWithoutAttachments() {
        var turns = turns(turn("turn", user("user", textInput("Original")), object("id", "assistant", "type", "agentMessage", "text", "Reply")));
        assertThrows(IllegalArgumentException.class, () -> MessageEdit.prepare(turns, "missing", "New"));
        assertThrows(IllegalArgumentException.class, () -> MessageEdit.prepare(turns, "assistant", "New"));
        assertThrows(IllegalArgumentException.class, () -> MessageEdit.prepare(turns, "user", "  "));
    }

    @Test void imageOnlyMessagesCanBeEditedAndOriginalTextCanBeRemoved() {
        var image = object("type", "localImage", "path", "/image.png");
        var turns = turns(turn("turn", user("image", image)));
        assertEquals(2, MessageEdit.prepare(turns, "image", "Describe this").input().size());
        assertEquals(image, MessageEdit.prepare(turns, "image", "").input().get(0));
    }

    @Test void editedImagesReplaceOnlyTheSelectedMessagesImages() {
        var earlier = object("type", "localImage", "path", "/earlier.png");
        var old = object("type", "image", "url", "data:image/png;base64,OLD");
        var mention = object("type", "mention", "name", "Notes", "path", "/notes.txt");
        var turns = turns(turn("turn", user("before", textInput("Keep this"), earlier), user("edit", textInput("Old text"), old, mention), user("later", textInput("Do not include"))));
        String saved = GSON.toJson(turns);
        var images = new JsonArray();
        images.add(object("type", "localImage", "path", "/added.png"));
        var input = MessageEdit.prepare(turns, "edit", "New text", images).input();
        assertEquals(5, input.size());
        assertEquals(earlier, input.get(1), "Earlier steering images must stay");
        assertEquals(mention, input.get(3), "Non-image context must stay");
        assertEquals(images.get(0), input.get(4));
        assertFalse(input.contains(old), "Removed images must not reach the new turn");
        input.get(4).getAsJsonObject().addProperty("path", "/changed-copy.png");
        assertEquals("/added.png", text(images.get(0).getAsJsonObject(), "path"));
        assertEquals(saved, GSON.toJson(turns), "Editing must not change the original history");
    }

    @Test void explicitEmptyImageListRemovesAllImagesAndRejectsAnEmptyMessage() {
        var turns = turns(turn("turn", user("edit", object("type", "localImage", "path", "/old.png"))));
        var input = MessageEdit.prepare(turns, "edit", "Text only now", new JsonArray()).input();
        assertEquals(1, input.size());
        assertEquals("text", text(input.get(0).getAsJsonObject(), "type"));
        assertThrows(IllegalArgumentException.class, () -> MessageEdit.prepare(turns, "edit", " ", new JsonArray()));
        var images = new JsonArray(); images.add(object("type", "image", "url", "data:image/png;base64,NEW"));
        assertEquals(images, MessageEdit.prepare(turns, "edit", "", images).input(), "An image-only replacement is allowed");
    }

    @Test void rejectsNonImageAndIncompleteReplacementAttachments() {
        var turns = turns(turn("turn", user("edit", textInput("Original"))));
        for (var invalid : new JsonElement[]{new JsonPrimitive("bad"), object("type", "text", "text", "Sneak in extra text"), object("type", "localImage", "path", ""), object("type", "image", "url", "")}) {
            var images = new JsonArray(); images.add(invalid);
            assertThrows(IllegalArgumentException.class, () -> MessageEdit.prepare(turns, "edit", "New", images));
        }
    }

    private static JsonObject textInput(String text) { return object("type", "text", "text", text); }
    private static JsonObject user(String id, JsonObject... input) { return object("id", id, "type", "userMessage", "content", input); }
    private static JsonObject turn(String id, JsonObject... items) { return object("id", id, "items", items); }
    private static JsonArray turns(JsonObject... turns) { return GSON.toJsonTree(turns).getAsJsonArray(); }
}
