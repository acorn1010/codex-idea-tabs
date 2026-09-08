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
titles = {"review": "Review session recovery", "choices": "Choose the composer layout", "image": "Preview the new artwork", "build": "Check the build", "archive": "Archive workflow check"}
archive_file = root / "archived.json"
archived = set(json.loads(archive_file.read_text())) if archive_file.exists() else set()
history_file = root / "edit-history.json"
histories = json.loads(history_file.read_text()) if history_file.exists() else {}
failed_sends = set()
subscriptions = set()
resumes = {}
unsubscribes = {}
statuses = {}


def emit(value):
    """Serialize whole JSONL frames when events and replies run together."""
    with lock:
        print(json.dumps(value), flush=True)


def event(method, thread, **params):
    emit({"method": method, "params": {"threadId": thread, **params}})


def context_inputs():
    """Synthetic rules include one repeated paragraph for inspector checks."""
    paragraph = "Preserve the existing public behavior while making the requested change. Verify that the full output remains the same before accepting an optimization."
    def message(role, text):
        return {"type": "message", "role": role, "content": [{"type": "input_text", "text": text}]}
    return [
        message("developer", "# Project rules\n\n" + paragraph + "\n\nRead /project/AGENTS.md before editing."),
        message("developer", "### Available skills\n- review: Inspect changes at /project/.agents/skills/review/SKILL.md\n\n" + paragraph),
        message("user", "IDE context:\nOpen files:\n/project/client/src/pages/ShopPage.tsx\n\nKeep this draft unchanged while inspecting context."),
    ]


def context_record(thread_id):
    """Write an actual JSONL session fixture for Windows-to-WSL read checks."""
    path = root / (thread_id + ".jsonl")
    if not path.exists():
        records = [
            {"type": "session_meta", "payload": {"base_instructions": {"text": "Fixture base instructions"}}},
            {"type": "response_item", "payload": {"type": "message", "role": "user", "content": [{"type": "input_text", "text": "OLD_CONTEXT_BEFORE_COMPACTION"}]}},
            {"type": "compacted", "payload": {"replacement_history": context_inputs()}},
            {"type": "response_item", "payload": {"type": "custom_tool_call", "name": "functions.exec", "call_id": "context-read", "input": "cat /project/.agents/skills/review/SKILL.md"}},
            {"type": "response_item", "payload": {"type": "custom_tool_call_output", "call_id": "context-read", "output": "# Review skill\nCheck scope and preserve unrelated work.\n"}},
            {"type": "event_msg", "payload": {"type": "item_completed", "item": {"text": "DISPLAY_EVENT_MUST_NOT_BE_COUNTED"}}},
        ]
        path.write_text("\n".join(json.dumps(record) for record in records) + "\n")
    return path


def thread(thread_id):
    return {"path": str(context_record(thread_id)) if thread_id.startswith("context-") else None, "id": thread_id, "name": titles.get(thread_id, "New task"), "preview": "", "cwd": str(root), "turns": turns(thread_id), "createdAt": 1, "updatedAt": 1, "status": {"type": statuses.get(thread_id, "idle")}}


def items(thread_id):
    if thread_id.startswith("activity-"):
        return [
            {"id": "activity-user", "type": "userMessage", "content": [{"type": "text", "text": "Run the workspace checks and summarize the results."}]},
            {"id": "activity-thinking", "type": "reasoning", "summary": ["Check each workspace before summarizing."]},
            *[{"id": f"activity-command-{index}", "type": "commandExecution", "command": f"npm run check -- workspace-{index}", "status": "completed", "exitCode": 1 if index == 7 else 0, "aggregatedOutput": "Check failed: missing import" if index == 7 else "All checks passed"} for index in range(20)],
            {"id": "activity-files", "type": "fileChange", "status": "completed", "changes": [{"path": str(root / "preview.txt")}]},
            {"id": "activity-answer", "type": "agentMessage", "text": "The workspace checks finished. One import needs a fix."},
        ]
    messages = [
        {"id": thread_id + "-user", "type": "userMessage", "content": [{"type": "text", "text": "Keep chats fast to open, and make it clear when you need my input."}]},
        {"id": thread_id + "-tool", "type": "commandExecution", "command": "npm test", "status": "completed", "aggregatedOutput": "12 tests passed in 0.8 seconds", "exitCode": 0},
        {"id": thread_id + "-answer", "type": "agentMessage", "text": "Each chat has its own editor tab and keeps running while you work elsewhere.\n\n- **Blue** means work is in progress.\n- **Amber** means a question or approval needs you.\n- **Green** marks an unread result.\n\nThe composer keeps the workspace, model, and permissions on one compact row."},
    ]
    if thread_id.startswith("edit-images-"):
        messages[0]["content"].append({"type": "localImage", "path": str(image)})
    if thread_id == "image":
        messages[-1]["text"] = "The generated image is ready. You can preview it here or open it in the editor."
        messages.append({"id": "generated-art", "type": "imageGeneration", "status": "completed", "savedPath": str(image), "result": ""})
    return messages


