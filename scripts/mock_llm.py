"""Minimal OpenAI-compatible chat server for end-to-end testing without a real key.

Streams a fixed tutor reply for /v1/chat/completions (stream or not) and lists one model.
usage: python mock_llm.py [port]
"""

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPLY = "你好！我是 DeepTutor 本地测试回复：勾股定理指出直角三角形两直角边的平方和等于斜边的平方，即 a² + b² = c²。"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("mock: " + fmt % args + "\n")

    def _json(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            self._json(200, {"object": "list", "data": [{"id": "mock-tutor", "object": "model"}]})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self._json(404, {"error": "not found"})
            return
        created = int(time.time())
        if not body.get("stream"):
            self._json(200, {
                "id": "mock", "object": "chat.completion", "created": created, "model": body.get("model"),
                "choices": [{"index": 0, "finish_reason": "stop",
                             "message": {"role": "assistant", "content": REPLY}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 30, "total_tokens": 40},
            })
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for i in range(0, len(REPLY), 6):
            chunk = {"id": "mock", "object": "chat.completion.chunk", "created": created,
                     "model": body.get("model"),
                     "choices": [{"index": 0, "delta": {"content": REPLY[i:i + 6]}, "finish_reason": None}]}
            self.wfile.write(f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n".encode())
            self.wfile.flush()
            time.sleep(0.03)
        end = {"id": "mock", "object": "chat.completion.chunk", "created": created, "model": body.get("model"),
               "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
               "usage": {"prompt_tokens": 10, "completion_tokens": 30, "total_tokens": 40}}
        self.wfile.write(f"data: {json.dumps(end)}\n\ndata: [DONE]\n\n".encode())
        self.wfile.flush()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18999
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
