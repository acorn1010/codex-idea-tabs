"""Let a WSL harness reach a Windows-local model through Windows curl and pipes."""
import http.server
import json
from collections import OrderedDict
import subprocess
import sys
import threading
import urllib.parse

UPSTREAM = urllib.parse.urlsplit(sys.argv[1])
CURL = sys.argv[2]
ACTIVE = set()
LOCK = threading.Lock()
MAX_CACHED_TOOL_SETS = 128
TOOL_SETS = OrderedDict()


def compaction_body(body):
    """Keep the prompt prefix for known Codex compactions without enabling tool calls."""
    try:
        request = json.loads(body)
        if not isinstance(request, dict):
            return body
        metadata = request.get('client_metadata', {})
        if not isinstance(metadata, dict):
            return body
        turn = json.loads(metadata.get('x-codex-turn-metadata', '{}'))
        if not isinstance(turn, dict):
            return body
    except (ValueError, TypeError):
        return body
    identity = (request.get('model'), request.get('prompt_cache_key'), request.get('instructions', ''))
    if not all(isinstance(value, str) for value in identity) or not identity[0] or not identity[1]:
        return body
    tools = request.get('tools', [])
    if not isinstance(tools, list):
        return body
    with LOCK:
        if turn.get('request_kind') != 'compaction':
            TOOL_SETS.pop(identity, None)
            if tools:
                TOOL_SETS[identity] = tools
                if len(TOOL_SETS) > MAX_CACHED_TOOL_SETS:
                    TOOL_SETS.popitem(last=False)
            return body
        saved = TOOL_SETS.get(identity)
        if tools or not saved:
            return body
        request['tools'] = saved
        request['tool_choice'] = 'none'
    return json.dumps(request, ensure_ascii=False).encode('utf-8')


READINESS_NAMESPACE = 'mcp__codex_tabs_readiness'
READINESS_FUNCTION = READINESS_NAMESPACE + '__wait_http'


def readiness_body(body):
    """Translate only the bundled readiness namespace into a llama.cpp function."""
    try:
        request = json.loads(body)
        if not isinstance(request, dict) or not isinstance(request.get('tools'), list):
            return body, False
        tools = []
        changed = False
        for tool in request['tools']:
            if isinstance(tool, dict) and tool.get('type') == 'namespace' and tool.get('name') == READINESS_NAMESPACE:
                for function in tool.get('tools', []):
                    if function.get('type') == 'function' and function.get('name') == 'wait_http':
                        tools.append({**function, 'name': READINESS_FUNCTION})
                        changed = True
            else:
                tools.append(tool)
        if not changed:
            return body, False
        request['tools'] = tools
        for item in request.get('input', []):
            if isinstance(item, dict) and item.get('type') == 'function_call' and item.get('namespace') == READINESS_NAMESPACE and item.get('name') == 'wait_http':
                item.pop('namespace')
                item['name'] = READINESS_FUNCTION
        return json.dumps(request, ensure_ascii=False).encode('utf-8'), True
    except (ValueError, TypeError, AttributeError):
        return body, False


def readiness_event(line):
    """Restore the Codex namespace on streamed tool items without changing text."""
    if not line.startswith(b'data: '):
        return line
    try:
        event = json.loads(line[6:])
        items = [event.get('item')] + event.get('response', {}).get('output', [])
        changed = False
        for item in items:
            if isinstance(item, dict) and item.get('type') == 'function_call' and item.get('name') == READINESS_FUNCTION:
                item['namespace'] = READINESS_NAMESPACE
                item['name'] = 'wait_http'
                changed = True
        return b'data: ' + json.dumps(event, ensure_ascii=False).encode('utf-8') + b'\n' if changed else line
    except (ValueError, TypeError, AttributeError):
        return line


class Relay(http.server.BaseHTTPRequestHandler):
    """Forward inference requests without exposing the Windows server to a network."""

    def do_GET(self):
        """Forward the model catalog request."""
        self.forward()

    def do_POST(self):
        """Forward JSON requests and flush each streamed response chunk."""
        self.forward()

    def forward(self):
        """Use native Windows curl so localhost points to the Windows model server."""
        if not self.path.startswith('/v1/'):
            self.send_error(404, 'Only model API requests are supported.')
            return
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        readiness = False
        if self.command == 'POST' and urllib.parse.urlsplit(self.path).path == '/v1/responses':
            body, readiness = readiness_body(compaction_body(body))
        url = urllib.parse.urlunsplit((UPSTREAM.scheme, UPSTREAM.netloc, '', '', '')) + self.path
        command = [CURL, '--silent', '--show-error', '--no-buffer', '--noproxy', '*', '--max-time', '3600', '--dump-header', '-', '--request', self.command, '--header', 'Content-Type: application/json', '--header', 'Expect:', '--url', url]
        if self.command == 'POST':
            command += ['--data-binary', '@-']
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        with LOCK:
            ACTIVE.add(process)
        try:
            if body:
                process.stdin.write(body)
            process.stdin.close()
            status = process.stdout.readline().decode('latin-1').strip()
            if not status.startswith('HTTP/'):
                error = process.stderr.read(2048).decode('utf-8', errors='replace')
                self.send_error(502, 'Could not reach the Windows model server. Start the model and retry. ' + error.strip())
                return
            self.send_response(int(status.split()[1]))
            content_type = ''
            while True:
                line = process.stdout.readline().decode('latin-1').strip()
                if not line:
                    break
                name, value = line.split(':', 1)
                if name.lower() == 'content-type':
                    content_type = value.strip()
                if name.lower() == 'content-type' or (name.lower() == 'content-length' and not readiness):
                    self.send_header(name, value.strip())
            self.send_header('Connection', 'close')
            self.end_headers()
            while True:
                streamed_readiness = readiness and content_type.startswith('text/event-stream')
                chunk = process.stdout.readline() if streamed_readiness else process.stdout.read1(8192)
                if streamed_readiness:
                    chunk = readiness_event(chunk)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            with LOCK:
                ACTIVE.discard(process)
            if process.poll() is None:
                process.terminate()
            process.stdout.close()
            process.stderr.close()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Relay)
server.daemon_threads = True
threading.Thread(target=server.serve_forever, daemon=True).start()
print(f'http://127.0.0.1:{server.server_port}/v1', flush=True)
sys.stdin.buffer.read()
server.shutdown()
server.server_close()
with LOCK:
    processes = list(ACTIVE)
for process in processes:
    process.terminate()
