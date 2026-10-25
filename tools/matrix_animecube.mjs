#!/usr/bin/env node
/**
 * Live validation of AnimeCubeProvider.
 * Faithful Node port of:
 *   AnimeCubeProvider/src/main/kotlin/com/animecube/AnimeCubeProvider.kt
 *   common/src/CommonExtractors.kt (extractDailymotion / extractRumble)
 *
 * Steps (exactly as the Kotlin does):
 *   getMainPage -> slugs (home <a href="/anime/x"> + sitemap.xml)
 *   load        -> GET /anime/<slug>?_rsc=1&_t=<ms> (RSC:1) -> acParseEpisodes regex
 *   loadLinks   -> ^(.+)-(tab-..)-ep-(N)$ -> fetchRegistry -> fetchSources (X-Obf + AES-GCM)
 *                  -> dailymotion: CE.extractDailymotion
 *                  -> rumble:      CE.extractRumble (+ HLS fallback with generateM3u8)
 *   validate    -> HTTP GET with the same Referer/UA, #EXTM3U, first variant,
 *                  ad/VMAP detection, AUDIO group detection, Range check for VIDEO.
 *
 * Usage: node tools/matrix_animecube.mjs [slug1 slug2 ...]
 * No Kotlin file is modified.
 */
import crypto from 'node:crypto';
import https from 'node:https';
import http from 'node:http';
import { execFile } from 'node:child_process';

const BASE = 'https://animecube.live';
// companion object UA (AnimeCubeProvider.kt:214)
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const DM_REFERER = 'https://www.dailymotion.com/'; // CommonExtractors.kt:26
const QUAL = { Unknown: 0, P360: 360, P480: 480, P720: 720, P1080: 1080, P1440: 1440, P2160: 2160 };

const RAWARGS = process.argv.slice(2);
const args = RAWARGS.filter(a => !a.startsWith('--'));
const DEBUG = process.env.AC_DEBUG === '1' || !!process.env.AC_RAW;
const log = (...a) => console.log(...a);
const dbg = (...a) => { if (DEBUG) console.log('[dbg]', ...a); };

async function httpGet(url, { headers = {}, referer = null, method = 'GET', body = null, timeout = 25000 } = {}) {
  const h = { 'User-Agent': UA, ...headers };
  if (referer) h.Referer = referer;
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), timeout);
  try {
    const r = await fetch(url, { method, headers: h, body, redirect: 'follow', signal: ac.signal });
    const text = await r.text();
    const out = { status: r.status, text, headers: {} };
    r.headers.forEach((v, k) => { out.headers[k.toLowerCase()] = v; });
    return out;
  } finally {
    clearTimeout(t);
  }
}

/**
 * Player-like GET: node:https directly, exactly like ExoPlayer/OkHttp/curl.
 * global fetch (undici) adds sec-fetch-mode: cors / sec-fetch-site: cross-site,
 * which Dailymotion's cdndirector WAF answers with 403 -> false negatives.
 */
