#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把房主推到"厅已开、邀请链接可用"的状态，并把链接落到 .dev/invite.txt。

为什么单独一个脚本：开厅这件事以前散在三个脚本里各写一遍，而它有个真实的坑 ——
**从 logcat 捞邀请链接必须清缓冲后重新开厅**。不清的话捞到的是上一次会话的域名，
隧道早就随进程死了，观众永远进不来，而脚本会一脸认真地报"观众 45 秒没进厅"。
今天就是这么翻的一次车（同一个坑在这条链路上第三次出现：前两次是嗅探候选和回执）。

用法： python scripts/open_room.py [emulator-5556]
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
PKG = "com.ticketfortwo.app"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INVITE_FILE = os.path.join(ROOT, ".dev", "invite.txt")

INVITE_RE = re.compile(r"邀请链接已就绪：(https://\S+?trycloudflare\.com/\?k=\S+)")


def sh(serial, *args, timeout=40):
    return subprocess.run(
        [ADB, "-s", serial] + list(args), capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=timeout,
    ).stdout


def find_node(serial, *needles):
    """按 text / content-desc 子串找控件中心点（Compose 的图标控件文字挂在 content-desc）。"""
    raw = sh(serial, "exec-out", "uiautomator", "dump", "/sdcard/ui.xml")
    if "dumped" not in raw and "UI hierarchy" not in raw:
        return None
    xml = sh(serial, "exec-out", "cat", "/sdcard/ui.xml")
    try:
        root = ET.fromstring(re.sub(r"^\s*UI hierarchy.*?\n", "", xml, count=1))
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        hay = (node.get("text") or "") + "|" + (node.get("content-desc") or "")
        for needle in needles:
            if needle in hay:
                b = re.findall(r"\d+", node.get("bounds") or "")
                if len(b) == 4:
                    return (int(b[0]) + int(b[2])) // 2, (int(b[1]) + int(b[3])) // 2
    return None


def wait_for(predicate, seconds, step=1.0):
    end = time.time() + seconds
    while time.time() < end:
        v = predicate()
        if v:
            return v
        time.sleep(step)
    return None


def open_room(serial, restart=True):
    """返回邀请链接；拿不到返回 None。restart=False 时只等，不动进程。"""
    if restart:
        sh(serial, "shell", "am", "force-stop", PKG)
        time.sleep(1.0)
        sh(serial, "logcat", "-c")            # 清缓冲：否则捞到的是上一次会话的域名
        sh(serial, "shell", "am", "start", "-n", PKG + "/.MainActivity")
        time.sleep(3.0)
        pos = wait_for(lambda: find_node(serial, "放映厅"), 12, 0.6)
        if not pos:
            print("FAIL  首页找不到「放映厅」入口")
            return None
        sh(serial, "shell", "input", "tap", str(pos[0]), str(pos[1]))
    log = lambda: sh(serial, "logcat", "-d", "-v", "brief", "-s", "CallSession:V")
    url = wait_for(lambda: (INVITE_RE.search(log()) or [None, None])[1], 40, 1.2)
    if not url:
        print("FAIL  40 秒内没等到「邀请链接已就绪」（麦克风权限？隧道起不来？）")
        return None
    url = url.rstrip("）)，,。")
    # 探活要重试，不能只试一次：cloudflared 一打印域名我们就把链接记下了，
    # 但边缘路由/DNS 还要几秒才生效 —— 只试一次会把一个**其实能用**的厅判成死的
    # （实测 HTTP 000，20 秒后再 curl 就是 200）。
    code = "000"
    for _ in range(12):
        code = subprocess.run(
            ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", url],
            capture_output=True, text=True, timeout=30,
        ).stdout.strip()
        if code == "200":
            break
        time.sleep(2.5)
    if code != "200":
        print("FAIL  邀请链接拿得到但 30 秒内打不开（HTTP %s）—— 隧道没起来或被墙" % code)
        return None
    os.makedirs(os.path.dirname(INVITE_FILE), exist_ok=True)
    with open(INVITE_FILE, "w", encoding="utf-8") as f:
        f.write(url)
    print("OK    厅已开 · HTTP %s · %s" % (code, url))
    return url


def saved_invite():
    """上一次开厅写下的链接，还活着才算数。"""
    if not os.path.exists(INVITE_FILE):
        return None
    url = open(INVITE_FILE, encoding="utf-8").read().strip()
    if not url.startswith("http"):
        return None
    code = subprocess.run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", url],
        capture_output=True, text=True, timeout=30,
    ).stdout.strip()
    return url if code == "200" else None


def main():
    serial = sys.argv[1] if len(sys.argv) > 1 else "emulator-5556"
    return 0 if open_room(serial) else 1


if __name__ == "__main__":
    sys.exit(main())
