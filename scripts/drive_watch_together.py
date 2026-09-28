#!/usr/bin/env python3
"""一起看（同看）端到端验收：房主开内置浏览器 → 观众看到进度 → 观众按 +10 → 房主真的跳。

判据是**三处像素互相印证**，不接受"日志说发过了"：
  1. 房主屏上那个大字时钟（watch/test.html 里的 #clock）；
  2. 观众屏上同看条显示的 `pos / dur`；
  3. 观众按 +10 之后，房主屏的时钟与观众条同时往前跳约 10 秒。
只看第 3 条不够 —— 时钟本来就在走，必须拿第 1、2 条对齐了才能说"是这一按跳的"。

顺带量一件事：控件出现的那几秒，观众看到的画面会不会被压暗（PLAN §17 一直没结论）。
用同一屏内容、控件收起/展开两张图的**同一块内容区**均值对比，比"感觉暗了"可靠。

⚠ 坐标兜底：GlassTextButton 的 "-10/+10" 是画出来的，进不了 uiautomator 的无障碍树
（上一轮就栽在这儿：find() 报"没有 +10"，其实观众屏上明明画着）。
所以找不到时退回按坐标点，并把这件事打印出来 —— 兜底必须可见，否则下次又会误判成缺陷。

前置：t2test 已在分享（scripts/drive_m0.py），t2view 已连上（scripts/drive_app_viewer.py --keep-sharing）。
用法： python scripts/drive_watch_together.py
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
DEV = os.path.join(HERE, "..", ".dev")
sys.path.insert(0, HERE)
import t2device

ADB = r"C:\Android\sdk\platform-tools\adb.exe"
ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT="5039", MSYS_NO_PATHCONV="1")

# 1080x2400 的 t2view 上实测出来的位置（见上面的兜底说明）
FALLBACK = {
    "+10": (395, 2010),
    "-10": (128, 2010),
    "▶": (262, 2010),
    "❚❚": (262, 2010),
}
# 观众屏上不属于任何控件、也不属于房主控件的一块内容区（房主那屏这块是黑底 + 时钟）
CONTENT_BOX = (100, 820, 980, 1000)


def sh(*args, timeout=90):
    return subprocess.run(args, capture_output=True, encoding="utf-8", errors="replace",
                          env=ENV, timeout=timeout)


def adb(serial, *args, timeout=90):
    return sh(ADB, "-s", serial, *args, timeout=timeout)


def dump(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/wt.xml")
    return adb(serial, "shell", "cat", "/sdcard/wt.xml").stdout


def find(serial, text):
    xml = dump(serial)
    for m in re.finditer(r"<node[^>]*>", xml):
        tag = m.group(0)
        if f'text="{text}"' in tag or f'content-desc="{text}"' in tag:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def locate(serial, text):
    """先按无障碍树找，找不到退回坐标 —— 但一定把退回说出来。"""
    pt = find(serial, text)
    if pt:
        return pt
    if text in FALLBACK:
        print(f"   ⚠ 「{text}」不在无障碍树里（画出来的按钮），退回坐标 {FALLBACK[text]}")
        return FALLBACK[text]
    return None


def tap(serial, pt):
    adb(serial, "shell", "input", "tap", str(pt[0]), str(pt[1]))


def shot(serial, name):
    dst = os.path.join(DEV, name)
    adb(serial, "shell", "screencap", "-p", "/sdcard/wt.png")
    adb(serial, "pull", "/sdcard/wt.png", dst)
    return dst


def mean(path, box=None):
    from PIL import Image
    im = Image.open(path).convert("L")
    if box:
        im = im.crop(box)
    px = list(im.getdata())
    return sum(px) / len(px)


def main():
    host = t2device.resolve("t2test")
    view = t2device.resolve("t2view")
    print(f"房主 {host} / 观众 {view}")

    # 1) 房主：进"一起看"
    if find(host, "测试片"):
        print("   房主已经在同看页上，跳过进入这一步")
    else:
        btn = locate(host, "同屏放映")
        if not btn:
            print("FAIL 房主屏上找不到「同屏放映」—— 分享没在跑？")
            return 1
        tap(host, btn)
        print("   点「同屏放映」，等页面加载…")
    time.sleep(12)
    # 网页的 autoplay 在 WebView 里经常被静默拦掉（媒体策略看的是"有没有用户手势"，
    # 而我们的手势发生在 App 的按钮上，不算给页面）。这里替用户点一下播放器本身。
    print("   房主侧点播放器中间，补一次真实手势…")
    tap(host, (540, 690))
    time.sleep(6)
    h_before = shot(host, "watch-host-before.png")

    # 2) 观众：控件收起状态先量一次亮度，再唤出控件量第二次
    shot(view, "watch-viewer-chrome-off.png")
    tap(view, (540, 1200))
    time.sleep(1.2)
    v_bar = shot(view, "watch-viewer-bar.png")
    off = mean(os.path.join(DEV, "watch-viewer-chrome-off.png"), CONTENT_BOX)
    on = mean(os.path.join(DEV, "watch-viewer-bar.png"), CONTENT_BOX)
    print(f"   控件收起 vs 展开，同一块内容区亮度：{off:.1f} → {on:.1f}")

    plus = locate(view, "+10")
    if not plus:
        print(f"FAIL 观众侧没有同看条 —— 截图：{v_bar}")
        return 1
    tap(view, plus)
    print("   观众按下 +10，等广播回来…")
    time.sleep(3.0)
    h_after = shot(host, "watch-host-after.png")
    tap(view, (540, 1200))       # 再唤出控件，读跳完之后的进度
    time.sleep(1.0)
    v_after = shot(view, "watch-viewer-after.png")

    # 真正的判据是**倒放**：播放只会往前走，数字一旦变小，就只能是观众那一下造成的。
    # （只看 +10 不够：两次截图之间本来就过了 4~5 秒，差值里混着自然播放，说不清。）
    print("   观众连按两次 -10（要看到时间往回走）…")
    minus = locate(view, "-10")
    tap(view, (540, 1200))       # 控件可能已经收起，先唤出
    time.sleep(0.6)
    if minus:
        tap(view, minus)
        time.sleep(0.6)
        tap(view, minus)
    time.sleep(1.6)
    h_back = shot(host, "watch-host-backward.png")

    from PIL import Image
    print("   复核用的裁图（人眼看数字，不做 OCR）：")
    for src, dst in [(h_before, "watch-clock-before.png"), (h_after, "watch-clock-after.png"),
                     (h_back, "watch-clock-backward.png")]:
        Image.open(src).crop((330, 1010, 760, 1190)).save(os.path.join(DEV, dst))
        print(f"   {os.path.join(DEV, dst)}")
    for src, dst in [(v_bar, "watch-bar-before.png"), (v_after, "watch-bar-after.png")]:
        Image.open(src).crop((420, 1965, 800, 2055)).save(os.path.join(DEV, dst))
        print(f"   {os.path.join(DEV, dst)}")
    print("   判定：clock-backward 的数字必须**小于** clock-after，否则观众的控制没有生效。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
