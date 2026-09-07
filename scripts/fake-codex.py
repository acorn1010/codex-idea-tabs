#!/usr/bin/env python3
"""Deterministic local app-server fixture for native IDE smoke tests. Never calls a model."""
import base64
import json
import os
import pathlib
import sys
import threading
import time

lock = threading.Lock()
root = pathlib.Path(os.environ.get("CODEX_SMOKE_ROOT", "/tmp/codex-idea-smoke-project"))
root.mkdir(parents=True, exist_ok=True)
image = root / "preview.svg"
image.write_text('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="280"><rect width="640" height="280" rx="24" fill="#293745"/><circle cx="115" cy="140" r="60" fill="#62aef7"/><text x="210" y="151" font-family="sans-serif" font-size="28" fill="#e6edf3">Image preview works</text></svg>')
titles = {"review": "Review session recovery", "choices": "Choose the composer layout", "image": "Preview the new artwork", "build": "Check the build"}


def emit(value):
    """Serialize whole JSONL frames when events and replies run together."""
    with lock:
        print(json.dumps(value), flush=True)


def event(method, thread, **params):
    emit({"method": method, "params": {"threadId": thread, **params}})


def thread(thread_id):
    return {"id": thread_id, "name": titles.get(thread_id, "New task"), "preview": "", "cwd": str(root), "turns": [], "createdAt": 1, "updatedAt": 1}


def items(thread_id):
    messages = [
        {"id": thread_id + "-user", "type": "userMessage", "content": [{"type": "text", "text": "Keep chats fast to open, and make it clear when you need my input."}]},
        {"id": thread_id + "-tool", "type": "commandExecution", "command": "npm test", "status": "completed", "aggregatedOutput": "12 tests passed in 0.8 seconds", "exitCode": 0},
        {"id": thread_id + "-answer", "type": "agentMessage", "text": "Each chat has its own editor tab and keeps running while you work elsewhere.\n\n- **Blue** means work is in progress.\n- **Amber** means a question or approval needs you.\n- **Green** marks an unread result.\n\nThe composer keeps the workspace, model, and permissions on one compact row."},
    ]
    if thread_id == "image":
        messages[-1]["text"] = "The generated image is ready. You can preview it here or open it in the editor."
        messages.append({"id": "generated-art", "type": "imageGeneration", "status": "completed", "savedPath": str(image), "result": ""})
    return messages


def attention(thread_id):
    time.sleep(1)
    if thread_id == "choices":
        event("item/tool/requestUserInput", thread_id, turnId="fixture-turn", itemId="fixture-choice", isBlocking=False, questions=[{"id": "layout", "header": "Layout", "question": "Where should new conversations open?", "options": [{"label": "Beside the current chat", "description": "Keep the current work visible while starting another task."}, {"label": "In the current group", "description": "Use the existing group without adding a split."}]}])
    if thread_id == "build":
        event("turn/started", thread_id, turn={"id": "fixture-build", "status": "inProgress"})


def respond(request):
    method, params = request.get("method"), request.get("params", {})
    result = {}
    if method == "initialize":
        result = {"userAgent": "codex-tabs-fixture"}
    elif method == "account/read":
        result = {"account": {"type": "chatgpt", "planType": "pro"}, "requiresOpenaiAuth": True}
    elif method == "model/list":
        result = {"data": [{"id": "fixture-model", "model": "fixture-model", "displayName": "Test model", "supportedReasoningEfforts": [{"reasoningEffort": "high", "description": "High"}]}]}
    elif method == "thread/list":
        result = {"data": [thread(key) for key in titles], "nextCursor": None}
    elif method == "thread/resume":
        result = {"thread": thread(params["threadId"])}
        threading.Thread(target=attention, args=(params["threadId"],), daemon=True).start()
    elif method == "thread/items/list":
        result = {"data": [{"item": item, "turnId": "fixture-turn"} for item in reversed(items(params["threadId"]))], "nextCursor": None}
    elif method == "thread/start":
        result = {"thread": thread("new-" + str(time.time_ns()))}
    elif method in ("turn/start", "turn/steer"):
        thread_id = params["threadId"]
        turn = {"id": "turn-" + str(time.time_ns()), "status": "inProgress"}
        event("turn/started", thread_id, turn=turn)
        event("item/completed", thread_id, item={"id": turn["id"] + "-user", "type": "userMessage", "content": params["input"]})
        event("item/completed", thread_id, item={"id": turn["id"] + "-reply", "type": "agentMessage", "text": "Message received in this conversation. The other tabs are unchanged."})
        event("turn/completed", thread_id, turn={**turn, "status": "completed"})
        result = {"turn": turn}
    elif method == "turn/interrupt":
        event("turn/completed", params["threadId"], turn={"id": params["turnId"], "status": "interrupted"})
    elif method == "fs/readFile":
        result = {"dataBase64": base64.b64encode(pathlib.Path(params["path"]).read_bytes()).decode()}
    elif method == "fs/createDirectory":
        pathlib.Path(params["path"]).mkdir(parents=params.get("recursive", False), exist_ok=True)
    elif method == "fs/writeFile":
        pathlib.Path(params["path"]).write_bytes(base64.b64decode(params["dataBase64"]))
    if "id" in request:
        emit({"id": request["id"], "result": result})


for line in sys.stdin:
    try:
        respond(json.loads(line))
    except Exception as error:
        emit({"id": request.get("id") if "request" in globals() else None, "error": {"code": -32000, "message": str(error)}})
