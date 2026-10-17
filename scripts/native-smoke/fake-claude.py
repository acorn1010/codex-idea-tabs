#!/usr/bin/env python3
"""Deterministic Claude stream fixture. No network, model, tools, or credentials."""
import base64
import json
import struct
import zlib
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
steered = []
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
    emit({"type": "result", "session_id": session_id, "subtype": "success", "usage": {"input_tokens": 10, "output_tokens": 2}, "total_cost_usd": 0.01, "user_message_uuids": [token, *steered]})
    steered.clear()

def release(token):
    while active == token:
        if (root / "release-turn").exists():
            finish(token)
            return
        time.sleep(0.01)

def background_work(token):
    global active
    while not (root / "release-background").exists():
        time.sleep(0.01)
    emit({"type": "system", "subtype": "task_notification", "task_id": "build", "status": "completed"})
    active = token
    emit({"type": "stream_event", "session_id": session_id, "event": {"type": "message_start", "message": {"id": token}}})
    while not (root / "release-resumed").exists():
        time.sleep(0.01)
    finish(token)

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
        priority = frame.get("priority")
        if priority == "now" and active:
            emit({"type": "result", "session_id": session_id, "is_error": True, "result": "Interrupted for steering", "user_message_uuids": [active]})
        if "--replay-user-messages" in sys.argv:
            emit(frame)
        if priority == "later" and active:
            steered.append(frame["uuid"])
            continue
        active = frame["uuid"]
        content = frame["message"]["content"]
        prompt = " ".join(part.get("text", "") for part in content)
        if prompt == "image-reader":
            token = active
            width, height = 2000, 333
            pixels = (b"\x00" + b"\x35\x86\xb8" * width) * height
            def chunk(kind, data):
                return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
            png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")
            frames = [
                frame,
                {"type": "assistant", "uuid": "image-read-frame", "message": {"id": "image-read-message", "content": [{"type": "tool_use", "id": "image-read-tool", "name": "Read", "input": {"file_path": str(root / "read-image.png")}}]}},
                {"type": "user", "uuid": "image-result-frame", "message": {"content": [{"type": "tool_result", "tool_use_id": "image-read-tool", "content": [{"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": base64.b64encode(png).decode()}}]}]}},
                {"type": "user", "uuid": "image-note-frame", "isMeta": True, "turnCompanion": True, "message": {"content": "[Image: original 2160x360, displayed at 2000x333. Multiply coordinates by 1.08 to map to original image.]"}},
                {"type": "assistant", "uuid": "image-answer-frame", "message": {"id": "image-answer", "content": [{"type": "text", "text": "Image reader finished."}]}},
            ]
            history = pathlib.Path(os.environ["CLAUDE_CONFIG_DIR"]) / "projects" / "image-fixture" / (session_id + ".jsonl")
            history.parent.mkdir(parents=True, exist_ok=True)
            previous = ""
            for record in frames:
                record["session_id"] = session_id
                record["parentUuid"] = previous
                previous = record["uuid"]
            history.write_text("".join(json.dumps(record) + "\n" for record in frames))
            for record in frames[1:]:
                emit(record)
            finish(token)
        elif prompt == "background-activity":
            token = active
            emit({"type": "system", "subtype": "task_started", "task_id": "build", "description": "Build images"})
            finish(token)
            threading.Thread(target=background_work, args=("resumed-" + token,), daemon=True).start()
        elif prompt == "background-approval":
            finish(active)
            active = "background-" + frame["uuid"]
            pending = active
            emit({"type": "control_request", "request_id": "background-approval", "request": {"subtype": "can_use_tool", "tool_name": "Bash", "input": {"command": "echo background"}}})
        elif prompt in ("/goal", "/goal clear"):
            active = None
            emit({"type": "result", "uuid": str(uuid.uuid4()), "session_id": session_id, "subtype": "success", "is_error": False, "result": "No goal set", "usage": {}})
        elif prompt == "approval":
            pending = active
            emit({"type": "control_request", "request_id": "approval", "request": {"subtype": "can_use_tool", "tool_name": "Bash", "input": {"command": "npm test"}, "permission_suggestions": [{"type": "addRules", "behavior": "allow", "rules": [{"toolName": "Bash", "ruleContent": "npm test"}]}]}})
        elif prompt == "question":
            pending = active
            emit({"type": "control_request", "request_id": "question", "request": {"subtype": "can_use_tool", "tool_name": "AskUserQuestion", "input": {"questions": [{"question": "Which?", "multiSelect": True, "options": [{"label": "A"}, {"label": "B"}]}]}}})
        elif prompt.startswith("hold") or priority == "now":
            threading.Thread(target=release, args=(active,), daemon=True).start()
        else:
            finish(active)
    elif kind == "control_response":
        finish(pending)
