// Matrix validator for LmAnimeProvider (faithful port of the Kotlin loadLinks
// + CommonExtractors). Run: node tools/matrix_lmanime.mjs
// Read-only probe of the LIVE site; does not touch any /src/ file.

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const MAIN = "https://lmanime.com";
const DM_REFERER = "https://www.dailymotion.com/";

// ---------------- helpers ----------------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function decodeEntities(s) {
  return s
    .replace(/&quot;/g, '"')
    .replace(/&#039;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

async function httpGet(url, { referer = "", headers = {}, timeout = 25000, method = "GET", body = null } = {}) {
  const h = { "User-Agent": UA, ...(referer ? { Referer: referer } : {}), ...headers };
  try {
    const res = await fetch(url, {
      method,
      headers: h,
      body,
      redirect: "follow",
      signal: AbortSignal.timeout(timeout),
    });
    const text = await res.text().catch(() => "");
    return { status: res.status, text, ctype: res.headers.get("content-type") || "", finalUrl: res.url };
  } catch (e) {
    return { status: 0, text: "", ctype: "", finalUrl: url, error: String(e && e.message ? e.message : e) };
  }
}

// CommonExtractors.unescJson
function unescJson(s) {
  let out = "";
  for (let i = 0; i < s.length; ) {
    const c = s[i];
    if (c === "\\" && i + 1 < s.length) {
      const n = s[i + 1];
      if (n === "u") {
        const hex = s.slice(i + 2, Math.min(i + 6, s.length));
        const code = /^[0-9a-fA-F]+$/.test(hex) ? parseInt(hex, 16) : null;
        if (code !== null && !Number.isNaN(code)) { out += String.fromCharCode(code); i += 6; }
        else { out += n; i += 2; }
      } else if (n === "n") { out += "\n"; i += 2; }
      else if (n === "t") { out += "\t"; i += 2; }
      else if (n === "r") { out += "\r"; i += 2; }
      else { out += n; i += 2; }
    } else { out += c; i++; }
  }
  return out;
}

// CommonExtractors.dmSubtitlesBlock (balanced braces)
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

function dmVideoId(src) {
  const m = /(?:geo\.dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|dailymotion\.com\/embed\/video\/)([A-Za-z0-9]+)/i.exec(src);
  return m ? m[1] : null;
}

function dmSubLangName(code) {
  if (code.startsWith("es")) return "Spanish";
  if (code.startsWith("en")) return code.includes("auto") ? "English (auto)" : "English";
  const map = { fr: "French", pt: "Portuguese", ru: "Russian", tr: "Turkish", pl: "Polish", ar: "Arabic", bn: "Bengali", fa: "Persian", id: "Indonesian", ms: "Malay", it: "Italian", de: "German", hi: "Hindi", th: "Thai", vi: "Vietnamese" };
  for (const k of Object.keys(map)) if (code.startsWith(k)) return map[k];
  return code;
}

// Kotlin: Regex("""data-options="([^"]+)"""") -> &quot; -> \\/ -> \uXXXX decode
function decodeUnicode(s) {
  return s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
}

const Q = { UNK: -2, P360: 360, P480: 480, P720: 720, P1080: 1080 };
const qName = (q) => (q === Q.UNK || q == null ? "Unknown" : `${q}p`);

// ---------------- M3u8Helper.generateM3u8 port ----------------
async function generateM3u8(linkName, m3u8Url, referer, out) {
  const r = await httpGet(m3u8Url, { referer, timeout: 20000 });
  const text = r.text;
  if (!text.trimStart().startsWith("#EXTM3U")) return;
  const lines = text.split(/\r?\n/);
  const variants = [];
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i].trim();
    if (l.startsWith("#EXT-X-STREAM-INF")) {
      const res = /RESOLUTION=(\d+)x(\d+)/.exec(l);
      const bw = /BANDWIDTH=(\d+)/.exec(l);
      const codecs = /CODECS="([^"]+)"/.exec(l);
      let uri = "";
      for (let j = i + 1; j < lines.length; j++) {
        const t = lines[j].trim();
        if (t && !t.startsWith("#")) { uri = t; break; }
      }
      if (uri) variants.push({ height: res ? +res[2] : 0, bw: bw ? +bw[1] : 0, codecs: codecs ? codecs[1] : "", uri });
    }
  }
  if (variants.length > 0) {
    for (const v of variants) {
      const abs = new URL(v.uri, m3u8Url).href;
      const q = v.height >= 1080 ? Q.P1080 : v.height >= 720 ? Q.P720 : v.height >= 480 ? Q.P480 : v.height >= 360 ? Q.P360 : Q.UNK;
      out.push({ source: linkName, name: `${linkName} ${v.height || "?"}p`, url: abs, type: "M3U8", quality: q, referer, codecs: v.codecs, fromGenerate: true });
    }
  } else {
    out.push({ source: linkName, name: linkName, url: m3u8Url, type: "M3U8", quality: Q.UNK, referer, fromGenerate: true });
  }
}