def turns(thread_id):
    """Keep completed turns and attachments available for fork and restart checks."""
    if thread_id not in histories:
        histories[thread_id] = [] if thread_id.startswith("new-") else [{"id": thread_id + "-first", "status": "completed", "items": items(thread_id)}]
        if thread_id == "review":
            histories[thread_id].extend([
                {"id": "review-second", "status": "completed", "items": [
                    {"id": "review-edit", "type": "userMessage", "content": [{"type": "text", "text": "Please focus on tab restoration."}, {"type": "localImage", "path": str(image)}]},
                    {"id": "review-progress", "type": "agentMessage", "text": "Checking the old approach."},
                    {"id": "review-steer", "type": "userMessage", "content": [{"type": "text", "text": "Keep the old toolbar."}]},
                    {"id": "review-second-reply", "type": "agentMessage", "text": "The old toolbar was kept."},
                ]},
                {"id": "review-third", "status": "completed", "items": [{"id": "review-later", "type": "userMessage", "content": [{"type": "text", "text": "Later instruction must not enter edited history."}]}]},
            ])
    return histories[thread_id]


def save_history():
    """Persist only this fixture's synthetic conversations."""
    history_file.write_text(json.dumps(histories))


def attention(thread_id):
    time.sleep(1)
    if thread_id == "choices":
        event("item/completed", thread_id, item={"id": "fixture-choice", "type": "agentMessage", "delivery": "async", "text": "", "questions": [{"title": "Where should new conversations open?", "options": ["Beside the current chat", "In the current group"]}]})
    if thread_id == "build" or thread_id.startswith("steer-"):
        event("turn/started", thread_id, turn={"id": "fixture-build", "status": "inProgress"})


