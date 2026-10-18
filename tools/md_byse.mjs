#!/usr/bin/env node
// Cliente de la API de bysekoze (servidor "Fm" de MundoDonghua).
// Flujo: captcha(PoW) -> verify -> playback(AES-GCM) -> JSON completo.
// Además sondea endpoints de subtítulos/tracks y el m3u8 resultante.
import crypto from "node:crypto";
import zlib from "node:zlib";

const API_BASE = "https://bysekoze.com";
const EMBED_ORIGIN = "https://www.mundodonghua.com";
const EMBED_REFERER = "https://www.mundodonghua.com/";

// ===== hash custom del PoW (port del bundle; verificado: gr("test:0")
// = 2c62ed779d20d796ca039154fec1dbe30e3bef566b89a146d2e365a5ae4a98fb) =====
const rotl32 = (x, n) => ((x << n) | (x >>> (32 - n))) >>> 0;
function mixRound(t) {
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 16);
  t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 12);
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 8);
  t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 7);
}
function powHash(data) {
  const e = [1779033703, (3144134277) >>> 0, 1013904242, (2773480762) >>> 0];
  for (const b of data) {
    e[0] = (e[0] + b) >>> 0;
    e[0] = rotl32(e[0], 7);
    mixRound(e);
  }
  for (let i = 0; i < 8; i++) mixRound(e);
  const size = 512, mask = size - 1;
  const lr = (2654435761) >>> 0, hr = (2246822519) >>> 0;
  const table = new Array(size);
  for (let i = 0; i < size; i++) { mixRound(e); table[i] = (e[0] ^ e[2]) >>> 0; }
  for (let r = 0; r < 2; r++) {
    for (let s = 0; s < size; s++) {
      let c = (table[s] + table[table[s] & mask]) >>> 0;
      c = rotl32(c, 13);
      c = (c ^ (Math.imul(table[(s + 1) & mask], lr))) >>> 0;
      table[s] = c;
      e[0] = (e[0] ^ c) >>> 0;
      mixRound(e);
    }
  }
  const out = new Array(8);
  const group = size / 8;
  for (let i = 0; i < 8; i++) {
    mixRound(e);
    let acc = e[0];
    const base = i * group;
    for (let j = 0; j < group; j++) {
      const d = table[base + j];
      acc = (acc + d) >>> 0;
      acc = rotl32(acc, 5);
      acc = (acc ^ (Math.imul(d, hr))) >>> 0;
    }
    out[i] = (acc ^ e[2]) >>> 0;
  }
  return out;
}
const hexOf = (words) => words.map((w) => w.toString(16).padStart(8, "0")).join("");
const leadingZeroBits = (words) => {
  let total = 0;
  for (const w of words) {
    if (w === 0) { total += 32; continue; }
    total += Math.clz32(w);
    break;
  }
  return total;
};
function solvePow(nonce, difficulty) {
  const prefix = Buffer.from(nonce + ":", "utf8");
  const t0 = Date.now();
  let counter = 0;
  while (true) {
    const input = Buffer.concat([prefix, Buffer.from(String(counter))]);
    if (leadingZeroBits(powHash(new Uint8Array(input))) >= difficulty) return String(counter);
    counter++;
    if (counter % 100000 === 0 && Date.now() - t0 > 60000) return null;
  }
}

// ===== HTTP helpers (sin dependencias) =====
async function req(url, { method = "GET", headers = {}, body = null } = {}) {
  const h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36", ...headers };
  const r = await fetch(url, {
    method,
    headers: h,
    body: body === null ? undefined : typeof body === "string" ? body : JSON.stringify(body),
    redirect: "manual",
  });
  const buf = Buffer.from(await r.arrayBuffer());
  let text;
  if (r.headers.get("content-encoding") === "gzip" && buf.length > 0 && buf[0] === 0x1f) {
    text = zlib.gunzipSync(buf).toString("utf8");
  } else {
    text = buf.toString("utf8");
  }
  return { status: r.status, headers: r.headers, text, buf };
}