// ---------------- CommonExtractors.extractDailymotion ----------------
async function extractDailymotion(iframeSrc, embedderReferer, linkName, emittedSubs, emittedVids, subs, out) {
  let vid = dmVideoId(iframeSrc) || (/^[A-Za-z0-9]+$/.test(iframeSrc.trim()) ? iframeSrc.trim() : null);
  if (!vid) return false;
  if (emittedVids.has(vid)) return true; // dedupe: same video listed in several mirrors
  const referer = embedderReferer || "https://animexin.dev";
  try {
    const metaUrl = `https://www.dailymotion.com/player/metadata/video/${vid}` +
      `?embedder=${encodeURIComponent(referer)}&integration=inline&app=com.dailymotion.neon`;
    const raw = await httpGet(metaUrl, { referer, timeout: 20000 });
    const json = raw.text.replace(/\\\//g, "/").replace(/\\u0026/g, "&");

    // emitDmSubtitles
    const block = dmSubtitlesBlock(json);
    if (block) {
      const re = /"(es[a-z-]*|en[a-z-]*|fr|pt|ru|tr|pl|ar|bn|fa|id|ms|it|de|hi|th|vi)":\s*\{[^{}]*?"urls":\["([^"]+)"/g;
      let m; const found = [];
      while ((m = re.exec(block))) found.push([m[1], unescJson(m[2])]);
      const uniq = [];
      const seenUrl = new Set();
      for (const f of found) { if (!seenUrl.has(f[1])) { seenUrl.add(f[1]); uniq.push(f); } }
      uniq.sort((a, b) => (a[0].startsWith("es") ? 0 : a[0].startsWith("en") ? 1 : 2) - (b[0].startsWith("es") ? 0 : b[0].startsWith("en") ? 1 : 2));
      for (const [code, url] of uniq) {
        if (!emittedSubs.has(url)) { emittedSubs.add(url); subs.push({ lang: dmSubLangName(code), url }); }
      }
    }

    let foundAny = false;
    const emittedUrls = new Set();
    function emit(u, hls, q) {
      if (!u || !u.startsWith("http")) return;
      if (emittedUrls.has(u)) return;
      emittedUrls.add(u);
      const suffix = q === Q.P1080 ? " 1080p" : q === Q.P720 ? " 720p" : q === Q.P480 ? " 480p" : q === Q.P360 ? " 360p" : "";
      const disp = suffix && !/\b\d{3,4}p\b/i.test(linkName) ? linkName + suffix : linkName;
      out.push({ source: linkName, name: disp, url: u, type: hls ? "M3U8" : "VIDEO", quality: q ?? Q.UNK, referer: DM_REFERER });
      foundAny = true;
    }
    // tryPlaylist: NO real GET validation in Kotlin despite the comment.
    function tryPlaylist(u, hlsQ) {
      if (!u || !u.startsWith("http")) return false;
      if (/dmxleo|advertising|auth=/i.test(u)) return false;
      emit(u, true, hlsQ);
      return true;
    }

    // 1) master HLS from qualities.auto
    let emittedAny = false;
    const re1 = /"auto":\[\{"url":"(https:\/\/[^"]+)"/g;
    let mm;
    while ((mm = re1.exec(json))) {
      if (emittedAny) break;
      if (mm[1].includes("dailymotion.com")) emittedAny = tryPlaylist(unescJson(mm[1]), Q.UNK);
    }
    if (!emittedAny) {
      const re1b = /"auto":\[\{[^}]*?"type":"[^"]+","url":"(https:\/\/[^"]+)"/g;
      while ((mm = re1b.exec(json))) {
        if (emittedAny) break;
        if (mm[1].includes("dailymotion.com")) emittedAny = tryPlaylist(unescJson(mm[1]), Q.UNK);
      }
    }
    // 2) vCRN presets fallback
    if (!emittedAny) {
      const masterAuto = /https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i.exec(json)?.[0];
      const dc = masterAuto ? (/https:\/\/([a-z0-9.-]+)\.dailymotion\.com/i.exec(masterAuto)?.[1] || "dmxleo") : null;
      const dcOk = dc && !dc.startsWith("cdndirector") ? dc : null;
      const vid2 = masterAuto ? (/video\/([a-z0-9]+)\.m3u8/i.exec(masterAuto)?.[1]) : null;
      if (dcOk && vid2) {
        for (const preset of ["H264-1280x720", "H264-1920x1080", "H264-854x480"]) {
          const u = `https://${dcOk}.dailymotion.com/cdn/${preset}/vCRN/mp4_h264_aac.m3u8?video=${vid2}`;
          if (tryPlaylist(u, Q.P720)) { emittedAny = true; break; }
        }
      }
    }
    // 3) other dmcdn masters, excluding AV1
    if (!emittedAny) {
      const re3 = /https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"']*/g;
      let mm3;
      while ((mm3 = re3.exec(json))) {
        if (emittedAny) break;
        const it = mm3[0];
        if (!/\/av1|_av1_/i.test(it)) { if (tryPlaylist(it, null)) emittedAny = true; }
      }
    }
    if (emittedAny) { emittedVids.add(vid); return foundAny; }

    // 4) progressive mp4 from qualities map (AV1 skipped)
    const re4 = /"(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"/g;
    let mm4;
    while ((mm4 = re4.exec(json))) {
      const h = parseInt(mm4[1], 10);
      const url = unescJson(mm4[3]);
      const q = Number.isNaN(h) ? Q.UNK : h >= 1080 ? Q.P1080 : h >= 720 ? Q.P720 : h >= 480 ? Q.P480 : h >= 360 ? Q.P360 : Q.UNK;
      if (/\/av1|_av1_/i.test(url)) continue;
      emit(url, url.includes(".m3u8"), q);
    }
    if (!foundAny) {
      let candidate = /auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json)?.[1];
      candidate = candidate ? unescJson(candidate)
        : (/https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i.exec(json)?.[0]);
      if (candidate && !/dmxleo|advertising|auth=/i.test(candidate)) {
        emit(candidate, true, Q.UNK);
      }
    }
    if (foundAny) emittedVids.add(vid);
    return foundAny;
  } catch { return false; }
}

// ---------------- CommonExtractors.extractRumble ----------------
async function extractRumble(embedUrl, siteReferer, linkName, out) {
  const vm = /rumble\.com\/(?:embed\/)?(v[0-9a-z]+)(?:[-/_.@?]|$)/i.exec(embedUrl);
  if (!vm) return false;
  const vid = vm[1];
  const api = `https://rumble.com/embedJS/u3/?request=video&v=${vid}&embed=${encodeURIComponent(embedUrl)}`;
  function emit(json) {
    const fixed = json.replace(/\\\//g, "/");
    const re = /"(\d{3,4})":\["(https:\/\/[a-z0-9.-]*rumble\.cloud\/video\/[A-Za-z0-9/._-]+\.mp4)"/g;
    let n = 0, m;
    while ((m = re.exec(fixed))) {
      const h = parseInt(m[1], 10);
      const q = Number.isNaN(h) ? Q.UNK : h >= 1080 ? Q.P1080 : h >= 720 ? Q.P720 : h >= 480 ? Q.P480 : Q.P360;
      out.push({ source: linkName, name: linkName, url: m[2], type: "VIDEO", quality: q, referer: "https://rumble.com/" });
      n++;
    }
    return n;
  }
  let r = await httpGet(api, { referer: "https://rumble.com/", timeout: 20000 });
  let json = r.status ? r.text : "";
  let count = json.trim() ? emit(json) : 0;
  let evidence = `api#1 HTTP ${r.status} len=${json.length}`;
  if (count === 0) {
    // Cloudflare WAF path: visit embed, retry api with its cookies-like flow.
    const e = await httpGet(embedUrl, { referer: siteReferer, timeout: 20000 });
    evidence += ` | embed HTTP ${e.status}${/cloudflare|challenge/i.test(e.text) ? " (CF challenge marker)" : ""}`;
    r = await httpGet(api, { referer: embedUrl, timeout: 20000 });
    json = r.status ? r.text : "";
    if (json.trim()) count = emit(json);
    evidence += ` | api#2 HTTP ${r.status} len=${json.length} body=${json.slice(0, 60).replace(/\n/g, " ")}`;
  }
  out.__lastRumble = { count, evidence };
  return count > 0;
}

// ---------------- CommonExtractors.extractOkRu ----------------
async function extractOkRu(embedUrl0, siteReferer, linkName, out) {
  const url = embedUrl0.startsWith("//") ? `https:${embedUrl0}` : embedUrl0;
  const r = await httpGet(url, { referer: siteReferer, timeout: 25000 });
  const html = r.text;
  const dm = /data-options="([^"]+)"/.exec(html);
  if (!dm) return false;
  const options = decodeUnicode(dm[1].replace(/&quot;/g, '"').replace(/\\\//g, "/"));
  let found = false;
  const cands = [];
  const reH = /"(?:[a-zA-Z]*ManifestUrl|ondemandHls)":"([^"]+)"/g;
  let m;
  while ((m = reH.exec(options))) cands.push(m[1]);
  let hlsUrl = null;
  for (const c of cands) {
    const v = await httpGet(c, { referer: url, timeout: 20000 });
    if (v.text.trimStart().startsWith("#EXTM3U")) { hlsUrl = c; break; }
  }
  if (hlsUrl) {
    const before = out.length;
    await generateM3u8(linkName, hlsUrl, url, out);
    if (out.length > before) found = true;
  }
  const reP = /"name":"(mobile|lowest|low|sd|hd|full|super)","url":"([^"]+)"/g;
  let mp;
  while ((mp = reP.exec(options))) {
    let progressive = decodeUnicode(mp[2]);
    if (progressive.startsWith("http")) {
      const full = progressive.startsWith("//") ? `https:${progressive}` : progressive;
      const q = mp[1] === "full" || mp[1] === "super" ? Q.P1080 : mp[1] === "hd" ? Q.P720 : mp[1] === "sd" ? Q.P480 : Q.P360;
      out.push({ source: linkName, name: linkName, url: full, type: "VIDEO", quality: q, referer: url });
      found = true;
    }
  }
  return found;
}