def respond(request):
    method, params = request.get("method"), request.get("params", {})
    result = {}
    if method == "initialize":
        result = {"userAgent": "codex-tabs-fixture"}
    elif method == "account/read":
        result = {"account": {"type": "chatgpt", "planType": "pro"}, "requiresOpenaiAuth": True}
    elif method == "model/list":
        result = {"data": [
            {"id": "gpt-6-astra", "model": "gpt-6-astra", "displayName": "GPT-6-Astra", "isDefault": True, "defaultReasoningEffort": "medium", "supportedReasoningEfforts": [{"reasoningEffort": value, "description": value} for value in ["low", "medium", "high", "xhigh"]]},
            {"id": "fixture-model", "model": "fixture-model", "displayName": "Test model", "defaultReasoningEffort": "low", "supportedReasoningEfforts": [{"reasoningEffort": value, "description": value} for value in ["low", "high"]]},
        ]}
    elif method == "thread/list":
        result = {"data": [thread(key) for key in titles if (key in archived) == params.get("archived", False)], "nextCursor": None}
    elif method in ("thread/archive", "thread/unarchive"):
        thread_id = params["threadId"]
        if method == "thread/archive":
            if thread_id in ("build", "choices"):
                raise RuntimeError("Cannot archive active fixture work")
            archived.add(thread_id)
        else:
            archived.discard(thread_id)
        archive_file.write_text(json.dumps(sorted(archived)))
        event("thread/archived" if method == "thread/archive" else "thread/unarchived", thread_id)
        result = {} if method == "thread/archive" else {"thread": thread(thread_id)}
    elif method == "fixture/stats":
        result = {"subscriptions": sorted(subscriptions), "resumes": resumes, "unsubscribes": unsubscribes}
    elif method == "fixture/event":
        notification = params["event"]
        thread_id = notification["params"]["threadId"]
        if notification["method"] == "turn/started":
            statuses[thread_id] = "active"
        elif notification["method"] == "turn/completed":
            statuses[thread_id] = "idle"
        emit(notification)
    elif method == "thread/unsubscribe":
        thread_id = params["threadId"]
        unsubscribes[thread_id] = unsubscribes.get(thread_id, 0) + 1
        result = {"status": "unsubscribed" if thread_id in subscriptions else "notSubscribed"}
        subscriptions.discard(thread_id)
    elif method == "thread/resume":
        thread_id = params["threadId"]
        resumes[thread_id] = resumes.get(thread_id, 0) + 1
        if thread_id.startswith("lifecycle-retry-") and resumes[thread_id] == 1:
            raise RuntimeError("Fixture resume needs a retry")
        subscriptions.add(thread_id)
        result = {"thread": thread(thread_id)}
        threading.Thread(target=attention, args=(params["threadId"],), daemon=True).start()
    elif method == "thread/read":
        result = {"thread": thread(params["threadId"])}
    elif method == "thread/items/list":
        entries = [{"item": item, "turnId": turn["id"]} for turn in turns(params["threadId"]) for item in turn["items"]]
        result = {"data": list(reversed(entries)), "nextCursor": None}
    elif method == "thread/turns/list":
        entries = turns(params["threadId"])
        if params.get("sortDirection") == "desc":
            entries = list(reversed(entries))
        # A tiny page deliberately exercises the edit flow's history pagination.
        offset = int(params.get("cursor", "0"))
        result = {"data": entries[offset:offset + 1], "nextCursor": str(offset + 1) if offset + 1 < len(entries) else None}
    elif method == "thread/fork":
        entries = turns(params["threadId"])
        index = next(index for index, turn in enumerate(entries) if turn["id"] == params["lastTurnId"])
        new_id = "new-" + str(time.time_ns())
        histories[new_id] = json.loads(json.dumps(entries[:index + 1]))
        save_history()
        result = {"thread": thread(new_id)}
    elif method == "thread/start":
        result = {"thread": thread("new-" + str(time.time_ns()))}
        subscriptions.add(result["thread"]["id"])
    elif method == "turn/steer" and params["threadId"].startswith("steer-"):
        if params.get("expectedTurnId") != "fixture-build":
            raise RuntimeError("Steer must target the current fixture turn")
        result = {"turnId": "fixture-build"}
        event("item/completed", params["threadId"], turnId="fixture-build", item={"id": "steer-" + str(time.time_ns()), "type": "userMessage", "content": params["input"]})
    elif method in ("turn/start", "turn/steer"):
        thread_id = params["threadId"]
        if "FIXTURE_RETRY_EDIT" in json.dumps(params["input"]) and thread_id not in failed_sends:
            failed_sends.add(thread_id)
            raise RuntimeError("Fixture send failed once. Your edited message is ready to retry.")
        turn = {"id": "turn-" + str(time.time_ns()), "status": "inProgress"}
        user = {"id": turn["id"] + "-user", "type": "userMessage", "content": params["input"]}
        answer = {"id": turn["id"] + "-reply", "type": "agentMessage", "text": "Message received in this conversation. The other tabs are unchanged."}
        turns(thread_id).append({**turn, "status": "completed", "items": [user, answer]})
        save_history()
        event("turn/started", thread_id, turn=turn)
        event("item/completed", thread_id, turnId=turn["id"], item=user)
        event("item/completed", thread_id, turnId=turn["id"], item=answer)
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
    if params.get("excludeTurns") and "thread" in result:
        result["thread"] = {**result["thread"], "turns": []}
    if "id" in request:
        emit({"id": request["id"], "result": result})


if sys.argv[1:3] == ["debug", "prompt-input"]:
    print(json.dumps(context_inputs()))
    raise SystemExit(0)

for line in sys.stdin:
    request = {}
    try:
        request = json.loads(line)
        respond(request)
    except Exception as error:
        emit({"id": request.get("id"), "error": {"code": -32000, "message": str(error)}})
