#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真实站点的 **S 档命中率**：一条一条量"房主嗅到的地址，观众到底播不播得出来"。

为什么必须先有这个数
--------------------
放映厅 S 档的全部价值建立在一个假设上："观众自己拿同一条地址去播，画质是原生的"。
但这个假设在真实站点上能成立几次，之前只有印象、没有数。而 A 档（房主手机做
Range 中继）是该不该做的唯一判据就是这个数 —— 没有它，做与不做都是猜。

更要紧的是判据本身：**"嗅到了地址"不等于"对方播出来了"**。
签名过期、Referer/Cookie 绑定、需要会员的分片，都不会报错、就是不出画面。
所以这里读的日志是观众那边真的出了首帧才发的 `CINEMA_ACK v|ok|...`，
不是房主界面的"正在放映"，也不是嗅探器的候选条数。

流程（一轮，全程不重启房主 App）
--------------------------------
1. 房主已经在放映厅里（信令 + 隧道已起），脚本从 logcat 拿到邀请链接；
2. 用无头 Edge 开一个观众（真 Chromium，能跑 WebRTC 与 hls.js），整轮共用这一个；
3. 对每个站点：走**真实的「分享 → 双人票」入口**（ACTION_SEND）→ 轮询嗅探结果
   → 广播 DEBUG_SCREEN_BEST 递出最佳候选 → 等这条版本的回执；
4. 打印漏斗表：打开 → 嗅到 → 递出 → 对方播出，并把明细落到 .dev/cinema-coverage.json。

已知的坑（都在这条链路上踩过）
------------------------------
* **中文日志要显式 utf-8**，否则 Windows 默认 GBK 直接抛异常，观测窗口就丢了；
* **无头 Edge 每次要换 user-data-dir**：profile 被上一次残留进程锁住时，新进程会把
  链接丢给旧实例然后自己退出，看起来"启动了"却根本没进厅（这一条今天真遇到过）；
* **判据不能用界面上的字**：uiautomator 点按钮有过三次假阳性（软键盘遮挡、地址栏
  与候选行文字相似、固定 sleep 让脚本误报"已放映"），所以递片走 debug 广播；
* 页面加载 + 首个清单请求的耗时随网络浮动，所以**一律轮询**，不睡固定秒数。