// ---------------- LmAnime local extractFembed (Kotlin lines 482-518) ----------------
async function extractFembed(embedUrl, linkName, out) {
  const vm = /(?:fembed|femedia)[^/]*?\/(?:v|e)\/([A-Za-z0-9_-]+)/i.exec(embedUrl);
  if (!vm) return false;
  const originM = /^https?:\/\/[^/]+/.exec(embedUrl);
  if (!originM) return false;
  const r = await httpGet(`${originM[0]}/api/source`, {
    referer: embedUrl, timeout: 20000, method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ file: `/v/${vm[1]}` }),
  });
  let found = false;
  const re = /"(?:file|url)":"(https:\/\/[^"]+)"/g;
  let m;
  while ((m = re.exec(r.text))) {
    const u = m[1];
    out.push({ source: linkName, name: linkName, url: u, type: u.includes(".m3u8") ? "M3U8" : "VIDEO", quality: Q.UNK, referer: `${originM[0]}/` });
    found = true;
  }
  return found;
}

// ---------------- LmAnime loadLinks ----------------
function langPrio(label) {
  const l = label.toLowerCase();
  if (l.includes("multi")) return 0;
  if (l.includes("espa") || l.includes("spanish") || l.includes("latino")) return 0;
  if (l.includes("english") || l.includes("ingles") || l.includes("ingl")) return 1;
  return 2;
}

