#!/usr/bin/env python3
"""把调试包装机到真机，并且**先告诉你它会毁掉什么**。

为什么单独一个脚本、还要 `--yes`：
  * 真机上现在那个包是 **release 签名**（正式签名从 local.properties 读密钥），
    调试包签名不同，直接覆盖安装会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
    唯一出路是先卸载 —— 而**卸载会清掉 App 的全部数据**（分享设置、上次连接记录）。
    这是不可逆的，所以这里不做"顺手帮你清了"这种事，必须显式确认。
  * 魅族 Flyme 还有一道安装闸门：不点"允许"就 `INSTALL_FAILED_USER_RESTRICTED`。
    脚本会重试一次并提示去手机上看弹窗。

跑法：
    python scripts/install_phone.py            # 只体检：装的是什么版本、签名对不对
    python scripts/install_phone.py --yes      # 卸载旧包 + 装调试包 + 给权限
"""
import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import t2device

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
REL = os.path.join(HERE, "..", "app", "build", "outputs", "apk", "release", "app-release.apk")
DBG = os.path.join(HERE, "..", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
PKG = "com.ticketfortwo.app"
ADB = t2device.ADB
ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT="5039", MSYS_NO_PATHCONV="1")


def sh(*args, timeout=180):
    return subprocess.run(args, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", env=ENV, timeout=timeout)


def phone_serial():
    out = sh(ADB, "devices").stdout
    devs = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]
    phones = [d for d in devs if not d.startswith("emulator-")]
    if not phones:
        return None
    return phones[0]


def dump_pkg(serial):
    """读当前包的版本与安装来源。签名不同这件事没法从 dumpsys 直接看出来，
    但 installerPackageName 能给出线索（自己 build 的是 shell/adb，正式包是应用商店）。"""
    out = sh(ADB, "-s", serial, "shell", "dumpsys", "package", PKG).stdout
    return {"version": re.findall(r"versionName=(\S+)", out)[:1],
            "installer": re.findall(r"installerPackageName=(\S+)", out)[:1]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--yes", action="store_true", help="同意安装（默认原地覆盖，不清数据）")
    ap.add_argument("--wipe", action="store_true",
                    help="仅在签名真的对不上时才需要：先卸载旧包（会清掉 App 数据）")
    a = ap.parse_args()

    s = phone_serial()
    if not s:
        print("没找到真机。请确认：USB 已插、手机上允许了 USB 调试、弹窗点了「允许」。")
        return 1
    model = sh(ADB, "-s", s, "shell", "getprop", "ro.product.model").stdout.strip()
    api = sh(ADB, "-s", s, "shell", "getprop", "ro.build.version.sdk").stdout.strip()
    print(f"== 真机 {s}｜{model}｜API {api} ==")

    info = dump_pkg(s)
    print(f"   当前包：versionName={info['version'][0] if info['version'] else '未安装'}"
          f"  安装来源={info['installer'][0] if info['installer'] else '-'}")
    if not os.path.exists(REL):
        print(f"   没有正式包 {os.path.basename(REL)}，先 ./gradlew :app:assembleRelease")
        return 1
    print(f"   待装：{os.path.basename(REL)}  {os.path.getsize(REL) // (1024 * 1024)} MB"
          f"（正式签名，与手机上那个同源 ⇒ 原地覆盖，不清数据）")

    if not a.yes:
        print("\n（体检模式，什么都没动。要装请加 --yes。默认走原地覆盖；"
              "只有真撞上签名不一致才需要卸载，而那种情况脚本会停下来问，不自己动手。）")
        return 0

    if a.wipe:
        print("\n== 卸载旧包（数据会清）==")
        print("   " + sh(ADB, "-s", s, "uninstall", PKG).stdout.strip().replace("\n", " / "))

    print("\n== 原地覆盖安装 ==")
    r = sh(ADB, "-s", s, "install", "-r", "-t", REL)
    out = (r.stdout + r.stderr).strip()
    print("   " + out.replace("\n", " / "))
    if "USER_RESTRICTED" in out or "USER_CONFIRMATION" in out:
        # Flyme 的闸门：手机上会弹一个"是否允许安装"，不点就永远失败。
        print("   ⚠ 手机上有安装确认弹窗，点允许后我再试一次…")
        import time
        time.sleep(12)
        r = sh(ADB, "-s", s, "install", "-r", "-t", REL)
        print("   " + (r.stdout + r.stderr).strip().replace("\n", " / "))
    if "UPDATE_INCOMPATIBLE" in out or "signatures do not match" in out:
        # 只有真撞上签名不一致才走到这一步 —— 那意味着必须卸载、必须清数据，
        # 是不可逆的，所以这里停下来问，不替用户决定。
        print("⚠ 签名对不上，只能先卸载再装，而**卸载会清掉 App 全部数据**"
              "（分享设置、上次连接记录）。")
        print("   确认要做，就再跑一次：python scripts/install_phone.py --yes --wipe")
        return 1
    if "Success" not in out:
        return 1

    print("== 给权限（免得测试被弹窗打断）==")
    for p in ("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"):
        rr = sh(ADB, "-s", s, "shell", "pm", "grant", PKG, p)
        print(f"   {p.split('.')[-1]}: {(rr.stdout + rr.stderr).strip() or 'ok'}")
    print("\n装好了。接着跑：python scripts/run_phone_round.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
