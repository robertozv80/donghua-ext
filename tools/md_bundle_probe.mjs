#!/usr/bin/env node
// 1) Dump COMPLETO de la respuesta de playback y del JSON descifrado.
// 2) Descarga el bundle JS de bysekoze.com y busca endpoints de subtítulos.
import zlib from "node:zlib";
import crypto from "node:crypto";

const API_BASE = "https://bysekoze.com";
const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const HEADERS = {
  "User-Agent": UA,
  Accept: "application/json",
  Origin: MAIN,
  Referer: MAIN + "/",
  "X-Embed-Origin": MAIN,
  "X-Embed-Referer": MAIN + "/",
  "X-Embed-Parent": MAIN + "/",
};

async function req(url, { method = "GET", headers = {}, body = null, timeoutMs = 25000 } = {}) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, { method, headers, signal: ctrl.signal, body: body == null ? undefined : typeof body === "string" ? body : JSON.stringify(body) });
    const buf = Buffer.from(await r.arrayBuffer());
    let text = "";
    try { text = (r.headers.get("content-encoding") === "gzip" && buf.length && buf[0] === 0x1f) ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
    return { status: r.status, headers: r.headers, text, buf };
  } catch (e) { return { status: 0, text: "", buf: Buffer.alloc(0) }; } finally { clearTimeout(t); }
}
function rotl32(x, n) { return (x << n) | (x >>> (32 - n)); }
function mixRound(t) {
  t[0] = (t[0] + t[1]) | 0; t[3] = rotl32(t[3] ^ t[0], 16);
  t[2] = (t[2] + t[3]) | 0; t[1] = rotl32(t[1] ^ t[2], 12);
  t[0] = (t[0] + t[1]) | 0; t[3] = rotl32(t[3] ^ t[0], 8);
  t[2] = (t[2] + t[3]) | 0; t[1] = rotl32(t[1] ^ t[2], 7);
}
function powHash(data) {
  const e = [1779033703, 3144134277, 1013904242, 2773480762];
  for (const b of data) { e[0] = (e[0] + (b & 0xff)) | 0; e[0] = rotl32(e[0], 7); mixRound(e); }
  for (let i = 0; i < 8; i++) mixRound(e);
  const size = 512, mask = size - 1, lr = 2654435761, hr = 2246822519;
  const table = new Int32Array(size);
  for (let i = 0; i < size; i++) { mixRound(e); table[i] = (e[0] ^ e[2]) | 0; }
  for (let rep = 0; rep < 2; rep++) for (let s = 0; s < size; s++) {
    let c = (table[s] + table[table[s] & mask]) | 0;
    c = rotl32(c, 13);
    c = (c ^ Math.imul(table[(s + 1) & mask], lr)) | 0;
    table[s] = c; e[0] = (e[0] ^ c) | 0; mixRound(e);
  }
  const out = new Int32Array(8);
  const group = size / 8;
  for (let i = 0; i < 8; i++) {
    mixRound(e);
    let acc = e[0];
    for (let j = 0; j < group; j++) {
      const d = table[i * group + j];
      acc = (acc + d) | 0; acc = rotl32(acc, 5); acc = (acc ^ Math.imul(d, hr)) | 0;
    }
    out[i] = (acc ^ e[2]) | 0;
  }
  return out;
}
function solvePow(nonce, difficulty) {
  const prefix = Buffer.from(`${nonce}:`, "utf8");
  const t0 = Date.now();
  let c = 0;
  for (;;) {
    let t = 0;
    const h = powHash(new Uint8Array(Buffer.concat([prefix, Buffer.from(String(c))])));
    for (const w of h) { if (w === 0) { t += 32; continue; } t += Math.clz32(w >>> 0); break; }
    if (t >= difficulty) return String(c);
    c++;
    if (c % 2048 === 0 && Date.now() - t0 > 40000) return null;
  }
}
function b64urlDecode(s) {
  let t = s.trim().replace(/-/g, "+").replace(/_/g, "/");
  while (t.length % 4 !== 0) t += "=";
  return Buffer.from(t, "base64");
}

