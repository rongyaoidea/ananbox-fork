#!/usr/bin/env python3
"""Anna MCP bridge.

Exposes the Ananbox host gateway (the `anna` HTTP API) to any MCP-capable
agent as a small set of tools: status, screenshot, tap, swipe, type, key,
app/file browsing and log tailing.

Usage
-----
    export ANNA_BASE_URL=http://127.0.0.1:7749   # optional
    export ANNA_TOKEN=<bearer token from filesDir/anna.token>
    python3 anna_mcp.py

The server speaks newline-delimited JSON-RPC 2.0 on stdio (the MCP stdio
transport). It has no third-party dependencies.

Security: the token grants full control of the guest (input, files). Keep the
gateway bound to 127.0.0.1 unless you really need LAN access.
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.request
from urllib.parse import quote

PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "anna"
SERVER_VERSION = "0.1.0"


class AnnaError(RuntimeError):
    pass


class AnnaClient:
    def __init__(self, base_url=None, token=None, timeout=20.0):
        self.base_url = (base_url or os.environ.get("ANNA_BASE_URL", "http://127.0.0.1:7749")).rstrip("/")
        self.token = token or os.environ.get("ANNA_TOKEN", "")
        self.timeout = timeout

    def request(self, method, path, payload=None, raw=None, content_type="application/octet-stream"):
        headers = {"Authorization": "Bearer " + self.token}
        data = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif raw is not None:
            data = raw
            headers["Content-Type"] = content_type
        req = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.headers.get("Content-Type", ""), resp.read()
        except urllib.error.HTTPError as exc:
            try:
                detail = exc.read().decode("utf-8", "replace")
            finally:
                exc.close()
            raise AnnaError("HTTP %s from %s: %s" % (exc.code, path, detail)) from exc
        except urllib.error.URLError as exc:
            raise AnnaError("cannot reach Anna gateway at %s: %s" % (self.base_url, exc.reason)) from exc

    def json(self, method, path, payload=None):
        _, body = self.request(method, path, payload)
        return self._decode(path, body)

    def json_raw(self, method, path, data, content_type="application/octet-stream"):
        _, body = self.request(method, path, raw=data, content_type=content_type)
        return self._decode(path, body)

    @staticmethod
    def _decode(path, body):
        try:
            return json.loads(body.decode("utf-8"))
        except ValueError as exc:
            raise AnnaError("invalid JSON from %s: %s" % (path, exc)) from exc


def _require(args, *names):
    missing = [n for n in names if n not in args]
    if missing:
        raise AnnaError("missing argument(s): " + ", ".join(missing))


def _as_int(args, name):
    try:
        return int(args[name])
    except (TypeError, ValueError):
        raise AnnaError("argument %r must be an integer" % name)


def _json_text(value):
    return {"type": "text", "text": json.dumps(value, ensure_ascii=False)}


def tool_status(client, args):
    return [_json_text(client.json("GET", "/v1/status"))]


def tool_screenshot(client, args):
    ctype, body = client.request("GET", "/v1/screen")
    if not ctype.startswith("image/"):
        raise AnnaError("unexpected screen response: %s" % ctype)
    return [{"type": "image", "data": base64.b64encode(body).decode("ascii"), "mimeType": ctype.split(";")[0]}]


def tool_tap(client, args):
    _require(args, "x", "y")
    return [_json_text(client.json("POST", "/v1/input/tap", {"x": _as_int(args, "x"), "y": _as_int(args, "y")}))]


def tool_swipe(client, args):
    _require(args, "x1", "y1", "x2", "y2")
    payload = {
        "x1": _as_int(args, "x1"),
        "y1": _as_int(args, "y1"),
        "x2": _as_int(args, "x2"),
        "y2": _as_int(args, "y2"),
        "duration": _as_int(args, "duration") if "duration" in args else 300,
    }
    return [_json_text(client.json("POST", "/v1/input/swipe", payload))]


def tool_type_text(client, args):
    _require(args, "text")
    return [_json_text(client.json("POST", "/v1/input/text", {"text": str(args["text"])}))]


def tool_press_key(client, args):
    _require(args, "key")
    return [_json_text(client.json("POST", "/v1/input/key", {"key": str(args["key"])}))]


def tool_list_apps(client, args):
    return [_json_text(client.json("GET", "/v1/apps"))]


def _scoped_path(args):
    path = str(args.get("path", ""))
    package = args.get("package")
    if package:
        from urllib.parse import quote

        return "/v1/apps/%s/files?path=%s" % (quote(str(package), safe=""), quote(path, safe=""))
    from urllib.parse import quote

    return "/v1/fs/list?path=%s" % quote(path, safe="")


def tool_list_files(client, args):
    return [_json_text(client.json("GET", _scoped_path(args)))]


def tool_read_file(client, args):
    _require(args, "path")
    from urllib.parse import quote

    package = args.get("package")
    path = str(args["path"])
    if package:
        url = "/v1/apps/%s/file?path=%s" % (quote(str(package), safe=""), quote(path, safe=""))
    else:
        url = "/v1/fs/read?path=%s" % quote(path, safe="")
    ctype, body = client.request("GET", url)
    if ctype.startswith("text/"):
        return [{"type": "text", "text": body.decode("utf-8", "replace")}]
    return [
        {"type": "text", "text": "binary file (%s, %d bytes), base64:" % (ctype, len(body))},
        {"type": "text", "text": base64.b64encode(body).decode("ascii")},
    ]


def tool_logs(client, args):
    name = str(args.get("name", "system"))
    lines = _as_int(args, "lines") if "lines" in args else 200
    from urllib.parse import quote

    _, body = client.request("GET", "/v1/logs?name=%s&lines=%d" % (quote(name, safe=""), lines))
    return [{"type": "text", "text": body.decode("utf-8", "replace")}]


def tool_exec(client, args):
    _require(args, "command")
    payload = {"command": str(args["command"])}
    if "timeout_ms" in args:
        payload["timeoutMs"] = _as_int(args, "timeout_ms")
    return [_json_text(client.json("POST", "/v1/exec", payload))]


def tool_start_app(client, args):
    _require(args, "package")
    body = {}
    if args.get("activity"):
        body["activity"] = str(args["activity"])
    path = "/v1/apps/%s/start" % quote(str(args["package"]), safe="")
    return [_json_text(client.json("POST", path, body))]


def tool_install_app(client, args):
    _require(args, "apk_base64")
    try:
        apk = base64.b64decode(str(args["apk_base64"]), validate=True)
    except Exception as exc:
        raise AnnaError("invalid base64 apk: %s" % exc)
    return [_json_text(client.json_raw("POST", "/v1/apps/install", apk))]


def _file_body(args):
    has_text = "text" in args
    has_base64 = "base64" in args
    if has_text and has_base64:
        raise AnnaError("provide text or base64, not both")
    if has_text:
        return str(args["text"]).encode("utf-8")
    if has_base64:
        try:
            return base64.b64decode(str(args["base64"]), validate=True)
        except Exception as exc:
            raise AnnaError("invalid base64 content: %s" % exc)
    return b""


def tool_write_file(client, args):
    _require(args, "path")
    data = _file_body(args)
    path = str(args["path"])
    package = args.get("package")
    if package:
        url = "/v1/apps/%s/file?path=%s" % (quote(str(package), safe=""), quote(path, safe=""))
    else:
        url = "/v1/fs/file?path=%s" % quote(path, safe="")
    return [_json_text(client.json_raw("POST", url, data))]


def tool_mkdir(client, args):
    _require(args, "path")
    url = "/v1/fs/mkdir?path=%s" % quote(str(args["path"]), safe="")
    return [_json_text(client.json("POST", url, {}))]


def tool_delete(client, args):
    _require(args, "path")
    recursive = "true" if args.get("recursive") else "false"
    url = "/v1/fs/delete?path=%s&recursive=%s" % (quote(str(args["path"]), safe=""), recursive)
    return [_json_text(client.json("POST", url, {}))]


TOOLS = [
    {
        "name": "anna_status",
        "description": "Container/display/app count status of the Ananbox guest.",
        "inputSchema": {"type": "object", "properties": {}},
        "_handler": tool_status,
    },
    {
        "name": "anna_screenshot",
        "description": "Capture the current guest screen as a PNG image.",
        "inputSchema": {"type": "object", "properties": {}},
        "_handler": tool_screenshot,
    },
    {
        "name": "anna_tap",
        "description": "Tap at guest display coordinates.",
        "inputSchema": {
            "type": "object",
            "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}},
            "required": ["x", "y"],
        },
        "_handler": tool_tap,
    },
    {
        "name": "anna_swipe",
        "description": "Swipe between two guest display coordinates.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "x1": {"type": "integer"},
                "y1": {"type": "integer"},
                "x2": {"type": "integer"},
                "y2": {"type": "integer"},
                "duration": {"type": "integer", "description": "milliseconds, default 300"},
            },
            "required": ["x1", "y1", "x2", "y2"],
        },
        "_handler": tool_swipe,
    },
    {
        "name": "anna_type_text",
        "description": "Type text into the focused guest field (US layout).",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
        "_handler": tool_type_text,
    },
    {
        "name": "anna_press_key",
        "description": "Press a named key: back, home, menu, app_switch, enter, backspace, del, tab, escape, up/down/left/right.",
        "inputSchema": {
            "type": "object",
            "properties": {"key": {"type": "string"}},
            "required": ["key"],
        },
        "_handler": tool_press_key,
    },
    {
        "name": "anna_list_apps",
        "description": "List installed guest packages with uid and data directory.",
        "inputSchema": {"type": "object", "properties": {}},
        "_handler": tool_list_apps,
    },
    {
        "name": "anna_list_files",
        "description": "List files under the guest rootfs (or an app data dir when package is given).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "path relative to the scope root"},
                "package": {"type": "string", "description": "optional guest package name"},
            },
        },
        "_handler": tool_list_files,
    },
    {
        "name": "anna_read_file",
        "description": "Read a file from the guest rootfs (or an app data dir when package is given).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "package": {"type": "string"},
            },
            "required": ["path"],
        },
        "_handler": tool_read_file,
    },
    {
        "name": "anna_logs",
        "description": "Tail the guest system log or the proot log.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "enum": ["system", "proot"]},
                "lines": {"type": "integer"},
            },
        },
        "_handler": tool_logs,
    },
    {
        "name": "anna_exec",
        "description": "Run a shell command inside the guest (requires console access in Settings).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "command": {"type": "string"},
                "timeout_ms": {"type": "integer", "description": "default 30000, max 120000"},
            },
            "required": ["command"],
        },
        "_handler": tool_exec,
    },
    {
        "name": "anna_start_app",
        "description": "Launch an installed guest app by package name (requires console access).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "package": {"type": "string"},
                "activity": {"type": "string", "description": "optional explicit activity component"},
            },
            "required": ["package"],
        },
        "_handler": tool_start_app,
    },
    {
        "name": "anna_install_app",
        "description": "Install an APK into the guest from base64 bytes (requires console access).",
        "inputSchema": {
            "type": "object",
            "properties": {"apk_base64": {"type": "string"}},
            "required": ["apk_base64"],
        },
        "_handler": tool_install_app,
    },
    {
        "name": "anna_write_file",
        "description": "Write a file inside the guest rootfs or an app data dir (requires console access).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "text": {"type": "string", "description": "UTF-8 text content"},
                "base64": {"type": "string", "description": "binary content instead of text"},
                "package": {"type": "string", "description": "optional guest package data dir"},
            },
            "required": ["path"],
        },
        "_handler": tool_write_file,
    },
    {
        "name": "anna_mkdir",
        "description": "Create a directory inside the guest rootfs (requires console access).",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"],
        },
        "_handler": tool_mkdir,
    },
    {
        "name": "anna_delete",
        "description": "Delete a file or directory inside the guest rootfs (requires console access).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "recursive": {"type": "boolean", "description": "delete a directory tree"},
            },
            "required": ["path"],
        },
        "_handler": tool_delete,
    },
]


def public_tools():
    return [{k: v for k, v in t.items() if not k.startswith("_")} for t in TOOLS]


def call_tool(client, name, args):
    for tool in TOOLS:
        if tool["name"] == name:
            try:
                return tool["_handler"](client, args or {})
            except AnnaError as exc:
                return [{"type": "text", "text": "error: %s" % exc}]
    raise AnnaError("unknown tool: %s" % name)


class Server:
    def __init__(self, client):
        self.client = client

    def handle(self, message):
        """Returns a response dict, or None for notifications."""
        if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
            return None
        msg_id = message.get("id")
        method = message.get("method")
        if msg_id is None:
            return None  # notification
        try:
            if method == "initialize":
                result = {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                }
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": public_tools()}
            elif method == "tools/call":
                params = message.get("params") or {}
                name = params.get("name", "")
                content = call_tool(self.client, name, params.get("arguments") or {})
                result = {
                    "content": content,
                    "isError": bool(content) and content[0].get("type") == "text"
                    and content[0].get("text", "").startswith("error:"),
                }
            else:
                return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32601, "message": "method not found: %s" % method}}
            return {"jsonrpc": "2.0", "id": msg_id, "result": result}
        except AnnaError as exc:
            return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32000, "message": str(exc)}}
        except Exception as exc:  # noqa: BLE001 - keep the loop alive
            return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32603, "message": "internal error: %s" % exc}}


def main():
    client = AnnaClient()
    server = Server(client)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            continue
        response = server.handle(message)
        if response is not None:
            sys.stdout.write(json.dumps(response) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
