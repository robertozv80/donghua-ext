// Herramienta de validacion EN VIVO del provider MundoDonghuaProvider.kt.
// Port fiel (Node 24) de loadLinks + mdFmoonByseExtractor + StreamWish/JwPlayer +
// Asura/Tamamo + fallbacks, tal como estan en
// MundoDonghuaProvider/src/main/kotlin/com/donghuaext/MundoDonghuaProvider.kt
// y en common/src/CommonExtractors.kt (solo lo que el provider llama).
// NO modifica nada del codigo Kotlin: solo lee/valida.
//
// Uso: node tools/matrix_mundodonghua.mjs

import crypto from "node:crypto";

const MAIN = "https://www.mundodonghua.com";
// USER_AGENT de CloudStream usado por app.get() sin headers explicitos.
const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

const PLAYER_REFERER = "https://bysekoze.com/";
const API_BASE = "https://bysekoze.com";
const EMBED_ORIGIN = MAIN;
const EMBED_REFERER = MAIN + "/";

const QUAL = { Unknown: 400, P360: 360, P480: 480, P720: 720, P1080: 1080 };

// Evidencia de links que Kotlin DESCARTA (JwPlayerHelper: try/catch -> emptyList)
let genM3u8Errors = [];

// ------------------------------------------------------------------ HTTP
const jar = new Map();
function storeCookies(res) {
  try {
    for (const c of res.headers.getSetCookie()) {
      const pair = c.split(";")[0];
      const i = pair.indexOf("=");
      if (i > 0) jar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
    }
  } catch {}
}
const cookieHeader = () =>
  [...jar.entries()].map(([k, v]) => `${k}=${v}`).join("; ");

async function req(
  url,
  {
    method = "GET",
    headers = {},
    body = null,
    referer = null,
    timeoutMs = 20000,
    sendCookies = true,
    redirect = "follow",
    range = null,
  } = {},
) {
  const h = { "User-Agent": UA, Accept: "*/*", ...headers };
  if (referer && !h.Referer && !h.referer) h.Referer = referer;
  if (sendCookies && cookieHeader()) h.Cookie = cookieHeader();
  if (range) h.Range = range;
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      method,
      headers: h,
      body: body == null ? undefined : typeof body === "string" ? body : new URLSearchParams(body).toString(),
      signal: ctrl.signal,
      redirect,
    });
    storeCookies(res);
    const buf = Buffer.from(await res.arrayBuffer());
    let text = "";
    try {
      text = buf.toString("utf8");
    } catch {}
    return { status: res.status, text, buf, headers: res.headers, url: res.url };
  } catch (e) {
    return { status: 0, text: "", buf: Buffer.alloc(0), headers: null, url, err: e.name + ": " + e.message };
  } finally {
    clearTimeout(t);
  }
}

const resolveUrl = (u) =>
  u.startsWith("http")
    ? u
    : u.startsWith("//")
      ? "https:" + u
      : u.startsWith("/")
        ? MAIN + u
        : u
          ? MAIN + "/" + u
          : "";

// ------------------------------------------------------------------ Dean Edwards unpack
// port de getAndUnpack(): getPacked busca el PRIMER bloque eval(function(p,a,c,k,e,...))
// con terminador "))" y lo desempaqueta; si falla devuelve el string original.
function unpackLine(line) {
  try {
    if (!line.endsWith(")")) return null;
    const out = eval("(" + line.slice(5, -1) + ")");
    if (typeof out !== "string") return null;
    return out;
  } catch {
    return null;
  }
}
function getAndUnpack(text) {
  const m = /eval\(function\(p,a,c,k,e,.*\)\)/.exec(text);
  if (!m) return text;
  return unpackLine(m[0]) ?? text;
}
// MundoDonghua itera CADA linea empaquetada del <script> (FIX v22.3).
function mdUnpackLines(scriptData) {
  const out = [];
  for (const m of scriptData.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
    const line = m[0];
    // el regex de getPacked exige el terminador "))"
    if (!/^eval\(function\(p,a,c,k,e,.*\)\)/.test(line)) {
      out.push(line); // getAndUnpack devuelve el string tal cual (queda "empaquetado")
      continue;
    }
    const u = unpackLine(line);
    out.push(u == null ? line : u);
  }
  return out;
}

// <script> ... </script> de toda la pagina (equivale a doc.select("script"))
function scriptDatas(html) {
  const out = [];
  const re = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(html))) out.push(m[1]);
  return out;
}

// ------------------------------------------------------------------ M3u8Helper.generateM3u8
// master -> links por variante (sin trick-play) + el propio master (returnThis=true)
async function generateM3u8(source, streamUrl, referer, headers = {}) {
  const res = await req(streamUrl, { referer, headers, timeoutMs: 20000 });
  if (res.status !== 200) throw new Error(`HTTP ${res.status}${res.err ? " " + res.err : ""}`);
  const txt = res.text.replace(/\r/g, "");
  const lines = txt.split("\n").map((l) => l.trim());
  const isMaster = lines.some((l) => l.startsWith("#EXT-X-STREAM-INF"));
  const out = [];
  const seen = new Set();
  if (isMaster) {
    let quality = QUAL.Unknown;
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) continue; // trick play
      if (line.startsWith("#EXT-X-STREAM-INF")) {
        const m = /RESOLUTION=\d+x(\d+)/.exec(line);
        quality = m ? Number(m[1]) : QUAL.Unknown;
        // la siguiente linea no-comentada es la URL de la variante
        for (let j = i + 1; j < lines.length; j++) {
          if (!lines[j]) continue;
          if (lines[j].startsWith("#")) break;
          const abs = new URL(lines[j], streamUrl).toString();
          if (!seen.has(abs)) {
            seen.add(abs);
            out.push({ source, name: source, url: abs, type: "M3U8", quality, referer });
          }
          i = j;
          break;
        }
      }
    }
  }
  // Kotlin/M3u8Helper2: parsed != null solo si el body es una playlist HLS valida
  const parsed = txt.trimStart().startsWith("#EXTM3U");
  // TS_EXTENSION_REGEX = #EXTINF:(([0-9]*[.])?[0-9]+|).*\n(.+?\n)
  const isMedia = /#EXTINF:(([0-9]*[.])?[0-9]+|).*\n(.+?\n)/.test(txt);
  const anyFound = seen.size > 0;
  // if (parsed == null || !anyFound || returnThis) { if (parsed != null || TS_REGEX.matches) list += m3u8 }
  if (parsed || isMedia) {
    if (!seen.has(streamUrl)) out.unshift({ source, name: source, url: streamUrl, type: "M3U8", quality: QUAL.Unknown, referer });
  } else if (anyFound) {
    // master invalido: Kotlin lo descarta pero conserva las variantes
  }
  return out;
}