function rawGet(url, { headers = {}, timeout = 25000, maxRedirects = 5 } = {}) {
  return new Promise((resolve) => {
    const h = {
      'User-Agent': UA,
      Accept: '*/*',
      'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
      ...headers,
    };
    let u;
    try { u = new URL(url); } catch { resolve({ status: 0, headers: {}, text: 'BAD URL', bytes: Buffer.alloc(0) }); return; }
    const mod = u.protocol === 'http:' ? http : https;
    const req = mod.request(u, { method: 'GET', headers: h, timeout, servername: u.hostname }, (res) => {
      const loc = res.headers.location;
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && loc && maxRedirects > 0) {
        res.resume();
        rawGet(new URL(loc, u).toString(), { headers, timeout, maxRedirects: maxRedirects - 1 }).then(resolve);
        return;
      }
      const chunks = [];
      res.on('data', (c) => { chunks.push(c); if (Buffer.concat(chunks).length > 4_000_000) res.destroy(); });
      res.on('end', () => {
        const bytes = Buffer.concat(chunks);
        resolve({ status: res.statusCode, headers: res.headers, bytes, text: bytes.toString('utf8') });
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', (e) => resolve({ status: 0, headers: {}, text: 'ERR ' + e.message, bytes: Buffer.alloc(0) }));
    req.end();
  });
}

/**
 * Second transport: curl. Dailymotion's cdndirector answers Node's TLS/JA3
 * fingerprint with an empty 403 (curl/OkHttp/ExoPlayer get 200), so the plain
 * https client would report false negatives. Reported as [via curl] in the log.
 */
function curlGet(url, { headers = {}, timeout = 25 } = {}) {
  return new Promise((resolve) => {
    const hdrArgs = [];
    for (const [k, v] of Object.entries({ 'User-Agent': UA, Accept: '*/*', ...headers })) {
      if (v) hdrArgs.push('-H', `${k}: ${v}`);
    }
    execFile('curl', ['-sS', '-L', '--max-time', String(timeout), '-D', '-',
      '-w', `\n@@META@@%{http_code}@@%{content_type}@@%{size_download}`, url, ...hdrArgs],
    { maxBuffer: 8_000_000, windowsHide: true }, (err, stdout) => {
      if (err) { resolve({ status: 0, headers: {}, text: 'curl ERR ' + err.message, bytes: Buffer.alloc(0) }); return; }
      const idx = stdout.lastIndexOf('@@META@@');
      const meta = idx >= 0 ? stdout.slice(idx + 8) : '';
      const [code, ct] = meta.split('@@');
      let body = idx >= 0 ? stdout.slice(0, idx) : stdout;
      const headEnd = body.indexOf('\r\n\r\n') >= 0 ? body.indexOf('\r\n\r\n') : body.indexOf('\n\n');
      body = headEnd >= 0 ? body.slice(headEnd + (body[headEnd + 1] === '\r' ? 4 : 2)) : body;
      resolve({ status: Number(code) || 0, headers: { 'content-type': ct }, text: body, bytes: Buffer.from(body, 'latin1'), viaCurl: true });
    });
  });
}

// Player-equivalent GET: node https first, curl when the WAF rejects the TLS
// fingerprint (403/0 with an empty body).
async function playerGet(url, headers) {
  const a = await rawGet(url, { headers });
  if (a.status === 200) return a;
  const b = await curlGet(url, { headers });
  if (b.status === 200) return b;
  return a.status ? a : b;
}

/* ------------------------------------------------------------------ */
/* Kotlin helper ports                                                  */
/* ------------------------------------------------------------------ */

// acUnescJson (AnimeCubeProvider.kt:85) / unescJson (CommonExtractors.kt:35)
function acUnescJson(s) {
  let out = '';
  for (let i = 0; i < s.length;) {
    if (s[i] === '\\' && i + 1 < s.length) {
      const n = s[i + 1];
      if (n === 'u') {
        const hex = s.slice(i + 2, Math.min(i + 6, s.length));
        const code = /^[0-9a-fA-F]{1,4}$/.test(hex) ? parseInt(hex, 16) : null;
        if (code !== null && !Number.isNaN(code)) { out += String.fromCharCode(code); i += 6; }
        else { out += n; i += 2; }
      } else if (n === 'n') { out += '\n'; i += 2; }
      else if (n === 't') { out += '\t'; i += 2; }
      else if (n === 'r') { out += '\r'; i += 2; }
      else { out += n; i += 2; }
    } else { out += s[i]; i++; }
  }
  return out;
}

function acRandomHex() {
  return crypto.randomBytes(16).toString('hex');
}

// acDecrypt (AnimeCubeProvider.kt:123): IV = first 12 bytes, tag = last 16, key = SHA-256(material)
function acDecrypt(b64, keyMaterial) {
  try {
    const data = Buffer.from(b64, 'base64');
    if (data.length < 29) return null;
    const key = crypto.createHash('sha256').update(Buffer.from(keyMaterial, 'utf8')).digest();
    const iv = data.subarray(0, 12);
    const tag = data.subarray(data.length - 16);
    const ct = data.subarray(12, data.length - 16);
    const d = crypto.createDecipheriv('aes-256-gcm', key, iv);
    d.setAuthTag(tag);
    return Buffer.concat([d.update(ct), d.final()]).toString('utf8');
  } catch { return null; }
}

// acCatalog (AnimeCubeProvider.kt:158) — used for the For-You count / genre filter
function acCatalog(html) {
  const joined = html.replace('"]</script><script>self.__next_f.push([1,"', '');
  const un = joined.replaceAll('\\"', '"').replaceAll('\\u0026', '&');
  const merged = new Map();
  const metaRe = /"coverImage":"([^"]+)"[^}]*?"slug":"([a-z0-9-]+)"[^}]*?"title":"((?:[^"\\]|\\.)*)"/g;
  for (const m of un.matchAll(metaRe)) {
    const [, poster, slug, title] = m;
    merged.set(slug, { slug, title: acUnescJson(title) || acTitleFromSlug(slug), poster, genres: [], rating: null });
  }
  const genreRe = /"genres":\[([^\]]*)\][^}]*?"slug":"([a-z0-9-]+)"/g;
  for (const m of un.matchAll(genreRe)) {
    const genres = m[1].split(',').map(x => x.trim().replace(/^"|"$/g, '')).filter(Boolean);
    const prev = merged.get(m[2]);
    merged.set(m[2], prev ? { ...prev, genres } : { slug: m[2], title: acTitleFromSlug(m[2]), poster: '', genres, rating: null });
  }
  return [...merged.values()];
}

function acTitleFromSlug(slug) {
  return slug.replace(/-/g, ' ').replace(/\b[a-z]/g, c => c.toUpperCase());
}

// acParseEpisodes (AnimeCubeProvider.kt:353) — v7 pattern
function acParseEpisodes(flight, slug) {
  const seasons = new Map(); // seId -> {id, title, episodes}
  const epRe = /"id":"([^"]+)-(tab-[\w-]+)-ep-(\d+(?:-\d+)?)"?,"number":(\d+),"numberDisplay":"([^"]*)","title":"([^"]*)"/g;
  for (const em of flight.matchAll(epRe)) {
    const seId = em[2];
    if (em[1] !== slug) continue;
    const id = `${em[1]}-${seId}-ep-${em[3]}`;
    if (!seasons.has(seId)) seasons.set(seId, { id: seId, title: '', episodes: [] });
    seasons.get(seId).episodes.push({ id, number: parseInt(em[4], 10) || 0, numberDisplay: acUnescJson(em[5]), title: acUnescJson(em[6]) });
  }
  const seRe = /"id":"(tab-[\w-]+)","number":\d+,"title":"((?:[^"\\]|\\.)*)"/g;
  for (const sm of flight.matchAll(seRe)) {
    const s = seasons.get(sm[1]);
    if (s && !s.title) s.title = acUnescJson(sm[2]);
  }
  return [...seasons.values()];
}

/* ------------------------------------------------------------------ */
/* discovery: getMainPage                                               */
/* ------------------------------------------------------------------ */

async function discoverSlugs() {
  const html = (await httpGet(`${BASE}/`, { headers: {} })).text;
  const seen = new Set();
  const order = [];
  const re = /<a[^>]+href="\/anime\/([a-z0-9-]+)[^"]*/gi; // AnimeCubeProvider.kt:278
  for (const m of html.matchAll(re)) if (!seen.has(m[1])) { seen.add(m[1]); order.push(m[1]); }
  const homeCount = order.length;
  let smCount = 0;
  try {
    const sm = (await httpGet(`${BASE}/sitemap.xml`)).text; // :293
    const re2 = /<loc>https?:\/\/animecube\.live\/anime\/([a-z0-9-]+)<\/loc>/g;
    for (const m of sm.matchAll(re2)) if (!seen.has(m[1])) { seen.add(m[1]); order.push(m[1]); smCount++; }
  } catch { /* Kotlin swallows it too */ }
  return { order, homeCount, smCount, catalog: acCatalog(html) };
}

/* ------------------------------------------------------------------ */
/* load(): episode list                                                 */
/* ------------------------------------------------------------------ */

async function loadSeries(slug) {
  const ts = Date.now();
  const noCache = { 'Cache-Control': 'no-cache', Pragma: 'no-cache' };
  const flight = (await httpGet(`${BASE}/anime/${slug}?_rsc=1&_t=${ts}`, { headers: { ...noCache, RSC: '1' } })).text;
  const html = (await httpGet(`${BASE}/anime/${slug}?_t=${ts}`, { headers: noCache })).text;
  const htmlFlight = html.replaceAll('\\"', '"');
  const bySeason = new Map();
  for (const s of [...acParseEpisodes(flight, slug), ...acParseEpisodes(htmlFlight, slug)]) {
    const prev = bySeason.get(s.id);
    if (!prev) bySeason.set(s.id, s);
    else {
      const eps = [];
      const seenId = new Set();
      for (const e of [...prev.episodes, ...s.episodes]) if (!seenId.has(e.id)) { seenId.add(e.id); eps.push(e); }
      bySeason.set(s.id, { ...prev, episodes: eps });
    }
  }
  const seasons = [...bySeason.values()];
  const episodes = [];
  const seen = new Set(), seenNums = new Set();
  for (const season of seasons) {
    const isPseudo = !season.title.trim() && season.episodes.length <= 1; // :512
    for (const ep of season.episodes) {
      if (seen.has(ep.id)) continue; seen.add(ep.id);
      if (isPseudo && seenNums.has(ep.number)) continue;
      seenNums.add(ep.number);
      episodes.push({ ...ep, seasonTitle: season.title });
    }
  }
  episodes.sort((a, b) => (a.number || 0) - (b.number || 0));
  const og = /<meta property="og:title" content="([^"]*)"/.exec(html);
  return {
    slug,
    title: og ? og[1].replace(' - Watch Online | Anime Cube', '').trim() : acTitleFromSlug(slug),
    seasons, episodes,
  };
}

