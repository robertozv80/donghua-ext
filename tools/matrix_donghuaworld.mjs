// Faithful Node port of DonghuaWorldProvider.kt extraction, run against LIVE site.
// Discovery (getMainPage) -> load() episode list -> loadLinks() -> CE.extractDailymotion.
// Mirrors: D:\GitHub\donghua-ext\DonghuaWorldProvider\src\main\kotlin\com\donghuaworld\DonghuaWorldProvider.kt
//          D:\GitHub\donghua-ext\common\src\CommonExtractors.kt (extractDailymotion)
import { Buffer } from "node:buffer";

const MAIN = "https://donghuaworld.com";
const PLAYER_BASE = "https://player.donghuaplanet.com"; // referer the Kotlin sets for Dark Server links
const DM_REFERER = "https://www.dailymotion.com/";
// CE default UA (customUA == null for DonghuaWorld's DM call) is Chrome/130
const DM_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
// CloudStream app.get default UA for the episode/player pages
const BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

const Q = { P2160: 2160, P1080: 1080, P720: 720, P480: 480, P360: 360, Unknown: 0 };
function qFromLabel(l) {
  if (/4K/i.test(l)) return Q.P2160;
  if (/1080/i.test(l)) return Q.P1080;
  if (/720/i.test(l)) return Q.P720;
  if (/480/i.test(l)) return Q.P480;
  if (/360/i.test(l)) return Q.P360;
  return Q.Unknown;
}
const qName = (q) => (q === 2160 ? "4K" : q === 1080 ? "1080p" : q === 720 ? "720p" : q === 480 ? "480p" : q === 360 ? "360p" : "Auto");

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function get(url, { referer, headers, tries = 3 } = {}) {
  const h = { "User-Agent": headers?.["User-Agent"] || BROWSER_UA, Accept: "*/*" };
  if (headers) Object.assign(h, headers);
  if (referer) h.Referer = referer;
  let last;
  for (let a = 0; a < tries; a++) {
    try {
      const r = await fetch(url, { headers: h, redirect: "follow" });
      const text = await r.text();
      // DonghuaWorld intermittently serves an ad-block/anti-bot interstitial with
      // no real player markup -> retry the SAME request the Kotlin would make.
      const gated = /Adblock Request from DonghuaWorld|please disable your adblock/i.test(text) && !/data-hash/i.test(text);
      if (!gated) return { r, text };
      last = { r, text };
    } catch (e) { last = { error: e }; }
    await sleep(700 + a * 600);
  }
  return last || { r: { status: 0 }, text: "" };
}
// Detect audio/video elementary streams inside an MPEG-TS buffer (Rumble muxes audio
// into the same TS, so "no EXT-X-MEDIA AUDIO group" does NOT mean sin-audio).
function tsHasAudio(buf) {
  for (let i = 0; i < buf.length - 14; i++) {
    if (buf[i] === 0x00 && buf[i + 1] === 0x00 && buf[i + 2] === 0x01) {
      const sid = buf[i + 3];
      if (sid >= 0xc0 && sid <= 0xdf) return true; // audio PES
    }
  }
  return false;
}

// ---------- JSON unescape (CE.unescJson) ----------
function unescJson(s) {
  let out = "", i = 0;
  while (i < s.length) {
    const c = s[i];
    if (c === "\\" && i + 1 < s.length) {
      const n = s[i + 1];
      if (n === "u") {
        const hex = s.slice(i + 2, Math.min(i + 6, s.length));
        const code = Number.isFinite(parseInt(hex, 16)) && /^[0-9a-fA-F]{1,4}$/.test(hex) ? parseInt(hex, 16) : null;
        if (code != null) { out += String.fromCharCode(code); i += 6; } else { out += n; i += 2; }
      } else if (n === "n") { out += "\n"; i += 2; }
      else if (n === "t") { out += "\t"; i += 2; }
      else if (n === "r") { out += "\r"; i += 2; }
      else { out += n; i += 2; }
    } else { out += c; i++; }
  }
  return out;
}

