#!/usr/bin/env node
// Sondas avanzadas de subtítulos bysekoze:
// 1) contenido de seg-1.vtt
// 2) nº total de segmentos
// 3) nombres alternativos de VTT completo
// 4) bundle JS del player SPA: cómo gestiona subtítulos
import zlib from "node:zlib";
import crypto from "node:crypto";

const API_BASE = "https://bysekoze.com";
const EMBED_ORIGIN = "https://www.mundodonghua.com";
const EMBED_REFERER = "https://www.mundodonghua.com/";

async function req(url, { method = "GET", headers = {}, body = null } = {}) {
  const h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36", ...headers };
  const r = await fetch(url, { method, headers: h, body: body === null ? undefined : typeof body === "string" ? body : JSON.stringify(body), redirect: "manual" });
  const buf = Buffer.from(await r.arrayBuffer());
  let text;
  if (r.headers.get("content-encoding") === "gzip" && buf.length > 0 && buf[0] === 0x1f) text = zlib.gunzipSync(buf).toString("utf8");
  else text = buf.toString("utf8");
  return { status: r.status, headers: r.headers, text, buf };
}

// ---- flujo bysekoze ----
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
  for (let r = 0; r < 2; r++) for (let s = 0; s < size; s++) {
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
const leadingZeroBits = (words) => {
  let total = 0;
  for (const w of words) { if (w === 0) { total += 32; continue; } total += Math.clz32(w); break; }
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
const b64urlDecode = (s) => Buffer.from(s.trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");

async function byseFlow(code) {
  const headers = {
    Accept: "application/json", Origin: EMBED_ORIGIN, Referer: EMBED_REFERER,
    "X-Embed-Origin": EMBED_ORIGIN, "X-Embed-Referer": EMBED_REFERER, "X-Embed-Parent": EMBED_REFERER,
  };
  const cap = await req(`${API_BASE}/api/videos/${code}/embed/captcha`, { method: "POST", headers, body: {} });
  if (cap.status !== 200) return null;
  const capJson = JSON.parse(cap.text);
  const solution = solvePow(capJson.pow_nonce, capJson.pow_difficulty);
  if (!solution) return null;
  const ver = await req(`${API_BASE}/api/videos/${code}/embed/captcha/verify`, {
    method: "POST", headers, body: { pow_token: capJson.pow_token, solution },
  });
  if (ver.status !== 200) return null;
  const token = JSON.parse(ver.text).token;
  const fingerprint = { token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
  const pb = await req(`${API_BASE}/api/videos/${code}/embed/playback`, {
    method: "POST", headers: { ...headers, "X-Captcha-Token": token }, body: { fingerprint },
  });
  if (pb.status !== 200) return null;
  const enc = JSON.parse(pb.text).playback;
  const version = typeof enc.version === "number" ? enc.version : parseInt(String(enc.version).match(/\d+/)?.[0] ?? "0", 10);
  const sel = [version, 31 - version].map((i) => (i >= 1 && i <= enc.key_parts.length ? enc.key_parts[i - 1] : null)).filter(Boolean);
  const key = Buffer.concat(sel.map(b64urlDecode));
  const payload = b64urlDecode(enc.payload);
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, b64urlDecode(enc.iv));
  decipher.setAuthTag(payload.subarray(payload.length - 16));
  const plain = Buffer.concat([decipher.update(payload.subarray(0, payload.length - 16)), decipher.final()]);
  return JSON.parse(plain.toString("utf8"));
}

const code = process.argv[2] || "rgcgmsu5j63y";
console.log(`=== sondas de subtítulos: ${code} ===`);
const json = await byseFlow(code);
if (!json) { console.log("flujo fallido"); process.exit(1); }
const master = json.sources.find((s) => s.url.includes(".m3u8"));
const masterText = (await req(master.url, { headers: { Referer: "https://bysekoze.com/" } })).text;
const subUri = masterText.split("\n").find((l) => l.includes("#EXT-X-MEDIA:TYPE=SUBTITLES")).match(/URI="([^"]+)"/)[1];
const subText = (await req(subUri, { headers: { Referer: "https://bysekoze.com/" } })).text;
const segUrls = [...subText.matchAll(/https:\/\/\S+?\.vtt\?[^\s"]+/g)].map((m) => m[0]);
console.log(`segmentos: ${segUrls.length}`);
console.log(`primer seg: ${segUrls[0]}`);
console.log(`ultimo seg: ${segUrls[segUrls.length - 1]}`);

// 1) contenido de seg-1 y del último
const seg1 = await req(segUrls[0], { headers: { Referer: "https://bysekoze.com/" } });
console.log(`\n--- seg-1.vtt (${seg1.status}, ${seg1.buf.length}B, type=${seg1.headers.get("content-type")}) ---`);
console.log(seg1.text.slice(0, 800));
const segLast = await req(segUrls[segUrls.length - 1], { headers: { Referer: "https://bysekoze.com/" } });
console.log(`\n--- ultimo seg (${segLast.status}) ---`);
console.log(segLast.text.slice(0, 400));

// 2) nombres alternativos de VTT completo
console.log("\n=== nombres alternativos de VTT completo ===");
const u = new URL(subUri);
const segs = u.pathname.split("/").filter(Boolean);
const codeLang = segs[segs.length - 2]; // rgcgmsu5j63y_eng
const cdn = u.origin;
const dir = segs.slice(0, -1).join("/");
for (const name of [
  `${codeLang}.vtt`, `${codeLang.replace(/_[a-z]+$/, "")}.vtt`,
  "full.vtt", "all.vtt", "complete.vtt", "subtitles.vtt", "subtitle.vtt",
  "index.vtt", "captions.vtt", `captions_${codeLang}.vtt`,
  `${codeLang.replace(/_[a-z]+$/, "")}_captions.vtt`,
]) {
  try {
    const r = await req(`${cdn}/${dir}/${name}${u.search}`, { headers: { Referer: "https://bysekoze.com/" } });
    const head = r.text.slice(0, 80).replace(/\n/g, "\\n");
    console.log(`  ${name}: ${r.status} (${r.buf.length}B) ${head}`);
  } catch (e) { console.log(`  ${name}: ERR`); }
}

// 3) bundle JS del player bysekoze (SPA)
console.log("\n=== SPA bysekoze: bundles JS ===");
const spa = await req("https://bysekoze.com/", { headers: {} });
const jsUrls = [...spa.text.matchAll(/src="(\/[^"]+\.js)"/g)].map((m) => m[1]);
console.log(`scripts: ${jsUrls.join(", ")}`);
for (const jsPath of jsUrls) {
  const js = await req("https://bysekoze.com" + jsPath, { headers: {} });
  const body = js.text;
  const hits = [];
  for (const kw of ["subtitle", "caption", "WebVTT", ".vtt", "textTrack", "EXT-X-MEDIA", "merge", "concat"]) {
    if (body.toLowerCase().includes(kw.toLowerCase())) hits.push(kw);
  }
  console.log(`  ${jsPath}: ${js.status} ${body.length}B hits=[${hits.join(",")}]`);
  if (hits.length) {
    // contexto alrededor de "subtitle"/"vtt"
    const low = body.toLowerCase();
    for (const kw of ["subtitle", ".vtt", "texttrack", "ext-x-media"]) {
      let idx = low.indexOf(kw);
      let count = 0;
      while (idx !== -1 && count < 3) {
        console.log(`    [${kw}] ...${body.slice(Math.max(0, idx - 150), idx + 200).replace(/\s+/g, " ")}...`);
        idx = low.indexOf(kw, idx + kw.length);
        count++;
      }
    }
  }
}
