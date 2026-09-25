#!/usr/bin/env python3
"""App 内布局体检：把当前这一屏的文本节点几何摊出来 + 存一张图。

为什么需要它：本项目已经翻过两次"结构全绿、图上是坏的"的车 ——
观众端徽章压在顶栏上、放映厅主按钮整个被切到屏外，都是 uiautomator 里
"节点存在"但像素上不成立。反过来也成立：肉眼翻截图找重叠太慢，
所以这里把两类判据一次算完：**几何越界/互相压字** + **同一时刻的截图**。

判据只报"看得见的几何事实"，不猜语义：
  OUT-OF-SCREEN  节点任一边落在屏幕外（含被底部导航条吃掉）
  OVERLAP        两个文本节点的矩形相交（面积 > 较小者的 12%）
  CLIPPED-BOTTOM 文本框底边正好等于可视区底边（= 这一行被视口切了一半，看着像坏了）

用法：
    python scripts/audit_app_layout.py                      # 默认 t2view
    python scripts/audit_app_layout.py --avd t2test --out .dev/x.png
    python scripts/audit_app_layout.py --tap 跟随 --tap 横屏 # 先点按钮再体检（可重复）
"""
import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import t2device  # noqa: E402

ADB = os.environ.get("ADB", "adb")
PKG = "com.ticketfortwo.app"

NODE_RE = re.compile(r"<node\b[^>]*?(/?)>")
ATTR_RE = re.compile(r'(\w[\w-]*)="([^"]*)"')


