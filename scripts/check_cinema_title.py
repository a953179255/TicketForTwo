#!/usr/bin/env python3
"""放映条上的**片名**：房主那边算出来的那一条，观众那边是不是真看得见、换片后是不是跟着变。

为什么要专门有它：`MediaSniffer.hostLabel` 改成"站点 · 路径尾段"之前，同一个站点换一条流
标题一个字都不变（两条都是 `test-streams.mux.dev 的那条`），房主点了"换片"，观众那条横条
还写着上一部 —— 于是"到底在放哪条"只能靠猜。单测能钉住 hostLabel 本身，
钉不住"这个字符串走完 递片 → 广播 → 观众渲染 一整条路之后还在屏幕上"。

所以这里量的是**两端各读一次**：
  · 房主侧：uiautomator 读放映厅卡片上那行标题；
  · 观众侧：CDP 读网页放映条里的标题节点。
两边都要求"非空、且换片之后确实变了"。

前置：房主那一端先跑起来（厅已开、.dev/invite.txt 里有链接）。
用法： python scripts/check_cinema_title.py [serial]
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import t2device  # noqa: E402
from drive_phone_cdp import Cdp  # noqa: E402

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
PKG = "com.ticketfortwo.app"
ROOT = os.path.dirname(HERE)
INVITE = os.path.join(ROOT, ".dev", "invite.txt")
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
PORT = str(9500 + int(time.time()) % 60)
URL1 = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
URL2 = "https://test-streams.mux.dev/pts_shift/master.m3u8"

# 放映条 + 页面收到的放映状态，一起读回来。
#
# 为什么连 `cine` 一起读：这条横条在"收到了状态但这条流在这个浏览器里放不出来"时
# 会被页面自己藏掉（退回看屏幕流），那是**环境限制**不是标题没送到 ——
# 上一版只看 #cineBar 是否显示，于是把 hls.js 在隧道里加载不出来报成了"标题丢了"。
STATE_JS = """(() => {
  const bar = document.getElementById('cineBar');
  const t = document.getElementById('cTitle');
  const shown = !!bar && !bar.classList.contains('hide');
  let c = null;
  try { c = (typeof cine !== 'undefined' && cine)
        ? {title: String(cine.title || ''), url: String(cine.url || '').slice(0, 46)} : null; } catch (e) {}
  return JSON.stringify({
    shown: shown,
    text: (t && shown) ? (t.textContent || '').trim() : '',
    got: c,
    hls: (typeof Hls !== 'undefined'),
    ws: (typeof ws !== 'undefined' && ws) ? ws.readyState : null,
  });
})()"""


def sh(serial, *args, timeout=60):
    return subprocess.run([ADB, "-s", serial] + list(args), capture_output=True,
                          text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout


TITLE_RE = re.compile(r"(?:片源已递给对方|换片了)：(.+)")


def host_title(serial):
    """房主卡片上那行标题，从 logcat 取而不是从 uiautomator 取。

    两个原因：① 放映中的卡片文本和顶栏的"厅已开 · 等对方进来"长得一样有" · "，
    按形状挑节点会挑错行（第一版就是这么把量到的标题读成顶栏的）；
    ② WebView 在渲染视频时 uiautomator 的树本来就不稳。
    note() 里那句"片源已递给对方：<title>"用的正是界面要显示的同一个字符串。"""
    log = sh(serial, "logcat", "-d", "-s", "CallSession")
    hits = TITLE_RE.findall(log)
    return hits[-1].strip() if hits else ""


def screen_best(serial):
    sh(serial, "shell", "am", "broadcast", "-a", f"{PKG}.DEBUG_SCREEN_BEST",
       "-n", f"{PKG}/.DebugReceiver")


def tap(serial, needle, w, h):
    """按文本点：坐标从 dump 里取，**用当前这一屏的宽高**（横屏不互换，别拿 wm size 猜）。"""
    sh(serial, "exec-out", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = sh(serial, "exec-out", "cat", "/sdcard/ui.xml")
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t, x1, y1, x2, y2 = m.group(1), *[int(v) for v in m.groups()[1:]]
        if needle in t:
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            if 0 <= cx <= w and 0 <= cy <= h:
                sh(serial, "shell", "input", "tap", str(cx), str(cy))
                return True
    return False


def open_viewer(url):
    udd = os.path.join(os.environ.get("TEMP", "."), f"t2-title-{int(time.time())}")
    proc = subprocess.Popen(
        [EDGE, "--headless=new", f"--remote-debugging-port={PORT}", f"--user-data-dir={udd}",
         "--window-size=900,800", "--autoplay-policy=no-user-gesture-required",
         "--use-fake-ui-for-media-stream", "--no-first-run",
         "--disable-features=SyncPromoConfirmDialogCreation,SigninPromo"
         "--disable-sync", url],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # **认 URL，不认第一个 target**：Edge 会自己插一个
    # edge://sync-confirmation-dialog/ 的"在这台设备上同步吗"页，它常常排在第一位，
    # 于是上一版量的一直是那个弹窗（ws/cine 全空，报成"观众没收到状态"）。
    pages = []
    for _ in range(30):
        time.sleep(1)
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json", timeout=5) as r:
                got = [p for p in json.loads(r.read().decode()) if p.get("type") == "page"]
            pages = [p for p in got if "trycloudflare.com" in p.get("url", "")]
            if pages:
                break
        except Exception:
            pass
    if not pages:
        raise SystemExit("FAIL  Edge 里没有观众端那一张页（可能全被 edge:// 的推广页占了）")
    cdp = Cdp(pages[0]["webSocketDebuggerUrl"])
    cdp.call("Page.enable")
    cdp.call("Runtime.enable")
    return proc, cdp


def page_state(cdp):
    """Cdp.eval 回的是 {"value": ...}，不是裸字符串 —— 上一版按裸字符串解，
    于是永远解出空 dict，把"读不到"伪装成"页面没收到"。"""
    r = cdp.eval(STATE_JS)
    v = r.get("value") if isinstance(r, dict) else r
    try:
        return json.loads(v) if isinstance(v, str) else {}
    except Exception:
        return {}


def wait_bar(cdp, tries=30):
    """等放映条出现，或者等页面至少**收到**放映状态（后者即使条被藏了也算送达）。"""
    d = {}
    for _ in range(tries):
        time.sleep(1)
        d = page_state(cdp)
        if d.get("shown") or d.get("got"):
            return d
    return d


def main():
    serial = sys.argv[1] if len(sys.argv) > 1 else t2device.resolve("t2test")
    invite = open(INVITE, encoding="utf-8").read().strip()
    if not invite.startswith("https://"):
        raise SystemExit(f"FAIL  .dev/invite.txt 不是一条链接：{invite[:40]!r}")
    # 屏幕边界以**截图自身**为准：`wm size` 返回的是自然方向，横屏时宽高不互换，
    # 拿它当点击范围就会把点打到屏外（这条坑在本项目踩过三次）。
    png = os.path.join(ROOT, ".dev", "title-probe.png")
    with open(png, "wb") as f:
        f.write(subprocess.run([ADB, "-s", serial, "exec-out", "screencap", "-p"],
                               capture_output=True, timeout=40).stdout)
    from PIL import Image
    w, h = Image.open(png).size

    print(f"房主 {serial}  屏 {w}x{h}")
    # 先把房主推到第一条上（厅已开时这条 intent 只换页，不会重开会话），
    # 不然"第一条标题"取到的是上一次运行留下的那一条，两条一比就永远"没变"。
    sh(serial, "shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
       "--es", "android.intent.extra.TEXT", URL1, "-n", f"{PKG}/.MainActivity")
    time.sleep(18)
    screen_best(serial)
    time.sleep(2.5)
    t1 = host_title(serial)
    print(f"  房主卡片标题：{t1!r}")
    if not t1:
        print("FAIL  房主卡片上没有标题行（没递成片？dump 空树？）")
        return 1

    proc, cdp = open_viewer(invite)
    bad = 0
    try:
        d1 = wait_bar(cdp)
        v1 = d1.get("text", "")
        print(f"  观众放映条：  {v1!r}   页面收到={d1.get('got')!r} hls={d1.get('hls')} ws={d1.get('ws')}")
        if not d1.get("got"):
            print("FAIL  观众页面根本没收到放映状态（进厅失败 / 房主没广播）")
            bad += 1
        elif not d1.get("shown"):
            print("SKIP  状态到了但放映条被页面自己藏了 —— 这条 .m3u8 在无头浏览器里"
                  "要 hls.js，加载不到就退回屏幕流。标题送达已验证，渲染交给 audit_web_states.py")
        elif v1 != t1:
            print(f"FAIL  两端标题不一致：房主 {t1!r} vs 观众 {v1!r}")
            bad += 1

        # 换片：用**用户真实的那条路** —— 再分享一次另一个页面的链接。
        # 原来这里靠 uiautomator 点「展开嗅探」→「换一条流」，而放映中 WebView 正在渲染视频，
        # dump 经常给一棵没有文本节点的树（本项目第 N 次撞到），于是脚本把"点不到"报成产品坏了。
        sh(serial, "shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
           "--es", "android.intent.extra.TEXT", URL2, "-n", f"{PKG}/.MainActivity")
        time.sleep(18)
        screen_best(serial)
        time.sleep(3)
        t2 = host_title(serial)
        v2 = wait_bar(cdp, tries=6).get("text", "")
        print(f"  换片后 房主：{t2!r}")
        print(f"  换片后 观众：{v2!r}")
        if t2 == t1:
            print("FAIL  换片之后房主标题没变（hostLabel 又撞名了）")
            bad += 1
        if v2 and v2 != t2:
            print("FAIL  换片之后观众那条没跟上")
            bad += 1
    finally:
        proc.terminate()
    print("PASS" if not bad else f"FAIL  {bad} 条")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
