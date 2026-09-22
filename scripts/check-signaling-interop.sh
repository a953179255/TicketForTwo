#!/usr/bin/env bash
# 信令格式跨语言闭环校验：Kotlin 编 → 浏览器同源代码解，Node 编 → Kotlin 解。
#
# 方向一用 **sha256 比对**而不是比长度：长度相同但行尾 CRLF/LF 不一样，
# WebRTC 会直接拒绝解析，而长度检查发现不了。
#
# 用法：bash scripts/check-signaling-interop.sh
set -euo pipefail
cd "$(dirname "$0")/.."

CACHES="$HOME/.gradle/caches/modules-2/files-2.1"
KT="$PWD/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
TEST="$PWD/app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"
STDLIB=$(find "$CACHES" -name "kotlin-stdlib-2.4.10.jar" | head -1)

[ -d "$TEST" ] || { echo "先跑 ./gradlew :app:compileDebugUnitTestKotlin"; exit 1; }
CP="$TEST:$KT:$STDLIB"

echo "==> 方向一：Kotlin 编码 → 浏览器同源代码解码"
TOKEN=$(java -cp "$CP" com.ticketfortwo.app.rtc.CodecProbeKt encode | tail -1)
META=$(java -cp "$CP" com.ticketfortwo.app.rtc.CodecProbeKt meta | tail -1)
echo "    token ${#TOKEN} 字符 · 期望 $META"

node --input-type=module -e '
import { fromUrl } from "./viewer/signaling.mjs";
const [token, meta] = process.argv.slice(1);
const [expLen, expSha] = meta.split(" ");
const env = await fromUrl("https://share.local/#t2=" + token);
if (!env) { console.error("    FAIL 解不出内容"); process.exit(1); }
const buf = new TextEncoder().encode(env.sdp);
const sha = [...new Uint8Array(await crypto.subtle.digest("SHA-256", buf))]
  .map(b => b.toString(16).padStart(2, "0")).join("");
const checks = {
  "room": env.room === "K7M2",
  "kind": env.kind === "Offer",
  "字节数一致": buf.length === Number(expLen),
  "sha256 一致(字节级)": sha === expSha,
  "含 H264 rtpmap": env.sdp.includes("H264/90000"),
  "含候选行": env.sdp.includes("a=candidate:"),
  "保留 CRLF": env.sdp.includes("\r\n"),
};
console.log("    room=" + env.room + " kind=" + env.kind + " sdp=" + buf.length + "B sha=" + sha.slice(0,16) + "…");
for (const [k, v] of Object.entries(checks)) console.log(`    ${v ? "ok  " : "FAIL"} ${k}`);
process.exit(Object.values(checks).every(Boolean) ? 0 : 1);
' "$TOKEN" "$META"

echo "==> 方向二：Node 编码 → Kotlin 解码"
ANSWER=$(node --input-type=module -e '
import { encodeToken } from "./viewer/signaling.mjs";
const sdp = "v=0\r\no=- 999 2 IN IP4 127.0.0.1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:0\r\na=recvonly\r\na=candidate:j 1 udp 1685987071 198.51.100.9 50001 typ srflx\r\n";
process.stdout.write(await encodeToken({ room: "Q9Z1", kind: "Answer", sdp }));
')
echo "    token ${#ANSWER} 字符"
OUT=$(echo "$ANSWER" | java -cp "$CP" com.ticketfortwo.app.rtc.CodecProbeKt decode)
echo "$OUT" | sed 's/^/    /'
echo "$OUT" | grep -q "^room=Q9Z1$" && echo "$OUT" | grep -q "^kind=Answer$" \
  && echo "$OUT" | grep -q "^hasCandidates=true$" && echo "    PASS JS→Kotlin" \
  || { echo "    FAIL JS→Kotlin"; exit 1; }

echo "==> 双向闭环通过"
