#!/usr/bin/env node
/**
 * Live validation of AnimeGratisProvider (https://animegratis.net).
 *
 * Faithful Node port of:
 *   AnimeGratisProvider/src/main/kotlin/com/donghuaext/AnimeGratisProvider.kt
 *     getMainPage (:163-194) -> parseHomePageCards (:196-226), parseDonghuaCards
 *     (:232-244), parseDirectorioCards (:260-289, script#ssr-init)
 *     load (:451-710)  -> #dh-episodes-grid / #episodes-grid + episode synthesis
 *     loadLinks (:873-928) -> doc.select("[data-url]") / iframe / script URLs
 *     processVideoUrl (:945-978), extractJkPlayer (:986), extractStreamwish (:1012),
 *     extractFromPage (:1048), extractDailymotionApi (:1064), extractOkRu (:1114)
 *   common/src/CommonExtractors.kt: extractDailymotion (:160-352), extractOkRu (:460-516)
 *   CloudStream: M3u8Helper.generateM3u8, getAndUnpack + JsUnpacker
 *
 * ALL network I/O (scrape + validation) goes through curl (execFileSync):
 * Node's undici TLS fingerprint is blocked by Akamai/Cloudflare on Dailymotion
 * CDN URLs (fake 403), curl returns the real 200.
 *
 * Validation per emitted link: M3U8 -> HTTP 200 + body #EXTM3U + first variant
 * has #EXTINF segments + NOT VMAP/VAST; audio asserted via CODECS /
 * #EXT-X-MEDIA:TYPE=AUDIO / TS PMT probe / fMP4 'soun'. VIDEO -> 200/206 +
 * Content-Type video/* (Range 0-1023). video-only = FAILURE ("sin audio").
 *
 * Usage: node tools/matrix_animegratis.mjs [episodeUrl ...]   (AG_DEBUG=1 verbose)
 * No file outside tools/ is touched.
 */
import { execFileSync } from 'node:child_process';

const MAIN = 'https://animegratis.net';
// Desktop Chrome UA (CloudStream USER_AGENT / CommonExtractors.kt:29-30 uses Chrome/130).
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
const DM_REFERER = 'https://www.dailymotion.com/'; // CommonExtractors.kt:26
const QUAL = { Unknown: 0, P360: 360, P480: 480, P720: 720, P1080: 1080 };

// AnimeGratisProvider.kt:55-62
const SITE_HDRS = [
  'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language: es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3',
];

const DEBUG = process.env.AG_DEBUG === '1';
const log = (...a) => console.log(...a);
const dbg = (...a) => { if (DEBUG) console.log('[dbg]', ...a); };

/* ------------------------------------------------------------------ */
/* transport: curl only (execFileSync, per task requirement)           */
/* ------------------------------------------------------------------ */

const VMAP_RE = /<vmap:|<VMAP|<VAST|<Ad |<vmap:/i;

/**
 * GET via curl. Returns {status, text, bytes, ct, error}.
 * Mirrors: execFileSync('curl', ['-s','-L','-m','30','-A',UA,'-e',referer,url]).
 */
function get(url, { referer = null, headers = [], timeout = 30, range = null } = {}) {
  const args = ['-s', '-L', '-m', String(timeout), '-A', UA];
  if (referer) args.push('-e', referer);
  for (const h of headers) args.push('-H', h);
  if (range) args.push('-r', range);
  args.push(url, '-w', '\n@@CODE@@%{http_code}@@CT@@%{content_type}');
  let buf;
  try {
    buf = execFileSync('curl', args, { maxBuffer: 64 * 1024 * 1024, windowsHide: true });
  } catch (e) {
    return { status: 0, text: '', bytes: Buffer.alloc(0), ct: '', error: 'curl ERR ' + (e.message || '').slice(0, 120) };
  }
  const i = buf.lastIndexOf('@@CODE@@');
  if (i < 0) return { status: 0, text: buf.toString('utf8'), bytes: buf, ct: '', error: 'no meta' };
  const meta = buf.slice(i).toString('utf8'); // \n@@CODE@@nnn@@CT@@xxx
  const m = /@@CODE@@(\d+)@@CT@@([\s\S]*)/.exec(meta);
  const status = m ? Number(m[1]) : 0;
  const ct = m ? m[2].trim() : '';
  const bytes = buf.subarray(0, i); // strips the leading \n of the -w block
  return { status, ct, bytes, text: bytes.toString('utf8') };
}

