package com.acorn.codextabs.smoke;

import com.acorn.codextabs.core.*;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Measure repeated PR searches over a fixed large transcript fixture, without an IDE or model. */
public final class PrSearchBenchmark {
    public static void main(String[] args) {
        var chats = new ArrayList<Conversation>();
        String body = "Synthetic tool output for repeatable performance checks. ".repeat(87);
        for (int i = 0; i < 223; i++) {
            var chat = new Conversation("fixture-" + i, "/fixture/repo");
            for (int j = 0; j < 213; j++) {
                chat.event(object("method", "item/completed", "params", object("item", object("id", "tool-" + j, "type", "commandExecution", "aggregatedOutput", body))));
            }
            chat.event(object("method", "item/completed", "params", object("item", object("id", "pr", "type", "agentMessage", "text", "https://github.com/fixture/repo/pull/" + (8123 + i)))));
            chat.archive(i > 0); chats.add(chat);
        }
        for (int i = 0; i < 1000; i++) { search(chats); }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        int rounds = 2000;
        long beforeCpu = bean.getCurrentThreadCpuTime(), beforeBytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()), beforeWall = System.nanoTime();
        for (int i = 0; i < rounds; i++) { search(chats); }
        long elapsed = System.nanoTime() - beforeWall, cpu = bean.getCurrentThreadCpuTime() - beforeCpu, bytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeBytes;
        long indexBytes = 0;
        for (var chat : chats) { indexBytes += GSON.toJson(obj(chat.snapshot(), "pullRequestIndex")).getBytes(StandardCharsets.UTF_8).length; }
        System.out.println(GSON.toJson(object("chats", chats.size(), "items", chats.size() * 214, "rounds", rounds,
            "meanCpuMs", cpu / 1_000_000.0 / rounds, "meanWallMs", elapsed / 1_000_000.0 / rounds,
            "allocatedBytesPerSearch", bytes / rounds, "serializedIndexBytes", indexBytes)));
    }
    private static void search(List<Conversation> chats) {
        var query = PullRequestIndex.query("#8123");
        JsonObject result = null; int matches = 0;
        for (var chat : chats) { var found = chat.searchSummary(query); if (found != null) { result = found; matches++; } }
        if (matches != 1 || !text(result, "id").equals("fixture-0") || result.has("items")) { throw new AssertionError("Search output changed"); }
    }
    private PrSearchBenchmark() {}
}
