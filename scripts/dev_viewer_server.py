#!/usr/bin/env python3
"""开发用静态服务器 + 统计回传接收端。

浏览器页带 ?report=1 时会把 WebRTC 统计 POST 到 /report，这里逐行落到
.dev/reports.jsonl，便于在无头/半自动场景下取证（内置浏览器拿不到麦克风，
所以连麦验证必须走真 Chromium）。

用法： python scripts/dev_viewer_server.py [port]
"""
import json
import os
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

_HERE = os.path.dirname(os.path.abspath(__file__))
# 只提供 public/ —— 它就是"可部署的站点根"，与 Qoder Sites 的 webDirectory 同一个口径。
# 调试产物（token、统计）一律落在 .dev/，绝不进站点目录，否则会被打包上传。
ROOT = os.path.join(_HERE, "..", "viewer", "public")
OUT = os.path.join(_HERE, "..", ".dev", "reports.jsonl")
os.makedirs(os.path.dirname(OUT), exist_ok=True)


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def log_message(self, fmt, *args):
        pass  # 安静

    def end_headers(self):
        # ES module 需要正确的 MIME；同时禁缓存，避免改完页面看不到
        self.send_header("Cache-Control", "no-store")
        if self.path.endswith(".mjs"):
            self.headers_map = None
        super().end_headers()

    def do_POST(self):
        if self.path != "/report":
            self.send_error(404)
            return
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n)
        try:
            rec = json.loads(raw.decode("utf-8"))
        except Exception as e:
            self.send_error(400, str(e))
            return
        with open(OUT, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        self.send_response(204)
        self.end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8792
    if os.path.exists(OUT):
        os.remove(OUT)
    print(f"serving {ROOT} on http://127.0.0.1:{port}  (reports -> {OUT})")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
