#!/usr/bin/env bash
# 跑 JVM 单元测试。
#
# 为什么不用 ./gradlew testDebugUnitTest：在当前环境（AGP 9 内置 Kotlin 编译 + Gradle 9.6
# 的 test worker + 项目位于非 ASCII 路径 G:\工作台）下，test worker 会抛
# ClassNotFoundException，虽然 classpath 里确实带着编译产物目录。原因尚未定位，
# 见 docs/PLAN.md 的未决项。这里直接用 JUnitCore 跑已编译好的 class，效果等价。
#
# 用法： bash scripts/run-unit-tests.sh
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT_DIR="$(pwd)"
CACHES="$HOME/.gradle/caches/modules-2/files-2.1"
KT_CLASSES="$PROJECT_DIR/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
TEST_CLASSES="$PROJECT_DIR/app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"

echo "==> 编译主代码与测试代码"
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --console=plain -q

jar_of() { find "$CACHES" -name "$1" 2>/dev/null | head -1; }
JUNIT=$(jar_of "junit-4.13.2.jar")
HAMCREST=$(jar_of "hamcrest-core-1.3.jar")
STDLIB=$(jar_of "kotlin-stdlib-2.4.10.jar")
[ -n "$STDLIB" ] || STDLIB=$(find "$CACHES" -name "kotlin-stdlib-*.jar" ! -name "*sources*" | head -1)
for v in JUNIT HAMCREST STDLIB; do
  [ -n "${!v}" ] || { echo "找不到依赖 jar: $v"; exit 1; }
done

CP="$TEST_CLASSES:$KT_CLASSES:$STDLIB:$JUNIT:$HAMCREST"

# libwebrtc 的 AAR 里 classes.jar 是嵌着的，Gradle 缓存里没有现成的 jar 可指。
# StatsParseTest 要在 JVM 上直接 new org.webrtc.RTCStats / RTCStatsReport ——
# 这两个类是纯数据壳（没有 native 方法、不触发 NativeLibrary.initialize），
# 所以不必真加载 so，只要类能装上就能测 stats 解析。
WEBRTC_JAR="$PROJECT_DIR/app/build/test-libs/webrtc-classes.jar"
AAR=$(jar_of "android-150.7871.01.aar")
if [ -n "$AAR" ]; then
  mkdir -p "$(dirname "$WEBRTC_JAR")"
  if [ ! -f "$WEBRTC_JAR" ] || [ "$AAR" -nt "$WEBRTC_JAR" ]; then
    unzip -o -q -j "$AAR" classes.jar -d "$(dirname "$WEBRTC_JAR")" && mv "$(dirname "$WEBRTC_JAR")/classes.jar" "$WEBRTC_JAR"
  fi
  CP="$CP:$WEBRTC_JAR"
  echo "==> libwebrtc classes: $WEBRTC_JAR"
else
  echo "!! 找不到 libwebrtc AAR，StatsParseTest 会因缺类失败" >&2
fi

# 从 .class 文件名还原出全限定类名，只取带 JUnit @Test 的
CLASSES=$(cd "$TEST_CLASSES" && find . -name "*Test.class" ! -name "*\$*" \
  | sed 's|^\./||; s|\.class$||; s|/|.|g')

if [ -z "$CLASSES" ]; then echo "没有找到测试类"; exit 1; fi

echo "==> 运行：$(echo "$CLASSES" | tr '\n' ' ')"
java -Dfile.encoding=UTF-8 -cp "$CP" org.junit.runner.JUnitCore $CLASSES
