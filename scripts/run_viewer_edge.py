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

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
ADB = r"C:\Android\sdk\platform-tools\adb.exe"
SERIAL = None  # 运行时按 AVD 名解析，不写死端口，见 t2device.py
ACT = "com.ticketfortwo.app/.MainActivity"
INVITE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".dev", "invite.txt")
REPORTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".dev", "reports.jsonl")


def main():
    global SERIAL
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import t2device
    SERIAL = t2device.resolve()
    print(f"== 目标设备：{SERIAL}（按 AVD 名 t2test 解析）==")
    wait = int(sys.argv[1]) if len(sys.argv) > 1 else 30
    url = open(INVITE, encoding="utf-8").read().strip()
    m = re.match(r"https://share\.local/(#t2=.+)", url)
    if not m:
        print("invite.txt 里没有 token")
        return 1
    target = f"http://127.0.0.1:8792/index.html?report=1&autojoin=1{m.group(1)}"
    print(f"target token={len(m.group(1)) - 4}ch, 等待 {wait}s")

    if os.path.exists(REPORTS):
        os.remove(REPORTS)

    proc = subprocess.Popen(
        [EDGE, "--headless=new",
         "--use-fake-ui-for-media-stream",
         "--use-fake-device-for-media-stream",
         "--autoplay-policy=no-user-gesture-required",
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
                ans_url = f"http://127.0.0.1:8792/index.html#t2={last['answerToken']}"
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
