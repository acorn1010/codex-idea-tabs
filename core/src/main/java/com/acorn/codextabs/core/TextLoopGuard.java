package com.acorn.codextabs.core;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Compare long passages during streaming because a text loop may never finish an item or call a tool. */
final class TextLoopGuard {
    private static final int PASSAGE_WORDS = 48;
    private static final int RECENT_WORDS = 512;
    private static final int MAX_WORD_CHARS = 256;
    private static final int WARN_AT = 3;
    private final Map<String, Window> channels = new HashMap<>();
    private boolean recoveryRequested;

    void reset() { channels.clear(); recoveryRequested = false; }
    JsonObject observe(JsonObject memory, String channel, String delta) {
        if (recoveryRequested || flag(memory, "stopped") || delta.isEmpty()) { return object(); }
        var window = channels.computeIfAbsent(channel, ignored -> new Window());
        for (int i = 0; i < delta.length(); i++) {
            char character = delta.charAt(i);
            if (!Character.isWhitespace(character)) {
                if (window.word.length() < MAX_WORD_CHARS) { window.word.append(character); }
                else { window.oversized = true; }
                continue;
            }
            if (window.word.isEmpty()) { continue; }
            if (window.oversized) {
                // Opaque blobs are not prose. Do not compare truncated prefixes as if they were equal words.
                window.words.clear(); window.word.setLength(0); window.oversized = false; continue;
            }
            int repeats = window.word();
            if (repeats >= WARN_AT) {
                // Stop at the first warning even when a buffered delta contains an entire loop.
                recoveryRequested = true;
                return LocalLoopGuard.recovery(memory, true);
            }
        }
        return object();
    }
    private static final class Window {
        private final StringBuilder word = new StringBuilder();
        private final ArrayDeque<String> words = new ArrayDeque<>();
        private final ArrayDeque<String> recent = new ArrayDeque<>();
        private final Map<String, ArrayDeque<Long>> positions = new HashMap<>();
        private long offset;
        private boolean oversized;

        private int word() {
            words.addLast(word.toString()); word.setLength(0);
            if (words.size() > PASSAGE_WORDS) { words.removeFirst(); }
            if (words.size() < PASSAGE_WORDS) { return 0; }
            String key;
            try {
                key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join(" ", words).getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
            var matches = positions.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            matches.addLast(offset++); recent.addLast(key);
            if (recent.size() > RECENT_WORDS) {
                String expired = recent.removeFirst(); var previous = positions.get(expired);
                previous.removeFirst(); if (previous.isEmpty()) { positions.remove(expired); }
            }
            // Overlapping windows in one passage count once. Whitespace and transport chunk sizes do not matter.
            int repeats = 0; long next = -1;
            for (long position : matches) {
                if (position >= next) { repeats++; next = position + PASSAGE_WORDS; }
            }
            return repeats;
        }
    }
}
