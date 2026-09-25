#!/usr/bin/env python3
"""端到端验一件事：房主中途开画面时，**网页观众端**拿到画面、而且没有重建连接。

为什么单独要一个脚本（而不是只看 App 端那条 check_share_after_join）：
最常见的观众是朋友手机上的浏览器，不是同一个 APK。而网页侧 `startPeer` 原来
**每次收到 offer 都 new 一条 RTCPeerConnection** —— 房主中途加视频轨时，浏览器会把
正在跑的连麦整条丢掉、旧的那条还挂着不关。改成"有活着的 pc 就复用"之后，
必须有人量一遍：画面真的接上来了吗？连接真的没被重建吗？

判据全部来自页面自己 POST 回来的统计（`?report=1&relay=`，由 dev_viewer_server 落盘）：
  1. 进厅后 `conn=connected` 且 `vW=0`（厅先开只有语音 —— 这一步证明前置是真的）
  2. 房主按「让他看我的屏幕」并过完系统弹窗之后，出现 `vW>0`（画面解码出尺寸 = 真到了）
  3. 全程 `pcSeq === 1`（只建过一条 pc = 复用生效；>1 就是老毛病复发）
  4. 全程没出现 `conn=failed`，且 `pcSeq` 没有在一次协商后跳号

用法：
    python scripts/open_room.py            # 房主开厅（只起信令 + 语音）
    python scripts/drive_web_renegotiate.py
"""
import json
import os
import subprocess
import sys
import time
from urllib.parse import urlencode

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audit_app_layout as A  # noqa: E402
import check_share_after_join as btier  # noqa: E402  复用"过系统投屏弹窗"那一段

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
INVITE = os.path.join(HERE, "..", ".dev", "invite.txt")
REPORTS = os.path.join(HERE, "..", ".dev", "reports.jsonl")
DEV_PORT = os.environ.get("T2_DEV_PORT", "8792")
HOST = "emulator-5556"


def read_lines():
    if not os.path.exists(REPORTS):
        return []
    out = []
    for ln in open(REPORTS, encoding="utf-8", errors="replace").read().splitlines():
        ln = ln.strip()
        if not ln:
            continue
        try:
            out.append(json.loads(ln))
        except Exception:
            pass
    return out


def main():
    url = open(INVITE, encoding="utf-8").read().strip()
    if "?k=" not in url:
        print("FAIL  .dev/invite.txt 里没有隧道邀请，先跑 scripts/open_room.py")
        return 1

    if os.path.exists(REPORTS):
        os.remove(REPORTS)
    # 统计回收器：页面把 POST 打到本机，必须有进程在听（已经在听就不重复起）
    srv = None
    try:
        import socket
        probe = socket.socket()
        probe.settimeout(0.4)
        listening = probe.connect_ex(("127.0.0.1", int(DEV_PORT))) == 0
        probe.close()
    except Exception:
        listening = False
    if not listening:
        srv = subprocess.Popen([sys.executable, os.path.join(HERE, "dev_viewer_server.py"), DEV_PORT],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(2)

    sep = "&" if "?" in url else "?"
    target = f"{url}{sep}{urlencode({'report': '1', 'relay': f'http://127.0.0.1:{DEV_PORT}/report'})}"
    # **每轮换一个 user-data-dir**：profile 被上一轮的残留进程锁住时，新进程会把 URL
    # 交给那个旧实例然后自己退出 —— 浏览器"起来了"，观众其实没进厅。
    udd = os.path.join(os.environ.get("TEMP", "."), f"t2-edge-reneg-{int(time.time())}")
    proc = subprocess.Popen(
        [EDGE, "--headless=new",
         "--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
         "--autoplay-policy=no-user-gesture-required",
         "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessSendPreflights",
         "--disable-gpu", "--no-first-run", f"--user-data-dir={udd}",
         "--window-size=1280,800", target],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    joined = None
    for _ in range(40):
        time.sleep(1)
        for r in read_lines():
            if r.get("conn") == "connected":
                joined = r
                break
        if joined:
            break
    if not joined:
        print("FAIL  浏览器没进厅（没收到 conn=connected 的统计）")
        proc.terminate()
        if srv:
            srv.terminate()
        return 1
    print(f"   进厅：conn={joined.get('conn')} pcSeq={joined.get('pcSeq')} "
          f"vW={joined.get('vW')} panelH1={str(joined.get('panelH1'))[:24]!r}")
    if joined.get("vW"):
        print("FAIL  厅还没开画面就有视频尺寸 —— 前置不对（房主可能已经在投屏）")
        proc.terminate()
        if srv:
            srv.terminate()
        return 1

    print("== 房主按「让他看我的屏幕」并过系统弹窗 ==")
    # 房主很可能还停在**放映厅**那一屏（open_room 就停在那儿）：那颗钮在会话屏的卡片里。
    # ⚠ 别写成 `a() or (b() and time.sleep(2) and c())` —— sleep 返回 None，
    # 整条 and 链会在那里断掉，第三下根本没按（上一轮就是这么"点了没反应"的）。
    if not A.tap_text(HOST, "让他看我的屏幕", tries=2):
        A.tap_text(HOST, "返回")
        time.sleep(2)
        A.tap_text(HOST, "让他看我的屏幕")
    time.sleep(2)
    A.tap_text(HOST, "我知道了，继续")
    time.sleep(2.5)
    print("   弹窗：", btier.walk_projection_dialog(HOST))

    ok = None
    seen_seq = set()
    last_trace = None
    for i in range(100):
        time.sleep(1)
        for r in read_lines():
            if r.get("pcSeq"):
                seen_seq.add(r["pcSeq"])
            trace = f"pcSeq={r.get('pcSeq')} conn={r.get('conn')} vW={r.get('vW')}"
            if trace != last_trace:
                print(f"   t+{i:>3} {trace}")
                last_trace = trace
            if r.get("conn") == "failed":
                ok = "failed"
                break
            if (r.get("vW") or 0) > 0 and r.get("conn") == "connected":
                ok = r
                break
        if ok:
            break

    proc.terminate()
    if srv:
        srv.terminate()

    if ok == "failed":
        print("FAIL  重新协商把连接搞成 failed 了")
        return 1
    if not ok or not isinstance(ok, dict):
        print("FAIL  房主开画面之后浏览器始终没拿到视频（vW 一直是 0）")
        return 1
    print(f"   画面到了：vW={ok.get('vW')}x{ok.get('vH')} conn={ok.get('conn')} "
          f"pcSeq={ok.get('pcSeq')} gotVideo={ok.get('gotVideo')}")
    if max(seen_seq) > 1:
        print(f"FAIL  建过 {max(seen_seq)} 条 RTCPeerConnection —— 复用没生效，连麦会被丢掉")
        return 1
    print("PASS  房主中途开画面：网页观众端拿到画面，且全程复用同一条连接（pcSeq=1）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
