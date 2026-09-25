#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 CDP 精确驱动**手机上的 Chrome**，替代"盲点坐标"。

为什么要有这个：观众最常见的那一端是手机浏览器，而验证它以前只有一条路 ——
`adb shell input tap x y` 猜坐标。今天在这条路上摔过两次：
一次点在卡片文案里的「开始放映」四个字上（不是那颗按钮），
一次点在标签页栏下方的空白处（岛其实还在更上面），两次都差点被我当成"产品坏了"。
手机 Chrome 会把 DevTools 套接字暴露出来（chrome://inspect 就是这么工作的），
接上之后可以：读元素矩形、按真实命中测试派发鼠标事件（**这才算用户手势**，
全屏/画中画这类 API 用 `.click()` 是唤不出来的）、再读回结果。

用法：
    python scripts/drive_phone_cdp.py --serial emulator-5558 url
    python scripts/drive_phone_cdp.py --serial emulator-5558 eval "document.title"
    python scripts/drive_phone_cdp.py --serial emulator-5558 click "#btnFull"
    python scripts/drive_phone_cdp.py --serial emulator-5558 rect "#btnFull,#lv,video"
前置：手机上 Chrome 已经打开目标页；`adb forward` 可用。
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.request

try:
    import websocket          # websocket-client
except ImportError:
    print("需要 websocket-client：pip install websocket-client")
    raise

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
LOCAL_PORT = 9337
SOCKET_NAME = "chrome_devtools_remote"


def adb(serial, *args, timeout=40):
    return subprocess.run(
        [ADB, "-s", serial] + list(args), capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=timeout,
    )


def connect(serial):
    r = adb(serial, "forward", f"tcp:{LOCAL_PORT}", f"localabstract:{SOCKET_NAME}")
    if r.returncode != 0:
        raise SystemExit("adb forward 失败：" + (r.stdout + r.stderr).strip()[:200])
    with urllib.request.urlopen(f"http://127.0.0.1:{LOCAL_PORT}/json", timeout=10) as resp:
        targets = json.load(resp)
    pages = [t for t in targets if t.get("type") == "page"]
    if not pages:
        raise SystemExit("Chrome 里没有页面标签（先在手机上打开邀请链接）")
    return pages


def pick(pages, needle=None):
    if needle:
        for p in pages:
            if needle in (p.get("url") or ""):
                return p
    # 默认挑第一个 trycloudflare 的页面：那是观众页
    for p in pages:
        if "trycloudflare" in (p.get("url") or ""):
            return p
    return pages[0]


class Cdp:
    def __init__(self, ws_url):
        # Chrome 会拒掉带网页 Origin 的 DevTools 握手（403 "Rejected an incoming
        # WebSocket connection from the http://127.0.0.1:… origin"）—— 必须把
        # Origin 头去掉再连，这是 chrome://inspect 自己的做法。
        try:
            self.ws = websocket.create_connection(ws_url, timeout=20, suppress_origin=True)
        except TypeError:                      # 老版本 websocket-client 没有这个参数
            self.ws = websocket.create_connection(
                ws_url, timeout=20, origin="devtools://devtools")
        self.i = 0

    def call(self, method, params=None):
        self.i += 1
        mid = self.i
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    def eval(self, expr, await_promise=False):
        r = self.call("Runtime.evaluate", {
            "expression": expr, "returnByValue": True,
            "awaitPromise": await_promise,
        })
        if r.get("exceptionDetails"):
            return {"throw": r["exceptionDetails"].get("text") or str(r["exceptionDetails"])[:200]}
        return {"value": (r.get("result") or {}).get("value")}

    def click_at(self, x, y):
        """真实命中测试的点击 —— 全屏、画中画、自动播放这类 API 只认这种手势。"""
        for t, buttons in (("mousePressed", 1), ("mouseReleased", 0)):
            self.call("Input.dispatchMouseEvent", {
                "type": t, "x": x, "y": y, "button": "left",
                "clickCount": 1, "buttons": buttons,
            })

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


