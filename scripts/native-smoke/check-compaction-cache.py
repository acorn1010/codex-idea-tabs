"""Check real WSL Codex compaction and GLM prompt prefixes without replacing the live GPU cache."""
import argparse
import copy
import http.server
import json
import pathlib
import queue
import subprocess
import tempfile
import threading
import urllib.request


class Upstream(http.server.BaseHTTPRequestHandler):
    """Supply deterministic Responses streams while recording actual Codex requests."""
    requests = []

    def do_POST(self):
        """Return a text response without executing tools or generating tokens."""
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.requests.append(request)
        metadata = json.loads(request.get('client_metadata', {}).get('x-codex-turn-metadata', '{}'))
        text = 'Goal: test fixture. Completed: saved the marker. Next step: verify it.' if metadata.get('request_kind') == 'compaction' else 'Fixture marker saved.'
        item = {'id': 'message-' + str(len(self.requests)), 'type': 'message', 'role': 'assistant', 'content': [{'type': 'output_text', 'text': text}]}
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        for event in [
            {'type': 'response.created', 'response': {'id': 'fixture-response'}},
            {'type': 'response.output_item.done', 'item': item},
            {'type': 'response.completed', 'response': {'id': 'fixture-response', 'status': 'completed', 'output': [item], 'usage': {'input_tokens': 1000, 'output_tokens': 20, 'total_tokens': 1020}}},
        ]:
            self.wfile.write(('event: ' + event['type'] + '\ndata: ' + json.dumps(event) + '\n\n').encode())
        self.wfile.flush()

    def log_message(self, *_args):
        """Hide routine fixture access logs."""


def linux(path):
    """Map native test files to the same filesystem seen by WSL."""
    path = str(path).replace('\\', '/')
    if path.startswith('//wsl.localhost/Ubuntu/'):
        return '/' + path.split('/Ubuntu/', 1)[1]
    return '/mnt/' + path[0].lower() + path[2:]


