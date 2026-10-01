#!/usr/bin/env python3
"""Deterministic Claude stream fixture. No network, model, tools, or credentials."""
import json
import pathlib
import sys
import threading
import time

root = pathlib.Path.cwd()
lock = threading.Lock()
active = None
pending = None
with (root / "starts.jsonl").open("a") as output:
    output.write(json.dumps(sys.argv) + "\n")

def emit(frame):
    with lock:
        print(json.dumps(frame), flush=True)

def finish(token):
    global active
    if active != token:
        return
    active = None
    emit({"type": "assistant", "message": {"id": token, "content": [{"type": "text", "text": "Done"}]}})
    emit({"type": "result", "subtype": "success", "usage": {"input_tokens": 10, "output_tokens": 2}, "total_cost_usd": 0.01})

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
        if subtype == "initialize":
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
