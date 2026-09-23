#!/usr/bin/env python3
"""统计落盘端 + 观众页静态预览（a0cc901 隧道单轮后的主要职责）。

观众页本身由 SignalHub 经出站隧道提供（WS 绑 location.host，必须原样打开）；
这里①接收页面 ?relay= 指来的 WebRTC 统计 POST（SignalHub 没有 /report），逐行落
.dev/reports.jsonl；②静态服务 app/src/main/assets/viewer 仅用于本地预览页面
（信令仍需隧道，dev 预览连不上房主）。

用法： python scripts/dev_viewer_server.py [port]
"""
import json
import os
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

_HERE = os.path.dirname(os.path.abspath(__file__))
# 只服务 assets/viewer（观众页源文件，SignalHub 也从这里取）；统计与一次性产物
# 一律落 .dev/，与页面目录隔离。
ROOT = os.path.join(_HERE, "..", "app", "src", "main", "assets", "viewer")
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
        # 允许隧道里的观众页把统计 POST 回本机：run_viewer_edge 用 ?relay= 指到这里
        # （SignalHub 本身没有 /report），取证走朋友真正点的那条隧道链接。
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

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
