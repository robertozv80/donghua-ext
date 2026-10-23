// Live validator for SeriesDonghuaProvider.kt (faithful Node-24 port of
// loadLinks + processSdEmbed + extractSd* + CommonExtractors.extractDailymotion/
// extractOkRu as they exist TODAY in
//   SeriesDonghuaProvider/src/main/kotlin/com/donghuaext/SeriesDonghuaProvider.kt
//   common/src/CommonExtractors.kt
// jsoup selectors re-implemented as raw regex on HTML. NO npm packages.
// ALL final-URL validation uses CURL (undici/Node fetch gets fake 403s from
// Akamai/Cloudflare due to TLS fingerprint). Read-only against the live site.
//
// Usage: node tools/matrix_seriesdonghua.mjs

import { execFileSync } from "node:child_process";
import fs from "node:fs";

const MAIN = "https://seriesdonghua.com";
// SD_USER_AGENT (Kotlin:14-15)
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
const DM_REFERER = "https://www.dailymotion.com/";
const QUAL = { P2160: 2160, P1440: 1440, P1080: 1080, P720: 720, P480: 480, P360: 360, Unknown: 400 };
const qName = (q) => (q === QUAL.Unknown || q == null ? "Unknown" : `${q}p`);
fs.mkdirSync(`${import.meta.dirname}/tmp`, { recursive: true });
const JAR = `${import.meta.dirname}/tmp/sd_curl_cookies.txt`;
try { fs.rmSync(JAR, { force: true }); } catch {}
try { fs.writeFileSync(JAR, ""); } catch {}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- curl (validation workhorse)
const MARK = "@@CURLMETA@@";
function curl(url, { referer = "", headers = [], range = null, timeout = 30, form = null, method = null, noFollow = false } = {}) {
  const args = ["-s", "-m", String(timeout), "-A", UA];
  if (!noFollow) args.push("-L");
  if (referer) args.push("-e", referer);
  if (range) args.push("-r", range);
  args.push("-b", JAR, "-c", JAR); // OkHttp-style cookie jar (Laravel session, Knp_client etc.)
  if (form) for (const [k, v] of Object.entries(form)) args.push("--data-urlencode", `${k}=${v}`);
  for (const h of headers) args.push("-H", h);
  if (method) args.push("-X", method);
  args.push("-w", `\n${MARK}%{http_code}|%{content_type}|%{url_effective}`);
  args.push(url);
  let buf;
  try {
    buf = execFileSync("curl", args, { encoding: "buffer", maxBuffer: 64 * 1024 * 1024, stdio: ["ignore", "pipe", "ignore"] });
  } catch (e) {
    return { status: 0, ctype: "", body: "", finalUrl: url, err: String(e?.message ?? e).slice(0, 120) };
  }
  const s = buf.toString("latin1");
  const i = s.lastIndexOf(MARK);
  if (i < 0) return { status: 0, ctype: "", body: s.slice(0, 200), finalUrl: url, err: "no curl meta" };
  const meta = s.slice(i + MARK.length).trim().split("|");
  return { status: Number(meta[0]) || 0, ctype: meta[1] || "", finalUrl: meta[2] || url, body: s.slice(0, i).replace(/\r?\n$/, "") };
}

