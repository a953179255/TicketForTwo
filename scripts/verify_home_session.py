#!/usr/bin/env python3
# 方案二 + 回首页 + 设置热改 的端到端验收。
# 前置：单机（t2test 系模拟器），无需第二台设备。用法：
#   T2_SERIAL=emulator-5554 ADB=<adb路径> python scripts/verify_home_session.py
# 覆盖：① 空闲首页双圆 ② 投屏全链 ③ 分享中返回→首页圆钮变身（正在分享/停止分享/
#   进入观看隐藏）④ 点圆往返 + 设置热改（logcat 判据）⑤ 结束分享复原
#   ⑥ 厅先开→放映厅已开/关闭放映厅 ⑦ 厅内「分享我的屏幕」主路径。
import os, sys, time, subprocess, re
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts"))
import audit_app_layout as A

S = os.environ.get("T2_SERIAL", "emulator-5554")
PKG = "com.ticketfortwo.app"
adb = lambda *a: A.sh("-s", S, *a)   # audit_app_layout 的 sh 需要 -s，位置传序列号会被当成 adb 子命令
nodes = lambda: A.nodes(A.dump(S))
texts = lambda: [n["text"] for n in nodes() if n.get("text")]
def has(t): return t in texts()
def tap(t, tries=6): return A.tap_text(S, t, tries)
def wait_has(t, sec=10):
    end = time.time() + sec
    while time.time() < end:
        if has(t): return True
        time.sleep(0.8)
    print("   等不到节点：", texts()[:14]); return False
def key_back():
    adb("shell", "input", "keyevent", "4"); time.sleep(1.6)
def logcat():
    return adb("logcat", "-d", "-v", "brief", "-s", "CallSession:V", "ScreenShare:V").stdout
def dialog():
    for _ in range(10):
        ts = texts()
        if any("Share one app" in t for t in ts):
            A.tap_text(S, "Share one app"); time.sleep(1.5)
            if not A.tap_text(S, "Share entire screen"): return "no-entire"
            time.sleep(1.5); A.tap_text(S, "Share screen"); time.sleep(3.5); return "ok"
        if any(t in ("Allow", "允许") for t in ts):
            tap("Allow") or tap("允许"); time.sleep(1.6); continue
        time.sleep(1.0)
    return "no-dialog"
fails = []
def check(name, ok):
    print(("PASS  " if ok else "FAIL  ") + name)
    if not ok: fails.append(name)

os.environ.setdefault("ADB", A.ADB)
adb("logcat", "-c")
adb("shell", "am", "force-stop", PKG); time.sleep(2.5)
adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
check("空闲首页有两颗圆", wait_has("分享画面", 20) and wait_has("进入观看", 5))

print("== ① 全新首页 ==")

print("== ② 开投屏 ==")
check("点分享画面进选择页", tap("分享画面") and wait_has("分享我的屏幕"))
check("点分享我的屏幕进指引", tap("分享我的屏幕") and wait_has("我知道了，继续"))
tap("我知道了，继续"); time.sleep(1.5)
print("   投屏弹窗：", dialog())
check("邀请页就绪", wait_has("把这条发给朋友", 25))

print("== ③ 分享中按返回 → 首页变身 ==")
key_back()
check("首页出现 正在分享", wait_has("正在分享", 8))
check("圆下小字 停止分享", has("停止分享"))
check("进入观看已隐藏", not has("进入观看"))
check("分享设置仍在", has("分享设置"))

print("== ④ 点圆回会话屏，再返回，再进设置热改 ==")
tap("正在分享"); time.sleep(2.5)
check("回到会话屏", has("把这条发给朋友"))
key_back(); time.sleep(1)
check("再次回首页", wait_has("正在分享", 8))
check("进分享设置", tap("分享设置") and wait_has("分享画质", 8))
before = logcat()
tap("1080p"); time.sleep(1.5)
tap("8.0 Mbps"); time.sleep(2)
after = logcat()
check("logcat 有热改记录", ("热改采集" in after and "热改采集" not in before) or ("码率上限" in after and "码率上限" not in before))
adb("shell", "input", "keyevent", "4"); time.sleep(1.5)

print("== ⑤ 点圆回会话屏并结束分享 ==")
check("回首页仍在分享", wait_has("正在分享", 8))
tap("正在分享"); time.sleep(2.5)
check("回到邀请屏", has("把这条发给朋友"))
tap("停止分享"); time.sleep(2.5)
check("结束后首页恢复正常", wait_has("进入观看", 8) and not has("正在分享"))

print("== ⑥ 厅先开 → 首页放映厅已开 ==")
check("点分享画面", tap("分享画面") and wait_has("同步放映", 8))
tap("同步放映"); time.sleep(2)
tap("Allow") or tap("允许")   # 麦克风权限兜底（已授权时无弹窗，点了也不伤）
check("点同步放映卡进厅", wait_has("开始放映", 20))
check("厅内有 分享我的屏幕 入口", has("分享我的屏幕"))
key_back(); time.sleep(1.5)
check("首页出现 放映厅已开", wait_has("放映厅已开", 8))
check("圆下小字 关闭放映厅", has("关闭放映厅"))
check("进入观看仍隐藏", not has("进入观看"))
tap("放映厅已开"); time.sleep(2.5)
check("点圆回放映厅", has("开始放映"))

print("== ⑦ 厅内切投屏（v2.1 主路径）==")
check("点 分享我的屏幕", tap("分享我的屏幕") and wait_has("我知道了，继续", 8))
tap("我知道了，继续"); time.sleep(1.5)
print("   投屏弹窗：", dialog())
check("仍在放映厅", wait_has("开始放映", 20))
check("投屏后入口隐藏", not has("分享我的屏幕"))
check("logcat 画面已接上", "画面已接上" in logcat() or "画面已备好" in logcat())

print()
print("全部通过 ✔" if not fails else "失败项：" + "；".join(fails))
sys.exit(1 if fails else 0)