// ------------------------------------------------------------------ CommonExtractors (lo que usa el provider)
const unescJson = (s) =>
  s
    .replace(/\\u([\da-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/\\(["\\/nrt])/g, (_, c) => (c === "n" ? "\n" : c === "t" ? "\t" : c === "r" ? "\r" : c));

function dmVideoId(src) {
  return (
    /(?:geo\.dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|dailymotion\.com\/embed\/video\/)([A-Za-z0-9]+)/i.exec(
      src,
    )?.[1] ?? null
  );
}

// CE.extractDailymotion (v8): un master qualities.auto + mp4 progresivos de ultimo recurso
const emittedDmVids = new Set();
const emittedDmSubs = new Set();
async function extractDailymotion(iframeSrc, embedderReferer, linkName, subsOut, emit) {
  const vid = dmVideoId(iframeSrc) || (/^[A-Za-z0-9]+$/.test(iframeSrc.trim()) ? iframeSrc.trim() : null);
  if (!vid) return false;
  if (emittedDmVids.has(vid)) return true;
  const referer = embedderReferer || "https://animexin.dev";
  const metaUrl =
    `https://www.dailymotion.com/player/metadata/video/${vid}` +
    `?embedder=${encodeURIComponent(referer)}&integration=inline&app=com.dailymotion.neon`;
  const res = await req(metaUrl, {
    referer,
    headers: { "User-Agent": UA, Accept: "application/json" },
    timeoutMs: 20000,
  });
  if (res.status !== 200) {
    dmGate = `metadata HTTP ${res.status}`;
    return false;
  }
  const json = res.text.split("\\/").join("/").split("\\u0026").join("&");

  // subtitulos (bloque subtitles.data)
  const start = json.indexOf('"subtitles"');
  if (start >= 0) {
    const dataIdx = json.indexOf('"data"', start);
    const open = json.indexOf("{", dataIdx);
    if (open >= 0) {
      let depth = 0;
      let inStr = false;
      let esc = false;
      let block = null;
      for (let i = open; i < json.length; i++) {
        const ch = json[i];
        if (esc) {
          esc = false;
          continue;
        }
        if (ch === "\\" && inStr) esc = true;
        else if (ch === '"') inStr = !inStr;
        else if (!inStr && ch === "{") depth++;
        else if (!inStr && ch === "}") {
          depth--;
          if (depth === 0) {
            block = json.slice(open, i + 1);
            break;
          }
        }
      }
      if (block) {
        const found = [];
        for (const m of block.matchAll(
          /"(es[a-z-]*|en[a-z-]*|fr|pt|ru|tr|pl|ar|bn|fa|id|ms|it|de|hi|th|vi)":\s*\{[^{}]*?"urls":\["([^"]+)"/g,
        )) {
          found.push([m[1], unescJson(m[2])]);
        }
        const uniq = [];
        const seenU = new Set();
        for (const [code, url] of found) {
          if (seenU.has(url)) continue;
          seenU.add(url);
          uniq.push([code, url]);
        }
        uniq.sort((a, b) => (a[0].startsWith("es") ? 0 : a[0].startsWith("en") ? 1 : 2) - (b[0].startsWith("es") ? 0 : b[0].startsWith("en") ? 1 : 2));
        for (const [code, url] of uniq) {
          if (emittedDmSubs.has(url)) continue;
          emittedDmSubs.add(url);
          const name = code.startsWith("es") ? "Spanish" : code.startsWith("en") ? "English" : code;
          subsOut.push({ lang: name, url });
        }
      }
    }
  }

  const emittedUrls = new Set();
  let found = false;
  const doEmit = (u, hls, q) => {
    if (!u.startsWith("http")) return;
    if (emittedUrls.has(u)) return;
    emittedUrls.add(u);
    const suffix =
      q === QUAL.P1080 ? " 1080p" : q === QUAL.P720 ? " 720p" : q === QUAL.P480 ? " 480p" : q === QUAL.P360 ? " 360p" : "";
    const name = suffix && !/\b\d{3,4}p\b/i.test(linkName) ? linkName + suffix : linkName;
    emit({
      source: linkName,
      name,
      url: u,
      type: hls ? "M3U8" : "VIDEO",
      quality: q ?? QUAL.Unknown,
      referer: "https://www.dailymotion.com/",
      note: "CommonExtractors.extractDailymotion",
    });
    found = true;
  };
  // CE:227-254 tryPlaylist -> valida GET #EXT, expande variantes con generateM3u8,
  // y SI NO (403 por token 'sec' corto) emite el master crudo como respaldo.
  const tryPlaylist = async (u, hlsQ) => {
    if (!u || !u.startsWith("http")) return false;
    if (/dmxleo/i.test(u) || /advertising/i.test(u) || /auth=/i.test(u)) return false;
    let isValid = false;
    const v = await req(u, { referer: "https://www.dailymotion.com/", headers: { "User-Agent": UA }, timeoutMs: 12000 });
    isValid = v.status === 200 && v.text.trimStart().startsWith("#EXT");
    if (isValid) {
      try {
        const links = await generateM3u8(linkName, u, "https://www.dailymotion.com/");
        if (links.length) {
          links.forEach((l) => {
            emit({ ...l, note: "CE.tryPlaylist(expandida)" });
            found = true;
          });
          emittedUrls.add(u);
          return true;
        }
      } catch {}
    }
    doEmit(u, true, hlsQ);
    return true;
  };

  let emittedAny = false;
  for (const m of json.matchAll(/"auto":\[\{"url":"(https:\/\/[^"]+)"/g)) {
    if (emittedAny) break;
    if (m[1].includes("dailymotion.com")) emittedAny = await tryPlaylist(unescJson(m[1]), QUAL.Unknown);
  }
  if (!emittedAny) {
    for (const m of json.matchAll(/"auto":\[\{[^}]*?"type":"[^"]+","url":"(https:\/\/[^"]+)"/g)) {
      if (emittedAny) break;
      if (m[1].includes("dailymotion.com")) emittedAny = await tryPlaylist(unescJson(m[1]), QUAL.Unknown);
    }
  }
  if (!emittedAny) {
    const masterAuto =
      /https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"' ]*/i.exec(json)?.[0] ?? null;
    const dc = masterAuto ? /https:\/\/([a-z0-9.-]+)\.dailymotion\.com/i.exec(masterAuto)?.[1] : null;
    const vid2 = masterAuto ? /video\/([a-z0-9]+)\.m3u8/i.exec(masterAuto)?.[1] : null;
    if (dc && vid2 && !dc.startsWith("cdndirector")) {
      for (const preset of ["H264-1280x720", "H264-1920x1080", "H264-854x480"]) {
        if (tryPlaylist(`https://${dc}.dailymotion.com/cdn/${preset}/vCRN/mp4_h264_aac.m3u8?video=${vid2}`, QUAL.P720)) {
          emittedAny = true;
          break;
        }
      }
    }
  }
  if (!emittedAny) {
    for (const m of json.matchAll(/https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"' ]*/g)) {
      if (emittedAny) break;
      if (!/\/av1/i.test(m[0]) && !/_av1_/i.test(m[0]) && tryPlaylist(m[0], null)) emittedAny = true;
    }
  }
  if (emittedAny) {
    emittedDmVids.add(vid);
    return found;
  }
  // ultimo recurso: progresivos del qualities map (sin AV1)
  for (const m of json.matchAll(/"(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"/g)) {
    const hh = Number(m[1]);
    const url = unescJson(m[3]);
    const q = hh >= 1080 ? QUAL.P1080 : hh >= 720 ? QUAL.P720 : hh >= 480 ? QUAL.P480 : hh >= 360 ? QUAL.P360 : QUAL.Unknown;
    if (/\/av1/i.test(url) || /_av1_/i.test(url)) continue;
    doEmit(url, url.includes(".m3u8"), q);
  }
  if (!found) {
    const cand =
      (/auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json)?.[1] && unescJson(/auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json)[1])
        ) ??
      (/https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"' ]*/i.exec(json)?.[0] ?? null);
    if (cand && !/dmxleo/i.test(cand) && !/advertising/i.test(cand) && !/auth=/i.test(cand)) {
      doEmit(cand, true, QUAL.Unknown);
    }
  }
  if (found) emittedDmVids.add(vid);
  return found;
}
let dmGate = null;

// CE.extractOkRu
async function extractOkRu(embedUrl0, siteReferer, linkName, emit) {
  const url = embedUrl0.startsWith("//") ? "https:" + embedUrl0 : embedUrl0;
  const res = await req(url, { referer: siteReferer, timeoutMs: 20000 });
  const html = res.text;
  const decU = (s) => s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
  const raw = /data-options="([^"]+)"/.exec(html)?.[1];
  if (!raw) return false;
  const options = decU(raw.split("&quot;").join('"').split("\\/").join("/"));
  let found = false;
  const cands = [...options.matchAll(/"(?:[a-zA-Z]*ManifestUrl|ondemandHls)":"([^"]+)"/g)].map((m) => m[1]);
  let hlsUrl = null;
  for (const c of cands) {
    const t = await req(c, { referer: url, timeoutMs: 20000 });
    if (t.status === 200 && t.text.trimStart().startsWith("#EXTM3U")) {
      hlsUrl = c;
      break;
    }
  }
  if (hlsUrl) {
    try {
      for (const l of await generateM3u8(linkName, hlsUrl, url)) emit({ ...l, note: "CommonExtractors.extractOkRu" });
      found = true;
    } catch {}
  }
  for (const m of options.matchAll(/"name":"(mobile|lowest|low|sd|hd|full|super)","url":"([^"]+)"/g)) {
    let p = decU(m[2]);
    if (!p.startsWith("http")) continue;
    if (p.startsWith("//")) p = "https:" + p;
    const q = m[1] === "full" || m[1] === "super" ? QUAL.P1080 : m[1] === "hd" ? QUAL.P720 : m[1] === "sd" ? QUAL.P480 : QUAL.P360;
    emit({ source: linkName, name: linkName, url: p, type: "VIDEO", quality: q, referer: url, note: "CommonExtractors.extractOkRu progresivo" });
    found = true;
  }
  return found;
}

// ------------------------------------------------------------------ JwPlayerHelper (StreamWish/VidHidePro)
async function jwExtract(script, sourceName, mainUrl, emit, subsOut) {
  const out = [];
  const sourceMatches = [];
  for (const m of script.matchAll(/"?sources"?:\s*(\[.*?\])/g)) {
    let arr = m[1];
    for (const k of ["file", "label", "type"]) arr = arr.replace(new RegExp(`"?${k}"?`), `"${k}"`);
    try {
      const parsed = JSON.parse(arr);
      if (Array.isArray(parsed)) sourceMatches.push(...parsed);
    } catch {}
  }
  let extracted = [];
  for (const link of sourceMatches) {
    const clean = String(link.file || "").split("\\/").join("/");
    if (clean.includes(".m3u8") || clean.includes(".txt")) {
      // Kotlin: try/catch -> emptyList() (el link se PIERDE si generateM3u8 falla)
      try {
        extracted.push(...(await generateM3u8(sourceName, clean, mainUrl)));
      } catch {
        genM3u8Errors.push(`${sourceName}: generateM3u8 EXCEPT -> link descartado ${clean.slice(0, 90)}`);
      }
    } else if (clean) {
      extracted.push({
        source: sourceName,
        name: sourceName,
        url: jwFixUrl(clean, mainUrl),
        type: "VIDEO",
        quality: QUAL.Unknown,
        referer: clean,
      });
    }
  }
  if (extracted.length === 0) {
    for (const m of script.matchAll(/[:=]\s*"([^"\s]+(?:\.m3u8|master\.txt)[^"\s]*)"/g)) {
      const clean = m[1].split("\\/").join("/");
      const isHls = clean.includes(".m3u8") || clean.includes(".txt");
      extracted.push({
        source: sourceName,
        name: sourceName,
        url: jwFixUrl(clean, mainUrl),
        type: isHls ? "M3U8" : "INFER",
        quality: QUAL.Unknown,
        referer: clean,
        note: "JwPlayerHelper fallback m3u8Regex",
      });
    }
  }
  const subMatches = [];
  extracted.forEach(emit);
  for (const m of script.matchAll(/"?tracks"?:\s*(\[.*?\])/g)) {
    let arr = m[1];
    for (const k of ["file", "kind", "label"]) arr = arr.replace(new RegExp(`"?${k}"?`), `"${k}"`);
    try {
      for (const tr of JSON.parse(arr)) {
        // Kotlin: kind.contains("caption") || kind.contains("subtitle")  (sensible a mayusculas)
        const kind = String(tr?.kind ?? "");
        if (!(kind.includes("caption") || kind.includes("subtitle"))) continue;
        if (tr && tr.file && tr.label) {
          subMatches.push(1);
          subsOut.push({ lang: tr.label, url: jwFixUrl(String(tr.file), mainUrl) });
        }
      }
    } catch {}
  }
  return { count: extracted.length, linkFound: sourceMatches.length > 0 || subMatches.length > 0 };
}
const jwFixUrl = (url, mainUrl) => (url.startsWith("/") ? mainUrl + url : url.startsWith("http") ? url : `${mainUrl}/${url}`);

// mdVgExtractor / mdFmoonExtractor = StreamWishExtractor()
async function streamwishLike(embedUrl, name, mainUrl, emit, subsOut) {
  const headers = {
    Accept: "*/*",
    Connection: "keep-alive",
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "cross-site",
    Origin: mainUrl + "/",
    "User-Agent": UA,
  };
  let target = embedUrl;
  if (embedUrl.includes("/f/")) target = `${mainUrl}/${embedUrl.substringAfterShim("/f/")}`;
  else if (embedUrl.includes("/e/")) target = `${mainUrl}/${embedUrl.substringAfterShim("/e/")}`;
  const res = await req(target, { referer: MAIN + "/", headers, timeoutMs: 20000 });
  if (res.status !== 200) return { count: 0, gate: `HTTP ${res.status}${res.err ? " " + res.err : ""}` };
  const body = res.text;
  let playerScript = null;
  if (getPackedPresent(body)) playerScript = getAndUnpack(body);
  else {
    const jw = scriptDatas(body).find((s) => s.includes('jwplayer("vplayer").setup('));
    if (jw) playerScript = jw;
    else playerScript = /<script\b[^>]*>([\s\S]*?)<\/script>/i.test(body) ? scriptDatas(body).find((s) => s.includes("sources:")) ?? null : null;
  }
  if (!playerScript) return { count: 0, gate: "sin bloque jwplayer/sources" };
  const r = await jwExtract(playerScript, name, mainUrl, emit, subsOut);
  return { count: r.count, gate: r.linkFound ? null : "sources no parseable (el aditivo WebView de CloudStream no es portable)" };
}
const getPackedPresent = (t) => /eval\(function\(p,a,c,k,e,.*\)\)/.test(t);
String.prototype.substringAfterShim = function (sep) {
  const i = this.indexOf(sep);
  return i < 0 ? this : this.slice(i + sep.length);
};

// ------------------------------------------------------------------ mdFmoonByseExtractor
function b64UrlDecode(s) {
  let t = String(s).trim().replace(/-/g, "+").replace(/_/g, "/");
  while (t.length % 4) t += "=";
  return Buffer.from(t, "base64");
}
// Kotlin: rotl32(x, n) = (x shl n) or (x ushr (32 - n))  -> Int32 con signo
const rotl32 = (x, n) => ((x << n) | (x >>> (32 - n))) | 0;
function mixRound(t) {
  t[0] = (t[0] + t[1]) | 0;
  t[3] = rotl32(t[3] ^ t[0], 16);
  t[2] = (t[2] + t[3]) | 0;
  t[1] = rotl32(t[1] ^ t[2], 12);
  t[0] = (t[0] + t[1]) | 0;
  t[3] = rotl32(t[3] ^ t[0], 8);
  t[2] = (t[2] + t[3]) | 0;
  t[1] = rotl32(t[1] ^ t[2], 7);
}
function powHash(data) {
  const e = [1779033703 | 0, 3144134277 | 0, 1013904242 | 0, 2773480762 | 0];
  for (const b of data) {
    e[0] = (e[0] + (b & 0xff)) | 0;
    e[0] = rotl32(e[0], 7);
    mixRound(e);
  }
  for (let i = 0; i < 8; i++) mixRound(e);
  const size = 512;
  const mask = size - 1;
  const lr = 2654435761 | 0;
  const hr = 2246822519 | 0;
  const table = new Int32Array(size);
  for (let i = 0; i < size; i++) {
    mixRound(e);
    table[i] = (e[0] ^ e[2]) | 0;
  }
  for (let rep = 0; rep < 2; rep++) {
    for (let s = 0; s < size; s++) {
      let c = (table[s] + table[table[s] & mask]) | 0;
      c = rotl32(c, 13);
      c = (c ^ Math.imul(table[(s + 1) & mask], lr)) | 0;
      table[s] = c;
      e[0] = (e[0] ^ c) | 0;
      mixRound(e);
    }
  }
  const out = new Int32Array(8);
  const group = size / 8;
  for (let i = 0; i < 8; i++) {
    mixRound(e);
    let acc = e[0];
    const base = i * group;
    for (let j = 0; j < group; j++) {
      const d = table[base + j];
      acc = (acc + d) | 0;
      acc = rotl32(acc, 5);
      acc = (acc ^ Math.imul(d, hr)) | 0;
    }
    out[i] = (acc ^ e[2]) | 0;
  }
  return out;
}
function leadingZeroBits(words) {
  let total = 0;
  for (const w of words) {
    if (w === 0) {
      total += 32;
      continue;
    }
    total += Math.clz32(w);
    break;
  }
  return total;
}
function solvePow(nonce, difficulty) {
  if (difficulty <= 0) return "0";
  const prefix = Buffer.from(`${nonce}:`, "utf8");
  const started = Date.now();
  let counter = 0;
  for (;;) {
    const input = Buffer.concat([prefix, Buffer.from(String(counter), "utf8")]);
    if (leadingZeroBits(powHash(input)) >= difficulty) return String(counter);
    counter++;
    if (counter % 2048 === 0 && Date.now() - started > 45000) return null;
  }
}
function decryptPayload(enc) {
  const parts = enc.key_parts;
  if (!parts || !parts.length) return null;
  let version = null;
  if (typeof enc.version === "number") version = enc.version;
  else if (typeof enc.version === "string") version = Number(/\d+/.exec(enc.version)?.[0] ?? NaN);
  if (!Number.isFinite(version)) return null;
  const selected = [];
  for (const idx of [version, 31 - version]) {
    const part = idx >= 1 && idx <= parts.length ? parts[idx - 1] : null;
    if (part && part.trim()) selected.push(part);
  }
  if (!selected.length) selected.push(...parts);
  const key = Buffer.concat(selected.map(b64UrlDecode));
  if (![16, 24, 32].includes(key.length)) return null;
  const iv = b64UrlDecode(enc.iv);
  const payload = b64UrlDecode(enc.payload);
  if (payload.length <= 16) return null;
  try {
    const d = crypto.createDecipheriv("aes-" + key.length * 8 + "-gcm", key, iv);
    d.setAuthTag(payload.subarray(payload.length - 16));
    const plain = Buffer.concat([d.update(payload.subarray(0, payload.length - 16)), d.final()]);
    return JSON.parse(plain.toString("utf8"));
  } catch {
    return null;
  }
}
function apiHeaders() {
  return {
    "User-Agent": UA,
    Accept: "application/json",
    Origin: EMBED_ORIGIN,
    Referer: EMBED_REFERER,
    "X-Embed-Origin": EMBED_ORIGIN,
    "X-Embed-Referer": EMBED_REFERER,
    "X-Embed-Parent": EMBED_REFERER,
  };
}
async function fetchPublicVideo(code) {
  const res = await req(`${API_BASE}/api/videos/${code}`, { headers: apiHeaders(), timeoutMs: 15000 });
  if (res.status === 200 && res.text.includes('"playback"')) return JSON.parse(res.text);
  return null;
}
async function fetchViaCaptcha(code) {
  const headers = { ...apiHeaders(), "Content-Type": "application/json" };
  const cap = await req(`${API_BASE}/api/videos/${code}/embed/captcha`, { method: "POST", headers, body: "{}", timeoutMs: 20000 });
  const nonce = /"pow_nonce"\s*:\s*"([^"]+)"/.exec(cap.text)?.[1];
  const difficulty = Number(/"pow_difficulty"\s*:\s*(\d+)/.exec(cap.text)?.[1]);
  const powToken = /"pow_token"\s*:\s*"([^"]+)"/.exec(cap.text)?.[1];
  if (!nonce || !Number.isFinite(difficulty) || !powToken) return null;
  const solution = solvePow(nonce, difficulty);
  if (!solution) return null;
  const ver = await req(`${API_BASE}/api/videos/${code}/embed/captcha/verify`, {
    method: "POST",
    headers,
    body: JSON.stringify({ pow_token: powToken, solution }),
    timeoutMs: 20000,
  });
  const token = /"token"\s*:\s*"([^"]+)"/.exec(ver.text)?.[1];
  if (!token) return null;
  const uuid = () => crypto.randomUUID();
  const pb = await req(`${API_BASE}/api/videos/${code}/embed/playback`, {
    method: "POST",
    headers: { ...headers, "X-Captcha-Token": token },
    body: JSON.stringify({
      fingerprint: { token, viewer_id: uuid(), device_id: uuid(), confidence: 0.9 },
    }),
    timeoutMs: 30000,
  });
  try {
    return JSON.parse(pb.text);
  } catch {
    return null;
  }
}
function mergeVttSegment(raw) {
  let v = raw.replace(/\r/g, "");
  v = v.replace(/^WEBVTT[^\n]*\n/, "");
  v = v.replace(/^NOTE[^\n]*\n/gm, "");
  v = v.replace(/^\d+[ \t]*\n/gm, "");
  return v.trim() + "\n";
}
async function emitMergedSubtitle(playlistUrl, lang, subsOut) {
  const pl = await req(playlistUrl, { referer: PLAYER_REFERER, timeoutMs: 15000 });
  if (pl.status !== 200) return;
  const segments = pl.text
    .replace(/\r/g, "")
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith("#"))
    .slice(0, 80);
  if (!segments.length) return;
  let sb = "WEBVTT\n\n";
  let merged = 0;
  for (const seg of segments) {
    const abs = new URL(seg, playlistUrl).toString();
    const body = await req(abs, { referer: PLAYER_REFERER, timeoutMs: 10000 });
    if (body.status !== 200 || !body.text.trim()) continue;
    sb += mergeVttSegment(body.text);
    merged++;
  }
  if (!merged) return;
  subsOut.push({ lang, url: `data:text/vtt;base64,${Buffer.from(sb, "utf8").toString("base64")}`.slice(0, 60) + ` (${merged} segmentos)` });
}
async function byseGetUrl(embedUrl, emit, subsOut) {
  const code = /bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/.exec(embedUrl)?.[1];
  if (!code) return { count: 0, gate: "no hay code bysekoze en el embed" };
  const video = (await fetchPublicVideo(code)) ?? (await fetchViaCaptcha(code));
  if (!video) return { count: 0, gate: "API /api/videos/{code} sin playback (y PoW fallback fallo)" };
  const enc = video.playback;
  if (!enc) return { count: 0, gate: "respuesta sin campo playback" };
  const dec = decryptPayload(enc);
  if (!dec) return { count: 0, gate: "AES-GCM no descifrado (key_parts/version)" };
  let masterForSubs = null;
  let count = 0;
  for (const src of dec.sources || []) {
    const url = (src.url || "").trim();
    if (!url) continue;
    const height = src.height || 0;
    const isHls = /mpegurl/i.test(src.mime_type || "") || url.includes(".m3u8");
    if (isHls) {
      if (!masterForSubs && url.split("?")[0].endsWith("master.m3u8")) masterForSubs = url;
      try {
        for (const l of await generateM3u8("Fm", url, PLAYER_REFERER)) {
          emit({ ...l, note: "generateM3u8(Fm)" });
          count++;
        }
      } catch (e) {
        // Kotlin:354 `try { ... } catch (_: Exception) {}` -> el source se pierde en silencio
        genM3u8Errors.push(`Fm/bysekoze: generateM3u8 EXCEPT (${e.message}) -> source descartado en silencio`);
      }
    } else {
      emit({
        source: "Fm",
        name: "Fm",
        url,
        type: "VIDEO",
        quality: height >= 1080 ? QUAL.P1080 : height >= 720 ? QUAL.P720 : height >= 480 ? QUAL.P480 : QUAL.Unknown,
        referer: PLAYER_REFERER,
      });
      count++;
    }
  }
  // subtitulos: tracks de la API + renditions SUBTITLES del master
  const tracks = video.tracks || [];
  const emittedSubs = new Set();
  for (const t of tracks) {
    const u = (t.url || t.src || "").trim();
    if (!u || emittedSubs.has(u)) continue;
    emittedSubs.add(u);
    const raw = t.lang || t.language || t.label || t.name || "es";
    subsOut.push({ lang: /es/i.test(raw) ? "Spanish" : raw, url: u });
    count += 0;
  }
  if (masterForSubs) {
    const m = await req(masterForSubs, { referer: PLAYER_REFERER, timeoutMs: 12000 });
    if (m.status === 200 && m.text.includes("#EXT-X-MEDIA")) {
      for (const line of m.text.matchAll(/#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*/g)) {
        const uri = /URI="([^"]+)"/.exec(line[0])?.[1];
        if (!uri) continue;
        const nameM = /NAME="([^"]*)"/.exec(line[0]);
        const nm = nameM ? nameM[1] : "";
        const lang = /es/i.test(nm) ? "Spanish" : nm || "Spanish";
        const abs = new URL(uri, masterForSubs).toString();
        if (emittedSubs.has(abs)) continue;
        emittedSubs.add(abs);
        await emitMergedSubtitle(abs, lang, subsOut);
      }
    }
  }
  return { count };
}

