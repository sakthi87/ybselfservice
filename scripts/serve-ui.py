#!/usr/bin/env python3
"""Serve the compiled UI and proxy GraphQL to the local APIs.

The browser calls /graphql, /login/graphql, and /intake/graphql. This process
serves web/dist and forwards those calls, adding the local gateway credential
the same way the Vite dev server does. The credential is not in the UI bundle.
"""

import base64
import mimetypes
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1] / "web" / "dist"
PORT = int(os.environ.get("UI_PORT", "5173"))
TOKEN = base64.b64encode(b"metadata-gateway:metadata-gateway").decode("ascii")
PROXIES = (
    ("/login/graphql", "http://127.0.0.1:4010/graphql"),
    ("/intake/graphql", "http://127.0.0.1:4020/graphql"),
    ("/graphql", "http://127.0.0.1:4000/graphql"),
)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def do_OPTIONS(self):
        self.dispatch()

    def dispatch(self):
        path = self.path.split("?", 1)[0]
        for prefix, target in PROXIES:
            if path == prefix:
                self.proxy(target)
                return
        if self.command != "GET":
            self.send_error(405)
            return
        self.static(path)

    def proxy(self, target):
        length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(length) if length else None
        headers = {"Authorization": f"Basic {TOKEN}"}
        content_type = self.headers.get("Content-Type")
        if content_type:
            headers["Content-Type"] = content_type
        request = Request(target, data=body, headers=headers, method=self.command)
        try:
            with urlopen(request, timeout=60) as response:
                payload = response.read()
                self.send_response(response.status)
                self.send_header("Content-Type", response.headers.get("Content-Type", "application/json"))
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
        except HTTPError as error:
            payload = error.read()
            self.send_response(error.code)
            self.send_header("Content-Type", error.headers.get("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except URLError as error:
            message = f"API is not reachable: {error.reason}".encode()
            self.send_response(502)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(message)))
            self.end_headers()
            self.wfile.write(message)

    def static(self, path):
        relative = path.lstrip("/") or "index.html"
        file_path = (ROOT / relative).resolve()
        if not str(file_path).startswith(str(ROOT.resolve())):
            self.send_error(403)
            return
        if not file_path.is_file():
            file_path = ROOT / "index.html"
        if not file_path.is_file():
            self.send_error(404, "Compiled UI is missing. Run scripts/download-prebuilt.sh")
            return
        payload = file_path.read_bytes()
        content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        return


def main():
    if not (ROOT / "index.html").is_file():
        raise SystemExit(f"Missing {ROOT / 'index.html'}. Run scripts/download-prebuilt.sh")
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"UI http://127.0.0.1:{PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
