#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""放映厅 P0 探针：在模拟器上开厅、打开一个真实播放页，把嗅探结果从 logcat 里捞出来。

为什么要有这个脚本（而不是"我手动点一下看看"）：
S 档整条路线押在"能不能从真实站点拿到观众也能播的地址"上。这句话必须变成
一个可重复、可比较的数字，否则下一轮我又会凭印象说"应该能"。

判据（脚本会逐条打印）：
  1. SNIFF  —— 请求流里认出的媒体 URL（含 kind）
  2. PAGEPROBE —— 页面亲口回答"我在播什么"（blob / 时长 / 分辨率）
  3. EME    —— 内置 WebView 到底有没有 DRM 栈（这条是拿来证伪我自己的印象的）

注意几个坑（都是这个仓库里踩过一次的）：
  * 中文日志必须显式 utf-8，否则 Windows 默认 GBK 直接抛异常，观测窗口就丢了；
  * 找控件要同时看 text 与 content-desc，Compose 的纯图标控件文字挂在 content-desc；
  * 界面切换有动画，固定 sleep 会把"还没渲染"误判成"界面坏了"，所以一律轮询。
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5556"
PKG = "com.ticketfortwo.app"
OUT = Path(r"G:\工作台\TicketForTwo\.dev")
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, timeout=30):
    cmd = [ADB, "-s", SERIAL] + list(args)
    return subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8",
        errors="replace", timeout=timeout,
    ).stdout


def ui_dump():
    raw = adb("exec-out", "uiautomator", "dump", "/sdcard/ui.xml")
    if "dumped" not in raw and "UI hierarchy" not in raw:
        return ""
    return adb("exec-out", "cat", "/sdcard/ui.xml")


def find(*needles):
    """按 text 或 content-desc 的**子串**匹配，返回中心坐标。"""
    xml = ui_dump()
    if not xml:
        return None
    try:
        root = ET.fromstring(re.sub(r"^\s*UI hierarchy.*?\n", "", xml, count=1))
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        hay = (node.get("text") or "") + "|" + (node.get("content-desc") or "")
        for n in needles:
            if n in hay:
                b = node.get("bounds") or ""
                m = re.findall(r"\d+", b)
                if len(m) == 4:
                    return (int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2
    return None


def wait_and_tap(*needles, tries=20):
    for _ in range(tries):
        pos = find(*needles)
        if pos:
            adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
            return True
        time.sleep(0.5)
    return False


def logcat(tag):
    return adb("logcat", "-d", "-v", "brief", "-s", f"{tag}:V")


def main():
    print(f"设备 {SERIAL} · 装机")
    apk = Path(r"G:\工作台\TicketForTwo\app\build\outputs\apk\debug\app-debug.apk")
    print(adb("install", "-r", str(apk), timeout=300).strip()[-60:])
    adb("logcat", "-c")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")

    if not wait_and_tap("放映厅"):
        print("FAIL  首页找不到「放映厅」入口")
        return 1
    time.sleep(1.2)
    print("OK    已进放映厅")

    url = sys.argv[2] if len(sys.argv) > 2 else None
    if url:
        # 走**真实的分享入口**（ACTION_SEND），不是在地址栏里打字：
        # 这样测的就是用户那条路径 —— 别的浏览器点「分享 → 双人票」会发生什么。
        adb("logcat", "-c")
        # 必须先 force-stop：不关干净的话上一次还停在放映厅，
        # 后面那句 find("放映厅") 会"看着通过"，其实分享意图根本没被接住。
        adb("shell", "am", "force-stop", PKG)
        time.sleep(1.0)
        adb(
            "shell", "am", "start", "-a", "android.intent.action.SEND",
            "-t", "text/plain", "--es", "android.intent.extra.TEXT", f"'{url}'",
            "-n", f"{PKG}/.MainActivity",
        )
        time.sleep(2.0)
        if not find("放映厅"):
            print("FAIL  分享意图没能打开放映厅")
            return 1
        print(f"OK    分享入口已接住：{url[:70]}")
    else:
        tap("展开嗅探")          # 测试胶囊现在在嗅探面板里
        time.sleep(0.8)
        if not wait_and_tap("HLS 测试流"):
            print("FAIL  找不到「HLS 测试流」按钮")
            return 1

    print("等 14 秒让页面把分片请求发出来…")
    time.sleep(14)

    log = logcat("Cinema")
    sniffs = re.findall(r"SNIFF kind=(\w+) url=(\S+)", log)
    probes = re.findall(r"PAGEPROBE (.*)", log)
    eme = re.findall(r"EME (.*)", log)

    print(f"\n=== 嗅到的媒体（去重后 {len(set(s[1] for s in sniffs))} 条）===")
    seen = set()
    for kind, u in sniffs:
        if u in seen:
            continue
        seen.add(u)
        print(f"  [{kind:11s}] {u[:120]}")
    print("\n=== 页面自述 ===")
    for p in probes[-4:]:
        print("  " + p[:160])
    print("\n=== EME（WebView 有没有 DRM 栈）===")
    for e in eme[-3:]:
        print("  " + e[:160])
    if not eme:
        print("  (没打出 EME 行 —— 探测可能还没跑完，或脚本没等到)")

    shot = OUT / "cinema-probe.png"
    with open(shot, "wb") as f:
        f.write(subprocess.run(
            [ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
            capture_output=True, timeout=60).stdout)
    print(f"\n截图：{shot}")

    playable = [u for k, u in sniffs if k in ("Master", "Progressive", "Audio")]
    print(
        f"\n判定：可播候选 {len(set(playable))} 条 · "
        + ("SNIFF-OK" if playable else "SNIFF-EMPTY")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
