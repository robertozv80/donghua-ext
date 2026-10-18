#!/usr/bin/env node
// Escanea series/peliculas de mundodonghua: flujo Fm completo por cada uno
// y reporta si el master trae #EXT-X-MEDIA:TYPE=SUBTITLES o tracks en el payload.
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

async function req(url, { method = "GET", headers = {}, body = null, timeoutMs = 20000 } = {}) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, { method, headers, signal: ctrl.signal, body: body == null ? undefined : typeof body === "string" ? body : JSON.stringify(body) });
    const buf = Buffer.from(await r.arrayBuffer());
    let text = "";
    try { text = (r.headers.get("content-encoding") === "gzip" && buf.length && buf[0] === 0x1f) ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
    return { status: r.status, text };
  } catch (e) { return { status: 0, text: "" }; } finally { clearTimeout(t); }
}
function unpackOne(line) {
  try {
    if (!line.endsWith("))")) return null;
    const out = eval("(" + line.slice(5, -1) + ")");
    if (typeof out !== "string" || out.includes("function(p,a,c,k,e")) return null;
    return out;
  } catch { return null; }
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
    if (leadingZeroBits(powHash(new Uint8Array(Buffer.concat([prefix, Buffer.from(String(c))])))) >= difficulty) return String(c);
    c++;
    if (c % 2048 === 0 && Date.now() - t0 > 40000) return null;
  }
}
function leadingZeroBits(words) {
  let t = 0;
  for (const w of words) { if (w === 0) { t += 32; continue; } t += Math.clz32(w >>> 0); break; }
  return t;
}
function b64urlDecode(s) {
  let t = s.trim().replace(/-/g, "+").replace(/_/g, "/");
  while (t.length % 4 !== 0) t += "=";
  return Buffer.from(t, "base64");
}

async function byseCode(epUrl) {
  const html = (await req(epUrl, { headers: HEADERS })).text || "";
  for (const m of html.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
    const u = unpackOne(m[0]);
    if (!u) continue;
    const c = u.match(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/);
    if (c) return c[1];
  }
  return null;
}

async function inspect(code, label) {
  const captcha = await req(`${API_BASE}/api/videos/${code}/embed/captcha`, { method: "POST", headers: HEADERS, body: {} });
  const nonce = /"pow_nonce"\s*:\s*"([^"]+)"/.exec(captcha.text)?.[1];
  const difficulty = Number(/"pow_difficulty"\s*:\s*(\d+)/.exec(captcha.text)?.[1] ?? 0);
  const powToken = /"pow_token"\s*:\s*"([^"]+)"/.exec(captcha.text)?.[1];
  if (!nonce) return `${label}: captcha sin challenge (${captcha.status})`;
  const solution = solvePow(nonce, difficulty);
  if (!solution) return `${label}: PoW timeout`;
  const verify = await req(`${API_BASE}/api/videos/${code}/embed/captcha/verify`, { method: "POST", headers: HEADERS, body: { pow_token: powToken, solution } });
  const token = /"token"\s*:\s*"([^"]+)"/.exec(verify.text)?.[1];
  if (!token) return `${label}: verify fail`;
  const pb = await req(`${API_BASE}/api/videos/${code}/embed/playback`, {
    method: "POST",
    headers: { ...HEADERS, "X-Captcha-Token": token },
    body: { fingerprint: { token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 } },
  });
  const enc = JSON.parse(pb.text)?.playback;
  if (!enc) return `${label}: playback fail`;
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
  const dec = JSON.parse(plain.toString("utf8"));
  const tracksInfo = JSON.stringify(dec.tracks ?? []);
  let out = `${label}: tracks=${tracksInfo}`;
  for (const src of dec.sources || []) {
    if (!src.url || !src.url.includes(".m3u8")) continue;
    const master = await req(src.url, { headers: { ...HEADERS, Referer: "https://bysekoze.com/" } });
    const media = [...(master.text || "").matchAll(/#EXT-X-MEDIA:([^\n]+)/g)].map((m) => m[1]).join(" || ");
    out += `\n    master ${master.status} EXT-X-MEDIA: ${media || "(ninguno)"}`;
  }
  return out;
}

// ==== descubrir contenido ====
const args = process.argv.slice(2);
let targets = [];
if (args.length) {
  targets = args.map((s) => s.split(":"));
} else {
  // muestreo: paginas del directorio (hrefs directos + badge cercano)
  for (let page = 1; page <= 2; page++) {
    const r = await req(`${MAIN}/lista-donghuas${page > 1 ? `/${page}` : ""}`, { headers: HEADERS });
    const html = r.text || "";
    for (const m of html.matchAll(/href="(\/donghua\/([^"]+))"/g)) {
      const slug = m[2];
      if (targets.some((t) => t[0] === slug)) continue;
      const around = html.slice(Math.max(0, m.index - 700), m.index + 300);
      const badge = /md-card-badge[^>]*>([^<]+)</.exec(around)?.[1]?.trim() || "";
      targets.push([slug, badge]);
    }
  }
}
console.log(`objetivos: ${targets.length}`);
let done = 0;
for (const [slug, badge] of targets) {
  if (done >= 14) break;
  const dDoc = await req(`${MAIN}/donghua/${slug}`, { headers: HEADERS });
  if (dDoc.status !== 200) continue;
  const eps = [...(dDoc.text || "").matchAll(/href="([^"]*\/ver\/[^"]+)"/g)].map((m) => m[1]);
  if (!eps.length) continue;
  const epUrl = eps[0].startsWith("http") ? eps[0] : MAIN + eps[0];
  const code = await byseCode(epUrl);
  if (!code) { continue; }
  const res = await inspect(code, `${slug}${badge ? ` [${badge}]` : ""}`);
  console.log(res);
  done++;
}
