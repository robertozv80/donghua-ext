// Sonda final: flujo bysekoze completo -> vuelca el JSON descifrado (estructura de
// subtítulos incluida) y, si el master trae EXT-X-MEDIA de subtítulos, fusiona los
// segmentos VTT en un archivo único y lo guarda.
import crypto from "node:crypto";
import fs from "node:fs";
import zlib from "node:zlib";

const MAIN = "https://www.mundodonghua.com";
const EMBED_ORIGIN = MAIN;
const EMBED_REFERER = MAIN + "/";
const API_BASE = "https://bysekoze.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

async function req(url, { method = "GET", headers = {}, body = null, timeoutMs = 10000 } = {}) {
  const h = { "User-Agent": UA, Accept: "*/*", ...headers };
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, { method, headers: h, signal: ctrl.signal, redirect: "follow", body: body === null ? undefined : typeof body === "string" ? body : JSON.stringify(body) });
    const buf = Buffer.from(await r.arrayBuffer());
    let text = "";
    try { text = (r.headers.get("content-encoding") || "").includes("gzip") && buf.length && buf[0] === 0x1f ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
    return { status: r.status, headers: r.headers, text, buf };
  } catch (e) { return { status: 0, headers: null, text: "", buf: Buffer.alloc(0), err: e.message }; }
  finally { clearTimeout(t); }
}

function unpackAll(html) {
  const out = [];
  for (const m of html.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
    try {
      if (!m[0].endsWith(")")) continue;
      const u = eval("(" + m[0].slice(5, -1) + ")");
      if (typeof u === "string" && !u.includes("function(p,a,c,k,e")) out.push(u);
    } catch {}
  }
  return out;
}

// ---------- PoW bysekoze ----------
const rotl32 = (x, n) => ((x << n) | (x >>> (32 - n))) >>> 0;
function mixRound(t) {
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 16);
  t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 12);
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[0] ^ t[1], 8);
  t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 7);
}
function powHash(data) {
  const e = [1779033703, 3144134277 >>> 0, 1013904242, 2773480762 >>> 0];
  for (const b of data) { e[0] = (e[0] + b) >>> 0; e[0] = rotl32(e[0], 7); mixRound(e); }
  for (let i = 0; i < 8; i++) mixRound(e);
  const size = 512, mask = size - 1, lr = 2654435761 >>> 0, hr = 2246822519 >>> 0;
  const table = new Array(size);
  for (let i = 0; i < size; i++) { mixRound(e); table[i] = (e[0] ^ e[2]) >>> 0; }
  for (let r = 0; r < 2; r++) for (let s = 0; s < size; s++) {
    let c = (table[s] + table[table[s] & mask]) >>> 0;
    c = rotl32(c, 13);
    c = (c ^ Math.imul(table[(s + 1) & mask], lr)) >>> 0;
    table[s] = c; e[0] = (e[0] ^ c) >>> 0; mixRound(e);
  }
  const out = new Array(8), group = size / 8;
  for (let i = 0; i < 8; i++) {
    mixRound(e);
    let acc = e[0]; const base = i * group;
    for (let j = 0; j < group; j++) { const d = table[base + j]; acc = (acc + d) >>> 0; acc = rotl32(acc, 5); acc = (acc ^ Math.imul(d, hr)) >>> 0; }
    out[i] = (acc ^ e[2]) >>> 0;
  }
  return out;
}
const lzb = (w) => { let t = 0; for (const x of w) { if (x === 0) { t += 32; continue; } t += Math.clz32(x); break; } return t; };
function solvePow(nonce, difficulty) {
  const prefix = Buffer.from(nonce + ":", "utf8");
  const t0 = Date.now();
  for (let c = 0; ; c++) {
    if (lzb(powHash(Buffer.concat([prefix, Buffer.from(String(c))]))) >= difficulty) return String(c);
    if (c % 100000 === 0 && Date.now() - t0 > 60000) return null;
  }
}
const b64u = (s) => Buffer.from(s.trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");

async function byseFlow(code) {
  const headers = { "Content-Type": "application/json", Accept: "application/json", Origin: EMBED_ORIGIN, Referer: EMBED_REFERER, "X-Embed-Origin": EMBED_ORIGIN, "X-Embed-Referer": EMBED_REFERER, "X-Embed-Parent": EMBED_REFERER };
  const cap = await req(`${API_BASE}/api/videos/${code}/embed/captcha`, { method: "POST", headers, body: {} });
  if (cap.status !== 200) return { error: "captcha " + cap.status };
  const nonce = cap.text.match(/"pow_nonce"\s*:\s*"([^"]+)"/)?.[1];
  const difficulty = parseInt(cap.text.match(/"pow_difficulty"\s*:\s*(\d+)/)?.[1] || "0", 10);
  const powToken = cap.text.match(/"pow_token"\s*:\s*"([^"]+)"/)?.[1];
  if (!nonce || !powToken) return { error: "captcha body: " + cap.text.slice(0, 200) };
  const solution = solvePow(nonce, difficulty);
  if (solution === null) return { error: "pow timeout" };
  const ver = await req(`${API_BASE}/api/videos/${code}/embed/captcha/verify`, { method: "POST", headers, body: JSON.stringify({ pow_token: powToken, solution }) });
  const captchaToken = ver.text.match(/"token"\s*:\s*"([^"]+)"/)?.[1];
  if (!captchaToken) return { error: "verify " + ver.status + " " + ver.text.slice(0, 200) };
  const fingerprint = { token: captchaToken, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
  const pb = await req(`${API_BASE}/api/videos/${code}/embed/playback`, { method: "POST", headers: { ...headers, "X-Captcha-Token": captchaToken }, body: JSON.stringify({ fingerprint }) });
  let enc; try { enc = JSON.parse(pb.text).playback; } catch { return { error: "playback " + pb.status + " " + pb.text.slice(0, 200) }; }
  if (!enc) return { error: "sin playback" };
  const kp = enc.key_parts || [];
  const verN = typeof enc.version === "number" ? enc.version : parseInt(String(enc.version).match(/\d+/)?.[0] || "0", 10);
  const sel = [];
  for (const idx of [verN, 31 - verN]) if (idx >= 1 && idx <= kp.length && kp[idx - 1]) sel.push(kp[idx - 1]);
  if (!sel.length) sel.push(...kp);
  const key = Buffer.concat(sel.map(b64u));
  const iv = b64u(enc.iv || ""), payload = b64u(enc.payload || "");
  try {
    const d = crypto.createDecipheriv("aes-256-gcm", key, iv);
    return JSON.parse(Buffer.concat([d.update(payload), d.final()]).toString("utf8"));
  } catch (e) { return { error: "descifrado: " + e.message }; }
}