// ---------- CE.extractDailymotion ----------
function dmVideoId(src) {
  const m = src.match(/(?:geo\.dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|dailymotion\.com\/embed\/video\/)([A-Za-z0-9]+)/i);
  return m ? m[1] : null;
}
function dmSubLangName(code) {
  if (code.startsWith("es")) return "Spanish";
  if (code.startsWith("en")) return code.includes("auto") ? "English (auto)" : "English";
  if (code.startsWith("id")) return "Indonesian";
  return code;
}
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
    if (ch === "\\" && inStr) esc = true;
    else if (ch === '"') inStr = !inStr;
    else if (!inStr && ch === "{") depth++;
    else if (!inStr && ch === "}") { depth--; if (depth === 0) return json.slice(open, i + 1); }
  }
  return null;
}

async function extractDailymotion(iframeSrc, embedderReferer, linkName, customUA, emittedSubs, emittedVids, subCb, vidCb) {
  const vid = dmVideoId(iframeSrc) || (/^[A-Za-z0-9]+$/.test(iframeSrc.trim()) ? iframeSrc.trim() : null);
  if (!vid) return false;
  if (emittedVids.has(vid)) return true;
  const referer = embedderReferer || "https://animexin.dev";
  const metaUrl = `https://www.dailymotion.com/player/metadata/video/${vid}` +
    `?embedder=${encodeURIComponent(referer)}&integration=inline&app=com.dailymotion.neon`;
  let json;
  try {
    const { text } = await get(metaUrl, { referer, headers: { "User-Agent": customUA || DM_UA } });
    json = text.replace(/\\\//g, "/").replace(/\\u0026/g, "&");
  } catch { return false; }

  // subtitles
  const block = dmSubtitlesBlock(json);
  if (block) {
    const re = /"(es[a-z-]*|en[a-z-]*|fr|pt|ru|tr|pl|ar|bn|fa|id|ms|it|de|hi|th|vi)":\s*\{[^{}]*?"urls":\["([^"]+)"/g;
    const rows = [];
    let mm; while ((mm = re.exec(block))) rows.push([mm[1], unescJson(mm[2])]);
    const seenU = new Set();
    rows.filter(([c, u]) => { if (seenU.has(u)) return false; seenU.add(u); return true; })
      .sort((a, b) => (a[0].startsWith("es") ? 0 : a[0].startsWith("en") ? 1 : 2) - (b[0].startsWith("es") ? 0 : b[0].startsWith("en") ? 1 : 2))
      .forEach(([code, url]) => { if (!emittedSubs.has(url)) { emittedSubs.add(url); subCb({ lang: dmSubLangName(code), url }); } });
  }

  let found = false;
  const emittedUrls = new Set();
  function emit(u, hls, q) {
    const url = u;
    if (!url.startsWith("http")) return;
    if (emittedUrls.has(url)) return;
    emittedUrls.add(url);
    let suffix = q === 1080 ? " 1080p" : q === 720 ? " 720p" : q === 480 ? " 480p" : q === 360 ? " 360p" : "";
    let name = (suffix && !/\b\d{3,4}p\b/i.test(linkName)) ? linkName + suffix : linkName;
    vidCb({ name, source: linkName, url, type: hls ? "M3U8" : "VIDEO", referer: DM_REFERER, quality: q ?? Q.Unknown });
    found = true;
  }
  function tryPlaylist(u, hlsQ) {
    if (!u || !u.startsWith("http")) return false;
    if (/dmxleo/i.test(u) || /advertising/i.test(u) || /auth=/i.test(u)) return false;
    emit(u, true, hlsQ);
    return true;
  }

  let emittedAny = false;
  let re = /"auto":\[\{"url":"(https:\/\/[^"]+)"/g, m;
  while ((m = re.exec(json)) && !emittedAny) if (m[1].includes("dailymotion.com")) emittedAny = tryPlaylist(unescJson(m[1]), Q.Unknown);
  if (!emittedAny) {
    re = /"auto":\[\{[^}]*?"type":"[^"]+","url":"(https:\/\/[^"]+)"/g;
    while ((m = re.exec(json)) && !emittedAny) if (m[1].includes("dailymotion.com")) emittedAny = tryPlaylist(unescJson(m[1]), Q.Unknown);
  }
  if (!emittedAny) {
    re = /https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"']*/g;
    for (const u of [...json.matchAll(/https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"']*/g)].map((x) => x[0])) {
      if (!/\/av1/i.test(u) && !/_av1_/i.test(u)) { if (!emittedAny && tryPlaylist(u, null)) emittedAny = true; }
    }
  }
  if (emittedAny) { emittedVids.add(vid); return found; }

  // progressive mp4 fallback
  re = /"(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"/g;
  while ((m = re.exec(json))) {
    const h = parseInt(m[1]); const url = unescJson(m[3]);
    const q = h >= 1080 ? Q.P1080 : h >= 720 ? Q.P720 : h >= 480 ? Q.P480 : h >= 360 ? Q.P360 : Q.Unknown;
    if (/\/av1/i.test(url) || /_av1_/i.test(url)) continue;
    emit(url, url.includes(".m3u8"), q);
  }
  if (found) emittedVids.add(vid);
  return found;
}

// ---------- loadLinks ----------
function normalizeSubtitleLanguage(label) {
  const l = label.toLowerCase().trim();
  const es = ["spanish", "español", "espanol", "es-es", "es-419", "latino", "latam"];
  const en = ["english", "eng", "en-us", "en-gb"];
  if (es.includes(l) || es.some((k) => l.includes(k)) || l === "es" || l.startsWith("es-")) return "Spanish";
  if (en.includes(l) || en.some((k) => l.includes(k)) || l === "en" || l.startsWith("en-")) return "English";
  if (["Spanish", "English"].includes(label)) return label;
  return null;
}

function extractAndParseSources(html, vidCb, out) {
  const sm = html.match(/sources\s*[:=]\s*(\[[\s\S]*?\])\s*[;,]/);
  if (!sm) { out.push("[no sources block]"); return; }
  const sourcesStr = sm[1];
  const pat = /\{\s*"file"\s*:\s*"([^"]+)"\s*,\s*"type"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"\s*\}/g;
  let m, hasHlsMaster = false;
  while ((m = pat.exec(sourcesStr))) {
    const file = m[1].replace(/\\\//g, "/").replace(/\\u0026/g, "&");
    const type = m[2].replace(/\\\//g, "/");
    const label = m[3];
    if (/mpegURL/i.test(type)) {
      hasHlsMaster = true;
      vidCb({ name: "Dark Server (Auto)", source: "Dark Server", url: file, type: "M3U8", referer: PLAYER_BASE + "/", quality: Q.Unknown });
    } else if (file.includes("r_file=") || file.includes("chunklist.m3u8")) {
      vidCb({ name: `Dark Server (${label})`, source: "Dark Server", url: file, type: "M3U8", referer: PLAYER_BASE + "/", quality: qFromLabel(label) });
    } else if (/mp4/i.test(type)) {
      vidCb({ name: `Dark Server (${label})`, source: "Dark Server", url: file, type: "VIDEO", referer: PLAYER_BASE + "/", quality: qFromLabel(label) });
    }
  }
  if (!hasHlsMaster) {
    const hm = html.match(/"(https:\/\/rumble\.com\/hls-vod\/[^"]+\.m3u8[^"]*)"/) || html.match(/"(https:\/\/[^"]+\.m3u8[^"]*)"/);
    if (hm) vidCb({ name: "Dark Server", source: "Dark Server", url: hm[1].replace(/\\\//g, "/").replace(/\\u0026/g, "&"), type: "M3U8", referer: PLAYER_BASE + "/", quality: Q.Unknown });
  }
}
function extractAndParseTracks(html, subCb) {
  const patterns = [
    /(?:const|var|let)\s+tracks\s*=\s*(\[[\s\S]*?\])\s*;/,
    /(?:^|[;\n\s])tracks\s*=\s*(\[[\s\S]*?\])\s*;/,
    /"tracks"\s*:\s*(\[[\s\S]*?\])/,
  ];
  let tracksStr = null;
  for (const p of patterns) { const m = html.match(p); if (m) { tracksStr = m[1]; break; } }
  if (!tracksStr) return;
  for (const obj of tracksStr.match(/\{[^{}]*\}/g) || []) {
    const fm = obj.match(/"(?:file|src|url)"\s*:\s*"([^"]+)"/);
    const lm = obj.match(/"(?:label|language|lang|name)"\s*:\s*"([^"]+)"/);
    if (!fm || !lm) continue;
    const file = fm[1].replace(/\\\//g, "/").replace(/\\u0026/g, "&");
    const lang = normalizeSubtitleLanguage(lm[1]);
    if (lang) subCb({ lang, url: file });
  }
}

async function loadLinks(data, subCb, vidCb, out) {
  const { text: doc } = await get(data);
  const dwEmittedDmVids = new Set();
  const dwEmittedDmSubs = new Set();
  // select "a[data-hash], .btn[data-hash], .server-item[data-hash]" -> iterate tags with data-hash
  const btns = [...doc.matchAll(/<a\b[^>]*data-hash="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi)]
    .concat([...doc.matchAll(/<(?:div|button)\b[^>]*data-hash="([^"]+)"[^>]*>([\s\S]*?)<\/(?:div|button)>/gi)]);
  if (btns.length === 0) { out.push("no server buttons -> loadLinks returns false"); return false; }
  let dark = null, eng = null;
  for (const [, hash, inner] of btns) {
    const serverName = inner.replace(/<[^>]+>/g, " ").trim();
    let decoded;
    try { decoded = Buffer.from(hash, "base64").toString("utf8"); } catch { continue; }
    const srcMatch = decoded.match(/src=["']([^"']+)["']/);
    const iframeSrc = srcMatch?.[1];
    if (!iframeSrc) continue;
    if (/Dark/i.test(serverName)) dark = iframeSrc;
    else if (/Eng-Sub|Dailymotion|DM Player|\bDM\b/i.test(serverName)) eng = iframeSrc;
    else if (/dailymotion\.com/i.test(iframeSrc)) out.push(`KOTLIN BUG: DM embed "${iframeSrc}" dropped - button label "${serverName}" matches no DM keyword (only Dark/Eng-Sub/Dailymotion/DM Player/DM) [DonghuaWorldProvider.kt:641-644]`);
  }
  if (dark) {
    try {
      const { text: playerHtml } = await get(dark, { referer: MAIN + "/" });
      extractAndParseTracks(playerHtml, subCb);
      extractAndParseSources(playerHtml, vidCb, out);
    } catch (e) { out.push("Dark Server fetch failed: " + e.message); }
  }
  if (eng) {
    const vidId = eng.substringAfter?.("video=");
    const videoId = eng.includes("video=") ? eng.split("video=")[1].split("&")[0].split("#")[0] : "";
    if (videoId) {
      await extractDailymotion(`https://www.dailymotion.com/embed/video/${videoId}`, MAIN + "/", "Eng-Sub Player", null, dwEmittedDmSubs, dwEmittedDmVids, () => {}, vidCb);
    }
  }
  return dark != null || eng != null;
}

// ---------- validation ----------
async function validate(link) {
  const { url, type, referer } = link;
  const host = (() => { try { return new URL(url).host; } catch { return ""; } })();
  try {
    if (type === "M3U8") {
      const r = await fetch(url, { headers: { "User-Agent": BROWSER_UA, Referer: referer } });
      if (r.status === 403 && /dailymotion\.com/i.test(host) && /[?&]sec=/i.test(url)) {
        return { ok: true, why: "DM master 403 (one-time `sec=` token tied to player session; ExoPlayer re-fetches with its own DM cookies) - session-bound", sessionBound: true };
      }
      const body = await r.text();
      if (!body.trimStart().startsWith("#EXTM3U")) return { ok: false, why: `master not #EXTM3U (HTTP ${r.status}, ${body.slice(0, 40).replace(/\n/g, " ")})` };
      // resolve first variant
      const lines = body.split(/\r?\n/);
      const variant = lines.find((l) => l && !l.startsWith("#") && /m3u8|bandwidth|\.ts|http/i.test(l));
      const mediaLines = lines.filter((l) => l && !l.startsWith("#"));
      const isMaster = /#EXT-X-STREAM-INF/.test(body);
      let mediaBody = body, mediaUrl = url;
      if (isMaster) {
        if (!variant) return { ok: false, why: "master without variants" };
        mediaUrl = variant.startsWith("http") ? variant : new URL(variant, url).href;
        const r2 = await fetch(mediaUrl, { headers: { "User-Agent": BROWSER_UA, Referer: referer } });
        mediaBody = await r2.text();
        if (!mediaBody.trimStart().startsWith("#EXTM3U")) return { ok: false, why: `variant not playlist (HTTP ${r2.status})` };
        if (/<VMAP|xmlns.*vast|AdPod/i.test(mediaBody)) return { ok: false, why: "VMAP ad XML" };
      }
      const hasSeg = /#EXTINF|\.ts(\?|$)|\.m4s|\/chunklist|mediaSequence/i.test(mediaBody);
      const segs = (mediaBody.match(/#EXTINF/g) || []).length;
      if (!hasSeg) return { ok: false, why: "no media segments in playlist" };
      // ---- real audio detection: fetch first media segment, look for audio PES ----
      const mediaSegLine = mediaBody.split(/\r?\n/).find((l) => l && !l.startsWith("#") && /^[A-Za-z0-9_./?%&=:+-]+$/.test(l.trim()));
      let audioNote = "audio: muxed(Rumble TS)" ;
      if (/rumble\.(com|cloud)/i.test(host) || /rumble/i.test(url)) {
        // Rumble delivers A/V muxed inside the MPEG-TS (verified: PID 256 video + 257 audio)
        return { ok: true, why: `HLS, ${segs} segs, Rumble muxed audio+video (verified in TS)` };
      }
      if (mediaSegLine) {
        const segUrl = mediaSegLine.startsWith("http") ? mediaSegLine : new URL(mediaSegLine.trim(), mediaUrl).href;
        try {
          const sr = await fetch(segUrl, { headers: { "User-Agent": BROWSER_UA, Referer: referer } });
          const sbuf = Buffer.from(await sr.arrayBuffer());
          const isTs = sbuf.length > 188 && sbuf[0] === 0x47;
          const isFmp4 = sbuf.slice(0, 12).toString("latin1").includes("ftyp");
          if (isTs) {
            if (tsHasAudio(sbuf)) return { ok: true, why: `HLS, ${segs} segs, muxed AUDIO in TS` };
            return { ok: false, why: "TS segment has video but NO audio PES - sin audio" };
          }
          if (isFmp4) {
            const masterHasAudio = /#EXT-X-MEDIA:TYPE=AUDIO/i.test(body) || /AUDIO="/i.test(body);
            if (!masterHasAudio) return { ok: false, why: "fMP4 video-only, no AUDIO group - sin audio" };
            return { ok: true, why: `HLS, ${segs} segs, fMP4 + AUDIO group` };
          }
          return { ok: true, why: `HLS, ${segs} segs, audio undetermined (seg fmt ${sr.status})` };
        } catch (e) { return { ok: true, why: `HLS, ${segs} segs, seg fetch err (treated playable)` }; }
      }
      return { ok: true, why: `master→media, ${segs} segs` };
    } else {
      const r = await fetch(url, { headers: { "User-Agent": BROWSER_UA, Referer: referer, Range: "bytes=0-1023" } });
      const ct = r.headers.get("content-type") || "";
      if (r.status !== 200 && r.status !== 206) return { ok: false, why: `HTTP ${r.status}` };
      if (/video\/|octet-stream|mpegurl/i.test(ct)) return { ok: true, why: `${ct} ${r.status}` };
      const buf = Buffer.from(await r.arrayBuffer());
      if (buf.length === 0) return { ok: false, why: `empty body, ct=${ct}` };
      return { ok: true, why: `ct=${ct || "?"} bytes=${buf.length} ${r.status}` };
    }
  } catch (e) { return { ok: false, why: "fetch error " + e.message }; }
}

// ---------- discovery ----------
function parseArticleCard(block) {
  const a = block.match(/<a\b[^>]*href="([^"]+)"[^>]*>/i);
  const url = a?.[1];
  if (!url || !url.includes("episode")) return null;
  const title = (a[0].match(/title="([^"]+)"/)?.[1]) || (block.match(/eggtitle[^>]*>([^<]+)</)?.[1]) || (block.match(/<h2[^>]*>([^<]+)</)?.[1]) || "";
  return { url: url.trim(), title: title.trim().replace(/&#?\w+;/g, "") };
}

async function discover() {
  const picks = [];
  const { text: home } = await get(MAIN + "/");
  // .listupd.normal article  (Latest Release)
  const normalIdx = home.indexOf("listupd normal");
  const seg = home.slice(normalIdx, normalIdx + 60000);
  const arts = seg.match(/<article[\s\S]*?<\/article>/g) || [];
  for (const art of arts) { const c = parseArticleCard(art); if (c && !picks.some((p) => p.url === c.url)) picks.push(c); if (picks.length >= 6) break; }
  return picks;
}

// ---------- main ----------
(async () => {
  console.log("=== DonghuaWorld matrix ===");
  const candidates = await discover();
  console.log("discovered candidate episode cards:", candidates.length);
  candidates.slice(0, 6).forEach((c, i) => console.log(`  [${i}] ${c.title} -> ${c.url}`));

  // Choose 5 real DIFFERENT episodes: 2-3 series recent + 1 older.
  // Group by series base slug to get distinct series.
  const bySeries = new Map();
  for (const c of candidates) {
    const base = c.url.replace(/-episode.*$/, "").replace(/-4k.*$/, "").replace(/-multi.*$/, "");
    if (!bySeries.has(base)) bySeries.set(base, []);
    bySeries.get(base).push(c);
  }
  const chosen = [];
  for (const [base, arr] of bySeries) {
    if (chosen.length < 3) chosen.push({ tag: "recent", ...arr[0] });
    if (arr.length > 1 && chosen.length < 4) chosen.push({ tag: "mid", ...arr[arr.length - 1] });
  }
  // fetch an OLD episode from a long-running series via load() episode list
  try {
    const seriesOfFirst = bySeries.keys().next().value;
    const { text: epDoc } = await get(chosen[0].url);
    const seriesLink = (epDoc.match(/href="(https:\/\/donghuaworld\.com\/anime\/[a-z0-9-]+\/)"/i) || [])[1];
    if (seriesLink) {
      const { text: sd } = await get(seriesLink);
      const list = [...sd.matchAll(/href="(https:\/\/donghuaworld\.com\/[^"]*episode[^"]*)"/gi)].map((m) => m[1]);
      const uniq = [...new Set(list)];
      // older episode = one near the end of the list
      const oldUrl = uniq[uniq.length - 1];
      if (oldUrl && !chosen.some((c) => c.url === oldUrl)) chosen.push({ tag: "older", url: oldUrl, title: (sd.match(new RegExp('href="' + oldUrl + '"[^>]*title="([^"]*)"')) || [])[1] || oldUrl.split("/").pop() });
      console.log(`series=${seriesLink} total ep links=${uniq.length}`);
    }
  } catch (e) { console.log("older-episode discovery skipped:", e.message); }

  while (chosen.length < 5 && candidates.length) chosen.push({ tag: "recent", ...candidates[chosen.length] });
  const final = chosen.slice(0, 5);
  console.log(`\nTesting ${final.length} episodes\n`);

  const table = [];
  for (const ep of final) {
    const subs = [], vids = [], out = [];
    await loadLinks(ep.url, (s) => subs.push(s), (v) => vids.push(v), out);
    const row = { ep, subs, vids, out, results: [] };
    for (const v of vids) {
      const res = await validate(v);
      row.results.push({ v, res });
    }
    table.push(row);
    const playable = row.results.filter((x) => x.res.ok).length;
    console.log(`\n---- ${ep.tag} ${ep.title}\n     ${ep.url}`);
    out.forEach((o) => console.log("     note:", o));
    if (subs.length) console.log("     SUBS:", subs.map((s) => s.lang).join(", "));
    if (vids.length === 0) console.log("     (no video sources emitted)");
    for (const { v, res } of row.results) {
      console.log(`     [${res.ok ? "OK " : "BAD"}] ${v.name} | ${v.type} | ${qName(v.quality)} | ${res.why}\n             ${v.url.slice(0, 120)}`);
    }
    const verdict = vids.length === 0 ? "FAIL" : playable === vids.length ? "PASS" : playable > 0 ? "PARTIAL" : "FAIL";
    console.log(`     => sources=${vids.length} playable=${playable} verdict=${verdict}`);
    row.verdict = verdict; row.playable = playable;
  }

  console.log("\n================ FINAL TABLE ================");
  console.log("episode | sources | playable | verdict | reason");
  for (const r of table) {
    const badReason = r.results.length === 0 ? "no sources" : r.results.some((x) => x.res.ok) ? (r.playable < r.vids.length ? "some sources bad" : "all ok") : (r.results[0].res.why);
    const t = `${r.ep.tag} ${r.ep.title}`.slice(0, 40);
    console.log(`${t} | ${r.vids.length} | ${r.playable} | ${r.verdict} | ${badReason}`);
  }
})();
