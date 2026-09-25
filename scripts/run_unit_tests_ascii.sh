#!/usr/bin/env bash
# 在 ASCII 路径下跑单元测试。
#
# 为什么要绕这一圈（2026-09-25 实测）：项目放在 `G:\工作台\` —— 带中文的路径下，
# Gradle 的测试工作进程能起来、JUnit 框架能加载，但**每一个测试类都 ClassNotFoundException**
# （连今天上午还全绿的既有用例也一样），`clean` / `--no-configuration-cache` / 换 JDK 21 都救不回来。
# 同一份代码复制到 `C:\Users\95317\t2ascii` 之后，47 条用例正常执行。
# 也就是说这是**量具坏了，不是代码坏了** —— 别把它当成"我的改动把测试搞崩了"。
#
# 用法：./scripts/run_unit_tests_ascii.sh            # 跑全部
#      ./scripts/run_unit_tests_ascii.sh MediaSnifferTest   # 只跑某个类
set -euo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd)"
MIRROR="/c/Users/95317/t2ascii"
FILTER="${1:-}"

mkdir -p "$MIRROR"
# 只同步源码与构建脚本：build/、.git/、.dev/（含真实 SDP）一律不带过去
( cd "$SRC" && tar --exclude='./build' --exclude='./app/build' --exclude='./.git' \
    --exclude='./.dev' --exclude='./refs' -cf - . ) | ( cd "$MIRROR" && tar -xf - )

cd "$MIRROR"
if [ -n "$FILTER" ]; then
  ./gradlew :app:testDebugUnitTest --console=plain --tests "*$FILTER*"
else
  ./gradlew :app:testDebugUnitTest --console=plain
fi

echo "=== 结果汇总（$MIRROR）==="
for f in app/build/test-results/testDebugUnitTest/TEST-*.xml; do
  printf "%-58s %s\n" "$(basename "$f" .xml | sed 's/^TEST-//')" \
    "$(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f" | head -1)"
done
