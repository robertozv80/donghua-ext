// Volca el JSON de playback desencriptado COMPLETO y el master m3u8 completo.
import crypto from "node:crypto";
import zlib from "node:zlib";

const API_BASE = "https://bysekoze.com";
const EMBED_ORIGIN = "https://www.mundodonghua.com";
const EMBED_REFERER = "https://www.mundodonghua.com/";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

const rotl32 = (x, n) => ((x << n) | (x >>> (32 - n))) >>> 0;
function mixRound(t) {
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 16);
  t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 12);
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 8);
  t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 7);
}
function powHash(data) {
  const e = [1779033703, (3144134277) >>> 0, 1013904242, (2773480762) >>> 0];
  for (const b of data) { e[0] = (e[0] + b) >>> 0; e[0] = rotl32(e[0], 7); mixRound(e); }
  for (let i = 0; i < 8; i++) mixRound(e);
  const size = 512, mask = size - 1;
  const lr = (2654435761) >>> 0, hr = (2246822519) >>> 0;
  const table = new Array(size);
  for (let i = 0; i < size; i++) { mixRound(e); table[i] = (e[0] ^ e[2]) >>> 0; }
  for (let r = 0; r < 2; r++)
    for (let s = 0; s < size; s++) {
      let c = (table[s] + table[table[s] & mask]) >>> 0;
      c = rotl32(c, 13);
      c = (c ^ (Math.imul(table[(s + 1) & mask], lr))) >>> 0;
      table[s] = c; e[0] = (e[0] ^ c) >>> 0; mixRound(e);
    }
  const out = new Array(8);
  const group = size / 8;
  for (let i = 0; i < 8; i++) {
    mixRound(e);
    let acc = e[0];
    const base = i * group;
    for (let j = 0; j < group; j++) {
      const d = table[base + j];
      acc = (acc + d) >>> 0; acc = rotl32(acc, 5);
      acc = (acc ^ (Math.imul(d, hr))) >>> 0;
    }
    out[i] = (acc ^ e[2]) >>> 0;
  }
  return out;
}
const leadingZeroBits = (w) => {
  let t = 0;
  for (const x of w) { if (x === 0) { t += 32; continue; } t = Math.clz32(x); break; }
  return t;
};
function solvePow(nonce, difficulty) {
  const prefix = Buffer.from(nonce + ":", "utf8");
  const t0 = Date.now();
  let c = 0;
  while (true) {
    if (leadingZeroBits(powHash(Buffer.concat([prefix, Buffer.from(String(c))]))) >= difficulty) return String(c);
    c++;
    if (c % 100000 === 0 && Date.now() - t0 > 60000) return null;
  }
}
async function req(url, { method = "GET", headers = {}, body = null } = {}) {
  const h = { "User-Agent": UA, ...headers };
  const r = await fetch(url, {
    method, headers: h,
    body: body === null ? undefined : typeof body === "string" ? body : JSON.stringify(body),
    redirect: "manual",
  });
  const buf = Buffer.from(await r.arrayBuffer());
  let text;
  try { text = (r.headers.get("content-encoding") === "gzip" && buf.length && buf[0] === 0x1f) ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); }
  catch { text = ""; }
  return { status: r.status, headers: r.headers, text, buf };
}
const b64urlDecode = (s) => Buffer.from(s.trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");

async function byseFlow(code) {
  const headers = { Accept: "application/json", Origin: EMBED_ORIGIN, Referer: EMBED_REFERER, "X-Embed-Origin": EMBED_ORIGIN, "X-Embed-Referer": EMBED_REFERER, "X-Embed-Parent": EMBED_REFERER };
  const cap = await req(`${API_BASE}/api/videos/${code}/embed/captcha`, { method: "POST", headers, body: {} });
  if (cap.status !== 200) return { err: `captcha ${cap.status}` };
  const { pow_nonce: nonce, pow_difficulty: difficulty, pow_token: powToken } = JSON.parse(cap.text);
  const solution = solvePow(nonce, difficulty);
  if (!solution) return { err: "pow timeout" };
  const ver = await req(`${API_BASE}/api/videos/${code}/embed/captcha/verify`, { method: "POST", headers, body: { pow_token: powToken, solution } });
  if (ver.status !== 200) return { err: `verify ${ver.status}` };
  const token = JSON.parse(ver.text).token;
  const fingerprint = { token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
  const pb = await req(`${API_BASE}/api/videos/${code}/embed/playback`, { method: "POST", headers: { ...headers, "X-Captcha-Token": token }, body: { fingerprint } });
  if (pb.status !== 200) return { err: `playback ${pb.status}` };
  const enc = JSON.parse(pb.text).playback;
  const keyParts = enc.key_parts;
  const version = typeof enc.version === "number" ? enc.version : parseInt(String(enc.version).match(/\d+/)?.[0] ?? "0", 10);
  const sel = [version, 31 - version].map((i) => (i >= 1 && i <= keyParts.length ? keyParts[i - 1] : null)).filter(Boolean);
  const key = Buffer.concat(sel.map(b64urlDecode));
  const iv = b64urlDecode(enc.iv);
  const payload = b64urlDecode(enc.payload);
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(payload.subarray(payload.length - 16));
  const plain = Buffer.concat([decipher.update(payload.subarray(0, payload.length - 16)), decipher.final()]);
  return { token, json: JSON.parse(plain.toString("utf8")) };
}

const codes = process.argv.slice(2);
if (!codes.length) codes.push("rgcgmsu5j63y");

for (const code of codes) {
  console.log(`\n############ code=${code} ############`);
  const res = await byseFlow(code);
  if (res.err) { console.log(`  flujo fallido: ${res.err}`); continue; }
  console.log("=== JSON desencriptado COMPLETO ===");
  console.log(JSON.stringify(res.json, null, 2).slice(0, 4000));
  const m3u8Src = res.json.sources?.find((s) => s.mime_type?.includes("mpegurl") || s.url?.includes(".m3u8"));
  if (!m3u8Src?.url) { console.log("  sin m3u8"); continue; }
  console.log(`\n=== MASTER: ${m3u8Src.url} ===`);
  const mr = await req(m3u8Src.url, { headers: { Referer: "https://bysekoze.com/" } });
  console.log(`master status=${mr.status}`);
  console.log(mr.text.slice(0, 6000));
}
