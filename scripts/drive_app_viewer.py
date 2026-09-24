#!/usr/bin/env python3
"""在第二台模拟器 t2view 上跑一遍"App 内观看"，验证观众端整条链路。

为什么非得有第二台：观众侧（ViewerSession + WsClient）此前**从未端到端跑过** ——
同一个 APK 在一台模拟器上开不了两份，而 emulator-5554 是 haoai_emu（别的项目在用，
不能碰）。上一轮那个"主线程写 socket 把候选全丢掉"的缺陷（PLAN §16）就是靠读代码
+ 单测推出来的，一直没有真机证据。

判据全部来自日志与像素，不接受"看起来没问题"：
  1. WsClient 连上，且 ViewerSession 打出「已回话（Answer…）」——answer 真的发出去了；
  2. 「发送失败」0 条（上一轮那个坑的指纹）；
  3. ice=CONNECTED 且收到远端视频轨；
  4. 截图上真的看得见画面（均值不能等于纯黑），房主停止后是「房主结束了分享」。

⚠ 局限：两台模拟器都在同一台 PC、同一张 NAT 后面，所以这里验的是**信令与渲染**，
不是跨网打洞能力 —— 同机测试会掩盖候选投递类 bug（§16 的教训），跨网仍须真机。

用法：先在 t2test 上分享（scripts/drive_m0.py），再跑本脚本。
"""
import os
import re
import subprocess
import sys
import time

# Windows 控制台默认 GBK，脚本里的"⚠"这类字符会直接把 print 炸掉
# （实测：判定都跑完了，最后打印那行警告时整个脚本崩了，看起来像测试失败）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import t2device
import check_restart_roundtrip as rt   # 复用 ui()/node()/tap()/logs()，它们认 rt.SERIAL

HOST_AVD = "t2test"
VIEW_AVD = "t2view"
PKG = "com.ticketfortwo.app"
ACT = f"{PKG}/.MainActivity"
HERE = os.path.dirname(os.path.abspath(__file__))
INVITE = os.path.join(HERE, "..", ".dev", "invite.txt")
SHOT = os.path.join(HERE, "..", ".dev", "app-viewer.png")


def adb(serial, *args, timeout=60):
    return rt.sh(rt.ADB, "-s", serial, *args, timeout=timeout)


def tap_at(serial, x, y):
    adb(serial, "shell", "input", "tap", str(x), str(y))


def wait_node(serial, text, limit=20):
    for _ in range(limit):
        p = rt.node(text)
        if p:
            return p
        time.sleep(1)
    return None


def screencap(serial, dst):
    adb(serial, "shell", "screencap", "-p", "/sdcard/t2v.png")
    adb(serial, "pull", "/sdcard/t2v.png", dst)
    return os.path.exists(dst) and os.path.getsize(dst) > 0


def mean_brightness(path):
    """截图均值。纯黑（没画面）会直接暴露在这里，不用肉眼猜。"""
    try:
        from PIL import Image
        im = Image.open(path).convert("L")
        px = list(im.getdata())
        return sum(px) / len(px)
    except Exception as e:
        return None


