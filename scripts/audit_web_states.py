#!/usr/bin/env python3
"""不靠房主、不靠隧道，把网页观众端在几种视口下的每一屏量一遍 —— 用页面自带的演示模式。

为什么需要它：隧道的邀请链接每次都要重开一条会话，而 t2test 这类设备在多人机器上会被
别的 agent 占走；可"观众端长什么样"这件事本来就不该依赖那条最不稳的链路。
页面早就留了 `?demo=canvas`（画一块假画面）与 `&cinema=1`（演一遍已按下开始放映），
这里把它用起来：同一份 HTML，三种视口，矩形与截图都从 CDP 直接取。

判据与 drive_web_landscape 共用一套（越界 / 互相压住 >12% / 一个可见浮层都没有），
另外补一条**状态过渡**：演完放映之后调 `onCinema('')`（= 房主收厅），
必须看到一句解释，而不是横条凭空消失。
"""
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
from drive_web_landscape import AUDIT_JS, IDS  # noqa: E402  同一套判据，别再抄一份

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
PAGE = pathlib.Path(HERE).parent / "app" / "src" / "main" / "assets" / "viewer" / "index.html"
# 仓库里就带一条真 mp4（房主侧本地测试页用的就是它）。放映条要"留在屏上"，本地播放器
# 必须真的能取到片 —— 给个假地址，页面会走"这条地址在观众这边取不到"的退回分支、
# 把放映条整个藏掉，量到的又是没有条的那一屏（这个坑本轮踩过两次）。
# 而且页面自己挡非 http 地址（`/^https?:\/\//`），所以样片必须走**本地 http**，
# 不能用 file://：下面起一个一次性 http.server 专门喂它。
ASSETS = PAGE.parent.parent
HTTP_PORT = str(8900 + int(time.time()) % 90)
SAMPLE = f"http://127.0.0.1:{HTTP_PORT}/watch/sample.mp4"
# 每次跑换一个调试端口：上一轮的 Edge 子进程可能还占着固定端口，
# 那样 /json 会列出**已经死掉的**标签，WS 一连上就 recv 超时（实测踩过）。
PORT = str(9400 + int(time.time()) % 400)

VIEWS = [
    ("竖屏手机", 390, 844),
    ("横屏手机", 840, 396),
    ("矮横屏（分屏/小窗）", 640, 360),
]


def audit(cdp):
    r = cdp.eval(f"({AUDIT_JS})({json.dumps(IDS)})")
    a = r.get("value") if isinstance(r, dict) else r
    problems = []
    vw, vh = a["vw"], a["vh"]
    shown = [e for e in a["els"] if e["shown"]]
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
                problems.append(f"OVERLAP  {vis[i]['id']} × {vis[j]['id']} 压住 {inter * 100 // small}%")
    if not vis:
        problems.append("EMPTY    一个可见浮层都没有")
    return a, shown, problems


