#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给放映厅的房主卡拍"每种状态各一张"，用来验外观（不是验逻辑）。

为什么要有这个：结构判据（uiautomator 的 bounds、logcat 全绿）挡不住外观缺陷 ——
这张卡曾经用 `radiusIsland`（9999dp 胶囊），圆角被钳到短边一半，两端变成半圆，
第一行标题被玻璃边缘吃掉一半。**界面上一个字都没错，logcat 也全绿**，
只有截图能看出来。所以改完卡片样式必须回到这里看图。

流程：开厅 → 无头 Edge 进厅 → 依次递几条片源 → 每条截一张房主屏，
      顺带把观众回执抄在文件名旁边，图证和数对得上。

用法： python scripts/drive_cinema_shots.py [emulator-5556]
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import open_room  # noqa: E402  (同目录)

ADB = open_room.ADB
SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5556"
OUT = os.path.join(ROOT, ".dev")
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

# 每条对应卡片的一种状态；最后一条是"没选片"（收厅之后）
CASES = [
    ("01-waiting", None),
    ("02-screened", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
    ("03-cantplay", "https://devstreaming-cdn.apple.com/videos/streaming/examples/"
                    "img_bipbop_adv_example_ts/master.m3u8"),
    ("04-timeout", "https://10.255.255.1/dead.m3u8"),
]


def sh(*args, timeout=40):
    return subprocess.run(
        [ADB, "-s", SERIAL] + list(args), capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=timeout,
    ).stdout


def shot(name):
    path = os.path.join(OUT, "card-%s.png" % name)
    with open(path, "wb") as f:
        f.write(subprocess.run(
            [ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
            capture_output=True, timeout=60).stdout)
    return path


def acks():
    log = sh("logcat", "-d", "-v", "brief", "-s", "CallSession:V")
    return re.findall(r"CINEMA_ACK (\S*)", log)


def main():
    invite = open_room.open_room(SERIAL) or open_room.saved_invite()
    if not invite:
        print("FAIL  开不了厅")
        return 1
    profile = os.path.join(os.environ.get("TEMP", "."), "t2-edge-shot-%d" % int(time.time()))
    viewer = subprocess.Popen(
        [EDGE, "--headless=new", "--use-fake-ui-for-media-stream",
         "--use-fake-device-for-media-stream", "--autoplay-policy=no-user-gesture-required",
         "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessSendPreflights",
         "--disable-gpu", "--no-first-run", "--window-size=1280,800",
         "--user-data-dir=" + profile, invite + "&autojoin=1"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        if not open_room.wait_for(
            lambda: "观众已加入" in sh("logcat", "-d", "-v", "brief", "-s", "CallSession:V"),
            45, 1.5,
        ):
            print("FAIL  观众没进厅")
            return 1
        for name, url in CASES:
            sh("logcat", "-c")
            if url is None:
                sh("shell", "am", "broadcast", "-a", "com.ticketfortwo.app.DEBUG_UNSCREEN",
                   "-n", "com.ticketfortwo.app/com.ticketfortwo.app.DebugReceiver")
                time.sleep(2.0)
            else:
                sh("shell", "am", "broadcast", "-a", "com.ticketfortwo.app.DEBUG_SCREEN",
                   "-n", "com.ticketfortwo.app/com.ticketfortwo.app.DebugReceiver",
                   "--es", "url", "'%s'" % url, "--es", "kind", "master",
                   "--es", "title", "'放映厅外观自检'")
                # 等回执落地再截，图上的那行字才是这条 URL 的结论
                open_room.wait_for(lambda: len(acks()) > 0, 16, 1.0)
                time.sleep(1.0)
            print("%-12s -> %s   回执：%s" % (name, shot(name), (acks() or ["-"])[-1][:60]))
    finally:
        viewer.terminate()
    return 0


if __name__ == "__main__":
    sys.exit(main())
