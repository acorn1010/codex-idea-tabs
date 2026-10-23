package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import com.google.gson.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ClaudeResponseHeadersTest {
    private static JsonObject filter(JsonObject output) {
        return obj(obj(ClaudeResponseHeaders.reply(object("hook_event_name", "PostToolUse", "tool_name", "Bash", "tool_response", output)), "hookSpecificOutput"), "updatedToolOutput");
    }
    @Test void removesRawHeadersAcrossRedirectsWithoutChangingBodyOrOtherOutput() {
        String body = "x-robots-tag: this is body text\r\nTDM-Reservation: also body\r\n< HTTP/2 200\r\n< x-robots-tag: body example\r\n";
        String before = "HTTP/1.1 302 Found\r\nX-Robots-Tag: noai\r\n  noimageai\r\nLocation: /next\r\n\r\nHTTP/2 200\r\nTDM-Reservation: 1\r\nX-ROBOTS-TAG: noimageai\r\nContent-Type: text/plain\r\n\r\n";
        String expected = "HTTP/1.1 302 Found\r\nLocation: /next\r\n\r\nHTTP/2 200\r\nContent-Type: text/plain\r\n\r\n" + body;
        var output = object("stdout", before + body, "stderr", "warning: unchanged", "interrupted", false, "exitCode", 0);
        var filtered = filter(output);
        assertEquals(expected, text(filtered, "stdout"));
        assertEquals("warning: unchanged", text(filtered, "stderr"));
        assertFalse(flag(filtered, "interrupted")); assertEquals(0, filtered.get("exitCode").getAsInt());
        assertEquals(before + body, text(output, "stdout"), "Never mutate the original tool record");
    }
    @Test void removesCurlAndWgetVerboseResponseHeadersButKeepsErrorsAndRequests() {
        var output = object("stdout", "body", "stderr", "* Connected\n> X-Robots-Tag: request value\n< HTTP/2 200\n< X-Robots-Tag: noai\n< Server: test\n< Tdm-Reservation: 1\n< \nwarning\n  HTTP/1.1 200 OK\n  tdm-reservation: 1\n  Server: wget\n\nerror: unchanged\n");
        assertEquals("* Connected\n> X-Robots-Tag: request value\n< HTTP/2 200\n< Server: test\n< \nwarning\n  HTTP/1.1 200 OK\n  Server: wget\n\nerror: unchanged\n", text(filter(output), "stderr"));
    }
    @Test void filtersStructuredHeadersAndKeepsNonHeaderFieldsAndBinaryData() {
        var response = object("headers", object("X-Robots-Tag", "noai", "TDM-Reservation", "1", "Content-Type", "image/png"),
            "body", "HTTP/1.1 200 OK\nX-Robots-Tag: example\n\n", "data", "tdm-reservation: base64",
            "x-robots-tag", "ordinary field", "responseHeaders", List.of(List.of("tdm-reservation", "1"), List.of("Server", "test")),
            "nested", object("headers", List.of(object("name", "X-Robots-Tag", "value", "noai"), object("name", "ETag", "value", "123"))));
        var result = filter(response);
        assertEquals(object("Content-Type", "image/png"), obj(result, "headers"));
        assertEquals(response.get("body"), result.get("body")); assertEquals(response.get("data"), result.get("data"));
        assertEquals("ordinary field", text(result, "x-robots-tag"));
        assertEquals(1, array(result, "responseHeaders").size());
        assertEquals("ETag", text(array(obj(result, "nested"), "headers").get(0).getAsJsonObject(), "name"));
    }
    @Test void noChangeReturnsNoReplacementAndDoesNotFilterFileReadsOrFailures() {
        for (String tool : List.of("Bash", "WebFetch", "mcp__fetch__url")) {
            assertTrue(ClaudeResponseHeaders.reply(object("hook_event_name", "PostToolUse", "tool_name", tool,
                "tool_response", object("stdout", "x-robots-tag: documentation example\n"))).isEmpty());
        }
        for (String event : List.of("PreToolUse", "PostToolUseFailure")) {
            assertTrue(ClaudeResponseHeaders.reply(object("hook_event_name", event, "tool_name", "Bash",
                "tool_response", object("headers", object("x-robots-tag", "noai")))).isEmpty());
        }
        assertTrue(ClaudeResponseHeaders.reply(object("hook_event_name", "PostToolUse", "tool_name", "Read",
            "tool_response", object("headers", object("x-robots-tag", "noai")))).isEmpty());
    }
    @Test void downloadPolicyKeepsNativeSafeguardsAndDoesNotPreapproveShellCommands() {
        var settings = ClaudeClient.permissionSettings("", true);
        assertEquals(List.of("WebFetch"), array(obj(settings, "permissions"), "allow").asList().stream().map(JsonElement::getAsString).toList());
        var auto = obj(settings, "autoMode");
        assertEquals(1, auto.size()); assertEquals("$defaults", array(auto, "allow").get(0).getAsString());
        assertTrue(ClaudeClient.permissionSettings("", false).isEmpty());
        assertEquals(1, array(obj(ClaudeClient.permissionSettings("WebFetch", true), "permissions"), "allow").size());
    }
}
