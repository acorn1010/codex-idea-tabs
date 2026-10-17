package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiConnectionTest {
    private ApiConnection connection() {
        var value = new ApiConnection(); value.id = "routeway-123"; value.name = "Routeway";
        value.baseUrl = "https://api.routeway.ai/v1"; value.models.addAll(List.of("glm-test", "second-model")); return value;
    }
    @Test void credentialsStayOutOfArgumentsAndMetadata() {
        var value = connection(); value.hasKey = true;
        String args = String.join(" ", value.arguments());
        assertTrue(args.contains("wire_api=\"responses\""));
        assertTrue(args.contains("env_key=\"CODEX_TABS_API_ROUTEWAY_123\""));
        assertTrue(args.contains("requires_openai_auth=false"));
        assertFalse(args.contains("api_key")); assertFalse(args.contains("Authorization"));
        value.hasKey = false; assertFalse(String.join(" ", value.arguments()).contains("env_key"));
    }
    @Test void rejectsCredentialsInUrlsAndInsecureRemoteEndpoints() {
        for (String url : List.of("https://key@api.example.com/v1", "https://api.example.com?key=secret", "https://api.example.com/#part", "http://api.example.com/v1", "file:///tmp/api", "invalid")) {
            var value = connection(); value.baseUrl = url; assertThrows(IllegalArgumentException.class, value::validate, url);
        }
        for (String url : List.of("http://localhost:8080/v1", "http://127.0.0.1:1234/v1", "http://[::1]:1234/v1", "https://api.routeway.ai/v1")) {
            var value = connection(); value.baseUrl = url; assertDoesNotThrow(value::validate);
        }
    }
    @Test void threadRoutingOverridesStaleModelsAndRetainsOtherConfig() {
        var value = connection(); var params = object("model", "old-gpt-model", "config", object("other", true));
        value.configure(params, "old-gpt-model");
        assertEquals("glm-test", text(params, "model")); assertEquals(value.providerId(), text(params, "modelProvider"));
        assertTrue(flag(obj(params, "config"), "other")); assertEquals("none", text(obj(params, "config"), "model_reasoning_summary"));
        value.configure(params, "second-model"); assertEquals("second-model", text(params, "model"));
        assertEquals(2, value.modelOptions().size()); assertTrue(flag(value.modelOptions().get(0).getAsJsonObject(), "isDefault"));
    }
    @Test void editsAndProviderHandoffKeepConnectionIdentitySeparate() {
        var value = connection(); var draft = value.copy(); draft.models.add("third-model"); assertEquals(2, value.models.size());
        var chat = new Conversation("test", "/tmp"); chat.set("apiConnectionId", value.id);
        chat.set("modelProvider", LocalModel.PROVIDER); chat.set("model", LocalModel.MODEL);
        assertEquals(value.id, Conversation.restore(chat.snapshot()).get("apiConnectionId"));
        var switched = ProviderHandoff.prepare(chat.snapshot(), chat.items(), "codex");
        assertEquals("", switched.get("apiConnectionId"));
        assertEquals("", switched.get("modelProvider")); assertEquals("", switched.get("model"));
    }
    @Test void nativeAndWslCommandsKeepEveryOverrideAsOneArgument() {
        var value = connection(); value.name = "Quoted \" API $(touch sentinel)";
        var args = value.arguments();
        var nativeArgs = Paths.command("/path with spaces/codex", "", false, args);
        assertEquals(args, nativeArgs.subList(1, nativeArgs.size() - 1));
        var wsl = Paths.command("/path with spaces/codex", "Ubuntu", true, args);
        assertEquals("exec \"$@\" app-server", wsl.get(6)); assertEquals(args, wsl.subList(9, wsl.size()));
        assertFalse(wsl.get(6).contains("touch"));
    }
}
