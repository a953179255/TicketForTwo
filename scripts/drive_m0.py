#!/usr/bin/env python3
"""驱动 M0 闸门测试：装机 → 点「开始分享」→ 过系统对话框 → 读日志。

为什么要脚本化：这段流程要反复跑（改一处就要重测），手点坐标不可复现。
按 text 内容定位再点中心，比硬编码坐标稳。

用法： python scripts/drive_m0.py [--reinstall]
"""
import argparse
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
SERIAL = "emulator-5556"
PKG = "com.ticketfortwo.app"
ACT = f"{PKG}/.MainActivity"
APK = r"G:\工作台\TicketForTwo\app\build\outputs\apk\debug\app-debug.apk"
ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT="5039", MSYS_NO_PATHCONV="1")


def sh(*args, timeout=60):
    return subprocess.run(args, capture_output=True, text=True, env=ENV, timeout=timeout)


def ui():
    sh(ADB, "-s", SERIAL, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    sh(ADB, "-s", SERIAL, "pull", "/sdcard/ui.xml", "ui_dump.xml")
    try:
        return open("ui_dump.xml", encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


def nodes(s):
    for m in re.finditer(r"<node\b[^>]*/?>", s):
        t = m.group(0)
        g = lambda k: (re.search(k + r'="([^"]*)"', t) or [None, ""])[1]
        b = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", g("bounds"))
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        yield {"text": g("text"), "rid": g("resource-id").split("/")[-1],
               "cls": g("class").split(".")[-1], "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2}


def find(s, *, text=None, rid=None, contains=False):
    for n in nodes(s):
        if text is not None:
            hit = (text in n["text"]) if contains else (n["text"] == text)
            if not hit:
                continue
        if rid is not None and rid not in n["rid"].lower():
            continue
        return n
    return None


def tap(x, y):
    sh(ADB, "-s", SERIAL, "shell", "input", "tap", str(x), str(y))


def wait_for(pred, tries=12, delay=0.8):
    for _ in range(tries):
        s = ui()
        n = pred(s)
        if n:
            return s, n
        time.sleep(delay)
    return ui(), None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--reinstall", action="store_true")
    args = ap.parse_args()
    os.chdir(os.path.dirname(os.path.abspath(__file__)) + "/..")

    if args.reinstall:
        print("== install ==")
        print(sh(ADB, "-s", SERIAL, "install", "-r", APK).stdout.strip()[-80:])
    sh(ADB, "-s", SERIAL, "shell", "am", "force-stop", PKG)
    sh(ADB, "-s", SERIAL, "logcat", "-c")
    sh(ADB, "-s", SERIAL, "shell", "am", "start", "-n", ACT)
    time.sleep(10)

    s = ui()
    start = find(s, text="开始分享")
    if not start:
        print("找不到「开始分享」按钮，界面：", [n["text"] for n in nodes(s) if n["text"]][:10])
        return 1
    print("== tap 开始分享 ==")
    tap(start["cx"], start["cy"])
    time.sleep(2)

    # 系统对话框可能依次出现：通知 / 麦克风 / 投屏。逐个放行。
    for _ in range(4):
        s = ui()
        allow = find(s, rid="permission_allow")
        if allow:
            print(f"   放行：{allow['text']!r}")
            tap(allow["cx"], allow["cy"]); time.sleep(2); continue
        if find(s, text="Share your screen with 双人票?", contains=True) or \
           find(s, text="Share one app") or find(s, text="Share entire screen"):
            break
        break

    # 投屏对话框：默认是「Share one app」，必须改成整屏
    print("== 投屏对话框 ==")
    s = ui()
    dd = find(s, text="Share one app")
    if dd:
        print("   默认落在「Share one app」→ 展开下拉")
        tap(dd["cx"], dd["cy"]); time.sleep(1.5)
        s = ui()
        ent = find(s, text="Share entire screen")
        if not ent:
            print("   下拉里找不到「Share entire screen」"); return 1
        print("   选「Share entire screen」")
        tap(ent["cx"], ent["cy"]); time.sleep(1.5)
    else:
        print("   已是整屏模式")

    s = ui()
    # 选完「整个屏幕」后按钮文案会从 Next 变成 Share screen —— 实测踩到
    nxt = (find(s, text="Share screen") or find(s, text="Next")
           or find(s, text="Start now"))
    if not nxt:
        print("   找不到 Next/Start now；界面：", [n["text"] for n in nodes(s) if n["text"]][:8])
        return 1
    print(f"   点 {nxt['text']!r} @({nxt['cx']},{nxt['cy']})")
    tap(nxt["cx"], nxt["cy"])

    print("== 等信令就绪（最多 20s）==")
    for _ in range(20):
        time.sleep(1)
        logs = sh(ADB, "-s", SERIAL, "logcat", "-d",
                  "-s", "CallSession:V", "Peer:V", "RtcEngine:V", "ScreenShare:V").stdout
        if "signal ready" in logs or "SecurityException" in logs or "FATAL" in logs:
            break
    print("== 日志 ==")
    for line in logs.splitlines():
        if re.search(r"(CallSession|Peer|RtcEngine|ScreenShare):", line):
            print("  " + line.split(":", 2)[-1].strip()[:150])
    alive = sh(ADB, "-s", SERIAL, "shell", "pidof", PKG).stdout.strip()
    print("== 进程存活:", alive or "已崩溃")
    return 0


if __name__ == "__main__":
    sys.exit(main())