def sh(*args, timeout=60):
    return subprocess.run([ADB, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def resolve_serial(avd, serial):
    if serial:
        return serial
    return t2device.resolve(avd)


def screen_size(serial, png=None):
    """**以截图尺寸为准**，不要信 `wm size`：它返回的是"自然方向"的分辨率，
    横屏时宽高不互换，于是越界判定整个失效（实测：横屏 2400x1080 的屏被当成
    1080x2400，任何 y>1080 的节点都不会被报出来）。"""
    if png and os.path.exists(png):
        try:
            from PIL import Image
            w, h = Image.open(png).size
            if w and h:
                return w, h
        except Exception:
            pass
    out = sh("-s", serial, "shell", "wm", "size").stdout
    m = re.search(r"(\d+)x(\d+)", out)
    return int(m.group(1)), int(m.group(2))


def dump(serial):
    sh("-s", serial, "shell", "uiautomator", "dump", "/sdcard/t2audit.xml")
    # MSYS 会把 /sdcard/... 改写成 Windows 路径，必须绕开（本项目已踩过）
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    r = subprocess.run([ADB, "-s", serial, "shell", "cat", "/sdcard/t2audit.xml"],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env=env, timeout=60)
    return r.stdout or ""


def nodes(xml):
    out = []
    for raw in re.findall(r"<node\b[^>]*?>", xml):
        a = dict(ATTR_RE.findall(raw))
        bounds = a.get("bounds", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({
            "text": a.get("text", "") or a.get("content-desc", ""),
            "x1": x1, "y1": y1, "x2": x2, "y2": y2,
            "cls": a.get("class", "").split(".")[-1],
        })
    return [n for n in out if n["text"].strip()]


def tap_text(serial, text, tries=6):
    """按文本点一下：找不到就返回 False（不瞎点坐标）。"""
    for _ in range(tries):
        for n in nodes(dump(serial)):
            if text and text in n["text"]:
                sh("-s", serial, "shell", "input", "tap",
                   str((n["x1"] + n["x2"]) // 2), str((n["y1"] + n["y2"]) // 2))
                return True
        import time
        time.sleep(0.8)
    return False


def area(n):
    return max(0, n["x2"] - n["x1"]) * max(0, n["y2"] - n["y1"])


def intersect(a, b):
    x1, y1 = max(a["x1"], b["x1"]), max(a["y1"], b["y1"])
    x2, y2 = min(a["x2"], b["x2"]), min(a["y2"], b["y2"])
    return max(0, x2 - x1) * max(0, y2 - y1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--avd", default="t2view")
    ap.add_argument("--serial")
    ap.add_argument("--out", default=os.path.join(".dev", "audit.png"))
    ap.add_argument("--tap", action="append", default=[],
                    help="体检前先按文本点这些按钮（可重复）")
    ap.add_argument("--wake", action="store_true",
                    help="先点一下屏幕中央唤出自动收起的控件")
    args = ap.parse_args()

    s = resolve_serial(args.avd, args.serial)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)

    def cap():
        r = subprocess.run([ADB, "-s", s, "exec-out", "screencap", "-p"],
                           capture_output=True, timeout=60)
        with open(args.out, "wb") as f:
            f.write(r.stdout)
        return len(r.stdout)

    # 先截一张只为拿**真实方向**的宽高（`wm size` 横屏不互换，见 screen_size）
    cap()
    w, h = screen_size(s, args.out)
    if args.wake:
        sh("-s", s, "shell", "input", "tap", str(w // 2), str(h // 2))
        import time
        time.sleep(0.6)
    for t in args.tap:
        ok = tap_text(s, t)
        print(f"   tap {t!r}: {'ok' if ok else '找不到该文本'}")
        import time
        time.sleep(1.2)

    ns = nodes(dump(s))
    problems = []
    print(f"== {s}  {w}x{h}  文本节点 {len(ns)} 个 ==")
    for n in ns:
        print(f"  [{n['x1']:4},{n['y1']:4}][{n['x2']:4},{n['y2']:4}] {n['cls']:9} {n['text'][:40]!r}")
        if n["x1"] < 0 or n["y1"] < 0 or n["x2"] > w or n["y2"] > h:
            problems.append(f"OUT-OF-SCREEN {n['text'][:20]!r} {n['x1']},{n['y1']},{n['x2']},{n['y2']}")
        if n["y2"] == h or n["x2"] == w:
            problems.append(f"CLIPPED-EDGE {n['text'][:20]!r} 底/右边正好压在屏幕边缘")
    # 文本节点被视口/裁剪吃掉半截时，bounds 高度会明显矮于同屏其它文本
    # （实测：观众加入页横屏下说明行只剩 3px 高，而正常一行是 63px）。
    # 用**中位数**而不是最大值当"正常行高"：一屏里只要有一个多行 EditText（126px），
    # 拿它当基准就会把旁边那颗 42px 的小标签报成"被裁"（实测首页/放映厅各报 6 条假的）。
    hs = sorted(n["y2"] - n["y1"] for n in ns)
    tall = hs[len(hs) // 2] if hs else 0
    for n in ns:
        # 高度 0 的节点是"根本没量到边界"（WebView 把 video 控件报成空矩形），
        # 不是被裁 —— 上一版一屏报了六条假的 SQUISHED。
        if (n["y2"] - n["y1"]) <= 0 or (n["x2"] - n["x1"]) <= 0:
            continue
        if tall and (n["y2"] - n["y1"]) < tall * 0.45:
            problems.append(
                f"SQUISHED {n['text'][:20]!r} 高 {n['y2'] - n['y1']}px，同屏正常文本 {tall}px —— 被裁了")
    for i in range(len(ns)):
        for j in range(i + 1, len(ns)):
            a, b = ns[i], ns[j]
            it = intersect(a, b)
            small = min(area(a), area(b)) or 1
            if it / small > 0.12:
                problems.append(
                    f"OVERLAP {a['text'][:16]!r} × {b['text'][:16]!r} 相交 {it * 100 // small}%")

    nbytes = cap()
    print(f"   截图：{args.out} ({nbytes} bytes)")

    if not ns:
        # **零节点不是"没问题"，是没量到**：uiautomator 在动画中/控件全收起时
        # 会返回一棵没有文本的空树，上一版就在这里报了个假绿。
        # ⚠ 更阴的一种（2026-09-26 反复撞上）：**有视频流正在渲染**时窗口永远进不了 idle，
        #   dump 直接给空树 —— 实测连点屏幕唤出控件 5 次全空，而截图里画面明明在跑。
        #   ⇒ 观众端在放画面期间的排版判据只能走**截图**（网页侧走 CDP 读矩形，
        #   见 drive_web_landscape.py）。空树既不能读成"界面坏了"，也不能读成"没问题"。
        print("== 量具失效：这一屏一个文本节点都没有（控件已收起？dump 失败？）==")
        return 2
    if problems:
        print("== 几何问题 ==")
        for p in problems:
            print("  " + p)
    else:
        print("== 几何：没有越界/压字 ==")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
