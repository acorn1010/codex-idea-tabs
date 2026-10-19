package com.acorn.codextabs.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Read dated local usage from saved sessions. Cache file offsets without saving chat text. */
public final class LocalUsageHistory {
    private static final long SCAN_BYTES = 64L * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 64 * 1024 * 1024;
    private LocalUsageHistory() {}

    /** Return calendar totals and continue an interrupted index from complete JSON lines. */
    public static synchronized JsonObject read(Path home, Path cache, String thread, Clock clock) throws IOException {
        String source = home.toAbsolutePath().normalize().toString();
        var state = object("version", 1, "source", source, "zone", clock.getZone().getId(), "files", object());
        if (Files.exists(cache)) {
            try (var reader = Files.newBufferedReader(cache)) {
                var saved = JsonParser.parseReader(reader).getAsJsonObject();
                if (text(saved, "version").equals("1") && text(saved, "source").equals(source) && text(saved, "zone").equals(clock.getZone().getId())) { state = saved; }
            } catch (JsonParseException | IllegalStateException error) { throw new IOException("Saved local usage index could not be read.", error); }
        }
        var files = obj(state, "files");
        var paths = new TreeMap<String, Path>(Comparator.reverseOrder());
        for (String directory : List.of("sessions", "archived_sessions")) {
            Path root = home.resolve(directory);
            if (!Files.isDirectory(root)) { continue; }
            try (var walk = Files.walk(root)) {
                for (var path : walk.filter(p -> p.getFileName().toString().endsWith(".jsonl") && Files.isRegularFile(p)).toList()) {
                    String name = path.getFileName().toString();
                    Path previous = paths.get(name);
                    if (previous == null || Files.size(path) > Files.size(previous)) { paths.put(name, path); }
                }
            }
        }
        long remaining = SCAN_BYTES;
        var ordered = new ArrayList<>(paths.entrySet());
        ordered.sort(Comparator.comparing((Map.Entry<String, Path> entry) -> !thread.isBlank() && entry.getKey().contains(thread)).reversed());
        for (var entry : ordered) {
            var saved = obj(files, entry.getKey());
            long position = value(saved, "position", 0);
            if (Files.size(entry.getValue()) < position) { saved = object(); position = 0; }
            if (remaining > 0 && Files.size(entry.getValue()) > position) {
                long scanned = scan(entry.getValue(), saved, position, remaining, clock.getZone());
                remaining -= scanned;
                files.add(entry.getKey(), saved);
            }
        }
        boolean indexing = false;
        for (var entry : paths.entrySet()) {
            if (Files.size(entry.getValue()) > value(obj(files, entry.getKey()), "position", 0)) { indexing = true; break; }
        }
        Path parent = cache.toAbsolutePath().getParent(); Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "local-usage-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { GSON.toJson(state, writer); }
            Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        return summarize(files, thread, clock, indexing);
    }

