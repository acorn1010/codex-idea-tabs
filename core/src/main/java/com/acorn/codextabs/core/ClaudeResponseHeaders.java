package com.acorn.codextabs.core;

import com.google.gson.*;
import java.util.List;
import java.util.regex.Pattern;
import static com.acorn.codextabs.core.Json.*;

/** Remove the two selected HTTP metadata headers before Claude receives a tool result. */
public final class ClaudeResponseHeaders {
    public static final String CALLBACK = "codex_tabs_response_headers";
    private static final Pattern STATUS = Pattern.compile("^(< |  )?HTTP/[0-9.]+ [0-9]{3}(?: .*)?$");
    private ClaudeResponseHeaders() {}

    /** Register a synchronous SDK hook, so filtering happens before the next model request. */
    public static JsonObject hooks() {
        return object("PostToolUse", List.of(object("matcher", "^(Bash|PowerShell|WebFetch|mcp__.*)$", "hookCallbackIds", List.of(CALLBACK))));
    }

    /** Leave unknown events and unchanged results alone, including errors and non-header content. */
    public static JsonObject reply(JsonObject input) {
        if (!text(input, "hook_event_name").equals("PostToolUse") || !input.has("tool_response")) { return new JsonObject(); }
        String tool = text(input, "tool_name");
        if (!tool.equals("Bash") && !tool.equals("PowerShell") && !tool.equals("WebFetch") && !tool.startsWith("mcp__")) { return new JsonObject(); }
        var original = input.get("tool_response");
        var filtered = filter(original, "");
        return original.equals(filtered) ? new JsonObject() : object("hookSpecificOutput", object("hookEventName", "PostToolUse", "updatedToolOutput", filtered));
    }
    private static boolean removed(String name) {
        return name.equalsIgnoreCase("x-robots-tag") || name.equalsIgnoreCase("tdm-reservation");
    }
    private static boolean headerField(String name) {
        return name.equalsIgnoreCase("headers") || name.equalsIgnoreCase("responseHeaders") || name.equalsIgnoreCase("response_headers");
    }
    private static JsonElement filter(JsonElement value, String field) {
        // Bodies and binary data must not be interpreted as response metadata.
        if (field.equalsIgnoreCase("body") || field.equalsIgnoreCase("data")) { return value.deepCopy(); }
        if (value.isJsonObject()) {
            var result = new JsonObject();
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (headerField(field) && removed(entry.getKey())) { continue; }
                result.add(entry.getKey(), filter(entry.getValue(), entry.getKey()));
            }
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray();
            for (var entry : value.getAsJsonArray()) {
                if (headerField(field)) {
                    if (entry.isJsonObject() && removed(text(entry.getAsJsonObject(), "name"))) { continue; }
                    if (entry.isJsonArray() && !entry.getAsJsonArray().isEmpty() && entry.getAsJsonArray().get(0).isJsonPrimitive()
                        && removed(entry.getAsJsonArray().get(0).getAsString())) { continue; }
                }
                result.add(filter(entry, ""));
            }
            return result;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(filterText(value.getAsString(), headerField(field), field.equalsIgnoreCase("stderr")));
        }
        return value.deepCopy();
    }
    private static String filterText(String source, boolean headerOnly, boolean verbose) {
        var output = new StringBuilder();
        String prefix = headerOnly ? "" : null;
        boolean dropped = false;
        boolean body = false;
        for (String line : source.split("(?<=\\n)", -1)) {
            String text = line.replaceFirst("[\\r\\n]+$", "");
            var status = STATUS.matcher(text);
            // curl -v and wget server-response prefixes distinguish headers from body text.
            if (status.matches() && (!body || (verbose && status.group(1) != null))) {
                prefix = status.group(1) == null ? "" : status.group(1); dropped = false;
                output.append(line); continue;
            }
            if (prefix != null && text.startsWith(prefix)) {
                String header = text.substring(prefix.length());
                if (header.isBlank()) { prefix = null; dropped = false; output.append(line); continue; }
                if (dropped && (header.startsWith(" ") || header.startsWith("\t"))) { continue; }
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                    dropped = removed(header.substring(0, colon));
                    if (!dropped) { output.append(line); }
                    continue;
                }
                prefix = null; dropped = false;
            }
            if (!text.isBlank()) { body = true; }
            output.append(line);
        }
        return output.toString();
    }
}
