#!/usr/bin/env python3
"""真机这一轮：一次跑完 8 项跨网检查，并把两端网络类型记进报告。

为什么要有这个脚本：前面几轮所有"跨网到底行不行"的结论都卡在**没有第二台设备**，
而手机一旦插上就能当那台设备。手工点一遍不可复现，改一处就要重来，所以固化成脚本。

跑法（手机插 USB、允许调试后）：
    python scripts/run_phone_round.py                 # 观众=手机，房主=t2test
    python scripts/run_phone_round.py --flip          # 反过来：手机当房主
    python scripts/run_phone_round.py --serial XXX    # 指定手机 serial

⚠ 这个脚本**不会**自动卸载/安装 APK —— 真机上的包是 release 签名，
  调试包签名不同，覆盖安装会失败，必须先卸载，而卸载会清掉 App 数据。
  那一步要人明确同意，所以留给 install_phone.ps1 由用户/agent 手动触发。

⚠ 硬规矩：每轮实测必须同时记两端网络类型（WiFi/蜂窝/是否同网段/有无 VPN）。
  以前吃过亏 —— 同机测试掩盖了候选投递类 bug（PLAN §16），
  所以这里把网络类型当成判据的一部分，没记到就等于这轮不作数。
"""
import argparse
import datetime
import io
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import t2device
import check_restart_roundtrip as rt   # 复用 ui()/node()/tap()/logs()，认 rt.SERIAL

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT_DIR = os.path.join(ROOT, ".dev", "phone-round")
PKG = "com.ticketfortwo.app"
ACT = f"{PKG}/.MainActivity"
HOST_AVD = "t2test"


def adb(serial, *args, timeout=90):
    return rt.sh(rt.ADB, "-s", serial, *args, timeout=timeout)


def nettype(serial):
    """这台设备此刻走的是什么网。识别不出来就返回 unknown，绝不猜。

    上一版靠解析 dumpsys connectivity 里 `Active default network` 再回找 `T: WIFI`，
    在真机上正则没匹配上、直接返回 unknown —— 而"两端网络类型"是这一轮的硬前置，
    记不到就等于这轮不作数，所以宁可换两个各自读得懂的笨探针。
    """
    wifi = adb(serial, "shell", "cmd", "wifi", "status").stdout
    if re.search(r"Connected to", wifi, re.I):
        return "WIFI"
    cell = adb(serial, "shell", "dumpsys", "telephony.registry").stdout
    if re.search(r"mDataConnectionState=\s*(2|CONNECTED)", cell) or        re.search(r"dataConnectionState=DATA_CONNECTED", cell):
        types = adb(serial, "shell", "getprop", "gsm.network.type").stdout.strip()
        return f"MOBILE({types})" if types else "MOBILE"
    if "not connected" not in wifi.lower() and re.search(r"Connected to", wifi):
        return "WIFI"
    return "unknown"


def wifi_ssid(serial):
    out = adb(serial, "shell", "dumpsys", "wifi").stdout
    m = re.search(r"SSID:\s*([^,\n]+)", out)
    return (m.group(1).strip().strip('"') or "<none>") if m else "-"


def phone_network_type(serial):
    out = adb(serial, "shell", "dumpsys", "telephony").stdout
    m = re.search(r"dataNetworkType:\s*([A-Z0-9+]+)", out)
    return m.group(1) if m else "-"


def pc_network():
    """房主那侧其实是这台 PC。网络类型从 netsh 读。"""
    try:
        out = subprocess.run(["netsh", "wlan", "show", "interfaces"],
                             capture_output=True, text=True, encoding="utf-8",
                             errors="replace", timeout=20).stdout
        state = re.search(r"State\s*:\s*(\w+)", out)
        ssid = re.search(r"SSID\s*:\s*(.+)", out)
        if state and state.group(1).lower() == "connected":
            return f"WIFI({ssid.group(1).strip() if ssid else '?'})"
    except Exception:
        pass
    return "unknown(可能是有线)"


def screencap(serial, dst):
    adb(serial, "shell", "screencap", "-p", "/sdcard/round.png")
    adb(serial, "pull", "/sdcard/round.png", dst)
    return os.path.exists(dst) and os.path.getsize(dst) > 0


def mean_brightness(path, box=None):
    from PIL import Image
    im = Image.open(path).convert("L")
    if box:
        im = im.crop(box)
    im = im.resize((min(160, im.width), min(320, im.height)))
    px = list(im.getdata())
    return round(sum(px) / len(px), 1)


