package com.acorn.codextabs.core;

import com.google.gson.*;
import static com.acorn.codextabs.core.Json.*;

/** Present server-supported approval scopes and validate the exact choice before replying. */
public final class ApprovalDecisions {
    public static JsonArray choices(JsonObject pending) {
        String method = text(pending, "method");
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
            String label = "", description = "";
            if (decision.isJsonPrimitive() && decision.getAsJsonPrimitive().isString()) {
                switch (decision.getAsString()) {
                    case "accept" -> label = "Allow once";
                    case "decline" -> label = "Decline";
                    case "cancel" -> label = "Cancel turn";
                    case "acceptForSession" -> {
                        label = "Allow for session";
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
                    label = "Always allow";
                    description = "Save a rule for commands starting with these arguments: " + GSON.toJson(prefix);
                } else if (value.size() == 1 && text(network, "action").equals("allow") && !text(network, "host").isBlank()) {
                    label = "Always allow";
                    description = "Save a network access rule for " + text(network, "host") + ".";
                } else { continue; }
            } else { continue; }
            result.add(object("decision", decision.deepCopy(), "label", label, "description", description));
        }
        return result;
    }

    public static JsonObject response(JsonObject pending, JsonElement decision) {
        boolean offered = java.util.stream.StreamSupport.stream(choices(pending).spliterator(), false)
            .anyMatch(choice -> choice.getAsJsonObject().get("decision").equals(decision));
        if (!offered) { throw new IllegalArgumentException("This approval choice is not available for this request."); }
        if (text(pending, "method").equals("item/permissions/requestApproval")) {
            String value = decision.getAsString();
            return object("permissions", value.equals("accept") || value.equals("acceptForSession") ? obj(pending, "permissions").deepCopy() : new JsonObject(),
                "scope", value.equals("acceptForSession") ? "session" : "turn");
        }
        return object("decision", decision.deepCopy());
    }
    private ApprovalDecisions() {}
}
