#!/usr/bin/env python3
"""观众退出观看后，窗口亮度必须交还系统 —— 而且要**留在 App 里**就交还。

为什么单独一个脚本、还要刻意用「停止观看」按钮而不是返回键：
`mBrightnessState` 是显示层的最终值，任何焦点变化（按 HOME、退出到桌面）都会让
DisplayPowerController 重算一次，于是"数值回来了"可能只是**离开窗口**造成的，
跟我们的还原代码无关。上一轮就差点把这份相关性写成因果 ——
返回键那一次量到 manual，但同一个命令里还按了第二下 BACK，第二下把 App 退到了桌面。

所以这里走唯一一条"焦点不变"的路径：观众屏上点「停止观看」，
会话结束、人还停在 App 的首页，窗口始终有焦点。这时候 reason 从
override(本应用) 变回 manual，才真的是还原代码生效了。

前置：t2view 正在观看（scripts/drive_app_viewer.py --keep-sharing）。
"""
import os
import re
import subprocess
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import t2device

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT="5039", MSYS_NO_PATHCONV="1")
STOP = (740, 2228)      # 观众控制岛最右边那颗方块（1080x2400 实测位置）


def adb(serial, *args, timeout=90):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True,
                          encoding="utf-8", errors="replace", env=ENV, timeout=timeout)


def brightness(serial):
    out = adb(serial, "shell", "dumpsys", "display").stdout
    val = re.search(r"mBrightnessState=([\d.]+)", out)
    reason = re.search(r"mBrightnessReason=(\S[^\n]*)", out)
    return (val.group(1) if val else "?", (reason.group(1)[:60] if reason else "?"))


def focus(serial):
    out = adb(serial, "shell", "dumpsys", "window").stdout
    # 行形如：  mCurrentFocus=Window{73d8cdf u0 com.pkg/com.pkg.MainActivity}
    # 上一版写成 Window\S*\s+(\S+/\S+)，把 "u0" 当成了"包/类"，永远匹配不上，
    # 于是这个自检把自己判成"测试无效"——量具读空不等于被测对象读空。
    m = re.search(r"mCurrentFocus=Window\{\S+\s+\S+\s+([^\s}]+)", out)
    return m.group(1) if m else "?"


def main():
    view = t2device.resolve("t2view")
    print(f"观众 {view}")
    before = brightness(view)
    print(f"① 观看中：value={before[0]}  reason={before[1]}")
    if "ticketfortwo" not in before[1]:
        print("   （没看到本应用的亮度覆盖，说明这一轮没人拖动过亮度 —— 测试无意义）")
        return 1

    # 控件可能已收起，先点一下屏幕唤出，再点「停止观看」
    adb(view, "shell", "input", "tap", "540", "1200")
    time.sleep(0.8)
    adb(view, "shell", "input", "tap", str(STOP[0]), str(STOP[1]))
    time.sleep(2.5)

    after = brightness(view)
    f = focus(view)
    print(f"② 点「停止观看」后：value={after[0]}  reason={after[1]}")
    print(f"   当前焦点窗口：{f}")
    still_in_app = "com.ticketfortwo.app" in f
    restored = "override" not in after[1]
    if still_in_app and restored:
        print("PASS  人还在 App 里（焦点没变），亮度已经交还系统")
        return 0
    if not still_in_app:
        print("FAIL(测试无效)  焦点已经不在 App 里，这次无法区分'代码生效'和'退出窗口触发重算'")
        return 1
    print("FAIL  焦点没变、亮度却还压着 —— 还原没生效")
    return 1


if __name__ == "__main__":
    sys.exit(main())
