#!/usr/bin/env python3
"""按 **AVD 名**解析 adb serial，不认端口。

为什么不能写死 emulator-5556：控制台端口是"开机时第一个空位"，不是身份。
今天实测：t2test 冷启动时 edgeai 恰好没在跑，于是它拿到了 5554/5555；
如果按老写法连 5556，要么连不上，要么更糟 —— 等 edgeai 回来重新占位后，
我的安装、清 logcat、改权限、点 UI 全部打到**另一个项目的模拟器**上。
那正是当初专门建第二台模拟器要避免的互相干扰，而且是静默发生。

所以每次执行都问一遍 `adb emu avd name`，认名字不认端口。
"""
import os
import subprocess
import sys

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
AVD = os.environ.get("T2_AVD", "t2test")   # 要看第二台（t2view）时：T2_AVD=t2view


def _run(*args, timeout=25):
    env = dict(os.environ, ANDROID_ADB_SERVER_PORT="5039", MSYS_NO_PATHCONV="1")
    return subprocess.run(args, capture_output=True, text=True, env=env, timeout=timeout)


def candidates():
    out = _run(ADB, "devices").stdout
    return [ln.split()[0] for ln in out.splitlines()[1:] if ln.strip().endswith("device")]


def avd_of(serial):
    # `emu avd name` 回两行：AVD 名 + "OK"
    out = _run(ADB, "-s", serial, "emu", "avd", "name").stdout
    return out.splitlines()[0].strip() if out.strip() else ""


def resolve(want=AVD):
    """返回跑着指定 AVD 的 serial；找不到就抛错，**绝不退化成"随便一台"**。"""
    for s in candidates():
        if not s.startswith("emulator-"):
            continue
        if avd_of(s) == want:
            return s
    raise SystemExit(
        f"没找到正在运行的 {want} 模拟器（当前设备：{candidates() or '无'}）。\n"
        f"先跑 scripts/run_t2test_emulator.sh 启动它。"
    )


if __name__ == "__main__":
    print(resolve(sys.argv[1] if len(sys.argv) > 1 else AVD))