// ------------------------------------------------------------------ loadExtractor (mini-router con los hosts que aparecen)
const RAW_EMBED_RE = [
  /https?:\/\/[^\s"'<>]*vidhidepro\.com\/[ve]\/[a-zA-Z0-9]+/,
  /https?:\/\/[^\s"'<>]*vgembed\.com\/e\/[a-zA-Z0-9]+/,
  /https?:\/\/[^\s"'<>]*embedwish\.com\/e\/[a-zA-Z0-9]+/,
  /https?:\/\/[^\s"'<>]*streamwish\.to\/e\/[a-zA-Z0-9]+/,
  /https?:\/\/[^\s"'<>]*bysekoze\.com\/e\/[a-zA-Z0-9]+/,
  /https?:\/\/[^\s"'<>]*filemoon\.to\/e\/[a-zA-Z0-9]+/,
  /https?:\/\/voe\.sx\/e\/[a-zA-Z0-9]+/,
];
function findAllGlobally(html, re) {
  const out = [];
  const g = new RegExp(re.source, re.flags.includes("g") ? re.flags : re.flags + "g");
  let m;
  while ((m = g.exec(html))) out.push(m[0]);
  return out;
}

async function loadExtractorRouted(embedUrl, referer, emit, subsOut) {
  const u = embedUrl;
  if (u.includes("dailymotion.com") || u.includes("dai.ly")) {
    const ok = await extractDailymotion(u, referer, "Dailymotion", subsOut, emit);
    return { count: ok ? 1 : 0, gate: ok ? null : dmGate };
  }
  if (u.includes("ok.ru") || u.includes("odnoklassniki")) {
    const n0 = emitCount();
    const ok = await extractOkRu(u, referer, "Okru", (l) => emit({ ...l }));
    return { count: emitCount() - n0, gate: ok ? null : "data-options no encontrado" };
  }
  if (u.includes("vidhidepro.com")) return await streamwishLikeR(u, "VidHidePro", "https://vidhidepro.com", emit, subsOut);
  if (u.includes("embedwish.com")) return await streamwishLike(u, "Embedwish", "https://embedwish.com", emit, subsOut);
  if (u.includes("streamwish.to")) return await streamwishLike(u, "Streamwish", "https://streamwish.to", emit, subsOut);
  // mdVgExtractor = StreamWishExtractor con mainUrl https://vgembed.com; no esta en el
  // registry del APK, el provider lo instancia a mano (Kotlin:26-29, 389).
  if (u.includes("vgembed.com")) return await streamwishLike(u, "Vg", "https://vgembed.com", emit, subsOut);
  if (u.includes("voe.sx") || /voe\.[a-z]+/.test(u)) return await voeExtract(u, referer, emit);
  if (u.includes("streamtape")) return await streamTapeExtract(u, referer, emit);
  return { count: 0, gate: "sin extractor propio (loadExtractor no conoce el host en el port)" };
}

// VidHidePro.getEmbedUrl: /d/ /download/ /file/ /f/ -> /v/
async function streamwishLikeR(embedUrl, name, mainUrl, emit, subsOut) {
  const emb = embedUrl
    .replace("/d/", "/v/")
    .replace("/download/", "/v/")
    .replace("/file/", "/v/")
    .replace("/f/", "/v/");
  const headers = {
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "cross-site",
    Origin: mainUrl,
    "User-Agent": UA,
  };
  const res = await req(emb, { referer: MAIN + "/", headers, timeoutMs: 20000 });
  if (res.status !== 200) return { count: 0, gate: `HTTP ${res.status}` };
  const script = getPackedPresent(res.text) ? getAndUnpack(res.text) : scriptDatas(res.text).find((s) => s.includes("sources:")) ?? null;
  if (!script) return { count: 0, gate: "sin script sources:" };
  const r = await jwExtract(script, name, mainUrl, emit, subsOut);
  return { count: r.count, gate: r.linkFound ? null : "sources no parseable" };
}

// Voe extractor (CloudStream) - rot13 + patrones + b64 + shift -3 + reverse + b64
async function voeExtract(url, referer, emit) {
  let res = await req(url, { referer, timeoutMs: 20000 });
  const red = /window\.location\.href\s*=\s*'([^']+)';/.exec(res.text)?.[1];
  if (red) res = await req(red, { referer, timeoutMs: 20000 });
  const jsonScript = /<script[^>]*type="application\/json"[^>]*>([\s\S]*?)<\/script>/i.exec(res.text)?.[1];
  if (!jsonScript) return { count: 0, gate: "voe: sin script[type=application/json]" };
  const enc = jsonScript.trim().substringAfterShim('["').substringBeforeLastShim('"]');
  const dec = decryptF7(enc);
  if (!dec) return { count: 0, gate: "voe: decryptF7 fallo" };
  let n = 0;
  const mainUrl = "https://voe.sx";
  if (dec.source) {
    for (const l of await generateM3u8("Voe", dec.source, mainUrl + "/", { Origin: mainUrl + "/" })) {
      emit({ ...l, note: "CloudStream Voe" });
      n++;
    }
  }
  if (dec.direct_access_url) {
    emit({ source: "Voe MP4", name: "Voe MP4", url: dec.direct_access_url, type: "VIDEO", quality: QUAL.Unknown, referer: url, note: "CloudStream Voe" });
    n++;
  }
  return { count: n, gate: n ? null : "voe: JSON sin source" };
}
String.prototype.substringBeforeLastShim = function (sep) {
  const i = this.lastIndexOf(sep);
  return i < 0 ? this : this.slice(0, i);
};
function decryptF7(p8) {
  try {
    let v = p8.replace(/[A-Za-z]/g, (c) => {
      const base = c <= "Z" ? 65 : 97;
      return String.fromCharCode(((c.charCodeAt(0) - base + 13) % 26) + base);
    });
    for (const p of ["@$", "^^", "~@", "%?", "*~", "!!", "#&"]) v = v.split(p).join("_");
    v = v.replace(/_/g, "");
    const s1 = Buffer.from(v, "base64").toString("latin1");
    const s2 = [...s1].map((c) => String.fromCharCode(c.charCodeAt(0) - 3)).join("");
    const s3 = [...s2].reverse().join("");
    return JSON.parse(Buffer.from(s3, "base64").toString("utf8"));
  } catch {
    return null;
  }
}
async function streamTapeExtract(url, referer, emit) {
  const res = await req(url, { referer, timeoutMs: 20000 });
  const line = scriptDatas(res.text).join("\n").split("\n").find((l) => l.includes("botlink').innerHTML"));
  if (!line) return { count: 0, gate: "streamtape: sin botlink" };
  const after = line.substringAfterShim(").innerHTML").replace("=", "");
  const str = /['"]([^'"]+)['"]/.exec(after)?.[1];
  if (!str) return { count: 0, gate: "streamtape: sin cadena de url" };
  emit({ source: "StreamTape", name: "StreamTape", url: `https:${str}&stream=1`, type: "VIDEO", quality: QUAL.Unknown, referer: url, note: "CloudStream StreamTape" });
  return { count: 1, gate: null };
}

// ------------------------------------------------------------------ mdHandleEmbed
let LINKS = [];
let SUBS = [];
const emitCount = () => LINKS.length;
function mdHandleEmbed(embedUrl, seen, referer) {
  if (seen.has(embedUrl)) return { count: 0, gate: "duplicado (seen)" };
  seen.add(embedUrl);
  const before = LINKS.length;
  try {
    if (embedUrl.includes("vgembed.com")) return streamwishLike(embedUrl, "Vg", "https://vgembed.com", (l) => LINKS.push(l), SUBS);
    if (embedUrl.includes("bysekoze.com")) return byseGetUrl(embedUrl, (l) => LINKS.push(l), SUBS);
    if (embedUrl.includes("filemoon")) return streamwishLike(embedUrl, "Fmoon", "https://bysekoze.com", (l) => LINKS.push(l), SUBS);
    return loadExtractorRouted(embedUrl, referer, (l) => LINKS.push(l), SUBS);
  } catch (e) {
    return { count: LINKS.length - before, gate: "excepcion: " + e.message };
  }
}

const mdServerPriority = (u) => {
  const x = u.toLowerCase();
  if (x.includes("bysekoze.com")) return 4;
  if (x.includes("embedwish.com") || x.includes("streamwish.to")) return 3;
  if (x.includes("vidhidepro.com")) return 2;
  return 0;
};

// ------------------------------------------------------------------ loadLinks (port)
async function loadLinks(data) {
  LINKS = [];
  SUBS = [];
  genM3u8Errors = [];
  const gates = [];
  const res = await req(data, { timeoutMs: 120000 });
  const html = res.text;
  if (res.status !== 200) return { links: [], subs: [], gates: [`episodio HTTP ${res.status}`], deadLinks: [] };
  const datafix = data.replaceAll("ñ", "%C3%B1");
  const seenEmbeds = new Set();
  const embedsFound = [];
  const embedSet = new Set();
  const deadLinks = [];
  const reqHEAD = {
    Accept: "*/*",
    "Accept-Language": "en-US,en;q=0.5",
    "X-Requested-With": "XMLHttpRequest",
    Referer: datafix,
    DNT: "1",
    Connection: "keep-alive",
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "same-origin",
    TE: "trailers",
  };

  const addEmbed = (u) => {
    if (!embedSet.has(u)) {
      embedSet.add(u);
      embedsFound.push(u);
    }
  };

  const tabs = [...html.matchAll(/<button[^>]*class="md-server-tab[^"]*"[^>]*data-target="([^"]+)"/g)].map((m) => m[1]);

  for (const script of scriptDatas(html)) {
    if (!script.includes("eval(function(p,a,c,k,e")) continue;
    for (const packedLine of mdUnpackLines(script)) {
      const unpack = packedLine;
      if (!unpack || unpack.includes("eval(function(p,a,c,k,e")) continue;

      // ---- Asura
      if (unpack.includes("asura_player") || unpack.includes("redirector")) {
        const slug = /redirector\.php\?slug=([A-Za-z0-9+/=]+)/.exec(unpack)?.[1];
        if (slug) {
          deadLinks.push({
            source: "Asura",
            name: "Asura",
            url: `https://www.mdnemonicplayer.xyz/nemonicplayer/redirector.php?slug=${slug}`,
            type: "M3U8",
            quality: QUAL.Unknown,
            referer: datafix,
            note: "emitido en crudo (v26 SPEED)",
          });
        }
        const f = /["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/.exec(unpack)?.[1];
        if (f) deadLinks.push({ source: "Asura", name: "Asura", url: f, type: "M3U8", quality: QUAL.Unknown, referer: datafix });
      }

      // ---- Tamamo / Protea (Dailymotion via api_donghua.php)
      if (unpack.includes("protea_tab") || unpack.includes("tamamo") || unpack.includes("api_donghua")) {
        const slugPatterns = [/slug["']?\s*:\s*["']([A-Za-z0-9+/=]+)["']/, /data\s*:\s*\{\s*["']slug["']\s*:\s*["']([A-Za-z0-9+/=]+)["']/];
        for (const p of slugPatterns) {
          const slug = p.exec(unpack)?.[1];
          if (!slug) continue;
          const api = await req(`${MAIN}/api_donghua.php?slug=${slug}`, { headers: reqHEAD, timeoutMs: 15000 });
          const key = /"url"\s*:\s*"([^"]+)"/.exec(api.text)?.[1];
          if (!key) {
            gates.push(`Tamamo: api_donghua HTTP ${api.status}${api.err ? " " + api.err : ""}, sin "url"`);
            break;
          }
          const playerUrl = `https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=${key}`;
          const pl = await req(playerUrl, { headers: reqHEAD, timeoutMs: 6000 });
          if (pl.status !== 200) {
            gates.push(`Tamamo: dmplayer.php HTTP ${pl.status}${pl.err ? " (" + pl.err.split(":")[0] + ")" : ""} -> sin vidID`);
            break;
          }
          const dmPatterns = [/video\s*:\s*["']([A-Za-z0-9]+)["']/, /DM\.player\([^,]+,\s*\{[^}]*video\s*:\s*["']([A-Za-z0-9]+)["']/, /video["']\s*:\s*["']([A-Za-z0-9]+)["']/];
          let vidID = null;
          for (const p of dmPatterns) {
            vidID = p.exec(pl.text)?.[1];
            if (vidID) break;
          }
          if (!vidID) {
            gates.push("Tamamo: dmplayer responded pero sin videoId");
            break;
          }
          const meta = await req(`https://www.dailymotion.com/player/metadata/video/${vidID}`, {
            referer: `https://www.dailymotion.com/embed/video/${vidID}`,
            headers: { "User-Agent": UA, Accept: "application/json" },
            timeoutMs: 15000,
          });
          // KOTLIN (linea 942/951): aplica los regex a jsonText SIN desescapar "\/" ->
          // nunca matchea. Lo replicamos para reportar el comportamiento real.
          const jsonText = meta.text;
          const m3 = /https?:\/\/[^"'\s<>]+\.m3u8[^\s"'<>]*/.exec(jsonText);
          if (m3) deadLinks.push({ source: "Tamamo", name: "Tamamo", url: m3[0], type: "M3U8", quality: QUAL.Unknown, referer: "https://www.dailymotion.com", note: "crudo (v26 SPEED)" });
          const mp4s = [...new Set([...jsonText.matchAll(/https?:\/\/[^"'\s<>]+\.mp4[^\s"'<>]*/g)].map((m) => m[0]))];
          for (const mp4 of mp4s) {
            const q = mp4.includes("1080") ? QUAL.P1080 : mp4.includes("720") ? QUAL.P720 : mp4.includes("480") ? QUAL.P480 : QUAL.Unknown;
            deadLinks.push({ source: "Tamamo", name: `Tamamo ${Math.floor(q / 1000)}p`, url: mp4, type: "VIDEO", quality: q, referer: "https://www.dailymotion.com" });
          }
          await loadExtractorRouted(`https://www.dailymotion.com/embed/video/${vidID}`, data, (l) => LINKS.push(l), SUBS);
          break;
        }
      }

      // ---- Fmoon / FileMoon
      const fmPatterns = [/bysekoze\.com\/e\/([a-zA-Z0-9]+)/, /filemoon\.to\/e\/([a-zA-Z0-9]+)/, /fmoonplay.*?src=['"]([^'"]+)['"]/];
      for (const p of fmPatterns) {
        const m = p.exec(unpack);
        if (!m) continue;
        const fmUrl = p.source.includes("src=") ? m[1] : `https://bysekoze.com/e/${m[1]}`;
        addEmbed(fmUrl);
        break;
      }

      // ---- Vhide
      for (const p of [/vidhidepro\.com\/v\/([a-zA-Z0-9]+)/, /vidhidepro\.com\/e\/([a-zA-Z0-9]+)/]) {
        const id = p.exec(unpack)?.[1];
        if (!id) continue;
        addEmbed(`https://vidhidepro.com/${p.source.includes("/v/") ? "v" : "e"}/${id}`);
        break;
      }

      // ---- Swish
      for (const p of [/embedwish\.com\/e\/([a-zA-Z0-9]+)/, /streamwish\.to\/e\/([a-zA-Z0-9]+)/]) {
        const id = p.exec(unpack)?.[1];
        if (!id) continue;
        addEmbed(`https://embedwish.com/e/${id}`);
        break;
      }

      // ---- fallback m3u8/mp4 del unpack (EN CRUDO, linea 1036)
      for (const m of unpack.matchAll(/(https?:\/\/[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)/g)) {
        deadLinks.push({ source: "Server", name: "Server", url: m[1], type: "M3U8", quality: QUAL.Unknown, referer: datafix, note: "fallback urlRegex" });
      }

      // ---- iframes conocidos
      const iframeRegexes = [
        /https?:\/\/voe\.sx\/e\/[^\s"'<>]+/g,
        /https?:\/\/[^\s"'<>]*bysekoze\.com\/e\/[^\s"'<>]+/g,
        /https?:\/\/[^\s"'<>]*embedwish\.com\/e\/[^\s"'<>]+/g,
        /https?:\/\/[^\s"'<>]*vidhidepro\.com\/[ve]\/[^\s"'<>]+/g,
        /https?:\/\/[^\s"'<>]*vgembed\.com\/e\/[^\s"'<>]+/g,
        /https?:\/\/[^\s"'<>]*streamwish\.to\/e\/[^\s"'<>]+/g,
        /https?:\/\/[^\s"'<>]*filemoon\.to\/e\/[^\s"'<>]+/g,
      ];
      for (const re of iframeRegexes) for (const u of findAllGlobally(unpack, re)) addEmbed(u);
    }
  }

  // fallback HTML crudo
  for (const re of RAW_EMBED_RE) for (const u of findAllGlobally(html, re)) addEmbed(u);

  // orden por prioridad (sortedByDescending es estable)
  const ordered = embedsFound
    .map((u, i) => ({ u, i }))
    .sort((a, b) => mdServerPriority(b.u) - mdServerPriority(a.u) || a.i - b.i)
    .map((x) => x.u);

  for (const emb of ordered) {
    const r = await mdHandleEmbed(emb, seenEmbeds, data);
    if (r && r.gate) gates.push(`${emb.substringAfterShim("://").substringBeforeShim("/")}: ${r.gate}`);
  }
  for (const d of deadLinks) LINKS.push(d);

  return { links: LINKS, subs: SUBS, gates: gates.concat(genM3u8Errors), tabs, deadLinks };
}
String.prototype.substringBeforeShim = function (sep) {
  const i = this.indexOf(sep);
  return i < 0 ? this : this.slice(0, i);
};

// ------------------------------------------------------------------ descubrimiento (getMainPage + load)
async function discoverEpisodes() {
  const home = await req(`${MAIN}/`, { timeoutMs: 120000 });
  // Kotlin: doc.select("div#nuevos-episodios-grid div.md-card") -> a[href] + h3.md-card-title.
  // Jsoup es independiente del orden de los atributos; aqui se emula con bloques.
  const cards = [];
  const seenCard = new Set();
  for (const block of home.text.split(/<div class="md-card">/).slice(1)) {
    const b = block.slice(0, 1600);
    const href = /<a[^>]*href="(\/ver\/[^"]+)"/.exec(b)?.[1];
    const title = /<h3[^>]*class="md-card-title"[^>]*>([^<]+)<\/h3>/.exec(b)?.[1]?.trim();
    if (!href || !title) continue;
    const limited = /fa-lock|md-card[^"]*limited|Limitado/.test(b);
    const key = href + "|" + title;
    if (seenCard.has(key)) continue;
    seenCard.add(key);
    cards.push({ href: resolveUrl(href), title, limited });
  }
  const bySeries = new Map();
  for (const c of cards) {
    // convertEpisodeToDonghuaUrl: /ver/{slug}/{ep}[/{token}] -> /donghua/{slug}
    const slug = /\/ver\/([^/]+)/.exec(c.href)?.[1];
    if (!slug) continue;
    if (!bySeries.has(slug)) bySeries.set(slug, []);
    bySeries.get(slug).push(c);
  }
  console.log(`  home: ${cards.length} md-card, ${bySeries.size} series distintas`);
  const picked = [];
  const seriesOrder = [...bySeries.keys()];
  for (const slug of seriesOrder.slice(0, 6)) {
    const ficha = await req(`${MAIN}/donghua/${slug}`, { timeoutMs: 120000 });
    if (ficha.status !== 200) continue;
    // Kotlin: doc.select("li.md-episode-item") -> a.md-ep-link href + li[data-ep]
    const eps = [];
    const seenEp = new Set();
    for (const block of ficha.text.split(/<li class="md-episode-item"/).slice(1)) {
      const b = block.slice(0, 1200);
      const tag = b.substringBeforeShim(">");
      const dataEp = Number(/data-ep="(\d+)"/.exec(tag)?.[1] ?? NaN);
      const url = /<a[^>]*class="md-ep-link"[^>]*href="([^"]+)"|<a[^>]*href="([^"]+)"[^>]*class="md-ep-link"/.exec(b);
      const raw = url && (url[1] || url[2]);
      if (!raw) continue;
      const href = resolveUrl(raw);
      if (seenEp.has(href)) continue;
      seenEp.add(href);
      const ep = Number.isFinite(dataEp) ? dataEp : Number(/\/(\d+)\/?$/.exec(href.split("?")[0])?.[1] ?? 0);
      eps.push({ ep, url: href });
    }
    const eps2 = eps.length
      ? eps
      : [
          ...new Set(
            [...ficha.text.matchAll(/href="(\/ver\/[^"]+)"/g)].map((m) => {
              const url = resolveUrl(m[1]);
              const ep = Number(/\/(\d+)\/?$/.exec(url.split("?")[0])?.[1] ?? 0);
              return JSON.stringify({ ep, url });
            }),
          ),
        ].map((s) => JSON.parse(s));
    eps2.sort((a, b) => a.ep - b.ep);
    if (!eps2.length) continue;
    console.log(`  /donghua/${slug}: ${eps2.length} episodios (Kotlin li.md-episode-item)`);
    const clean = (bySeries.get(slug)[0]?.title || slug).replace(/\s*Episodio\s*\d+/i, "").trim();
    picked.push({ slug, series: clean, ep: eps2[eps2.length - 1].ep, data: eps2[eps2.length - 1].url, kind: "reciente" });
    if (eps2.length > 10) picked.push({ slug, series: clean, ep: eps2[1].ep, data: eps2[1].url, kind: "antiguo" });
    if (picked.length >= 6) break;
  }
  return picked.slice(0, 5);
}

// ------------------------------------------------------------------ validacion
const AUDIO_CODECS = /(mp4a|ac-3|ec-3|opus|alac|flac|aac|mp3)/i;
function tsAudio(buf) {
  const PKT = 188;
  let pmtPid = null;
  const types = new Set();
  const pesIds = new Set();
  let aligned = 0;
  for (let off = 0; off + PKT <= buf.length; off += PKT) {
    if (buf[off] !== 0x47) {
      // resincronizar: buscar el primer paquete con sync valido
      let fix = -1;
      for (let k = 1; k < PKT; k++) {
        if (buf[off + k] === 0x47 && off + k + PKT <= buf.length && buf[off + k + PKT] === 0x47) {
          fix = k;
          break;
        }
      }
      if (fix < 0) continue;
      off += fix - PKT;
      aligned++;
      continue;
    }
    const pusi = (buf[off + 1] & 0x40) !== 0;
    const pid = ((buf[off + 1] & 0x1f) << 8) | buf[off + 2];
    const adapt = (buf[off + 3] >> 4) & 3;
    let p = off + 4;
    if (adapt === 2) continue; // solo adaptacion, sin payload
    if (adapt === 3) {
      const afLen = buf[off + 4];
      p = off + 5 + afLen;
    }
    if (p >= off + PKT || p >= buf.length) continue;
    if (pid === 8191 || pid === 0x1fff) continue; // null packet
    if (!pusi) {
      // continuation: aun podemos toparnos con una cabecera PES troceada
      continue;
    }
    const pointer = buf[p];
    const t = p + 1 + pointer;
    if (t >= buf.length) continue;
    if (pid === 0 && t + 12 <= buf.length && buf[t] === 0x00) {
      // PAT: table_id(1) section_length(2) ts_id(2) res/ver/cnr(1) section_num(1) protocol(1) = 8
      const sectionLen = ((buf[t + 1] & 0x0f) << 8) | buf[t + 2];
      const loopEnd = Math.min(t + 3 + sectionLen - 4, buf.length - 1, t + PKT);
      for (let q = t + 8; q + 3 < loopEnd; q += 4) {
        const progNum = ((buf[q] & 0x03) << 8) | buf[q + 1];
        const pm = ((buf[q + 2] & 0x1f) << 8) | buf[q + 3];
        if (progNum !== 0 && pm < 8191) pmtPid = pm;
      }
    } else if (pmtPid != null && pid === pmtPid && buf[t] === 0x02 && t + 12 < buf.length) {
      const sectionLen = ((buf[t + 1] & 0x0f) << 8) | buf[t + 2];
      const progInfoLen = (((buf[t + 10] & 0x0f) << 8) | buf[t + 11]) & 0xfff;
      const sectionEnd = Math.min(t + 3 + sectionLen - 4, buf.length - 1, t + PKT);
      let q = t + 12 + progInfoLen;
      while (q + 4 < sectionEnd) {
        types.add(buf[q]);
        const esLen = (((buf[q + 3] & 0x0f) << 8) | buf[q + 4]) & 0xfff;
        q += 5 + esLen;
      }
    }
    // señal secundaria: cabeceras PES dentro del payload
    for (let s = t; s + 3 < Math.min(off + PKT, buf.length - 1); s++) {
      if (buf[s] === 0x00 && buf[s + 1] === 0x00 && buf[s + 2] === 0x01 && buf[s + 3] >= 0xbc) pesIds.add(buf[s + 3]);
    }
  }
  const AUDIO_TYPES = [0x03, 0x04, 0x0f, 0x11, 0x87, 0x81, 0x82, 0x83, 0x06];
  const VIDEO_TYPES = [0x01, 0x02, 0x1b, 0x24, 0x0c, 0x15];
  const audioByPmt = [...types].some((t) => AUDIO_TYPES.includes(t));
  const videoByPmt = [...types].some((t) => VIDEO_TYPES.includes(t));
  const audioByPes = [...pesIds].some((id) => (id >= 0xc0 && id <= 0xdf) || id === 0xbd);
  const videoByPes = [...pesIds].some((id) => id >= 0xe0 && id <= 0xef);
  return {
    audio: audioByPmt || audioByPes,
    video: videoByPmt || videoByPes,
    types: [...types].map((t) => t.toString(16)),
    pes: [...pesIds].map((t) => t.toString(16)),
    aligned,
  };
}
function mp4HasAudio(buf) {
  const s = buf.toString("latin1");
  const fourcc = [...new Set((s.match(/[\x00-\x7f]{4}/g) || []).filter((f) => /^(moov|trak|mdhd|hdlr|minf|stsd|avc1|hev1|hvc1|mp4a|ac-3|ec-3|vp09|opus|ftyp|moof|mfra|soun|vide|meta)$/.test(f)))];
  return {
    audio: /hdlr[\s\S]{0,24}soun/.test(s) || fourcc.includes("mp4a") || fourcc.includes("ac-3") || fourcc.includes("ec-3") || fourcc.includes("opus"),
    video: fourcc.includes("vide") || fourcc.includes("avc1") || fourcc.includes("hev1") || fourcc.includes("hvc1") || fourcc.includes("vp09"),
    fourcc: fourcc.join(","),
  };
}

async function sniffSegment(url, referer, headers) {
  const r = await req(url, { referer, headers, range: "bytes=0-262143", timeoutMs: 25000 });
  if (r.status !== 200 && r.status !== 206) return { ok: false, reason: `segmento HTTP ${r.status}${r.err ? " " + r.err : ""}` };
  const ct = (r.headers?.get("content-type") || "").toLowerCase();
  const buf = r.buf;
  if (!buf.length) return { ok: false, reason: "segmento vacio" };
  if (buf.length < 128 && [...buf].every((b) => b >= 0x20 && b < 0x7f))
    return { ok: false, reason: `ASCII en vez de datos: ${buf.toString("utf8").slice(0, 60)}` };
  if (ct.startsWith("image/"))
    return { ok: false, reason: `segmento es una IMAGEN de anuncios (${ct}, host=${new URL(url).host}) -> playlist envenenada` };
  if (buf[0] === 0x47 || ct.includes("mpeg") || ct.includes("mp2t")) {
    const t = tsAudio(buf);
    return {
      ok: true,
      audio: t.audio,
      video: t.video,
      kind: "TS",
      detail: `PMT stream_types=${t.types.join(",") || "-"} PES stream_ids=${t.pes.join(",") || "-"}`,
    };
  }
  const m = mp4HasAudio(buf);
  return { ok: true, audio: m.audio, video: m.video, kind: ct.includes("mp4") || m.video ? "fMP4/MP4" : "desconocido", detail: `ct=${ct || "-"} boxes=${m.fourcc}` };
}

async function validateLink(link) {
  const headers = { "User-Agent": UA };
  const referer = link.referer || "";
  if (link.type === "M3U8" || /\.(m3u8|txt)(\?|$)/.test(link.url)) {
    const r = await req(link.url, { referer, headers, timeoutMs: 25000 });
    if (r.status !== 200) return { playable: false, audio: "n/a", reason: `playlist HTTP ${r.status}${r.err ? " " + r.err : ""}` };
    const body = r.text.trimStart();
    if (!body.startsWith("#EXTM3U")) {
      const marker = body.startsWith("<") ? body.slice(0, 40).replace(/\s+/g, " ") : body.slice(0, 40);
      const isVmap = /<vmap|<VMAP|<Ad |VAST|error|not found|no longer available/i.test(r.text);
      return { playable: false, audio: "n/a", reason: `no es #EXTM3U (${isVmap ? "VMAP/ad" : "XML/HTML"}: ${JSON.stringify(marker)})` };
    }
    const master = /#EXT-X-STREAM-INF/.test(r.text);
    let mediaUrl = link.url;
    let variantAudioHint = null;
    let audioRendition = null;
    if (master) {
      const lines = r.text.replace(/\r/g, "").split("\n").map((l) => l.trim());
      let chosen = null;
      let chosenAttrs = "";
      let best = -1;
      for (let i = 0; i < lines.length; i++) {
        if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
        const h = Number(/RESOLUTION=\d+x(\d+)/.exec(lines[i])?.[1] ?? 0);
        for (let j = i + 1; j < lines.length; j++) {
          if (!lines[j]) continue;
          if (lines[j].startsWith("#")) break;
          if (h > best) {
            best = h;
            chosen = lines[j];
            chosenAttrs = lines[i];
          }
          break;
        }
      }
      if (!chosen) return { playable: false, audio: "n/a", reason: "master sin variantes jugables" };
      mediaUrl = new URL(chosen, link.url).toString();
      variantAudioHint = /AUDIO="/.test(chosenAttrs);
      const cm = /CODECS="([^"]+)"/.exec(chosenAttrs);
      if (cm) audioRendition = AUDIO_CODECS.test(cm[1]) ? "muxed-codecs" : "codecs-sin-audio";
      const media = /#EXT-X-MEDIA:TYPE=AUDIO/.test(r.text);
      if (media) audioRendition = audioRendition === "muxed-codecs" ? audioRendition : "grupo-AUDIO-en-master";
    }
    const mr = await req(mediaUrl, { referer, headers, timeoutMs: 25000 });
    if (mr.status !== 200) return { playable: false, audio: audioRendition ?? "n/a", reason: `media playlist HTTP ${mr.status}` };
    if (!mr.text.trimStart().startsWith("#EXTM3U")) return { playable: false, audio: "n/a", reason: "media playlist no es #EXTM3U" };
    if (!/#EXTINF/.test(mr.text)) return { playable: false, audio: "n/a", reason: "playlist sin #EXTINF (sin segmentos)" };
    const seg = mr.text
      .replace(/\r/g, "")
      .split("\n")
      .map((l) => l.trim())
      .find((l) => l && !l.startsWith("#"));
    if (!seg) return { playable: false, audio: "n/a", reason: "sin URL de segmento" };
    const sniff = await sniffSegment(new URL(seg, mediaUrl).toString(), referer, headers);
    if (!sniff.ok) return { playable: false, audio: "n/a", reason: sniff.reason };
    let audio = sniff.audio ? "si (muxed en segmentos)" : null;
    if (!audio && /#EXT-X-MEDIA:TYPE=AUDIO/.test(mr.text)) audio = "si (rendition AUDIO en la playlist)";
    if (!audio && audioRendition === "grupo-AUDIO-en-master") audio = "solo si se usa el MASTER (grupo AUDIO aparte)";
    if (!audio && audioRendition === "muxed-codecs") audio = "si (CODECS con audio)";
    const standalone = master && link.url !== mediaUrl; // variante suelta emitida
    return {
      playable: true,
      audio: audio ?? "NO (video-only)",
      reason: `${master ? "master->" : ""}segmento ${sniff.kind} ${sniff.detail || ""}${variantAudioHint && !sniff.audio ? " | variante con AUDIO= aparte" : ""}`,
      videoOnlyRisk: !audio && (variantAudioHint || sniff.video),
    };
  }
  const r = await req(link.url, { referer, headers, range: "bytes=0-262143", timeoutMs: 30000 });
  const ct = (r.headers?.get("content-type") || "").toLowerCase();
  if (r.status !== 200 && r.status !== 206) return { playable: false, audio: "n/a", reason: `HTTP ${r.status}${r.err ? " " + r.err : ""}` };
  if (ct.startsWith("video/")) return { playable: true, audio: "n/a (progresivo)", reason: `200/206 ${ct}, ${r.buf.length} B` };
  if (ct.includes("text/html") || ct.startsWith("text/"))
    return { playable: false, audio: "n/a", reason: `Content-Type ${ct} (pagina de error/publicidad)` };
  const sniff = r.buf.length > 200 ? mp4HasAudio(r.buf) : { audio: false, video: false };
  const isFtyp = r.buf.slice(4, 8).toString("latin1") === "ftyp";
  if (isFtyp || (ct.includes("octet-stream") && r.buf.length > 10000))
    return { playable: true, audio: sniff.audio ? "si" : "sin comprobar", reason: `${ct || "octet-stream"} ftyp=${isFtyp}, ${r.buf.length} B` };
  return { playable: false, audio: "n/a", reason: `Content-Type ${ct || "?"}, ${r.buf.length} B` };
}

// ------------------------------------------------------------------ main
const eps = await discoverEpisodes();
console.log(`\n=== MundoDonghuaProvider: ${eps.length} episodios descubiertos (getMainPage -> load) ===`);
eps.forEach((e, i) => console.log(`  [${i + 1}] ${e.slug} EP ${e.ep} (${e.kind}) -> ${e.data}`));
if (eps.length < 5) console.log("  AVISO: descubrimiento <5 episodios (sitio cambió de markup).");

const rows = [];
for (const e of eps) {
  console.log(`\n--- ${e.slug} EP ${e.ep} (${e.kind}) ---`);
  const out = await loadLinks(e.data);
  console.log(`  pestañas del sitio: ${out.tabs ? out.tabs.join(",") : "-"}`);
  const grouped = new Map();
  for (const l of out.links) {
    const k = l.source;
    if (!grouped.has(k)) grouped.set(k, []);
    grouped.get(k).push(l);
  }
  for (const [host, ls] of grouped) {
    for (const l of ls)
      console.log(`  fuente ${host} | ${l.name} | ${l.type} | q=${l.quality} | ${l.url.slice(0, 118)}${l.url.length > 118 ? "..." : ""}`);
  }
  if (!out.links.length) console.log("  (ninguna fuente emitida)");
  if (out.subs.length) out.subs.forEach((s) => console.log(`  subtitulo: ${s.lang} ${String(s.url).slice(0, 90)}`));
  if (out.gates.length) out.gates.forEach((g) => console.log(`  gate: ${g}`));

  const uniqUrls = new Set();
  let dupes = 0;
  for (const l of out.links) {
    if (uniqUrls.has(l.url)) dupes++;
    uniqUrls.add(l.url);
  }
  const results = [];
  for (const l of out.links) {
    const v = await validateLink(l);
    results.push({ link: l, v });
    console.log(
      `    => ${v.playable ? "OK  " : "FAIL"} [${l.source}] ${l.type} ${v.audio && v.audio !== "n/a" ? "audio=" + v.audio : ""} :: ${v.reason} :: ${l.url.slice(0, 70)}`,
    );
  }
  const playable = results.filter((r) => r.v.playable && !String(r.v.audio).startsWith("NO"));
  const audioOk = results.filter((r) => r.v.playable && /si|muxed|progresivo|grupo-AUDIO/.test(String(r.v.audio)));
  // El veredicto se basa en HOSTS distintos con audio (master+variante del mismo
  // host cuentan como UNA sola opcion real para el usuario).
  const audioHosts = new Set(audioOk.map((r) => r.link.source));
  let verdict = "FAIL";
  if (audioHosts.size >= 2) verdict = "PASS";
  else if (audioHosts.size === 1) verdict = "PARTIAL";
  const reasons = [];
  if (!out.links.length) reasons.push("0 fuentes");
  const bad = results.filter((r) => !r.v.playable);
  if (bad.length) reasons.push(`${bad.length} fuentes rotas (${[...new Set(bad.map((b) => b.v.reason.split(" ")[0]))].join("; ")})`);
  const noAudio = results.filter((r) => r.v.playable && String(r.v.audio).startsWith("NO"));
  if (noAudio.length) reasons.push(`${noAudio.length} video-only (sin audio)`);
  if (dupes) reasons.push(`${dupes} fuentes duplicadas (misma URL)`);
  rows.push({
    ep: `${e.slug} EP${e.ep} (${e.kind})`,
    emitted: out.links.length,
    playable: `${audioHosts.size}/${audioOk.length}`,
    verdict,
    reason: reasons.join(" | ") || "todas las fuentes juegan con audio",
    hosts: [...new Set(out.links.map((l) => l.source))].join(","),
    audioHosts: [...audioHosts].join(","),
  });
}

console.log("\n================= TABLA FINAL MundoDonghua =================");
const w = [46, 9, 11, 8, 70];
const line = (c) => c.map((x, i) => String(x).padEnd(w[i])).join(" | ").trimEnd();
console.log(line(["episodio", "emitidas", "playables", "veredicto", "motivo"]));
console.log(line(["-".repeat(w[0]), "-".repeat(w[1]), "-".repeat(w[2]), "-".repeat(w[3]), "-".repeat(w[4])]));
for (const r of rows) console.log(line([r.ep.slice(0, 46), r.emitted, r.playable, r.verdict, r.reason.slice(0, 70)]));
console.log("\nfuentes con audio por episodio:");
for (const r of rows) console.log(`  ${r.ep} -> hosts=${r.audioHosts || "-"} | todos=${r.hosts}`);
