"""Exercise the model relay through its HTTP entry point and real curl child processes."""
import copy
import http.server
import json
import pathlib
import shutil
import subprocess
import sys
import threading
import unittest
import urllib.request


class Upstream(http.server.BaseHTTPRequestHandler):
    """Return the exact request received after the relay forwards it."""

    def do_POST(self):
        """Echo request JSON without executing tools or calling a model."""
        body = self.rfile.read(int(self.headers["Content-Length"]))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        """Keep fixture output free of routine HTTP logs."""


class RelayTest(unittest.TestCase):
    """Verify compaction behavior without borrowing tool definitions across chats."""

    @classmethod
    def setUpClass(cls):
        """Start the actual relay script against an isolated loopback upstream."""
        cls.upstream = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        threading.Thread(target=cls.upstream.serve_forever, daemon=True).start()
        script = pathlib.Path(__file__).parents[2] / "main/resources/wsl-model-relay.py"
        cls.process = subprocess.Popen([sys.executable, str(script),
            f"http://127.0.0.1:{cls.upstream.server_port}/v1", shutil.which("curl")],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        cls.url = cls.process.stdout.readline().strip()
        if not cls.url.startswith("http://127.0.0.1:"):
            raise RuntimeError("The actual model relay did not start")

    @classmethod
    def tearDownClass(cls):
        """Stop only the fixture relay and upstream server."""
        cls.process.stdin.close()
        cls.process.wait(timeout=10)
        cls.process.stdout.close()
        cls.upstream.shutdown()
        cls.upstream.server_close()

    def request(self, value, path="/responses"):
        """Read the request received by the real upstream HTTP server."""
        request = urllib.request.Request(self.url + path, data=json.dumps(value).encode(),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    def normal(self, key):
        """Use the request fields sent by Codex 0.153 for local Responses providers."""
        return {"model": "glm-flash-uncensored", "prompt_cache_key": key,
            "instructions": "Coding assistant instructions.", "tools": [
                {"type": "function", "name": "read_file", "description": "Read a file.",
                 "parameters": {"type": "object", "properties": {}}}],
            "tool_choice": "auto", "input": [{"role": "user", "content": "Keep this history."}],
            "client_metadata": {"x-codex-turn-metadata": json.dumps({"request_kind": "main"})}}

    def compact(self, value):
        """Mirror Codex's tool-free compaction request while preserving its history."""
        value = copy.deepcopy(value)
        value["tools"] = []
        value["client_metadata"]["x-codex-turn-metadata"] = json.dumps({"request_kind": "compaction"})
        value["input"].append({"role": "user", "content": "Create a checkpoint."})
        return value

    def test_restores_tools_only_for_compaction_and_disables_tool_calls(self):
        """The changed request keeps history and instructions byte-for-byte as JSON values."""
        normal = self.normal("cache-hit")
        self.assertEqual(normal, self.request(normal))
        compact = self.compact(normal)
        forwarded = self.request(compact)
        expected = {**compact, "tools": normal["tools"], "tool_choice": "none"}
        self.assertEqual(expected, forwarded)

    def test_latest_tools_are_scoped_to_chat_model_and_instructions(self):
        """A different chat or prefix must never inherit another request's tools."""
        normal = self.normal("scoped")
        self.request(normal)
        changed = copy.deepcopy(normal)
        changed["tools"][0]["name"] = "new_tool"
        self.request(changed)
        self.assertEqual(changed["tools"], self.request(self.compact(changed))["tools"])
        for field in ("prompt_cache_key", "model", "instructions"):
            other = self.compact(changed)
            other[field] += "-different"
            self.assertEqual(other, self.request(other))

    def test_empty_normal_tool_list_clears_stale_definitions(self):
        """Changing a normal turn to no tools must not resurrect its previous inventory."""
        normal = self.normal("clear")
        self.request(normal)
        normal["tools"] = []
        self.request(normal)
        compact = self.compact(normal)
        self.assertEqual(compact, self.request(compact))

    def test_requests_without_compaction_metadata_remain_unchanged(self):
        """Ordinary user text and unsupported request formats are not compaction signals."""
        normal = self.normal("ordinary")
        self.request(normal)
        ordinary = self.compact(normal)
        ordinary.pop("client_metadata")
        self.assertEqual(ordinary, self.request(ordinary))
        self.assertEqual(ordinary, self.request(ordinary, "/chat/completions"))
        for metadata in (None, "not an object", {"x-codex-turn-metadata": "not JSON"}):
            ordinary["client_metadata"] = metadata
            self.assertEqual(ordinary, self.request(ordinary))

    def test_compaction_with_its_own_tools_or_without_identity_is_unchanged(self):
        """Preserve a future compactor's explicit tool list and unknown chat identities."""
        normal = self.normal("explicit")
        self.request(normal)
        compact = self.compact(normal)
        compact["tools"] = [{"type": "function", "name": "explicit_tool"}]
        self.assertEqual(compact, self.request(compact))
        compact = self.compact(normal)
        compact.pop("prompt_cache_key")
        self.assertEqual(compact, self.request(compact))


if __name__ == "__main__":
    unittest.main()
