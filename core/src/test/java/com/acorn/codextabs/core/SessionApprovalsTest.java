package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import com.google.gson.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class SessionApprovalsTest {
    @Test void selectionAppliesToNextTurnAndCanBeDisabledImmediately() {
        var mode = new SessionApprovals(); mode.beginTurn("ask"); mode.select("session");
        assertEquals("session", mode.mode("auto")); assertFalse(mode.active());
        mode.beginTurn("session"); assertTrue(mode.active());
        mode.select("ask"); assertFalse(mode.active());
        assertEquals("ask", mode.resolve("session"), "A stale UI value must not re-enable session approval");
        mode.select("session"); mode.beginTurn("read"); assertFalse(mode.active(), "Read-only turns must retain their restrictions");
    }
    @Test void completionKeepsSelectionButReconnectExpiresIt() {
        var chat = new Conversation("chat", "/repo"); chat.approvals.select("session"); chat.approvals.beginTurn("session");
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "turn"))));
        assertFalse(chat.approvals.active()); assertEquals("session", chat.approvals.mode("auto"));
        chat.approvals.beginTurn("session"); chat.disconnected();
        assertFalse(chat.approvals.active()); assertEquals("ask", chat.approvals.mode("auto"));
        chat.approvals.beginTurn("session"); assertFalse(chat.approvals.active());
    }
    @Test void goalContinuationKeepsTheGrantOnlyWhileTheBackendStillUsesSessionMode() {
        var chat = new Conversation("chat", "/repo"); chat.approvals.select("session"); chat.approvals.beginTurn("session");
        chat.event(object("method", "turn/completed", "params", object("turn", object("id", "first"))));
        chat.event(object("method", "turn/started", "params", object("turn", object("id", "continued"))));
        assertTrue(chat.approvals.active());
        chat.approvals.select("ask"); chat.approvals.turnStarted(); assertFalse(chat.approvals.active());
        chat.approvals.beginTurn("read"); chat.approvals.select("session"); chat.approvals.turnStarted();
        assertFalse(chat.approvals.active(), "Selecting session cannot change an existing read-only turn");
        chat.disconnected(); chat.approvals.select("session"); chat.approvals.turnStarted();
        assertFalse(chat.approvals.active(), "Reconnected turns must not inherit the old backend mode");
    }
    @Test void restartHandoffAndOtherChatsDoNotInheritTheGrant() {
        var chat = new Conversation("chat", "/repo"); chat.approvals.select("session"); chat.approvals.beginTurn("session");
        assertFalse(Conversation.restore(chat.snapshot()).approvals.active());
        assertEquals("auto", Conversation.restore(chat.snapshot()).approvals.mode("auto"));
        assertEquals("ask", ProviderHandoff.prepare(chat.snapshot(), chat.items(), "claude").approvals.mode("ask"));
        assertEquals("auto", new Conversation("other", "/repo").approvals.mode("auto"));
        chat.approvals.endTurn(); chat.archive(true); assertEquals("ask", chat.approvals.mode("auto"));
    }
    @Test void commandsFilesAndPermissionsUseOnlyOneOffResponses() {
        assertEquals(object("decision", "accept"), ApprovalDecisions.automaticResponse(object("method", "item/commandExecution/requestApproval")));
        assertEquals(object("decision", "accept"), ApprovalDecisions.automaticResponse(object("method", "item/fileChange/requestApproval")));
        var permissions = object("network", object("enabled", true));
        assertEquals(object("permissions", permissions, "scope", "turn"), ApprovalDecisions.automaticResponse(object("method", "item/permissions/requestApproval", "permissions", permissions)));
        var unsupported = object("method", "item/commandExecution/requestApproval", "availableDecisions", List.of("decline", "acceptForSession"));
        assertNull(ApprovalDecisions.automaticResponse(unsupported), "Never substitute a lasting grant when Allow once is unavailable");
    }
    @Test void emptyConnectorApprovalWorksWithoutPersistenceButQuestionsAndFormsRemainManual() {
        var approval = object("method", "mcpServer/elicitation/request", "mode", "form", "requestedSchema", object("type", "object", "properties", object()), "_meta", object("persist", List.of("session", "always")));
        assertEquals(object("action", "accept", "content", object()), ApprovalDecisions.automaticResponse(approval));
        for (String mode : List.of("url", "openai/userVerification")) {
            var external = approval.deepCopy(); external.addProperty("mode", mode); assertNull(ApprovalDecisions.automaticResponse(external));
        }
        var form = approval.deepCopy(); obj(form, "requestedSchema").add("properties", object("answer", object("type", "string")));
        assertNull(ApprovalDecisions.automaticResponse(form));
        form = approval.deepCopy(); obj(form, "requestedSchema").add("required", GSON.toJsonTree(List.of("answer")));
        assertNull(ApprovalDecisions.automaticResponse(form));
        form = approval.deepCopy(); obj(form, "requestedSchema").add("oneOf", new JsonArray());
        assertNull(ApprovalDecisions.automaticResponse(form));
        assertNull(ApprovalDecisions.automaticResponse(object("method", "item/tool/requestUserInput")));
        assertNull(ApprovalDecisions.automaticResponse(object("method", "unknown/requestApproval")));
    }
}