def clear_lingering_dialogs(serial):
    """开局先确认没有系统弹窗压在 App 上。

    上一轮就是栽在这：Flyme 的权限弹窗没被点掉，一直留在屏幕上，
    于是这一轮第一步"把 App 带到前台"就失败，而第 6 步 dump 到的也是弹窗文字 ——
    两项判定同时变假，看着像产品坏了，其实是上一轮留的现场没清。
    """
    rt.SERIAL = serial
    focus = adb(serial, "shell", "dumpsys", "window").stdout
    line = next((l for l in focus.splitlines() if "mCurrentFocus" in l), "")
    if "permissioncontroller" not in line and "systemui" not in line:
        return
    print("   检测到系统弹窗压在屏幕上，先处理掉")
    for pat in ("仅在使用时允许", "仅本次使用时允许", "While using the app", "允许", "Allow"):
        p = rt.node(pat)
        if p:
            rt.sh(rt.ADB, "-s", serial, "shell", "input", "tap", *map(str, p))
            time.sleep(2)
            return
    rt.sh(rt.ADB, "-s", serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)


def launch_app(serial):
    adb(serial, "shell", "am", "start", "-n", ACT)
    for _ in range(30):
        time.sleep(1)
        out = adb(serial, "shell", "dumpsys", "window").stdout
        line = next((l for l in out.splitlines() if "mCurrentFocus" in l), "")
        if PKG in line:
            return True
    return False


def join_as_viewer(serial, url):
    """在观众机上把链接喂进去。返回 True=已提交。"""
    rt.SERIAL = serial
    clear_lingering_dialogs(serial)
    # 必须冷启：上一轮结束时 App 停在"房主结束了分享"那一屏，
    # 只 am start 把它带到前台的话，屏幕上根本没有「进入观看」可点。
    adb(serial, "shell", "am", "force-stop", PKG)
    time.sleep(1)
    if not launch_app(serial):
        return False, "App 没到前台"
    p = rt.node("进入观看")
    if not p:
        return False, "找不到「进入观看」"
    rt.sh(rt.ADB, "-s", serial, "shell", "input", "tap", *map(str, p)); time.sleep(2)
    field = rt.node("长按粘贴") or rt.node("邀请链接")
    if not field:
        return False, "找不到输入框"
    rt.sh(rt.ADB, "-s", serial, "shell", "input", "tap", *map(str, field)); time.sleep(1)
    adb(serial, "shell", "input", "text", url.replace(" ", ""))
    time.sleep(1)
    # 不收键盘，点击就会落在键盘上：以前因此把按键当成了链接的一部分，
    # 尾巴多出两个字母，整轮判定全是假的。
    adb(serial, "shell", "input", "keyevent", "KEYCODE_ESCAPE"); time.sleep(1)
    got = ""
    for m in re.finditer(r'text="([^"]*)"', rt.ui()):
        if m.group(1).startswith("http"):
            got = m.group(1); break
    if got != url:
        return False, f"链接没输对（实际尾巴 …{got[-12:]}）"
    go = rt.node("在 App 内观看")
    if not go:
        return False, "提交按钮没出现"
    rt.sh(rt.ADB, "-s", serial, "shell", "input", "tap", *map(str, go))
    return True, "已提交"