// ---------------------------------------------------------------- helpers (Kotlin mirrors)
const resolveUrl = (u) => (u.startsWith("http") ? u : u.startsWith("//") ? "https:" + u : u.startsWith("/") ? MAIN + u : u ? `${MAIN}/${u}` : "");
const unescJson = (s) =>
  s.replace(/\\u([\da-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
   .replace(/\\(["\\/nrt])/g, (_, c) => (c === "n" ? "\n" : c === "t" ? "\t" : c === "r" ? "\r" : c));
const dmVideoId = (src) => /(?:geo\.dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|dailymotion\.com\/embed\/video\/)([A-Za-z0-9]+)/i.exec(src)?.[1] ?? null;
const decodeUnicode = (s) => s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));

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
  const map = { fr: "French", pt: "Portuguese", ru: "Russian", tr: "Turkish", pl: "Polish", ar: "Arabic", bn: "Bengali", fa: "Persian", id: "Indonesian", ms: "Malay", it: "Italian", de: "German", hi: "Hindi", th: "Thai", vi: "Vietnamese" };
  for (const k of Object.keys(map)) if (code.startsWith(k)) return map[k];
  return code;
}
function getAndUnpack(text) {
  const lines = text.split("\n");
  for (const l of lines) {
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

// M3u8Helper.generateM3u8 (used by CE.okru / rumble hls-vod / voe / generic)
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
const subsDetected = []; // NOTE: SD llama con subtitleCallback={ } -> nunca llegan a la app
async function ceExtractDailymotion(iframeSrc, embedderReferer, linkName, emittedSubs, emittedVids, emit) {
  const vid = dmVideoId(iframeSrc) || (/^[A-Za-z0-9]+$/.test(iframeSrc.trim()) ? iframeSrc.trim() : null);
  if (!vid) return { ok: false, gate: "dmVideoId no match" };
  if (emittedVids.has(vid)) return { ok: true, gate: `dedupe: ${vid} ya emitido en este episodio` };
  const referer = embedderReferer || "https://animexin.dev";
  const metaUrl = `https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(referer)}&integration=inline&app=com.dailymotion.neon`;
  const res = curl(metaUrl, { referer, headers: ["Accept: application/json"], timeout: 20 });
  if (res.status !== 200) return { ok: false, gate: `metadata HTTP ${res.status}${res.err ? " " + res.err : ""}` };
  const json = res.body.split("\\/").join("/").split("\\u0026").join("&");

  // CE.emitDmSubtitles (line 97)
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
  // CE:229 tryPlaylist v8: master SIEMPRE se emite; variantes H264 expandidas; AV1/HEVC descartadas
  const tryPlaylist = (u, hlsQ) => {
    if (!u || !u.startsWith("http")) return false;
    if (/dmxleo|advertising|auth=/i.test(u)) return false;
    const v = curl(u, { referer: DM_REFERER, timeout: 12 });
    const master = v.status === 200 ? v.body : "";
    if (!master.trimStart().startsWith("#EXTM3U")) { doEmit(u, true, hlsQ, { note: "master crudo (validacion fallo, se emite igual)" }); return true; }
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
      doEmit(abs, true, q, { codecs, attrs, note: "variante expandida (AV1/HEVC excluidas)" });
      expanded = true;
    }
    doEmit(u, true, hlsQ, { isMaster: true, note: expanded ? "master 'Auto'" : "master (sin variantes)" });
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

// ---------------------------------------------------------------- CE.extractOkRu (CommonExtractors.kt:487) + fallback propio (idempotente)
async function ceExtractOkRu(embedUrl0, siteReferer, linkName, emit) {
  const url = embedUrl0.startsWith("//") ? `https:${embedUrl0}` : embedUrl0;
  const r = curl(url, { referer: siteReferer, timeout: 20 });
  if (r.status !== 200) return { ok: false, gate: `okru embed HTTP ${r.status}${/cloudflare|challenge/i.test(r.body) ? " (CF)" : ""}` };
  const raw = /data-options="([^"]+)"/.exec(r.body)?.[1];
  if (!raw) return { ok: false, gate: `okru: sin data-options (HTTP ${r.status}, ${/video removed|not available/i.test(r.body) ? "video eliminado" : "page sin player"})` };
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
const extractSdOkRu = ceExtractOkRu; // Kotlin fallback propio (SD:638) es texto-identico al CE

// ---------------------------------------------------------------- extractSdRumble (Kotlin:706)
async function extractSdRumble(embedUrl, referer, name, emit) {
  extractSdRumble.lastGate = null;
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
    extractSdRumble.lastGate = ev;
  }
  const html = curl(embedUrl, { referer, timeout: 20 });
  const fixed = html.body.split("\\/").join("/");
  const hlsUrl = /"hls":\{"url":"(https:\/\/rumble[.]com\/hls-vod\/[^"]+)"/.exec(fixed)?.[1];
  if (hlsUrl) {
    try { for (const l of await generateM3u8(name, hlsUrl, embedUrl)) emit({ ...l, note: "Rumble hls-vod" }); return true; }
    catch (e) { extractSdRumble.lastGate = `${extractSdRumble.lastGate ?? ""} hls-vod generateM3u8: ${e.message}`; }
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

// ---------------------------------------------------------------- extractSdVoe (Kotlin:802)
async function extractSdVoe(videoUrl, referer, serverName, emit) {
  let r = curl(videoUrl, { referer, timeout: 20 });
  let html = r.body;
  if (html.length < 1200 && html.includes("window.location.href")) {
    const dest = /window\.location\.href\s*=\s*'(https:\/\/[^']+)'/.exec(html)?.[1];
    if (dest) html = curl(dest, { referer: videoUrl, timeout: 20 }).body;
  }
  for (const m of html.matchAll(/(https?:\/\/[^"'\s<>]+\.m3u8[^\s"'<>]*)/g)) {
    try { for (const l of await generateM3u8(serverName, m[1], videoUrl)) emit({ ...l, note: "extractSdVoe" }); return true; } catch {}
  }
  for (const m of html.matchAll(/(https?:\/\/[^"'\s<>]+\.mp4[^\s"'<>]*)/g)) {
    emit({ source: serverName, name: serverName, url: m[1], type: "VIDEO", quality: QUAL.Unknown, referer: videoUrl, note: "extractSdVoe mp4" });
    return true;
  }
  return false;
}

// ---------------------------------------------------------------- extractSdGeneric (Kotlin:836)
async function extractSdGeneric(playerUrl, referer, serverName, emit) {
  const text = curl(playerUrl, { referer, timeout: 15 }).body;
  const scan = async (body, tag) => {
    for (const m of body.matchAll(/(https?:\/\/[^"'\s<>]+\.m3u8[^"'<>]*)/g)) {
      try { for (const l of await generateM3u8(serverName, m[1], playerUrl)) emit({ ...l, note: tag }); return true; } catch {}
    }
    for (const m of body.matchAll(/(https?:\/\/[^"'\s<>]+\.mp4[^"'<>]*)/g)) {
      emit({ source: serverName, name: serverName, url: m[1], type: "VIDEO", quality: QUAL.Unknown, referer: playerUrl, note: tag + " mp4" });
      return true;
    }
    return false;
  };
  if (await scan(text, "extractSdGeneric")) return true;
  return await scan(getAndUnpack(text), "extractSdGeneric unpack");
}

// ---------------------------------------------------------------- processSdEmbed (Kotlin:566)
// sdEmittedSubs: set de instancia que loadLinks NO limpia (Kotlin:629 vs 430)
const sdEmittedSubs = new Set();
let sdEmittedDmVids = new Set(); // SI se limpia por episodio (Kotlin:430)

async function processSdEmbed(embedUrl, referer, serverName, emit) {
  const u = embedUrl.trim();
  if (u.includes("dailymotion.com") || u.includes("dai.ly")) {
    const videoId = /(?:dailymotion\.com|dai\.ly)\/(?:embed\/)?(?:video\/|player(?:\/[a-z0-9]+)?\.html\?video=)([a-zA-Z0-9]+)/.exec(u)?.[1];
    if (!videoId) return { ok: false, gate: "extractSdDailymotion: videoId no matcheado" };
    const canonical = `https://www.dailymotion.com/embed/video/${videoId}`;
    curl(canonical, { referer, timeout: 15 }); // pre-warm cookies
    const r = await ceExtractDailymotion(canonical, referer, serverName, sdEmittedSubs, sdEmittedDmVids, emit);
    return { ok: r.ok, gate: r.ok ? null : r.gate ?? "CE.extractDailymotion sin links" };
  }
  if (u.includes("ok.ru") || u.includes("odnoklassniki")) {
    const a = await ceExtractOkRu(u, referer, serverName, emit);
    if (a.ok) return { ok: true, via: "CE" };
    const b = await extractSdOkRu(u, referer, serverName, emit);
    return { ok: b, gate: b ? null : `CE: ${a.gate ?? "sin links"}` };
  }
  if (u.includes("rumble.com")) {
    const ok = await extractSdRumble(u, referer, serverName, emit);
    return { ok, gate: ok ? null : `rumble: ${extractSdRumble.lastGate ?? "embedJS y HTML sin mp4/hls-vod"}` };
  }
  if (u.includes("voe.") || u.includes("voeunblk") || u.includes("audaciousdefaulthouse")) {
    const ok = await extractSdVoe(u, referer, serverName, emit);
    return { ok, gate: ok ? null : "extractSdVoe: sin m3u8/mp4" };
  }
  // Kotlin: loadExtractor(u, ...) del registry (no portable: fuera de alcance) + extractSdGeneric
  const gen = await extractSdGeneric(u, referer, serverName, emit);
  return { ok: gen, gate: gen ? null : "extractSdGeneric: sin m3u8/mp4" };
}

// extractSdEmbedsFromHtml (Kotlin:588)
async function extractSdEmbedsFromHtml(html, referer, emit) {
  let found = 0;
  const re = /(https?:\/\/[^"'\s<>]*(?:dailymotion\.com\/(?:embed\/)?video|dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|ok\.ru\/videoembed|rumble\.com\/embed|voe\.[a-z]+\/e\/)[^"'\s<>]*)/g;
  const embeds = [...new Set([...html.matchAll(re)].map((m) => m[1].split("\\/").join("/")))].map((x) => (x.startsWith("//") ? "https:" + x : x));
  for (const embed of embeds) {
    const label = embed.includes("dailymotion") ? "Dailymotion" : embed.includes("ok.ru") ? "Ok" : embed.includes("rumble") ? "Rumble" : "Voe";
    const r = await processSdEmbed(embed, referer, label, emit);
    if (r.ok) found++;
  }
  return found;
}

// serverLabelFromBtn (Kotlin:558)
function serverLabelFromBtn(html, fromIndex) {
  const win = html.slice(fromIndex, Math.min(html.length, fromIndex + 600));
  const s = (/<span>([^<]+)<\/span>/.exec(win)?.[1]?.trim() ?? "Servidor");
  return s.charAt(0).toUpperCase() + s.slice(1);
}

// ---------------------------------------------------------------- loadLinks (Kotlin:423-555)
async function loadLinks(data) {
  const LINKS = [];
  const emit = (l) => LINKS.push(l);
  const gates = [];
  const tabs = [];
  sdEmittedDmVids.clear(); // Kotlin:430

  const pageResp = curl(data, { timeout: 30 });
  let html = pageResp.body;
  let episodePage = data;

  if (!data.includes("-episodio-")) {
    const firstEp = /href="(\/[a-z0-9-]+-episodio-\d+\/)"/.exec(html)?.[1];
    if (firstEp) {
      episodePage = MAIN + firstEp;
      html = curl(episodePage, { timeout: 30 }).body;
    } else {
      const btnHref = /<a[^>]*href="(\/[^"]+)"[^>]*class="btn-stream[^"]*"/.exec(html)?.[1] ?? /<a[^>]*class="btn-stream[^"]*"[^>]*href="(\/[^"]+)"/.exec(html)?.[1];
      for (const cand of [btnHref, `/${data.replace(/\/+$/, "").split("/").pop()}-1/`].filter(Boolean)) {
        const probe = curl(MAIN + cand, { timeout: 30 });
        if (probe.body.includes("server-tab-btn")) { episodePage = MAIN + cand; html = probe.body; break; }
      }
    }
  }
  if (!episodePage.includes("-episodio-") && !html.includes("server-tab-btn")) {
    const n = await extractSdEmbedsFromHtml(html, data, emit);
    return { links: LINKS, gates: [`ficha sin server-tab-btn; embeds sueltos HTML=${n}`], tabs, processed: n > 0 };
  }

  const csrf = /<meta name="csrf-token" content="([^"]+)"/.exec(html)?.[1] ?? "";
  let processed = false;
  for (const m of html.matchAll(/<button[^>]*class="server-tab-btn[^"]*"[^>]*>/gs)) {
    const tag = m[0];
    const videoId = /data-video-id="(\d+)"/.exec(tag)?.[1];
    const serverIndex = /data-server-index="(\d+)"/.exec(tag)?.[1];
    if (!videoId || !serverIndex) continue;
    const serverName = serverLabelFromBtn(html, m.index);
    tabs.push(`${serverIndex}:${serverName}`);
    let resp = "";
    for (let attempt = 0; attempt < 2 && !resp.includes("embed_url"); attempt++) {
      if (resp.length) { await sleep(150); curl(episodePage, { timeout: 20 }); } // re-plantar sesion (v24.6)
      const r = curl(`${MAIN}/api/player/get-server`, {
        method: "POST",
        form: { video_id: videoId, server_index: serverIndex },
        headers: ["X-CSRF-TOKEN: " + csrf, "X-Requested-With: XMLHttpRequest", "Accept: application/json"],
        referer: episodePage,
        timeout: 20,
      });
      resp = r.status === 200 ? r.body : `HTTP ${r.status} ${r.body.slice(0, 100)}`;
    }
    if (!resp.includes("embed_url")) { gates.push(`servidor ${serverIndex} (${serverName}): sin embed_url -> ${resp.slice(0, 100)}`); continue; }
    const embed = /"embed_url"\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(resp)?.[1]?.split("\\/").join("/");
    if (!embed) { gates.push(`${serverName}: embed_url vacio`); continue; }
    gates.push(`POST ${serverIndex} (${serverName}) -> ${embed.slice(0, 120)}`);
    try {
      const r = await processSdEmbed(embed, episodePage, serverName, emit);
      if (r.ok) processed = true;
      else if (r.gate) gates.push(`  ${serverName}: ${r.gate}`);
    } catch (e) { gates.push(`  ${serverName}: EXCEPT ${e.message}`); }
  }
  if (!processed) {
    if ((await extractSdEmbedsFromHtml(html, episodePage, emit)) > 0) processed = true;
    else gates.push("respaldo HTML: 0 embeds");
  }
  return { links: LINKS, gates, tabs, processed };
}

// ---------------------------------------------------------------- discovery (getMainPage + load)
async function discoverEpisodes() {
  const home = curl(`${MAIN}/`, { timeout: 30 });
  const cards = [];
  for (const block of home.body.split(/<article[^>]*class="[^"]*donghua-card/).slice(1)) {
    const b = block.slice(0, 1500);
    const href = /<a[^>]*href="(\/[^"]+)"/.exec(b)?.[1];
    const title = /<h3 class="card-title">([^<]+)<\/h3>/.exec(b)?.[1]?.trim();
    if (href && title) cards.push({ href: resolveUrl(href), title });
  }
  const epCards = cards.filter((c) => c.href.includes("-episodio-"));
  console.log(`  home: ${cards.length} article.donghua-card, ${epCards.length} de episodio`);
  const bySeries = new Map();
  for (const c of epCards) {
    const path = c.href.replace(MAIN, "").replace(/\/+$/, "").replace(/^\//, "").split("?")[0];
    const m = /^(.+)-episodio-\d+$/.exec(path);
    const seriesUrl = m ? `${MAIN}/${m[1]}/` : c.href;
    if (!bySeries.has(seriesUrl)) bySeries.set(seriesUrl, c.title);
  }
  const picked = [];
  const seriesData = [];
  for (const [seriesUrl, title] of [...bySeries.entries()].slice(0, 6)) {
    const ficha = curl(seriesUrl, { timeout: 30 });
    const eps = [];
    const seen = new Set();
    for (const block of ficha.body.split(/<article[^>]*class="[^"]*episode-card-item/).slice(1)) {
      const b = block.slice(0, 1500);
      const tag = b.slice(0, b.indexOf(">") + 1);
      const href = /<a[^>]*href="([^"]*?-episodio-[^"]+)"/.exec(b)?.[1];
      if (!href) continue;
      const url = resolveUrl(href);
      if (seen.has(url)) continue;
      seen.add(url);
      const ep = Number(/data-ep="(\d+)"/.exec(tag)?.[1] ?? NaN) || Number(/-episodio-(\d+)/.exec(url)?.[1] ?? 0);
      eps.push({ ep, url });
    }
    if (!eps.length) {
      for (const m2 of ficha.body.matchAll(/href="(\/[a-z0-9-]+-episodio-\d+\/)"/g)) {
        const url = resolveUrl(m2[1]);
        if (seen.has(url)) continue;
        seen.add(url);
        eps.push({ ep: Number(/-episodio-(\d+)/.exec(url)[1]), url });
      }
      if (eps.length) console.log(`  ${seriesUrl}: grid episode-card-item AUSENTE -> respaldo regex (Kotlin:254)`);
    }
    eps.sort((a, b) => a.ep - b.ep);
    if (!eps.length) continue;
    console.log(`  ${seriesUrl}: ${eps.length} eps (EP ${eps[0].ep}..${eps[eps.length - 1].ep})`);
    seriesData.push({ title, eps });
  }
  const taken = new Set();
  const push = (s, e, kind) => {
    if (!e || taken.has(e.url) || picked.length >= 5) return;
    taken.add(e.url);
    picked.push({ series: s.title, ep: e.ep, data: e.url, kind });
  };
  for (const s of seriesData.slice(0, 3)) push(s, s.eps[s.eps.length - 1], "reciente");
  let antiguo = null;
  for (const s of seriesData) {
    const e = s.eps.find((x) => x.ep >= 3);
    if (e && (!antiguo || e.ep < antiguo.e.ep)) antiguo = { s, e };
  }
  push(antiguo?.s, antiguo?.e, "antiguo");
  for (const s of seriesData.slice(3)) push(s, s.eps[s.eps.length - 1], "relleno");
  for (const s of seriesData) push(s, s.eps[Math.max(0, s.eps.length - 2)], "relleno");
  return picked.slice(0, 5);
}

// ---------------------------------------------------------------- VALIDATION (CURL only)
const AUDIO_RE = /mp4a|ac-3|ec-3|opus|alac|flac|aac|mp3/i;
function validateLink(link) {
  if (/test-videos\.co\.uk|bigbuckbunny/i.test(link.url))
    return { playable: false, audio: null, reason: "DECOY: mp4 de ejemplo anti-bot de voe (Big Buck Bunny), NO es el episodio" };
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
        if (link.isMaster && /av01|hev1|hvc1/i.test(codecs)) continue; // primera variante jugable
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
    // AUDIO: grupo EXT-X-MEDIA:TYPE=AUDIO, CODECS muxed, o heuristica TS (simple, sin PAT/PMT)
    let audio = null;
    if (/#EXT-X-MEDIA:TYPE=AUDIO/.test(mv.body) || hasAudioGroup) audio = "si (EXT-X-MEDIA:TYPE=AUDIO)";
    else if (attrsCodecs && AUDIO_RE.test(attrsCodecs)) audio = "si (CODECS muxed)";
    else if (/\.(ts)(\?|$)/i.test(segUrl)) audio = "si (TS muxed, heuristica)";
    else if (attrsCodecs) audio = "NO (video-only CODECS)";
    const videoOnly = audio === "NO (video-only CODECS)";
    return { playable: !videoOnly, videoOnly, audio, reason: `${master ? "master->" : ""}segmento HTTP ${sg.status} (${sg.ctype || "n/a"}), codecs='${attrsCodecs || "n/a"}'` };
  }
  // VIDEO progresivo: curl -r 0-1023 + Content-Type
  const r = curl(link.url, { referer: ref, range: "0-1023", timeout: 30 });
  const ct = (r.ctype || "").toLowerCase();
  if (/^text\/html|^application\/xml|^text\/xml/.test(ct) || /^\s*<(html|!doctype|\?xml)/i.test(r.body))
    return { playable: false, audio: null, reason: `HTTP ${r.status} con HTML/XML (anti-bot): ct='${ct}'` };
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
console.log(`\n=== SeriesDonghuaProvider: ${eps.length} episodios (getMainPage -> load) ===`);
eps.forEach((e, i) => console.log(`  [${i + 1}] ${e.series} EP ${e.ep} (${e.kind}) -> ${e.data}`));
if (eps.length < 5) console.log("  AVISO: descubrimiento <5 episodios");

const rows = [];
const subChecks = [];
for (const e of eps) {
  console.log(`\n--- ${e.series} EP ${e.ep} (${e.kind}) ---`);
  const t0 = Date.now();
  const out = await loadLinks(e.data);
  console.log(`  loadLinks=${out.processed} en ${((Date.now() - t0) / 1000).toFixed(1)}s | servidores: ${out.tabs.join(", ") || "-"} | fuentes: ${out.links.length}`);
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
    console.log(`    SUB ${s.lang} ${vc.ok ? "VTT/SRT OK" : "BAD"} :: ${vc.why}${s.suppressed ? " [suprimido: set sdEmittedSubs no-limpiado]" : " [descartado: subtitleCallback={ } en SD:624]"} :: ${s.url.slice(0, 90)}`);
  }
  const playable = results.filter((r) => r.v.playable);
  const withAudio = playable.filter((r) => r.v.audio && !/^n\/a/.test(r.v.audio));
  const videoOnly = results.filter((r) => r.v.videoOnly);
  let verdict, reason;
  if (!out.links.length) { verdict = "FAIL"; reason = `0 fuentes (${out.gates.filter((g) => g.includes("sin embed_url")).length} servidores sin embed_url)`; }
  else if (withAudio.length > 0 || playable.length > 0) { verdict = "PASS"; reason = `${playable.length}/${out.links.length} playables (${withAudio.length} con audio verificado)${videoOnly.length ? `; ${videoOnly.length} video-only excluidas` : ""}`; }
  else {
    const gated = results.filter((r) => /Cloudflare|anti-bot|HTTP 4\d\d|VMAP/i.test(r.v.reason));
    verdict = gated.length === results.length ? "PARTIAL" : "FAIL";
    reason = gated.length ? `gated/bot con evidencia: ${gated[0].v.reason}` : results[0]?.v.reason ?? "sin playables";
  }
  rows.push({ ep: `${e.series} EP${e.ep} (${e.kind})`, servers: out.tabs.length, emitted: out.links.length, playable: `${playable.length}`, verdict, reason });
}

console.log("\n================= TABLA FINAL SeriesDonghua =================");
const w = [40, 8, 8, 10, 9, 64];
const pad = (c) => c.map((x, i) => String(x).padEnd(w[i])).join(" | ").trimEnd();
console.log(pad(["episodio", "servers", "fuentes", "playables", "veredicto", "motivo"]));
console.log(pad(w.map((x) => "-".repeat(x))));
for (const r of rows) console.log(pad([r.ep.slice(0, 40), r.servers, r.emitted, r.playable, r.verdict, r.reason.slice(0, 64)]));
console.log(`\nSubs DM detectados: ${subChecks.length}, VTT/SRT OK: ${subChecks.filter((s) => s.ok).length}, suprimidos por set no-limpiado: ${subChecks.filter((s) => s.suppressed).length}`);
console.log("NOTA: SD pasa subtitleCallback={ } a CE (SD:624) -> los subs NUNCA llegan a la app.");