async function processMirror(mirror, referer, emittedSubs, emittedDmVids, subs, out) {
  try {
    const page = await httpGet(mirror.url, { referer, timeout: 25000 });
    const iframesAll = [];
    const reIf = /<iframe[^>]+src="([^"]+)"/g;
    let m;
    while ((m = reIf.exec(page.text))) iframesAll.push(decodeEntities(m[1]));
    // Kotlin filter (line 442): ONLY dailymotion / ok.ru survive -> fembed branch is dead code.
    const iframes = [...new Set(iframesAll.filter((u) => /dailymotion/i.test(u) || /ok\.ru/i.test(u)))];
    if (iframes.length === 0) return false;
    const l = mirror.label.toLowerCase();
    const langTag = mirror.label.toLowerCase().includes("multi") ? "Multisub"
      : langPrio(mirror.label) === 0 ? "Español"
      : langPrio(mirror.label) === 1 ? "English" : mirror.label;
    let found = false;
    for (const iframe of iframes) {
      let ok = false;
      if (/dailymotion/i.test(iframe)) ok = await extractDailymotion(iframe, `${MAIN}/`, `${langTag} • Dailymotion`, emittedSubs, emittedDmVids, subs, out);
      else if (/ok\.ru/i.test(iframe)) ok = await extractOkRu(iframe, referer, `${langTag} • Ok.ru`, out);
      else if (/fembed|freeplayer/i.test(iframe)) ok = await extractFembed(iframe, `${langTag} • Fembed`, out); // unreachable
      found = ok || found;
    }
    return found;
  } catch { return false; }
}

