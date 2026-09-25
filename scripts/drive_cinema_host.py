#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把房主这边推到"正在放映"，**不重启 App**。

为什么要单独一个脚本：drive_cinema_probe.py 会 force-stop，那正好把已经建好的
分享会话和隧道一起杀了。端到端要测的是"厅开着、对方已经进来、现在选片"，
所以这里只点界面，不动进程。

用法：python scripts/drive_cinema_host.py [serial] [站点URL]
      不给站点就用内置的 HLS 测试流。
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
PKG = "com.ticketfortwo.app"
SERIAL = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1].startswith("emulator") else "emulator-5556"
SITE = sys.argv[2] if len(sys.argv) > 2 else (None if len(sys.argv) <= 2 else sys.argv[1])


def adb(*args, timeout=40):
    return subprocess.run(
        [ADB, "-s", SERIAL] + list(args), capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=timeout,
    ).stdout


def dump():
    if "dumped" not in adb("shell", "uiautomator", "dump", "/sdcard/u.xml"):
        return ""
    return adb("exec-out", "cat", "/sdcard/u.xml")


def nodes():
    xml = dump()
    if not xml:
        return []
    out = []
    for m in re.finditer(r'<node[^>]*>', xml):
        tag = m.group(0)
        label = " ".join(re.findall(r'(?:text|content-desc)="([^"]*)"', tag))
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            out.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def tap(needle, tries=16):
    for _ in range(tries):
        for label, x, y in nodes():
            if needle in label:
                adb("shell", "input", "tap", str(x), str(y))
                return True
        time.sleep(0.5)
    return False


def labels(*needles):
    return [l for l, _, _ in nodes() if any(n in l for n in needles)]


def main():
    print(f"设备 {SERIAL}")
    if not tap("放映厅"):
        print("FAIL  首页找不到「放映厅」入口（可能不在首页）")
        return 1
    time.sleep(1.5)
    if SITE:
        adb("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
            "--es", "android.intent.extra.TEXT", f"'{SITE}'", "-n", f"{PKG}/.MainActivity")
        time.sleep(7)
    elif not tap("HLS 测试流"):
        print("FAIL  找不到「HLS 测试流」")
        return 1
    print("等 12 秒让片源被嗅到…")
    time.sleep(12)
    for l in labels("候选", "嗅", "video")[:4]:
        print("  屏上:", l[:150])
    if not tap("开始放映"):
        print("FAIL  找不到「开始放映」（可能没嗅到候选，或已经在放映）")
        return 1
    time.sleep(2.5)
    for l in labels("递给", "放映", "收厅")[:3]:
        print("  屏上:", l[:150])
    print("OK    房主已进入放映状态")
    return 0


if __name__ == "__main__":
    sys.exit(main())
