#!/usr/bin/env python3
"""Static file server — reads PORT from env (for Claude Preview compatibility)."""
import os
from http.server import HTTPServer, SimpleHTTPRequestHandler

port = int(os.environ.get("PORT", 7899))
directory = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         "src", "main", "resources", "static")

class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=directory, **kwargs)

    def do_GET(self):
        if self.path == "/":
            self.send_response(302)
            self.send_header("Location", "/admin.html")
            self.end_headers()
        else:
            super().do_GET()

    def log_message(self, fmt, *args):
        pass  # quiet

print(f"Serving {directory} on port {port}", flush=True)
with HTTPServer(("", port), Handler) as httpd:
    httpd.serve_forever()
