package com.acorn.codextabs.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.*;
import static com.acorn.codextabs.core.Json.*;

/** Non-secret connection settings. Keys live in the IDE credential store. */
public final class ApiConnection {
    public String id = "";
    public String name = "";
    public String baseUrl = "";
    public List<String> models = new ArrayList<>();
    public String selectedModel = "";
    public boolean hasKey;
    public String permissions = "ask";
    public boolean planMode;

    public ApiConnection copy() {
        var copy = new ApiConnection();
        copy.id = id; copy.name = name; copy.baseUrl = baseUrl;
        copy.models = new ArrayList<>(models); copy.selectedModel = selectedModel; copy.hasKey = hasKey; copy.permissions = permissions; copy.planMode = planMode;
        return copy;
    }
    public void validate() {
        if (!id.matches("[a-zA-Z0-9_-]{1,64}")) { throw new IllegalArgumentException("Invalid connection ID."); }
        if (name.isBlank()) { throw new IllegalArgumentException("Enter a connection name."); }
        URI uri;
        try { uri = URI.create(baseUrl); } catch (IllegalArgumentException error) { throw new IllegalArgumentException("Enter a valid API base URL."); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Use an API base URL without credentials, a query, or a fragment.");
        }
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(local && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Use HTTPS, or HTTP for a local server.");
        }
        if (models.isEmpty() || models.stream().anyMatch(model -> model.isBlank() || model.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Enter at least one model ID, one per line.");
        }
    }
    public String providerId() { return "codex_tabs_" + id; }
    public String environmentKey() { return "CODEX_TABS_API_" + id.replace('-', '_').toUpperCase(Locale.ROOT); }
    public String model(String requested) { return models.contains(requested) ? requested : models.getFirst(); }
    public JsonArray modelOptions() {
        var result = new JsonArray();
        for (String model : models) {
            result.add(object("id", model, "model", model, "displayName", model, "isDefault", model.equals(models.getFirst()),
                "defaultReasoningEffort", "", "supportedReasoningEfforts", new JsonArray()));
        }
        return result;
    }
    /** Only public metadata appears in command arguments. Secrets are passed in the child environment. */
    public List<String> arguments() {
        validate();
        String prefix = "model_providers." + providerId() + ".";
        var args = new ArrayList<String>();
        for (var entry : Map.of("name", name, "base_url", baseUrl, "wire_api", "responses").entrySet()) {
            args.add("-c"); args.add(prefix + entry.getKey() + "=" + GSON.toJson(entry.getValue()));
        }
        args.add("-c"); args.add(prefix + "requires_openai_auth=false");
        if (hasKey) { args.add("-c"); args.add(prefix + "env_key=" + GSON.toJson(environmentKey())); }
        return args;
    }
    /** Pin each thread to its chosen connection, including resumes and forks. */
    public void configure(JsonObject params, String requestedModel) {
        params.addProperty("modelProvider", providerId());
        params.addProperty("model", model(requestedModel));
        var config = obj(params, "config").deepCopy();
        config.remove("model_reasoning_effort");
        config.addProperty("model_reasoning_summary", "none");
        params.add("config", config);
    }
    @Override public String toString() { return name.isBlank() ? "New connection" : name; }
    @Override public boolean equals(Object other) {
        return other instanceof ApiConnection value && id.equals(value.id) && name.equals(value.name) && baseUrl.equals(value.baseUrl)
            && models.equals(value.models) && selectedModel.equals(value.selectedModel) && hasKey == value.hasKey && permissions.equals(value.permissions) && planMode == value.planMode;
    }
    @Override public int hashCode() { return Objects.hash(id, name, baseUrl, models, selectedModel, hasKey, permissions, planMode); }
}
