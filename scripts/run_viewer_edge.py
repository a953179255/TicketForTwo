#!/usr/bin/env python3
"""用带假媒体设备的真实 Chromium(Edge) 跑观众端，验证连麦的音频轨道协商。

为什么不能用 Qoder 内置浏览器：它对 getUserMedia 直接返回
NotAllowedError: Permission denied，音频那条腿根本没有输入。

--use-fake-ui-for-media-stream      自动放行授权弹窗（无头没有 UI 可点）
--use-fake-device-for-media-stream  提供假麦克风/摄像头
--autoplay-policy=no-user-gesture-required  无手势也允许播放

注意：假设备只能证明"轨道双向协商通、音频包在流动"，
**证明不了真实回声消除效果** —— 那需要真机或真人外放。

新流程（a0cc901 隧道单轮）：.dev/invite.txt 是完整隧道链接 https://…/?k=…，
页面打开即 hello 自动进房，没有 answer 回传环节；页面 WS 绑 location.host，
必须原样打开（只允许追加调试参数），统计经 ?relay= 回收本机 dev server。

用法： python scripts/run_viewer_edge.py [等待秒数]
前置： 先跑 scripts/drive_m0.py 生成 .dev/invite.txt，
      并起 python scripts/dev_viewer_server.py 8792（统计落盘）
"""
import json
import os
import subprocess
import sys
import time
from urllib.parse import urlencode

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
INVITE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".dev", "invite.txt")
REPORTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".dev", "reports.jsonl")
DEV_PORT = 8792  # 与 dev_viewer_server.py 的默认端口一致


def main():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import t2device
    serial = t2device.resolve()
    print(f"== 目标设备：{serial}（按 AVD 名 t2test 解析）==")

    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    wait = int(args[0]) if args else 30

    url = open(INVITE, encoding="utf-8").read().strip()
    # 隧道邀请是完整链接（https://…trycloudflare.com/?k=…），页面 WS 绑
    # location.host，必须原样打开，只允许在 query 上追加调试参数。
    if not (url.startswith("http") and "?k=" in url):
        print("invite.txt 里没有隧道邀请（?k=）")
        return 1
    # 统计回收到本机采集器：页面同源 /report 会打到 SignalHub（它没有 /report），
    # 用 relay= 指回 dev server；公网→本机的 LNA 由下方 --disable-features 放行。
    sep = "&" if "?" in url else "?"
    target = (f"{url}{sep}"
              f"{urlencode({'report': '1', 'relay': f'http://127.0.0.1:{DEV_PORT}/report'})}")
    print(f"== 打开隧道邀请（{len(url)}ch），等待 {wait}s ==")

    if os.path.exists(REPORTS):
        os.remove(REPORTS)

    proc = subprocess.Popen(
        [EDGE, "--headless=new",
         "--use-fake-ui-for-media-stream",
         "--use-fake-device-for-media-stream",
         "--autoplay-policy=no-user-gesture-required",
         # 页面在公网隧道上、统计要 POST 到本机 127.0.0.1 —— Chrome 的
         # Local Network Access 保护会掐掉这种"公网→本地"请求（无头没有授权 UI）。
         # 关掉它只影响这台一次性 profile 的浏览器，是测试夹具，不是产品配置。
         "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessSendPreflights",
         "--disable-gpu", "--no-first-run",
         f"--user-data-dir={os.environ.get('TEMP', '.')}\\t2-edge-viewer",
         "--window-size=1280,800", target],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        last = None
        for _ in range(wait):
            time.sleep(1)
            if not os.path.exists(REPORTS):
                continue
            lines = open(REPORTS, encoding="utf-8").read().strip().splitlines()
            if not lines:
                continue
            last = json.loads(lines[-1])
            st = last.get("stats") or {}
            print(f"  t+{len(lines):>3} conn={str(last.get('conn')):11} ice={last.get('ice')} "
                  f"frames={st.get('framesDecoded')} fps={st.get('fps')} "
                  f"rtp={len(last.get('rtp') or [])}")
    finally:
        proc.terminate()

    if not last:
        print("没有任何回传，检查 dev_viewer_server 是否在跑")
        return 1

    print("\n=== 最后一帧统计 ===")
    print(json.dumps(last, ensure_ascii=False, indent=2)[:1600])
    kinds = last.get("rtp") or []
    out_audio = [k for k in kinds if k.startswith("out:audio")]
    in_video = [k for k in kinds if k.startswith("in:video")]
    print("\n=== 判定 ===")
    print(("PASS" if in_video else "FAIL"), "下行视频到达:", in_video[:1])
    print(("PASS" if out_audio else "FAIL"), "上行麦克风:", out_audio[:1])
    return 0


if __name__ == "__main__":
    sys.exit(main())
