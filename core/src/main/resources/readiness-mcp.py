"""Expose one bounded, read-only HTTP readiness check over MCP stdio.

Polling stays inside this process so identical startup failures do not fill the
model context. Curl enforces probe deadlines, including slow response headers.
"""
import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse

TOOL = {
    'name': 'wait_http',
    'description': 'Wait for a local HTTP server to become ready using one bounded call instead of repeated shell health checks. Start the server once, then call this tool. Default: 60 seconds, polling every 2 seconds, HTTP 200. Maximum: 300 seconds. On timeout, inspect logs or fix the server before trying again. This checks the HTTP status only. Use environment=windows for a Windows server when Codex runs in WSL, otherwise use current.',
    'inputSchema': {'type': 'object', 'additionalProperties': False, 'required': ['url'], 'properties': {
        'url': {'type': 'string', 'description': 'HTTP URL on localhost, 127.0.0.1 or [::1], including the server port and health path.'},
        'timeout_seconds': {'type': 'number', 'minimum': 1, 'maximum': 300, 'default': 60},
        'interval_seconds': {'type': 'number', 'minimum': 1, 'maximum': 30, 'default': 2},
        'expected_status': {'type': 'integer', 'minimum': 100, 'maximum': 599, 'default': 200},
        'environment': {'type': 'string', 'enum': ['current', 'windows'], 'default': 'current'},
    }},
    'annotations': {'readOnlyHint': True, 'destructiveHint': False, 'idempotentHint': True, 'openWorldHint': False},
}


def _number(arguments, key, default, minimum, maximum):
    value = arguments.get(key, default)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not minimum <= value <= maximum:
        raise ValueError(f'{key} must be between {minimum} and {maximum}.')
    return value


def _validate(arguments, windows_curl):
    if not isinstance(arguments, dict) or set(arguments) - set(TOOL['inputSchema']['properties']):
        raise ValueError('Use only the documented wait_http arguments.')
    url = arguments.get('url', '')
    if not isinstance(url, str) or any(ord(char) <= 32 or ord(char) == 127 for char in url):
        raise ValueError('Use an HTTP URL without spaces or control characters.')
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'http' or parsed.hostname not in {'localhost', '127.0.0.1', '::1'} or parsed.username is not None or parsed.password is not None or parsed.fragment:
        raise ValueError('Use an HTTP loopback URL without credentials or a fragment.')
    # Reading port also rejects invalid and out-of-range port strings.
    if parsed.port == 0:
        raise ValueError('Use a nonzero server port.')
    timeout = _number(arguments, 'timeout_seconds', 60, 1, 300)
    interval = _number(arguments, 'interval_seconds', 2, 1, 30)
    status = _number(arguments, 'expected_status', 200, 100, 599)
    if int(status) != status:
        raise ValueError('expected_status must be a whole HTTP status code.')
    environment = arguments.get('environment', 'current')
    if environment not in {'current', 'windows'}:
        raise ValueError('environment must be current or windows.')
    curl = windows_curl if environment == 'windows' else shutil.which('curl')
    if not curl:
        raise ValueError('Curl is required in the selected environment. For a WSL server, use current.')
    return url, timeout, interval, int(status), curl


def _result(ready, reason, status=None):
    value = {'ready': ready, 'reason': reason, 'http_status': status}
    return {'content': [{'type': 'text', 'text': json.dumps(value)}], 'structuredContent': value, 'isError': not ready}


def _wait(arguments, cancelled, windows_curl):
    url, timeout, interval, expected, curl = _validate(arguments, windows_curl)
    deadline = time.monotonic() + timeout
    status = None
    while not cancelled.is_set():
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        probe_time = min(5, remaining)
        # Do not follow redirects or retain the body. No cookies, auth, proxy or shell.
        command = [curl, '--disable', '--silent', '--noproxy', '*', '--globoff', '--output',
            'NUL' if curl.lower().endswith('.exe') else os.devnull,
            '--write-out', '%{http_code}', '--max-time', str(probe_time), '--url', url]
        flags = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, **flags) as process:
            while True:
                if cancelled.is_set() or time.monotonic() >= deadline:
                    process.kill()
                    process.communicate()
                    return _result(False, 'cancelled' if cancelled.is_set() else 'timeout', status)
                try:
                    output, _ = process.communicate(timeout=min(.1, max(.001, deadline - time.monotonic())))
                    break
                except subprocess.TimeoutExpired:
                    continue
            status = int(output) if process.returncode == 0 and output.isdigit() else None
        if cancelled.is_set():
            return _result(False, 'cancelled', status)
        if status == expected:
            return _result(True, 'ready', status)
        cancelled.wait(min(interval, max(0, deadline - time.monotonic())))
    return _result(False, 'cancelled' if cancelled.is_set() else 'timeout', status)


def main():
    """Serve MCP requests until stdin closes, cancelling active probes on exit."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--windows-curl', default=shutil.which('curl') if os.name == 'nt' else None)
    options = parser.parse_args()
    lock = threading.Lock()
    active = {}

    def reply(sequence, result=None, error=None):
        with lock:
            frame = {'jsonrpc': '2.0', 'id': sequence}
            frame['error' if error is not None else 'result'] = error if error is not None else result
            print(json.dumps(frame), flush=True)

    def run(sequence, arguments, cancelled):
        try:
            result = _wait(arguments, cancelled, options.windows_curl)
        except (ValueError, OSError) as error:
            result = {'content': [{'type': 'text', 'text': str(error)}], 'isError': True}
        with lock:
            active.pop(sequence, None)
        reply(sequence, result)

    try:
        for line in sys.stdin:
            try:
                frame = json.loads(line)
                sequence = frame.get('id')
                method = frame.get('method')
                params = frame.get('params', {})
                if method == 'notifications/cancelled':
                    with lock:
                        entry = active.get(params.get('requestId'))
                        if entry:
                            entry[0].set()
                elif sequence is None:
                    continue
                elif method == 'initialize':
                    reply(sequence, {'protocolVersion': params.get('protocolVersion', '2024-11-05'), 'capabilities': {'tools': {}}, 'serverInfo': {'name': 'codex-tabs-readiness', 'version': '1.0.20'}})
                elif method == 'ping':
                    reply(sequence, {})
                elif method == 'tools/list':
                    reply(sequence, {'tools': [TOOL]})
                elif method == 'tools/call' and params.get('name') == 'wait_http':
                    with lock:
                        busy = bool(active)
                        if not busy:
                            cancelled = threading.Event()
                            worker = threading.Thread(target=run, args=(sequence, params.get('arguments', {}), cancelled), daemon=True)
                            active[sequence] = (cancelled, worker)
                    if busy:
                        reply(sequence, {'content': [{'type': 'text', 'text': 'A readiness wait is already active. Wait for it to finish or cancel it.'}], 'isError': True})
                    else:
                        worker.start()
                else:
                    reply(sequence, error={'code': -32601, 'message': 'Unknown readiness MCP method or tool.'})
            except (ValueError, TypeError, AttributeError):
                reply(None, error={'code': -32700, 'message': 'Invalid readiness JSON-RPC request.'})
    finally:
        with lock:
            pending = list(active.values())
        for cancelled, _ in pending:
            cancelled.set()
        for _, worker in pending:
            worker.join(timeout=2)


if __name__ == '__main__':
    main()
