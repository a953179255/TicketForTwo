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
    """系统投屏弹窗：这张镜像的文案随 locale 中英不定（实测切过 zh-Hans-CN 后
    全中文），按键驱动到"共享真的起来"。Android16 这版确认页只有单应用分支：
    「下一步」→ 选应用 → 点完直接开共享**并把该应用顶到前台** —— 必须把自己
    App 拉回来，后面「邀请页就绪」的断言才成立（卡在弹窗上时按返回会把整个
    流程退到桌面，之后全链路雪崩 —— 2026-09-29 实测）。"""
    order = ["我知道了，继续", "允许", "Allow", "Next", "下一步",
             "Share entire screen", "共享整个屏幕", "整个屏幕",
             "Start now", "立即开始", "Share screen", "共享屏幕", "开始共享",
             "Share one app", "共享一个应用", "Choose app"]
    apps = ["时钟", "日历", "相机", "Chrome"]
    last_hit, same = None, 0
    for _ in range(30):
        ts = texts()
        if any("选择要分享的应用" in t for t in ts):
            got = next((a for a in apps if a in ts), None)
            if got:
                A.tap_text(S, got); time.sleep(2.0)
            A.sh("-s", S, "shell", "am", "start", "-n", PKG + "/.MainActivity")
            time.sleep(3.0)
            if any(t in ("开始放映", "打开") for t in texts()):
                return "ok"
            continue
        hit = next((k for k in order if k in ts), None)
        if hit:
            if hit == last_hit:
                same += 1
                if same >= 3:          # 推进不了的键（单选行/置灰）跳过
                    order = [k for k in order if k != hit]
                    hit, same, last_hit = None, 0
            else:
                same, last_hit = 0, hit
            if hit:
                A.tap_text(S, hit); time.sleep(1.6)
                if hit in ("Share screen", "Start now", "立即开始"):
                    time.sleep(2.0)
                    return "ok"
                continue
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
# 等设置页真实存在的节点：'分享画质' 是**首页**那行摘要（Screens.kt 里 home 的
# lastSummary 行），设置页上根本没有它 —— 这就是 REVIEW 里记的"脚本等待目标写错"
check("进分享设置", tap("分享设置") and wait_has("码率上限", 8))
before = logcat()
# 必须点一个**不同于当前**的档才会有热改日志（默认值会被上一轮留在1080p）；
# 码率那排是自定义输入框、没有"8.0 Mbps"按钮（脚本旧断言的等待目标又错了一处）。
# 档位默认值会被上一轮带走，"点一个"可能点在已选中的档上等于没换 —— 两档都点
tap("720p"); time.sleep(1.2); tap("1080p")
time.sleep(2)
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
