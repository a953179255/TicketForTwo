#!/usr/bin/env python3
"""回归检查：同一个进程内「分享 → 停止 → 再分享」第二次必须真的能起来。

为什么单独要这个脚本：所有其它脚本开头都 `am force-stop`，进程一重启，
静态字段全部归零 —— **正好把这一类 bug 藏起来**。

真实翻车现场（2026-09-24）：`SignalHub.stop()` 漏了 `port = 0`，
而 `CallSession.isActive` 用 `port != 0` 当判据 ⇒ 第一次分享之后 isActive 永久为真，
第二次点「我知道了，继续」被 guard 静默吞掉，用户看到的就是"点了没反应"。
只有**不重启进程**地跑第二遍才测得出来。

用法：先跑 scripts/drive_m0.py 完成第一次分享，再跑本脚本。
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import t2device

ADB = t2device.ADB
PKG = "com.ticketfortwo.app"
ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT="5039", MSYS_NO_PATHCONV="1")
SERIAL = t2device.resolve()


def sh(*args, timeout=60):
    # 必须显式 utf-8：logcat 里有中文，Windows 默认按 GBK 解码会直接抛
    # UnicodeDecodeError，整个脚本看起来就像"找不到按钮"。
    return subprocess.run(args, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", env=ENV, timeout=timeout)


def ui():
    sh(ADB, "-s", SERIAL, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    sh(ADB, "-s", SERIAL, "pull", "/sdcard/ui.xml", "ui_dump.xml")
    try:
        return open("ui_dump.xml", encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


def node(text, contains=True):
    """按文字找节点。默认**子串**匹配：按钮实际叫「停止分享」，
    精确等于「停止」是找不到的 —— 上一版就是这么白跑一轮。"""
    for m in re.finditer(r"<node\b[^>]*/?>", ui()):
        t = m.group(0)
        tx = (re.search(r'text="([^"]*)"', t) or [None, ""])[1]
        hit = (text in tx) if contains else (tx == text)
        if not hit:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def tap(text, tries=8):
    for _ in range(tries):
        p = node(text)
        if p:
            sh(ADB, "-s", SERIAL, "shell", "input", "tap", *map(str, p))
            return True
        time.sleep(1)
    return False


def logs():
    return sh(ADB, "-s", SERIAL, "logcat", "-d", "-s", "CallSession:V", "SignalHub:V").stdout


def main():
    print(f"== 目标 {SERIAL}（不重启进程，直接测第二轮）==")
    before = logs()

    # 1) 结束第一次分享
    if not tap("停止"):
        print("找不到「停止」按钮 —— 第一次分享是不是没在邀请屏？")
        return 1
    time.sleep(2)
    print("   已点停止")

    # 2) 再走一遍完整的开始流程
    if not tap("分享屏幕") or not tap("我知道了，继续"):
        print("   第二轮没能进入授权流程")
        return 1
    time.sleep(2)

    # 权限这轮已经授过，只会剩一个投屏框
    for _ in range(6):
        s = ui()
        allow = re.search(r'text="(允许|Allow)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if allow:
            x1, y1, x2, y2 = map(int, allow.groups()[1:])
            sh(ADB, "-s", SERIAL, "shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(1.5)
        if node("Share screen") or node("Next") or node("Start now"):
            break
        if "Share one app" in s:
            break

    s = ui()
    if "Share one app" in s:
        p = node("Share one app")
        sh(ADB, "-s", SERIAL, "shell", "input", "tap", *map(str, p)); time.sleep(1.5)
        p = node("Share entire screen")
        if p:
            sh(ADB, "-s", SERIAL, "shell", "input", "tap", *map(str, p)); time.sleep(1.5)
    p = node("Share screen") or node("Start now") or node("Next")
    if not p:
        print("   没找到投屏确认按钮")
        return 1
    sh(ADB, "-s", SERIAL, "shell", "input", "tap", *map(str, p))
    print("   已确认投屏，等第二轮出链接…")

    # 3) 判定：日志里出现"忽略本次请求"就是回归；出现新的"邀请链接已就绪"就是修好了
    # 窗口给 75 秒：cloudflared 要跑完 precheck 再注册，实测第二轮从授权完成到出链接
    # 约 7 秒，但隧道冷启动慢的时候能到 30 秒以上 —— 25 秒会误报 FAIL。
    for _ in range(75):
        time.sleep(1)
        now = logs()[len(before):]
        if "已有进行中的分享" in now:
            print("\nFAIL —— 第二轮又被 isActive 挡掉了：")
            for line in now.splitlines():
                if "CallSession" in line:
                    print("   " + line[-110:])
            return 1
        if "信令就绪" in now:
            print("\nPASS —— 第二轮真的起来了，新链接已生成")
            for line in now.splitlines():
                if "信令就绪" in line or "传话员已就位" in line:
                    print("   " + line[-110:])
            return 0
    print("\nFAIL —— 25 秒内第二轮没出链接")
    return 1


if __name__ == "__main__":
    sys.exit(main())
