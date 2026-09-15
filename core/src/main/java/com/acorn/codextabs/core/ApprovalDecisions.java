package com.acorn.codextabs.core;

import com.google.gson.*;
import static com.acorn.codextabs.core.Json.*;

/** Present server-supported approval scopes and validate the exact choice before replying. */
public final class ApprovalDecisions {
    public static JsonArray choices(JsonObject pending) {
        String method = text(pending, "method");
        if (method.equals("mcpServer/elicitation/request")) { return elicitationChoices(pending); }
        boolean command = method.equals("item/commandExecution/requestApproval");
        boolean files = method.equals("item/fileChange/requestApproval");
        boolean permissions = method.equals("item/permissions/requestApproval");
        var result = new JsonArray();
        if (!command && !files && !permissions) { return result; }
        var decisions = new JsonArray();
        if (command && pending.has("availableDecisions") && pending.get("availableDecisions").isJsonArray()) {
            decisions = array(pending, "availableDecisions");
        } else {
            decisions.add("decline");
            decisions.add("acceptForSession");
            if (command) {
                var prefix = array(pending, "proposedExecpolicyAmendment");
                if (!prefix.isEmpty()) { decisions.add(object("acceptWithExecpolicyAmendment", object("execpolicy_amendment", prefix))); }
                for (var amendment : array(pending, "proposedNetworkPolicyAmendments")) {
                    if (amendment.isJsonObject() && text(amendment.getAsJsonObject(), "action").equals("allow")) {
                        decisions.add(object("applyNetworkPolicyAmendment", object("network_policy_amendment", amendment)));
                    }
                }
            }
            decisions.add("accept");
        }
        for (var decision : decisions) {
            String label = "", description = "", scope = "";
            if (decision.isJsonPrimitive() && decision.getAsJsonPrimitive().isString()) {
                switch (decision.getAsString()) {
                    case "accept" -> label = "Allow once";
                    case "decline" -> label = "Decline";
                    case "cancel" -> label = "Cancel turn";
                    case "acceptForSession" -> {
                        label = "Allow for session"; scope = "session";
                        description = permissions ? "Keep these permissions for later turns in this session."
                            : files ? "Allow further changes to these files during this session."
                            : "Remember this approval during this session.";
                    }
                    default -> { continue; }
                }
            } else if (command && decision.isJsonObject()) {
                var value = decision.getAsJsonObject();
                var prefix = array(obj(value, "acceptWithExecpolicyAmendment"), "execpolicy_amendment");
                var network = obj(obj(value, "applyNetworkPolicyAmendment"), "network_policy_amendment");
                if (value.size() == 1 && !prefix.isEmpty() && java.util.stream.StreamSupport.stream(prefix.spliterator(), false).allMatch(part -> part.isJsonPrimitive() && part.getAsJsonPrimitive().isString())) {
                    label = "Always allow"; scope = "always";
                    description = "Save a rule for commands starting with these arguments: " + GSON.toJson(prefix);
                } else if (value.size() == 1 && (text(network, "action").equals("allow") || text(network, "action").equals("deny")) && !text(network, "host").isBlank()) {
                    label = text(network, "action").equals("allow") ? "Always allow" : "Always decline"; scope = "always";
                    description = (text(network, "action").equals("allow") ? "Allow" : "Block") + " future network access to " + text(network, "host") + ".";
                } else { continue; }
            } else { continue; }
            result.add(object("decision", decision.deepCopy(), "label", label, "description", description, "scope", scope));
        }
        return result;
    }

    /** Metadata scopes belong to the requesting connector. Never invent a durable grant. */
    private static JsonArray elicitationChoices(JsonObject pending) {
        var choices = new JsonArray();
        choices.add(object("decision", "decline", "label", "Decline", "description", "", "scope", ""));
        String mode = text(pending, "mode", "form");
        boolean form = java.util.Set.of("form", "openai/form", "openaiForm").contains(mode);
        var meta = obj(pending, "_meta");
        var persist = meta.get("persist");
        var scopes = new JsonArray();
        if (form && persist != null) {
            if (persist.isJsonArray()) { scopes = persist.getAsJsonArray(); }
            else if (persist.isJsonPrimitive() && persist.getAsJsonPrimitive().isString()) { scopes.add(persist); }
        }
        String source = text(meta, "connector_name", text(pending, "serverName", "this connector"));
        String target = text(pending, "message");
        if (scopes.contains(new JsonPrimitive("session"))) {
            choices.add(object("decision", "acceptForSession", "label", "Allow for session", "scope", "session",
                "description", "Remember this approval for " + source + " during this session."));
        }
        if (scopes.contains(new JsonPrimitive("always"))) {
            choices.add(object("decision", "acceptAlways", "label", "Always allow", "scope", "always",
                "description", "Let " + source + " remember this approval for future matching requests: " + target));
        }
        if (!mode.equals("openai/userVerification")) {
            choices.add(object("decision", "accept", "label", "Allow once", "description", "", "scope", ""));
        }
        return choices;
    }

    public static JsonObject response(JsonObject pending, JsonElement decision) {
        return response(pending, decision, new JsonObject());
    }
    public static JsonObject response(JsonObject pending, JsonElement decision, JsonObject content) {
        boolean offered = java.util.stream.StreamSupport.stream(choices(pending).spliterator(), false)
            .anyMatch(choice -> choice.getAsJsonObject().get("decision").equals(decision));
        if (!offered) { throw new IllegalArgumentException("This approval choice is not available for this request."); }
        if (text(pending, "method").equals("mcpServer/elicitation/request")) {
            String value = decision.getAsString();
            boolean accepted = value.equals("accept") || value.equals("acceptForSession") || value.equals("acceptAlways");
            var response = object("action", accepted ? "accept" : value, "content", accepted ? content.deepCopy() : JsonNull.INSTANCE);
            if (value.equals("acceptForSession") || value.equals("acceptAlways")) {
                response.add("_meta", object("persist", value.equals("acceptAlways") ? "always" : "session"));
            }
            return response;
        }
        if (text(pending, "method").equals("item/permissions/requestApproval")) {
            String value = decision.getAsString();
            return object("permissions", value.equals("accept") || value.equals("acceptForSession") ? obj(pending, "permissions").deepCopy() : new JsonObject(),
                "scope", value.equals("acceptForSession") ? "session" : "turn");
        }
        return object("decision", decision.deepCopy());
    }
    private ApprovalDecisions() {}
}