const slug = process.argv[2] || "combat-continent-2";
const epArg = process.argv[3] || "87";
async function getHtml(url) { return (await req(url, { headers: { "User-Agent": UA } })).text || ""; }
function unpackOne(line) {
  try {
    if (!line.endsWith("))")) return null;
    const out = eval("(" + line.slice(5, -1) + ")");
    if (typeof out !== "string" || out.includes("function(p,a,c,k,e")) return null;
    return out;
  } catch { return null; }
}
const dDoc = await getHtml(`${MAIN}/donghua/${slug}`);
const eps = [...dDoc.matchAll(/href="([^"]*\/ver\/[^"]+)"/g)].map((m) => m[1]);
const epUrl = (eps[parseInt(epArg) - 1] || eps[0]).startsWith("http") ? eps[parseInt(epArg) - 1] : MAIN + eps[parseInt(epArg) - 1];
const eDoc = await getHtml(epUrl);
let code = null;
for (const m of eDoc.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
  const u = unpackOne(m[0]);
  if (!u) continue;
  const c = u.match(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/);
  if (c) { code = c[1]; break; }
}
console.log(`code: ${code} (ep ${epUrl})`);

// flujo
const captcha = await req(`${API_BASE}/api/videos/${code}/embed/captcha`, { method: "POST", headers: HEADERS, body: {} });
const nonce = /"pow_nonce"\s*:\s*"([^"]+)"/.exec(captcha.text)?.[1];
const difficulty = Number(/"pow_difficulty"\s*:\s*(\d+)/.exec(captcha.text)?.[1] ?? 0);
const powToken = /"pow_token"\s*:\s*"([^"]+)"/.exec(captcha.text)?.[1];
const solution = solvePow(nonce, difficulty);
console.log(`pow: ${solution}`);
const verify = await req(`${API_BASE}/api/videos/${code}/embed/captcha/verify`, { method: "POST", headers: HEADERS, body: { pow_token: powToken, solution } });
const token = /"token"\s*:\s*"([^"]+)"/.exec(verify.text)?.[1];
const pb = await req(`${API_BASE}/api/videos/${code}/embed/playback`, {
  method: "POST",
  headers: { ...HEADERS, "X-Captcha-Token": token },
  body: { fingerprint: { token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 } },
});
console.log(`\n===== respuesta de playback COMPLETA (${pb.text.length} chars) =====`);
console.log(pb.text);
const enc = JSON.parse(pb.text)?.playback;
const version = Number(String(enc.version).match(/\d+/)?.[0] ?? 0);
const selected = [];
for (const idx of [version, 31 - version]) {
  const p = idx >= 1 && idx <= enc.key_parts.length ? enc.key_parts[idx - 1] : null;
  if (p) selected.push(p);
}
if (!selected.length) selected.push(...enc.key_parts);
const key = Buffer.concat(selected.map(b64urlDecode));
const decipher = crypto.createDecipheriv("aes-256-gcm", key, b64urlDecode(enc.iv));
const payload = b64urlDecode(enc.payload);
decipher.setAuthTag(payload.subarray(payload.length - 16));
const plain = Buffer.concat([decipher.update(payload.subarray(0, payload.length - 16)), decipher.final()]);
console.log(`\n===== JSON descifrado COMPLETO (${plain.length} chars) =====`);
console.log(plain.toString("utf8"));

// ==== bundle JS de bysekoze ====
console.log(`\n===== bundle de bysekoze =====`);
const embedPage = await req(`https://bysekoze.com/e/${code}`, { headers: { ...HEADERS, Referer: "https://www.mundodonghua.com/", Accept: "text/html" } });
console.log(`embed page: ${embedPage.status} (${embedPage.buf.length} bytes)`);
const html = embedPage.text || "";
console.log(html.slice(0, 600));
const scripts = [...html.matchAll(/<script[^>]*src="([^"]+\.js[^"]*)"/g)].map((m) => m[1]);
console.log(`scripts: ${scripts.join(", ")}`);
for (const s of scripts) {
  const jsUrl = new URL(s, "https://bysekoze.com/").href;
  const js = await req(jsUrl, { headers: { Referer: "https://bysekoze.com/" } });
  const jt = js.text || "";
  console.log(`\n--- ${jsUrl.slice(0, 90)} -> ${js.status} (${jt.length} chars)`);
  // buscar pistas de subtítulos
  for (const kw of ["subtitle", "caption", "WebVTT", "\\.vtt", "text/vtt", "track"]) {
    const re = new RegExp(kw, "gi");
    const hits = [...jt.matchAll(re)].length;
    if (hits) console.log(`  '${kw}': ${hits} hits`);
  }
  // contexto alrededor de "vtt"
  for (const m of jt.matchAll(/.{160}[Vv]tt.{200}/g)) {
    console.log(`  ...${m[0]}...`);
    break;
  }
  for (const m of jt.matchAll(/.{120}subtitle.{180}/gi)) {
    console.log(`  ...${m[0]}...`);
    break;
  }
}
