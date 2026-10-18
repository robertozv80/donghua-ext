// Sonda completa de subtítulos HLS de bysekoze (flujo real con tokens frescos).
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
  for (const x of w) { if (x === 0) { t += 32; continue; } t += Math.clz32(x); break; }
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
  if (cap.status !== 200) return null;
  const { pow_nonce: nonce, pow_difficulty: difficulty, pow_token: powToken } = JSON.parse(cap.text);
  const solution = solvePow(nonce, difficulty);
  if (!solution) return null;
  const ver = await req(`${API_BASE}/api/videos/${code}/embed/captcha/verify`, { method: "POST", headers, body: { pow_token: powToken, solution } });
  if (ver.status !== 200) return null;
  const token = JSON.parse(ver.text).token;
  const fingerprint = { token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
  const pb = await req(`${API_BASE}/api/videos/${code}/embed/playback`, { method: "POST", headers: { ...headers, "X-Captcha-Token": token }, body: { fingerprint } });
  if (pb.status !== 200) return null;
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
if (!codes.length) codes.push("rgcgmsu5j63y", "0nn2ohmw6jyv");

for (const code of codes) {
  console.log(`\n############ code=${code} ############`);
  const res = await byseFlow(code);
  if (!res) { console.log("  flujo fallido"); continue; }
  const m3u8Src = res.json.sources?.find((s) => s.mime_type?.includes("mpegurl") || s.url?.includes(".m3u8"));
  if (!m3u8Src?.url) { console.log("  sin m3u8"); continue; }
  console.log(`  master: ${m3u8Src.url}`);
  const mr = await req(m3u8Src.url, { headers: { Referer: "https://bysekoze.com/" } });
  console.log(`  master status=${mr.status}`);
  const mediaTags = [...mr.text.matchAll(/#EXT-X-MEDIA:([^\r\n]+)/g)].map((m) => m[1]);
  for (const tag of mediaTags) console.log(`  MEDIA: ${tag}`);

  for (const tag of mediaTags) {
    if (!tag.includes("TYPE=SUBTITLES")) continue;
    const uriVal = (tag.match(/URI="([^"]+)"/) || [])[1];
    const name = (tag.match(/NAME="([^"]*)"/) || [])[1];
    const lang = (tag.match(/LANGUAGE="([^"]*)"/) || [])[1];
    if (!uriVal) continue;
    const subUrl = uriVal.startsWith("http") ? uriVal : new URL(uriVal, m3u8Src.url).href;
    console.log(`\n  -- subtitle playlist (${name}/${lang}): ${subUrl}`);
    const sr = await req(subUrl, { headers: { Referer: "https://bysekoze.com/" } });
    console.log(`     status=${sr.status} bytes=${sr.buf.length}`);
    const segs = [...sr.text.matchAll(/(?:^|\n)(?!#)([^\r\n]+)/g)].map((m) => m[1].trim()).filter(Boolean);
    console.log(`     segmentos: ${segs.length}`);
    if (segs.length) {
      console.log(`     primero: ${segs[0]}`);
      console.log(`     ultimo:  ${segs[segs.length - 1]}`);
      // ¿los segmentos son absolutos o relativos?
      const firstAbs = segs[0].startsWith("http") ? segs[0] : new URL(segs[0], subUrl).href;
      const dir = firstAbs.slice(0, firstAbs.lastIndexOf("/") + 1);
      const qs = firstAbs.slice(firstAbs.indexOf("?") + 1);
      console.log(`     dir: ${dir}`);
      // sonda de VTT único
      for (const nm of ["all.vtt", "combined.vtt", "full.vtt", "merge.vtt", "subtitles.vtt", "subtitle.vtt", "captions.vtt", "eng.vtt", `${code}.vtt`, `${code}_${lang}.vtt`, `${code}_${lang}_all.vtt`, `seg-ALL.vtt`, `seg-all.vtt`, `seg-0.vtt`]) {
        const r = await req(`${dir}${nm}?${qs}`).catch(() => null);
        if (r && r.status === 200 && r.text.includes("WEBVTT")) {
          console.log(`     !!! VTT UNICO ENCONTRADO: ${nm} (${r.buf.length}B)`);
        }
      }
      // ¿el playlist sin query funciona? ¿y el index con Accept: text/vtt?
      const noq = await req(subUrl.split("?")[0]).catch(() => null);
      console.log(`     playlist sin query: ${noq?.status}`);
      // ¿segmentos sin query?
      const segNoq = await req(firstAbs.split("?")[0]).catch(() => null);
      console.log(`     seg-1 sin query: ${segNoq?.status}`);
      // descargar todos los segmentos y fusionar (simulación del fix)
      let merged = "WEBVTT\n\n";
      let ok = 0, fail = 0;
      for (const s of segs.slice(0, 400)) {
        const u = s.startsWith("http") ? s : new URL(s, subUrl).href;
        const r = await req(u, { headers: { Referer: "https://bysekoze.com/" } }).catch(() => null);
        if (r && r.status === 200 && r.text.includes("WEBVTT")) {
          ok++;
          merged += r.text.replace(/^\s*WEBVTT[^\n]*\n?/i, "").replace(/\s+$/, "") + "\n\n";
        } else fail++;
      }
      console.log(`     FUSION: ok=${ok} fail=${fail} -> ${merged.length} chars`);
      console.log(`     muestra fusion:\n${merged.split("\n").slice(0, 8).join("\n")}`);
    }
  }
}
