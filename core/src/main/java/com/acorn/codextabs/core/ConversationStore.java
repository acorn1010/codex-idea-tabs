package com.acorn.codextabs.core;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Atomic per-chat files keep an edited draft from rewriting every saved transcript. */
public final class ConversationStore {
    private record Saved(Conversation chat, long revision) {}
    private final Path legacy, directory, index;
    private final Map<String, Saved> saved = new HashMap<>();
    private List<String> indexedFiles;

    public ConversationStore(Path legacy) {
        this.legacy = legacy; directory = legacy.resolveSibling("chats"); index = directory.resolve("index.json");
    }
    public synchronized List<Conversation> load(Path fallback) throws IOException {
        var result = new ArrayList<Conversation>();
        if (Files.exists(index)) {
            JsonObject manifest;
            try (var reader = Files.newBufferedReader(index)) { manifest = JsonParser.parseReader(reader).getAsJsonObject(); }
            if (!text(manifest, "version").equals("1")) { throw new IOException("Unknown chat cache format"); }
            var files = new ArrayList<String>();
            for (var value : array(manifest, "files")) {
                String name = value.getAsString();
                if (!name.matches("[a-f0-9]{64}\\.json")) { throw new IOException("Invalid chat cache entry"); }
                Conversation chat;
                try (var reader = Files.newBufferedReader(directory.resolve(name))) { chat = Conversation.restore(JsonParser.parseReader(reader).getAsJsonObject()); }
                result.add(chat); files.add(name); saved.put(chat.id, new Saved(chat, chat.revision()));
            }
            indexedFiles = List.copyOf(files);
        } else {
            var source = Files.exists(legacy) ? legacy : fallback;
            if (Files.exists(source)) {
                try (var reader = new JsonReader(Files.newBufferedReader(source))) {
                    reader.beginArray();
                    while (reader.hasNext()) { result.add(Conversation.restore(JsonParser.parseReader(reader).getAsJsonObject())); }
                    reader.endArray();
                }
            }
        }
        return result;
    }
    public synchronized void save(Collection<Conversation> chats) throws IOException {
        Files.createDirectories(directory);
        var files = new ArrayList<String>();
        for (var chat : chats) {
            String filename = filename(chat.id); files.add(filename);
            var previous = saved.get(chat.id);
            if (previous != null && previous.chat() == chat && previous.revision() == chat.revision()) { continue; }
            var snapshot = chat.snapshot();
            write(directory.resolve(filename), snapshot);
            saved.put(chat.id, new Saved(chat, snapshot.get("revision").getAsLong()));
        }
        files.sort(String::compareTo);
        if (!files.equals(indexedFiles)) {
            write(index, object("version", 1, "files", files));
            indexedFiles = List.copyOf(files);
        }
    }
    private static String filename(String id) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8))) + ".json"; }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static void write(Path target, JsonElement value) throws IOException {
        var temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(temporary)) { GSON.toJson(value, writer); }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
