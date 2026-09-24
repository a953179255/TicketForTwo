#!/usr/bin/env python3
"""回归检查：房主点「停止分享」时，观众端必须看到**正常结束**，不是"连不上"。

为什么要专门一个脚本：这条判据是"观众被说了什么"，不是"有没有出错"。
2026-09-25 之前的行为是：房主停止分享 → 隧道随之没 → 观众侧媒体连接 failed →
页面/界面走失败分支，标题「连不上房主的手机」＋「纯直连在部分网络下会失败」＋
三条换网络建议。一个正常动作被报成用户的网络故障，而这正是用户报上来的问题。

所以这里断言的是**面板标题的文本**，光"页面有反应"不算通过：
  1. 观众端最后显示的标题里必须有"结束/断了"，且**不得**是"连不上房主的手机"；
  2. 房主 logcat 里必须有「已告知观众：分享结束」—— 证明那句 bye 真的写进了 socket，
     而不是排进队列后被 SignalHub.stop() 的 outbox.clear() 一起扔掉。

用法：先起统计采集器 `python scripts/dev_viewer_server.py 8792`，
再跑 `python scripts/drive_m0.py` 完成分享，最后跑本脚本。
（本脚本自己不再点「开始分享」，免得和 drive_m0 的装机流程抢界面。）
"""
import json
import os
import subprocess
import sys
import time
from urllib.parse import urlencode

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# 复用而不是另写一份：按文字找节点再点中心、logcat 取日志，都已经在 roundtrip 脚本里
# 踩平过坑（子串匹配、utf-8 解码）。那个脚本有 __main__ 守卫，import 不会有副作用。
import check_restart_roundtrip as rt
import t2device

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
INVITE = os.path.join(ROOT, ".dev", "invite.txt")
REPORTS = os.path.join(ROOT, ".dev", "reports.jsonl")
DEV_PORT = 8792

# 判定用的词。失败分支的标题是"连不上房主的手机"，正常收场是"房主结束了分享"。
BAD_WORDS = ("连不上房主的手机", "直连失败", "对称 NAT")
GOOD_WORDS = ("结束了分享", "连接断了", "连接先断了")


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


def open_viewer(url):
    """开一个无头 Edge 当观众，把统计 POST 回本机采集器。返回 Popen。"""
    sep = "&" if "?" in url else "?"
    target = f"{url}{sep}{urlencode({'report': '1', 'relay': f'http://127.0.0.1:{DEV_PORT}/report'})}"
    return subprocess.Popen(
        [EDGE, "--headless=new",
         "--use-fake-ui-for-media-stream",
         "--use-fake-device-for-media-stream",
         "--autoplay-policy=no-user-gesture-required",
         # 页面在公网隧道上、统计要 POST 回本机 —— Chrome 的 Local Network Access
         # 会掐这种"公网→本地"请求，无头没有授权 UI 可点。只影响这台一次性 profile。
         "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessSendPreflights",
         "--disable-gpu", "--no-first-run",
         f"--user-data-dir={os.environ.get('TEMP', '.')}\\t2-edge-viewer",
         "--window-size=1280,800", target],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def wait_connected(limit=45):
    """等观众真的看到画面：面板收起、且解出过视频帧。"""
    for _ in range(limit):
        time.sleep(1)
        for r in reports():
            if r.get("panelHidden") and ((r.get("stats") or {}).get("framesDecoded") or 0) > 0:
                return True
    return False


def wait_ended_panel(limit=12):
    """等观众把"结束"那一屏画出来，返回 (标题, 是否误报成失败)。"""
    for _ in range(limit):
        time.sleep(1)
        for r in reversed(reports()):
            h1 = r.get("panelH1")
            if not h1:
                continue
            if any(w in h1 for w in GOOD_WORDS):
                return h1, any(w in h1 for w in BAD_WORDS)
    return None, False


def main():
    serial = t2device.resolve()
    print(f"== 目标 {serial} ==")

    if not os.path.exists(INVITE):
        print("缺少 .dev/invite.txt，先跑 scripts/drive_m0.py")
        return 1
    url = open(INVITE, encoding="utf-8").read().strip()
    if "?k=" not in url:
        print("invite.txt 里不是隧道邀请")
        return 1

    if os.path.exists(REPORTS):
        os.remove(REPORTS)
    rt.sh(rt.ADB, "-s", serial, "logcat", "-c")   # 只看这一轮之后的日志

    proc = open_viewer(url)
    try:
        if not wait_connected():
            print("FAIL  观众端没能出画面，后面的判定无从谈起")
            return 1
        print("  观众已看到画面，房主点「停止分享」")
        if not rt.tap("停止分享"):
            print("FAIL  找不到「停止分享」按钮 —— 是不是根本没在分享？")
            return 1

        h1, mixed = wait_ended_panel()
        time.sleep(1)
        log = rt.logs()
    finally:
        proc.terminate()

    said_bye = "已告知观众：分享结束" in log
    print(f"  观众端面板标题：{h1!r}")
    print(f"  房主侧已把再见写进 socket：{said_bye}")

    if h1 is None:
        print("FAIL  房主停止后观众端没有画出收场面板（可能一直卡在画面上，或采集断了）")
        return 1
    if mixed:
        print("FAIL  收场面板里混进了失败话术（换网络建议那类）")
        return 1
    if not said_bye:
        print("FAIL  房主没把 bye 写出去：观众那句『结束』是靠通道断开猜的，不是收到的")
        return 1
    print("PASS  正常结束被如实报成『结束』，且那句道别确实从房主写了出去")
    return 0


if __name__ == "__main__":
    sys.exit(main())
