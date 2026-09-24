#!/usr/bin/env python3
"""量观众端"画面框 / 视口 / 全屏"的真实几何，用手机的窗口尺寸复现裁切。

为什么要有这个脚本：画面被裁、点全屏没反应，光看截图分不清是"视频框算错了"还是
"布局视口本来就比看得见的高"（移动端 100vh 的经典坑）。这两种病的修法完全不同，
而 computed style 全绿也照样看不出来 —— 必须把 innerHeight 与 visualViewport.height
放在一起比。

用法：先分享并生成 .dev/invite.txt，再起 dev 采集器
（python scripts/dev_viewer_server.py 8792），然后：
    python scripts/measure_viewer_geo.py [--window 412,915] [--wait 25]
"""
import argparse
import json
import os
import subprocess
import sys
import time
from urllib.parse import urlencode

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
INVITE = os.path.join(ROOT, ".dev", "invite.txt")
REPORTS = os.path.join(ROOT, ".dev", "reports.jsonl")
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
DEV_PORT = 8792


def reports():
    if not os.path.exists(REPORTS):
        return []
    out = []
    for ln in open(REPORTS, encoding="utf-8", errors="replace").read().splitlines():
        try:
            out.append(json.loads(ln))
        except ValueError:
            pass
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--window", default="412,915", help="无头窗口尺寸，默认按手机竖屏")
    ap.add_argument("--wait", type=int, default=25)
    a = ap.parse_args()
    w, h = a.window.split(",")

    url = open(INVITE, encoding="utf-8").read().strip()
    if "?k=" not in url:
        print("invite.txt 里没有隧道邀请")
        return 1
    target = f"{url}&{urlencode({'report': '1', 'relay': f'http://127.0.0.1:{DEV_PORT}/report'})}"
    if os.path.exists(REPORTS):
        os.remove(REPORTS)

    proc = subprocess.Popen(
        [EDGE, "--headless=new",
         "--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
         "--autoplay-policy=no-user-gesture-required",
         "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessSendPreflights",
         "--disable-gpu", "--no-first-run",
         f"--user-data-dir={os.environ.get('TEMP', '.')}\\t2-edge-geo",
         f"--window-size={w},{h}", target],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        time.sleep(a.wait)
    finally:
        proc.terminate()

    rows = [r for r in reports() if r.get("geo")]
    if not rows:
        print("没有任何回传，检查 dev_viewer_server 是否在跑、链接是否还有效")
        return 1
    for r in rows[-3:]:
        g = r["geo"]
        v = r.get("video") or {}
        print(f"视口 inner={g['iw']}x{g['ih']}  visualViewport={g['vvw']}x{g['vvh']}  "
              f"outer={g['osh']}  stage={g['stageW']}x{g['stageH']}  video={g['vidW']}x{g['vidH']}  "
              f"fit={g['fit']}  fullscreen={g['fs']}")
        st = r.get("stats") or {}
        print(f"    帧尺寸={st.get('width')}x{st.get('height')}  currentTime={v.get('t')}  "
              f"标题={r.get('panelH1')!r}")
    g = rows[-1]["geo"]
    # 100vh 坑的判据：布局视口比看得见的高，就是有一部分画面被浏览器外壳盖住
    if g["vvh"] and g["ih"] - g["vvh"] > 2:
        print(f"\n⚠ 布局视口 {g['ih']} 比可见区 {g['vvh']} 高 {round(g['ih'] - g['vvh'])}px "
              f"⇒ 用 100vh 撑起来的底部会被裁掉")
    return 0


if __name__ == "__main__":
    sys.exit(main())
