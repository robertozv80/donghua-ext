// Live validator for TioDonghuaProvider.kt (faithful Node-24 port of
// loadLinks + Dooplay admin-ajax + processTdEmbed + extractTd* +
// CommonExtractors.extractDailymotion/extractOkRu as they exist TODAY in
//   TioDonghuaProvider/src/main/kotlin/com/donghuatiodonghua/TioDonghuaProvider.kt
//   common/src/CommonExtractors.kt
// jsoup selectors re-implemented as raw regex on HTML. NO npm packages.
// ALL final-URL validation uses CURL (undici/Node fetch gets fake 403s from
// Akamai/Cloudflare). Read-only against the live site.
//
// Usage: node tools/matrix_tiodonghua.mjs

import { execFileSync } from "node:child_process";
import fs from "node:fs";

const MAIN = "https://tiodonghua.lat";
// TD_USER_AGENT (Kotlin:16-17). Nota: pageHeaders (Kotlin:279) usa el USER_AGENT
// GLOBAL de CloudStream (Chrome/116-ish), no TD_USER_AGENT; se conserva el mismo UA.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
const LANG = "Accept-Language: es-ES,es;q=0.8,en;q=0.5"; // pageHeaders (Kotlin:279-282)
const DM_REFERER = "https://www.dailymotion.com/";
const QUAL = { P2160: 2160, P1440: 1440, P1080: 1080, P720: 720, P480: 480, P360: 360, Unknown: 400 };
const qName = (q) => (q === QUAL.Unknown || q == null ? "Unknown" : `${q}p`);
fs.mkdirSync(`${import.meta.dirname}/tmp`, { recursive: true });
const JAR = `${import.meta.dirname}/tmp/tio_curl_cookies.txt`;
try { fs.rmSync(JAR, { force: true }); } catch {}
try { fs.writeFileSync(JAR, ""); } catch {}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- curl
const MARK = "@@CURLMETA@@";
function curl(url, { referer = "", headers = [], range = null, timeout = 30, form = null, method = null, lang = true, noFollow = false } = {}) {
  const args = ["-s", "-m", String(timeout), "-A", UA];
  if (!noFollow) args.push("-L");
  if (referer) args.push("-e", referer);
  if (range) args.push("-r", range);
  args.push("-b", JAR, "-c", JAR);
  if (lang) args.push("-H", LANG);
  if (form) for (const [k, v] of Object.entries(form)) args.push("--data-urlencode", `${k}=${v}`);
  for (const h of headers) args.push("-H", h);
  if (method) args.push("-X", method);
  args.push("-w", `\n${MARK}%{http_code}|%{content_type}|%{url_effective}`);
  args.push(url);
  let buf;
  try {
    buf = execFileSync("curl", args, { encoding: "buffer", maxBuffer: 64 * 1024 * 1024, stdio: ["ignore", "pipe", "ignore"] });
  } catch (e) {
    return { status: 0, ctype: "", body: "", headers2: "", finalUrl: url, err: String(e?.message ?? e).slice(0, 120) };
  }
  const s = buf.toString("latin1");
  const i = s.lastIndexOf(MARK);
  if (i < 0) return { status: 0, ctype: "", body: s.slice(0, 200), finalUrl: url, err: "no curl meta" };
  const meta = s.slice(i + MARK.length).trim().split("|");
  return { status: Number(meta[0]) || 0, ctype: meta[1] || "", finalUrl: meta[2] || url, body: s.slice(0, i).replace(/\r?\n$/, "") };
}
// con volcado de cabeceras (para el challenge modagamers: Location en 3xx)
function curlHead(url, opts = {}) {
  const r = curl(url, { ...opts, noFollow: true });
  return r;
}