async function loadLinks(data, subs, out) {
  emittedDmVidsClear(); // v8 per-episode dedupe clear (Kotlin line 384)
  const document = await httpGet(data, { timeout: 30000 });
  const mirrors = [];
  const reSel = /<select[^>]*class="mirror"[\s\S]*?<\/select>/g;
  let sm;
  while ((sm = reSel.exec(document.text))) {
    const reOpt = /<option[^>]*value="([^"]*)"[^>]*>([\s\S]*?)<\/option>/g;
    let om;
    while ((om = reOpt.exec(sm[0]))) {
      const u = decodeEntities(om[1]).trim();
      const label = om[2].replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim();
      if (u) mirrors.push({ label, url: u });
    }
  }
  if (mirrors.length === 0) return false;
  const sorted = mirrors.slice().sort((a, b) => langPrio(a.label) - langPrio(b.label)); // stable
  const emittedSubs = new Set();
  let foundAny = false;
  for (const m of sorted.filter((x) => langPrio(x.label) <= 1)) {
    if (await processMirror(m, data, emittedSubs, DMVIDS, subs, out)) foundAny = true;
  }
  if (!foundAny) {
    for (const m of sorted.filter((x) => langPrio(x.label) === 2)) {
      if (await processMirror(m, data, emittedSubs, DMVIDS, subs, out)) foundAny = true;
    }
  }
  return foundAny;
}
const DMVIDS = new Set();
function emittedDmVidsClear() { DMVIDS.clear(); }

