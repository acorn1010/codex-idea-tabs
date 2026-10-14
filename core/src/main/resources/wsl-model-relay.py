"""Let a WSL harness reach a Windows-local model through Windows curl and pipes."""
import http.server
import subprocess
import sys
import threading
import urllib.parse

UPSTREAM = urllib.parse.urlsplit(sys.argv[1])
CURL = sys.argv[2]
ACTIVE = set()
LOCK = threading.Lock()


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
            while True:
                line = process.stdout.readline().decode('latin-1').strip()
                if not line:
                    break
                name, value = line.split(':', 1)
                if name.lower() in ('content-type', 'content-length'):
                    self.send_header(name, value.strip())
            self.send_header('Connection', 'close')
            self.end_headers()
            while True:
                chunk = process.stdout.read1(8192)
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
