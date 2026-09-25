package com.acorn.codextabs.core;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Read Claude's local transcripts without changing its session files or credentials. */
public final class ClaudeHistory {
    private final Path projects;
    public ClaudeHistory(Path config) { projects = config.resolve("projects"); }
    private List<Path> files() throws IOException {
        if (!Files.isDirectory(projects)) { return List.of(); }
        try (var paths = Files.walk(projects, 2)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().matches("[0-9a-fA-F-]{36}\\.jsonl")).toList();
        }
    }
    public JsonArray list(Set<String> directories, String search) throws IOException {
        var found = new ArrayList<JsonObject>();
        for (var file : files()) {
            var meta = object("id", file.getFileName().toString().replace(".jsonl", ""), "provider", "claude");
            lines(file, frame -> {
                if (flag(frame, "isSidechain")) { return; }
                if (!text(frame, "cwd").isBlank()) { meta.addProperty("cwd", text(frame, "cwd")); }
                if (text(frame, "type").equals("custom-title")) { meta.addProperty("name", text(frame, "customTitle")); }
                if (!meta.has("preview") && text(frame, "type").equals("user")) {
                    String prompt = prompt(obj(frame, "message").get("content"));
                    if (!prompt.isBlank()) { meta.addProperty("preview", prompt.substring(0, Math.min(160, prompt.length()))); }
                }
            });
            if (!meta.has("preview") || !directories.contains(text(meta, "cwd"))) { continue; }
            if (!meta.has("name")) { meta.addProperty("name", text(meta, "preview")); }
            if (!(text(meta, "name") + " " + text(meta, "preview")).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))) { continue; }
            meta.addProperty("updatedAt", Files.getLastModifiedTime(file).toMillis() / 1000); found.add(meta);
        }
        found.sort(Comparator.comparingLong((JsonObject value) -> value.get("updatedAt").getAsLong()).reversed());
        return GSON.toJsonTree(found).getAsJsonArray();
    }
    public JsonArray read(String id) throws IOException {
        UUID.fromString(id);
        var file = files().stream().filter(path -> path.getFileName().toString().equals(id + ".jsonl")).max(Comparator.comparingLong(path -> path.toFile().lastModified()));
        if (file.isEmpty()) { return new JsonArray(); }
        var nodes = new LinkedHashMap<String, JsonObject>();
        String[] leaf = {""};
        lines(file.get(), frame -> {
            String uuid = text(frame, "uuid");
            if (uuid.isBlank() || flag(frame, "isSidechain")) { return; }
            nodes.put(uuid, frame);
            if (Set.of("user", "assistant", "system").contains(text(frame, "type"))) { leaf[0] = uuid; }
        });
        var chain = new ArrayList<JsonObject>(); var seen = new HashSet<String>();
        for (String at = leaf[0]; nodes.containsKey(at) && seen.add(at); at = text(nodes.get(at), "parentUuid")) { chain.add(nodes.get(at)); }
        Collections.reverse(chain); return GSON.toJsonTree(chain).getAsJsonArray();
    }
    private static void lines(Path file, java.util.function.Consumer<JsonObject> consume) throws IOException {
        try (var input = Files.newBufferedReader(file)) {
            String line;
            while ((line = input.readLine()) != null) {
                try { var value = JsonParser.parseString(line); if (value.isJsonObject()) { consume.accept(value.getAsJsonObject()); } }
                catch (JsonParseException ignored) { /* A running CLI can leave its last line incomplete. */ }
            }
        }
    }
    public static String prompt(JsonElement content) {
        if (content == null || content.isJsonNull()) { return ""; }
        if (content.isJsonPrimitive()) { return content.getAsString(); }
        var parts = new ArrayList<String>();
        for (var entry : content.getAsJsonArray()) {
            var part = entry.getAsJsonObject(); String value = text(part, "text");
            if (text(part, "type").equals("text") && !value.stripLeading().startsWith("<codex_tabs_shared_guidance>")) { parts.add(value); }
        }
        return String.join("\n", parts);
    }
    public static JsonArray input(JsonObject frame) {
        var content = obj(frame, "message").get("content"); var input = new JsonArray();
        if (content == null) { return input; }
        if (content.isJsonPrimitive()) { input.add(object("type", "text", "text", content.getAsString())); return input; }
        for (var entry : content.getAsJsonArray()) {
            var part = entry.getAsJsonObject();
            if (text(part, "type").equals("text") && !text(part, "text").stripLeading().startsWith("<codex_tabs_shared_guidance>")) { input.add(part.deepCopy()); }
            if (text(part, "type").equals("image")) {
                var source = obj(part, "source");
                if (text(source, "type").equals("base64")) { input.add(object("type", "image", "url", "data:" + text(source, "media_type") + ";base64," + text(source, "data"))); }
                else if (text(source, "type").equals("url")) { input.add(object("type", "image", "url", text(source, "url"))); }
            }
        }
        return input;
    }
    /** Fork before the selected prompt, preserving the full chain entry preceding it. */
    public static JsonObject editPoint(JsonArray frames, String itemId) {
        String previous = "";
        for (var entry : frames) {
            var frame = entry.getAsJsonObject();
            if (text(frame, "uuid").equals(itemId) && text(frame, "type").equals("user") && !input(frame).isEmpty()) {
                return object("anchor", previous, "input", input(frame));
            }
            previous = text(frame, "uuid", previous);
        }
        throw new IllegalArgumentException("Claude has not saved this message yet. Try again after the turn finishes.");
    }
}
