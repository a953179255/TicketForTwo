#!/usr/bin/env python3
"""B 档主路径回归：厅先开（只有语音）→ 观众已进厅 → 房主**再**按「让他看我的屏幕」。

为什么单独要一个脚本：这条路径 2026-09-26 之前是**死路** —— `CallSession.startHost`
里那句 `if (isActive) return` 把刚拿到的投屏授权一起丢了（日志"已有进行中的分享，忽略本次请求"），
观众那边永远只有语音，而房主界面写着"正在分享你的手机"。修法是加轨 + 重新协商
（`attachScreenCapture`），但"重新协商有没有真的把画面送过去"只能端到端量。

前置：房主已开厅且**没有**投屏、观众已在厅里。最省事的两步：
    python scripts/open_room.py
    python scripts/drive_app_viewer.py --keep-sharing
然后跑本脚本。判据全部来自日志与像素：
    1. 房主日志出现「画面已接上，正在推给对方」（= 加轨 + startOffer 走了）
    2. 观众日志出现 VideoLayer 的 first frame（= 画面真的到了观众那边）
    3. 观众截图均值 > 20（= 不是纯黑；纯黑就是"日志说到了、屏幕没画"那一类）
    4. 房主那屏的措辞跟着翻成"正在分享"（= 界面不再说谎）
"""
import os
import subprocess
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audit_app_layout as A  # noqa: E402

HOST = "emulator-5556"   # t2test
VIEW = "emulator-5558"   # t2view
HERE = os.path.dirname(os.path.abspath(__file__))
SHOT = os.path.join(HERE, "..", ".dev", "b-tier-after.png")


def nodes(serial):
    return A.nodes(A.dump(serial))


def walk_projection_dialog(serial, limit=8):
    """过系统投屏弹窗：默认落在「Share one app」，必须改成整屏再确认。"""
    for _ in range(limit):
        texts = [n["text"] for n in nodes(serial)]
        if any("Share one app" in t for t in texts):
            A.tap_text(serial, "Share one app")
            time.sleep(1.6)
            if not A.tap_text(serial, "Share entire screen"):
                return "下拉里找不到「Share entire screen」"
            time.sleep(1.6)
            A.tap_text(serial, "Share screen")
            time.sleep(3.5)
            return "ok"
        if any(t in ("Allow", "允许") for t in texts):
            A.tap_text(serial, "Allow") if A.tap_text(serial, "Allow") else A.tap_text(serial, "允许")
            time.sleep(1.6)
            continue
        if any("双人票" in t and "Share your screen" in t for t in texts):
            time.sleep(1.2)
            continue
        return "没等到投屏弹窗"
    return "循环上限"


def main():
    print(f"== 房主 {HOST} / 观众 {VIEW} ==")
    A.sh("-s", HOST, "logcat", "-c")
    A.sh("-s", VIEW, "logcat", "-c")

    # 房主很可能还站在**放映厅**那一屏上（open_room 就停在那儿）：那颗钮在会话屏的卡片里，
    # 所以先按返回回到会话屏 —— 厅没关，只是覆盖层收起来。
    if not A.tap_text(HOST, "让他看我的屏幕", tries=2):
        print("   房主不在会话屏（多半停在放映厅）→ 按返回回到会话屏")
        A.tap_text(HOST, "返回")
        time.sleep(2)
    if not A.tap_text(HOST, "让他看我的屏幕"):
        print("FAIL  房主屏上没有「让他看我的屏幕」—— 前置不满足（没进厅 / 已经在投屏 / 版本旧）")
        return 1
    time.sleep(2)
    A.tap_text(HOST, "我知道了，继续")
    time.sleep(2.5)
    print("   投屏弹窗：", walk_projection_dialog(HOST))

    host_log = A.sh("-s", HOST, "logcat", "-d", "-s", "CallSession:V").stdout
    view_log = A.sh("-s", VIEW, "logcat", "-d", "-s", "VideoLayer:V", "ViewerSession:V").stdout
    attached = "画面已接上" in host_log
    first_frame = "first frame rendered" in view_log
    # 顶栏措辞要**轮询**：接上画面到状态机翻成"正在分享"之间有一两秒，
    # 只抓一次会抓成空表，然后报一个并不存在的缺陷。
    host_copy = []
    for _ in range(8):
        host_copy = [n["text"] for n in nodes(HOST) if n["text"] in ("正在分享", "语音连麦中")]
        if host_copy:
            break
        time.sleep(1.5)

    subprocess.run([A.ADB, "-s", VIEW, "exec-out", "screencap", "-p"], stdout=open(SHOT, "wb"), timeout=60)
    lum = None
    try:
        from PIL import Image
        im = Image.open(SHOT).convert("L")
        px = list(im.getdata())
        lum = sum(px) / len(px)
    except Exception as e:
        print("   截图读不了：", e)

    print(f"   房主日志「画面已接上」：{attached}")
    print(f"   观众 first frame：{first_frame}")
    print(f"   观众截图均值：{None if lum is None else round(lum, 1)}（纯黑=没画面）")
    print(f"   房主顶栏措辞：{host_copy}")

    bad = not attached or not first_frame or (lum is not None and lum <= 20) \
        or host_copy != ["正在分享"]
    print("FAIL  厅先开之后再开画面这条路没通" if bad else "PASS  厅先开→人进来→再开画面，观众拿到了画面")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
