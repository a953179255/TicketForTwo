#!/usr/bin/env python3
"""驱动 M0 闸门测试：装机 → 点「开始分享」→ 过系统对话框 → 提取隧道邀请（?k=）。

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
# 不写死端口：控制台号是"开机时第一个空位"，不是身份。见 t2device.py 的说明。
SERIAL = None
PKG = "com.ticketfortwo.app"
ACT = f"{PKG}/.MainActivity"
APK = r"G:\工作台\TicketForTwo\app\build\outputs\apk\debug\app-debug.apk"
ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT="5039", MSYS_NO_PATHCONV="1")


def sh(*args, timeout=60):
    # logcat 输出是 UTF-8，Windows 默认 GBK 解码会抛 UnicodeDecodeError
    # （中文日志里的字节序列不都是合法 GBK），reader 线程一崩 stdout 就是 None，
    # 下面 `"signal ready" in logs` 直接 TypeError。按 UTF-8 读、非法字节替换。
    return subprocess.run(args, capture_output=True, encoding="utf-8", errors="replace",
                          env=ENV, timeout=timeout)


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


SHOTS = os.path.join("app", "build", "t2-shots")


def shot(name):
    """存一张当前屏到 build/t2-shots/，供肉眼验收。

    为什么必须有：结构检查（uiautomator 有没有这个节点）全绿，仍然会漏掉
    重叠、变形、玻璃不折射这类"只有看图才发现"的问题 —— 本项目已经翻过一次车。
    放 build/ 下：跟着 clean 一起走，不会污染仓库。
    """
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, f"{name}.png")
    # 必须绕开 sh()：它带 text=True，PNG 是二进制，按文本解码会直接毁掉字节。
    r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                       capture_output=True, env=ENV, timeout=30)
    with open(path, "wb") as f:
        f.write(r.stdout)
    print(f"   [shot] {path} ({len(r.stdout)} bytes)")
    return path


def wait_for(pred, tries=12, delay=0.8):
    for _ in range(tries):
        s = ui()
        n = pred(s)
        if n:
            return s, n
        time.sleep(delay)
    return ui(), None


def main():
    global SERIAL
    ap = argparse.ArgumentParser()
    ap.add_argument("--reinstall", action="store_true")
    args = ap.parse_args()
    os.chdir(os.path.dirname(os.path.abspath(__file__)) + "/..")
    sys.path.insert(0, os.path.join(os.getcwd(), "scripts"))
    import t2device
    SERIAL = t2device.resolve()
    print(f"== 目标设备：{SERIAL}（按 AVD 名 t2test 解析）==")

    if args.reinstall:
        print("== install ==")
        print(sh(ADB, "-s", SERIAL, "install", "-r", APK).stdout.strip()[-80:])
    sh(ADB, "-s", SERIAL, "shell", "am", "force-stop", PKG)
    sh(ADB, "-s", SERIAL, "logcat", "-c")
    sh(ADB, "-s", SERIAL, "shell", "am", "start", "-n", ACT)

    # 冷启动要等 Compose 首帧 + backdrop 着色器编译，实测 7–15 秒波动。
    # 之前是固定 sleep 10，撞上慢的那次就会"找不到开始分享"而误判成界面坏了 ——
    # 轮询到节点出现为止，比猜一个等待秒数稳。
    #
    # 首页已收成两颗圆（11b9026）：入口是绿色「分享画面」，进去后在选择页
    # 点「分享我的屏幕」才进授权指引。旧入口「开始分享/分享屏幕」已不存在，
    # 认旧名只会让脚本静默停在首页（实测报"找不到开始分享"）。
    s, orb = wait_for(lambda x: find(x, text="分享画面"), tries=20, delay=1.5)
    shot("01-home")
    if not orb:
        print("找不到「分享画面」入口，界面：", [n["text"] for n in nodes(s) if n["text"]][:10])
        return 1
    print("== tap 分享画面（首页绿圆）==")
    # 同样要**能重试**：节点出现到窗口接指针之间有一小段空窗，第一下常被吞
    # （01-home 截图 30KB 全黑就是还在首帧）。判据 = 选择页的「分享我的屏幕」
    # 出没出现；没出现且人还在首页（「进入观看」还在）就重新定位、再点。
    pick = None
    for attempt in range(4):
        s = ui()
        pick = find(s, text="分享我的屏幕")
        if pick:
            break
        on_home = find(s, text="进入观看") is not None and find(s, text="分享画面") is not None
        if not on_home:
            time.sleep(1.5)  # 状态未知（转场中），等一拍再看
            continue
        orb_now = find(s, text="分享画面")
        if orb_now:
            tap(orb_now["cx"], orb_now["cy"])
            print(f"   第 {attempt + 1} 下" if attempt else "   点击")
        time.sleep(2)
    shot("01b-sharekind")
    if not pick:
        print("选择页没出现「分享我的屏幕」，界面：", [n["text"] for n in nodes(s) if n["text"]][:10])
        return 1
    print("== tap 分享我的屏幕 ==")
    # 同样允许重试：转场中间的点击会被吞（老注释里那一轮白查的教训照旧适用）
    consent = None
    for attempt in range(4):
        s = ui()
        cur = find(s, text="分享我的屏幕")
        if cur is None:
            break  # 已经离开选择页
        tap(cur["cx"], cur["cy"])
        time.sleep(2)
        # 玻璃化之后多了一屏"授权指引"（ConsentGuideScreen）：系统会连着问两件事，
        # 不先讲清楚"要选整个屏幕"，用户十次有三次会选成单应用，然后以为 App 坏了。
        # 按钮文案认两种：完整句「我知道了，继续」和简写「继续」。
        s, consent = wait_for(
            lambda x: find(x, text="我知道了，继续") or find(x, text="继续"),
            tries=5,
        )
        if consent:
            if attempt:
                print(f"   第 {attempt + 1} 次点击才生效（前几次被转场吞了）")
            break
        print("   这点没生效，界面还在选择页 → 等一拍再重新定位、再点")
        time.sleep(3)

    if consent:
        shot("02-consent")
        print("== 授权指引 → tap 我知道了，继续 ==")
        tap(consent["cx"], consent["cy"])
        time.sleep(2)
    else:
        print("   没出现授权指引（可能直接进系统对话框了）")

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
    shot("03-projection")
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
    time.sleep(1.2)
    shot("04-preparing")

    print("== 等邀请链接出现（隧道注册最长约 30s，上限 45s）==")
    # 新流程（a0cc901）：邀请是隧道 URL（https://…trycloudflare.com/?k=…），
    # 要等 SignalHub + cloudflared 就绪（30s 启动闸门）才出现在等待屏上。
    # 旧的"等 signal ready 日志 20s"判据已失效 —— 以**邀请节点可见**为唯一完成条件。
    urls = []
    for _ in range(45):
        time.sleep(1)
        s = ui()
        urls = [n["text"] for n in nodes(s)
                if "?k=" in n["text"] and n["text"].startswith("http")]
        if urls:
            break
    logs = (sh(ADB, "-s", SERIAL, "logcat", "-d",
               "-s", "CallSession:V", "Peer:V", "RtcEngine:V", "ScreenShare:V",
               "SignalHub:V", "TunnelManager:V").stdout) or ""
    print("== 日志 ==")
    for line in logs.splitlines():
        if re.search(r"(CallSession|Peer|RtcEngine|ScreenShare|SignalHub|TunnelManager):", line):
            print("  " + line.split(":", 2)[-1].strip()[:150])
    alive = sh(ADB, "-s", SERIAL, "shell", "pidof", PKG).stdout.strip()
    print("== 进程存活:", alive or "已崩溃")

    # 把隧道邀请落到 .dev/invite.txt，供 run_viewer_edge 原样打开。
    # 链接只带 ?k= 凭证不含 SDP；一次性产物仍放 .dev/（不进站点目录）。
    shot("05-invite")
    if urls:
        os.makedirs(os.path.join(os.getcwd(), ".dev"), exist_ok=True)
        path = os.path.join(os.getcwd(), ".dev", "invite.txt")
        open(path, "w", encoding="utf-8").write(urls[0])
        print("== invite 已写入 .dev/invite.txt (%d 字符)" % len(urls[0]))
    else:
        print("== 界面上没找到隧道邀请（?k=）")
        print("   界面文本：", [n["text"][:40] for n in nodes(s) if n["text"]][:12])
    return 0


if __name__ == "__main__":
    sys.exit(main())
