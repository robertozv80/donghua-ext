#!/usr/bin/env node
// Flujo completo de Fm (bysekoze): captcha -> PoW -> verify -> playback
// -> AES-GCM -> m3u8 master -> #EXT-X-MEDIA de subtítulos.
// Replica mdFmoonByseExtractor del provider para inspeccionar el m3u8 master.
import zlib from "node:zlib";
import crypto from "node:crypto";

const API_BASE = "https://bysekoze.com";
const EMBED_ORIGIN = "https://www.mundodonghua.com";
const EMBED_REFERER = "https://www.mundodonghua.com/";
const PLAYER_REFERER = "https://bysekoze.com/";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

async function req(url, { method = "GET", headers = {}, body = null, timeoutMs = 25000 } = {}) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, {
      method, headers, signal: ctrl.signal,
      body: body === null || body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
    });
    const buf = Buffer.from(await r.arrayBuffer());
    let text = "";
    try { text = (r.headers.get("content-encoding") === "gzip" && buf.length && buf[0] === 0x1f) ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
    return { status: r.status, headers: r.headers, text, buf };
  } catch (e) {
    return { status: 0, headers: null, text: "", buf: Buffer.alloc(0), err: e.message };
  } finally { clearTimeout(t); }
}

// ---- PoW (port del hash custom) ----
function rotl32(x, n) { return (x << n) | (x >>> (32 - n)); }
function mixRound(t) {
  t[0] = (t[0] + t[1]) | 0; t[3] = rotl32(t[3] ^ t[0], 16);
  t[2] = (t[2] + t[3]) | 0; t[1] = rotl32(t[1] ^ t[2], 12);
  t[0] = (t[0] + t[1]) | 0; t[3] = rotl32(t[3] ^ t[0], 8);
  t[2] = (t[2] + t[3]) | 0; t[1] = rotl32(t[1] ^ t[2], 7);
}
function powHash(data) {
  const e = [1779033703, 3144134277, 1013904242, 2773480762];
  for (const b of data) {
    e[0] = (e[0] + (b & 0xff)) | 0;
    e[0] = rotl32(e[0], 7);
    mixRound(e);
  }
  repeatMix(8);
  function repeatMix(n) { for (let i = 0; i < n; i++) mixRound(e); }
  const size = 512, mask = size - 1;
  const lr = 2654435761, hr = 2246822519;
  const table = new Int32Array(size);
  for (let i = 0; i < size; i++) { mixRound(e); table[i] = (e[0] ^ e[2]) | 0; }
  for (let rep = 0; rep < 2; rep++) {
    for (let s = 0; s < size; s++) {
      let c = (table[s] + table[table[s] & mask]) | 0;
      c = rotl32(c, 13);
      c = (c ^ Math.imul(table[(s + 1) & mask], lr)) | 0;
      table[s] = c;
      e[0] = (e[0] ^ c) | 0;
      mixRound(e);
    }
  }
  const out = new Int32Array(8);
  const group = size / 8;
  for (let i = 0; i < 8; i++) {
    mixRound(e);
    let acc = e[0];
    const base = i * group;
    for (let j = 0; j < group; j++) {
      const d = table[base + j];
      acc = (acc + d) | 0;
      acc = rotl32(acc, 5);
      acc = (acc ^ Math.imul(d, hr)) | 0;
    }
    out[i] = (acc ^ e[2]) | 0;
  }
  return out;
}
function leadingZeroBits(words) {
  let total = 0;
  for (const w of words) {
    if (w === 0) { total += 32; continue; }
    total += Math.clz32(w >>> 0);
    break;
  }
  return total;
}
function solvePow(nonce, difficulty, maxMs = 45000) {
  const prefix = Buffer.from(`${nonce}:`, "utf8");
  const t0 = Date.now();
  let counter = 0;
  for (;;) {
    const input = Buffer.concat([prefix, Buffer.from(String(counter))]);
    if (leadingZeroBits(powHash(new Uint8Array(input))) >= difficulty) return String(counter);
    counter++;
    if (counter % 2048 === 0 && Date.now() - t0 > maxMs) return null;
  }
}

// ---- AES-GCM descifrado (node crypto) ----
function b64urlDecode(s) {
  let t = s.trim().replace(/-/g, "+").replace(/_/g, "/");
  while (t.length % 4 !== 0) t += "=";
  return Buffer.from(t, "base64");
}