// ---------------------------------------------------------------- helpers
const unescJson = (s) =>
  s.replace(/\\u([\da-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
   .replace(/\\(["\\/nrt])/g, (_, c) => (c === "n" ? "\n" : c === "t" ? "\t" : c === "r" ? "\r" : c));
const dmVideoId = (src) => /(?:geo\.dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|dailymotion\.com\/embed\/video\/)([A-Za-z0-9]+)/i.exec(src)?.[1] ?? null;
const decodeUnicode = (s) => s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
const tdNormalize = (t) => t.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]+/g, "").replace(/[^a-z0-9]+/g, " ").trim();

function dmSubtitlesBlock(json) {
  const start = json.indexOf('"subtitles"');
  if (start < 0) return null;
  const dataIdx = json.indexOf('"data"', start);
  if (dataIdx < 0) return null;
  const open = json.indexOf("{", dataIdx);
  if (open < 0) return null;
  let depth = 0, inStr = false, esc = false;
  for (let i = open; i < json.length; i++) {
    const ch = json[i];
    if (esc) { esc = false; continue; }
    if (ch === "\\" && inStr) { esc = true; continue; }
    if (ch === '"') { inStr = !inStr; continue; }
    if (!inStr && ch === "{") depth++;
    if (!inStr && ch === "}") { depth--; if (depth === 0) return json.slice(open, i + 1); }
  }
  return null;
}
function dmSubLangName(code) {
  if (code.startsWith("es")) return "Spanish";
  if (code.startsWith("en")) return code.includes("auto") ? "English (auto)" : "English";
  const map = { fr: "French", pt: "Portuguese", ru: "Russian", tr: "Turkish", pl: "Polish", ar: "Arabic", bn: "Bengali", fa: "Persian", id: "Indonesian", ms: "Malay", it: "Italian", de: "German", hi: "Hindi", th: "Vietnamese", vi: "Vietnamese" };
  for (const k of Object.keys(map)) if (code.startsWith(k)) return map[k];
  return code;
}
function getAndUnpack(text) {
  for (const l of text.split("\n")) {
    const i = l.indexOf("eval(function(p,a,c,k,e,");
    if (i >= 0) {
      try {
        const o = (0, eval)(`(${l.slice(i + 5, l.trimEnd().endsWith(")") ? l.lastIndexOf(")") + 1 : undefined)})`);
        if (typeof o === "string") return o;
      } catch {}
    }
  }
  return text;
}

async function generateM3u8(linkName, m3u8Url, referer) {
  const r = curl(m3u8Url, { referer, timeout: 25 });
  const text = r.body;
  const out = [];
  if (!text.trimStart().startsWith("#EXTM3U")) throw new Error(`HTTP ${r.status}, not #EXTM3U`);
  const lines = text.replace(/\r/g, "").split("\n").map((l) => l.trim());
  const seen = new Set();
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
    const h = Number(/RESOLUTION=\d+x(\d+)/.exec(lines[i])?.[1] ?? 0);
    const codecs = /CODECS="([^"]+)"/.exec(lines[i])?.[1] ?? "";
    for (let j = i + 1; j < lines.length; j++) {
      if (!lines[j]) continue;
      if (lines[j].startsWith("#")) break;
      const abs = new URL(lines[j], m3u8Url).href;
      if (!seen.has(abs)) { seen.add(abs); out.push({ source: linkName, name: linkName, url: abs, type: "M3U8", quality: h || QUAL.Unknown, referer, codecs, attrs: lines[i] }); }
      break;
    }
  }
  if (/#EXTINF:/.test(text) && !seen.has(m3u8Url)) out.unshift({ source: linkName, name: linkName, url: m3u8Url, type: "M3U8", quality: QUAL.Unknown, referer });
  return out;
}

// ---------------------------------------------------------------- CE.extractDailymotion (CommonExtractors.kt:160-379)
const subsDetected = []; // Tio pasa subtitleCallback={ } (Tio:1121) -> no llegan a la app
async function ceExtractDailymotion(iframeSrc, embedderReferer, linkName, emittedSubs, emittedVids, emit) {
  const vid = dmVideoId(iframeSrc) || (/^[A-Za-z0-9]+$/.test(iframeSrc.trim()) ? iframeSrc.trim() : null);
  if (!vid) return { ok: false, gate: "dmVideoId no match" };
  if (emittedVids.has(vid)) return { ok: true, gate: `dedupe: ${vid} ya emitido` };
  const referer = embedderReferer || "https://animexin.dev";
  const metaUrl = `https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(referer)}&integration=inline&app=com.dailymotion.neon`;
  const res = curl(metaUrl, { referer, headers: ["Accept: application/json"], timeout: 20 });
  if (res.status !== 200) return { ok: false, gate: `metadata HTTP ${res.status}${res.err ? " " + res.err : ""}` };
  const json = res.body.split("\\/").join("/").split("\\u0026").join("&");

  const block = dmSubtitlesBlock(json);
  if (block) {
    const found = [];
    for (const m of block.matchAll(/"(es[a-z-]*|en[a-z-]*|fr|pt|ru|tr|pl|ar|bn|fa|id|ms|it|de|hi|th|vi)":\s*\{[^{}]*?"urls":\["([^"]+)"/g)) found.push([m[1], unescJson(m[2])]);
    const uniq = []; const seenU = new Set();
    for (const f of found) { if (!seenU.has(f[1])) { seenU.add(f[1]); uniq.push(f); } }
    uniq.sort((a, b) => (a[0].startsWith("es") ? 0 : a[0].startsWith("en") ? 1 : 2) - (b[0].startsWith("es") ? 0 : b[0].startsWith("en") ? 1 : 2));
    for (const [code, url] of uniq) {
      if (emittedSubs.has(url)) { subsDetected.push({ lang: dmSubLangName(code), url, suppressed: true }); continue; }
      emittedSubs.add(url);
      subsDetected.push({ lang: dmSubLangName(code), url, suppressed: false });
    }
  }

  let found = false;
  const emittedUrls = new Set();
  const doEmit = (u, hls, q, extra = {}) => {
    if (!u.startsWith("http")) return;
    if (emittedUrls.has(u)) return;
    emittedUrls.add(u);
    const suffix = q === QUAL.P2160 ? " 2160p" : q === QUAL.P1440 ? " 1440p" : q === QUAL.P1080 ? " 1080p" : q === QUAL.P720 ? " 720p" : q === QUAL.P480 ? " 480p" : q === QUAL.P360 ? " 360p" : "";
    const name = suffix && !/\b\d{3,4}p\b/i.test(linkName) ? linkName + suffix : linkName;
    emit({ source: linkName, name, url: u, type: hls ? "M3U8" : "VIDEO", quality: q ?? QUAL.Unknown, referer: DM_REFERER, ...extra });
    found = true;
  };
  const tryPlaylist = (u, hlsQ) => {
    if (!u || !u.startsWith("http")) return false;
    if (/dmxleo|advertising|auth=/i.test(u)) return false;
    const v = curl(u, { referer: DM_REFERER, timeout: 12 });
    const master = v.status === 200 ? v.body : "";
    if (!master.trimStart().startsWith("#EXTM3U")) { doEmit(u, true, hlsQ, { note: "master crudo" }); return true; }
    let expanded = false;
    for (const m of master.matchAll(/#EXT-X-STREAM-INF:([^\n]*)\n([^\n]+)/g)) {
      const attrs = m[1];
      const variant = m[2].trim();
      const codecs = /CODECS="([^"]+)"/.exec(attrs)?.[1] ?? "";
      if (/av01|hev1|hvc1/i.test(codecs)) continue;
      const hRaw = /RESOLUTION=\d+x(\d+)/.exec(attrs)?.[1];
      const h = hRaw ? Number(hRaw) : null;
      const q = h == null ? (hlsQ ?? QUAL.Unknown) : h >= 2160 ? QUAL.P2160 : h >= 1440 ? QUAL.P1440 : h >= 1080 ? QUAL.P1080 : h >= 720 ? QUAL.P720 : h >= 480 ? QUAL.P480 : h >= 360 ? QUAL.P360 : QUAL.Unknown;
      const origin = /^https?:\/\/[^/]+/.exec(u)?.[0] ?? u;
      const abs = variant.startsWith("http") ? variant : variant.startsWith("/") ? origin + variant : `${origin}/${variant}`;
      doEmit(abs, true, q, { codecs, attrs, note: "variante expandida" });
      expanded = true;
    }
    doEmit(u, true, hlsQ, { isMaster: true, note: expanded ? "master 'Auto'" : "master" });
    return true;
  };

  let emittedAny = false;
  for (const m of json.matchAll(/"auto":\[\{"url":"(https:\/\/[^"]+)"/g)) {
    if (emittedAny) break;
    if (m[1].includes("dailymotion.com")) emittedAny = tryPlaylist(unescJson(m[1]), QUAL.Unknown);
  }
  if (!emittedAny) {
    for (const m of json.matchAll(/"auto":\[\{[^}]*?"type":"[^"]+","url":"(https:\/\/[^"]+)"/g)) {
      if (emittedAny) break;
      if (m[1].includes("dailymotion.com")) emittedAny = tryPlaylist(unescJson(m[1]), QUAL.Unknown);
    }
  }
  if (!emittedAny) {
    const masterAuto = /https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i.exec(json)?.[0];
    const dc = masterAuto ? (/https:\/\/([a-z0-9.-]+)\.dailymotion\.com/i.exec(masterAuto)?.[1] ?? "dmxleo") : null;
    const dcOk = dc && !dc.startsWith("cdndirector") ? dc : null;
    const vid2 = masterAuto ? /video\/([a-z0-9]+)\.m3u8/i.exec(masterAuto)?.[1] : null;
    if (dcOk && vid2) {
      for (const preset of ["H264-1280x720", "H264-1920x1080", "H264-854x480"]) {
        if (tryPlaylist(`https://${dcOk}.dailymotion.com/cdn/${preset}/vCRN/mp4_h264_aac.m3u8?video=${vid2}`, QUAL.P720)) { emittedAny = true; break; }
      }
    }
  }
  if (!emittedAny) {
    for (const m of json.matchAll(/https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"']*/g)) {
      if (emittedAny) break;
      if (!/\/av1|_av1_/i.test(m[0]) && tryPlaylist(m[0], null)) emittedAny = true;
    }
  }
  if (emittedAny) { emittedVids.add(vid); return { ok: found, vid }; }

  for (const m of json.matchAll(/"(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"/g)) {
    const h = Number(m[1]);
    const url = unescJson(m[3]);
    const q = Number.isNaN(h) ? QUAL.Unknown : h >= 1080 ? QUAL.P1080 : h >= 720 ? QUAL.P720 : h >= 480 ? QUAL.P480 : h >= 360 ? QUAL.P360 : QUAL.Unknown;
    if (/\/av1|_av1_/i.test(url)) continue;
    doEmit(url, url.includes(".m3u8"), q, { progType: m[2], note: "progresivo qualities map" });
  }
  if (!found) {
    let cand = /auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json)?.[1];
    cand = cand ? unescJson(cand) : /https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i.exec(json)?.[0];
    if (cand && !/dmxleo|advertising|auth=/i.test(cand)) doEmit(cand, true, QUAL.Unknown, { note: "fallback master sin validar" });
  }
  if (found) emittedVids.add(vid);
  return { ok: found, vid };
}

// ---------------------------------------------------------------- CE.extractOkRu (y fallback propio Tio:23, texto identico)
async function ceExtractOkRu(embedUrl0, siteReferer, linkName, emit) {
  const url = embedUrl0.startsWith("//") ? `https:${embedUrl0}` : embedUrl0;
  const r = curl(url, { referer: siteReferer, timeout: 20 });
  if (r.status !== 200) return { ok: false, gate: `okru embed HTTP ${r.status}${/cloudflare|challenge/i.test(r.body) ? " (CF)" : ""}` };
  const raw = /data-options="([^"]+)"/.exec(r.body)?.[1];
  if (!raw) return { ok: false, gate: `okru: sin data-options (HTTP ${r.status})` };
  const options = decodeUnicode(raw.split("&quot;").join('"').split("\\/").join("/"));
  let found = false;
  const cands = [...options.matchAll(/"(?:[a-zA-Z]*ManifestUrl|ondemandHls)":"([^"]+)"/g)].map((m) => m[1]);
  let hlsUrl = null;
  for (const c of cands) {
    const t = curl(c, { referer: url, timeout: 20 });
    if (t.status === 200 && t.body.trimStart().startsWith("#EXTM3U")) { hlsUrl = c; break; }
  }
  if (hlsUrl) {
    try { for (const l of await generateM3u8(linkName, hlsUrl, url)) emit({ ...l, note: "CE.extractOkRu" }); found = true; }
    catch (e) { emit._errs = (emit._errs ?? []).concat(`okru generateM3u8: ${e.message}`); }
  }
  for (const m of options.matchAll(/"name":"(mobile|lowest|low|sd|hd|full|super)","url":"([^"]+)"/g)) {
    let p = decodeUnicode(m[2]);
    if (!p.startsWith("http")) continue;
    if (p.startsWith("//")) p = "https:" + p;
    const q = m[1] === "full" || m[1] === "super" ? QUAL.P1080 : m[1] === "hd" ? QUAL.P720 : m[1] === "sd" ? QUAL.P480 : QUAL.P360;
    emit({ source: linkName, name: linkName, url: p, type: "VIDEO", quality: q, referer: url, note: "CE.okru progresivo" });
    found = true;
  }
  return { ok: found, hlsUrl };
}
const extractTdOkRuFallback = ceExtractOkRu;

// ---------------------------------------------------------------- extractTdRumble (Kotlin:110)
async function extractTdRumble(embedUrl, referer, name, emit) {
  extractTdRumble.lastGate = null;
  const vid = /rumble\.com\/(?:embed\/)?(v[a-zA-Z0-9]+)/.exec(embedUrl)?.[1];
  if (vid) {
    const api = `https://rumble.com/embedJS/u3/?request=video&v=${vid}&embed=${encodeURIComponent(embedUrl)}`;
    const emitFrom = (json) => {
      const fixed = json.split("\\/").join("/");
      let n = 0;
      for (const m of fixed.matchAll(/"(\d{3,4})":\["(https:\/\/[a-z0-9.-]*rumble\.cloud\/video\/[A-Za-z0-9\/\._-]+\.mp4)"/g)) {
        const h = Number(m[1]);
        emit({ source: name, name, url: m[2], type: "VIDEO", quality: h >= 1080 ? QUAL.P1080 : h >= 720 ? QUAL.P720 : h >= 480 ? QUAL.P480 : QUAL.P360, referer: "https://rumble.com/", note: "Rumble embedJS" });
        n++;
      }
      return n;
    };
    let r = curl(api, { referer: "https://rumble.com/", timeout: 20 });
    let count = r.status && r.body.trim() ? emitFrom(r.body) : 0;
    let ev = `embedJS#1 HTTP ${r.status} len=${r.body.length}`;
    if (count === 0) {
      const e = curl(embedUrl, { referer, timeout: 20 });
      ev += ` | embed HTTP ${e.status}${/just a moment|cloudflare|challenge/i.test(e.body) ? " (CF challenge)" : ""}`;
      r = curl(api, { referer: embedUrl, timeout: 20 });
      ev += ` | embedJS#2 HTTP ${r.status} len=${r.body.length} body='${r.body.slice(0, 60).replace(/\s+/g, " ")}'`;
      if (r.status && r.body.trim()) count = emitFrom(r.body);
    }
    if (count > 0) return true;
    extractTdRumble.lastGate = ev;
  }
  const html = curl(embedUrl, { referer, timeout: 20 });
  const fixed = html.body.split("\\/").join("/");
  const hlsUrl = /"hls":\{"url":"(https:\/\/rumble\.com\/hls-vod\/[^"]+)"/.exec(fixed)?.[1];
  if (hlsUrl) {
    try { for (const l of await generateM3u8(name, hlsUrl, embedUrl)) emit({ ...l, note: "Rumble hls-vod" }); return true; }
    catch (e) { extractTdRumble.lastGate = `${extractTdRumble.lastGate ?? ""} hls-vod gen: ${e.message}`; }
  }
  const qualities = [...fixed.matchAll(/"(\d{3,4})":\{"url":"(https:\/\/[a-z0-9.-]*rumble\.cloud\/video\/[A-Za-z0-9\/\._-]+\.mp4)"/g)];
  if (qualities.length) {
    const seen = new Set();
    for (const m of qualities) {
      if (seen.has(m[2])) continue; seen.add(m[2]);
      const h = Number(m[1]);
      emit({ source: name, name, url: m[2], type: "VIDEO", quality: h >= 1080 ? QUAL.P1080 : h >= 720 ? QUAL.P720 : h >= 480 ? QUAL.P480 : QUAL.P360, referer: embedUrl, note: "Rumble mapa calidades HTML" });
    }
    return true;
  }
  const first = /https:\/\/[a-z0-9.-]*rumble\.cloud\/video\/[A-Za-z0-9\/\._-]+\.mp4/.exec(fixed)?.[0];
  if (!first) return false;
  emit({ source: name, name, url: first, type: "VIDEO", quality: QUAL.Unknown, referer: embedUrl, note: "Rumble primer mp4" });
  return true;
}

// ---------------------------------------------------------------- extractTdStreamTape (Kotlin:212)
async function extractTdStreamTape(embedUrl, referer, name, emit) {
  const html = curl(embedUrl, { referer, timeout: 20 });
  const direct = /(?:https?:)?\/\/[a-z0-9-]+\.streamtape[a-z.]*\/get_video\?[^"'\s<>]+/i.exec(html.body)?.[0];
  if (!direct) return false;
  const fixedUrl = direct.startsWith("//") ? "https:" + direct : direct;
  emit({ source: name, name, url: fixedUrl, type: "VIDEO", quality: QUAL.Unknown, referer: embedUrl, note: "StreamTape get_video" });
  return true;
}

// ---------------------------------------------------------------- extractTdGoogleDrive (Kotlin:886)
function extractTdGoogleDrive(embedUrl, serverName, emit) {
  const id = /drive\.google\.com\/(?:file\/d\/|open\?id=)([a-zA-Z0-9_-]{10,})/.exec(embedUrl)?.[1];
  if (!id) return false;
  emit({ source: "Drive", name: "Drive", url: `https://drive.usercontent.google.com/download?id=${id}&export=download&confirm=t`, type: "VIDEO", quality: QUAL.Unknown, referer: "https://drive.google.com/", note: "Drive Vip transform pura" });
  return true;
}

// ---------------------------------------------------------------- tdGetWithChallenge (Kotlin:1030)
async function tdGetWithChallenge(startUrl, referer, maxSteps = 5) {
  let current = startUrl;
  for (let step = 0; step < maxSteps; step++) {
    const resp = curlHead(current, { referer: referer || "", timeout: 20 });
    if (resp.status === 0) return null;
    if (resp.status >= 300 && resp.status <= 399) {
      // redirect de publicidad: la cookie sid queda en el jar; reintentar la MISMA URL
      // (curl -s sin -L no captura Location en el cuerpo: leerla via %{redirect_url})
      const loc = curlRedirect(current, referer);
      if (loc && !resp.err) { /* Kotlin NO sigue: reintenta current */ }
      continue;
    }
    if (resp.status === 429) { await sleep(2500); continue; }
    const text = resp.body;
    const jsRedirect = /window\.location\.replace\('([^']+)'/.exec(text)?.[1];
    if (jsRedirect) { current = jsRedirect; continue; }
    if (text.length > 1500) return text;
  }
  return null;
}
function curlRedirect(url, referer) {
  // lee Location de un 3xx sin seguirlo
  const args = ["-s", "-m", "20", "-A", UA, "-b", JAR, "-c", JAR, "-o", "/dev/null", "-w", "%{redirect_url}", url];
  if (referer) args.splice(args.length - 1, 0, "-e", referer);
  try { return execFileSync("curl", args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim(); } catch { return ""; }
}

// ---------------------------------------------------------------- extractTdPacked / FromHtml / FromPage
async function extractTdPackedFromHtml(html, referer, serverName, emit) {
  const unpacked = getAndUnpack(html);
  let found = false;
  const mh = /["']?hls["']?\s*:\s*\{[^}]*?["']?(?:url|file)["']?\s*:\s*["']([^"']+)["']/.exec(unpacked);
  if (mh) { try { for (const l of await generateM3u8(serverName, mh[1], referer)) emit({ ...l, note: "packed hls" }); found = true; } catch {} }
  if (!found) {
    for (const m of unpacked.matchAll(/["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']/g)) {
      try { for (const l of await generateM3u8(serverName, m[1], referer)) emit({ ...l, note: "packed file m3u8" }); found = true; } catch {}
    }
  }
  if (!found) {
    for (const m of unpacked.matchAll(/(https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*)/g)) {
      try { for (const l of await generateM3u8(serverName, m[1], referer)) emit({ ...l, note: "packed m3u8 directo" }); found = true; } catch {}
    }
  }
  if (!found) {
    for (const m of unpacked.matchAll(/["']?file["']?\s*:\s*["']([^"']+\.mp4[^"']*)["']/g)) {
      emit({ source: serverName, name: serverName, url: m[1], type: "VIDEO", quality: QUAL.Unknown, referer, note: "packed mp4" });
      found = true;
    }
  }
  return found;
}
async function extractTdPackedFromChallenge(embedUrl, referer, serverName, emit) {
  const html = await tdGetWithChallenge(embedUrl, referer);
  if (!html) return false;
  return extractTdPackedFromHtml(html, referer, serverName, emit);
}
async function extractTdPacked(embedUrl, referer, serverName, emit) {
  const html = curl(embedUrl, { referer, timeout: 20 }).body;
  return extractTdPackedFromHtml(html, referer, serverName, emit);
}
async function extractTdFromPage(playerUrl, referer, serverName, emit) {
  const text = curl(playerUrl, { referer, timeout: 15 }).body;
  for (const m of text.matchAll(/(https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*)/g)) {
    try { for (const l of await generateM3u8(serverName, m[1], playerUrl)) emit({ ...l, note: "fromPage" }); return true; } catch {}
  }
  for (const m of text.matchAll(/(https?:\/\/[^"'\s<>]+\.mp4[^"'\s<>]*)/g)) {
    emit({ source: serverName, name: serverName, url: m[1], type: "VIDEO", quality: QUAL.Unknown, referer: playerUrl, note: "fromPage mp4" });
    return true;
  }
  return false;
}

// ---------------------------------------------------------------- extractTdDailymotion (Kotlin:1110)
async function extractTdDailymotion(embedUrl, referer, serverName, emit) {
  const m = /dailymotion\.com\/(?:embed\/)?video\/([a-zA-Z0-9]+)|geo\.dailymotion\.com\/player\.html\?video=([a-zA-Z0-9]+)/.exec(embedUrl);
  const videoId = m ? (m[1] || m[2]) : null;
  if (!videoId) return false;
  curl(embedUrl, { referer, timeout: 15 }); // pre-warm
  if ((await ceExtractDailymotion(`https://www.dailymotion.com/embed/video/${videoId}`, referer, serverName, tdEmittedDmSubs, tdEmittedDmVids, emit)).ok) return true;
  // fallback legacy (Kotlin:1125-1148)
  const res = curl(`https://www.dailymotion.com/player/metadata/video/${videoId}`, { referer: embedUrl, headers: ["Accept: application/json"], timeout: 15 });
  const json = res.body.split("\\/").join("/");
  const ma = /"qualities"\s*:\s*\{\s*"auto"\s*:\s*\[\s*\{[^}]*?"url"\s*:\s*"([^"]+\.m3u8[^"]*)"/.exec(json);
  if (ma) { try { for (const l of await generateM3u8(serverName, ma[1], embedUrl)) emit({ ...l, note: "legacy qualities auto" }); return true; } catch {} }
  for (const m2 of json.matchAll(/(https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*)/g)) {
    const u = m2[1];
    if (u.includes("dmxleo.dailymotion.com") || u.includes("[APIFRAMEWORKS]") || u.includes("[VASTVERSIONS]")) continue;
    try { for (const l of await generateM3u8(serverName, u, embedUrl)) emit({ ...l, note: "legacy m3u8 scan" }); return true; } catch {}
  }
  for (const m2 of json.matchAll(/(https?:\/\/[^"'\s<>]+\.mp4[^"'\s<>]*)/g)) {
    emit({ source: serverName, name: serverName, url: m2[1], type: "VIDEO", quality: QUAL.Unknown, referer: embedUrl, note: "legacy mp4" });
    return true;
  }
  return false;
}

// ---------------------------------------------------------------- extractTdMultiPlayer (Kotlin:908)
async function extractTdMultiPlayer(embedUrl, referer, name, emit, linkCounter) {
  const html = curl(embedUrl, { referer, timeout: 20 }).body;
  const fromGo = [...html.matchAll(/go_to_player\('([^']+)'\)/g)].map((m) => m[1].trim());
  const fromSplit = html.split("go_to_player").slice(1).map((seg) => /\(\s*['"]?([a-z0-9.-]+\.[a-z]{2,}[^'"\s]*)['"]?\)/i.exec(seg)?.[1]?.trim()).filter(Boolean);
  const embeds = [...new Set([...fromGo, ...fromSplit].filter((x) => x.startsWith("http")))];
  let any = false;
  for (const child of embeds) {
    const before = linkCounter.n;
    try { await processTdEmbed(child, embedUrl, serverLabelFromUrl(child), emit, linkCounter); } catch {}
    if (linkCounter.n > before) any = true;
  }
  if (!any) {
    for (const m of html.matchAll(/(https?:\/\/[a-zA-Z0-9.-]+\/[a-zA-Z0-9/._+?=&-]+)/g)) {
      const cand = m[1];
      if (/tiodonghua|googletagmanager|google-analytics|gstatic|fontawesome|cloudflare|yandex|jquery/i.test(cand)) continue;
      const before = linkCounter.n;
      try { await processTdEmbed(cand, embedUrl, serverLabelFromUrl(cand), emit, linkCounter); } catch {}
      if (linkCounter.n > before) { any = true; break; }
    }
  }
  return any;
}

// ---------------------------------------------------------------- routing
const serverLabelFromUrl = (url) => {
  try { const h = new URL(url).hostname.replace(/^www\./, ""); return (h.split(".")[0] || "Server").replace(/^./, (c) => c.toUpperCase()); } catch { return "Server"; }
};
function tdServerPriority(embedUrl) {
  const u = embedUrl.toLowerCase();
  if (u.includes("rumble.com")) return 3;
  if (u.includes("dailymotion.com") || u.includes("dai.ly")) return 3;
  if (u.includes("drive.google.com")) return 2;
  if (u.includes("ok.ru") || u.includes("odnoklassniki")) return 1;
  return 0;
}
// dedupe de instancia: loadLinks SI limpia ambos (Tio:732-733)
const tdEmittedDmSubs = new Set();
const tdEmittedDmVids = new Set();

async function processTdEmbed(embedUrl, referer, serverName, emit, linkCounter) {
  const u = embedUrl.trim();
  if (u.includes("playerp2p.online")) return false; // P2P/WebRTC: no jugable en ExoPlayer (Kotlin:969)
  if (u.includes("terabox")) return false; // sin API anonima (Kotlin:979)
  if (u.includes("dailymotion.com")) return await extractTdDailymotion(u, referer, serverName, trackedEmit(emit, linkCounter));
  if (u.includes("ok.ru") || u.includes("odnoklassniki")) {
    const before = linkCounter.n;
    const a = await ceExtractOkRu(u, referer, serverName, trackedEmit(emit, linkCounter));
    if (a.ok) return true;
    return (await extractTdOkRuFallback(u, referer, serverName, trackedEmit(emit, linkCounter))).ok;
  }
  if (u.includes("rumble.com")) { const before = linkCounter.n; const ok = await extractTdRumble(u, referer, serverName, emit); return ok; }
  if (u.includes("player.tiodonghua.com")) return extractTdMultiPlayer(u, referer, serverName, emit, linkCounter);
  if (u.includes("streamtape") || u.includes("strcloud")) return extractTdStreamTape(u, referer, serverName, emit);
  if (u.includes("modagamers")) return extractTdPackedFromChallenge(u, referer, serverName, emit);
  if (/wish|sblona|ahvsh|filelions|luluvdo|vgembed|vgfplay|vidguard|byse|asnwish/.test(u)) return extractTdPacked(u, referer, serverName, emit);
  if (u.includes("drive.google.com")) return extractTdGoogleDrive(u, serverName, emit);
  // else: loadExtractor (registry, no portable) + packed + fromPage (Kotlin:1010-1017)
  if (await extractTdPacked(u, referer, serverName, emit)) return true;
  return extractTdFromPage(u, referer, serverName, emit);
}
// contador simple estilo Kotlin (links++ en cb): emit incrementa por fuera
const linkCounter = { n: 0 };
function trackedEmit(emit, lc) { return (l) => { lc.n++; emit(l); }; }
function linkCounterCount(lc, before) { return lc.n > before; }

// tdNormalizeEmbed (Kotlin:817)
function tdNormalizeEmbed(raw) {
  const t = raw.split('\\"').join('"').split("\\/").join("/").trim();
  if (t.startsWith("http")) {
    const end = [...t].findIndex((c) => c === '"' || c === "\\");
    const s = (end === -1 ? t : t.slice(0, end)).trim();
    return s || null;
  }
  if (/<iframe/i.test(t) || /<div/i.test(t)) {
    const src = /src\s*=\s*["']([^"']+)["']/i.exec(t)?.[1];
    if (src) return src.trim();
  }
  return null;
}

// fetchTdPlayerEmbed (Kotlin:833)
async function fetchTdPlayerEmbed(post, nume, type) {
  const resp = curl(`${MAIN}/wp-admin/admin-ajax.php`, {
    method: "POST",
    form: { action: "doo_player_ajax", post, nume, type },
    timeout: 20,
  });
  return /"embed_url"\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(resp.status === 200 ? resp.body : "")?.[1] ?? "";
}

// ---------------------------------------------------------------- loadLinks (Kotlin:723-810)
async function loadLinks(data) {
  const LINKS = [];
  const emit = (l) => LINKS.push(l);
  const gates = [];
  linkCounter.n = 0;
  tdEmittedDmVids.clear(); // Tio:732-733 limpia AMBOS sets (bien)
  tdEmittedDmSubs.clear();

  const doc = curl(data, { timeout: 30 });
  const html = doc.body;
  const bodyPostId = /<body[^>]*class="[^"]*postid-(\d+)/.exec(html)?.[1] ?? null;

  const requests = new Map(); // key -> [post,nume,type,label]
  // OJO: tiodonghua.lat renderiza los li con comillas SIMPLES; jsoup (Kotlin) es
  // agnostico de comillas, asi que el puerto regex tambien debe serlo.
  const liRe = /<li[^>]*class=["'][^"']*dooplay_player_option[^"']*["'][^>]*>[\s\S]*?<\/li>/g;
  let idx = 0;
  let lm;
  while ((lm = liRe.exec(html))) {
    const block = lm[0];
    const tag = block.slice(0, block.indexOf(">") + 1);
    const isVip = /is-vip-server/.test(tag);
    const nume = (/id=["']player-option-(\d+)["']/.exec(tag)?.[1]) ?? String(idx + 1);
    const dataPost = /data-post=["']([^"']*)["']/.exec(tag)?.[1] ?? "";
    const dataType = /data-type=["']([^"']*)["']/.exec(tag)?.[1] ?? "";
    const post = !isVip && dataPost ? dataPost : bodyPostId;
    if (!post) { idx++; continue; } // Kotlin: return@forEachIndexed
    const type = !isVip ? (dataType || "tv") : "tv";
    const labelRaw = /<span class=["']title["'][^>]*>([\s\S]*?)<\/span>/.exec(block)?.[1]?.replace(/<[^>]*>/g, "").trim();
    const label = labelRaw || `Server ${nume}`;
    requests.set(`${post}_${nume}_${type}`, [post, nume, type, label]);
    idx++;
  }
  const serverNames = [...requests.values()].map((r) => `${r[1]}:${r[3]}`);

  if (requests.size > 0) {
    const results = [];
    for (const [post, nume, type, label] of requests.values()) {
      // Kotlin lo hace concurrente (async); secuencial aqui (mismo resultado)
      let raw = "";
      try { raw = await fetchTdPlayerEmbed(post, nume, type); } catch {}
      results.push([raw, label]);
    }
    // v24.13 sortedByDescending estable (comparador descendente + índice ascendente de empate)
    const prioOf = (raw) => tdServerPriority(tdNormalizeEmbed(raw) ?? "");
    const ordered2 = results.map((r, i) => ({ r, i })).sort((a, b) => prioOf(b.r[0]) - prioOf(a.r[0]) || a.i - b.i).map((x) => x.r);
    for (const [raw, label] of ordered2) {
      const embedUrl = tdNormalizeEmbed(raw);
      if (embedUrl) {
        gates.push(`AJAX ${label} -> ${embedUrl.slice(0, 110)}`);
        try { await processTdEmbed(embedUrl, data, label, emit, linkCounter); }
        catch (e) { gates.push(`  ${label}: EXCEPT ${e.message}`); }
      } else gates.push(`AJAX ${label} -> SIN embed util: '${raw.slice(0, 60)}'`);
    }
  }

  // Fallback 1: iframes directos (Kotlin:783) — OJO: solo si src.startsWith("http")
  if (linkCounter.n === 0 && LINKS.length === 0) {
    for (const f of html.matchAll(/<iframe[^>]*>/g)) {
      const tag = f[0];
      const sm = /(?:data-)?src=["']([^"']+)["']/.exec(tag);
      const s = (sm?.[1] ?? "").trim();
      if (s.startsWith("http")) {
        gates.push(`iframe fallback -> ${s.slice(0, 110)}`);
        try { await processTdEmbed(s, data, serverLabelFromUrl(s), emit, linkCounter); } catch {}
      }
    }
  }
  // Fallback 2: m3u8/mp4 en el HTML (Kotlin:794)
  if (LINKS.length === 0) {
    let foundLinks = false;
    for (const m of html.matchAll(/(https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*)/g)) {
      try { for (const l of await generateM3u8("Server", m[1], data)) emit({ ...l, note: "HTML m3u8" }); foundLinks = true; } catch {}
    }
    if (!foundLinks) {
      for (const m of html.matchAll(/(https?:\/\/[^"'\s<>]+\.mp4[^"'\s<>]*)/g)) {
        emit({ source: "Server", name: "Server", url: m[1], type: "VIDEO", quality: QUAL.Unknown, referer: data, note: "HTML mp4" });
        foundLinks = true;
      }
    }
  }
  return { links: LINKS, gates, servers: serverNames };
}

// ---------------------------------------------------------------- discovery
async function discoverEpisodes() {
  const home = curl(`${MAIN}/`, { timeout: 30 });
  // article.item.episodes (seccion "EPISODIOS" del home): span.serie + a /episodios/
  const arts = [];
  for (const block of home.body.split(/<article/).slice(1)) {
    const b = "<article" + block.slice(0, 2500);
    const cls = /class="([^"]*)"/.exec(b)?.[1] ?? "";
    if (!/\bitem\b/.test(cls) || !/\bepisodes\b/.test(cls)) continue;
    const href = /<a[^>]*href="(https?:\/\/[^"]*\/episodios\/[^"]+)"/.exec(b)?.[1];
    const serie = /<span class="serie">([\s\S]*?)<\/span>/.exec(b)?.[1]?.replace(/<[^>]*>/g, "").trim();
    const serieHref = /<span class="serie">[\s\S]*?href="([^"]+)"/.exec(b)?.[1];
    const h3 = /<h3[^>]*>([\s\S]*?)<\/h3>/.exec(b)?.[1]?.replace(/<[^>]*>/g, "").trim();
    if (href) arts.push({ href, serie: serie || serieHref?.replace(/\/+$/, "").split("/").pop() || "?", serieHref, epText: h3 ?? "" });
  }
  console.log(`  home: ${arts.length} article.item.episodes`);
  const bySeries = new Map();
  for (const a of arts) {
    const sKey = a.serieHref || a.href;
    if (!bySeries.has(sKey)) bySeries.set(sKey, a); // primero = mas reciente
  }
  const seriesUrls = [...bySeries.keys()].slice(0, 3);
  const picked = [];
  const taken = new Set();
  for (const su of seriesUrls) {
    const a = bySeries.get(su);
    if (!taken.has(a.href)) { taken.add(a.href); picked.push({ series: a.serie, ep: epNum(a.href, a.epText), data: a.href, kind: "reciente" }); }
  }
  // antiguos: ficha /donghua/slug/ -> ul.episodios (numerando), el minimo >=3
  for (const su of seriesUrls) {
    if (picked.length >= 5) break;
    const ficha = curl(su, { timeout: 30 });
    const region = /<ul[^>]*class="[^"]*episodios[^"]*"[\s\S]*?<\/ul>/.exec(ficha.body)?.[0];
    if (!region) { console.log(`  ${su}: ul.episodios AUSENTE`); continue; }
    const eps = [];
    for (const li of region.matchAll(/<li[\s\S]*?<\/li>/g)) {
      const href = /<div class="episodiotitle">[\s\S]*?<a[^>]*href="([^"]+)"/.exec(li[0])?.[1] ?? /<a[^>]*href="(https?:\/\/[^"]*\/episodios\/[^"]+)"/.exec(li[0])?.[1];
      if (!href) continue;
      const num = /<div class="numerando">([^<]*)</.exec(li[0])?.[1]?.trim() ?? "";
      const parts = num.split("-");
      const season = Number(parts[0]?.trim()) || 1;
      const epN = Number(parts[1]?.trim()) || Number(/-episodio-(\d+)/.exec(href)?.[1] ?? 0);
      eps.push({ href, season, epN });
    }
    console.log(`  ${su}: ${eps.length} episodios en ul.episodios`);
    const old = eps.filter((e) => e.epN >= 3).sort((a, b) => a.epN - b.epN)[0] ?? eps[eps.length - 1];
    if (old && !taken.has(old.href) && picked.length < 5) {
      taken.add(old.href);
      picked.push({ series: (su || "").replace(/\/+$/, "").split("/").pop().replace(/-/g, " "), ep: old.epN, data: old.href, kind: "antiguo" });
    }
  }
  // relleno con los siguientes recientes
  if (picked.length < 5) {
    for (const a of arts) {
      if (picked.length >= 5) break;
      if (!taken.has(a.href)) { taken.add(a.href); picked.push({ series: a.serie, ep: epNum(a.href, a.epText), data: a.href, kind: "relleno" }); }
    }
  }
  return picked.slice(0, 5);
}
function epNum(href, txt) {
  return Number(/-episodio-(\d+)/.exec(href)?.[1] ?? /(?:Episodio|Cap[íi]tulo)\s*(\d+)/i.exec(txt)?.[1] ?? 0);
}

// ---------------------------------------------------------------- VALIDATION (CURL only)
const AUDIO_RE = /mp4a|ac-3|ec-3|opus|alac|flac|aac|mp3/i;
function validateLink(link) {
  const ref = link.referer || "";
  if (link.type === "M3U8" || /\.(m3u8|txt)(\?|$)/.test(link.url)) {
    const r = curl(link.url, { referer: ref, timeout: 30 });
    if (r.status !== 200)
      return { playable: false, audio: null, reason: `playlist HTTP ${r.status}${r.err ? " " + r.err : ""}${/just a moment|attention required|cf-chl/i.test(r.body) ? " (Cloudflare)" : ""}` };
    const body = r.body.trimStart();
    if (!body.startsWith("#EXTM3U")) {
      const ad = /<vmap|VMAP|VAST|<Ad |<VideoAd/i.test(r.body);
      return { playable: false, audio: null, reason: `no #EXTM3U (HTTP 200, ${ad ? "VMAP/VAST AD XML" : "XML/HTML"}): '${body.slice(0, 40).replace(/\s+/g, " ")}'` };
    }
    const master = /#EXT-X-STREAM-INF/.test(r.body);
    let mediaUrl = link.url;
    let attrsCodecs = link.codecs ?? "";
    const hasAudioGroup = /#EXT-X-MEDIA:TYPE=AUDIO/.test(r.body);
    if (master) {
      const lines = r.body.replace(/\r/g, "").split("\n").map((l) => l.trim());
      for (let i = 0; i < lines.length; i++) {
        if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
        const codecs = /CODECS="([^"]+)"/.exec(lines[i])?.[1] ?? "";
        if (link.isMaster && /av01|hev1|hvc1/i.test(codecs)) continue;
        for (let j = i + 1; j < lines.length; j++) {
          if (!lines[j]) continue;
          if (lines[j].startsWith("#")) break;
          mediaUrl = new URL(lines[j], link.url).href;
          attrsCodecs = attrsCodecs || codecs;
          break;
        }
        if (mediaUrl !== link.url) break;
      }
      if (mediaUrl === link.url) return { playable: false, audio: null, reason: "master sin variantes jugables" };
    }
    const mv = curl(mediaUrl, { referer: ref, timeout: 30 });
    if (mv.status !== 200) return { playable: false, audio: null, reason: `media playlist HTTP ${mv.status}` };
    if (!mv.body.trimStart().startsWith("#EXTM3U")) {
      if (/<vmap|VMAP|VAST/i.test(mv.body)) return { playable: false, audio: null, reason: "media playlist es VMAP/VAST ad XML" };
      return { playable: false, audio: null, reason: "media playlist no #EXTM3U" };
    }
    const seg = mv.body.replace(/\r/g, "").split("\n").map((l) => l.trim()).find((l) => l && !l.startsWith("#"));
    if (!seg) return { playable: false, audio: null, reason: "playlist sin segmentos" };
    const segUrl = new URL(seg, mediaUrl).href;
    const sg = curl(segUrl, { referer: ref, range: "0-1023", timeout: 30 });
    if (sg.status !== 200 && sg.status !== 206) return { playable: false, audio: null, reason: `segmento HTTP ${sg.status}` };
    let audio = null;
    if (/#EXT-X-MEDIA:TYPE=AUDIO/.test(mv.body) || hasAudioGroup) audio = "si (EXT-X-MEDIA:TYPE=AUDIO)";
    else if (attrsCodecs && AUDIO_RE.test(attrsCodecs)) audio = "si (CODECS muxed)";
    else if (/\.(ts|mp2t|m4s)(\?|&|$)/i.test(segUrl)) audio = "si (TS muxed, heuristica)";
    else if (/mp2t/i.test(sg.ctype || "")) audio = "si (TS muxed, Content-Type video/mp2t)";
    else if (attrsCodecs) audio = "NO (video-only CODECS)";
    const videoOnly = audio === "NO (video-only CODECS)";
    return { playable: !videoOnly, videoOnly, audio, reason: `${master ? "master->" : ""}segmento HTTP ${sg.status} (${sg.ctype || "n/a"}), codecs='${attrsCodecs || "n/a"}'` };
  }
  // Drive: usercontent puede pedir confirmacion con form HTML -> detectarlo
  const r = curl(link.url, { referer: ref, range: "0-1023", timeout: 30 });
  const ct = (r.ctype || "").toLowerCase();
  if (/^text\/html|^application\/xml|^text\/xml/.test(ct) || /^\s*<(html|!doctype|\?xml)/i.test(r.body))
    return { playable: false, audio: null, reason: `HTTP ${r.status} con HTML/XML (confirm/anti-bot): ct='${ct}'` };
  if ((r.status === 200 || r.status === 206) && (/^video\//.test(ct) || (ct.includes("octet-stream") && r.body.length > 100)))
    return { playable: true, audio: "n/a (progresivo: no verificable sin ffprobe)", reason: `HTTP ${r.status} ${ct || "octet-stream"}, Range 0-1023 OK (${r.body.length} B)` };
  return { playable: false, audio: null, reason: `HTTP ${r.status}${r.err ? " " + r.err : ""} ct='${ct || "-"}'` };
}
function validateSub(sub) {
  const r = curl(sub.url, { referer: DM_REFERER, timeout: 30 });
  const ok = r.status === 200 && (/^\s*WEBVTT/.test(r.body) || /\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->/.test(r.body));
  return { ok, why: `HTTP ${r.status} ct='${r.ctype}' head='${r.body.slice(0, 24).replace(/\s+/g, " ")}'` };
}

// ---------------------------------------------------------------- main
const eps = await discoverEpisodes();
console.log(`\n=== TioDonghuaProvider: ${eps.length} episodios ===`);
eps.forEach((e, i) => console.log(`  [${i + 1}] ${e.series} EP ${e.ep} (${e.kind}) -> ${e.data}`));
if (eps.length < 5) console.log("  AVISO: descubrimiento <5 episodios");

const rows = [];
const subChecks = [];
for (const e of eps) {
  console.log(`\n--- ${e.series} EP ${e.ep} (${e.kind}) ---`);
  const t0 = Date.now();
  const out = await loadLinks(e.data);
  console.log(`  loadLinks=${out.links.length > 0} en ${((Date.now() - t0) / 1000).toFixed(1)}s | servidores Dooplay: ${out.servers.join(", ") || "-"} | fuentes: ${out.links.length}`);
  for (const l of out.links) console.log(`  SRC ${l.source} | ${l.name} | ${l.type} | ${qName(l.quality)} | ${l.note ?? ""} | ${l.url.slice(0, 110)}`);
  for (const g of out.gates) console.log(`  gate: ${g}`);
  const results = [];
  for (const l of out.links) {
    const v = validateLink(l);
    results.push({ l, v });
    console.log(`    => ${v.playable ? "OK " : "BAD"} [${l.source}] ${l.type} ${qName(l.quality)} audio=${v.audio ?? "?"} :: ${v.reason}`);
  }
  for (const s of subsDetected.splice(0)) {
    const vc = validateSub(s);
    subChecks.push({ ep: `${e.series} EP${e.ep}`, ...s, ...vc });
    console.log(`    SUB ${s.lang} ${vc.ok ? "VTT/SRT OK" : "BAD"} :: ${vc.why} [descartado: subtitleCallback={ } en Tio:1121] :: ${s.url.slice(0, 90)}`);
  }
  const playable = results.filter((r) => r.v.playable);
  const withAudio = playable.filter((r) => r.v.audio && !/^n\/a/.test(r.v.audio));
  const videoOnly = results.filter((r) => r.v.videoOnly);
  const gated = results.filter((r) => /Cloudflare|anti-bot|confirm|HTTP 4\d\d|VMAP/i.test(r.v.reason));
  let verdict, reason;
  if (!out.links.length) { verdict = "FAIL"; reason = `0 fuentes (${out.gates.filter((g) => g.includes("SIN embed")).length} servidores AJAX sin embed util)`; }
  else if (playable.length > 0) { verdict = "PASS"; reason = `${playable.length}/${out.links.length} playables (${withAudio.length} con audio verificado)${videoOnly.length ? `; ${videoOnly.length} video-only` : ""}`; }
  else { verdict = gated.length === results.length ? "PARTIAL" : "FAIL"; reason = gated.length ? `gated/bot con evidencia: ${gated[0].v.reason}` : results[0]?.v.reason ?? "sin playables"; }
  rows.push({ ep: `${e.series} EP${e.ep} (${e.kind})`, servers: out.servers.length, emitted: out.links.length, playable: `${playable.length}`, verdict, reason });
}

console.log("\n================= TABLA FINAL TioDonghua =================");
const w = [40, 8, 8, 10, 9, 64];
const pad = (c) => c.map((x, i) => String(x).padEnd(w[i])).join(" | ").trimEnd();
console.log(pad(["episodio", "servers", "fuentes", "playables", "veredicto", "motivo"]));
console.log(pad(w.map((x) => "-".repeat(x))));
for (const r of rows) console.log(pad([r.ep.slice(0, 40), r.servers, r.emitted, r.playable, r.verdict, r.reason.slice(0, 64)]));
console.log(`\nSubs DM detectados: ${subChecks.length}, VTT/SRT OK: ${subChecks.filter((s) => s.ok).length}`);
console.log("NOTA: Tio pasa subtitleCallback={ } a CE (Tio:1121) -> los subs NUNCA llegan a la app.");