def main():
    # 一次性静态服务器，只为把仓库里那条 sample.mp4 用 http 喂给页面
    srv = subprocess.Popen(
        [sys.executable, "-m", "http.server", HTTP_PORT, "--directory", str(ASSETS)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.2)
    udd = os.path.join(os.environ.get("TEMP", "."), f"t2-web-states-{int(time.time())}")
    proc = subprocess.Popen(
        [EDGE, "--headless=new", f"--remote-debugging-port={PORT}",
         f"--user-data-dir={udd}", "--window-size=900,800",
         "--autoplay-policy=no-user-gesture-required",
         # 页面与样片都在 file:// 下：不加这句 Chromium 不让 file 页面取本地媒体
         "--allow-file-access-from-files",
         "--disable-gpu", "--no-first-run",
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
        # 页面也走本地 http 而不是 file://：演示注入的是 .m3u8，页面在 file:// 下取不到
        # hls.js 就会自己退回、把放映条藏掉（量到的又是没有条的那一屏）。同源 http 下
        # 相对路径与 CDN 都能取，放映条才真的留在屏上。
        url = f"http://127.0.0.1:{HTTP_PORT}/viewer/index.html?demo=canvas&cinema=1"

        for label, w, h in VIEWS:
            print(f"== {label} {w}x{h} ==")
            cdp.call("Emulation.setDeviceMetricsOverride",
                     {"width": w, "height": h, "deviceScaleFactor": 2, "mobile": True})
            cdp.call("Page.navigate", {"url": url})
            time.sleep(3.2)
            # 演示注入的是 .m3u8 —— 在 file:// 下 hls.js 加载不到，页面会自己退回
            # "看对方屏幕"并把放映条藏掉（第一版就在这里报了个假干净：量到的其实是
            # 没有放映条的那一屏）。所以显式喂一条 mp4 类型的状态：走原生 video 元素，
            # 不需要外部脚本，放映条会留在屏上供我量。
            cdp.eval(
                "onCinema(['1','" + SAMPLE + "','mp4',"
                "'《漫长的季节》第 3 集',"
                "252000,634000,1,1,' + Date.now() + ',1].join('|'))"
            )
            # 等放映条**真的出现**再量：hls.js 是外部脚本，冷缓存时它到得比我的注入晚，
            # 前两个视口就在"条还没挂上来"的那一刻量了 —— 报出来的 EMPTY 是量早了，
            # 不是界面坏了（第三个视口因为脚本已缓存才恰好量到）。
            for _ in range(12):
                r = cdp.eval("!document.getElementById('cineBar').classList.contains('hide')")
                if isinstance(r, dict) and r.get("value"):
                    break
                time.sleep(0.8)
            a, shown, problems = audit(cdp)
            # "可控制"必须看着就像可控制：房主放开进度时这颗 chip 要带 ok 类（绿色）。
            # 之前它写成了一个 CSS 里根本不存在的 class 名（' g'），能力被样式藏住了。
            r = cdp.eval("JSON.stringify([document.getElementById('cPerm').className,"
                         "getComputedStyle(document.getElementById('cPerm')).color])")
            v = (r.get("value") if isinstance(r, dict) else r) or "[]"
            try:
                cls, color = json.loads(v)
            except Exception:
                cls, color = "", ""
            print(f"   权限徽章：class={cls!r} color={color}")
            # 判据用**类名集合**，不要拿字符串去 in —— className 是整串 "chip ok"，
            # 上一版找 '"ok"' 永远找不到，把一次修好的东西报成三条 STYLE 假警。
            if "ok" not in cls.split():
                problems.append("STYLE    可控制的 chip 没拿到 ok 类（看着像不能按）")
            for e in shown:
                print(f"   ✓ {e['id']:9} [{e['x']:4},{e['y']:4}] {e['w']:4}x{e['h']:<4} {e['text'][:24]!r}")
            r = cdp.call("Page.captureScreenshot", {"format": "png"})
            import base64
            out = os.path.join(HERE, "..", ".dev", f"web-{w}x{h}.png")
            with open(out, "wb") as f:
                f.write(base64.b64decode(r["data"]))
            if problems:
                bad += 1
                for p in problems:
                    print("   " + p)
            else:
                print(f"   干净。截图 .dev/{os.path.basename(out)}")

        # 状态过渡：房主收厅 ⇒ 必须留下一句解释，不能让横条凭空消失
        print("== 收厅过渡（onCinema('')）==")
        cdp.call("Emulation.setDeviceMetricsOverride",
                 {"width": 840, "height": 396, "deviceScaleFactor": 2, "mobile": True})
        cdp.eval("onCinema('')")
        time.sleep(0.8)
        r = cdp.eval("Array.from(document.querySelectorAll('.hint,[id=panel]'))"
                     ".map(e => (e.textContent||'').trim().slice(0,30)).filter(Boolean)")
        note = (r.get("value") if isinstance(r, dict) else r) or []
        print("   屏幕上的解释：", note)
        shot = os.path.join(HERE, "..", ".dev", "web-cinema-closed.png")
        rr = cdp.call("Page.captureScreenshot", {"format": "png"})
        import base64
        with open(shot, "wb") as f:
            f.write(base64.b64decode(rr["data"]))
        if not any("收" in t or "停" in t for t in note):
            bad += 1
            print("   FAIL  收厅之后没有任何解释（横条凭空消失）")
        else:
            print("   PASS  收厅有解释")
        cdp.close()
    finally:
        proc.terminate()
        srv.terminate()
    print("== 结论 ==", "有问题，见上面各行" if bad else "三种视口 + 收厅过渡全部干净")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