// ---------------- Validation (player simulation) ----------------
async function validateLink(link) {
  if (link.type === "M3U8") {
    const r = await httpGet(link.url, { referer: link.referer || "", timeout: 25000 });
    const body = r.text;
    if (!body.trimStart().startsWith("#EXTM3U")) {
      const marker = body.trimStart().startsWith("<?xml") ? "XML/VMAP?" : body.slice(0, 40).replace(/\s+/g, " ");
      return { ok: false, why: `master HTTP ${r.status} not #EXTM3U (${marker})` };
    }
    const lines = body.split(/\r?\n/);
    const hasAudioGroup = /#EXT-X-MEDIA:TYPE=AUDIO/.test(body);
    let variant = null;
    if (/#EXT-X-STREAM-INF/.test(body)) {
      for (let i = 0; i < lines.length; i++) {
        if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
          for (let j = i + 1; j < lines.length; j++) {
            const t = lines[j].trim();
            if (t && !t.startsWith("#")) { variant = { line: lines[i], uri: new URL(t, link.url).href }; break; }
          }
          break;
        }
      }
    }
    const mediaUrl = variant ? variant.uri : link.url;
    const mv = await httpGet(mediaUrl, { referer: link.referer || "", timeout: 25000 });
    const mb = mv.text;
    if (mb.trimStart().startsWith("<?xml") || /<vmap:|<VMAP/i.test(mb)) return { ok: false, why: `variant is VMAP ad XML (HTTP ${mv.status})` };
    if (!mb.trimStart().startsWith("#EXTM3U")) return { ok: false, why: `variant HTTP ${mv.status} not #EXTM3U` };
    const seg = mb.split(/\r?\n/).find((l) => l.trim() && !l.startsWith("#"));
    if (!seg) return { ok: false, why: "no media segments in variant" };
    const segUrl = new URL(seg.trim(), mediaUrl).href;
    const sg = await httpGet(segUrl, { referer: link.referer || "", headers: { Range: "bytes=0-2047" }, timeout: 25000 });
    if (!(sg.status === 200 || sg.status === 206)) return { ok: false, why: `segment HTTP ${sg.status}` };
    const isTs = /\.ts(\?|$)/i.test(segUrl);
    const codecs = (variant ? /CODECS="([^"]+)"/.exec(variant.line)?.[1] || "" : "") + " " + (mb.match(/CODECS="([^"]+)"/g) || []).join(" ");
    const muxedOrTs = isTs || /mp4a|ac-3|ec-3|opus|flac/.test(codecs);
    if (!hasAudioGroup && !muxedOrTs) {
      return { ok: false, why: `VIDEO-ONLY HLS (no AUDIO group, codecs='${codecs.trim() || "none"}') — sin audio`, segStatus: sg.status };
    }
    return { ok: true, why: `segments OK (HTTP ${sg.status}); ${hasAudioGroup ? "AUDIO groups present" : "muxed/TS"}` };
  }
  // VIDEO progressive
  const r = await httpGet(link.url, { referer: link.referer || "", headers: { Range: "bytes=0-65535" }, timeout: 30000 });
  const isVideo = /^video\//i.test(r.ctype);
  if ((r.status === 200 || r.status === 206) && (isVideo || /^application\/octet-stream/i.test(r.ctype))) {
    return { ok: true, why: `HTTP ${r.status} ${r.ctype}` };
  }
  return { ok: false, why: `HTTP ${r.status} ctype='${r.ctype}'` };
}

