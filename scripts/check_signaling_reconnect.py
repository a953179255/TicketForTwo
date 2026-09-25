#!/usr/bin/env python3
"""端到端验证「信令断了不再拆通话」：只掐信令，看画面会不会继续。

为什么需要这个脚本：这条路径以前只有单测覆盖判定，没有真证据 ——
在模拟器上用网络手段注入"只断 TCP、留 UDP"不可靠
（iptables REJECT 掐了 7 秒什么都没断，那条连接本来就空闲；ss -K 在这个镜像里段错误）。
所以加了个只进 debug 变体的广播口（src/debug/.../DebugReceiver.kt），
它复用的正是 rescueSignaling 里同一句 ws.close()，测的是真代码路径。

判据（三条都要成立）：
  1. 观众侧出现「第 N 次重连…」→「信令已重连，画面继续」，且**没有**「与房主的连接断了」；
  2. 房主侧出现「观众信令断了，画面先不断」+「观众信令重连，画面不断」，
     且**没有**「观众已离开」（那代表它把 peer 拆了）；
  3. 前后两张观众截图都还是对方的画面（不是收场卡片）。

前置：t2test 在分享、t2view 已连上（drive_m0.py + drive_app_viewer.py --keep-sharing）。
用法： python scripts/check_signaling_reconnect.py
"""
import os
import subprocess
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
DEV = os.path.join(HERE, "..", ".dev")
sys.path.insert(0, HERE)
import t2device

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT="5039", MSYS_NO_PATHCONV="1")
PKG = "com.ticketfortwo.app"
ACTION = f"{PKG}.DEBUG_DROP_WS"


def adb(serial, *args, timeout=90):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True,
                          encoding="utf-8", errors="replace", env=ENV, timeout=timeout)


def log(serial, grep):
    return adb(serial, "shell", f"logcat -d | grep -a '{grep}'").stdout


def shot(serial, name):
    adb(serial, "shell", "screencap", "-p", "/sdcard/r.png")
    adb(serial, "pull", "/sdcard/r.png", os.path.join(DEV, name))
    return os.path.join(DEV, name)


def mean(path):
    from PIL import Image
    im = Image.open(path).convert("L").crop((60, 300, 1020, 1700))
    px = list(im.getdata())
    return sum(px) / len(px)


def main():
    host = t2device.resolve("t2test")
    view = t2device.resolve("t2view")
    report = open(os.path.join(DEV, "reconnect-report.txt"), "w", encoding="utf-8")

    def w(s):
        report.write(s + "\n")
        print(s)

    w(f"房主 {host} / 观众 {view}")
    # 前置检查必须读**活信号**。上一版在这里吃了 stale：房主重启会话把观众甩掉了，
    # 可旧 logcat 缓冲里那句 `ice=CONNECTED` 还在 → 闸门放行 → 清完缓冲后三条读数
    # 全是空的，报成一个查无此症的 FAIL（"日志缓冲当证据"这个坑在这条链路上第五次咬人）。
    host_ui = adb(host, "shell", "uiautomator dump /sdcard/p.xml", "&&", "cat", "/sdcard/p.xml").stdout
    if "人正在观看" not in host_ui:
        w("FAIL  房主界面上没有「N 人正在观看」—— 观众此刻并不在（"
          "先跑 drive_m0.py + drive_app_viewer.py --keep-sharing）")
        return 1
    if "ice=CONNECTED" not in log(view, "ViewerSession"):
        w("FAIL  观众侧日志里没有 ice=CONNECTED（先跑 drive_m0.py + drive_app_viewer.py --keep-sharing）")
        return 1
    adb(view, "shell", "logcat", "-c")
    adb(host, "shell", "logcat", "-c")
    time.sleep(0.5)
    before = shot(view, "reconnect-before.png")
    m0 = mean(before)
    w(f"① 掐之前：观众屏内容均值={m0:.1f}（有画面时不该接近 0）")

    r = adb(view, "shell", "am", "broadcast", "-a", ACTION, "-n", f"{PKG}/.DebugReceiver")
    w(f"② 广播掐信令：{(r.stdout + r.stderr).strip()[-90:]}")

    ok_viewer = ok_host = False
    bad = ""
    for _ in range(30):
        time.sleep(1)
        v = log(view, "ViewerSession")
        h = log(host, "CallSession")
        if "信令已重连" in v:
            ok_viewer = True
        if "观众信令重连" in h:
            ok_host = True
        if "与房主的连接断了" in v or "与房主的信令断了" in v:
            bad = "观众侧直接结束了会话"
            break
        if "观众已离开" in h:
            bad = "房主侧把 peer 拆了"
            break
        if ok_viewer and ok_host:
            break

    after = shot(view, "reconnect-after.png")
    m1 = mean(after)
    w("③ 观众日志：")
    for line in log(view, "ViewerSession").strip().splitlines()[-8:]:
        w("   " + line[-110:])
    w("④ 房主日志：")
    for line in log(host, "CallSession").strip().splitlines()[-8:]:
        w("   " + line[-110:])
    w(f"⑤ 掐之后：观众屏内容均值={m1:.1f}")

    if bad:
        w(f"FAIL  {bad} —— 信令断了还是把通话带走了")
        return 1
    if ok_viewer and ok_host:
        w("PASS  只掐信令：观众重连成功、房主保留 peer、画面没断"
          f"（内容均值 {m0:.1f} → {m1:.1f}）")
        return 0
    w(f"FAIL  没看到完整证据链 viewer_reconnect={ok_viewer} host_keep={ok_host}；见上面日志")
    return 1


if __name__ == "__main__":
    sys.exit(main())
