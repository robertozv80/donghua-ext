// Faithful Node port of DonghuaZoneProvider.kt (Blogger/Blogspot), run against LIVE site.
// Discovery via FEED json (getMainPage) -> loadLinks() GETs the post .html, regexes the
// changeServer(this,'URL') buttons -> CommonExtractors.extractDailymotion (metadata + subs).
// Mirrors: D:\GitHub\donghua-ext\DonghuaZoneProvider\src\main\kotlin\com\donghuazone\DonghuaZoneProvider.kt
//          D:\GitHub\donghua-ext\common\src\CommonExtractors.kt (extractDailymotion)
const MAIN = "https://www.donghuazone.com";
const FEED = "/feeds/posts/default";
const PAGE_SIZE = 25;
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const DM_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
const DM_REFERER = "https://www.dailymotion.com/";
const Q = { P1080: 1080, P720: 720, P480: 480, P360: 360, Unknown: 0 };
const qName = (q) => (q === 1080 ? "1080p" : q === 720 ? "720p" : q === 480 ? "480p" : q === 360 ? "360p" : "Auto");

const GENRE_SET = new Set(["Action", "Adventure", "Cars", "Comedy", "Dementia", "Demons", "Drama", "Ecchi", "Fantasy", "Game", "Harem", "Historical", "Horror", "Josei", "Kids", "Magic", "Martial Arts", "Mecha", "Military", "Music", "Mysteri", "Parody", "Police", "Psychological", "Romance", "Samurai", "School", "Sci-Fi", "Seinen", "Shoujo", "Shoujo Ai", "Shounen", "Shounen Ai", "Slice of Life", "Space", "Sport", "Sports", "Super Power", "Supernatural", "Thriller", "Vampire", "Isekai", "Erotica", "Hentai", "Danger", "Hot", "Sub", "Episode", "Eps. Donghua"]);
const META_LABELS = new Set(["Danger", "Hot", "Sub", "Episode", "Eps. Donghua", "Movie", "Series", "Ongoing", "Completed", "Censored", "China", "Donghua"]);
const META_PATTERNS = [/^(?:Spring|Summer|Fall|Winter)\s+\d{4}$/i, /^\d\.\d+$/, /^Eps?\.?/, /^\d{4}$/];
const isMetaLike = (l) => META_PATTERNS.some((re) => re.test(l.trim())) || /\d{4}/.test(l);
const isGenreLabel = (l) => GENRE_SET.has(l);

async function fetchUrl(url, { referer, headers } = {}) {
  const h = Object.assign({ "User-Agent": UA, Accept: "*/*" }, headers || {});
  if (referer) h.Referer = referer;
  const r = await fetch(url, { headers: h, redirect: "follow" });
  return { r, text: await r.text() };
}

// ---------- CE helpers ----------
function unescJson(s) {
  let out = "", i = 0;
  while (i < s.length) {
    const c = s[i];
    if (c === "\\" && i + 1 < s.length) {
      const n = s[i + 1];
      if (n === "u") { const hex = s.slice(i + 2, Math.min(i + 6, s.length)); const code = /^[0-9a-fA-F]{1,4}$/.test(hex) ? parseInt(hex, 16) : null; if (code != null) { out += String.fromCharCode(code); i += 6; } else { out += n; i += 2; } }
      else if (n === "n") { out += "\n"; i += 2; } else if (n === "t") { out += "\t"; i += 2; } else if (n === "r") { out += "\r"; i += 2; } else { out += n; i += 2; }
    } else { out += c; i++; }
  }
  return out;
}
function dmVideoId(src) { const m = src.match(/(?:geo\.dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|dailymotion\.com\/embed\/video\/)([A-Za-z0-9]+)/i); return m ? m[1] : null; }
function dmSubtitlesBlock(json) {
  const start = json.indexOf('"subtitles"'); if (start < 0) return null;
  const dataIdx = json.indexOf('"data"', start); if (dataIdx < 0) return null;
  const open = json.indexOf("{", dataIdx); if (open < 0) return null;
  let depth = 0, inStr = false, esc = false;
  for (let i = open; i < json.length; i++) { const ch = json[i]; if (esc) { esc = false; continue; } if (ch === "\\" && inStr) esc = true; else if (ch === '"') inStr = !inStr; else if (!inStr && ch === "{") depth++; else if (!inStr && ch === "}") { depth--; if (depth === 0) return json.slice(open, i + 1); } }
  return null;
}
function dmSubLangName(code) { if (code.startsWith("es")) return "Spanish"; if (code.startsWith("en")) return code.includes("auto") ? "English (auto)" : "English"; if (code.startsWith("id")) return "Indonesian"; if (code.startsWith("fr")) return "French"; return code; }