/* ------------------------------------------------------------------ */
/* registry + sources                                                   */
/* ------------------------------------------------------------------ */

function parseRegistryPlain(raw) {
  const out = [];
  try {
    const obj = JSON.parse(raw);
    const bySeason = obj.bySeason;
    if (!bySeason) return out;
    for (const slug of Object.keys(bySeason)) {
      const perSlug = bySeason[slug] || {};
      const keys = Object.keys(perSlug);
      const hasPrimary = keys.some(k => k.startsWith('primary-'));
      if (hasPrimary) {
        for (const pt of keys) {
          const perPt = perSlug[pt] || {};
          for (const se of Object.keys(perPt)) {
            const ver = perPt[se];
            if (se.startsWith('tab-') && ver) out.push({ slug, primaryTabId: pt, seasonId: se, version: ver });
          }
        }
      } else {
        for (const se of keys) {
          const ver = perSlug[se];
          if (se.startsWith('tab-') && ver) out.push({ slug, primaryTabId: 'primary-1', seasonId: se, version: ver });
        }
      }
    }
  } catch { return []; }
  return out;
}

async function fetchRegistry() {
  const baseHeaders = { Accept: 'application/json', 'Cache-Control': 'no-cache', Pragma: 'no-cache' };
  const u = `${BASE}/api/anime-sources-versions?_t=${Date.now()}`;
  const r = await httpGet(u, { headers: baseHeaders });
  if (r.text.includes('"bySeason"')) return { entries: parseRegistryPlain(r.text), mode: 'plain' };
  const obf = acRandomHex();
  const resp = await httpGet(u, { headers: { ...baseHeaders, 'X-Obf': obf } });
  const etag = resp.headers['x-registry-etag'];
  const d = resp.text.split('"d":"')[1]?.split('"')[0] ?? '';
  if (!d) return { entries: [], mode: 'encrypted-no-d' };
  const key = !etag ? obf : `${obf}|${etag}`;
  const plain = acDecrypt(d, key) ?? acDecrypt(d, obf);
  return { entries: plain ? parseRegistryPlain(plain) : [], mode: plain ? 'encrypted-decrypted' : 'encrypted-FAILED' };
}

function parseSourcesPayload(payload) {
  try {
    const obj = JSON.parse(payload);
    const arr = obj.sources;
    if (!Array.isArray(arr)) return [];
    const out = [];
    const seen = new Set();
    for (const o of arr) {
      if (!o || typeof o !== 'object') continue;
      const platform = String(o.platform ?? '').trim();
      const id = String(o.privateId ?? '').trim() || String(o.videoId ?? '').trim();
      if (!platform || !id) continue;
      const k = `${platform}|${id}`;
      if (seen.has(k)) continue;
      seen.add(k);
      out.push({ platform, videoId: id, quality: String(o.quality ?? '').trim(), raw: o });
    }
    return out;
  } catch { return []; }
}

async function fetchSources(slug, epId, ptId, seId, version) {
  const obf = acRandomHex();
  const u = `${BASE}/api/anime/${slug}/episode/${epId}/sources`
    + `?v=${encodeURIComponent(version)}`
    + `&primaryTabId=${encodeURIComponent(ptId)}`
    + `&seasonId=${encodeURIComponent(seId)}`
    + `&_t=${Date.now()}`;
  const baseHeaders = { Accept: 'application/json', 'Cache-Control': 'no-cache', Pragma: 'no-cache' };
  const r = await httpGet(u, { headers: { ...baseHeaders, 'X-Obf': obf } });
  let payload = r.text.includes('"sources"') ? r.text : '';
  let decryptedWith = r.text.includes('"sources"') ? 'plain' : '';
  if (process.env.AC_RAW) dbg(`RAW /sources ${r.status}: ${r.text.slice(0, 400)}\n  obf=${obf}`);
  if (!payload) {
    const d = r.text.split('"d":"')[1]?.split('"')[0] ?? '';
    if (d) {
      for (const km of [`${obf}|${version}`, obf, version, `${version}|${obf}`]) {
        const p = acDecrypt(d, km);
        if (p) { payload = p; decryptedWith = km.includes('|') ? 'gcm(obf|v)' : 'gcm(obf|v-only)'; break; }
      }
      if (payload && process.env.AC_RAW) dbg(`DECRYPTED /sources: ${payload.slice(0, 3000)}`);
    }
    dbg(`  sources http=${r.status} len=${r.text.length} obf=${obf.slice(0, 6)} decrypted=${!!payload}`);
  }
  let list = payload ? parseSourcesPayload(payload) : [];
  if (list.length === 0) {
    const r2 = await httpGet(u, { headers: baseHeaders });
    list = parseSourcesPayload(r2.text);
    if (list.length) decryptedWith = 'fallback-no-X-Obf';
  }
  return { list, via: decryptedWith, status: r.status };
}

/* ------------------------------------------------------------------ */
/* CommonExtractors.extractDailymotion (CommonExtractors.kt:160)        */
/* ------------------------------------------------------------------ */

function dmVideoId(src) {
  const m = /(?:geo\.dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|dailymotion\.com\/embed\/video\/)([A-Za-z0-9]+)/i.exec(src);
  return m ? m[1] : null;
}

function balancedObjectFrom(json, openIdx) {
  let depth = 0, inStr = false, esc = false;
  for (let i = openIdx; i < json.length; i++) {
    const c = json[i];
    if (esc) { esc = false; continue; }
    if (c === '\\' && inStr) esc = true;
    else if (c === '"') inStr = !inStr;
    else if (!inStr && c === '{') depth++;
    else if (!inStr && c === '}') { depth--; if (depth === 0) return json.slice(openIdx, i + 1); }
  }
  return null;
}

