#!/usr/bin/env python3
"""Deterministic Claude stream fixture. No network, model, tools, or credentials."""
import json
import os
import pathlib
import sys
import threading
import time
import uuid

root = pathlib.Path.cwd()
lock = threading.Lock()
active = None
pending = None
with (root / "starts.jsonl").open("a") as output:
    output.write(json.dumps(sys.argv) + "\n")

def emit(frame):
    with lock:
        print(json.dumps(frame), flush=True)

# Startup IDs identify a process before the CLI has saved any conversation.
resume_id = next((arg.split("=", 1)[1] for arg in sys.argv if arg.startswith("--resume=")), "")
session_id = next((arg.split("=", 1)[1] for arg in sys.argv if arg.startswith("--session-id=")), resume_id or str(uuid.uuid4()))
saved_sessions = root / "saved-sessions"
saved_sessions.mkdir(exist_ok=True)
if resume_id and not (saved_sessions / resume_id).exists():
    print("No conversation found with session ID: " + resume_id, file=sys.stderr, flush=True)
    sys.exit(1)

def finish(token):
    global active
    if active != token:
        return
    active = None
    emit({"type": "assistant", "session_id": session_id, "message": {"id": token, "content": [{"type": "text", "text": "Done"}]}})
    emit({"type": "result", "session_id": session_id, "subtype": "success", "usage": {"input_tokens": 10, "output_tokens": 2}, "total_cost_usd": 0.01})

def release(token):
    while active == token:
        if (root / "release-turn").exists():
            finish(token)
            return
        time.sleep(0.01)

for line in sys.stdin:
    frame = json.loads(line)
    with (root / "wire.jsonl").open("a") as output:
        output.write(line)
    kind = frame.get("type")
    if kind == "control_request":
        request = frame["request"]
        subtype = request["subtype"]
        response = {}
        if subtype == "set_model" and (root / "hold-model").exists():
            (root / "model-waiting").write_text("waiting")
            while (root / "hold-model").exists():
                time.sleep(0.01)
        if subtype == "initialize":
            emit({"type": "system", "subtype": "notification", "session_id": session_id, "text": "Fixture ready"})
            (root / "claude-pid").write_text(str(os.getpid()))
            response = {"models": [{"value": "default", "supportsEffort": True, "supportedEffortLevels": ["low", "high"], "supportsAutoMode": True, "supportsFastMode": True}], "commands": [{"name": "inspect", "description": "Inspect"}]}
        elif subtype == "get_usage":
            if (root / "usage-unavailable").exists():
                emit({"type": "control_response", "response": {"subtype": "error", "request_id": frame["request_id"], "error": "Unsupported control request: get_usage"}})
                continue
            response = {"rate_limits_available": True, "rate_limits": {"five_hour": {"utilization": 25, "resets_at": "2026-10-01T04:00:00Z"}, "seven_day": {"utilization": 64, "resets_at": "2026-10-05T04:00:00Z"}}}
        elif subtype == "get_context_usage":
            response = {"totalTokens": 512, "maxTokens": 200000, "categories": []}
        elif subtype == "mcp_status":
            response = {"mcpServers": [{"name": "fixture", "status": "connected", "tools": [{"name": "read"}]}]}
        elif subtype == "interrupt":
            finish(active)
        emit({"type": "control_response", "response": {"subtype": "success", "request_id": frame["request_id"], "response": response}})
    elif kind == "user":
        (saved_sessions / session_id).touch()
        active = frame["uuid"]
        content = frame["message"]["content"]
        prompt = " ".join(part.get("text", "") for part in content)
        if prompt == "approval":
            pending = active
            emit({"type": "control_request", "request_id": "approval", "request": {"subtype": "can_use_tool", "tool_name": "Bash", "input": {"command": "npm test"}, "permission_suggestions": [{"type": "addRules", "behavior": "allow", "rules": [{"toolName": "Bash", "ruleContent": "npm test"}]}]}})
        elif prompt == "question":
            pending = active
            emit({"type": "control_request", "request_id": "question", "request": {"subtype": "can_use_tool", "tool_name": "AskUserQuestion", "input": {"questions": [{"question": "Which?", "multiSelect": True, "options": [{"label": "A"}, {"label": "B"}]}]}}})
        elif prompt.startswith("hold"):
            threading.Thread(target=release, args=(active,), daemon=True).start()
        else:
            finish(active)
    elif kind == "control_response":
        finish(pending)
