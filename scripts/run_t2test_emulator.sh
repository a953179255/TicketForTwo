#!/usr/bin/env bash
# 启动 TicketForTwo 专用模拟器 t2test（x86_64 / API 36）。
#
# 为什么单独一个模拟器：edgeai 那个正被另一个 agent 用来调试 HaoAI，
# 两边同时 adb 会互相打断（清 logcat、改无障碍设置、重启进程都会串台）。
#
# 为什么写在仓库里而不是临时目录：这是跨会话要复用的脚本，
# 放 %TEMP% 或工作区根的临时区会被别的 agent 清掉。
#
# 项目收尾时这个 AVD 要删掉，只保留 edgeai（用户明确要求）。
set -u

export ANDROID_AVD_HOME='G:\Android\avd'
export ANDROID_HOME='G:\Android\sdk-ext'
export ANDROID_SDK_ROOT='G:\Android\sdk-ext'
# 本机 adb 走非默认端口；默认 5037 会起重定向到别的 server，把两个项目串起来。
export ANDROID_ADB_SERVER_PORT="${ANDROID_ADB_SERVER_PORT:-5039}"

EMU='/g/Android/sdk-ext/emulator/emulator.exe'
LOG='G:/Android/avd/t2test-launch.log'

if [ ! -x "$EMU" ]; then
  echo "找不到 emulator：$EMU" >&2
  exit 1
fi

# -gpu host：AVD 配置里的默认值在这台机上跑不出玻璃所需的 RenderNode 离屏层，
# 必须显式覆盖成 host（上次建完就是这样才起得来）。
# -no-window：不抢焦点，界面靠 adb uiautomator + screencap 驱动与验收。
"$EMU" -avd t2test -no-window -no-boot-anim -gpu host >"$LOG" 2>&1 &
echo "已在后台启动 t2test，日志：$LOG"

ADB='/c/Android/sdk/platform-tools/adb.exe'
HERE="$(cd "$(dirname "$0")" && pwd)"
echo "等待开机（冷启动实测 39–97 秒波动）..."
# 轮询**按 AVD 名**问，不问固定端口：t2test 可能拿到 5554（edgeai 没在跑时），
# 写死 5556 会既等不到、又在别的机器上误连一台。
for _ in $(seq 1 120); do
  if SERIAL=$(python "$HERE/t2device.py" 2>/dev/null) && [ -n "$SERIAL" ]; then
    if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      echo "t2test 已就绪：$SERIAL"
      exit 0
    fi
  fi
  sleep 4
done
echo "超时：没等到 t2test 开机完成，查看 $LOG" >&2
tail -20 "$LOG"
exit 1