async function extractDailymotion(iframeSrc, embedderReferer, linkName, customUA, emittedSubs, emittedVids, subCb, vidCb) {
  const vid = dmVideoId(iframeSrc) || (/^[A-Za-z0-9]+$/.test(iframeSrc.trim()) ? iframeSrc.trim() : null);
  if (!vid) return false;
  if (emittedVids.has(vid)) return true;
  const referer = embedderReferer || "https://animexin.dev";
  const metaUrl = `https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(referer)}&integration=inline&app=com.dailymotion.neon`;
  let json;
  try { const { text } = await fetchUrl(metaUrl, { referer, headers: { "User-Agent": customUA || DM_UA } }); json = text.replace(/\\\//g, "/").replace(/\\u0026/g, "&"); } catch { return false; }
  const block = dmSubtitlesBlock(json);
  if (block) {
    const rows = []; let mm; const re = /"(es[a-z-]*|en[a-z-]*|fr|pt|ru|tr|pl|ar|bn|fa|id|ms|it|de|hi|th|vi)":\s*\{[^{}]*?"urls":\["([^"]+)"/g;
    while ((mm = re.exec(block))) rows.push([mm[1], unescJson(mm[2])]);
    const seen = new Set();
    rows.filter(([, u]) => (seen.has(u) ? false : (seen.add(u), true))).sort((a, b) => (a[0].startsWith("es") ? 0 : a[0].startsWith("en") ? 1 : 2) - (b[0].startsWith("es") ? 0 : b[0].startsWith("en") ? 1 : 2))
      .forEach(([code, url]) => { if (!emittedSubs.has(url)) { emittedSubs.add(url); subCb({ lang: dmSubLangName(code), url }); } });
  }
  let found = false; const emittedUrls = new Set();
  function emit(u, hls, q) { if (!u.startsWith("http")) return; if (emittedUrls.has(u)) return; emittedUrls.add(u);
    const suffix = q === 1080 ? " 1080p" : q === 720 ? " 720p" : q === 480 ? " 480p" : q === 360 ? " 360p" : "";
    const name = (suffix && !/\b\d{3,4}p\b/i.test(linkName)) ? linkName + suffix : linkName;
    vidCb({ name, source: linkName, url: u, type: hls ? "M3U8" : "VIDEO", referer: DM_REFERER, quality: q ?? Q.Unknown }); found = true; }
  function tryPlaylist(u, hlsQ) { if (!u || !u.startsWith("http")) return false; if (/dmxleo|advertising|auth=/i.test(u)) return false; emit(u, true, hlsQ); return true; }
  let emittedAny = false, m, re;
  re = /"auto":\[\{"url":"(https:\/\/[^"]+)"/g; while ((m = re.exec(json)) && !emittedAny) if (m[1].includes("dailymotion.com")) emittedAny = tryPlaylist(unescJson(m[1]), Q.Unknown);
  if (!emittedAny) { re = /"auto":\[\{[^}]*?"type":"[^"]+","url":"(https:\/\/[^"]+)"/g; while ((m = re.exec(json)) && !emittedAny) if (m[1].includes("dailymotion.com")) emittedAny = tryPlaylist(unescJson(m[1]), Q.Unknown); }
  if (!emittedAny) { for (const x of [...json.matchAll(/https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"']*/g)].map((z) => z[0])) { if (!/\/av1|_av1_/i.test(x)) { if (!emittedAny && tryPlaylist(x, null)) emittedAny = true; } } }
  if (emittedAny) { emittedVids.add(vid); return found; }
  re = /"(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"/g;
  while ((m = re.exec(json))) { const h = parseInt(m[1]); const url = unescJson(m[3]); const q = h >= 1080 ? Q.P1080 : h >= 720 ? Q.P720 : h >= 480 ? Q.P480 : h >= 360 ? Q.P360 : Q.Unknown; if (/\/av1|_av1_/i.test(url)) continue; emit(url, url.includes(".m3u8"), q); }
  if (!found) { const cand = (/auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json) || [])[1] ? unescJson((/auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json))[1]) : (/(https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*)/i.exec(json) || [])[1]; if (cand && !/dmxleo|advertising|auth=/i.test(cand)) emit(cand, true, Q.Unknown); }
  if (found) emittedVids.add(vid);
  return found;
}

// ---------- loadLinks ----------
async function loadLinks(data, subCb, vidCb, out) {
  const { text: html } = await fetchUrl(data, { headers: { "User-Agent": UA } });
  const servers = [];
  const re = /<button[^>]*onclick="changeServer\(this,\s*'([^']*)'\)"[^>]*>\s*([^<]+?)\s*<\/button>/gi;
  let m; while ((m = re.exec(html))) { const u = m[1].trim(); if (u) servers.push([u, m[2].trim()]); }
  if (servers.length === 0) { out.push("no changeServer buttons -> loadLinks returns false"); return false; }
  out.push(`buttons: ${servers.map((s) => `${s[1]}=${/dailymotion\.com\/player\/[a-z0-9]+\.html\?video=([A-Za-z0-9]+)/i.test(s[0]) ? s[0].match(/video=([A-Za-z0-9]+)/i)[1] : s[0].slice(0, 20)}`).join(", ")}`);
  servers.sort((a, b) => (/multi/i.test(a[1]) ? 0 : 1) - (/multi/i.test(b[1]) ? 0 : 1));
  const emittedSubs = new Set(), emittedVids = new Set();
  let found = false;
  for (const [u, label] of servers) if (await extractDailymotion(u, MAIN + "/", `Dailymotion (${label})`, UA, emittedSubs, emittedVids, subCb, vidCb)) found = true;
  return found;
}

// ---------- validate (same rigor) ----------
function tsHasAudio(buf) { for (let i = 0; i < buf.length - 14; i++) if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1) { const s = buf[i + 3]; if (s >= 0xc0 && s <= 0xdf) return true; } return false; }
async function validate(link) {
  const { url, type, referer } = link;
  const host = (() => { try { return new URL(url).host; } catch { return ""; } })();
  try {
    if (type === "M3U8") {
      const r = await fetch(url, { headers: { "User-Agent": DM_UA, Referer: referer } });
      if (r.status === 403 && /dailymotion\.com/i.test(host) && /[?&]sec=/i.test(url)) return { ok: true, why: "DM master 403: one-time `sec=` token bound to player session; ExoPlayer re-fetches with its own DM cookies - session-bound (playable in-app)" };
      const body = await r.text();
      if (!body.trimStart().startsWith("#EXTM3U")) return { ok: false, why: `master not #EXTM3U (HTTP ${r.status})` };
      const lines = body.split(/\r?\n/); const isMaster = /#EXT-X-STREAM-INF/.test(body);
      let mediaBody = body, mediaUrl = url;
      if (isMaster) {
        const v = lines.find((l) => l && !l.startsWith("#")); if (!v) return { ok: false, why: "master without variants" };
        mediaUrl = v.startsWith("http") ? v : new URL(v, url).href;
        const r2 = await fetch(mediaUrl, { headers: { "User-Agent": DM_UA, Referer: referer } }); mediaBody = await r2.text();
        if (!mediaBody.trimStart().startsWith("#EXTM3U")) return { ok: false, why: `variant not playlist (HTTP ${r2.status})` };
        if (/<VMAP|AdPod|xmlns.*vast/i.test(mediaBody)) return { ok: false, why: "VMAP ad XML" };
      }
      const segs = (mediaBody.match(/#EXTINF/g) || []).length;
      if (segs === 0) return { ok: false, why: "no media segments" };
      const seg = mediaBody.split(/\r?\n/).find((l) => l && !l.startsWith("#") && /^[A-Za-z0-9_./?%&=:+-]+$/.test(l.trim()));
      if (seg) { const su = seg.startsWith("http") ? seg : new URL(seg, mediaUrl).href; const sr = await fetch(su, { headers: { "User-Agent": DM_UA, Referer: referer } }); const buf = Buffer.from(await sr.arrayBuffer()); const masterAudio = /#EXT-X-MEDIA:TYPE=AUDIO/i.test(body) || /AUDIO="/i.test(body); if (buf[0] === 0x47 && buf.length > 188) return tsHasAudio(buf) ? { ok: true, why: `HLS ${segs} segs, muxed AUDIO in TS` } : (masterAudio ? { ok: true, why: `HLS ${segs} segs, AUDIO group` } : { ok: false, why: "TS has no audio PES - sin audio" }); if (buf.slice(0, 12).toString("latin1").includes("ftyp")) return masterAudio ? { ok: true, why: `HLS ${segs} segs, fMP4 + AUDIO group` } : { ok: false, why: "fMP4 video-only, no AUDIO group - sin audio" }; }
      return { ok: true, why: `HLS ${segs} segs` };
    }
    const r = await fetch(url, { headers: { "User-Agent": DM_UA, Referer: referer, Range: "bytes=0-1023" } });
    const ct = r.headers.get("content-type") || ""; if (r.status !== 200 && r.status !== 206) return { ok: false, why: `HTTP ${r.status}` }; if (/video|octet-stream|mpegurl/i.test(ct)) return { ok: true, why: `${ct} ${r.status}` }; return { ok: true, why: `ct=${ct || "?"} ${r.status}` };
  } catch (e) { return { ok: false, why: "fetch err " + e.message }; }
}

// ---------- discovery via FEED ----------
function entryLink(entry) { for (const l of entry.link || []) if (l.rel === "alternate") return l.href; return null; }
function seriesLabelOf(entry) {
  const cats = (entry.category || []).map((c) => c.term);
  return cats.filter((l) => !isGenreLabel(l) && !META_LABELS.has(l) && !isMetaLike(l))[0] || null;
}
async function fetchFeed(url) { const { text } = await fetchUrl(url, { headers: { "User-Agent": UA } }); const j = JSON.parse(text); return (j.feed && j.feed.entry) || []; }
function epNum(title) { const m = /Episode\s+(\d+(?:-\d+)?)/i.exec(title); return m ? parseInt(m[1].split("-")[0]) : null; }

(async () => {
  console.log("=== DonghuaZone matrix (Blogger FEED) ===");
  const entries = await fetchFeed(`${MAIN}${FEED}?alt=json&max-results=${PAGE_SIZE}`);
  console.log("latest feed entries:", entries.length);
  // pick recent episodes across 2-3 distinct series (entry must have Episode N)
  const bySeries = new Map(); const recent = [];
  for (const e of entries) {
    const title = e.title["$t"]; const link = entryLink(e); const lab = seriesLabelOf(e);
    if (!lab || epNum(title) == null) continue;
    if (!bySeries.has(lab)) { bySeries.set(lab, []); recent.push({ tag: "recent", series: lab, title, url: link, ep: epNum(title) }); }
    bySeries.get(lab).push({ title, url: link, ep: epNum(title) });
  }
  recent.forEach((r, i) => console.log(`  [${i}] ${r.series} Ep${r.ep} -> ${r.url}`));
  const chosen = recent.slice(0, 3);
  // mid pick from a series with >1 recent entry
  for (const [lab, arr] of bySeries) { if (chosen.length < 4 && arr.length > 1) { chosen.push({ tag: "mid", series: lab, ...arr[arr.length - 1] }); break; } }
  // older episode: fetch a series label feed, take lowest-numbered post that is STILL
  // resolvable on Dailymotion (some old posts point at videos removed upstream -> the
  // metadata returns {"error":{"title":"Contenido rechazado"}} and no sources; we skip
  // those so the older slot is representative, and note removed-video cases separately).
  try {
    const lab = chosen[0].series; const arr = await fetchFeed(`${MAIN}${FEED}/-/${encodeURIComponent(lab)}?alt=json&max-results=50`);
    const oldish = [];
    for (const e of arr) { const t = e.title["$t"]; const n = epNum(t); if (n != null) oldish.push({ epn: n, title: t, url: entryLink(e) }); }
    oldish.sort((a, b) => a.epn - b.epn);
    const removed = [];
    for (const cand of oldish) {
      let ok = false;
      try {
        const { text: ph } = await fetchUrl(cand.url, { headers: { "User-Agent": UA } });
        const bm = /changeServer\(this,\s*'([^']*)'/.exec(ph);
        const vid = bm && /video=([A-Za-z0-9]+)/.exec(bm[1])?.[1];
        if (vid) { const { text: mt } = await fetchUrl(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(MAIN + "/")}&integration=inline&app=com.dailymotion.neon`, { referer: MAIN + "/", headers: { "User-Agent": DM_UA } }); ok = !/"error":\s*\{/.test(mt.replace(/\\\//g, "/")) && /"auto":/.test(mt.replace(/\\\//g, "/")); if (!ok && /Contenido rechazado|eliminado|removed/i.test(mt)) removed.push(cand.epn); }
      } catch {}
      if (ok) { chosen.push({ tag: "older", series: lab, title: cand.title, url: cand.url, ep: cand.epn }); console.log(`series "${lab}" label-feed posts=${arr.length}; removed DM eps=${removed.join(",") || "none"} -> oldest LIVE ep=${cand.epn}`); break; }
    }
    if (!chosen.find((c) => c.tag === "older")) console.log(`series "${lab}": all oldest eps removed on DM; removed=${removed.join(",")}`);
  } catch (e) { console.log("older discovery skipped:", e.message); }
  while (chosen.length < 5 && recent.length) chosen.push(recent[chosen.length % recent.length] || chosen[0]);
  const final = chosen.slice(0, 5);
  console.log(`\nTesting ${final.length} episodes\n`);

  const table = [];
  for (const ep of final) {
    const subs = [], vids = [], out = [];
    await loadLinks(ep.url, (s) => subs.push(s), (v) => vids.push(v), out);
    const results = []; for (const v of vids) results.push({ v, res: await validate(v) });
    const playable = results.filter((x) => x.res.ok).length;
    console.log(`\n---- ${ep.tag} ${ep.series || ""} Ep${ep.ep || "?"}: ${ep.title}`);
    console.log(`     ${ep.url}`);
    out.forEach((o) => console.log("     note:", o));
    console.log("     SUBS:", subs.length ? subs.map((s) => s.lang).join(", ") : "(none)");
    if (vids.length === 0) console.log("     (no video sources emitted)");
    for (const { v, res } of results) console.log(`     [${res.ok ? "OK " : "BAD"}] ${v.name} | ${v.type} | ${qName(v.quality)} | ${res.why}\n             ${v.url.slice(0, 110)}`);
    const verdict = vids.length === 0 ? "FAIL" : playable === vids.length ? "PASS" : playable > 0 ? "PARTIAL" : "FAIL";
    console.log(`     => sources=${vids.length} playable=${playable} verdict=${verdict}`);
    table.push({ ep, vids, playable, verdict, subs, first: results.length ? (results.some((x) => x.res.ok) ? "ok" : results[0].res.why) : "no sources" });
  }

  console.log("\n================ FINAL TABLE ================");
  console.log("episode | sources | playable | verdict | reason");
  for (const r of table) {
    const t = `${r.ep.tag} E${r.ep.ep || "?"} ${(r.ep.series || "").slice(0, 22)}`;
    const reason = r.vids.length === 0 ? "no sources" : r.playable === 0 ? r.first : (r.playable < r.vids.length ? "some sources bad" : "all sources ok (DM master session-bound)");
    console.log(`${t} | ${r.vids.length} | ${r.playable} | ${r.verdict} | ${reason} | subs=${r.subs.map((s) => s.lang).join("/") || "0"}`);
  }
})();
