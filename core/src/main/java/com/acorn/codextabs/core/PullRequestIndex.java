package com.acorn.codextabs.core;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.acorn.codextabs.core.Json.*;

/** Store only PR references. Searches never copy or scan a conversation's transcript. */
public final class PullRequestIndex {
    private static final Pattern URL = Pattern.compile("https?://([a-z0-9.-]+(?::[0-9]+)?)/([a-z0-9_.-]+/[a-z0-9_.-]+)/pull/([1-9][0-9]*)(?![a-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUALIFIED = Pattern.compile("(?<![a-z0-9_./-])([a-z0-9_.-]+/[a-z0-9_.-]+)#([1-9][0-9]*)(?![a-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED = Pattern.compile("\\b(?:PR|pull request)\\s*#?\\s*([1-9][0-9]*)(?![a-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(?:pr\\s*[:#]?\\s*|#)?([1-9][0-9]*)", Pattern.CASE_INSENSITIVE);
    private final Set<String> references = new LinkedHashSet<>();

    public record Query(String text, String number, String repository, String host) {
        public boolean isPullRequest() { return !number.isEmpty(); }
        private boolean matches(String reference) {
            int separator = reference.lastIndexOf('#');
            if (reference.length() - separator - 1 != number.length() || !reference.regionMatches(separator + 1, number, 0, number.length())) { return false; }
            if (repository.isEmpty()) { return true; }
            String location = reference.substring(0, separator);
            if (!host.isEmpty()) { return location.equals(host + "/" + repository); }
            return repository.isEmpty() || location.equals(repository) || location.endsWith("/" + repository);
        }
    }
    public static Query query(String input) {
        String text = input.strip().toLowerCase(Locale.ROOT);
        var url = URL.matcher(text);
        if (url.lookingAt() && (url.end() == text.length() || "/?#".indexOf(text.charAt(url.end())) >= 0)) { return new Query(text, url.group(3), url.group(2), url.group(1)); }
        var qualified = QUALIFIED.matcher(text);
        if (qualified.matches()) { return new Query(text, qualified.group(2), qualified.group(1), ""); }
        var number = NUMBER.matcher(text);
        return number.matches() ? new Query(text, number.group(1), "", "") : new Query(text, "", "", "");
    }
    public void add(String text) {
        if (text.isEmpty()) { return; }
        var urls = URL.matcher(text);
        while (urls.find()) { references.add((urls.group(1) + "/" + urls.group(2) + "#" + urls.group(3)).toLowerCase(Locale.ROOT)); }
        var qualified = QUALIFIED.matcher(text);
        while (qualified.find()) { references.add((qualified.group(1) + "#" + qualified.group(2)).toLowerCase(Locale.ROOT)); }
        var named = NAMED.matcher(text);
        while (named.find()) { references.add("#" + named.group(1)); }
    }
    /** Skip images, encoded files, reasoning, and unrelated tool fields. */
    public void addItem(JsonObject item) {
        switch (text(item, "type")) {
            case "userMessage", "agentMessage" -> { add(text(item, "text")); content(item.get("content")); }
            case "commandExecution" -> { add(text(item, "command")); add(text(item, "aggregatedOutput")); }
            case "mcpToolCall", "dynamicToolCall" -> { content(item.get("result")); content(item.get("contentItems")); }
            default -> { }
        }
    }
    private void content(JsonElement value) {
        if (value == null || value.isJsonNull()) { return; }
        if (value.isJsonPrimitive()) { if (value.getAsJsonPrimitive().isString()) { add(value.getAsString()); } }
        else if (value.isJsonArray()) { value.getAsJsonArray().forEach(this::content); }
        else {
            var object = value.getAsJsonObject();
            if (Set.of("image", "localImage", "input_image", "resource").contains(text(object, "type"))) { return; }
            add(text(object, "text")); add(text(object, "url")); add(text(object, "html_url"));
            content(object.get("content")); content(object.get("structuredContent"));
        }
    }
    public String match(Query query) {
        // Prefer a repository name over a bare number when both were mentioned.
        String match = "";
        for (String reference : references) { if (query.matches(reference) && reference.length() > match.length()) { match = reference; } }
        return match;
    }
    public JsonObject snapshot() { return object("version", 1, "references", references); }
    public boolean restore(JsonObject saved) {
        if (!text(saved, "version").equals("1") || !saved.has("references")) { return false; }
        for (var reference : array(saved, "references")) { references.add(reference.getAsString()); }
        return true;
    }
    public void clear() { references.clear(); }
}