// ---------- discovering un código fresco ----------
async function findCodes(n = 4) {
  const codes = new Set();
  const list = await req(`${MAIN}/lista-donghuas`, { timeoutMs: 10000 });
  const slugs = [...new Set([...list.text.matchAll(/href="(\/donghua\/[^"]+)"/g)].map((m) => m[1].slice(8)))];
  console.log("series en lista:", slugs.length);
  let i = 0;
  while (codes.size < n && i < slugs.length) {
    const slug = slugs[Math.floor((i * 7919) % slugs.length)];
    i++;
    const d = await req(`${MAIN}/donghua/${slug}`, { timeoutMs: 10000 });
    const ep = d.text.match(/href="([^"]*\/ver\/[^"]+)"/)?.[1];
    if (!ep) continue;
    const e2 = await req(MAIN + ep, { timeoutMs: 10000 });
    const hay = [e2.text, ...unpackAll(e2.text)].join("\n");
    const fm = hay.match(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
    console.log(`  ${slug} -> ${fm ? "bysekoze " + fm : "sin fm"}`);
    if (fm) codes.add(fm);
  }
  return [...codes];
}

const argCodes = process.argv.slice(2).filter((a) => !a.startsWith("-"));
const codes = argCodes.length ? argCodes : await findCodes(3);

for (const code of codes) {
  console.log(`\n############ code=${code} ############`);
  const dec = await byseFlow(code);
  if (dec.error) { console.log("  ERROR:", dec.error); continue; }
  fs.writeFileSync(`tools/_fm_${code}.json`, JSON.stringify(dec, null, 2));
  console.log("  claves raiz:", Object.keys(dec).join(", "));
  for (const [k, v] of Object.entries(dec)) {
    if (Array.isArray(v)) console.log(`   ${k}: array(${v.length})`, v.length ? JSON.stringify(v[0]).slice(0, 220) : "");
    else if (v && typeof v === "object") console.log(`   ${k}: obj`, JSON.stringify(v).slice(0, 400));
    else console.log(`   ${k}:`, String(v).slice(0, 160));
  }
  const srcs = dec.sources || [];
  for (const s of srcs) console.log("  source:", JSON.stringify(s).slice(0, 200));
  const m3u = srcs.find((s) => (s.url || "").includes(".m3u8"));
  if (!m3u) continue;
  const master = await req(m3u.url, { headers: { Referer: API_BASE + "/" } });
  const media = master.text.match(/#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*/g) || [];
  console.log("  master status", master.status, "len", master.text.length, "variants", (master.text.match(/#EXT-X-STREAM-INF/g) || []).length);
  console.log("  EXT-X-MEDIA:", media.length ? media.join(" | ") : "(ninguna)");
  for (const line of media) {
    const uri = line.match(/URI="([^"]+)"/)?.[1];
    if (!uri) continue;
    const abs = new URL(uri, m3u.url).toString();
    const pl = await req(abs, { headers: { Referer: m3u.url } });
    console.log("  sub playlist:", pl.status, pl.text.length, "b", (pl.text.match(/#EXTINF/g) || []).length, "segmentos");
    const segs = [...pl.text.matchAll(/^(?!#)([^\r\n]+)$/gm)].map((m) => m[1].trim()).filter(Boolean);
    let out = "WEBVTT\n\n";
    let n = 0;
    for (const s of segs.slice(0, 60)) {
      const segUrl = new URL(s, abs).toString();
      const sr = await req(segUrl, { headers: { Referer: m3u.url } });
      if (sr.status !== 200 || !sr.text.trim()) { console.log("   seg fallida", sr.status, s.slice(-40)); continue; }
      n++;
      out += normalizeSeg(sr.text);
    }
    fs.writeFileSync(`tools/_fm_${code}_merged.vtt`, out);
    console.log(`  VTT fusionado: ${n} segmentos, ${out.length} chars -> tools/_fm_${code}_merged.vtt`);
    console.log("  muestra:", JSON.stringify(out.slice(0, 300)));
  }
}

// Normaliza un segmento VTT (quita cabecera WEBVTT y ajusta numeracion) sin timestamps.
function normalizeSeg(t) {
  let s = t.replace(/\r/g, "");
  s = s.replace(/^WEBVTT[^\n]*\n/, "");
  s = s.replace(/^NOTE[^\n]*\n/gm, "");
  s = s.replace(/^\d+[ \t]*\n/gm, "");
  return s.trim() + "\n";
}