用法： python scripts/drive_cinema_coverage.py [emulator-5556] [站点URL ...]
"""
import json
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
PKG = "com.ticketfortwo.app"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, ".dev", "cinema-coverage.json")

# 默认样本：三类混着来，才有分辨力
#   公开无鉴权的测试流 —— 对照组，理应全绿；不绿说明是链路坏了，不是站点的问题
#   公开商业 demo（Akamai/Apple/Unified）—— 真 CDN、真 HLS，但没绑 Referer
#   国内真实播放页 —— 这才是用户实际会用的那类，也是 A 档要不要做的决定者
DEFAULT_SITES = [
    ("mux 测试流", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
    ("Apple bipbop", "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"),
    ("Unified 钢之泪", "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"),
    ("Akamai sintel", "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8"),
    ("B 站 大航海", "https://www.bilibili.com/video/BV1GJ411x7h7/"),
    ("有剧场 首页片", "https://www.yjllq.com/#home"),
]

PLAYABLE = ("Master", "Progressive", "Dash", "Audio")
SNIFF_WAIT = 32      # 页面加载 + 首个清单请求
ACK_WAIT = 22        # 观众侧看门狗是 8 秒，加隧道往返，22 秒还没信儿就当没回执


def sh(serial, *args, timeout=40):
    return subprocess.run(
        [ADB, "-s", serial] + list(args), capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=timeout,
    ).stdout


def log_of(serial, tags):
    return sh(serial, "logcat", "-d", "-v", "brief", "-s", *tags)


def last_sniff(serial):
    """返回 (最新一条可播候选 URL, 全部候选种类)。"""
    log = log_of(serial, ("Cinema:V",))
    hits = re.findall(r"SNIFF kind=(\w+) url=(\S+)", log)
    playable = [u for k, u in hits if k in PLAYABLE]
    return (playable[-1] if playable else None), [k for k, _ in hits]


def wait_for(predicate, seconds, step=1.0):
    end = time.time() + seconds
    while time.time() < end:
        if predicate():
            return True
        time.sleep(step)
    return False


def open_viewer(invite):
    """起一个无头 Edge 当观众。返回 Popen —— 整轮共用这一个，别反复开关。"""
    profile = os.path.join(
        os.environ.get("TEMP", "."), "t2-edge-cov-%d" % int(time.time()))
    url = invite + ("&" if "?" in invite else "?") + "autojoin=1"
    return subprocess.Popen(
        [EDGE, "--headless=new",
         "--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
         # 没有手势 —— 不放行的话 autoplay 直接被静音/拒播，回执就永远是 timeout
         "--autoplay-policy=no-user-gesture-required",
         "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessSendPreflights",
         "--disable-gpu", "--no-first-run", "--window-size=1280,800",
         "--user-data-dir=" + profile, url],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def run_site(serial, label, url):
    """一个站点：递链接 → 等嗅探 → 递最佳候选 → 等回执。返回一行结果。"""
    row = {"label": label, "page": url, "sniffed": None, "screened": None,
           "ack": "noack", "code": "", "detail": "", "seconds": 0}
    t0 = time.time()
    # 每个站点开头清一次日志缓冲。不清的话上一个站点的 SNIFF 还在里面，
    # 这一站什么都没嗅到也会被读成"嗅到了"—— 假阳性直接进分子。
    sh(serial, "logcat", "-c")
    sh(serial, "shell", "am", "start", "-a", "android.intent.action.SEND",
       "-t", "text/plain", "--es", "android.intent.extra.TEXT", "'%s'" % url,
       "-n", PKG + "/.MainActivity")

    if not wait_for(lambda: last_sniff(serial)[0], SNIFF_WAIT, 1.5):
        row["ack"] = "nosniff"
        kinds = last_sniff(serial)[1]
        row["detail"] = "嗅到的种类：" + (",".join(sorted(set(kinds))[-6:]) or "一条都没有")
        row["seconds"] = round(time.time() - t0, 1)
        return row
    row["sniffed"] = last_sniff(serial)[0]

    # 版本从这一刻起才有意义：先记下 CINEMA_SCREEN 的最大版本，再等新回执
    before = max_screen_ver(serial)
    out = sh(serial, "shell", "am", "broadcast", "-a", PKG + ".DEBUG_SCREEN_BEST",
             "-n", PKG + "/com.ticketfortwo.app.DebugReceiver")
    if "Error" in out and "Broadcast" not in out:
        row["ack"] = "nobroadcast"
        row["detail"] = out.strip()[:120]
        return row
    if not wait_for(lambda: max_screen_ver(serial) > before, 8, 0.5):
        row["ack"] = "notscreened"
        # 递不出去时要把 App 自己的话抄回来：它知道是"没候选"还是"钩子没挂上"。
        # 这一行今天救过一次 —— 日志里明明有 SNIFF，屏幕上的候选列表却是空的，
        # 光看脚本的"没递出"三个字是查不到这一层的。
        dbg = log_of(serial, ("DebugReceiver:V",)).strip().splitlines()
        probe = log_of(serial, ("Cinema:V",)).strip().splitlines()
        row["detail"] = "；".join([
            (dbg[-1][:90] if dbg else "DebugReceiver 没出声"),
            ([l for l in probe if "PAGEPROBE" in l] or ["没有 PAGEPROBE"])[-1][:70],
        ])
        row["seconds"] = round(time.time() - t0, 1)
        return row
    ver = max_screen_ver(serial)
    row["screened"] = screened_url(serial, ver)

    def got():
        return ack_for(serial, ver)

    if not wait_for(got, ACK_WAIT, 1.0):
        row["ack"] = "noack"
        row["detail"] = "%d 秒内没有回执（观众侧看门狗是 8 秒）" % ACK_WAIT
    else:
        ok, code, detail = got()
        row["ack"] = "ok" if ok else "fail"
        row["code"], row["detail"] = code, detail
    row["seconds"] = round(time.time() - t0, 1)
    return row


def max_screen_ver(serial):
    log = log_of(serial, ("CallSession:V",))
    vs = [int(v) for v in re.findall(r"CINEMA_SCREEN (\d+)\|", log)]
    return max(vs) if vs else 0


def screened_url(serial, ver):
    log = log_of(serial, ("CallSession:V",))
    m = re.search(r"CINEMA_SCREEN %d\|(\S+)" % ver, log)
    return m.group(1) if m else None


def ack_for(serial, ver):
    """返回 (ok, code, detail) 或 None —— 只认这一条版本，旧回执不算。"""
    log = log_of(serial, ("CallSession:V",))
    for m in re.finditer(r"CINEMA_ACK (\d+)\|(ok|fail)\|([^|]*)\|(.*)", log):
        if int(m.group(1)) == ver:
            return (m.group(2) == "ok", m.group(3).strip(), m.group(4).strip())
    return None


def main():
    serial = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1].startswith("emulator") \
        else "emulator-5556"
    extra = [a for a in sys.argv[1:] if not a.startswith("emulator")]
    sites = ([(("站点 %d" % (i + 1)), u) for i, u in enumerate(extra)]
             if extra else DEFAULT_SITES)

    print("== S 档命中率 · 设备 %s ==" % serial)
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import open_room

    invite = None
    for _ in range(10):
        log = log_of(serial, ("CallSession:V",))
        m = re.findall(r"(https://\S+trycloudflare\.com/\?k=\S+)", log)
        if m:
            invite = m[-1].rstrip("）)，,。")
            break
        time.sleep(1)
    if not invite:
        # 日志里没有不等于没有：可能是我清过缓冲。退而求其次用 invite.txt（带 HTTP 探活），
        # 再不行就重新开一次厅 —— 三条路都走一遍，别让"量具看不到"变成"结论是没有"。
        invite = open_room.saved_invite()
    if not invite:
        print("     日志和 invite.txt 都没有可用链接，重新开一次厅…")
        invite = open_room.open_room(serial)
    if not invite:
        print("FAIL  拿不到可用邀请 —— 先手动确认房主能开厅（scripts/open_room.py）")
        return 1
    print("邀请：%s" % invite)

    viewer = open_viewer(invite)
    try:
        if not wait_for(
            lambda: "观众已加入" in log_of(serial, ("CallSession:V",)), 45, 1.5,
        ):
            print("FAIL  观众 45 秒没进厅（多半是 profile 被上一次残留进程锁住）")
            return 1
        print("观众已进厅，开始逐站测量（每站最多 %d 秒）\n" % (SNIFF_WAIT + ACK_WAIT))

        rows = []
        for label, url in sites:
            r = run_site(serial, label, url)
            rows.append(r)
            mark = {"ok": "播出", "fail": "放不出", "nosniff": "没嗅到",
                    "noack": "无回执", "notscreened": "没递出",
                    "nobroadcast": "广播失败"}.get(r["ack"], r["ack"])
            print("%-14s %-6s %5.1fs  %s%s" % (
                label, mark, r["seconds"],
                (r["code"] + " ") if r["code"] else "",
                (r["detail"] or r["screened"] or r["sniffed"] or "")[:96]))

        ok = sum(1 for r in rows if r["ack"] == "ok")
        sniffed = sum(1 for r in rows if r["sniffed"])
        print("\n=== 漏斗 ===")
        print("  站点 %d · 嗅到可播地址 %d (%.0f%%) · 对方真播出来 %d (%.0f%%)" % (
            len(rows), sniffed, 100.0 * sniffed / len(rows), ok, 100.0 * ok / len(rows)))
        codes = {}
        for r in rows:
            if r["ack"] == "fail":
                codes[r["code"]] = codes.get(r["code"], 0) + 1
        if codes:
            print("  失败原因分布：" + "  ".join("%s×%d" % kv for kv in sorted(
                codes.items(), key=lambda kv: -kv[1])))

        with open(OUT, "w", encoding="utf-8") as f:
            json.dump({"serial": serial, "invite": invite,
                       "at": time.strftime("%Y-%m-%d %H:%M:%S"), "rows": rows},
                      f, ensure_ascii=False, indent=2)
        print("\n明细：%s" % OUT)
    finally:
        viewer.terminate()
    return 0


if __name__ == "__main__":
    sys.exit(main())
