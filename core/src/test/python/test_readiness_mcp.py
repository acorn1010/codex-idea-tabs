"""Exercise bounded readiness waits through the shipped MCP stdio protocol."""
import http.server
import json
import pathlib
import queue
import subprocess
import sys
import threading
import time
import unittest


class Health(http.server.BaseHTTPRequestHandler):
    """Model startup, a redirect and a server that never sends headers."""
    attempts = 0

    def do_GET(self):
        """Serve deterministic health responses without logging probes."""
        if self.path == '/slow':
            time.sleep(5)
            return
        if self.path == '/redirect':
            self.send_response(302)
            self.send_header('Location', '/ready')
        else:
            type(self).attempts += 1
            status = 200 if self.path == '/ready' or (self.path == '/starting' and self.attempts >= 6) else 503
            self.send_response(status)
        self.send_header('Content-Length', '0')
        self.end_headers()

    def log_message(self, *_args):
        """Keep test output clear."""


class ReadinessTest(unittest.TestCase):
    """Check real curl requests, deadlines and cancellation through JSON-RPC."""

    def setUp(self):
        """Start an isolated MCP process and HTTP health fixture."""
        Health.attempts = 0
        self.http = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Health)
        threading.Thread(target=self.http.serve_forever, daemon=True).start()
        self.url = f'http://127.0.0.1:{self.http.server_port}'
        script = pathlib.Path(__file__).parents[2] / 'main/resources/readiness-mcp.py'
        self.process = subprocess.Popen([sys.executable, str(script)], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8')
        self.frames = queue.Queue()
        def read():
            for line in self.process.stdout:
                self.frames.put(json.loads(line))
        threading.Thread(target=read, daemon=True).start()
        self.send(1, 'initialize', {'protocolVersion': '2024-11-05', 'capabilities': {}, 'clientInfo': {'name': 'test', 'version': '1'}})
        self.assertEqual('2024-11-05', self.receive(1)['result']['protocolVersion'])
        self.send(None, 'notifications/initialized', {})

    def tearDown(self):
        """Stop only this test's child process and server."""
        self.process.stdin.close()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=3)
        self.process.stdout.close()
        self.process.stderr.close()
        self.http.shutdown()
        self.http.server_close()

    def send(self, sequence, method, params):
        """Send one MCP frame without shell interpretation."""
        frame = {'jsonrpc': '2.0', 'method': method, 'params': params}
        if sequence is not None:
            frame['id'] = sequence
        self.process.stdin.write(json.dumps(frame) + '\n')
        self.process.stdin.flush()

    def receive(self, sequence):
        """Read a response with a test deadline so failures cannot hang CI."""
        frame = self.frames.get(timeout=12)
        self.assertEqual(sequence, frame['id'])
        return frame

    def call(self, path, **options):
        """Run a single normal MCP tools/call request."""
        self.send(2, 'tools/call', {'name': 'wait_http', 'arguments': {'url': self.url + path, **options}})
        frame = self.receive(2)
        self.assertNotIn('error', frame)
        return frame['result']

    def test_lists_the_bounded_tool_and_survives_six_unchanged_startup_checks(self):
        """Startup can take more than five probes without model turns between them."""
        self.send(3, 'tools/list', {})
        tool = self.receive(3)['result']['tools'][0]
        self.assertEqual('wait_http', tool['name'])
        self.assertEqual(300, tool['inputSchema']['properties']['timeout_seconds']['maximum'])
        result = self.call('/starting', timeout_seconds=9, interval_seconds=1)
        self.assertTrue(result['structuredContent']['ready'])
        self.assertEqual(6, Health.attempts)
        self.assertFalse(result.get('isError', False))

    def test_timeout_is_an_error_and_has_no_changing_timing_noise(self):
        """Repeated timeouts remain comparable by the harness loop guard."""
        started = time.monotonic()
        first = self.call('/down', timeout_seconds=1)
        self.assertLess(time.monotonic() - started, 2.5)
        self.assertTrue(first['isError'])
        self.assertEqual('timeout', first['structuredContent']['reason'])
        self.assertEqual(first, self.call('/down', timeout_seconds=1))

    def test_deadline_limits_slow_servers_and_does_not_follow_redirects(self):
        """A stalled response and a redirect cannot bypass the wait deadline."""
        started = time.monotonic()
        self.assertTrue(self.call('/slow', timeout_seconds=1)['isError'])
        self.assertLess(time.monotonic() - started, 2.5)
        self.assertTrue(self.call('/redirect', timeout_seconds=1)['isError'])

    def test_validates_limits_urls_and_expected_status_before_any_request(self):
        """The read-only tool permits only explicit loopback HTTP GET probes."""
        for options in [{'timeout_seconds': 301}, {'timeout_seconds': 0}, {'timeout_seconds': True},
                {'interval_seconds': 0}, {'interval_seconds': 31}, {'expected_status': 99},
                {'url': 'https://example.com/'}, {'url': 'http://user:pass@localhost/'},
                {'url': 'http://127.0.0.1:1/\r\nX: y'}, {'environment': 'elsewhere'}, {'extra': True}]:
            self.send(4, 'tools/call', {'name': 'wait_http', 'arguments': {'url': self.url + '/ready', **options}})
            self.assertTrue(self.receive(4)['result']['isError'], options)
        self.assertEqual(0, Health.attempts)
        self.assertTrue(self.call('/down', expected_status=503)['structuredContent']['ready'])

    def test_cancellation_and_disconnect_end_the_wait_promptly(self):
        """Stopping a turn or closing Codex must not leave an active polling child."""
        self.send(5, 'tools/call', {'name': 'wait_http', 'arguments': {'url': self.url + '/slow', 'timeout_seconds': 300}})
        time.sleep(.25)
        started = time.monotonic()
        self.send(None, 'notifications/cancelled', {'requestId': 5})
        self.assertEqual('cancelled', self.receive(5)['result']['structuredContent']['reason'])
        self.assertLess(time.monotonic() - started, 2)
        self.send(6, 'tools/call', {'name': 'wait_http', 'arguments': {'url': self.url + '/slow', 'timeout_seconds': 300}})
        time.sleep(.25)
        self.process.stdin.close()
        self.process.wait(timeout=3)


if __name__ == '__main__':
    unittest.main()
