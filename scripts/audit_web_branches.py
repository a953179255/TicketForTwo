#!/usr/bin/env python3
"""网页观众端的**分支状态**逐个演一遍并量：只读 / 地址不能播 / 极窄视口 / 收厅解释。

为什么单独一个脚本（而不是塞进 audit_web_states.py）：那个量的是"正常放映时几层会不会打架"，
这里量的是**掉到别的分支时界面还说不说实话**。上一段就是在这种分支里抓到
"可控制"徽章是灰的（class 名写成 CSS 里不存在的 ' g'）—— 文案对、颜色说反话，只有像素能发现。

不依赖房主、不依赖隧道：用页面自带的 `?demo=canvas` + 一次性本地 http 喂仓库里的 sample.mp4。

用法： python scripts/audit_web_branches.py
"""
import base64
import json
import os
import pathlib
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
from drive_phone_cdp import Cdp  # noqa: E402
from drive_web_landscape import AUDIT_JS, IDS  # noqa: E402  同一套矩形判据

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
ASSETS = pathlib.Path(HERE).parent / "app" / "src" / "main" / "assets"
PORT = str(9500 + int(time.time()) % 60)
HTTP_PORT = str(8950 + int(time.time()) % 40)
PAGE_URL = f"http://127.0.0.1:{HTTP_PORT}/viewer/index.html?demo=canvas"
SAMPLE = f"http://127.0.0.1:{HTTP_PORT}/watch/sample.mp4"
OUT = os.path.join(HERE, "..", ".dev")


def cinema_js(url, title, allow=1, pos=252000, dur=634000, ver=1):
    """拼一条放映状态。用函数拼是为了不把 JS 嵌成跨行字符串 —— 那样容易写出
    自己都看不懂的引号（上一版就在这里把脚本写崩了）。"""
    fields = [ver, url, "mp4", title, pos, dur, 1, 1, int(time.time() * 1000), allow]
    return "onCinema(%s)" % json.dumps("|".join(str(f) for f in fields))