// ---------------- Episode discovery ----------------
async function discover() {
  console.log("== Discovery (home -> recent episodes; series .eplister -> older) ==");
  const home = await httpGet(`${MAIN}/`, { timeout: 30000 });
  // getMainPage "Latest Release": selector .listupd.normal article ; we scope the regex to the listupd block.
  const blockM = /<div[^>]+class="[^"]*listupd[^"]*"[\s\S]*?<\/section>/.exec(home.text) || [home.text];
  const re = /<a[^>]+href="(https:\/\/lmanime\.com\/[^"]+-episode-\d+[^"]*)"[^>]*title="([^"]*)"/g;
  const eps = [];
  const bySeries = new Map();
  let m;
  while ((m = re.exec(blockM[0]))) {
    const url = m[1];
    const slug = url.replace(/\/$/, "").split("/").pop().replace(/-episode-\d+.*$/, "");
    if (!bySeries.has(slug)) bySeries.set(slug, []);
    bySeries.get(slug).push(url);
    if (!eps.find((e) => e.slug === slug)) eps.push({ slug, url });
    if (bySeries.size >= 4 && eps.length >= 4) break;
  }
  const recent = eps.slice(0, 3);
  console.log("Recent:", recent.map((e) => e.url).join("\n  "));

  // older episodes: load series page (.eplister), take an early ep of 2 series
  const older = [];
  for (const slug of [...bySeries.keys()].reverse()) {
    if (older.length >= 2) break;
    const list = await httpGet(`${MAIN}/${slug}/`, { timeout: 30000 });
    const idx = list.text.indexOf('class="eplister"'); // theme uses <div class="eplister"><ul>...
    if (idx < 0) continue;
    const end = list.text.indexOf("</ul>", idx);
    const em = [list.text.slice(idx, end > 0 ? end + 5 : idx + 300000)];
    if (!em) continue;
    const reEp = /<a[^>]+href="(https:\/\/lmanime\.com\/[^"]+-episode-\d+[^"]*)"/g;
    const arr = [];
    let mm;
    const seen = new Set();
    while ((mm = reEp.exec(em[0]))) if (!seen.has(mm[1])) { seen.add(mm[1]); arr.push(mm[1]); }
    if (arr.length > 30) older.push({ slug, url: arr[arr.length - 2], older: true });
  }
  console.log("Older:", older.map((e) => e.url).join("\n  "));
  return [...recent, ...older].slice(0, 5);
}

// ---------------- Main ----------------
(async () => {
  const episodes = await discover();
  if (episodes.length < 5) console.log("WARN: only found", episodes.length, "episodes");
  const rows = [];
  for (const ep of episodes) {
    const subs = [];
    const out = [];
    console.log(`\n### EPISODE ${ep.url}`);
    const t0 = Date.now();
    const ok = await loadLinks(ep.url, subs, out);
    console.log(`loadLinks returned ${ok} in ${((Date.now() - t0) / 1000).toFixed(1)}s; sources=${out.length}; subs=${subs.length}`);
    for (const s of subs) console.log(`   SUB: ${s.lang} -> ${s.url.slice(0, 110)}`);
    const results = [];
    for (const l of out) {
      console.log(`   SRC: ${l.source} | ${l.name} | ${l.type} | ${qName(l.quality)} | ${l.url.slice(0, 120)}`);
    }
    for (const l of out) {
      const v = await validateLink(l);
      results.push({ l, v });
      console.log(`   VALIDATE ${l.type} ${qName(l.quality)} ${l.url.slice(0, 90)} -> ${v.ok ? "OK" : "BAD"}: ${v.why}`);
      await sleep(300);
    }
    const playable = results.filter((r) => r.v.ok);
    const audioBad = results.filter((r) => !r.v.ok && /VIDEO-ONLY/.test(r.v.why));
    const verdict = playable.length > 0 ? "PASS" : out.length === 0 ? "FAIL" : audioBad.length > 0 ? "PARTIAL" : "FAIL";
    const reason = playable.length > 0
      ? `${playable.length}/${out.length} playable`
      : out.length === 0 ? "no sources emitted" : audioBad.length > 0 ? "HLS video-only (sin audio)" : results.map((r) => r.v.why)[0] || "all invalid";
    rows.push({ ep: ep.url.replace("https://lmanime.com/", "").replace(/\/$/, ""), emitted: out.length, playable: playable.length, verdict, reason });
  }
  console.log("\n================ FINAL TABLE (LmAnime) ================");
  console.table(rows);
})();
