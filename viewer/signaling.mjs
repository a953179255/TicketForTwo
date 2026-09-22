/* 双人票信令编解码 —— 浏览器与 Node 共用同一份实现。
 *
 * 为什么单独成文件：这是"零服务器"这条路的承重墙格式。Kotlin 侧
 * (app/src/main/java/.../rtc/SignalingCodec.kt) 编出来的 token，浏览器要能解开，
 * 反向也要成立。如果测试里复制一份 JS 实现，测试通过并不代表网页能用。
 * 所以浏览器 import 这里，Node 互操作脚本也 import 这里，只有一份实现。
 *
 * 格式契约（必须与 Kotlin 侧逐字节一致）：
 *   payload = room + "\u0001" + ("Offer"|"Answer") + "\u0001" + sdp
 *   token   = base64url( gzip( utf8(payload) ) )      无 '=' padding
 *   链接     = <base>?...#t2=<token>                  放 fragment，不进服务器日志
 */

export const SEP = "\u0001";
export const TOKEN_KEY = "t2";

export function bytesToB64url(bytes) {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function b64urlToBytes(s) {
  let t = String(s).replace(/-/g, "+").replace(/_/g, "/");
  const pad = t.length % 4 ? "=".repeat(4 - (t.length % 4)) : "";
  const bin = atob(t + pad);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

export async function gunzip(bytes) {
  const ds = new DecompressionStream("gzip");
  const buf = await new Response(
    new Blob([bytes]).stream().pipeThrough(ds)
  ).arrayBuffer();
  return new TextDecoder("utf-8").decode(buf);
}

export async function gzip(text) {
  const cs = new CompressionStream("gzip");
  const buf = await new Response(
    new Blob([new TextEncoder().encode(text)]).stream().pipeThrough(cs)
  ).arrayBuffer();
  return new Uint8Array(buf);
}

export async function decodeToken(token) {
  const raw = await gunzip(b64urlToBytes(token));
  // 注意：JS 的 split(sep, limit) 会**截断**而不是把余下拼回来，
  // 而 SDP 本身可能含 \u0001 之外的任意字符，所以手工切前两个分隔符。
  const i1 = raw.indexOf(SEP);
  if (i1 < 0) return null;
  const i2 = raw.indexOf(SEP, i1 + 1);
  if (i2 < 0) return null;
  return { room: raw.slice(0, i1), kind: raw.slice(i1 + 1, i2), sdp: raw.slice(i2 + 1) };
}

export async function encodeToken(env) {
  return bytesToB64url(
    await gzip(env.room + SEP + env.kind + SEP + env.sdp)
  );
}

/** 从 URL 里取 token；只认我们自己的 key。 */
export function tokenFromUrl(url) {
  const frag = String(url).split("#")[1] || "";
  for (const kv of frag.split(/[&;]/)) {
    if (kv.startsWith(TOKEN_KEY + "=")) return kv.slice(TOKEN_KEY.length + 1);
  }
  return null;
}

export async function fromUrl(url) {
  const t = tokenFromUrl(url);
  return t ? await decodeToken(t) : null;
}

export async function toUrl(base, env) {
  return `${base}#${TOKEN_KEY}=${await encodeToken(env)}`;
}
