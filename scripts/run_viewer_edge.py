#!/usr/bin/env python3
"""用带假媒体设备的真实 Chromium(Edge) 跑观众端，验证连麦的音频轨道协商。

为什么不能用 Qoder 内置浏览器：它对 getUserMedia 直接返回
NotAllowedError: Permission denied，音频那条腿根本没有输入。

--use-fake-ui-for-media-stream      自动放行授权弹窗（无头没有 UI 可点）
--use-fake-device-for-media-stream  提供假麦克风/摄像头
--autoplay-policy=no-user-gesture-required  无手势也允许播放

注意：假设备只能证明"轨道双向协商通、音频包在流动"，
**证明不了真实回声消除效果** —— 那需要真机或真人外放。

用法： python scripts/run_viewer_edge.py [等待秒数]
前置： 先跑 scripts/drive_m0.py 生成 viewer/invite.txt，
      并起 python scripts/dev_viewer_server.py 8792
"""
import json
import os
import re
import subprocess
import sys
import time
from urllib.parse import urlencode

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
ADB = r"C:\Android\sdk\platform-tools\adb.exe"
SERIAL = None  # 运行时按 AVD 名解析，不写死端口，见 t2device.py
ACT = "com.ticketfortwo.app/.MainActivity"
INVITE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".dev", "invite.txt")
REPORTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".dev", "reports.jsonl")
DEV_PORT = 8792  # 与 dev_viewer_server.py 的默认端口一致


def main():
    global SERIAL
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import t2device
    SERIAL = t2device.resolve()
    print(f"== 目标设备：{SERIAL}（按 AVD 名 t2test 解析）==")

    args = [a for a in sys.argv[1:]]
    dev = "--dev" in args
    args = [a for a in args if not a.startswith("--")]
    wait = int(args[0]) if args else 30

    url = open(INVITE, encoding="utf-8").read().strip()
    # 只认 fragment，不认域名：邀请链接的基址现在是构建输入（可能是 share.local
    # 占位，也可能是已部署的真实域名），写死 `https://share\.local/` 会让这条
    # 脚本在真实链接上直接报"没有 token"。
    head, _, frag = url.partition("#")
    if not frag.startswith("t2="):
        print("invite.txt 里没有 token")
        return 1
    origin = f"http://127.0.0.1:{DEV_PORT}/index.html" if dev else head
    # 统计一律回收到本地采集器：dev 模式是同源，部署域名走 ?relay 跨源（服务器已开 CORS）。
    params = {
        "autojoin": "1",
        "report": "1",
        "relay": f"http://127.0.0.1:{DEV_PORT}/report",
    }
    target = f"{origin}?{urlencode(params)}#{frag}"
    print(f"== 打开：{origin}（token {len(frag) - 3}ch），等待 {wait}s ==")

    if os.path.exists(REPORTS):
        os.remove(REPORTS)

    proc = subprocess.Popen(
        [EDGE, "--headless=new",
         "--use-fake-ui-for-media-stream",
         "--use-fake-device-for-media-stream",
         "--autoplay-policy=no-user-gesture-required",
         # 页面跑在公网 https 上、统计要 POST 到本机 127.0.0.1 —— Chrome 的
         # Local Network Access 保护会直接掐掉这种"公网→本地"请求（无头没有授权 UI）。
         # 关掉它只影响这台一次性 profile 的浏览器，是测试夹具，不是产品配置。
         "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessSendPreflights",
         "--disable-gpu", "--no-first-run",
         f"--user-data-dir={os.environ.get('TEMP', '.')}\\t2-edge-viewer",
         "--window-size=1280,800", target],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        last = None
        fed = False
        for _ in range(wait):
            time.sleep(1)
            if not os.path.exists(REPORTS):
                continue
            lines = open(REPORTS, encoding="utf-8").read().strip().splitlines()
            if not lines:
                continue
            last = json.loads(lines[-1])
            # 拿到应答就自动灌回 App —— 否则握手只走了一半，ICE 永远连不上
            if not fed and last.get("answerToken"):
                ans_url = f"{head}#t2={last['answerToken']}"
                env = dict(os.environ, ANDROID_ADB_SERVER_PORT="5039")
                r = subprocess.run(
                    [ADB, "-s", SERIAL, "shell", "am", "start", "-n", ACT,
                     "--es", "answer", ans_url],
                    capture_output=True, text=True, env=env)
                fed = True
                print(f"  已回灌 answer ({len(last['answerToken'])}ch) → App")
            st = last.get("stats") or {}
            print(f"  t+{len(lines):>3} conn={last['conn']:11} ice={last.get('ice')} "
                  f"frames={st.get('framesDecoded')} fps={st.get('fps')} "
                  f"rtp={len(last.get('rtp', []))}")
    finally:
        proc.terminate()

    if not last:
        print("没有任何回传，检查 dev_viewer_server 是否在跑")
        return 1

    print("\n=== 最后一帧统计 ===")
    print(json.dumps(last, ensure_ascii=False, indent=2)[:1600])
    kinds = last.get("rtp", [])
    out_audio = [k for k in kinds if k.startswith("out:audio")]
    in_video = [k for k in kinds if k.startswith("in:video")]
    print("\n=== 判定 ===")
    print(("PASS" if in_video else "FAIL"), "下行视频到达:", in_video[:1])
    print(("PASS" if out_audio else "FAIL"), "上行麦克风:", out_audio[:1])
    return 0


if __name__ == "__main__":
    sys.exit(main())
