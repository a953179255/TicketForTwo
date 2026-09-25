#!/usr/bin/env python3
"""手机横过来用浏览器看片时，观众页那几层会不会打起来 —— 用 CDP 量矩形，不靠肉眼猜。

为什么单独要一个脚本：这个项目最常见的观众是**朋友手机上的浏览器**，而之前所有网页侧的
像素证据都是竖屏的。横屏（宽 > 高）下页面里同时可能有：顶栏、控制条、放映条、
本地播放徽章、HUD、等候卡 —— 六层叠在一个只有 ~400dp 高的视口里。
"结构存在"和"看得见、没互相压"是两件事，本项目已经为此翻过两次车。

用法：
    python scripts/open_room.py            # 房主开厅（要放映状态就先在厅里选片）
    python scripts/drive_web_landscape.py [--port 9338] [--w 900 --h 420]

判据（全部来自 getBoundingClientRect + computedStyle，脚本自己算）：
  OUT      可见元素的矩形超出视口
  OVERLAP  两个可见浮层互相压住 >12%（顶栏/控制条/放映条/徽章/HUD/卡）
  EMPTY    连着屏却没有一个可见浮层（= 观众没有任何控件可用）
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from drive_phone_cdp import Cdp  # noqa: E402  同一个 WS 客户端，只是这里连本机端口

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
INVITE = os.path.join(HERE, "..", ".dev", "invite.txt")
SHOT = os.path.join(HERE, "..", ".dev", "web-landscape.png")

# 关心的层：顶栏 / 控制条 / 放映条 / 同看条 / 本地播放徽章 / HUD / 等候卡
IDS = ["topBar", "ctlBar", "cineBar", "watchBar", "lvBadge", "hud", "panel", "stage", "lv", "v"]

AUDIT_JS = r"""
(ids) => {
  const vw = innerWidth, vh = innerHeight;
  const out = { vw, vh, els: [] };
  for (const id of ids) {
    const el = document.getElementById(id);
    if (!el) continue;
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    const shown = cs.display !== 'none' && cs.visibility !== 'hidden'
      && parseFloat(cs.opacity || '1') > 0.05 && r.width > 1 && r.height > 1;
    out.els.push({
      id, shown, x: Math.round(r.x), y: Math.round(r.y),
      w: Math.round(r.width), h: Math.round(r.height),
      // 透明度必须一起报：只看 opacity > 0.05 的话，一层正在淡出（0.2）也算"可见"，
      // 于是极窄视口那张截图整屏发暗、矩形却全绿 —— 量到了"在"，没量到"看得清"。
      op: parseFloat(cs.opacity || '1'),
      text: (el.textContent || '').trim().slice(0, 26),
    });
  }
  return out;
}
"""


def json_get(port, path):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=10) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="9338")
    ap.add_argument("--w", type=int, default=900)
    ap.add_argument("--h", type=int, default=420)
    args = ap.parse_args()

    url = open(INVITE, encoding="utf-8").read().strip()
    if "?k=" not in url:
        print("FAIL  .dev/invite.txt 里没有隧道邀请，先跑 scripts/open_room.py")
        return 1

    # 每轮一个全新 profile：残留进程锁住 profile 时，新 Edge 会把 URL 交给旧实例然后自己退出
    udd = os.path.join(os.environ.get("TEMP", "."), f"t2-web-land-{int(time.time())}")
    proc = subprocess.Popen(
        [EDGE, "--headless=new", f"--remote-debugging-port={args.port}",
         f"--user-data-dir={udd}", f"--window-size={args.w},{args.h}",
         "--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
         "--autoplay-policy=no-user-gesture-required",
         "--disable-features=LocalNetworkAccessChecks",
         "--disable-gpu", "--no-first-run", url],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    cdp = None
    try:
        pages = []
        for _ in range(20):
            time.sleep(1)
            try:
                pages = [p for p in json_get(args.port, "/json")
                         if p.get("type") == "page" and "trycloudflare" in (p.get("url") or "")]
                if pages:
                    break
            except Exception:
                pass
        if not pages:
            print("FAIL  Edge 没把页面开起来（/json 里找不到 trycloudflare 标签）")
            return 1
        cdp = Cdp(pages[0]["webSocketDebuggerUrl"])

        def ev(expr):
            # drive_phone_cdp 的 eval 返回的是 CDP 的 result 字典（{"value": …}），
            # 直接拿它和字符串比会永远不相等 —— 上一版就是这么白等满 30 秒。
            r = cdp.eval(expr)
            return r.get("value") if isinstance(r, dict) else r

        st = "none"
        for _ in range(30):
            st = ev("window.t2 && window.t2.pc ? window.t2.pc.connectionState : 'none'")
            if st == "connected":
                break
            time.sleep(1)
        print(f"   连接状态：{st}")
        a = ev(f"({AUDIT_JS})({json.dumps(IDS)})")
        print(f"   视口：{a['vw']}x{a['vh']}")
        shown = [e for e in a["els"] if e["shown"]]
        for e in a["els"]:
            print(f"   {'✓' if e['shown'] else '·'} {e['id']:9} "
                  f"[{e['x']:5},{e['y']:5}] {e['w']:5}x{e['h']:<5} {e['text'][:22]!r}")

        problems = []
        vw, vh = a["vw"], a["vh"]
        for e in shown:
            if e["x"] < -1 or e["y"] < -1 or e["x"] + e["w"] > vw + 1 or e["y"] + e["h"] > vh + 1:
                problems.append(f"OUT      {e['id']} 超出视口")
        overlay = {"topBar", "ctlBar", "cineBar", "watchBar", "lvBadge", "hud", "panel"}
        vis = [e for e in shown if e["id"] in overlay]
        for i in range(len(vis)):
            for j in range(i + 1, len(vis)):
                x1 = max(vis[i]["x"], vis[j]["x"])
                y1 = max(vis[i]["y"], vis[j]["y"])
                x2 = min(vis[i]["x"] + vis[i]["w"], vis[j]["x"] + vis[j]["w"])
                y2 = min(vis[i]["y"] + vis[i]["h"], vis[j]["y"] + vis[j]["h"])
                inter = max(0, x2 - x1) * max(0, y2 - y1)
                small = min(vis[i]["w"] * vis[i]["h"], vis[j]["w"] * vis[j]["h"]) or 1
                if inter / small > 0.12:
                    problems.append(
                        f"OVERLAP  {vis[i]['id']} × {vis[j]['id']} 压住 {inter * 100 // small}%")
        if not vis:
            problems.append("EMPTY    一个可见浮层都没有（观众没有控件可点）")

        cdp.call("Page.captureScreenshot", {"format": "png"})
        r = cdp.call("Page.captureScreenshot", {"format": "png"})
        import base64
        with open(SHOT, "wb") as f:
            f.write(base64.b64decode(r["data"]))
        print(f"   截图：{SHOT}")

        if problems:
            print("== 问题 ==")
            for p in problems:
                print("  " + p)
            return 1
        print("== 横屏观众页：没有越界 / 没有互相压住 ==")
        return 0
    finally:
        if cdp:
            cdp.close()
        proc.terminate()


if __name__ == "__main__":
    sys.exit(main())