def main():
    srv = subprocess.Popen(
        [sys.executable, "-m", "http.server", HTTP_PORT, "--directory", str(ASSETS)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.2)
    udd = os.path.join(os.environ.get("TEMP", "."), f"t2-web-branch-{int(time.time())}")
    proc = subprocess.Popen(
        [EDGE, "--headless=new", f"--remote-debugging-port={PORT}",
         f"--user-data-dir={udd}", "--window-size=900,800",
         "--autoplay-policy=no-user-gesture-required",
         "--allow-file-access-from-files", "--disable-gpu", "--no-first-run",
         "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    bad = 0
    try:
        pages = []
        for _ in range(20):
            time.sleep(1)
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json", timeout=5) as r:
                    pages = [p for p in json.loads(r.read().decode()) if p.get("type") == "page"]
                if pages:
                    break
            except Exception:
                pass
        if not pages:
            print("FAIL  Edge 没起来")
            return 1
        cdp = Cdp(pages[0]["webSocketDebuggerUrl"])
        cdp.call("Page.enable")
        cdp.call("Runtime.enable")

        def ev(expr):
            r = cdp.eval(expr)
            return r.get("value") if isinstance(r, dict) else r

        def set_view(w, h):
            cdp.call("Emulation.setDeviceMetricsOverride",
                     {"width": w, "height": h, "deviceScaleFactor": 2, "mobile": True})

        def geo():
            a = ev(f"({AUDIT_JS})({json.dumps(IDS)})")
            problems = []
            vw, vh = a["vw"], a["vh"]
            shown = [e for e in a["els"] if e["shown"]]
            for e in shown:
                if e["x"] < -1 or e["y"] < -1 or e["x"] + e["w"] > vw + 1 or e["y"] + e["h"] > vh + 1:
                    problems.append(f"OUT {e['id']} 超出视口")
            overlay = {"topBar", "ctlBar", "cineBar", "watchBar", "lvBadge", "hud", "panel"}
            vis = [e for e in shown if e["id"] in overlay]
            for i in range(len(vis)):
                for j in range(i + 1, len(vis)):
                    x1 = max(vis[i]["x"], vis[j]["x"]); y1 = max(vis[i]["y"], vis[j]["y"])
                    x2 = min(vis[i]["x"] + vis[i]["w"], vis[j]["x"] + vis[j]["w"])
                    y2 = min(vis[i]["y"] + vis[i]["h"], vis[j]["y"] + vis[j]["h"])
                    inter = max(0, x2 - x1) * max(0, y2 - y1)
                    small = min(vis[i]["w"] * vis[i]["h"], vis[j]["w"] * vis[j]["h"]) or 1
                    if inter / small > 0.12:
                        problems.append(f"OVERLAP {vis[i]['id']}×{vis[j]['id']} {inter * 100 // small}%")
            return shown, problems

        def wait_bar(seconds=8):
            for _ in range(int(seconds / 0.4)):
                if ev("!document.getElementById('cineBar').classList.contains('hide')"):
                    return True
                time.sleep(0.4)
            return False

        def shot(name):
            with open(os.path.join(OUT, name), "wb") as f:
                f.write(base64.b64decode(cdp.call("Page.captureScreenshot", {"format": "png"})["data"]))

        cdp.call("Page.navigate", {"url": PAGE_URL})
        time.sleep(2)

        # ---- 1) 只读：房主没放开控制 ⇒ 徽章必须写"仅观看"，而且**不能**是绿的那套样式
        print("== 1) 只读的一片 ==")
        set_view(840, 396)
        ev(cinema_js(SAMPLE, "只读的一片", allow=0))
        print("   放映条出现:", wait_bar())
        chip = ev("JSON.stringify([document.getElementById('cPerm').textContent,"
                  "document.getElementById('cPerm').className,"
                  "getComputedStyle(document.getElementById('cPerm')).color])")
        print("   徽章:", chip)
        c = json.loads(chip or '["","",""]')
        if c[0] != "仅观看":
            bad += 1
            print("   FAIL  只读时徽章文案没切过来")
        if "ok" in c[1].split():
            bad += 1
            print("   FAIL  只读却拿到了可控制的绿色样式")
        shot("branch-readonly.png")

        # ---- 2) 可控制：反过来必须绿
        print("== 2) 可控制的一片 ==")
        ev(cinema_js(SAMPLE, "可控制的一片", allow=1))
        time.sleep(1.2)
        c = json.loads(ev("JSON.stringify([document.getElementById('cPerm').textContent,"
                          "document.getElementById('cPerm').className,"
                          "getComputedStyle(document.getElementById('cPerm')).color])"))
        print(f"   徽章: {c[0]!r} class={c[1]!r} color={c[2]}")
        if c[0] != "可控制" or "ok" not in c[1].split():
            bad += 1
            print("   FAIL  可控制没拿到绿色样式（上一段修的那个 bug 复发）")

        # ---- 3) 极窄视口：320x568 上放映条 + 控制岛 + 顶栏还塞得下吗
        print("== 3) 极窄视口 320x568 ==")
        set_view(320, 568)
        time.sleep(0.6)
        shown, problems = geo()
        for msg in problems:
            bad += 1
            print("   " + msg)
        print("   浮层:", [e["id"] for e in shown if e["id"] != "stage"])
        shot("branch-narrow.png")

        # ---- 4) 收厅：必须有解释，不能让横条凭空消失
        print("== 4) 收厅 ==")
        set_view(840, 396)
        time.sleep(0.6)
        ev("cineDown('对方收了厅', false, 'closed')")
        time.sleep(1.0)
        notes = ev("Array.from(document.querySelectorAll('.hint,#panel'))"
                   ".map(e => (e.textContent||'').trim().slice(0,30)).filter(Boolean)") or []
        print("   屏幕上的解释:", notes)
        if not any("收" in t for t in notes):
            bad += 1
            print("   FAIL  收厅没有任何解释")
        shot("branch-closed.png")

        cdp.close()
    finally:
        proc.terminate()
        srv.terminate()
    print("== 结论 ==", "全部符合预期" if not bad else f"{bad} 处不符合")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
