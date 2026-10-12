package com.acorn.codextabs.core;

import com.google.gson.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import static com.acorn.codextabs.core.Json.*;

/** Track live task metadata without polling processes or rereading transcript output. */
final class ClaudeActivity {
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "stopped", "killed");
    private static final Pattern WAKEUP_DELAY = Pattern.compile("^Next wakeup scheduled for [^\\n]+ \\(in (\\d+)s\\)\\.");
    private final Conversation chat;
    private final LongSupplier clock;
    private final Map<String, JsonObject> tasks = new LinkedHashMap<>();
    private final Set<String> finished = new LinkedHashSet<>();
    private JsonObject wakeup = new JsonObject();

    ClaudeActivity(Conversation chat, LongSupplier clock) { this.chat = chat; this.clock = clock; }

    void system(JsonObject frame) {
        String subtype = text(frame, "subtype"), id = text(frame, "task_id");
        if (id.isBlank()) { return; }
        if (subtype.equals("task_notification") || subtype.equals("task_updated")) {
            var patch = obj(frame, "patch");
            if (TERMINAL.contains(text(frame, "status", text(patch, "status")))) { finish(id); }
            else if (tasks.containsKey(id)) { running(id, text(patch, "description", text(frame, "description"))); }
        } else if (subtype.equals("task_started") || subtype.equals("task_progress")) {
            running(id, text(frame, "description"));
        }
    }

    void result(JsonObject tool, JsonObject frame, JsonObject result, String output) {
        if (flag(result, "is_error")) { return; }
        var data = frame.has("tool_use_result") ? obj(frame, "tool_use_result") : obj(frame, "toolUseResult");
        String name = text(tool, "tool"); var input = obj(tool, "arguments");
        if (name.equals("ScheduleWakeup")) {
            if (flag(input, "stop") || flag(data, "stopped")) { wakeup = new JsonObject(); }
            else {
                long due = 0;
                try {
                    if (data.has("scheduledFor")) { due = data.get("scheduledFor").getAsLong(); }
                    else {
                        var match = WAKEUP_DELAY.matcher(output);
                        if (match.find()) { due = Math.addExact(clock.getAsLong(), Math.multiplyExact(Long.parseLong(match.group(1)), 1000)); }
                    }
                } catch (NumberFormatException | ArithmeticException ignored) { /* Keep an unknown time visible as scheduled. */ }
                wakeup = object("scheduledFor", due, "reason", compact(text(input, "reason")));
            }
            publish();
        }
        // Some CLI versions report the task ID in the tool result before the task event.
        if (text(tool, "type").equals("commandExecution")) { running(text(data, "backgroundTaskId"), text(tool, "command")); }
        if (name.equals("Agent") && text(data, "status").equals("async_launched")) { running(text(data, "agentId"), text(data, "description")); }
        if (name.equals("Monitor")) { running(text(data, "taskId"), text(input, "description", "Monitor")); }
        if (name.equals("TaskStop")) { finish(text(input, "task_id", text(input, "shell_id"))); }
    }

    void resumed() {
        // A reply after the deadline consumes the pending one-shot wakeup.
        if (wakeup.has("scheduledFor") && wakeup.get("scheduledFor").getAsLong() > 0 && wakeup.get("scheduledFor").getAsLong() <= clock.getAsLong()) {
            wakeup = new JsonObject(); publish();
        }
    }

    private void running(String id, String description) {
        if (id.isBlank() || finished.contains(id)) { return; }
        var previous = tasks.get(id);
        if (description.isBlank() && previous != null) { return; }
        var task = object("id", id, "description", compact(description.isBlank() ? "Background task" : description));
        if (!task.equals(previous)) { tasks.put(id, task); publish(); }
    }
    private void finish(String id) {
        if (id.isBlank()) { return; }
        finished.add(id);
        if (finished.size() > 256) { finished.remove(finished.iterator().next()); }
        if (tasks.remove(id) != null) { publish(); }
    }
    private void publish() { chat.set("claudeActivity", object("tasks", tasks.values(), "wakeup", wakeup)); }
    private static String compact(String value) { return value.substring(0, Math.min(240, value.length())); }
}