/* ------------------------------------------------------------------ */
/* Jsoup replacements (regex over raw HTML) — same as the previous port */
/* ------------------------------------------------------------------ */

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ensp: ' ', shy: '' };
function attrDecode(s) {
  return String(s).replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (mm, g) => {
    if (g[0] === '#') {
      const cp = g[1] === 'x' || g[1] === 'X' ? parseInt(g.slice(2), 16) : parseInt(g.slice(1), 10);
      return Number.isNaN(cp) ? mm : String.fromCodePoint(cp);
    }
    const k = g.toLowerCase();
    return ENTITIES[k] !== undefined ? ENTITIES[k] : mm;
  });
}
function parseAttrs(s) {
  const a = {};
  for (const m of s.matchAll(/([\w:@-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s">]+)))?/g)) {
    a[m[1].toLowerCase()] = attrDecode(m[2] ?? m[3] ?? m[4] ?? '');
  }
  return a;
}
/** doc.select("[data-url]") equivalent: [{tag, attrs, value}] */
function selectAttr(html, attr) {
  const out = [];
  for (const m of html.matchAll(/<([a-zA-Z][\w:-]*)\b([^>]*)>/g)) {
    const am = new RegExp(`\\b${attr}=(?:"([^"]*)"|'([^']*)'|([^\\s">]+))`).exec(m[2]);
    if (!am) continue;
    out.push({ tag: m[1].toLowerCase(), attrs: parseAttrs(m[2]), value: attrDecode(am[1] ?? am[2] ?? am[3] ?? '') });
  }
  return out;
}
function anchors(html) {
  const out = [];
  for (const m of html.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/gi)) out.push({ attrs: parseAttrs(m[1]), inner: m[2] });
  return out;
}
function elementById(html, id) {
  const open = new RegExp(`<([a-zA-Z][\\w:-]*)\\b[^>]*\\bid="${id}"[^>]*>`, 'i').exec(html);
  if (!open) return null;
  const tag = open[1];
  let i = open.index + open[0].length;
  let depth = 1;
  const re = new RegExp(`<${tag}\\b[^>]*>|</${tag}\\s*>`, 'gi');
  re.lastIndex = i;
  let m;
  while ((m = re.exec(html))) {
    if (m[0][1] === '/') { depth--; if (depth === 0) return html.slice(i, m.index); }
    else if (!/\/>$/.test(m[0])) depth++;
  }
  return html.slice(i);
}
function firstImg(fragment) {
  const m = /<img\b([^>]*)>/i.exec(fragment || '');
  return m ? parseAttrs(m[1]) : null;
}
function spanTexts(html) {
  const out = [];
  for (const m of html.matchAll(/<span\b[^>]*>([\s\S]*?)<\/span>/gi)) {
    out.push(attrDecode(m[1].replace(/<[^>]*>/g, '')).replace(/\s+/g, ' ').trim());
  }
  return out;
}
function resolveUrl(url) { // :43-51
  if (!url) return '';
  if (url.startsWith('http')) return url;
  if (url.startsWith('//')) return 'https:' + url;
  if (url.startsWith('/')) return MAIN + url;
  if (url.trim()) return MAIN + '/' + url;
  return '';
}

/* ------------------------------------------------------------------ */
/* discovery: getMainPage (:163-194)                                   */
/* ------------------------------------------------------------------ */

function parseHomePageCards(html) { // :196-226 + parseHomeEpisodeCard :352-372
  const results = [];
  let cards = [...html.matchAll(/<a\b([^>]*\bdata-home-episode-card[^>]*)>([\s\S]*?)<\/a>/gi)]
    .map(m => ({ attrs: parseAttrs(m[1]), inner: m[2] }));
  if (!cards.length) { // Método 2
    cards = anchors(html).filter(a => (a.attrs.href || '').includes('episodio'));
  }
  for (const c of cards) {
    const p = parseHomeEpisodeCard(c.attrs.href || '', c.inner);
    if (p) results.push(p);
  }
  return results;
}
function parseHomeEpisodeCard(href, fragment) {
  if (!href || !href.includes('episodio')) return null;
  const img = firstImg(fragment);
  let title = img && img.alt ? img.alt.trim() : null;
  if (!title) {
    const t = /class="[^"]*(?:title|name)[^"]*"[^>]*>([^<]+)/.exec(fragment || '');
    title = t ? attrDecode(t[1]).trim() : null;
  }
  if (!title) return null;
  const clean = title.replace(/\s*Episodio\s*\d+/i, '').trim();
  if (!clean) return null;
  const ep = /episodio-(\d+)/.exec(href)?.[1];
  return { title: clean, seriesUrl: episodeUrlToSeriesUrl(href), ep: ep ? Number(ep) : null };
}
function episodeUrlToSeriesUrl(epHref) { // :374-387
  const full = resolveUrl(epHref);
  const dh = /\/donghua\/([^/]+)\/episodio-\d+/.exec(full);
  if (dh) return `${MAIN}/donghua/${dh[1]}`;
  const an = /\/anime\/([^/]+)\/episodio-\d+/.exec(full);
  if (an) return `${MAIN}/anime/${an[1]}`;
  return full;
}
function parseDonghuaCards(html) { // :232-244
  const seen = new Set(); const out = [];
  for (const a of anchors(html)) {
    const href = a.attrs.href || '';
    if (!href.startsWith('/donghua/')) continue;
    if (href.includes('episodio') || href.includes('/genero/')) continue;
    const img = firstImg(a.inner);
    let title = img && img.alt ? img.alt.trim().replace(/^Portada de\s*/i, '').trim() : null;
    if (!title) title = (/<h3[^>]*>([^<]+)<\/h3>/i.exec(a.inner)?.[1] || '').trim() || null;
    if (!title) continue;
    const url = resolveUrl(href);
    if (seen.has(url)) continue;
    seen.add(url);
    out.push({ title, url });
  }
  return out;
}
function parseDirectorioCards(html) { // :260-289 script#ssr-init
  const m = /<script[^>]*id="ssr-init"[^>]*>([\s\S]*?)<\/script>/i.exec(html);
  if (!m) return [];
  try {
    const ssr = JSON.parse(m[1]);
    return (ssr.animes || []).filter(a => a.t && a.sl).map(a => ({
      title: a.t,
      url: (a.ty || '').toLowerCase().includes('donghua') ? `${MAIN}/donghua/${a.sl}` : `${MAIN}/anime/${a.sl}`,
    }));
  } catch { return []; }
}

/* ------------------------------------------------------------------ */
/* load (:451-710) — only the episode-grid part is needed here         */
/* ------------------------------------------------------------------ */

function load(url) { // synchronous (curl); returns {title, episodes, status}
  const res = get(url, { referer: null, headers: SITE_HDRS });
  const html = res.text;
  const isDonghua = url.includes('/donghua/');

  let jsonLdEpCount = null;
  for (const m of html.matchAll(/<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/gi)) {
    const raw = m[1];
    if (raw.includes('TVSeries') || raw.includes('Movie')) {
      try {
        const j = JSON.parse(raw);
        const n = (Array.isArray(j) ? j : [j]).map(x => x.numberOfEpisodes).find(v => typeof v === 'number');
        if (n !== undefined) jsonLdEpCount = n;
      } catch { /* Kotlin: catch(_:Exception) */ }
    }
  }

  const episodes = [];
  if (isDonghua) {
    const gridHtml = elementById(html, 'dh-episodes-grid');
    if (gridHtml) {
      const slug = /data-slug="([^"]*)"/.exec(gridHtml)?.[1] || '';
      for (const a of anchors(gridHtml)) {
        if (a.attrs['data-episode'] === undefined) continue; // epGrid.select("a[data-episode]")
        const href = a.attrs.href || '';
        if (!href) continue;
        const epNum = Number(a.attrs['data-episode']) || (/episodio-(\d+)/.exec(href)?.[1] ? Number(/episodio-(\d+)/.exec(href)[1]) : null);
        episodes.push({ url: resolveUrl(href), ep: epNum });
      }
      const totalEpText = spanTexts(html).find(s => s.includes('episodio')) || '';
      const totalEps = Number(/(\d+)\s*episodio/i.exec(totalEpText)?.[1] ?? jsonLdEpCount ?? 0);
      if (totalEps > episodes.length && slug) {
        for (let ep = 1; ep <= totalEps; ep++) {
          if (!episodes.some(e => e.ep === ep)) episodes.push({ url: `${MAIN}/donghua/${slug}/episodio-${ep}`, ep, synthesized: true });
        }
      }
    }
    if (!episodes.length) { // fallback :629-637
      for (const a of anchors(html)) {
        const href = a.attrs.href || '';
        if (!href.includes('episodio') || !href.includes('/donghua/')) continue;
        episodes.push({ url: resolveUrl(href), ep: Number(/episodio-(\d+)/.exec(href)?.[1] ?? 0) || null });
      }
    }
  } else {
    const gridHtml = elementById(html, 'episodes-grid');
    if (gridHtml) {
      for (const a of anchors(gridHtml)) {
        if (a.attrs['data-episode'] === undefined) continue;
        const href = a.attrs.href || '';
        if (!href) continue;
        episodes.push({ url: resolveUrl(href), ep: Number(a.attrs['data-episode']) || (/episodio-(\d+)/.exec(href)?.[1] ? Number(/episodio-(\d+)/.exec(href)[1]) : null) });
      }
    }
    if (!episodes.length) { // fallback :651-659
      const seen = new Set();
      for (const a of anchors(html)) {
        const href = a.attrs.href || '';
        if (!href.includes('episodio') || !href.includes('/anime/') || seen.has(href)) continue;
        seen.add(href);
        episodes.push({ url: resolveUrl(href), ep: Number(/episodio-(\d+)/.exec(href)?.[1] ?? 0) || null });
      }
    }
    if (episodes.length < (jsonLdEpCount ?? 0)) { // synthesis :662-672
      const totalEps = jsonLdEpCount ?? episodes.length;
      const base = (url.split('/anime/')[1] || '').replace(/-anime$/, '').replace(/\/$/, '');
      for (let ep = 1; ep <= totalEps; ep++) {
        if (!episodes.some(e => e.ep === ep)) episodes.push({ url: `${MAIN}/anime/${base}/episodio-${ep}`, ep, synthesized: true });
      }
    }
  }
  const h1 = /<h1[^>]*>([\s\S]*?)<\/h1>/i.exec(html);
  const title = h1 ? attrDecode(h1[1].replace(/<[^>]*>/g, '')).replace(/\s+/g, ' ').trim() : '';
  return { url, status: res.status, title, episodes, jsonLdEpCount, isDonghua };
}

/* ------------------------------------------------------------------ */
/* loadLinks (:873-928) + provider extractors                          */
/* ------------------------------------------------------------------ */

const agEmittedDmVids = new Set(); // :1112 (instance set, cleared per episode)
let LINKS = [];
let SUBS = [];   // subtitle files that reach a REAL subtitleCallback
let DROPPED = []; // subtitles CE discovered but the provider swallowed (:1067 `{ }`)
let NOTES = [];

function serverNameFromUrl(url) { // :931-938
  try {
    const host = new URL(url).host || 'Server';
    return host.replace(/^www\./, '').split('.')[0].replace(/^./, c => c.toUpperCase());
  } catch { return 'Server'; }
}

