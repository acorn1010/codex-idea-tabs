package com.acorn.codextabs.core;

import com.google.gson.*;

/** Small JSON helpers keep the versioned Codex protocol readable without copying its entire schema. */
public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private Json() {}
    public static JsonObject object(Object... pairs) {
        var result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            result.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
        }
        return result;
    }
    public static String text(JsonObject object, String key) { return text(object, key, ""); }
    public static String text(JsonObject object, String key, String fallback) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
    public static JsonObject obj(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    public static JsonArray array(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
    public static boolean flag(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }
}
