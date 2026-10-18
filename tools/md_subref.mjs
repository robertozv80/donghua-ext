// ¿Los segmentos VTT de subtítulos cargan con el referer que usaría la app?
import zlib from "node:zlib";

const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

async function req(url, { headers = {} } = {}) {
  const h = { "User-Agent": UA, ...headers };
  const r = await fetch(url, { headers: h, redirect: "manual" });
  const buf = Buffer.from(await r.arrayBuffer());
  let text = "";
  try { text = (r.headers.get("content-encoding") === "gzip" && buf.length && buf[0] === 0x1f) ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
  return { status: r.status, ct: r.headers.get("content-type"), len: buf.length, text };
}

const code = process.argv[2] || "rgcgmsu5j63y";
// Flujo bysekoze mínimo: captcha -> verify -> playback
import crypto from "node:crypto";
const API_BASE = "https://bysekoze.com";
const EMBED_ORIGIN = "https://www.mundodonghua.com";
// (reutilizamos la lógica de md_subs5)
const rotl32 = (x, n) => ((x << n) | (x >>> (32 - n))) >>> 0;
function mixRound(t) { t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 16); t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 12); t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 8); t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 7); }
function powHash(data) { const e = [1779033703, (3144134277) >>> 0, 1013904242, (2773480762) >>> 0]; for (const b of data) { e[0] = (e[0] + b) >>> 0; e[0] = rotl32(e[0], 7); mixRound(e); } for (let i = 0; i < 8; i++) mixRound(e); const size = 512, mask = size - 1; const lr = (2654435761) >>> 0, hr = (2246822519) >>> 0; const table = new Array(size); for (let i = 0; i < size; i++) { mixRound(e); table[i] = (e[0] ^ e[2]) >>> 0; } for (let r = 0; r < 2; r++) for (let s = 0; s < size; s++) { let c = (table[s] + table[table[s] & mask]) >>> 0; c = rotl32(c, 13); c = (c ^ (Math.imul(table[(s + 1) & mask], lr))) >>> 0; table[s] = c; e[0] = (e[0] ^ c) >>> 0; mixRound(e); } const out = new Array(8); const group = size / 8; for (let i = 0; i < 8; i++) { mixRound(e); let acc = e[0]; const base = i * group; for (let j = 0; j < group; j++) { const d = table[base + j]; acc = (acc + d) >>> 0; acc = rotl32(acc, 5); acc = (acc ^ (Math.imul(d, hr))) >>> 0; } out[i] = (acc ^ e[2]) >>> 0; } return out; }
const leadingZeroBits = (w) => { let t = 0; for (const x of w) { if (x === 0) { t += 32; continue; } t += Math.clz32(x); break; } return t; };
function solvePow(nonce, difficulty) { const prefix = Buffer.from(nonce + ":", "utf8"); const t0 = Date.now(); let c = 0; while (true) { if (leadingZeroBits(powHash(Buffer.concat([prefix, Buffer.from(String(c))]))) >= difficulty) return String(c); c++; if (c % 100000 === 0 && Date.now() - t0 > 60000) return null; } }
async function byseFlow(c) {
  const headers = { Accept: "application/json", Origin: EMBED_ORIGIN, Referer: EMBED_REFERER, "X-Embed-Origin": EMBED_ORIGIN, "X-Embed-Referer": EMBED_REFERER, "X-Embed-Parent": EMBED_REFERER };
  const cap = await req(`${API_BASE}/api/videos/${c}/embed/captcha`, { headers: { ...headers, "Content-Type": "application/json" } });
  if (cap.status !== 200) return null;
  const { pow_nonce: nonce, pow_difficulty: difficulty, pow_token: powToken } = JSON.parse(cap.text);
  const solution = solvePow(nonce, difficulty);
  if (!solution) return null;
  const ver = await req(`${API_BASE}/api/videos/${c}/embed/captcha/verify`, { headers: { ...headers, "Content-Type": "application/json" }, body: JSON.stringify({ pow_token: powToken, solution }) });
  if (ver.status !== 200) return null;
  const token = JSON.parse(ver.text).token;
  const pb = await req(`${API_BASE}/api/videos/${c}/embed/playback`, { headers: { ...headers, "X-Captcha-Token": token, "Content-Type": "application/json" }, body: JSON.stringify({ fingerprint: { token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 } }) });
  if (pb.status !== 200) return null;
  return { token, json: JSON.parse(pb.text).playback };
}

const res = await byseFlow(code);
if (!res) { console.log("flujo fallido"); process.exit(1); }
// el playback ya viene desencriptado en md_subs5; aquí solo queremos el master. Reutilizamos la decodificación:
import crypto as _c;
console.log("playback (raw, hay que desencriptar)");