def render(request):
    """Use the loaded GLM template without inference or GPU cache changes."""
    messages = [{'role': 'system', 'content': request['instructions']}]
    for item in request['input']:
        if item.get('type', 'message') != 'message':
            raise AssertionError('This fixture should contain only text messages')
        content = item.get('content', [])
        text = content if isinstance(content, str) else ''.join(part.get('text', '') for part in content)
        messages.append({'role': 'system' if item['role'] == 'developer' else item['role'], 'content': text})
    tools = []
    for tool in request['tools']:
        nested = tool['tools'] if tool['type'] == 'namespace' else [tool]
        for function in nested:
            assert function['type'] == 'function'
            definition = {key: value for key, value in function.items() if key != 'type'}
            if tool['type'] == 'namespace':
                definition['name'] = tool['name'] + '.' + definition['name']
            tools.append({'type': 'function', 'function': definition})
    body = {'messages': messages, 'tools': tools, 'tool_choice': request['tool_choice'], 'add_generation_prompt': True}
    query = urllib.request.Request('http://127.0.0.1:30001/apply-template', data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(query, timeout=15) as response:
            return json.load(response)['prompt']
    except urllib.error.HTTPError as error:
        raise AssertionError(error.read().decode()[:500])


def common_prefix(first, second):
    """Count the exact shared prompt prefix, which is eligible for cache reuse."""
    for index, (a, b) in enumerate(zip(first, second)):
        if a != b:
            return index
    return min(len(first), len(second))


def main():
    """Run the real app-server and relay with isolated history and deterministic responses."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--relay', type=pathlib.Path, default=pathlib.Path(__file__).parents[2] / 'core/src/main/resources/wsl-model-relay.py')
    parser.add_argument('--output', type=pathlib.Path, required=True)
    options = parser.parse_args()
    upstream = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    with tempfile.TemporaryDirectory(prefix='codex-compaction-probe-') as directory:
        root = pathlib.Path(directory)
        (root / 'project').mkdir()
        (root / 'codex').mkdir()
        relay = subprocess.Popen(['wsl.exe', '-d', 'Ubuntu', '--exec', 'python3', linux(options.relay), f'http://127.0.0.1:{upstream.server_port}/v1', '/mnt/c/Windows/System32/curl.exe'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        endpoint = relay.stdout.readline().strip()
        if not endpoint.startswith('http://127.0.0.1:'):
            raise AssertionError('The WSL relay failed to start')
        args = ['wsl.exe', '-d', 'Ubuntu', '--exec', 'env', 'CODEX_HOME=' + linux(root / 'codex'), '/home/acorn/.local/bin/codex']
        for key, value in {'name': 'Compaction probe', 'base_url': endpoint, 'wire_api': 'responses', 'requires_openai_auth': False}.items():
            args += ['-c', 'model_providers.probe.' + key + '=' + json.dumps(value)]
        args += ['-c', 'model_provider=\"probe\"', '-c', 'model=\"glm-flash-uncensored\"']
        with (root / 'codex-stderr.log').open('w') as errors:
            codex = subprocess.Popen(args + ['app-server'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, text=True, encoding='utf-8')
            frames = queue.Queue()
            def read():
                """Drain the fixture's app-server notifications."""
                for line in codex.stdout:
                    frames.put(json.loads(line))
            threading.Thread(target=read, daemon=True).start()
            sequence = 0
            def rpc(method, params):
                """Send a normal app-server request and wait for its matching response."""
                nonlocal sequence
                sequence += 1
                codex.stdin.write(json.dumps({'id': sequence, 'method': method, 'params': params}) + '\n')
                codex.stdin.flush()
                while True:
                    try:
                        frame = frames.get(timeout=45)
                    except queue.Empty:
                        raise AssertionError((root / 'codex-stderr.log').read_text()[-3000:])
                    if frame.get('id') == sequence:
                        if 'error' in frame:
                            raise AssertionError(frame['error'])
                        return frame['result']
            def wait(method, kind=None):
                """Wait for the real turn or compaction lifecycle to complete."""
                while True:
                    frame = frames.get(timeout=45)
                    if frame.get('method') == method and (kind is None or frame.get('params', {}).get('item', {}).get('type') == kind):
                        return frame
            try:
                rpc('initialize', {'clientInfo': {'name': 'compaction_cache_probe', 'version': '1'}, 'capabilities': {'experimentalApi': True}})
                thread = rpc('thread/start', {'model': 'glm-flash-uncensored', 'modelProvider': 'probe', 'cwd': linux(root / 'project'), 'ephemeral': True, 'sandbox': 'read-only', 'approvalPolicy': 'never', 'config': {'model_context_window': 98304, 'model_reasoning_effort': 'none', 'web_search': 'disabled', 'features.unified_exec': False, 'features.apply_patch_freeform': False}})['thread']['id']
                rpc('turn/start', {'threadId': thread, 'input': [{'type': 'text', 'text': 'Do not use tools. Save this fixture marker: BLUE-FERN-42. ' + 'The fixture is unchanged. ' * 1000}]})
                wait('turn/completed')
                rpc('thread/compact/start', {'threadId': thread})
                wait('item/completed', 'contextCompaction')
                normal, compact = Upstream.requests[-2:]
                assert normal['tools'] and compact['tools'] == normal['tools'], 'Compaction did not preserve the actual Codex tool inventory'
                assert compact['tool_choice'] == 'none', 'Compaction tool calls were not disabled'
                assert normal['instructions'] == compact['instructions']
                assert normal['prompt_cache_key'] == compact['prompt_cache_key']
                before = copy.deepcopy(compact)
                before['tools'] = []
                before['tool_choice'] = 'auto'
                normal_prompt, old_prompt, fixed_prompt = render(normal), render(before), render(compact)
                preserved = common_prefix(normal_prompt, fixed_prompt)
                result = {'realCodexCompaction': True, 'windowsCurlBridge': True, 'toolsDisabled': True, 'normalPromptChars': len(normal_prompt), 'oldPrefixChars': common_prefix(normal_prompt, old_prompt), 'fixedPrefixChars': preserved, 'sharedPrefixFraction': preserved / len(normal_prompt)}
                assert result['sharedPrefixFraction'] > 0.95, result
                options.output.parent.mkdir(parents=True, exist_ok=True)
                options.output.write_text(json.dumps(result, indent=2) + '\n')
                print(json.dumps(result, indent=2))
            finally:
                codex.stdin.close()
                try:
                    codex.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    codex.terminate()
                    codex.wait(timeout=10)
                codex.stdout.close()
                relay.stdin.close()
                relay.wait(timeout=10)
                relay.stdout.close()
    upstream.shutdown()
    upstream.server_close()


if __name__ == '__main__':
    main()