async function fmFlow(code) {
  const headers = {
    "User-Agent": UA,
    Accept: "application/json",
    Origin: EMBED_ORIGIN,
    Referer: EMBED_REFERER,
    "X-Embed-Origin": EMBED_ORIGIN,
    "X-Embed-Referer": EMBED_REFERER,
    "X-Embed-Parent": EMBED_REFERER,
  };

  const captcha = await req(`${API_BASE}/api/videos/${code}/embed/captcha`, {
    method: "POST", headers, body: {},
  });
  console.log(`captcha: ${captcha.status} ${captcha.text.slice(0, 120)}`);
  const nonce = /"pow_nonce"\s*:\s*"([^"]+)"/.exec(captcha.text)?.[1];
  const difficulty = Number(/"pow_difficulty"\s*:\s*(\d+)/.exec(captcha.text)?.[1] ?? 0);
  const powToken = /"pow_token"\s*:\s*"([^"]+)"/.exec(captcha.text)?.[1];
  if (!nonce || !powToken) return;
  console.log(`PoW dificultad=${difficulty}`);
  const t0 = Date.now();
  const solution = solvePow(nonce, difficulty);
  console.log(`PoW solucion=${solution} en ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  if (!solution) return;

  const verify = await req(`${API_BASE}/api/videos/${code}/embed/captcha/verify`, {
    method: "POST", headers, body: { pow_token: powToken, solution },
  });
  console.log(`verify: ${verify.status} ${verify.text.slice(0, 120)}`);
  const captchaToken = /"token"\s*:\s*"([^"]+)"/.exec(verify.text)?.[1];
  if (!captchaToken) return;

  const playback = await req(`${API_BASE}/api/videos/${code}/embed/playback`, {
    method: "POST",
    headers: { ...headers, "X-Captcha-Token": captchaToken },
    body: { fingerprint: { token: captchaToken, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 } },
  });
  console.log(`playback: ${playback.status} ${playback.text.slice(0, 200)}`);
  const enc = JSON.parse(playback.text)?.playback;
  if (!enc) return;

  const keyParts = enc.key_parts;
  const version = Number(String(enc.version).match(/\d+/)?.[0] ?? 0);
  const selected = [];
  for (const idx of [version, 31 - version]) {
    const part = idx >= 1 && idx <= keyParts.length ? keyParts[idx - 1] : null;
    if (part) selected.push(part);
  }
  if (!selected.length) selected.push(...keyParts);
  const key = Buffer.concat(selected.map(b64urlDecode));
  const iv = b64urlDecode(enc.iv);
  const payload = b64urlDecode(enc.payload);
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(payload.subarray(payload.length - 16));
  const plain = Buffer.concat([decipher.update(payload.subarray(0, payload.length - 16)), decipher.final()]);
  const dec = JSON.parse(plain.toString("utf8"));
  console.log(`\ndescifrado: sources=${JSON.stringify(dec.sources?.map((s) => ({ label: s.label, height: s.height, mime: s.mime_type, url: s.url?.slice(0, 110) })), null, 1)}`);
  console.log(`tracks: ${JSON.stringify(dec.tracks)}`);

  for (const src of dec.sources || []) {
    if (!src.url) continue;
    const master = await req(src.url, { headers: { ...headers, Referer: PLAYER_REFERER } });
    console.log(`\n===== m3u8 master ${src.url.slice(0, 100)} -> ${master.status} =====`);
    console.log(master.text.slice(0, 3000));
    // #EXT-X-MEDIA de subtítulos
    for (const m of master.text.matchAll(/#EXT-X-MEDIA:([^\n]+)/g)) {
      const attrs = m[1];
      const type = /TYPE=([^,\s]+)/.exec(attrs)?.[1];
      const uri = /URI="([^"]+)"/.exec(attrs)?.[1];
      const lang = /LANGUAGE="([^"]+)"/.exec(attrs)?.[1];
      const name = /NAME="([^"]+)"/.exec(attrs)?.[1];
      console.log(`EXT-X-MEDIA: type=${type} lang=${lang} name=${name}\n  uri=${uri}`);
      if (type === "SUBTITLES" && uri) {
        const subUrl = new URL(uri, src.url).href;
        const subPlay = await req(subUrl, { headers: { Referer: PLAYER_REFERER } });
        console.log(`\n  playlist subs ${subUrl.slice(0, 110)} -> ${subPlay.status} (${subPlay.text.split("\n").length} lineas)`);
        console.log(subPlay.text.slice(0, 1500));
        // primer segmento VTT
        const firstSeg = /URI="([^"]+)"/.exec(subPlay.text)?.[1];
        if (firstSeg) {
          const segUrl = new URL(firstSeg, subUrl).href;
          const seg = await req(segUrl, { headers: { Referer: PLAYER_REFERER } });
          console.log(`\n  segmento VTT -> ${seg.status} (${seg.text.length} chars)`);
          console.log(seg.text.slice(0, 800));
        }
      }
    }
  }
}

// obtener codigo bysekoze de un episodio
const slug = process.argv[2] || "combat-continent-2";
const epArg = process.argv[3] || "87";
const MAIN = "https://www.mundodonghua.com";
async function getHtml(url) {
  const r = await req(url, { headers: { "User-Agent": UA } });
  return r.text || "";
}
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
console.log(`EP: ${epUrl}`);
const eDoc = await getHtml(epUrl);
let code = null;
for (const m of eDoc.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
  const u = unpackOne(m[0]);
  if (!u) continue;
  const c = u.match(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/);
  if (c) { code = c[1]; break; }
}
if (!code) { console.log("no se encontro embed bysekoze"); process.exit(1); }
console.log(`bysekoze code: ${code}`);
await fmFlow(code);