function dmSubtitlesBlock(json) {
  const start = json.indexOf('"subtitles"');
  if (start < 0) return null;
  const dataIdx = json.indexOf('"data"', start);
  if (dataIdx < 0) return null;
  const open = json.indexOf('{', dataIdx);
  if (open < 0) return null;
  return balancedObjectFrom(json, open);
}

function dmSubLangName(code) {
  if (code.startsWith('es')) return 'Spanish';
  if (code.startsWith('en')) return code.includes('auto') ? 'English (auto)' : 'English';
  const map = { fr: 'French', pt: 'Portuguese', ru: 'Russian', tr: 'Turkish', pl: 'Polish', ar: 'Arabic', bn: 'Bengali', fa: 'Persian', id: 'Indonesian', ms: 'Malay', it: 'Italian', de: 'German', hi: 'Hindi', th: 'Thai', vi: 'Vietnamese' };
  return map[code.slice(0, 2)] || code;
}

async function extractDailymotion(iframeSrc, embedderReferer, linkName, emittedSubs, emittedVids, subs, links, session) {
  const vid = dmVideoId(iframeSrc) || (/^[A-Za-z0-9]+$/.test(iframeSrc.trim()) ? iframeSrc.trim() : null);
  if (!vid) return false;
  if (emittedVids.has(vid)) return true; // dedupe (CommonExtractors.kt:174)
  const referer = embedderReferer || 'https://animexin.dev';
  try {
    const metaUrl = `https://www.dailymotion.com/player/metadata/video/${vid}`
      + `?embedder=${encodeURIComponent(referer)}&integration=inline&app=com.dailymotion.neon`;
    const r = await httpGet(metaUrl, { referer, headers: { 'User-Agent': UA } });
    if (r.status !== 200) log(`    DM ${vid} metadata HTTP ${r.status} :: ${r.text.slice(0, 200).replace(/\s+/g, ' ')}`);
    // cookies that OkHttp's cookie jar would keep for the player session
    session.cookies = ((r.headers['set-cookie'] || '').split(/,(?=[^;]+?=)/).map(s => s.split(';')[0].trim()).join('; '));
    const json = r.text.replaceAll('\\/', '/').replaceAll('\\u0026', '&'); // :181

    // subs (:103)
    const block = dmSubtitlesBlock(json);
    if (block) {
      const subRe = /"(es[a-z-]*|en[a-z-]*|fr|pt|ru|tr|pl|ar|bn|fa|id|ms|it|de|hi|th|vi)":\s*\{[^{}]*?"urls":\["([^"]+)"/g;
      const rows = [];
      for (const m of block.matchAll(subRe)) rows.push([m[1], acUnescJson(m[2])]);
      const uniq = [];
      const seenUrl = new Set();
      for (const [code, url] of rows) { if (!seenUrl.has(url)) { seenUrl.add(url); uniq.push([code, url]); } }
      uniq.sort((a, b) => (a[0].startsWith('es') ? 0 : a[0].startsWith('en') ? 1 : 2) - (b[0].startsWith('es') ? 0 : b[0].startsWith('en') ? 1 : 2));
      for (const [code, url] of uniq) if (!emittedSubs.has(url)) { emittedSubs.add(url); subs.push({ lang: dmSubLangName(code), url }); }
    }

    let found = false;
    const emittedUrls = new Set();
    const emit = (u, hls, q) => {
      if (!u || !u.startsWith('http')) return;
      if (emittedUrls.has(u)) return;
      emittedUrls.add(u);
      // FIX v9: CS3 muestra la calidad aparte; el nombre ya no lleva sufijo.
      links.push({ source: linkName, name: linkName, url: u, type: hls ? 'M3U8' : 'VIDEO', quality: q ?? QUAL.Unknown, referer: DM_REFERER, platform: 'dailymotion', vid });
      found = true;
    };
    // FIX v9 (audio): refleja CommonExtractors.tryPlaylist — expande variantes
    // del master salvo las que declaran AUDIO="grupo" externo o CODECS sin
    // audio (standalone = video sin audio). El master Auto siempre se emite.
    const tryPlaylist = async (u, hlsQ) => {
      if (!u || !u.startsWith('http')) return false;
      if (/dmxleo/i.test(u) || /advertising/i.test(u) || u.includes('auth=')) return false;
      const masterResp = await httpGet(u, { referer: DM_REFERER, headers: { 'User-Agent': UA } });
      const master = masterResp.text;
      if (!master.trimStart().startsWith('#EXTM3U')) { emit(u, true, hlsQ); return true; }
      const audioCodecRe = /mp4a|aac|ac-3|eac-3|ec-3|opus|flac|mp3/i;
      for (const m of master.matchAll(/#EXT-X-STREAM-INF:([^\n]*)\n([^\n]+)/g)) {
        const attrs = m[1], variant = m[2].trim();
        const codecs = (/CODECS="([^"]+)"/.exec(attrs)?.[1]) || '';
        if (/av01|hev1|hvc1/i.test(codecs)) continue;
        if (/\bAUDIO=/.test(attrs)) continue;
        if (codecs && !audioCodecRe.test(codecs)) continue;
        const h = /RESOLUTION=\d+x(\d+)/.exec(attrs)?.[1];
        const q = h == null ? (hlsQ ?? QUAL.Unknown)
          : h >= 2160 ? QUAL.P2160 : h >= 1440 ? QUAL.P1440 : h >= 1080 ? QUAL.P1080
          : h >= 720 ? QUAL.P720 : h >= 480 ? QUAL.P480 : h >= 360 ? QUAL.P360 : QUAL.Unknown;
        const origin = /^https?:\/\/[^/]+/.exec(u)?.[0] || u;
        const abs = variant.startsWith('http') ? variant : variant.startsWith('/') ? origin + variant : `${origin}/${variant}`;
        emit(abs, true, q);
      }
      emit(u, true, hlsQ); // Auto
      return true;
    };

    // 1) qualities.auto master (:242)
    let emittedAny = false;
    for (const m of json.matchAll(/"auto":\[\{"url":"(https:\/\/[^"]+)"/g)) {
      if (emittedAny) break;
      if (m[1].includes('dailymotion.com')) emittedAny = await tryPlaylist(acUnescJson(m[1]), QUAL.Unknown);
    }
    if (!emittedAny) {
      for (const m of json.matchAll(/"auto":\[\{[^}]*?"type":"[^"]+","url":"(https:\/\/[^"]+)"/g)) {
        if (emittedAny) break;
        if (m[1].includes('dailymotion.com')) emittedAny = await tryPlaylist(acUnescJson(m[1]), QUAL.Unknown);
      }
    }
    // 2) muxed vCRN presets (:260)
    if (!emittedAny) {
      const masterAuto = /https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i.exec(json)?.[0];
      const dc = masterAuto ? (/https:\/\/([a-z0-9.-]+)\.dailymotion\.com/i.exec(masterAuto)?.[1] || 'dmxleo') : null;
      const vid2 = masterAuto ? /video\/([a-z0-9]+)\.m3u8/i.exec(masterAuto)?.[1] : null;
      if (dc && vid2 && !dc.startsWith('cdndirector')) {
        for (const preset of ['H264-1280x720', 'H264-1920x1080', 'H264-854x480']) {
          if (await tryPlaylist(`https://${dc}.dailymotion.com/cdn/${preset}/vCRN/mp4_h264_aac.m3u8?video=${vid2}`, QUAL.P720)) { emittedAny = true; break; }
        }
      }
    }
    // 3) other dmcdn masters (:276)
    if (!emittedAny) {
      for (const m of json.matchAll(/https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"']*/g)) {
        const u = m[0];
        if (/\/av1/i.test(u) || /_av1_/i.test(u)) continue;
        if (await tryPlaylist(u, null)) { emittedAny = true; break; }
      }
    }
    if (emittedAny) { emittedVids.add(vid); return found; }

    // 4) progressives (:290)
    for (const m of json.matchAll(/"(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"/g)) {
      const h = parseInt(m[1], 10);
      const url = acUnescJson(m[3]);
      const q = h >= 1080 ? QUAL.P1080 : h >= 720 ? QUAL.P720 : h >= 480 ? QUAL.P480 : h >= 360 ? QUAL.P360 : QUAL.Unknown;
      if (/\/av1/i.test(url) || /_av1_/i.test(url)) continue;
      emit(url, url.includes('.m3u8'), q);
    }
    if (!found) {
      const cand = /auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json)?.[1]
        ? acUnescJson(/auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json)[1])
        : (/https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i.exec(json)?.[0] ?? null);
      if (cand && !/dmxleo/i.test(cand) && !/advertising/i.test(cand) && !cand.includes('auth=')) {
        emit(cand, true, QUAL.Unknown);
      }
    }
    if (found) emittedVids.add(vid);
    if (!found && process.env.AC_RAW) dbg(`    DM ${vid} metadata diag: status=${r.status} len=${json.length} auto=${(json.match(/"auto":\[\{"url":"[^"]{0,90}/g) || []).join(' | ')} cdn=${(json.match(/https:\/\/[^"]{0,80}\.m3u8/g) || []).slice(0, 3).join(' | ')}`);
    return found;
  } catch (e) { dbg('DM extract error', e.message); return false; }
}

/* ------------------------------------------------------------------ */
/* CommonExtractors.extractRumble (CommonExtractors.kt:341) + provider  */
/* fallback (AnimeCubeProvider.kt:917)                                  */
/* ------------------------------------------------------------------ */

async function rumbleEmit(json, linkName, links) {
  const fixed = json.replaceAll('\\/', '/'); // :354
  let n = 0;
  const re = /"(\d{3,4})":\["(https:\/\/[a-z0-9.-]*rumble\.cloud\/video\/[A-Za-z0-9/._-]+\.mp4)"/g;
  for (const m of fixed.matchAll(re)) {
    const h = parseInt(m[1], 10);
    links.push({
      source: linkName, name: linkName, url: m[2], type: 'VIDEO',
      quality: Number.isNaN(h) ? QUAL.Unknown : h >= 1080 ? QUAL.P1080 : h >= 720 ? QUAL.P720 : h >= 480 ? QUAL.P480 : QUAL.P360,
      referer: 'https://rumble.com/', platform: 'rumble',
    });
    n++;
  }
  return n;
}

async function extractRumble(vid, linkName, links) {
  const embed = `https://rumble.com/embed/${vid}/`;
  // AC_RUMBLE_UA: diagnostic override. Rumble's WAF 403s browser UAs and only
  // answers embedJS to non-browser ones (okhttp/4.x, com.android.vending).
  const RUA = process.env.AC_RUMBLE_UA || UA;
  const api = `https://rumble.com/embedJS/u3/?request=video&v=${vid}&embed=${encodeURIComponent(embed)}`;
  // FIX: curlGet directo — Rumble puso su embedJS detras de Cloudflare y
  // responde HTTP 200 con pagina "Just a moment" a Node/curl-unsupported UAs,
  // por lo que playerGet no detecta el fallo (status 200). curl pasa siempre.
  let json = '';
  try { json = (await curlGet(api, { headers: { 'User-Agent': RUA, Referer: 'https://rumble.com/' } })).text; } catch { json = ''; }
  if (DEBUG) dbg(`    rumble embedJS #1 (ua=${RUA.slice(0, 24)}): ${json.slice(0, 200).replace(/\s+/g, ' ')}`);
  let count = json.trim() ? await rumbleEmit(json, linkName, links) : 0;
  if (count === 0) {
    try { await curlGet(embed, { headers: { 'User-Agent': RUA, Referer: `${BASE}/` } }); } catch {}
    try { json = (await curlGet(api, { headers: { 'User-Agent': RUA, Referer: embed } })).text; } catch { json = ''; }
    if (DEBUG) dbg(`    rumble embedJS #2: ${json.slice(0, 200).replace(/\s+/g, ' ')}`);
    if (json.trim()) count = await rumbleEmit(json, linkName, links);
  }
  if (count > 0) return true;
  // provider fallback HLS (AnimeCubeProvider.kt:926)
  try {
    const r = await curlGet(api, { headers: { 'User-Agent': UA, Referer: embed } });
    const fixed = r.text.replaceAll('\\/', '/');
    if (DEBUG) dbg(`    rumble fallback HLS: status=${r.status} hls=${/"hls":\{"url":"[^"]+"/.exec(fixed)?.[0] || 'NONE'} keys=${Object.keys(JSON.parse(fixed) || {}).join(',')}`.slice(0, 400));
    const m = /"hls":\{"url":"(https:\/\/rumble\.com\/hls-vod\/[^"]+)"/.exec(fixed);
    if (m) {
      const subs = [];
      await generateM3u8(linkName, m[1], embed, links, subs); // CloudStream generateM3u8
      return true;
    }
  } catch {}
  return false;
}

// Approximation of CloudStream M3u8Helper.generateM3u8: reads the master and
// emits one M3U8 link per #EXT-X-STREAM-INF (or a single link for a media playlist).
async function generateM3u8(serverName, masterUrl, referer, links, subs) {
  const r = await playerGet(masterUrl, { headers: { 'User-Agent': UA, Referer: referer } });
  const text = r.text;
  if (!text.trimStart().startsWith('#EXTM3U')) throw new Error('not a playlist');
  const base = new URL(masterUrl);
  if (/#EXT-X-STREAM-INF/i.test(text)) {
    const lines = text.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      if (!/#EXT-X-STREAM-INF/i.test(lines[i])) continue;
      const attr = lines[i];
      const res = /RESOLUTION=(\d+)x(\d+)/.exec(attr);
      const bw = /BANDWIDTH=(\d+)/.exec(attr);
      let u = (lines[i + 1] || '').trim();
      if (!u) continue;
      if (!/^https?:/.test(u)) u = new URL(u, base).toString();
      const q = res ? (Number(res[1]) >= 1900 || Number(res[2]) >= 1080 ? QUAL.P1080 : Number(res[2]) >= 720 ? QUAL.P720 : Number(res[2]) >= 480 ? QUAL.P480 : QUAL.P360)
        : (bw ? QUAL.Unknown : QUAL.Unknown);
      links.push({ source: serverName, name: `${serverName} ${res ? res[2] + 'p' : ''}`.trim(), url: u, type: 'M3U8', quality: q, referer, platform: 'generateM3u8' });
    }
  } else {
    links.push({ source: serverName, name: serverName, url: masterUrl, type: 'M3U8', quality: QUAL.Unknown, referer, platform: 'generateM3u8' });
  }
}

/* ------------------------------------------------------------------ */
/* loadLinks (AnimeCubeProvider.kt:796)                                 */
/* ------------------------------------------------------------------ */

const emittedDmVids = new Set(); // private val emittedDmVids (:911) - instance field

async function loadLinks(data, registry) {
  // La app pasa el dato por fixUrl(): siempre llega como URL absoluta del mainUrl.
  const m = /^(?:https?:\/\/[^\/]+\/)?(.+)-(tab-[\w-]+)-ep-(\d+(?:-\d+)?)$/.exec(data);
  if (!m) return { ok: false, reason: 'episode id no match', links: [], subs: [], skipped: [] };
  const slug = m[1];
  const seId = m[2];
  // Id desnudo para /sources (el provider reconstruye tras el parse).
  const epId = `${slug}-${seId}-ep-${m[3]}`;
  emittedDmVids.clear(); // v8 (:809)
  const entries = registry.filter(e => e.slug === slug).sort((a, b) => (a.seasonId === seId ? 0 : 1) - (b.seasonId === seId ? 0 : 1));
  if (!entries.length) return { ok: false, reason: 'registry sin entradas', links: [], subs: [], skipped: [] };
  let sources = [], used = null, via = '';
  for (const entry of entries.slice(0, 3)) {
    const res = await fetchSources(slug, epId, entry.primaryTabId, entry.seasonId, entry.version);
    via = res.via;
    if (res.list.length) { sources = res.list; used = entry; break; }
  }
  if (!sources.length) return { ok: false, reason: 'fuentes vacias', links: [], subs: [], skipped: [] };
  const links = [], subs = [], skipped = [];
  const emittedSubs = new Set();
  const session = {};
  let found = false;
  const desc = sources.map(s => `${s.platform}:${s.videoId}${s.quality ? `(${s.quality})` : ''}`);
  log(`     /sources -> ${sources.length} [${desc.join(', ')}] via=${via}`);
  for (const s of sources) {
    if (s.platform === 'dailymotion') {
      const name = `Dailymotion${s.quality ? ` (${s.quality})` : ''}`;
      const ok = await extractDailymotion(`https://www.dailymotion.com/embed/video/${s.videoId}`, `${BASE}/`, name, emittedSubs, emittedDmVids, subs, links, session);
      for (const l of links) if (l.platform === 'dailymotion' && !l._cookies) l._cookies = session.cookies;
      if (ok) found = true; else dbg(`  DM ${s.videoId} q=${s.quality} ok=false`);
    } else if (s.platform === 'rumble') {
      const ok = await extractRumble(s.videoId, 'Rumble', links);
      if (ok) found = true; else dbg(`  Rumble ${s.videoId} ok=false`);
    } else { skipped.push(`${s.platform}:${s.videoId}`); }
  }
  return { ok: found, links, subs, skipped, used, via, nSources: sources.length };
}

/* ------------------------------------------------------------------ */
/* validation (what a player/ExoPlayer does)                            */
/* ------------------------------------------------------------------ */

const AUDIO_CODEC_RE = /(mp4a|aac|ac-3|eac-3|ec-3|opus|flac|mp3)/i;

async function validateLink(link) {
  const res = { playable: false, audio: 'n/a', detail: '' };
  const headers = { 'User-Agent': UA };
  if (link.referer) headers.Referer = link.referer;
  try {
    if (link.type === 'M3U8') {
      let r = await playerGet(link.url, { headers });
      const asPlayer = `as-player(ref=${link.referer || '-'},UA,no-cookies)`;
      let via = asPlayer;
      if (r.status !== 200) {
        // diagnostic: what does the signed manifest actually need?
        const combos = [
          ['ref=DM+cookies', { 'User-Agent': UA, Referer: DM_REFERER, Cookie: link._cookies || '' }],
          ['ref=DM+v1st-from-url', { 'User-Agent': UA, Referer: DM_REFERER, Cookie: `v1st=${new URL(link.url).searchParams.get('dmV1st') || ''}` }],
          ['ref=site+v1st-from-url', { 'User-Agent': UA, Referer: `${BASE}/`, Cookie: `v1st=${new URL(link.url).searchParams.get('dmV1st') || ''}` }],
          ['ref=DM', { 'User-Agent': UA, Referer: DM_REFERER }],
          ['ref=site+cookies', { 'User-Agent': UA, Referer: `${BASE}/`, Cookie: link._cookies || '' }],
          ['no-ref', { 'User-Agent': UA }],
        ];
        const tried = [`first:${r.status}`];
        for (const [name, h] of combos) {
          const rr = await playerGet(link.url, { headers: h });
          tried.push(`${name}:${rr.status}`);
          if (rr.status === 200) { r = rr; via = name; break; }
        }
        if (r.status !== 200) { res.detail = `master HTTP ${r.status} [${tried.join(' ')}] body=${r.text.slice(0, 80).replace(/\s+/g, ' ')} url=${link.url.slice(0, 260)}`; return res; }
        res.via = via;
      }
      res.via = via;
      const vHeaders = { ...headers };
      if (via.includes('cookies') && link._cookies) vHeaders.Cookie = link._cookies;
      const body = r.text;
      const ct = r.headers['content-type'] || '';
      if (/vmap|<VMAP|adpilot|\/ad\?|beacon/i.test(body.slice(0, 400)) || (ct.includes('xml') && !body.trimStart().startsWith('#EXTM3U'))) {
        res.detail = 'AD/VMAP xml (ExoPlayer ERROR_CODE_PARSING_MANIFEST_MALFORMED)'; return res;
      }
      if (!body.trimStart().startsWith('#EXTM3U')) { res.detail = `no #EXTM3U (ct=${ct}, head=${body.slice(0, 40).replace(/\n/g, ' ')})`; return res; }
      const hasAudioGroup = /#EXT-X-MEDIA:TYPE=AUDIO/i.test(body);
      // audit every rendition: ExoPlayer picks by BANDWIDTH, so an AV1/H265-only
      // rendition is what a real device may end up decoding
      const renditions = [...body.matchAll(/#EXT-X-STREAM-INF:([^\r\n]*)/g)].map(m => ({
        bw: Number(/BANDWIDTH=(\d+)/.exec(m[1])?.[1] || 0),
        res: /RESOLUTION=(\d+x\d+)/.exec(m[1])?.[1] || '-',
        codecs: /CODECS="([^"]+)"/.exec(m[1])?.[1] || '',
      }));
      const av1 = renditions.filter(r2 => /av01|dvh1|hev1|hvc1/.test(r2.codecs));
      const topBw = renditions.slice().sort((a, b) => b.bw - a.bw)[0];
      const audioRenditions = renditions.filter(r2 => /mp4a|ac-3|opus/.test(r2.codecs));
      if (/#EXT-X-STREAM-INF/i.test(body)) {
        const variantMatch = body.split(/\r?\n/).findIndex(l => /#EXT-X-STREAM-INF/i.test(l));
        const attrLine = body.split(/\r?\n/)[variantMatch];
        let vurl = body.split(/\r?\n/).slice(variantMatch + 1).find(l => l.trim() && !l.startsWith('#'))?.trim();
        if (!vurl) { res.detail = 'master sin variante'; return res; }
        if (!/^https?:/.test(vurl)) vurl = new URL(vurl, new URL(link.url)).toString();
        const codecs = /CODECS="([^"]+)"/.exec(attrLine)?.[1] || '';
        const vr = await playerGet(vurl, { headers: vHeaders });
        const vbody = vr.text;
        if (vr.status !== 200 || !vbody.trimStart().startsWith('#EXTM3U')) { res.detail = `variante HTTP ${vr.status}/no-EXTM3U`; return res; }
        const seg = /#EXTINF/.test(vbody);
        if (!seg) { res.detail = 'variante sin segmentos #EXTINF'; return res; }
        const resl = /RESOLUTION=(\d+x\d+)/.exec(attrLine)?.[1] || '-';
        // nested fmp4 Rendition audio track -> prove audio playlist exists
        let audioTrack = '';
        const am = /#EXT-X-MEDIA:TYPE=AUDIO[^"\n]*(?:"([^"]*)")?[^\n]*\n([^\n#][^\n]*)/.exec(vbody) || /#EXT-X-MEDIA:TYPE=AUDIO[^\n]*URI="([^"]+)"/.exec(vbody);
        if (am) {
          const u = (am[2] || am[1]).trim();
          const au = /^https?:/.test(u) ? u : new URL(u, vurl).toString();
          const ar = await playerGet(au, { headers: vHeaders });
          audioTrack = ` audioTrack HTTP ${ar.status}${/#EXTINF/.test(ar.text) ? ' +segs' : ' NOsegs'}`;
        }
        res.playable = true;
        if (hasAudioGroup || AUDIO_CODEC_RE.test(codecs) || /#EXT-X-MEDIA:TYPE=AUDIO/i.test(vbody)) res.audio = 'yes';
        else if (codecs && !AUDIO_CODEC_RE.test(codecs)) res.audio = 'NO(video-only)';
        else res.audio = 'unknown(muxed?)';
        res.detail = `master(${renditions.length}v, first ${resl} codecs=${codecs || '-'}, topBw=${topBw?.res || '-'}/${topBw?.codecs || '-'}, av1/hevc=${av1.length}, audio-in-codecs=${audioRenditions.length}/${renditions.length}) +variante segs=${(vbody.match(/#EXTINF/g) || []).length}${audioTrack}${r.viaCurl ? ' [node-tls=403,via curl]' : ''}${via === asPlayer ? '' : ' [' + via + ']'}`;
        return res;
      }
      // direct media playlist
      if (!/#EXTINF/.test(body)) { res.detail = 'playlist sin segmentos'; return res; }
      res.playable = true;
      res.audio = hasAudioGroup ? 'yes' : (/#EXT-X-MEDIA:TYPE=AUDIO/i.test(body) ? 'yes' : 'unknown(muxed?)');
      res.detail = `media playlist ok, segs=${(body.match(/#EXTINF/g) || []).length}`;
      return res;
    }
    // VIDEO: Range request for first bytes
    const r = await playerGet(link.url, { headers: { ...headers, Range: 'bytes=0-65535' } });
    const buf = r.bytes;
    const ct = String(r.headers['content-type'] || '').toLowerCase();
    if (r.status !== 200 && r.status !== 206) { res.detail = `HTTP ${r.status} ct=${ct}`; return res; }
    const isMp4 = /ftyp/.test(buf.subarray(0, 32).toString('latin1'));
    if (!ct.startsWith('video/') && !isMp4) { res.detail = `HTTP ${r.status} ct=${ct || '-'} bytes=${buf.length} (no video/*)`; return res; }
    res.playable = true;
    res.detail = `HTTP ${r.status} ct=${ct || '-'} bytes=${buf.length}${isMp4 ? ' (mp4 ftyp ok)' : ''}`;
    res.audio = 'n/a(progressive)';
    return res;
  } catch (e) { res.detail = `ERR ${e.message}`; return res; }
}

async function validateSub(s) {
  try {
    const r = await playerGet(s.url, { headers: { 'User-Agent': UA, Referer: DM_REFERER } });
    const head = r.text.slice(0, 40).replace(/\s+/g, ' ');
    return { ...s, status: r.status, head, ok: r.status === 200 && (head.includes('WEBVTT') || /-->\s/.test(r.text.slice(0, 400))) };
  } catch (e) { return { ...s, status: 'ERR ' + e.message, ok: false }; }
}

/* ------------------------------------------------------------------ */
/* main                                                                 */
/* ------------------------------------------------------------------ */

async function main() {
  log('=== AnimeCubeProvider live matrix ===');
  const disc = await discoverSlugs();
  log(`home cards slugs=${disc.homeCount}, extra from sitemap=${disc.smCount}, catalog items=${disc.catalog.length}`);

  const reg = await fetchRegistry();
  log(`registry: mode=${reg.mode} entries=${reg.entries.length}`);
  const registry = reg.entries;

  // --scan: which platforms does the site actually publish? (decides whether the
  // rumble branch of loadLinks is dead code in practice)
  if (RAWARGS.includes('--scan')) {
    const tally = new Map();
    const onlyReg = new Set(registry.map(r => r.slug));
    const notInReg = disc.order.filter(s => !onlyReg.has(s));
    log(`slugs en home sin entrada en el registro: ${notInReg.length} [${notInReg.slice(0, 8).join(', ')}]`);
    for (const slug of disc.order.filter(s => onlyReg.has(s)).slice(0, Number(process.env.AC_SCAN_N || 15))) {
      const ser = await loadSeries(slug);
      const ep = ser.episodes[ser.episodes.length - 1];
      if (!ep) { log(`  ${slug}: 0 episodios`); continue; }
      const ent = registry.filter(e => e.slug === ep.id.match(/^(.+)-(tab-[\w-]+)-ep-\d+(?:-\d+)?$/)?.[1] && e.seasonId === ep.id.match(/-(tab-[\w-]+)-ep-/)?.[1])
        .concat(registry.filter(e => e.slug === slug));
      if (!ent.length) { log(`  ${slug} ep${ep.number}: SIN entradas en registro`); continue; }
      const res = await fetchSources(slug, ep.id, ent[0].primaryTabId, ent[0].seasonId, ent[0].version);
      for (const s of res.list) {
        const k = `${s.platform}${s.quality ? '/' + s.quality : ''}`;
        tally.set(k, (tally.get(k) || 0) + 1);
      }
      log(`  ${slug} ep${ep.number}: ${res.list.length} sources [${res.list.map(s => s.platform + ':' + s.quality).join(', ')}]`);
    }
    log('platform tally:', JSON.stringify([...tally]));
    return;
  }

  let slugs = args.filter(a => !a.startsWith('--'));
  if (!slugs.length) {
    const nonMovie = disc.order.filter(s => !s.includes('movie'));
    slugs = [nonMovie[0], nonMovie[1], nonMovie[2]];
  }

  const picked = []; // {series, ep, kind}
  for (let i = 0; i < slugs.length; i++) {
    const slug = slugs[i];
    const ser = await loadSeries(slug);
    const withSources = ser.episodes.filter(e => registry.some(r => r.slug === slug && r.seasonId === e.id.split('-ep-')[0].split(`${slug}-`)[1]));
    log(`\nseries ${slug}: "${ser.title}" seasons=${ser.seasons.length} episodes=${ser.episodes.length} (in registry=${withSources.length})`);
    if (!ser.episodes.length) continue;
    const latest = ser.episodes[ser.episodes.length - 1];
    picked.push({ ser, ep: latest, kind: i === 0 ? 'latest#1' : `latest#${i + 1}` });
    if (i === 0) {
      const old = ser.episodes.find(e => e.number === 1) || ser.episodes[0];
      if (old !== latest) picked.push({ ser, ep: old, kind: 'older ep1' });
      const mid = ser.episodes[Math.max(0, Math.floor(ser.episodes.length / 2))];
      if (mid !== latest && mid !== old) picked.push({ ser, ep: mid, kind: 'mid ep' });
    }
  }
  const five = picked.slice(0, 5);
  log(`\nselected ${five.length} episodes`);

  const rows = [];
  for (const p of five) {
    const label = `${p.ser.slug} ep${p.ep.number}`;
    log(`\n---- ${label} [${p.kind}] id=${p.ep.id}`);
    const t0 = Date.now();
    const r = await loadLinks('https://animecube.live/' + p.ep.id, registry);
    log(`     sources=${r.nSources ?? 0} via=${r.via || '-'} entry=${r.used ? r.used.primaryTabId + '/' + r.used.seasonId : '-'} links=${r.links.length} subs=${r.subs.length} skipped=[${r.skipped.join(',')}] loadLinks.returned=${r.ok} (${Date.now() - t0}ms)`);
    if (!r.ok && !r.links.length) {
      rows.push({ ep: label, sources: r.nSources ?? 0, emitted: 0, playable: 0, verdict: 'FAIL', reason: r.reason || 'no links' });
      continue;
    }
    for (const l of r.links) {
      const v = await validateLink(l);
      log(`     [${v.playable ? 'OK ' : 'BAD'}] ${l.type.padEnd(5)} q=${String(l.quality).padEnd(4)} ${l.name} -> ${DEBUG ? l.url : l.url.slice(0, 96)}  ${v.detail}${v.audio === 'NO(video-only)' ? '  *** SIN AUDIO ***' : ''}`);
      l._v = v;
    }
    const vsubs = [];
    for (const s of r.subs.slice(0, 3)) { const vs = await validateSub(s); vsubs.push(vs); log(`     sub ${vs.lang} HTTP ${vs.status} ${vs.ok ? 'WEBVTT ok' : 'BAD'} head=${JSON.stringify((vs.head || '').slice(0, 50))} ${DEBUG ? vs.url : ''}`); }
    const playable = r.links.filter(l => l._v?.playable && l._v.audio !== 'NO(video-only)').length;
    const emitted = r.links.length;
    const verdict = emitted === 0 ? 'FAIL' : playable === emitted ? 'PASS' : playable > 0 ? 'PARTIAL' : 'FAIL';
    const reasons = [];
    if (r.links.some(l => !l._v?.playable)) reasons.push('muerto: ' + r.links.filter(l => !l._v?.playable).map(l => l._v?.detail).join(' | '));
    if (r.links.some(l => l._v?.audio === 'NO(video-only)')) reasons.push('HLS sin audio');
    if (r.skipped.length) reasons.push('plataforma ignorada: ' + r.skipped.join(','));
    rows.push({ ep: label, sources: r.nSources ?? 0, emitted, playable, verdict, reason: reasons.join('; ') || 'ok' });
  }

  console.log('\n================= FINAL TABLE (AnimeCube) =================');
  console.table(rows);
}

main().catch(e => { console.error('FATAL', e); process.exit(1); });