# 几何审计：可见元素两两重叠、越出视界的都算问题。
# 手机上实测抓到过一颗徽章压在顶栏右半边 118x28 个像素上（两行字叠在一起）——
# 这类缺陷结构判据看不出来，只有把矩形量出来才会现形。
AUDIT_JS = r"""(() => {
  const R = s => { const e = document.querySelector(s); if (!e) return null;
    const b = e.getBoundingClientRect();
    return { s, x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height),
      hide: e.classList.contains('hide'), out: e.classList.contains('chrome-out') }; };
  const all = '#topBar,#cineBar,#ctlBar,#watchBar,#lvBadge,#hud,#panel'.split(',')
    .map(x => x.trim()).map(R).filter(Boolean);
  const vis = all.filter(e => !e.hide && !e.out);
  const overlap = [];
  for (let i = 0; i < vis.length; i++) for (let j = i + 1; j < vis.length; j++) {
    const a = vis[i], b = vis[j];
    const ox = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
    const oy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
    if (ox > 2 && oy > 2) overlap.push(a.s + '×' + b.s + ' ' + ox + 'x' + oy);
  }
  const off = vis.filter(e => e.x < 0 || e.y < 0 || e.x + e.w > innerWidth + 1 || e.y + e.h > innerHeight + 1)
    .map(e => e.s);
  return JSON.stringify({ vp: [innerWidth, innerHeight],
    els: vis.map(e => e.s + ' ' + e.x + ',' + e.y + ' ' + e.w + 'x' + e.h),
    hidden: all.filter(e => e.hide || e.out).map(e => e.s), overlap, offscreen: off });
})()"""

RECT_JS = """(sel) => {
  const out = [];
  for (const s of sel.split(',')) {
    const el = document.querySelector(s.trim());
    if (!el) { out.push([s.trim(), null]); continue; }
    const b = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    out.push([s.trim(), {x: Math.round(b.x), y: Math.round(b.y),
      w: Math.round(b.width), h: Math.round(b.height),
      hidden: el.classList.contains('hide'), op: cs.opacity}]);
  }
  return JSON.stringify(out);
}"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5558")
    ap.add_argument("--url-needle", default=None)
    ap.add_argument("cmd", choices=["pages", "url", "title", "rect", "click", "eval", "audit"])
    ap.add_argument("arg", nargs="?", default="#lv")
    a = ap.parse_args()

    pages = connect(a.serial)
    if a.cmd == "pages":
        for p in pages:
            print(" ", (p.get("title") or "")[:34], "|", (p.get("url") or "")[:78])
        return 0
    t = pick(pages, a.url_needle)
    cdp = Cdp(t["webSocketDebuggerUrl"])
    try:
        if a.cmd == "url":
            print(cdp.eval("location.href")["value"])
        elif a.cmd == "title":
            print(cdp.eval("document.title")["value"])
        elif a.cmd == "rect":
            r = cdp.eval(f"({RECT_JS})({json.dumps(a.arg)})")["value"]
            for item in json.loads(r):
                print("  ", item[0], "→", item[1])
        elif a.cmd == "click":
            pos = cdp.eval(
                "(() => { const e = document.querySelector(%s);"
                " if (!e) return null; const b = e.getBoundingClientRect();"
                " return JSON.stringify([b.x + b.width / 2, b.y + b.height / 2]); })()"
                % json.dumps(a.arg))["value"]
            if not pos:
                print("找不到元素：", a.arg)
                return 1
            x, y = json.loads(pos)
            cdp.click_at(x, y)
            print(f"已点击 {a.arg} @ ({x:.0f},{y:.0f})")
        elif a.cmd == "audit":
            print(cdp.eval(AUDIT_JS)["value"])
        else:
            print(cdp.eval(a.arg, await_promise=True))
    finally:
        cdp.close()
        adb(a.serial, "forward", "--remove", f"tcp:{LOCAL_PORT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