def main():
    host = t2device.resolve(HOST_AVD)
    view = t2device.resolve(VIEW_AVD)
    print(f"== 房主 {host}（{HOST_AVD}） / 观众 {view}（{VIEW_AVD}）==")

    if not os.path.exists(INVITE):
        print("缺少 .dev/invite.txt，先在 t2test 上跑 scripts/drive_m0.py")
        return 1
    url = open(INVITE, encoding="utf-8").read().strip()
    if "?k=" not in url:
        print("invite.txt 里不是隧道邀请")
        return 1

    # 提交前先确认隧道真的通了。Cloudflare 的 quick tunnel 注册有延迟，而且会自己掉：
    # 实测出现过 curl 拿到 200、两分钟后同一地址回 530。这时候观众端"连不上"是隧道的
    # 问题，不是被测代码的问题 —— 不先卡这一道，判定就是随机的。
    import urllib.request
    import urllib.error
    live = False
    for _ in range(20):
        try:
            with urllib.request.urlopen(url, timeout=15) as resp:
                if resp.status == 200:
                    live = True
                    break
        except Exception:
            pass
        time.sleep(5)
    if not live:
        print("FAIL  房主的隧道地址取不到页面（先确认 t2test 还在分享）")
        return 1

    # 观众机的日志只看这一轮
    adb(view, "logcat", "-c")
    adb(view, "shell", "am", "force-stop", PKG)
    # ACT 本身就是 "包名/.类名"，即 -n 要的完整组件；再拼一次包名会得到
    # "pkg/pkg/.MainActivity" 这种畸形串，am start 直接报 Bad component name，
    # 而脚本只看"焦点没过来"，于是报成"App 没起来"——是量具写错了。
    r = adb(view, "shell", "am", "start", "-n", ACT)
    # 冷启动要等真正确前台：三台模拟器同时跑的时候 4 秒根本不够，
    # 上一版就是固定 sleep 太短，脚本说"首页找不到按钮"，其实界面还没出来。
    focused, last = False, ""
    for _ in range(30):
        time.sleep(1)
        # 只看 mCurrentFocus 那一行本身。上一版写的是 split("mCurrentFocus")[-1][:120]，
        # 而 dumpsys window 里这个键出现不止一次，取"最后一次"取到的是别的东西，
        # 于是一个已经好好在前台的 App 被判成"没起来"。
        out = adb(view, "shell", "dumpsys", "window").stdout
        lines = [l.strip() for l in out.splitlines() if "mCurrentFocus" in l]
        last = lines[0] if lines else "<没有 mCurrentFocus 行>"
        if PKG in last:
            focused = True
            break
    if not focused:
        print(f"FAIL  观众机的 App 没到前台，焦点一直是：{last}")
        print(f"      am start 输出：{r.stdout.strip()[:200]}")
        return 1

    # 首页 → 进入观看。按 content-desc/text 找，找不到就用坐标兜底的前提是
    # 先证明按钮真的在屏幕上，所以这里宁可失败也不要瞎点。
    rt.SERIAL = view
    p = wait_node(view, "进入观看")
    if not p:
        print("FAIL  观众机首页找不到「进入观看」")
        return 1
    tap_at(view, *p)
    if not wait_node(view, "在 App 内观看"):
        print("FAIL  没进到粘贴邀请那一屏")
        return 1

    # 输入链接：点输入框 → input text。URL 里没有 shell 特殊字符，直接单引号包住。
    field = wait_node(view, "长按粘贴") or wait_node(view, "邀请链接")
    if not field:
        print("FAIL  找不到邀请链接输入框")
        return 1
    tap_at(view, *field)
    time.sleep(1)
    adb(view, "shell", "input", "text", url.replace(" ", ""))
    time.sleep(1)
    # 收起键盘再点按钮。上一版不收键盘，(x,y) 全落在键盘上：不但按钮没点到，
    # 还把按键当成了输入 —— 实测链接尾巴上多出 "gg"，然后整轮判定都是假的。
    adb(view, "shell", "input", "keyevent", "KEYCODE_ESCAPE")
    time.sleep(1)

    # 提交前先核对框里的字，不匹配就停 —— 否则"连不上"其实是量具输错了
    got = ""
    for n in re.finditer(r'text="([^"]*)"', rt.ui()):
        if n.group(1).startswith("http"):
            got = n.group(1)
            break
    if got != url:
        print(f"FAIL  邀请链接没输对：期望 …{url[-14:]}，实际 …{got[-14:]}")
        return 1

    go = wait_node(view, "在 App 内观看")
    if not go:
        print("FAIL  输入后「在 App 内观看」按钮没出现（多半文字没输进去）")
        return 1
    tap_at(view, *go)
    print(f"   已提交邀请，等观众端自己走完：{url[:52]}…")

    # 等 ice=CONNECTED（最多 40 秒）
    connected = False
    for _ in range(40):
        time.sleep(1)
        log = adb(view, "logcat", "-d", "-s", "ViewerSession:V", "WsClient:V", "Peer:V").stdout
        if "ice=CONNECTED" in log:
            connected = True
            break
        if "State.Failed" in log or "直连失败" in log:
            break

    log = adb(view, "logcat", "-d", "-s", "ViewerSession:V", "WsClient:V", "Peer:V").stdout
    send_fail = [l for l in log.splitlines() if "发送失败" in l]
    answered = "已回话" in log
    got_video = "remote video track" in log or "画面已到" in log

    print(f"   answer 已发出：{answered}")
    print(f"   ice=CONNECTED：{connected}")
    print(f"   收到远端视频轨：{got_video}")
    print(f"   「发送失败」条数：{len(send_fail)}（上一轮这个坑的指纹，必须是 0）")
    for l in send_fail[:3]:
        print("      " + l[-120:])

    time.sleep(3)
    ok_shot = screencap(view, SHOT)
    lum = mean_brightness(SHOT) if ok_shot else None
    print(f"   截图：{SHOT if ok_shot else '没截到'}  亮度均值={None if lum is None else round(lum,1)}")

    if "--keep-sharing" in sys.argv:
        # 做"方向同步"这类实验时要留着分享，否则测完就没得看了
        print("   （--keep-sharing：跳过停止分享这一步）")
        print("PASS  观众端已连上，分享保持中")
        return 0

    # 房主停止 → 观众应显示"房主结束了分享"，而不是失败屏
    rt.SERIAL = host
    if not rt.tap("停止分享"):
        print("   ⚠ 房主侧没找到「停止分享」，跳过收场判定")
    else:
        time.sleep(4)
        rt.SERIAL = view
        ended = wait_node(view, "房主结束了分享", limit=12)
        # 判"误报失败"要挑失败屏独有的东西（重试按钮、证据卡标题），不能拿"连不上"
        # 这种词去子串匹配 —— 收场文案里本来就写着"不是你的网络问题/连不上"，
        # 上一版就是这么自己绊自己：明明显示的是收场屏，却报"误报失败=True"。
        failed = bool(rt.node("根据什么这么判断") or rt.node("按这个顺序试") or rt.node("重试"))
        print(f"   房主停止后观众屏：结束={bool(ended)} 误报失败={bool(failed)}")

    bad = (not connected) or (not answered) or send_fail or (not got_video)
    print("\n" + ("FAIL  观众端链路没跑通，见上面哪条是假的" if bad else "PASS  App 内观看整条链路跑通"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
