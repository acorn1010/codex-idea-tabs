package com.acorn.codextabs.core;

import com.google.gson.*;
import static com.acorn.codextabs.core.Json.*;

/** Translate deliberate composer choices into supported app-server turn settings. */
public final class ComposerOptions {
    /** Request readable summaries for this plugin without changing the user's global Codex settings. */
    public static JsonObject threadConfig(String effort) {
        var config = object("model_reasoning_summary", "detailed");
        if (!effort.isBlank()) { config.addProperty("model_reasoning_effort", effort); }
        return config;
    }
    public static void apply(JsonObject params, JsonObject payload, JsonArray models, String model, String effort, boolean previousPlan) {
        // Astra's automatic summary setting can return no text even for long reasoning steps.
        params.addProperty("summary", "detailed");
        JsonObject selected = new JsonObject();
        for (var entry : models) {
            var candidate = entry.getAsJsonObject();
            if ((!model.isBlank() && (text(candidate, "model").equals(model) || text(candidate, "id").equals(model))) || (model.isBlank() && flag(candidate, "isDefault"))) { selected = candidate; break; }
        }
        if (payload.has("fast")) {
            String tier = "default";
            if (flag(payload, "fast")) {
                tier = "";
                for (var entry : array(selected, "serviceTiers")) {
                    var value = entry.getAsJsonObject();
                    if (text(value, "name").equalsIgnoreCase("fast") || java.util.Set.of("fast", "priority").contains(text(value, "id"))) { tier = text(value, "id"); break; }
                }
                if (tier.isBlank()) {
                    for (var entry : array(selected, "additionalSpeedTiers")) {
                        if (java.util.Set.of("fast", "priority").contains(entry.getAsString())) { tier = entry.getAsString(); break; }
                    }
                }
                if (tier.isBlank()) { throw new IllegalArgumentException("Fast mode is not available for the selected model."); }
            }
            params.addProperty("serviceTierForTurn", tier);
        }
        boolean plan = flag(payload, "planMode");
        if (plan || previousPlan) {
            String resolved = model.isBlank() ? text(selected, "model") : model;
            if (resolved.isBlank()) { throw new IllegalArgumentException("Choose a model before changing Plan mode."); }
            var settings = object("model", resolved);
            if (!effort.isBlank()) { settings.addProperty("reasoning_effort", effort); }
            // Omitting custom developer instructions selects the built-in prompt for this mode.
            params.add("collaborationMode", object("mode", plan ? "plan" : "default", "settings", settings));
        }
    }
    public static JsonObject goal(JsonObject payload, String threadId) {
        String objective = text(payload, "goalObjective").strip();
        if (objective.isBlank() || objective.length() > 4000) { throw new IllegalArgumentException("A goal must contain 1 to 4,000 characters."); }
        var result = object("threadId", threadId, "objective", objective, "status", "active");
        if (payload.has("tokenBudget")) {
            var value = payload.get("tokenBudget");
            long budget;
            try { budget = value.getAsBigDecimal().longValueExact(); }
            catch (RuntimeException error) { throw new IllegalArgumentException("Use a positive whole number for the token budget."); }
            if (budget <= 0) { throw new IllegalArgumentException("Use a positive whole number for the token budget."); }
            result.addProperty("tokenBudget", budget);
        }
        return result;
    }
    public static JsonObject review(JsonObject target) {
        String type = text(target, "type");
        if (type.equals("uncommittedChanges")) { return object("type", type); }
        String branch = text(target, "branch").strip();
        if (type.equals("baseBranch") && !branch.isBlank()) { return object("type", type, "branch", branch); }
        throw new IllegalArgumentException("Choose uncommitted changes or a base branch to review.");
    }
    private ComposerOptions() {}
}