    private static long scan(Path path, JsonObject saved, long start, long budget, ZoneId zone) throws IOException {
        long committed = start, consumed = 0;
        var line = new ByteArrayOutputStream();
        boolean oversized = false;
        try (var stream = Files.newInputStream(path)) {
            stream.skipNBytes(start);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                int from = 0;
                for (int at = 0; at < read; at++) {
                    if (buffer[at] != '\n') { continue; }
                    int length = at - from;
                    consumed += length + 1;
                    if (!oversized && line.size() + length <= MAX_LINE_BYTES) {
                        line.write(buffer, from, length);
                        accept(saved, line.toString(StandardCharsets.UTF_8), zone);
                    } else { saved.addProperty("unreadRecords", value(saved, "unreadRecords", 0) + 1); }
                    committed += consumed; consumed = 0; line.reset(); oversized = false; from = at + 1;
                    if (committed - start >= budget) { saved.addProperty("position", committed); return committed - start; }
                }
                int length = read - from;
                consumed += length;
                if (!oversized && line.size() + length <= MAX_LINE_BYTES) { line.write(buffer, from, length); }
                else { oversized = true; line.reset(); }
            }
        }
        saved.addProperty("position", committed);
        return committed - start;
    }

    private static void accept(JsonObject saved, String line, ZoneId zone) {
        if (!line.contains("token_count") && !line.contains("session_meta") && !line.contains("turn_context")) { return; }
        try {
            var frame = JsonParser.parseString(line).getAsJsonObject();
            var payload = obj(frame, "payload");
            switch (text(frame, "type")) {
                case "session_meta" -> {
                    saved.addProperty("thread", text(payload, "id"));
                    saved.addProperty("provider", text(payload, "model_provider"));
                    saved.addProperty("forked", !text(payload, "forked_from_id").isBlank());
                    saved.addProperty("createdAt", text(frame, "timestamp"));
                }
                case "turn_context" -> {
                    if (!text(payload, "model").isBlank()) { saved.addProperty("model", text(payload, "model")); }
                }
                case "event_msg" -> {
                    if (!text(payload, "type").equals("token_count")) { return; }
                    var info = obj(payload, "info");
                    var total = counters(obj(info, "total_token_usage"));
                    if (total == null) { return; }
                    var previous = counters(obj(saved, "previous"));
                    saved.add("previous", total);
                    // A fork can contain its parent's old usage events. Only new calls belong to the fork.
                    if (flag(saved, "forked") && !text(saved, "createdAt").isBlank()
                        && Instant.parse(text(frame, "timestamp")).isBefore(Instant.parse(text(saved, "createdAt")))) { return; }
                    JsonObject delta;
                    if (previous == null) {
                        delta = counters(obj(info, "last_token_usage"));
                        if (delta == null || flag(saved, "forked") && !delta.equals(total)) { return; }
                    } else {
                        if (value(total, "input", 0) < value(previous, "input", 0) || value(total, "output", 0) < value(previous, "output", 0)) {
                            if (local(saved)) { saved.addProperty("counterResets", value(saved, "counterResets", 0) + 1); } return;
                        }
                        delta = object("input", value(total, "input", 0) - value(previous, "input", 0), "output", value(total, "output", 0) - value(previous, "output", 0));
                        Long now = count(total, "cached"), before = count(previous, "cached");
                        delta.add("cached", now != null && before != null && now >= before ? GSON.toJsonTree(now - before) : JsonNull.INSTANCE);
                    }
                    long input = value(delta, "input", 0), output = value(delta, "output", 0);
                    if (input == 0 && output == 0) { return; }
                    if (!local(saved)) { return; }
                    Long cached = count(delta, "cached");
                    if (cached != null && cached > input) { delta.add("cached", JsonNull.INSTANCE); }
                    String day = Instant.parse(text(frame, "timestamp")).atZone(zone).toLocalDate().toString();
                    var days = obj(saved, "days");
                    var sums = obj(days, day);
                    add(sums, delta); days.add(day, sums); saved.add("days", days);
                }
            }
        } catch (RuntimeException error) { saved.addProperty("unreadRecords", value(saved, "unreadRecords", 0) + 1); }
    }

    private static boolean local(JsonObject saved) {
        String model = text(saved, "model");
        return model.isBlank() ? text(saved, "provider").equals(LocalModel.PROVIDER) : LocalModel.selected(model);
    }
    private static JsonObject counters(JsonObject input) {
        Long incoming = count(input, "input_tokens"), outgoing = count(input, "output_tokens");
        // The saved cursor uses short keys. Rollout events use the Codex snake_case fields.
        if (incoming == null) { incoming = count(input, "input"); }
        if (outgoing == null) { outgoing = count(input, "output"); }
        if (incoming == null || outgoing == null) { return null; }
        Long cached = count(input, input.has("cached_input_tokens") ? "cached_input_tokens" : "cached");
        return object("input", incoming, "output", outgoing, "cached", cached != null && cached <= incoming ? cached : JsonNull.INSTANCE);
    }
    private static Long count(JsonObject input, String name) {
        var value = input.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) { return null; }
        try { long count = value.getAsBigDecimal().longValueExact(); return count >= 0 ? count : null; }
        catch (ArithmeticException error) { return null; }
    }
    private static long value(JsonObject input, String name, long fallback) { Long count = count(input, name); return count == null ? fallback : count; }
    private static void add(JsonObject sums, JsonObject delta) {
        sums.addProperty("input", Math.addExact(value(sums, "input", 0), value(delta, "input", 0)));
        sums.addProperty("output", Math.addExact(value(sums, "output", 0), value(delta, "output", 0)));
        Long cached = count(delta, "cached");
        sums.addProperty("cached", Math.addExact(value(sums, "cached", 0), cached == null ? 0 : cached));
        sums.addProperty("cachedUnknown", flag(sums, "cachedUnknown") || flag(delta, "cachedUnknown") || cached == null && value(delta, "input", 0) > 0);
    }
    private static JsonObject publicCounts(JsonObject sums) {
        return object("input", value(sums, "input", 0), "output", value(sums, "output", 0), "cached", flag(sums, "cachedUnknown") ? JsonNull.INSTANCE : value(sums, "cached", 0),
            "total", Math.addExact(value(sums, "input", 0), value(sums, "output", 0)));
    }
    private static JsonObject summarize(JsonObject files, String thread, Clock clock, boolean indexing) {
        LocalDate today = LocalDate.now(clock), week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), month = today.withDayOfMonth(1);
        var current = object(); var daily = object(); var weekly = object(); var monthly = object(); var all = object();
        String since = ""; long skipped = 0, resets = 0;
        for (var entry : files.entrySet()) {
            var file = entry.getValue().getAsJsonObject();
            skipped += value(file, "unreadRecords", 0); resets += value(file, "counterResets", 0);
            for (var dayEntry : obj(file, "days").entrySet()) {
                String day = dayEntry.getKey(); var sums = dayEntry.getValue().getAsJsonObject();
                if (since.isBlank() || day.compareTo(since) < 0) { since = day; }
                add(all, sums);
                if (text(file, "thread").equals(thread)) { add(current, sums); }
                if (day.equals(today.toString())) { add(daily, sums); }
                if (day.compareTo(week.toString()) >= 0 && day.compareTo(today.toString()) <= 0) { add(weekly, sums); }
                if (day.compareTo(month.toString()) >= 0 && day.compareTo(today.toString()) <= 0) { add(monthly, sums); }
            }
        }
        return object("periods", object("thread", publicCounts(current), "today", publicCounts(daily), "week", publicCounts(weekly), "month", publicCounts(monthly), "allTime", publicCounts(all)),
            "since", since, "timeZone", clock.getZone().getId(), "indexing", indexing, "unreadRecords", skipped, "counterResets", resets, "updatedAt", clock.millis());
    }
}
