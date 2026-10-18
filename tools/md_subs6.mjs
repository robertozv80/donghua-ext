// Sonda: ¿el CDN bysekoze sirve un VTT único para subtítulos (además del playlist de segmentos)?
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
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[0] ^ t[1], 8);
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
  const r = await fetch(url, { method, headers: h, body: body === null ? undefined : typeof body === "string" ? body : JSON.stringify(body), redirect: "manual" });
  const buf = Buffer.from(await r.arrayBuffer());
  let text;
  try { text = (r.headers.get("content-type") || "").includes("gzip") && buf.length && buf[0] === 0x1f ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch { text = ""; }
  return { status: r.status, headers: r.headers, text, buf };
}
const b64urlDecode = (s) => Buffer.from(s.trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");

async function byseFlow(code) {
  const headers = { Accept: "application/json", Origin: EMBED_ORIGIN, Referer: EMBED_REFERER, "X-Embed-Origin": EMBED_ORIGIN, "X-Embed-Referer": EMBED_REFERER, "X-Embed-Parent": EMBED_REFERER };
  const cap = await req(`${API_BASE}/api/videos/${code}/embed/captcha`, { method: "POST", headers, body: {} });
  if (cap.status !== 200) return null;
  const nonce = (cap.text.match(/"pow_nonce"\s*:\s*"([^"]+)"/) || [])[1];
  const difficulty = parseInt((cap.text.match(/"pow_difficulty"\s*:\s*(\d+)/) || [])[1] || "0", 10);
  const powToken = (cap.text.match(/"pow_token"\s*:\s*"([^"]+)"/) || [])[1];
  if (!nonce || !powToken) return null;
  const solution = solvePow(nonce, difficulty);
  if (solution === null) return null;
  const ver = await req(`${API_BASE}/api/videos/${code}/embed/captcha/verify`, { method: "POST", headers, body: JSON.stringify({ pow_token: powToken, solution }) });
  const captchaToken = (ver.text.match(/"token"\s*:\s*"([^"]+)"/) || [])[1];
  if (!captchaToken) return null;
  const fingerprint = { token: captchaToken, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
  const pb = await req(`${API_BASE}/api/videos/${code}/embed/playback`, { method: "POST", headers: { ...headers, "X-Captcha-Token": captchaToken }, body: JSON.stringify({ fingerprint }) });
  let enc;
  try { enc = JSON.parse(pb.text).playback; } catch { return null; }
  if (!enc) return null;
  const kp = enc.key_parts || [];
  const verN = typeof enc.version === "number" ? enc.version : parseInt(String(enc.version).match(/\d+/)?.[0] || "0", 10);
  const sel = [];
  for (const idx of [verN, 31 - verN]) { if (idx >= 1 && idx <= kp.length && kp[idx - 1]) sel.push(kp[idx - 1]); }
  if (!sel.length) sel.push(...kp);
  const key = Buffer.concat(sel.map(b64urlDecode));
  const iv = b64urlDecode(enc.iv || "");
  const payload = b64urlDecode(enc.payload || "");
  let plain;
  try {
    const dec = crypto.createDecipheriv("aes-256-gcm", key, iv);
    plain = Buffer.concat([dec.update(payload), dec.final()]);
  } catch { return null; }
  return JSON.parse(plain.toString("utf8"));
}

// ---- sonda de variantes de URL de subtítulos ----
async function probeVariants(subUri) {
  const u = new URL(subUri);
  const base = u.origin + u.pathname; // .../lang/eng/<code>_eng/index.m3u8
  const q = u.search;
  const variants = [
    base + q,
    base.replace(/\.m3u8$/, ".vtt") + q,
    base.replace(/index\.m3u8$/, "all.vtt") + q,
    base.replace(/index\.m3u8$/, "subtitles.vtt") + q,
    base.replace(/index\.m3u8$/, "index.vtt") + q,
    base.replace(/index\.m3u8$/, "sub.m3u8") + q,
    base + q + "&raw=1",
  ];
  for (const v of variants) {
    const r = await req(v, { headers: { Referer: EMBED_REFERER } });
    const ct = r.headers.get("content-type") || "";
    const head = r.text.slice(0, 60).replace(/\r?\n/g, "\\n");
    console.log(`  ${r.status} ${ct.padEnd(24)} ${head.padEnd(62)} ${v.slice(0, 90)}`);
  }
}

const codes = process.argv.slice(2);
if (!codes.length) { console.log("uso: node md_subs6.mjs <code> [code...]"); process.exit(0); }
for (const code of codes) {
  console.log(`\n######## code=${code} ########`);
  const dec = await byseFlow(code);
  if (!dec) { console.log("  fallo el flujo bysekoze"); continue; }
  const src = (dec.sources || []).find((s) => (s.url || "").includes(".m3u8")) || (dec.sources || [])[0];
  if (!src) { console.log("  sin sources"); continue; }
  const master = await req(src.url, { headers: { Referer: "https://bysekoze.com/" } });
  const media = master.text.match(/#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*/g) || [];
  for (const m of media) {
    const uri = (m.match(/URI="([^"]+)"/) || [])[1];
    const name = (m.match(/NAME="([^"]*)"/) || [])[1];
    const lang = (m.match(/LANGUAGE="([^"]*)"/) || [])[1];
    console.log(`  MEDIA name=${name} lang=${lang}`);
    if (uri) await probeVariants(uri);
  }
  if (!media.length) console.log("  (master sin EXT-X-MEDIA de subtítulos)");
}