const b64urlDecode = (s) => Buffer.from(s.trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");

async function byseFlow(code, verbose = true) {
  const headers = {
    Accept: "application/json",
    Origin: EMBED_ORIGIN,
    Referer: EMBED_REFERER,
    "X-Embed-Origin": EMBED_ORIGIN,
    "X-Embed-Referer": EMBED_REFERER,
    "X-Embed-Parent": EMBED_REFERER,
  };
  // 1) captcha
  const cap = await req(`${API_BASE}/api/videos/${code}/embed/captcha`, { method: "POST", headers, body: {} });
  if (verbose) console.log(`  captcha -> ${cap.status}: ${cap.text.slice(0, 200)}`);
  if (cap.status !== 200) return null;
  const capJson = JSON.parse(cap.text);
  const { pow_nonce: nonce, pow_difficulty: difficulty, pow_token: powToken } = capJson;
  // 2) PoW
  const solution = solvePow(nonce, difficulty);
  if (verbose) console.log(`  PoW dificultad=${difficulty} solucion=${solution}`);
  if (!solution) return null;
  // 3) verify
  const ver = await req(`${API_BASE}/api/videos/${code}/embed/captcha/verify`, {
    method: "POST", headers, body: { pow_token: powToken, solution },
  });
  if (verbose) console.log(`  verify -> ${ver.status}: ${ver.text.slice(0, 200)}`);
  if (ver.status !== 200) return null;
  const token = JSON.parse(ver.text).token;
  // 4) playback
  const fingerprint = { token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
  const pb = await req(`${API_BASE}/api/videos/${code}/embed/playback`, {
    method: "POST", headers: { ...headers, "X-Captcha-Token": token }, body: { fingerprint },
  });
  if (verbose) console.log(`  playback -> ${pb.status}: ${pb.text.slice(0, 300)}`);
  if (pb.status !== 200) return null;
  const enc = JSON.parse(pb.text).playback;
  // 5) clave + descifrado
  const keyParts = enc.key_parts;
  const version = typeof enc.version === "number" ? enc.version : parseInt(String(enc.version).match(/\d+/)?.[0] ?? "0", 10);
  const idxs = [version, 31 - version];
  const sel = idxs.map((i) => (i >= 1 && i <= keyParts.length ? keyParts[i - 1] : null)).filter(Boolean);
  const key = Buffer.concat(sel.map(b64urlDecode));
  const iv = b64urlDecode(enc.iv);
  const payload = b64urlDecode(enc.payload);
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(payload.subarray(payload.length - 16));
  const plain = Buffer.concat([decipher.update(payload.subarray(0, payload.length - 16)), decipher.final()]);
  const json = JSON.parse(plain.toString("utf8"));
  if (verbose) console.log("  === PLAYLOAD DESCIFRADO COMPLETO ===");
  if (verbose) console.log(JSON.stringify(json, null, 2));
  return { token, json, encHeaders: headers };
}

// ===== sonda de endpoints de subtítulos =====
async function probeSubtitleEndpoints(code, token, headers) {
  const paths = [
    `/api/videos/${code}`,
    `/api/videos/${code}/subtitles`,
    `/api/videos/${code}/tracks`,
    `/api/videos/${code}/captions`,
    `/api/videos/${code}/embed/subtitles`,
    `/api/videos/${code}/embed/tracks`,
    `/api/videos/${code}/embed/captions`,
    `/api/videos/${code}/subtitle`,
    `/api/videos/${code}/vtt`,
  ];
  for (const p of paths) {
    for (const method of ["GET", "POST"]) {
      try {
        const r = await req(API_BASE + p, {
          method,
          headers: { ...headers, "X-Captcha-Token": token },
          body: method === "POST" ? {} : undefined,
        });
        const interesting = r.status !== 404 && r.status !== 405 && !(r.status === 200 && r.text.trim() === "{}");
        if (interesting) {
          console.log(`  [SUBS] ${method} ${p} -> ${r.status}: ${r.text.slice(0, 400)}`);
        } else {
          console.log(`  [subs] ${method} ${p} -> ${r.status}`);
        }
      } catch (e) {
        console.log(`  [subs] ${method} ${p} -> ERR ${e.message}`);
      }
    }
  }
}

// ===== main =====
const code = process.argv[2] || "rgcgmsu5j63y";
console.log(`=== bysekoze video code: ${code} ===`);
const res = await byseFlow(code);
if (!res) {
  console.log("FLUJO FALLIDO");
  process.exit(1);
}
// Comprobación del hash (autotest)
console.log(`\nautotest powHash("test:0") = ${hexOf(powHash(Buffer.from("test:0")))}`);
console.log(`  esperado               = 2c62ed779d20d796ca039154fec1dbe30e3bef566b89a146d2e365a5ae4a98fb`);

// Sondas de subtítulos
console.log("\n=== sonda de endpoints de subtítulos ===");
await probeSubtitleEndpoints(code, res.token, res.encHeaders);

// m3u8: ¿trae pistas de subtítulos (#EXT-X-MEDIA)?
const firstM3u8 = res.json.sources?.find((s) => s.mime_type?.includes("mpegurl") || s.url?.includes(".m3u8"));
if (firstM3u8?.url) {
  console.log(`\n=== m3u8 master: ${firstM3u8.url} ===`);
  const r = await req(firstM3u8.url, { headers: { Referer: "https://bysekoze.com/" } });
  console.log(`  status=${r.status} bytes=${r.buf.length}`);
  const lines = r.text.split("\n").map((l) => l.trim()).filter(Boolean);
  console.log(`  lineas: ${lines.length}`);
  for (const l of lines.slice(0, 60)) console.log("   " + l);
  const hasMedia = r.text.includes("#EXT-X-MEDIA");
  const hasVtt = /\.vtt|WebVTT/i.test(r.text);
  console.log(`  #EXT-X-MEDIA: ${hasMedia}, vtt refs: ${hasVtt}`);
}
