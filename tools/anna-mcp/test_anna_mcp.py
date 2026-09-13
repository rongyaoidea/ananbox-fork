#!/usr/bin/env python3
"""Self-test for the Anna MCP bridge, using a mock gateway (stdlib only)."""

import base64
import json
import os
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import anna_mcp as m  # noqa: E402

TOKEN = "test-token"
FAKE_PNG = b"\x89PNG\r\n\x1a\nfakepng"


class MockGateway(BaseHTTPRequestHandler):
    received = []
    last_raw = b""

    def log_message(self, *args):
        pass

    def _auth_ok(self):
        return self.headers.get("Authorization") == "Bearer " + TOKEN

    def _send(self, status, ctype, body):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self._auth_ok():
            self._send(401, "application/json", b'{"error":"unauthorized"}')
            return
        MockGateway.received.append(("GET", self.path, None))
        if self.path == "/v1/status":
            self._send(200, "application/json", json.dumps({"gateway": True, "keyboard": True}).encode())
        elif self.path == "/v1/screen":
            self._send(200, "image/png", FAKE_PNG)
        elif self.path.startswith("/v1/fs/list"):
            self._send(200, "application/json", json.dumps({"path": "", "entries": []}).encode())
        else:
            self._send(404, "application/json", b'{"error":"not found"}')

    def do_POST(self):
        if not self._auth_ok():
            self._send(401, "application/json", b'{"error":"unauthorized"}')
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b""
        MockGateway.last_raw = raw
        try:
            payload = json.loads(raw or b"{}")
        except ValueError:
            payload = None
        MockGateway.received.append(("POST", self.path, payload))
        response = {"ok": True}
        if isinstance(payload, dict):
            response.update(payload)
        self._send(200, "application/json", json.dumps(response).encode())


class AnnaMcpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), MockGateway)
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        base = "http://127.0.0.1:%d" % cls.httpd.server_address[1]
        cls.client = m.AnnaClient(base_url=base, token=TOKEN)
        cls.server = m.Server(cls.client)

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def setUp(self):
        MockGateway.received.clear()

    def test_initialize(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
        self.assertEqual(response["result"]["serverInfo"]["name"], "anna")
        self.assertIn("tools", response["result"]["capabilities"])

    def test_notifications_are_ignored(self):
        self.assertIsNone(self.server.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))

    def test_tools_list(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        names = [t["name"] for t in response["result"]["tools"]]
        self.assertIn("anna_tap", names)
        self.assertIn("anna_screenshot", names)
        self.assertIn("anna_type_text", names)

    def test_status_tool(self):
        response = self.server.handle(
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "anna_status", "arguments": {}}}
        )
        self.assertFalse(response["result"]["isError"])
        text = response["result"]["content"][0]["text"]
        self.assertTrue(json.loads(text)["gateway"])

    def test_tap_tool_posts_coordinates(self):
        response = self.server.handle(
            {
                "jsonrpc": "2.0",
                "id": 4,
                "method": "tools/call",
                "params": {"name": "anna_tap", "arguments": {"x": 12, "y": 34}},
            }
        )
        self.assertFalse(response["result"]["isError"])
        self.assertEqual(MockGateway.received[-1], ("POST", "/v1/input/tap", {"x": 12, "y": 34}))

    def test_screenshot_returns_image(self):
        response = self.server.handle(
            {"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "anna_screenshot", "arguments": {}}}
        )
        content = response["result"]["content"][0]
        self.assertEqual(content["type"], "image")
        self.assertEqual(content["mimeType"], "image/png")

    def test_missing_argument_is_error_content(self):
        response = self.server.handle(
            {
                "jsonrpc": "2.0",
                "id": 6,
                "method": "tools/call",
                "params": {"name": "anna_tap", "arguments": {"x": 1}},
            }
        )
        self.assertTrue(response["result"]["isError"])
        self.assertTrue(response["result"]["content"][0]["text"].startswith("error:"))

    def test_unknown_tool_returns_jsonrpc_error(self):
        response = self.server.handle(
            {"jsonrpc": "2.0", "id": 7, "method": "tools/call", "params": {"name": "nope", "arguments": {}}}
        )
        self.assertIn("error", response)

    def test_bad_token_raises(self):
        bad = m.AnnaClient(base_url=self.client.base_url, token="wrong")
        with self.assertRaises(m.AnnaError):
            bad.json("GET", "/v1/status")

    def _call(self, msg_id, name, arguments):
        return self.server.handle(
            {
                "jsonrpc": "2.0",
                "id": msg_id,
                "method": "tools/call",
                "params": {"name": name, "arguments": arguments},
            }
        )

    def test_exec_tool(self):
        response = self._call(10, "anna_exec", {"command": "pm list packages"})
        self.assertFalse(response["result"]["isError"])
        self.assertEqual(MockGateway.received[-1], ("POST", "/v1/exec", {"command": "pm list packages"}))

    def test_start_app_tool(self):
        response = self._call(11, "anna_start_app", {"package": "com.example.app"})
        self.assertFalse(response["result"]["isError"])
        self.assertEqual(MockGateway.received[-1], ("POST", "/v1/apps/com.example.app/start", {}))

    def test_install_app_decodes_base64(self):
        apk = b"PK\x03\x04fake-apk"
        response = self._call(12, "anna_install_app", {"apk_base64": base64.b64encode(apk).decode()})
        self.assertFalse(response["result"]["isError"])
        self.assertEqual(MockGateway.last_raw, apk)

    def test_write_file_sends_text(self):
        response = self._call(13, "anna_write_file", {"path": "note.txt", "text": "hello guest"})
        self.assertFalse(response["result"]["isError"])
        self.assertEqual(MockGateway.last_raw, b"hello guest")
        self.assertTrue(MockGateway.received[-1][1].startswith("/v1/fs/file?path=note.txt"))

    def test_write_file_rejects_ambiguous_content(self):
        response = self._call(14, "anna_write_file", {"path": "x", "text": "a", "base64": "YQ=="})
        self.assertTrue(response["result"]["isError"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
