package com.acorn.codextabs.core;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalUsageHistoryTest {
    @TempDir Path root;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T19:00:00Z"), ZoneId.of("America/Los_Angeles"));
    private Path file(String id) throws Exception {
        var directory = root.resolve("sessions/2026/10/19"); Files.createDirectories(directory);
        var file = directory.resolve("rollout-" + id + ".jsonl");
        append(file, object("type", "session_meta", "payload", object("id", id, "model_provider", LocalModel.PROVIDER)));
        model(file, LocalModel.MODEL); return file;
    }
    private void model(Path file, String model) throws Exception { append(file, object("type", "turn_context", "payload", object("model", model))); }
    private void append(Path file, JsonObject event) throws Exception { Files.writeString(file, GSON.toJson(event) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
    private JsonObject counts(long in, long out, Object cached) { return object("input_tokens", in, "output_tokens", out, "cached_input_tokens", cached); }
    private void usage(Path file, String at, JsonObject total, JsonObject last) throws Exception {
        append(file, object("type", "event_msg", "timestamp", at, "payload", object("type", "token_count", "info", object("total_token_usage", total, "last_token_usage", last))));
    }
    private JsonObject read(String thread) throws Exception { return LocalUsageHistory.read(root, root.resolve("index.json"), thread, clock); }
    private JsonObject period(JsonObject usage, String name) { return obj(obj(usage, "periods"), name); }
    private long input(JsonObject usage, String period) { return period(usage, period).get("input").getAsLong(); }

    @Test void includesConfiguredLocalIdsAndKeepsThemAfterChangingModels() throws Exception {
        var file = file("other-local"); model(file, "qwen-coder");
        usage(file, "2026-10-19T10:00:00Z", counts(100, 20, 70), counts(100, 20, 70));
        assertEquals(0, input(read("other-local"), "allTime"));
        var result = LocalUsageHistory.read(root, root.resolve("index.json"), "other-local", clock, java.util.Set.of("qwen-coder"));
        assertEquals(100, input(result, "allTime"));
        usage(file, "2026-10-19T11:00:00Z", counts(150, 30, 90), counts(50, 10, 20));
        result = LocalUsageHistory.read(root, root.resolve("index.json"), "other-local", clock, java.util.Set.of("llama-coder"));
        assertEquals(150, input(result, "allTime"));
        assertEquals(result.get("periods"), read("other-local").get("periods"));
    }
    @Test void countsNewUsageOnceAcrossRepeatedReadsDuplicateEventsAndAppends() throws Exception {
        var file = file("local"); var first = counts(100, 20, 70);
        usage(file, "2026-10-19T10:00:00Z", first, first);
        usage(file, "2026-10-19T10:01:00Z", first, first);
        var result = read("local");
        assertEquals(100, input(result, "today")); assertEquals(120, period(result, "allTime").get("total").getAsLong());
        assertEquals(70, period(result, "today").get("cached").getAsLong());
        assertEquals(result.get("periods"), read("local").get("periods"));
        usage(file, "2026-10-19T11:00:00Z", counts(150, 30, 100), counts(50, 10, 30));
        assertEquals(150, input(read("local"), "thread"));
    }
    @Test void usesLocalCalendarDaysMondayWeeksAndCalendarMonths() throws Exception {
        var file = file("calendar");
        usage(file, "2026-09-30T12:00:00Z", counts(10, 1, 0), counts(10, 1, 0));
        usage(file, "2026-10-18T23:00:00Z", counts(30, 3, 0), counts(20, 2, 0));
        usage(file, "2026-10-19T06:59:00Z", counts(60, 6, 0), counts(30, 3, 0));
        usage(file, "2026-10-19T07:01:00Z", counts(100, 10, 0), counts(40, 4, 0));
        var result = read("calendar");
        assertEquals(40, input(result,"today")); assertEquals(40,input(result,"week"));
        assertEquals(90,input(result,"month")); assertEquals(100,input(result,"allTime"));
    }
    @Test void excludesCloudRequestsAndDoesNotDoubleCountMovedOrCopiedArchives() throws Exception {
        var file = file("mixed");
        usage(file, "2026-10-19T10:00:00Z", counts(10, 1, 0), counts(10, 1, 0));
        model(file, "cloud"); usage(file,"2026-10-19T10:01:00Z",counts(50,5,0),counts(40,4,0));
        model(file,LocalModel.MODEL); usage(file,"2026-10-19T10:02:00Z",counts(80,8,0),counts(30,3,0));
        assertEquals(40,input(read("mixed"),"allTime"));
        model(file, "cloud"); usage(file,"2026-10-19T10:03:00Z",counts(1,1,0),counts(1,1,0));
        assertEquals(0, read("mixed").get("counterResets").getAsLong(), "Cloud counter resets must not mark local totals incomplete");
        Path archived=root.resolve("archived_sessions"); Files.createDirectories(archived);
        Files.copy(file,archived.resolve(file.getFileName()));
        assertEquals(40,input(read("mixed"),"allTime"));
        Files.delete(file); assertEquals(40,input(read("mixed"),"allTime"));
    }
    @Test void inheritedForkEventsOnlySeedTheCounterAndDoNotCountParentUsageAgain() throws Exception {
        var parent = file("parent");
        usage(parent,"2026-10-19T10:00:00Z",counts(100,10,70),counts(100,10,70));
        var fork = parent.resolveSibling("rollout-fork.jsonl");
        append(fork,object("type","session_meta","timestamp","2026-10-19T11:00:00Z","payload",object("id","fork","model_provider",LocalModel.PROVIDER,"forked_from_id","parent")));
        model(fork,LocalModel.MODEL);
        usage(fork,"2026-10-19T10:00:00Z",counts(100,10,70),counts(100,10,70));
        usage(fork,"2026-10-19T12:00:00Z",counts(150,15,100),counts(50,5,30));
        var result = read("fork");
        assertEquals(50,input(result,"thread")); assertEquals(150,input(result,"allTime"));
    }
    @Test void skipsIncompleteLastLinesThenReadsThemOnceCompleted() throws Exception {
        var file=file("partial");
        var event=object("type","event_msg","timestamp","2026-10-19T10:00:00Z","payload",object("type","token_count","info",object("total_token_usage",counts(5,1,0),"last_token_usage",counts(5,1,0))));
        Files.writeString(file,GSON.toJson(event),StandardOpenOption.APPEND);
        assertEquals(0,input(read("partial"),"today"));
        Files.writeString(file,"\n",StandardOpenOption.APPEND);
        assertEquals(5,input(read("partial"),"today"));
    }
    @Test void resetsAndMissingCacheCountsAreReportedWithoutInflatingTotals() throws Exception {
        var file=file("reset");
        usage(file,"2026-10-19T10:00:00Z",counts(100,10,null),counts(100,10,null));
        usage(file,"2026-10-19T11:00:00Z",counts(50,5,0),counts(20,2,0));
        usage(file,"2026-10-19T12:00:00Z",counts(60,6,5),counts(10,1,5));
        var result=read("reset");
        assertEquals(110,input(result,"allTime")); var cached = period(result,"allTime").get("cached"); assertTrue(cached == null || cached.isJsonNull());
        assertEquals(1,result.get("counterResets").getAsLong());
    }
}