function loadLinks(data) {
  LINKS = []; SUBS = []; DROPPED = []; NOTES = [];
  const res = get(data, { headers: SITE_HDRS });
  const html = res.text;
  agEmittedDmVids.clear(); // :879
  let foundLinks = false;
  const servers = [];

  // Método 1: doc.select("[data-url]") (:886-891)
  for (const el of selectAttr(html, 'data-url')) {
    const videoUrl = el.value.trim();
    if (!videoUrl.startsWith('http')) continue;
    const before = LINKS.length;
    processVideoUrl(videoUrl, data, serverNameFromUrl(videoUrl));
    servers.push({ kind: 'data-url', url: videoUrl, links: LINKS.length - before });
    foundLinks = (LINKS.length - before) > 0 || foundLinks;
  }

  // Método 2: iframe (:894-904)
  if (!foundLinks) {
    for (const m of html.matchAll(/<iframe\b([^>]*)>/gi)) {
      const a = parseAttrs(m[1]);
      const src = [a.src, a['data-src']].map(v => (v || '').trim()).find(v => v);
      if (!src) continue;
      const fullSrc = resolveUrl(src);
      if (!fullSrc.startsWith('http')) continue;
      const before = LINKS.length;
      processVideoUrl(fullSrc, data, 'Player');
      servers.push({ kind: 'iframe', url: fullSrc, links: LINKS.length - before });
      foundLinks = (LINKS.length - before) > 0 || foundLinks;
    }
  }

  // Método 3: URLs en scripts (:907-925)
  if (!foundLinks) {
    for (const m of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) {
      const scriptData = m[1];
      if (!scriptData.includes('.m3u8') && !scriptData.includes('.mp4')) continue;
      const mu = /(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/.exec(scriptData);
      if (mu) { try { if (generateM3u8('Server', mu[1], data).length) foundLinks = true; } catch {} }
      if (!foundLinks) {
        const pu = /(https?:\/\/[^\s"'<>]+\.mp4[^\s"'<>]*)/.exec(scriptData);
        if (pu) { LINKS.push({ source: 'Server', name: 'Server', url: pu[1], type: 'VIDEO', quality: QUAL.Unknown, referer: data, platform: 'script-mp4' }); foundLinks = true; }
      }
      if (foundLinks) break;
    }
  }

  // diagnostic only: the site's client-rendered server list (NOT read by the Kotlin)
  const pud = /<script[^>]*id="player-ui-data"[^>]*>([\s\S]*?)<\/script>/i.exec(html);
  if (pud && !servers.length) {
    try {
      const j = JSON.parse(pud[1]);
      const names = [];
      for (const groups of Object.values(j.groups || {})) for (const g of groups || []) names.push(g.name);
      if (names.length) NOTES.push(`HTML tiene <script id="player-ui-data"> con ${names.length} servers [${[...new Set(names)].join(', ')}] renderizados cliente; el Kotlin (Jsoup, sin JS) solo ve el iframe por defecto -> Método 2`);
    } catch {}
  }
  return { foundLinks, servers, status: res.status };
}

/** processVideoUrl (:945-978) — synchronous */
function processVideoUrl(videoUrl, referer, serverName) {
  let links = 0;
  const counting = (l) => { links++; LINKS.push(l); };

  if (videoUrl.includes('jkanime.net/jkplayer')) extractJkPlayer(videoUrl, referer, serverName, counting);
  else if (videoUrl.includes('wish') || videoUrl.includes('sfast') || videoUrl.includes('streamta')) extractStreamwish(videoUrl, referer, serverName, counting);
  else if (videoUrl.includes('zilla-networks.com') || videoUrl.includes('player.zilla')) extractFromPage(videoUrl, referer, serverName, counting);
  else if (videoUrl.includes('dailymotion.com')) {
    const videoId = /dailymotion\.com\/(?:embed\/)?video\/([a-zA-Z0-9]+)/.exec(videoUrl)?.[1]; // :962
    if (videoId) extractDailymotionApi(videoId, referer, serverName, counting);
    else NOTES.push(`${serverName}: regex videoId (:962) sin match -> server descartado`);
  } else if (videoUrl.includes('ok.ru')) extractOkRuLocal(videoUrl, referer, serverName, counting);

  if (links === 0) {
    // loadExtractor (:971): CloudStream's built-in registry (voe, mega, mixdrop,
    // mp4upload, vidhide, yourupload, dsvplay, bysekoze...). Not portable to Node.
    const host = (() => { try { return new URL(videoUrl).host; } catch { return videoUrl.slice(0, 40); } })();
    NOTES.push(`${serverName} (${host}): links==0 -> loadExtractor() (registry de la app CloudStream, no emulable en Node)`);
  }
  if (links === 0) extractFromPage(videoUrl, referer, serverName, counting); // :974-976
  return links > 0;
}

/** extractJkPlayer (:986-1005) */
function extractJkPlayer(playerUrl, referer, serverName, callback) {
  const r = get(playerUrl, { referer, headers: SITE_HDRS, timeout: 20 });
  if (r.status !== 200) { NOTES.push(`${serverName} ${playerUrl.slice(0, 60)}: player HTTP ${r.status}${r.error ? ' ' + r.error : ''}`); return false; }
  const text = r.text;
  let found = false;
  for (const m of text.matchAll(/(https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*)/g)) {
    try { if (generateM3u8(serverName, m[1], playerUrl, callback).length) found = true; } catch {}
  }
  if (!found) {
    for (const m of text.matchAll(/(https?:\/\/[^"'\s<>]+\.mp4[^"'\s<>]*)/g)) {
      callback({ source: serverName, name: serverName, url: m[1], type: 'VIDEO', quality: QUAL.Unknown, referer: playerUrl, platform: 'jkplayer' });
      found = true;
    }
  }
  if (!found) NOTES.push(`${serverName}: jkplayer page (${r.text.length}B) sin .m3u8/.mp4`);
  return found;
}

/** CloudStream getAndUnpack + JsUnpacker (:1015) */
function getAndUnpack(str) {
  const packed = /eval\(function\(p,a,c,k,e,.*\)\)/.exec(str)?.[0];
  return unpack(packed) ?? str;
}
function unpack(packedJS) {
  if (!packedJS) return null;
  try {
    const m = /\}\s*\('([\s\S]*?)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)/.exec(packedJS);
    if (!m || m.length < 5) return null;
    const payload = m[1].replace(/\\'/g, "'");
    let radix = 36;
    const radixStr = m[2];
    const count = parseInt(m[3], 10) || 0;
    if (!Number.isNaN(parseInt(radixStr, 10))) radix = parseInt(radixStr, 10);
    const symtab = m[4].split('|');
    if (symtab.length !== count) throw new Error('Unknown p.a.c.k.e.r. encoding');
    const unbase = makeUnbase(radix);
    let out = ''; let lastIndex = 0;
    const wordRe = /\b[a-zA-Z0-9_]+\b/g;
    let wm;
    while ((wm = wordRe.exec(payload))) {
      const word = wm[0];
      let value = null;
      try { const x = unbase(word, radix); if (x >= 0 && x < symtab.length) value = symtab[x]; } catch { value = null; }
      out += payload.slice(lastIndex, wm.index);
      out += (value && value.length) ? value : word;
      lastIndex = wm.index + word.length;
    }
    out += payload.slice(lastIndex);
    return out;
  } catch { return null; }
}
const ALPHABET_62 = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
const ALPHABET_95 = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~";
function makeUnbase(radix) {
  let dict = null;
  if (radix > 36) {
    let alphabet = null;
    if (radix < 62) alphabet = ALPHABET_62.slice(0, radix);
    else if (radix === 62) alphabet = ALPHABET_62;
    else if (radix >= 63 && radix <= 94) alphabet = ALPHABET_95.slice(0, radix);
    else if (radix === 95) alphabet = ALPHABET_95;
    dict = new Map();
    for (let i = 0; i < alphabet.length; i++) dict.set(alphabet[i], i);
  }
  return function (str) {
    if (!dict) { const n = parseInt(str, radix); if (Number.isNaN(n)) throw new Error('unbase NaN'); return n; }
    let ret = 0;
    const tmp = str.split('').reverse().join('');
    for (let i = 0; i < tmp.length; i++) ret += Math.pow(radix, i) * dict.get(tmp[i]);
    return ret;
  };
}

/** extractStreamwish (:1012-1046) */
function extractStreamwish(videoUrl, referer, serverName, callback) {
  const r = get(videoUrl, { referer, headers: SITE_HDRS, timeout: 20 });
  const html = r.text;
  if (r.status !== 200) NOTES.push(`${serverName} ${videoUrl.slice(0, 50)}: embed HTTP ${r.status}${r.error ? ' ' + r.error : ''}`);
  let unpacked;
  try { unpacked = getAndUnpack(html); } catch { unpacked = html; }
  if (unpacked === html) NOTES.push(`${serverName}: getAndUnpack sin packed JS (HTTP ${r.status}, ${html.length}B) -> regex sobre el HTML crudo`);
  let found = false;
  const g1 = /["']?hls["']?\s*:\s*\{[^}]*?["']?(?:url|file)["']?\s*:\s*["']([^"']+)["']/.exec(unpacked);
  if (g1) { try { if (generateM3u8(serverName, g1[1], referer, callback).length) found = true; } catch {} }
  if (!found) for (const m of unpacked.matchAll(/["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']/g)) {
    try { if (generateM3u8(serverName, m[1], referer, callback).length) found = true; } catch {}
  }
  if (!found) for (const m of unpacked.matchAll(/(https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*)/g)) {
    try { if (generateM3u8(serverName, m[1], referer, callback).length) found = true; } catch {}
  }
  if (!found) for (const m of unpacked.matchAll(/["']?file["']?\s*:\s*["']([^"']+\.mp4[^"']*)["']/g)) {
    callback({ source: serverName, name: serverName, url: m[1], type: 'VIDEO', quality: QUAL.Unknown, referer, platform: 'streamwish' });
    found = true;
  }
  return found;
}

/** extractFromPage (:1048-1062) — note: the Kotlin's `\.mp4` regex also matches
 *  hosts like www.mp4upload.com (".mp4" inside the host); kept faithful, the
 *  emitted junk is flagged at validation time. */
function extractFromPage(playerUrl, referer, serverName, callback) {
  try {
    const text = get(playerUrl, { referer, headers: SITE_HDRS, timeout: 20 }).text;
    for (const m of text.matchAll(/(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/g)) {
      try { if (generateM3u8(serverName, m[1], playerUrl, callback).length) return true; } catch {}
    }
    for (const m of text.matchAll(/(https?:\/\/[^\s"'<>]+\.mp4[^\s"'<>]*)/g)) {
      callback({ source: serverName, name: serverName, url: m[1], type: 'VIDEO', quality: QUAL.Unknown, referer: playerUrl, platform: 'page-scrape' });
      return true;
    }
  } catch {}
  return false;
}

/** extractDailymotionApi (:1064-1109): CE first, legacy fallbacks after. */
function extractDailymotionApi(videoId, referer, serverName, callback) {
  // :1067-1071 — the provider passes `{ }` as subtitleCallback: DM subs are swallowed.
  if (ceExtractDailymotion(`https://www.dailymotion.com/embed/video/${videoId}`, referer, serverName, UA, new Set(), agEmittedDmVids, callback)) return true;
  try {
    const apiUrl = `https://www.dailymotion.com/player/metadata/video/${videoId}`;
    const jsonText = get(apiUrl, { referer: `https://www.dailymotion.com/embed/video/${videoId}`, headers: ['User-Agent: ' + UA, 'Accept: application/json'], timeout: 20 }).text;
    for (const m of jsonText.matchAll(/(https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*)/g)) {
      try { if (generateM3u8(serverName, m[1], 'https://www.dailymotion.com', callback).length) return true; } catch {}
    }
    const mp4s = [...new Set([...jsonText.matchAll(/(https?:\/\/[^"'\s<>]+\.mp4[^"'\s<>]*)/g)].map(x => x[1]))];
    if (mp4s.length) {
      for (const url of mp4s) {
        const q = url.includes('1080') ? QUAL.P1080 : url.includes('720') ? QUAL.P720 : url.includes('480') ? QUAL.P480 : QUAL.Unknown;
        callback({ source: serverName, name: `${serverName} ${Math.floor(q / 1000)}`, url, type: 'VIDEO', quality: q, referer: 'https://www.dailymotion.com', platform: 'dailymotion-legacy' });
      }
      return true;
    }
  } catch {}
  try {
    const embedUrl = `https://www.dailymotion.com/embed/video/${videoId}`;
    const html = get(embedUrl, { referer, headers: ['User-Agent: ' + UA], timeout: 20 }).text;
    for (const m of html.matchAll(/(https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*)/g)) {
      try { if (generateM3u8(serverName, m[1], embedUrl, callback).length) return true; } catch {}
    }
    for (const m of html.matchAll(/(https?:\/\/[^"'\s<>]+\.mp4[^"'\s<>]*)/g)) {
      callback({ source: serverName, name: serverName, url: m[1], type: 'VIDEO', quality: QUAL.Unknown, referer: embedUrl, platform: 'dailymotion-embed-scrape' });
      return true;
    }
  } catch {}
  return false;
}

/** provider extractOkRu (:1114-1143): CE first, legacy after. */
function extractOkRuLocal(videoUrl, referer, serverName, callback) {
  if (ceExtractOkRu(videoUrl, referer, serverName, UA, callback)) return true;
  try {
    const html = get(videoUrl, { referer, headers: ['User-Agent: ' + UA], timeout: 20 }).text;
    const dm = /data-options="([^"]+)"/.exec(html);
    if (dm) {
      const optionsJson = dm[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&');
      for (const m of optionsJson.matchAll(/(https?:\/\/[^"]+\.(?:mp4|m3u8)[^"]*)/g)) {
        callback({ source: serverName, name: serverName, url: m[1], type: m[1].includes('.m3u8') ? 'M3U8' : 'VIDEO', quality: QUAL.Unknown, referer: videoUrl, platform: 'okru-legacy' });
        return true;
      }
    }
    const og = /<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']/.exec(html);
    if (og) { callback({ source: serverName, name: serverName, url: og[1], type: 'VIDEO', quality: QUAL.Unknown, referer: videoUrl, platform: 'okru-og' }); return true; }
    for (const m of html.matchAll(/(https?:\/\/[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)/g)) {
      callback({ source: serverName, name: serverName, url: m[1], type: m[1].includes('.m3u8') ? 'M3U8' : 'VIDEO', quality: QUAL.Unknown, referer: videoUrl, platform: 'okru-scrape' });
      return true;
    }
  } catch {}
  return false;
}

/* ------------------------------------------------------------------ */
/* CommonExtractors ports                                              */
/* ------------------------------------------------------------------ */

function unescJson(s) { // CommonExtractors.kt:35
  let out = '';
  for (let i = 0; i < s.length;) {
    if (s[i] === '\\' && i + 1 < s.length) {
      const n = s[i + 1];
      if (n === 'u') {
        const hex = s.slice(i + 2, Math.min(i + 6, s.length));
        const code = /^[0-9a-fA-F]{1,4}$/.test(hex) ? parseInt(hex, 16) : null;
        if (code !== null) { out += String.fromCharCode(code); i += 6; }
        else { out += n; i += 2; }
      } else if (n === 'n') { out += '\n'; i += 2; }
      else if (n === 't') { out += '\t'; i += 2; }
      else if (n === 'r') { out += '\r'; i += 2; }
      else { out += n; i += 2; }
    } else { out += s[i]; i++; }
  }
  return out;
}
function dmVideoId(src) { // :89
  return /(?:geo\.dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|dailymotion\.com\/embed\/video\/)([A-Za-z0-9]+)/i.exec(src)?.[1] || null;
}
function dmSubtitlesBlock(json) { // :62 (balanced-object scan)
  const start = json.indexOf('"subtitles"');
  if (start < 0) return null;
  const dataIdx = json.indexOf('"data"', start);
  if (dataIdx < 0) return null;
  const open = json.indexOf('{', dataIdx);
  if (open < 0) return null;
  let depth = 0, inStr = false, esc = false;
  for (let i = open; i < json.length; i++) {
    const c = json[i];
    if (esc) { esc = false; continue; }
    if (c === '\\' && inStr) esc = true;
    else if (c === '"') inStr = !inStr;
    else if (!inStr && c === '{') depth++;
    else if (!inStr && c === '}') { depth--; if (depth === 0) return json.slice(open, i + 1); }
  }
  return null;
}
function dmSubLangName(code) { // :124
  if (code.startsWith('es')) return 'Spanish';
  if (code.startsWith('en')) return code.includes('auto') ? 'English (auto)' : 'English';
  const map = { fr: 'French', pt: 'Portuguese', ru: 'Russian', tr: 'Turkish', pl: 'Polish', ar: 'Arabic', bn: 'Bengali', fa: 'Persian', id: 'Indonesian', ms: 'Malay', it: 'Italian', de: 'German', hi: 'Hindi', th: 'Thai', vi: 'Vietnamese' };
  return map[code.slice(0, 2)] || code;
}

/** CommonExtractors.extractDailymotion (:160-352). */
function ceExtractDailymotion(iframeSrc, embedderReferer, linkName, customUA, emittedSubs, emittedVids, callback) {
  const vid = dmVideoId(iframeSrc) || (/^[A-Za-z0-9]+$/.test(iframeSrc.trim()) ? iframeSrc.trim() : null);
  if (!vid) return false;
  if (emittedVids.has(vid)) return true; // :173 dedupe
  const referer = embedderReferer || 'https://animexin.dev';
  try {
    const metaUrl = `https://www.dailymotion.com/player/metadata/video/${vid}` +
      `?embedder=${encodeURIComponent(referer)}&integration=inline&app=com.dailymotion.neon`;
    const r = get(metaUrl, { referer, headers: ['User-Agent: ' + customUA], timeout: 25 });
    if (r.status !== 200) dbg(`  DM ${vid} metadata HTTP ${r.status} ${r.text.slice(0, 140)}`);
    const json = r.text.split('\\/').join('/').split('\\u0026').join('&'); // :181

    // emitDmSubtitles (:97-122): CE -> subtitleCallback; AnimeGratis passes `{ }`
    // (:1067) so these are discovered and then SWALLOWED by the provider.
    const block = dmSubtitlesBlock(json);
    if (block) {
      const rows = [];
      for (const m of block.matchAll(/"(es[a-z-]*|en[a-z-]*|fr|pt|ru|tr|pl|ar|bn|fa|id|ms|it|de|hi|th|vi)":\s*\{[^{}]*?"urls":\["([^"]+)"/g)) rows.push([m[1], unescJson(m[2])]);
      const seen = new Set();
      const uniq = [];
      for (const [c, u] of rows) if (!seen.has(u)) { seen.add(u); uniq.push([c, u]); }
      uniq.sort((a, b) => (a[0].startsWith('es') ? 0 : a[0].startsWith('en') ? 1 : 2) - (b[0].startsWith('es') ? 0 : b[0].startsWith('en') ? 1 : 2));
      for (const [c, u] of uniq) {
        if (emittedSubs.has(u)) continue;
        emittedSubs.add(u);
        DROPPED.push({ lang: dmSubLangName(c), url: u, reason: 'AnimeGratisProvider.kt:1067 pasa subtitleCallback = { } a CE.extractDailymotion' });
      }
    }

    let found = false;
    const emittedUrls = new Set();
    const emit = (u, hls, q) => {
      if (!u || !u.startsWith('http')) return;
      if (emittedUrls.has(u)) return;
      emittedUrls.add(u);
      const suffix = q === QUAL.P1080 ? ' 1080p' : q === QUAL.P720 ? ' 720p' : q === QUAL.P480 ? ' 480p' : q === QUAL.P360 ? ' 360p' : '';
      let name = linkName;
      if (suffix && !/\b\d{3,4}p\b/i.test(name)) name += suffix;
      callback({ source: linkName, name, url: u, type: hls ? 'M3U8' : 'VIDEO', quality: q ?? QUAL.Unknown, referer: DM_REFERER, platform: 'dailymotion', vid });
      found = true;
    };
    const tryPlaylist = (u, hlsQ) => {
      if (!u || !u.startsWith('http')) return false;
      if (/dmxleo/i.test(u) || /advertising/i.test(u) || u.includes('auth=')) return false; // :231 never the VMAP ad URL
      let isValid = false;
      try { isValid = get(u, { referer: DM_REFERER, headers: ['User-Agent: ' + customUA], timeout: 20 }).text.trimStart().startsWith('#EXT'); } catch {}
      if (isValid) {
        let expanded = false;
        try { if (generateM3u8(linkName, u, DM_REFERER, callback, customUA).length) { expanded = true; found = true; } } catch {}
        if (expanded) return true;
      }
      emit(u, true, hlsQ);
      return true;
    };

    let emittedAny = false;
    for (const m of json.matchAll(/"auto":\[\{"url":"(https:\/\/[^"]+)"/g)) { // :261
      if (emittedAny) break;
      if (m[1].includes('dailymotion.com')) emittedAny = tryPlaylist(unescJson(m[1]), QUAL.Unknown);
    }
    if (!emittedAny) for (const m of json.matchAll(/"auto":\[\{[^}]*?"type":"[^"]+","url":"(https:\/\/[^"]+)"/g)) { // :269
      if (emittedAny) break;
      if (m[1].includes('dailymotion.com')) emittedAny = tryPlaylist(unescJson(m[1]), QUAL.Unknown);
    }
    if (!emittedAny) { // vCRN presets :278-292
      const masterAuto = /https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i.exec(json)?.[0];
      const dc = masterAuto ? (/https:\/\/([a-z0-9.-]+)\.dailymotion\.com/i.exec(masterAuto)?.[1] || 'dmxleo') : null;
      const vid2 = masterAuto ? /video\/([a-z0-9]+)\.m3u8/i.exec(masterAuto)?.[1] : null;
      if (dc && vid2 && !dc.startsWith('cdndirector')) {
        for (const preset of ['H264-1280x720', 'H264-1920x1080', 'H264-854x480']) {
          if (tryPlaylist(`https://${dc}.dailymotion.com/cdn/${preset}/vCRN/mp4_h264_aac.m3u8?video=${vid2}`, QUAL.P720)) { emittedAny = true; break; }
        }
      }
    }
    if (!emittedAny) for (const m of json.matchAll(/https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"']*/g)) { // :295
      const u = m[0];
      if (/\/av1/i.test(u) || /_av1_/i.test(u)) continue;
      if (tryPlaylist(u, null)) { emittedAny = true; break; }
    }
    if (emittedAny) { emittedVids.add(vid); return found; }

    for (const m of json.matchAll(/"(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"/g)) { // :309 progressives
      const h = parseInt(m[1], 10);
      const url = unescJson(m[3]);
      const q = Number.isNaN(h) ? QUAL.Unknown : h >= 1080 ? QUAL.P1080 : h >= 720 ? QUAL.P720 : h >= 480 ? QUAL.P480 : h >= 360 ? QUAL.P360 : QUAL.Unknown;
      if (/\/av1/i.test(url) || /_av1_/i.test(url)) continue;
      emit(url, url.includes('.m3u8'), q);
    }
    if (!found) { // :330 unvalidated fallback (never dmxleo/advertising/auth=)
      const mm = /auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json);
      const cand = mm ? unescJson(mm[1]) : (/https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i.exec(json)?.[0] ?? null);
      if (cand && !/dmxleo/i.test(cand) && !/advertising/i.test(cand) && !cand.includes('auth=')) emit(cand, true, QUAL.Unknown);
    }
    if (found) emittedVids.add(vid);
    return found;
  } catch (e) { dbg('DM extract error', e.message); return false; }
}

/** CommonExtractors.extractOkRu (:460-516). */
function ceExtractOkRu(embedUrl0, siteReferer, linkName, customUA, callback) {
  try {
    const url = embedUrl0.startsWith('//') ? 'https:' + embedUrl0 : embedUrl0;
    const html = get(url, { referer: siteReferer, headers: ['User-Agent: ' + customUA], timeout: 25 }).text;
    const decodeUnicode = s => s.replace(/\\u([0-9a-fA-F]{4})/g, (m, h) => String.fromCharCode(parseInt(h, 16)));
    const raw = /data-options="([^"]+)"/.exec(html)?.[1];
    if (!raw) return false;
    const options = decodeUnicode(raw.replace(/&quot;/g, '"').split('\\/').join('/'));
    let found = false;
    const cands = [...options.matchAll(/"(?:[a-zA-Z]*ManifestUrl|ondemandHls)":"([^"]+)"/g)].map(m => m[1]);
    for (const c of cands) {
      let ok = false;
      try { ok = get(c, { referer: url, headers: ['User-Agent: ' + customUA], timeout: 25 }).text.trimStart().startsWith('#EXTM3U'); } catch {}
      if (ok) { try { if (generateM3u8(linkName, c, url, callback, customUA).length) found = true; } catch {} break; }
    }
    for (const m of options.matchAll(/"name":"(mobile|lowest|low|sd|hd|full|super)","url":"([^"]+)"/g)) {
      const progressive = decodeUnicode(m[2]);
      if (!progressive.startsWith('http') && !progressive.startsWith('//')) continue;
      const full = progressive.startsWith('//') ? 'https:' + progressive : progressive;
      const q = (m[1] === 'full' || m[1] === 'super') ? QUAL.P1080 : m[1] === 'hd' ? QUAL.P720 : m[1] === 'sd' ? QUAL.P480 : QUAL.P360;
      callback({ source: linkName, name: linkName, url: full, type: 'VIDEO', quality: q, referer: url, platform: 'okru' });
      found = true;
    }
    return found;
  } catch { return false; }
}

/**
 * CloudStream M3u8Helper.generateM3u8:
 *  - master (#EXT-X-STREAM-INF): one M3U8 link per variant (quality = height)
 *    plus the master itself (returnThis=true).
 *  - media playlist (#EXTINF): the playlist itself, one link.
 *  - anything else: no link (Kotlin drops it).
 */
function generateM3u8(source, streamUrl, referer, emit = (l) => LINKS.push(l), customUA) {
  const r = get(streamUrl, { referer, headers: customUA ? ['User-Agent: ' + customUA] : [], timeout: 25 });
  const text = r.text;
  if (r.status !== 200) { NOTES.push(`generateM3u8(${source}): master HTTP ${r.status} ${streamUrl.slice(0, 80)}`); throw new Error('http ' + r.status); }
  if (!text.trimStart().startsWith('#EXTM3U')) throw new Error('not a playlist: ' + text.slice(0, 40).replace(/\s+/g, ' '));
  const base = new URL(streamUrl);
  const added = [];
  if (/#EXT-X-STREAM-INF/i.test(text)) {
    const lines = text.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const attr = lines[i];
      if (!/#EXT-X-STREAM-INF/i.test(attr)) continue;
      const code = /CODECS="([^"]*)"/.exec(attr)?.[1] || '';
      if (/(^|,)\s*(stvp|mp4a\.255)/.test(code) && !/RESOLUTION=/i.test(attr)) continue; // trickplay
      const res = /RESOLUTION=(\d+)x(\d+)/.exec(attr);
      let u = (lines.slice(i + 1).find(l => l.trim() && !l.startsWith('#')) || '').trim();
      if (!u) continue;
      if (!/^https?:/.test(u)) u = new URL(u, base).toString();
      added.push({ source, name: source, url: u, type: 'M3U8', quality: res ? Number(res[2]) : QUAL.Unknown, referer, platform: 'generateM3u8', codecs: code, fromMaster: streamUrl });
    }
    added.push({ source, name: source + ' (master)', url: streamUrl, type: 'M3U8', quality: QUAL.Unknown, referer, platform: 'generateM3u8(master)', isMaster: true });
  } else if (/#EXTINF/.test(text)) {
    added.push({ source, name: source, url: streamUrl, type: 'M3U8', quality: QUAL.Unknown, referer, platform: 'generateM3u8' });
  } else return [];
  for (const l of added) emit(l);
  return added;
}

/* ------------------------------------------------------------------ */
/* validation (curl, like a player would do)                           */
/* ------------------------------------------------------------------ */

const AUDIO_CODEC_RE = /(mp4a|aac|ac-3|eac-3|ec-3|opus|flac|\.mp3|audio)/i;
const vCache = new Map();

function curlGetCached(url, opts) {
  const key = url + '|' + (opts.referer || '') + '|' + (opts.range || '');
  let v = vCache.get(key);
  if (!v) { v = get(url, opts); vCache.set(key, v); }
  return v;
}

/** MPEG-TS PAT->PMT->elementary streams. Audio stream_types (AVC 0x1B/HEVC 0x24
 *  are VIDEO, deliberately NOT in the set). */
const TS_AUDIO_TYPES = new Set([0x03, 0x04, 0x0f, 0x11, 0x81, 0x83, 0x85, 0x86, 0x87, 0x91, 0x92, 0x93, 0x94, 0x95]);
function tsPackets(buf) {
  const out = [];
  let start = 0;
  while (start < buf.length && buf[start] !== 0x47) start++;
  for (let p = start; p + 188 <= buf.length; p += 188) {
    if (buf[p] !== 0x47) continue;
    const pusi = (buf[p + 1] & 0x40) !== 0;
    const pid = ((buf[p + 1] & 0x1F) << 8) | buf[p + 2];
    const adapt = (buf[p + 3] >> 4) & 0x03;
    if (adapt === 0 || adapt === 2) continue; // no payload
    const off = adapt === 3 ? p + 5 + buf[p + 4] : p + 4;
    if (off >= p + 188) continue;
    out.push({ pid, pusi, off, end: p + 188 });
  }
  return out;
}
/**
 * True if any PMT (table_id 0x02) in the TS lists an audio elementary stream.
 * Direct PMT scan (no PAT hop): these CDNs emit PATs the strict spec walk
 * chokes on, but the PMT itself is well-formed:
 *   tid | len | pnum(2) | ver | sec | last | pcrpid(2) | pil(2) | descriptors
 *   | { stream_type, pid(2), esinfo_len(2), desc* }* | crc(4)
 */
function tsHasAudio(buf) {
  for (const pk of tsPackets(buf)) {
    if (!pk.pusi) continue;
    const ptr = buf[pk.off];
    const t = pk.off + 1 + ptr;
    if (t + 16 > pk.end || buf[t] !== 0x02) continue;
    const tab = buf.subarray(t, pk.end);
    const sectionLen = ((tab[1] & 0x0F) << 8) | tab[2];
    const psiLen = ((tab[10] & 0x0F) << 8) | tab[11];
    let es = 12 + psiLen;
    const end = Math.min(tab.length, 3 + sectionLen - 4);
    while (es + 5 <= end) {
      if (TS_AUDIO_TYPES.has(tab[es])) return true;
      const elen = ((tab[es + 3] & 0x0F) << 8) | tab[es + 4];
      es += 5 + elen;
    }
  }
  return false;
}
function mp4HasAudio(buf) {
  return buf.subarray(0, Math.min(buf.length, 400000)).toString('latin1').includes('soun');
}

function probeSegmentAudio(segUrl, headersOpts, segBytesHint) {
  const r = curlGetCached(segUrl, { ...headersOpts, range: '0-65535', timeout: 25 });
  const b = r.bytes;
  if (!b.length) return { audio: 'unknown', detail: `segmento HTTP ${r.status}` };
  if (b.subarray(4, 8).toString('latin1').match(/ftyp|moof|moov/) || /\.mp4|\.m4s|init/i.test(segUrl)) {
    // fMP4: 'soun' handler needed; for a moof-only fragment we'd need the init
    let has = mp4HasAudio(b);
    if (!has && !/ftyp/.test(b.subarray(0, 32).toString('latin1'))) {
      const initUrl = segUrl.replace(/\/[^/]*\.m4s.*$/i, '/init.mp4').replace(/\.m4s.*$/i, '_init.mp4');
      if (initUrl !== segUrl) has = mp4HasAudio(curlGetCached(initUrl, { ...headersOpts, range: '0-65535' }).bytes);
    }
    return { audio: has ? 'yes(fmp4 soun)' : 'NO(fmp4 video-only?)', detail: `seg ${b.length}B fmp4` };
  }
  if (b[0] === 0x47) return { audio: tsHasAudio(b) ? 'yes(TS PMT audio)' : 'NO(TS video-only)', detail: `seg ${b.length}B TS` };
  return { audio: 'unknown', detail: `seg ${b.length}B formato?` };
}

function validateLink(link) {
  const res = { playable: false, audio: 'n/a', detail: '' };
  const referer = link.referer || null;
  try {
    if (link.type === 'M3U8') {
      let r = curlGetCached(link.url, { referer, timeout: 30 });
      if (r.status !== 200) {
        // retry without referer (ExoPlayer sometimes sends none)
        const r2 = curlGetCached(link.url, { referer: null, timeout: 30 });
        if (r2.status === 200) r = r2;
        else { res.detail = `master HTTP ${r.status}${r.error ? ' ' + r.error : ''} body="${r.text.slice(0, 70).replace(/\s+/g, ' ')}"`; return res; }
      }
      const body = r.text;
      if (VMAP_RE.test(body.slice(0, 600)) || (body.trimStart().startsWith('<') && !body.trimStart().startsWith('#EXTM3U'))) {
        res.detail = 'AD/VMAP xml (ExoPlayer ERROR_CODE_PARSING)'; return res;
      }
      if (!body.trimStart().startsWith('#EXTM3U')) { res.detail = `no #EXTM3U (ct=${r.ct}, head="${body.slice(0, 40).replace(/\s+/g, ' ')}")`; return res; }
      const masterHasAudioGroup = /#EXT-X-MEDIA:TYPE=AUDIO/i.test(body);
      if (/#EXT-X-STREAM-INF/i.test(body)) {
        const lines = body.split(/\r?\n/);
        const li = lines.findIndex(l => /#EXT-X-STREAM-INF/i.test(l));
        const attr = lines[li];
        let vurl = lines.slice(li + 1).find(l => l.trim() && !l.startsWith('#'))?.trim();
        if (!vurl) { res.detail = 'master sin variante'; return res; }
        if (!/^https?:/.test(vurl)) vurl = new URL(vurl, new URL(link.url)).toString();
        const codecs = /CODECS="([^"]+)"/.exec(attr)?.[1] || '';
        const resl = /RESOLUTION=(\d+x\d+)/.exec(attr)?.[1] || '-';
        const vr = curlGetCached(vurl, { referer, timeout: 30 });
        if (vr.status !== 200 || !vr.text.trimStart().startsWith('#EXTM3U')) { res.detail = `variante HTTP ${vr.status}/no-EXTM3U ${resl}`; return res; }
        const segs = (vr.text.match(/#EXTINF/g) || []).length;
        if (!segs) { res.detail = `variante sin segmentos ${resl}`; return res; }
        let audio = 'unknown';
        const variantAudioGroup = /#EXT-X-MEDIA:TYPE=AUDIO/i.test(vr.text);
        if (AUDIO_CODEC_RE.test(codecs)) audio = 'yes(codecs ' + codecs.split(',')[0] + '…)';
        else if (variantAudioGroup || masterHasAudioGroup) audio = variantAudioGroup ? 'yes(variant AUDIO group)' : 'NO(master split: variante sin audio)';
        else {
          const firstSeg = (vr.text.split(/\r?\n/).find(l => l.trim() && !l.startsWith('#')) || '').trim();
          if (firstSeg) {
            const su = /^https?:/.test(firstSeg) ? firstSeg : new URL(firstSeg, new URL(vurl)).toString();
            const p = probeSegmentAudio(su, { referer });
            audio = p.audio;
          }
        }
        res.playable = true;
        res.audio = audio;
        const renditions = [...body.matchAll(/#EXT-X-STREAM-INF:([^\r\n]*)/g)].length;
        res.detail = `master(${renditions}v, first ${resl}) +variante segs=${segs}`;
        return res;
      }
      // media playlist directly
      const segs = (body.match(/#EXTINF/g) || []).length;
      if (!segs) { res.detail = 'playlist sin segmentos'; return res; }
      let audio = masterHasAudioGroup ? 'yes(AUDIO group)' : (AUDIO_CODEC_RE.test(link.codecs || '') ? 'yes(codecs)' : null);
      if (!audio) {
        const firstSeg = (body.split(/\r?\n/).find(l => l.trim() && !l.startsWith('#')) || '').trim();
        if (firstSeg) {
          const su = /^https?:/.test(firstSeg) ? firstSeg : new URL(firstSeg, new URL(link.url)).toString();
          audio = probeSegmentAudio(su, { referer }).audio;
        }
      }
      res.playable = true;
      res.audio = audio || 'unknown';
      res.detail = `media playlist segs=${segs}`;
      return res;
    }
    // VIDEO (progressive): Range 0-1023 + Content-Type
    const r = get(link.url, { referer, range: '0-1023', timeout: 30 });
    const ct = (r.ct || '').toLowerCase();
    if (r.status !== 200 && r.status !== 206) { res.detail = `HTTP ${r.status}${r.error ? ' ' + r.error : ''} ct=${ct}`; return res; }
    const isMp4 = /ftyp|1a45dfa3/i.test(r.bytes.subarray(0, 8).toString('hex')) || /ftyp/.test(r.bytes.subarray(0, 32).toString('latin1'));
    if (!ct.startsWith('video/') && !isMp4) { res.detail = `HTTP ${r.status} ct=${ct || '-'} bytes=${r.bytes.length} (no video/*)`; return res; }
    res.playable = true;
    res.audio = 'n/a(progressive)';
    res.detail = `HTTP ${r.status} ct=${ct || '-'} range 0-1023 ok${isMp4 ? ' (mp4)' : ''}`;
    return res;
  } catch (e) { res.detail = `ERR ${e.message}`; return res; }
}

function validateSub(s) {
  try {
    const r = get(s.url, { referer: DM_REFERER, timeout: 30 });
    const head = r.text.slice(0, 80).replace(/\s+/g, ' ');
    const ok = r.status === 200 && (head.includes('WEBVTT') || /-->\s/.test(r.text.slice(0, 400)) || /^\d+\s/.test(r.text));
    return { ...s, status: r.status, ok, head };
  } catch (e) { return { ...s, status: 'ERR ' + e.message, ok: false }; }
}

/* ------------------------------------------------------------------ */
/* per-episode run                                                     */
/* ------------------------------------------------------------------ */

function runEpisode(epUrl, label) {
  log(`\n### ${label}\n    ${epUrl}`);
  const ll = loadLinks(epUrl);
  log(`    loadLinks: HTTP ${ll.status}, servidores procesados=${ll.servers.length}, links emitidos=${LINKS.length}`);
  const hostOf = u => { try { return new URL(u).host; } catch { return u.slice(0, 30); } };
  for (const s of ll.servers) log(`      [${s.kind}] ${hostOf(s.url)} ${s.url.slice(0, 90)} -> ${s.links} links`);
  for (const n of new Set(NOTES)) log(`      NOTE: ${n}`);
  if (!LINKS.length) {
    return { episodio: label, servers: ll.servers.length, fuentes: 0, reproducibles: 0, veredicto: 'FAIL', motivo: ll.servers.length ? 'ningún extractor emite links (ver NOTEs)' : 'sin [data-url] ni iframe usable en el HTML' };
  }
  const rows = [];
  for (const l of LINKS) {
    const v = validateLink(l);
    l.valid = v;
    rows.push(l);
    const q = l.quality === QUAL.Unknown ? '?' : l.quality + 'p';
    log(`      ${v.playable && !String(v.audio).startsWith('NO') ? 'OK  ' : 'FAIL'} [${l.platform}] host=${hostOf(l.url)} name="${l.name}" ${q} ${l.type} audio=${v.audio}\n           url=${l.url.slice(0, 130)}`);
    if (v.detail) log(`           ${v.detail}`);
  }
  const subsOut = [];
  for (const s of SUBS) { const v = validateSub(s); subsOut.push(`${s.lang}:${v.ok ? 'OK' : 'FAIL(' + v.status + ')'}`); log(`      SUB ${v.ok ? 'OK' : 'FAIL'} ${s.lang} HTTP ${v.status} "${v.head || ''}" ${s.url.slice(0, 90)}`); }
  for (const s of DROPPED) { const v = validateSub(s); log(`      SUB(descartada por Kotlin :1067) ${s.lang} HTTP ${v.status} ${v.ok ? 'sería OK' : 'falla también'}`); }
  const playable = rows.filter(r => r.valid.playable && !String(r.valid.audio).startsWith('NO'));
  const broken = rows.filter(r => !r.valid.playable || String(r.valid.audio).startsWith('NO'));
  const byPlat = {};
  for (const r of rows) byPlat[r.platform] = (byPlat[r.platform] || 0) + 1;
  const verdict = playable.length === 0 ? 'FAIL' : broken.length === 0 ? 'PASS' : 'PARTIAL';
  const reasons = [];
  if (!playable.length) reasons.push(broken.map(b => `${b.platform}:${b.valid.detail.slice(0, 60)}`).join(' | '));
  else if (broken.length) reasons.push(`${broken.length} fuente(s) fallan: ` + broken.map(b => `${b.platform}(${b.valid.playable ? b.valid.audio : b.valid.detail.slice(0, 40)})`).join(', '));
  log(`    -> ${playable.length}/${rows.length} reproducibles con audio`);
  return {
    episodio: label,
    servers: ll.servers.length,
    fuentes: `${rows.length} (${Object.entries(byPlat).map(([k, v]) => `${k}:${v}`).join(' ')})`,
    reproducibles: `${playable.length}/${rows.length}`,
    veredicto: verdict,
    motivo: (reasons.join(' | ') || 'ok').slice(0, 220),
  };
}

/* ------------------------------------------------------------------ */
/* discovery + main                                                    */
/* ------------------------------------------------------------------ */

function discover() {
  log('=== PASO 1: getMainPage (discovery) ===');
  const home = get(`${MAIN}/`, { headers: SITE_HDRS });
  log(`GET ${MAIN}/ -> HTTP ${home.status}, ${home.text.length}B`);
  const homeCards = parseHomePageCards(home.text);
  log(`parseHomePageCards: a[data-home-episode-card] -> ${homeCards.length} cards`);
  for (const c of homeCards.slice(0, 4)) log(`   · ${c.title} E${c.ep} -> ${c.seriesUrl}`);
  log(`__NEXT_DATA__ presente: ${/id="__NEXT_DATA__"/.test(home.text)} ; RSC self.__next_f presente: ${/self\.__next_f\.push/.test(home.text)} (el Kotlin NO los usa)`);

  const dh = get(`${MAIN}/donghua`, { headers: SITE_HDRS });
  const dhCards = parseDonghuaCards(dh.text);
  log(`GET ${MAIN}/donghua -> HTTP ${dh.status}, parseDonghuaCards: ${dhCards.length} series`);

  const dir = get(`${MAIN}/directorio`, { headers: SITE_HDRS });
  const dirCards = parseDirectorioCards(dir.text);
  log(`GET ${MAIN}/directorio -> HTTP ${dir.status}, script#ssr-init.animes: ${dirCards.length}`);

  const picks = [];
  const usedSeries = new Set();

  // (a) 2 recientes de 2 series anime distintas (tarjetas del home)
  for (const c of homeCards) {
    if (picks.filter(p => p.kind === 'anime-reciente').length >= 2) break;
    if (!c.seriesUrl.includes('/anime/') || usedSeries.has(c.seriesUrl)) continue;
    const L = load(c.seriesUrl);
    const hit = L.episodes.filter(e => !e.synthesized).find(e => e.ep === c.ep);
    log(`   load(${c.seriesUrl}) -> HTTP ${L.status}, ${L.episodes.length} eps; ep ${c.ep}: ${hit ? hit.url : 'NO en grid (fallback?)'}`);
    if (hit) { usedSeries.add(c.seriesUrl); picks.push({ url: hit.url, label: `${c.title} E${hit.ep}`, kind: 'anime-reciente' }); }
  }

  // (b) 2 recientes de donghua con server list rica (data-url)
  let scanned = 0;
  let olderCandidate = null;
  for (const card of dhCards) {
    if (picks.filter(p => p.kind === 'donghua-reciente').length >= 2 && olderCandidate) break;
    if (scanned++ >= 10) break;
    if (usedSeries.has(card.url)) continue;
    const L = load(card.url);
    const real = L.episodes.filter(e => !e.synthesized);
    if (!real.length) continue;
    const latest = real.reduce((a, b) => ((a.ep || 0) >= (b.ep || 0) ? a : b));
    const epPage = get(latest.url, { headers: SITE_HDRS });
    const urls = selectAttr(epPage.text, 'data-url').map(e => e.value.trim()).filter(v => v.startsWith('http'));
    const rich = urls.some(u => /jkanime|dailymotion|ok\.ru|sfastwish|vidhide|zilla/.test(u));
    log(`   donghua ${card.url}: grid=${real.length}/${L.episodes.length} último=E${latest.ep} data-url=${urls.length}${rich ? ' (rico)' : ''}`);
    if (!urls.length) continue;
    const first = real.reduce((a, b) => ((a.ep || 1e9) <= (b.ep || 1e9) ? a : b));
    if (!olderCandidate && first.ep && first.ep <= 3 && L.episodes.length >= 8) olderCandidate = { card, first };
    if (rich && picks.filter(p => p.kind === 'donghua-reciente').length < 2) {
      usedSeries.add(card.url);
      picks.push({ url: latest.url, label: `${card.title} E${latest.ep}`, kind: 'donghua-reciente' });
    } else if (rich && !olderCandidate) olderCandidate = { card, first };
  }

  // (c) 1 episodio antiguo (ep bajo de una serie con grid >= 8 eps)
  if (olderCandidate) {
    const { card, first } = olderCandidate;
    const page = get(first.url, { headers: SITE_HDRS });
    const has = selectAttr(page.text, 'data-url').length;
    log(`   antiguo: ${card.url} E${first.ep} -> HTTP ${page.status}, data-url=${has}`);
    if (has) picks.push({ url: first.url, label: `${card.title} E${first.ep} (antiguo)`, kind: 'donghua-antiguo' });
  }
  return picks;
}

function main() {
  log('=== AnimeGratisProvider live matrix (curl-only) ===');
  const args = process.argv.slice(2).filter(a => !a.startsWith('--'));
  let picks;
  if (args.length) picks = args.map(a => ({ url: a, label: a.replace(MAIN + '/', ''), kind: 'manual' }));
  else picks = discover();

  if (picks.length < 5) log(`\nAVISO: se descubrieron ${picks.length}/5 episodios`);
  const table = [];
  for (const p of picks.slice(0, 5)) {
    try { table.push(runEpisode(p.url, p.label)); }
    catch (e) { table.push({ episodio: p.label, servers: '-', fuentes: 'ERR', reproducibles: '0', veredicto: 'FAIL', motivo: e.message }); }
  }
  log('\n=== TABLA FINAL ===');
  console.table(table);
  log(`PASS=${table.filter(t => t.veredicto === 'PASS').length} PARTIAL=${table.filter(t => t.veredicto === 'PARTIAL').length} FAIL=${table.filter(t => t.veredicto === 'FAIL').length}`);
  log(`Subtítulos emitidos a la app: ${SUBS.length}; descubiertos-y-descartados (DM, Kotlin :1067 { }): ${DROPPED.length}`);
}

main();