def wait_for(serial, tags, needle, limit=45):
    for _ in range(limit):
        time.sleep(1)
        out = adb(serial, "logcat", "-d", "-s", *tags).stdout
        if needle in out:
            return out
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", help="手机 serial；不填则自动挑那台不是 emulator- 的设备")
    ap.add_argument("--flip", action="store_true", help="手机当房主、模拟器当观众")
    a = ap.parse_args()

    # 挑手机：devices 列表里所有不是 emulator- 开头的
    devs = [l.split()[0] for l in rt.sh(rt.ADB, "devices").stdout.splitlines()[1:]
            if l.strip().endswith("device")]
    phones = [d for d in devs if not d.startswith("emulator-")]
    phone = a.serial or (phones[0] if len(phones) == 1 else None)
    if not phone:
        print("没找到唯一的手机设备（可能是没插 USB、没允许调试、或插了不止一台）")
        print(f"  当前设备：{devs}")
        return 1
    host = t2device.resolve(HOST_AVD)

    stamp = datetime.datetime.now().strftime("%m%d-%H%M%S")
    out_dir = os.path.join(OUT_DIR, stamp)
    os.makedirs(out_dir, exist_ok=True)

    # ── 0. 两端网络类型：没记到就不算这一轮 ────────────────────────────────
    env = {
        "viewer_phone": {"serial": phone, "net": nettype(phone),
                        "ssid": wifi_ssid(phone), "cell": phone_network_type(phone)},
        "host_emulator": {"serial": host, "via": "PC", "net": pc_network()},
    }
    print("== 两端网络 ==")
    print(f"   观众（手机 {phone}）：{env['viewer_phone']['net']} "
          f"WiFi={env['viewer_phone']['ssid']} 蜂窝={env['viewer_phone']['cell']}")
    print(f"   房主（{host} 走这台 PC）：{env['host_emulator']['net']}")
    io.open(os.path.join(out_dir, "env.json"), "w", encoding="utf-8").write(
        json.dumps(env, ensure_ascii=False, indent=2))

    results = {}
    adb(phone, "logcat", "-c")
    adb(host, "logcat", "-c")

    # ── 1. 房主分享 ────────────────────────────────────────────────────────
    print("\n== 1) 房主开始分享 ==")
    subprocess.run([sys.executable, os.path.join(HERE, "drive_m0.py")],
                   capture_output=True, timeout=300)
    url = io.open(os.path.join(ROOT, ".dev", "invite.txt"), encoding="utf-8").read().strip()
    if "?k=" not in url:
        print("   拿不到邀请链接")
        return 1
    print(f"   {url}")

    # ── 2. 手机入房 ────────────────────────────────────────────────────────
    print("\n== 2) 手机 App 内观看 ==")
    ok, msg = join_as_viewer(phone, url)
    print(f"   {msg}")
    if not ok:
        return 1
    log = wait_for(phone, ["ViewerSession:V", "WsClient:V", "Peer:V"], "ice=CONNECTED", 60)
    vlog = adb(phone, "logcat", "-d", "-s", "ViewerSession:V", "WsClient:V", "Peer:V").stdout
    results["跨网连上"] = "ice=CONNECTED" in vlog
    results["候选投递无异常"] = "发送失败" not in vlog and "发给观众失败" not in vlog
    results["收到视频轨"] = "画面已到" in vlog or "remote video track" in vlog
    for k, v in results.items():
        print(f"   {'PASS' if v else 'FAIL'}  {k}")

    if not results["跨网连上"]:
        # 连不上时最有价值的东西是判定与候选分布，原样存下来给人看
        io.open(os.path.join(out_dir, "viewer.log"), "w", encoding="utf-8").write(vlog)
        io.open(os.path.join(out_dir, "host.log"), "w", encoding="utf-8").write(
            adb(host, "logcat", "-d", "-s", "CallSession:V", "SignalHub:V", "Peer:V").stdout)
        print(f"   连不上，日志已存 {out_dir}")
        return 1

    # ── 3. 同帧亮度对比（PLAN §17 那笔）────────────────────────────────────
    print("\n== 3) 同一时刻两边截图，比画面是否被压暗 ==")
    hp = os.path.join(out_dir, "host.png"); vp = os.path.join(out_dir, "viewer.png")
    screencap(host, hp); screencap(phone, vp)
    hb = mean_brightness(hp); vb = mean_brightness(vp)
    print(f"   房主屏均值 {hb} / 观众收到的画面均值 {vb}（差 {round(vb - hb, 1)}）")
    print("   ⚠ 两边内容不同（房主是自己的界面、观众是收到的画面），"
          "所以这只是线索不是定论；定论要看观众画面里同一块内容的相对亮度")

    # ── 4. 观众开麦 ────────────────────────────────────────────────────────
    print("\n== 4) 观众开麦，房主能不能收到 ==")
    rt.SERIAL = phone
    mic = rt.node("取消静音")
    if mic:
        rt.sh(rt.ADB, "-s", phone, "shell", "input", "tap", *map(str, mic))
        time.sleep(3)
        # 系统权限弹窗要轮询着找：它出现得比 tap 晚，而且各家 ROM 文案不同
        # （原生是 "While using the app"，Flyme 是中文）。上一版只认英文、
        # 且只找一次，结果弹窗压在 App 上没关掉 —— 连带让后面几步 dump 到的
        # 都是弹窗而不是我们的界面，两项判定同时变假。
        allow = None
        for _ in range(10):
            for pat in ("While using the app", "仅在使用中允许", "使用时允许",
                        "始终允许", "允许", "Allow"):
                allow = rt.node(pat)
                if allow:
                    break
            if allow:
                break
            time.sleep(1)
        if allow:
            rt.sh(rt.ADB, "-s", phone, "shell", "input", "tap", *map(str, allow))
            print(f"   已点权限弹窗按钮")
            time.sleep(2)
            # 有的 ROM 会连着问第二次（通知、悬浮窗），再扫一轮兜底
            again = rt.node("允许") or rt.node("Allow")
            if again:
                rt.sh(rt.ADB, "-s", phone, "shell", "input", "tap", *map(str, again))
        else:
            print("   ⚠ 没找到权限弹窗按钮，这一步的判定不可信")
        got = wait_for(host, ["CallSession:V"], "收到对方音频轨", 20)
        results["观众开麦房主收到"] = got is not None
        print(f"   {'PASS' if results['观众开麦房主收到'] else 'FAIL'}  房主日志出现「收到对方音频轨」")
    else:
        print("   SKIP  没找到麦克风按钮")

    # ── 5. 房主转屏 → 观众跟不跟 ───────────────────────────────────────────
    print("\n== 5) 房主转横屏，观众屏会不会跟着转 ==")
    adb(host, "shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb(host, "shell", "settings", "put", "system", "user_rotation", "1")
    time.sleep(8)
    # 判据用**截图尺寸**而不是日志：logcat 缓冲会被系统噪声冲掉（上一轮就是这么误判的），
    # 而"屏幕到底横没横"直接看像素宽高最硬。
    p2 = os.path.join(out_dir, "viewer-landscape.png")
    screencap(phone, p2)
    from PIL import Image
    w0, h0 = Image.open(vp).size
    w1, h1 = Image.open(p2).size
    results["观众跟随转向"] = (w1 > h1) and (w0 < h0)
    vlog2 = adb(phone, "logcat", "-d", "-s", "ViewerSession:V", "MainActivity:V").stdout
    print(f"   {'PASS' if results['观众跟随转向'] else 'FAIL'}  "
          f"观众屏 {w0}x{h0} -> {w1}x{h1}（日志佐证："
          f"{'有' if 'Landscape' in vlog2 else '无'}）")
    adb(host, "shell", "settings", "put", "system", "user_rotation", "0")
    time.sleep(6)

    # ── 6. 房主停止 → 观众收场文案 ────────────────────────────────────────
    print("\n== 6) 房主停止分享，观众该显示「房主结束了分享」 ==")
    rt.SERIAL = host
    rt.tap("停止分享")
    time.sleep(5)
    rt.SERIAL = phone
    # 弹窗还压在上面就先关掉，否则 dump 到的不是我们的界面（上一轮就是这么误判的）
    for _ in range(3):
        stuck = rt.node("不允许") or rt.node("Don’t allow") or rt.node("Don't allow")
        if not stuck:
            break
        rt.sh(rt.ADB, "-s", phone, "shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)
    # 两种收场措辞都算对：收到道别是「房主结束了分享」，
    # 通道先断（锁屏、隧道到期）是「与房主的连接断了：对方可能已停止分享」。
    # 上一版只认前者，于是产品画出了正确的中性收场屏、脚本却判它 FAIL。
    ended = bool(rt.node("房主结束了分享") or rt.node("与房主的连接断了"))
    bad = rt.node("根据什么这么判断") is not None or rt.node("按这个顺序试") is not None
    results["收场文案正确"] = ended and not bad
    print(f"   {'PASS' if results['收场文案正确'] else 'FAIL'}  结束={ended} 误报失败={bad}")
    screencap(phone, os.path.join(out_dir, "viewer-ended.png"))

    # ── 汇总 ───────────────────────────────────────────────────────────────
    adb(phone, "logcat", "-d").stdout  # 留一份全量在下面
    io.open(os.path.join(out_dir, "viewer-full.log"), "w", encoding="utf-8").write(
        adb(phone, "logcat", "-d").stdout)
    io.open(os.path.join(out_dir, "host-full.log"), "w", encoding="utf-8").write(
        adb(host, "logcat", "-d").stdout)
    adb(host, "shell", "settings", "put", "system", "accelerometer_rotation", "1")

    print("\n== 汇总 ==")
    allok = True
    for k, v in results.items():
        print(f"   {'PASS' if v else 'FAIL'}  {k}")
        allok = allok and v
    print(f"   产物：{out_dir}")
    print("   ⚠ 回声/音质这类要靠耳朵的，脚本判不了，得人听。")
    return 0 if allok else 1


if __name__ == "__main__":
    sys.exit(main())
