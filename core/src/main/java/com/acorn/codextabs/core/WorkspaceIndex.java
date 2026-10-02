package com.acorn.codextabs.core;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.acorn.codextabs.core.Json.*;

/** Reuse path matches until the repository catalog is replaced. */
public final class WorkspaceIndex {
    private record Entry(String path, JsonObject value) {}
    private final List<Entry> entries;
    private final Map<String, Optional<JsonObject>> matches = new ConcurrentHashMap<>();

    public WorkspaceIndex(JsonArray workspaces) {
        entries = workspaces.asList().stream().map(JsonElement::getAsJsonObject)
            .sorted(Comparator.comparingInt((JsonObject value) -> text(value, "path").length()).reversed())
            .map(value -> new Entry(GitWorktrees.normalized(text(value, "path")), value.deepCopy())).toList();
    }
    public Optional<JsonObject> find(String path) {
        return matches.computeIfAbsent(path, key -> {
            String child = GitWorktrees.normalized(key);
            return entries.stream().filter(entry -> child.equals(entry.path()) || child.startsWith(entry.path() + "/"))
                .map(Entry::value).findFirst();
        }).map(JsonObject::deepCopy);
    }
}
