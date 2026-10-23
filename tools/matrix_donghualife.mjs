#!/usr/bin/env node
/*
 * Live validation of DonghuaLifeProvider (https://donghualife.com).
 * Faithful Node port of DonghuaLifeProvider.kt + common/CommonExtractors.kt pieces it calls.
 * WebView strategies (tryWebViewResolver / fetchHtmlViaWebView / extractOkRuViaWebView) CANNOT
 * run in Node: when the RSC payload is bot-truncated we mark the episode "webview-gated" and
 * still run every HTTP/RSC/API fallback the Kotlin tries before the WebView.
 */
const MAIN = 'https://donghualife.com';
const browserUA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36';
const browserHeaders = {
  'User-Agent': browserUA,
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
  'Accept-Language': 'es-ES,es;q=0.9,en;q=0.8',
  'Upgrade-Insecure-Requests': '1',
  'Sec-Fetch-Dest': 'document', 'Sec-Fetch-Mode': 'navigate', 'Sec-Fetch-Site': 'none', 'Sec-Fetch-User': '?1',
  'Sec-Ch-Ua': '"Not?A_Brand";v="99", "Chromium";v="149", "Google Chrome";v="149"',
  'Sec-Ch-Ua-Mobile': '?0', 'Sec-Ch-Ua-Platform': '"Windows"', 'Priority': 'u=0, i',
};
const ajaxClientHints = {
  'Sec-Ch-Ua': '"Not?A_Brand";v="99", "Chromium";v="149", "Google Chrome";v="149"',
  'Sec-Ch-Ua-Mobile': '?0', 'Sec-Ch-Ua-Platform': '"Windows"', 'Priority': 'u=1, i',
};
const Q = { Unknown: -2, P240: 240, P360: 360, P480: 480, P720: 720, P1080: 1080, P1440: 1440, P2160: 2160 };
const qualName = q => (q === Q.Unknown || q == null ? 'UNKNOWN' : q + 'p');
const say = (...a) => console.error(...a);

async function req(url, { headers = {}, method = 'GET', body = null, timeout = 30000 } = {}) {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), timeout);
  try {
    const r = await fetch(url, { method, headers: { 'User-Agent': browserUA, ...headers }, body: body ?? undefined, signal: ac.signal, redirect: 'follow' });
    const buf = Buffer.from(await r.arrayBuffer());
    return { status: r.status, code: r.status, headers: Object.fromEntries(r.headers.entries()), buf, text: buf.toString('utf8'), finalUrl: r.url };
  } finally { clearTimeout(t); }
}
const getText = async (url, opts = {}) => (await req(url, opts)).text;
const resolveUrl = (base, href) => { try { return new URL(href, base).href; } catch { return href; } };
const escRe = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/* ===================== extractRscPayload (DonghuaLifeProvider.kt:101) ===================== */
function extractRscPayload(html) {
  const payloadPattern = /self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)/g;
  let out = '', m;
  while ((m = payloadPattern.exec(html)) !== null) {
    const p = m[1]
      .replaceAll('\\"', '"')
      .replaceAll('\\\\', '\\')
      .replaceAll('\\n', '\n')
      .replaceAll('\\/', '/')
      .replaceAll('\\u0026', '&');
    out += p;
  }
  return out;
}

/* ===================== RSC source/server extraction (mirrors Kotlin) ===================== */
const balancedArray = (payload, fromIdx) => { // "[" .. matching "]" starting at fromIdx
  let depth = 0, i = fromIdx;
  while (i < payload.length) {
    if (payload[i] === '[') depth++;
    else if (payload[i] === ']') { if (depth === 0) break; depth--; }
    i++;
  }
  return i;
};
function sourcePattern() {
  return /\{"id":"([^"]+)","label":"([^"]+)","name":"([^"]+)","token":"([^"]+)","type":"([^"]+)","provider":"([^"]+)"/g;
}
function extractSourcesNearEpisode(payload, episodeId) { // Kotlin:1254
  const marker = `","${episodeId}",{"sources":[`;
  const pos = payload.indexOf(marker);
  if (pos < 0) return [];
  const arrayStart = pos + marker.length;
  const end = balancedArray(payload, arrayStart);
  const arr = payload.substring(arrayStart, end);
  const out = []; let m; const re = sourcePattern();
  while ((m = re.exec(arr)) !== null) out.push({ id: m[1], label: m[2], name: m[3], token: m[4], type: m[5], provider: m[6] });
  return out;
}
function extractAllSourcesFromRsc(payload) { // Kotlin:1325 (keeps only tokens w/ derivable UUID id)
  const out = []; let m; const re = sourcePattern();
  const uuidRe = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/;
  while ((m = re.exec(payload)) !== null) {
    const src = { id: m[1], label: m[2], name: m[3], token: m[4], type: m[5], provider: m[6] };
    const cid = (uuidRe.exec(src.id) || [])[0] ?? '';
    if (cid && src.token) out.push([src, cid]);
  }
  return out;
}
const unescServerUrl = u => u.replaceAll('\\/', '/').replaceAll('\\u0026', '&').replaceAll('\\"', '"');
function extractServersForEpisode(payload, episodeId) { // Kotlin:1347
  const idPos = payload.indexOf(`"id":"${episodeId}"`);
  if (idPos < 0) return [];
  const serversStart = payload.indexOf('"servers":[', idPos);
  if (serversStart < 0) return [];
  const arrayStart = serversStart + '"servers":['.length;
  const end = balancedArray(payload, arrayStart);
  const arr = payload.substring(arrayStart, end);
  const out = []; const re = /\{"name":"([^"]+)","url":"([^"]+)"\}/g; let m;
  while ((m = re.exec(arr)) !== null) out.push([m[1], unescServerUrl(m[2])]);
  return out;
}
function extractServersByNumber(payload, seasonSlug, epNum) { // Kotlin:1379
  const re = new RegExp(`"seasonSlug":"${escRe(seasonSlug)}"[^}]*?"number":${epNum},[^}]*?"servers":\\[([^\\]]+)\\]`);
  const m = re.exec(payload); if (!m) return [];
  const out = []; const re2 = /\{"name":"([^"]+)","url":"([^"]+)"\}/g; let e;
  while ((e = re2.exec(m[1])) !== null) out.push([e[1], unescServerUrl(e[2])]);
  return out;
}
function extractServersByEpisodeTitle(payload, epNum) { // Kotlin:1399
  const re = new RegExp(`"title":"(?:Capítulo|capítulo|Episodio|episodio|Especial|especial)\\s*0*${epNum}"[^}]*?"servers":\\[([^\\]]+)\\]`);
  const m = re.exec(payload); if (!m) return [];
  const out = []; const re2 = /\{"name":"([^"]+)","url":"([^"]+)"\}/g; let e;
  while ((e = re2.exec(m[1])) !== null) out.push([e[1], unescServerUrl(e[2])]);
  return out;
}
function extractActiveEpisodeServers(payload) { // Kotlin:1418
  const activeId = /"activeEpisodeId":"([0-9a-fA-F-]{36})"/.exec(payload)?.[1];
  if (!activeId) return [];
  return extractServersForEpisode(payload, activeId);
}
function extractServersFromHtml(html) { // Kotlin:1502 v12 HTML_SCAN
  const servers = []; const seen = new Set();
  const re = /\{\\?"name\\?":"([^"\\]+)\\?",\\?"url\\?":"([^"\\]+)\\?"\}/g; let m;
  while ((m = re.exec(html)) !== null) {
    const rawUrl = unescServerUrl(m[2]).replaceAll('\\\\', '\\');
    const isVideoUrl = ['dailymotion.com', 'rumble.com', 'ok.ru', 'vk.com', 'vk.ru', 'streamable.com', 'voe.sx', 'filemoon', 'moonplayer', 'r2.cloudflarestorage', 'hcdn.dev', 'cloudflarestorage'].some(k => rawUrl.includes(k))
      || rawUrl.includes('.mp4') || rawUrl.includes('.m3u8');
    if (!isVideoUrl) continue;
    if (rawUrl.includes('/video/example') || rawUrl.includes('cdn.example.com')) continue;
    if (rawUrl.length < 20 || seen.has(rawUrl)) continue;
    seen.add(rawUrl);
    servers.push([m[1], rawUrl]);
  }
  return servers;
}
function episodeNumberFromUrl(url) { // Kotlin:1449
  let m = /\/watch\/[^?]*?-(\d+)-(\d+)(?:[?#].*)?$/.exec(url);
  if (m) return parseInt(m[2], 10);
  m = /-(\d+)-(\d+)$/.exec(url);
  return m ? parseInt(m[2], 10) : null;
}

/* ===================== POST /api/player/source (Kotlin:1631) ===================== */
async function resolveTokenViaPlayerApi(token, referer) {
  if (!token) return '';
  const headers = {
    'Accept': 'application/json, text/plain, */*', 'Accept-Language': 'es-ES,es;q=0.9,en;q=0.8',
    'Content-Type': 'application/json', 'Origin': MAIN, 'Referer': referer, 'User-Agent': browserUA,
    'Sec-Fetch-Dest': 'empty', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Site': 'same-origin', ...ajaxClientHints,
  };
  try {
    const r = await req(`${MAIN}/api/player/source`, { method: 'POST', headers, body: JSON.stringify({ token }) });
    const resp = r.text;
    if (!resp || resp === '{}' || resp.includes('"error"') || resp.includes('cdn.example.com') || resp.includes('/video/example')) {
      say(`  PLAYER_API mock/error resp (len=${resp.length}): ${resp.slice(0, 120)}`); return '';
    }
    const url = (/"url"\s*:\s*"([^"]+)"/.exec(resp) || [])[1] ?? '';
    if (url) say(`  PLAYER_API resuelto: ${url.slice(0, 140)}`);
    return url;
  } catch (e) { say('  PLAYER_API failed:', e.message); return ''; }
}

/* ===================== CommonExtractors.extractDailymotion (shared) ===================== */
function unescJson(s) {
  let out = ''; let i = 0;
  while (i < s.length) {
    const c = s[i];
    if (c === '\\' && i + 1 < s.length) {
      const n = s[i + 1];
      if (n === 'u') {
        const hex = s.substring(i + 2, Math.min(i + 6, s.length));
        const code = /^[0-9a-fA-F]{1,4}$/.test(hex) ? parseInt(hex, 16) : null;
        if (code != null && !Number.isNaN(code)) { out += String.fromCharCode(code); i += 6; } else { out += n; i += 2; }
      } else if (n === 'n') { out += '\n'; i += 2; } else if (n === 't') { out += '\t'; i += 2; }
      else if (n === 'r') { out += '\r'; i += 2; } else { out += n; i += 2; }
    } else { out += c; i++; }
  }
  return out;
}
function dmSubtitlesBlock(json) {
  const start = json.indexOf('"subtitles"'); if (start < 0) return null;
  const dataIdx = json.indexOf('"data"', start); if (dataIdx < 0) return null;
  const open = json.indexOf('{', dataIdx); if (open < 0) return null;
  let depth = 0, inStr = false, esc = false;
  for (let i = open; i < json.length; i++) {
    const ch = json[i];
    if (esc) { esc = false; continue; }
    if (ch === '\\' && inStr) { esc = true; continue; }
    if (ch === '"') inStr = !inStr;
    else if (!inStr && ch === '{') depth++;
    else if (!inStr && ch === '}') { depth--; if (depth === 0) return json.substring(open, i + 1); }
  }
  return null;
}
function dmVideoId(src) {
  return /(?:geo\.dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|dailymotion\.com\/embed\/video\/)([A-Za-z0-9]+)/i.exec(src)?.[1] ?? null;
}
async function ceExtractDailymotion(iframeSrc, embedderReferer, linkName, ctx) { // CommonExtractors.kt:160
  const vid = dmVideoId(iframeSrc) || (/^[A-Za-z0-9]+$/.test(iframeSrc.trim()) ? iframeSrc.trim() : null);
  say(`  CE-DM enter iframeSrc=${iframeSrc.slice(0, 90)} vid=${vid}`);
  if (!vid) return false;
  if (ctx.emittedDmVids.has(vid)) { say('  CE-DM dedupe hit -> true'); return true; }
  const referer = (embedderReferer || '').trim() === '' ? 'https://animexin.dev' : embedderReferer;
  try {
    const metaUrl = `https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(referer)}&integration=inline&app=com.dailymotion.neon`;
    const rr = await req(metaUrl, { headers: { 'User-Agent': browserUA, Referer: referer } });
    const raw = rr.text;
    say(`  CE-DM vid=${vid} meta http=${rr.status} len=${raw.length}`);
    const json = raw.replaceAll('\\/', '/').replaceAll('\\u0026', '&');
    const block = dmSubtitlesBlock(json);
    if (block) {
      const found = []; const re = /"(es[a-z-]*|en[a-z-]*|fr|pt|ru|tr|pl|ar|bn|fa|id|ms|it|de|hi|th|vi)":\s*\{[^{}]*?"urls":\["([^"]+)"/g; let m;
      while ((m = re.exec(block)) !== null) found.push([m[1], unescJson(m[2])]);
      const uniq = []; for (const s of found) if (!uniq.some(x => x[1] === s[1])) uniq.push(s);
      uniq.sort((a, b) => (a[0].toLowerCase().startsWith('es') ? 0 : a[0].toLowerCase().startsWith('en') ? 1 : 2) - (b[0].toLowerCase().startsWith('es') ? 0 : b[0].toLowerCase().startsWith('en') ? 1 : 2));
      for (const [code, url] of uniq) if (!ctx.emittedDmSubs.has(url)) { ctx.emittedDmSubs.add(url); ctx.subs.push({ lang: code.startsWith('es') ? 'Spanish' : code, url }); }
    }
    let found = false; const emittedUrls = new Set();
    const emit = (u, hls, q) => {
      if (!u.startsWith('http')) return;
      if (emittedUrls.has(u)) return; emittedUrls.add(u);
      const suffix = q === 1080 ? ' 1080p' : q === 720 ? ' 720p' : q === 480 ? ' 480p' : q === 360 ? ' 360p' : '';
      const display = suffix && !/\b\d{3,4}p\b/i.test(linkName) ? linkName + suffix : linkName;
      ctx.links.push({ server: linkName, name: display, url: u, type: hls ? 'M3U8' : 'VIDEO', quality: q ?? Q.Unknown, referer: 'https://www.dailymotion.com/' });
      found = true;
    };
    const tryPlaylist = (u, q) => {
      if (!u || !u.startsWith('http')) return false;
      if (/dmxleo|advertising/i.test(u) || u.includes('auth=')) return false;
      emit(u, true, q); return true;
    };
    let emittedAny = false;
    { const re = /"auto":\[\{"url":"(https:\/\/[^"]+)"/g; let m;
      while (!emittedAny && (m = re.exec(json)) !== null) if (m[1].includes('dailymotion.com')) { say('  CE-DM step1 cand:', m[1].slice(0, 110)); emittedAny = tryPlaylist(unescJson(m[1]), Q.Unknown); } }
    if (!emittedAny) { const re = /"auto":\[\{[^}]*?"type":"[^"]+","url":"(https:\/\/[^"]+)"/g; let m;
      while (!emittedAny && (m = re.exec(json)) !== null) if (m[1].includes('dailymotion.com')) { say('  CE-DM step2 cand:', m[1].slice(0, 110)); emittedAny = tryPlaylist(unescJson(m[1]), Q.Unknown); } }
    if (!emittedAny) {
      const masterAuto = (json.match(/https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i) || [null])[0];
      const dcHost = masterAuto ? ((masterAuto.match(/https:\/\/([a-z0-9.-]+)\.dailymotion\.com/i) || [])[1] ?? 'dmxleo') : null;
      const dc = dcHost && !dcHost.startsWith('cdndirector') ? dcHost : null;
      const vid2 = masterAuto ? ((masterAuto.match(/video\/([a-z0-9]+)\.m3u8/i) || [])[1] ?? null) : null;
      if (dc && vid2) for (const preset of ['H264-1280x720', 'H264-1920x1080', 'H264-854x480']) {
        if (tryPlaylist(`https://${dc}.dailymotion.com/cdn/${preset}/vCRN/mp4_h264_aac.m3u8?video=${vid2}`, 720)) { emittedAny = true; break; }
      }
    }
    if (!emittedAny) { const re = /https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"']*/g; let m;
      while ((m = re.exec(json)) !== null) { if (/\/av1|_av1_/i.test(m[0])) continue; if (!emittedAny && tryPlaylist(m[0], null)) emittedAny = true; } }
    if (emittedAny) { ctx.emittedDmVids.add(vid); return found; }
    { const re = /"(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"/g; let m;
      while ((m = re.exec(json)) !== null) {
        const h = parseInt(m[1], 10); const url = unescJson(m[3]);
        const q = Number.isNaN(h) ? Q.Unknown : h >= 1080 ? 1080 : h >= 720 ? 720 : h >= 480 ? 480 : h >= 360 ? 360 : Q.Unknown;
        if (/\/av1|_av1_/i.test(url)) continue;
        emit(url, url.includes('.m3u8'), q);
      } }
    if (!found) {
      let candidate = null;
      const m1 = /auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json);
      if (m1) candidate = unescJson(m1[1]);
      if (!candidate) candidate = (json.match(/https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i) || [null])[0];
      if (candidate && !/dmxleo|advertising/i.test(candidate) && !candidate.includes('auth=')) emit(candidate, true, Q.Unknown);
    }
    if (found) ctx.emittedDmVids.add(vid);
    say(`  CE-DM result vid=${vid} emittedAny=${emittedAny} found=${found}`);
    return found;
  } catch (e) { say('  CE-DM EXCEPTION:', e.message); return false; }
}

/* ===================== provider-local extractors (DonghuaLifeProvider.kt) ===================== */
async function extractDailymotion(embedUrl, referer, serverName, ctx) { // Kotlin:3212
  const before = ctx.links.length;
  const videoId = (embedUrl.match(/video=([A-Za-z0-9]+)/) || [])[1] ?? (embedUrl.match(/\/video\/([A-Za-z0-9]+)/) || [])[1] ?? (embedUrl.match(/\/embed\/video\/([A-Za-z0-9]+)/) || [])[1];
  if (!videoId) return false;
  say(`  extractDailymotion server=${serverName} videoId=${videoId} from ${embedUrl.slice(0, 100)}`);
  const tracking = rec => ctx.links.push(rec);
  const countBeforeEmitted = () => ctx.links.length > before;
  try {
    if (await ceExtractDailymotion(`https://www.dailymotion.com/embed/video/${videoId}`, referer, serverName, ctx)) return true;
  } catch {}
  try {
    const apiUrl = `https://www.dailymotion.com/player/metadata/video/${videoId}`;
    const jsonText = await getText(apiUrl, { headers: { 'User-Agent': browserUA, 'Accept': 'application/json', 'Accept-Language': 'en-US,en;q=0.9', Origin: 'https://www.dailymotion.com', Referer: `https://www.dailymotion.com/embed/video/${videoId}` } });
    const masterRe = /(https?:\/\/[^"'\s<>]*dailymotion\.com\/cdn\/manifest\/video\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/gi; let m;
    while ((m = masterRe.exec(jsonText)) !== null) { // NOTE: Kotlin does NOT unescape on this path
      tracking({ server: serverName, name: serverName, url: m[1], type: 'M3U8', quality: Q.Unknown, referer: 'https://www.dailymotion.com/' });
      if (countBeforeEmitted()) return true;
    }
    const seenMp4 = new Set();
    const mp4Re = /(https?:\/\/[^"'\s<>]+\.mp4[^\s"'<>]*)/gi;
    while ((m = mp4Re.exec(jsonText)) !== null) { const v = m[1]; if (!seenMp4.has(v)) { seenMp4.add(v); } }
    for (const u of seenMp4) {
      const q = u.includes('1080') ? 1080 : u.includes('720') ? 720 : u.includes('480') ? 480 : Q.Unknown;
      tracking({ server: serverName, name: `${serverName} ${Math.trunc(q / 1000)}p`, url: u, type: 'VIDEO', quality: q, referer: 'https://www.dailymotion.com' });
    }
    if (countBeforeEmitted()) return true;
  } catch {}
  ctx.delegated.push({ label: serverName, url: `https://www.dailymotion.com/embed/video/${videoId}`, why: 'loadExtractor(DM) delegado a la app' });
  return false;
}

// extractRumbleDataBlock (Kotlin:3070)
async function extractRumbleDataBlock(html, vkey, serverName, ctx) {
  const blockRe = new RegExp(`m\\.f\\["${escRe(vkey)}"\\]=`);
  const bm = blockRe.exec(html);
  if (!bm) return false;
  const start = bm.index;
  const nextMf = html.indexOf('m.f["', start + 10);
  const cands = [nextMf, start + 300000].filter(x => x > start);
  const end = cands.length ? Math.min(...cands) : html.length;
  const data = html.substring(start, Math.min(end, html.length)).replace(/\\+\//g, '/').replace(/\\+u0026/g, '&');
  let emitted = false;
  const track = rec => { ctx.links.push(rec); emitted = true; };
  let hlsMaster = /"hls"\s*:\s*\{\s*"url"\s*:\s*"(https:\/\/rumble\.com\/(?:hls-vod|live-hls-dvr)\/[^"]+\.m3u8)"/.exec(data)
    || /(https:\/\/rumble\.com\/(?:hls-vod|live-hls-dvr)\/[^"]+\.m3u8)/.exec(data);
  if (hlsMaster) {
    const u = hlsMaster[1];
    try { for (const g of await generateM3u8(serverName, u, 'https://rumble.com')) track({ ...g, referer: 'https://rumble.com' }); }
    catch { track({ server: serverName, name: serverName, url: u, type: 'M3U8', quality: Q.Unknown, referer: 'https://rumble.com' }); }
  }
  if (!hlsMaster) {
    const any = /https?:\/\/[^"\s]+?\.m3u8[^"\s]*/.exec(data);
    if (any) track({ server: serverName, name: serverName, url: any[0], type: 'M3U8', quality: Q.Unknown, referer: 'https://rumble.com' });
  }
  const tarUrls = new Map();
  const tarRe = /"(\d{3,4})"\s*:\s*\{\s*"url"\s*:\s*"(https:\/\/hugh\.cdn\.rumble\.cloud\/[^"]+?\.tar[^"]*?)"/g; let m;
  while ((m = tarRe.exec(data)) !== null) if (!tarUrls.has(m[1])) tarUrls.set(m[1], m[2]);
  for (const [q, u] of tarUrls) {
    const quality = q === '2160' ? 2160 : q === '1440' ? 1440 : q === '1080' ? 1080 : q === '720' ? 720 : q === '480' ? 480 : q === '360' ? 360 : Q.Unknown;
    track({ server: serverName, name: `${serverName} ${q}p`, url: u, type: 'M3U8', quality, referer: 'https://rumble.com' });
  }
  if (!emitted) {
    const re = /(https?:\/\/[^"]+?\.mp4[^"]*)/g;
    while ((m = re.exec(data)) !== null) track({ server: serverName, name: serverName, url: m[1], type: 'VIDEO', quality: Q.Unknown, referer: 'https://rumble.com' });
  }
  return emitted;
}
async function tryExtractRumbleFromEmbedHtml(embedUrl, vkey, serverName, ctx) { // Kotlin:2272 (okhttp route; WebView skipped)
  const marker = `m.f["${vkey}"]=`;
  try {
    const r = await req(embedUrl, { headers: { 'User-Agent': browserUA } });
    say(`  rumble embedPage(okhttp) vkey=${vkey} code=${r.status} len=${r.text.length} marker=${r.text.includes(marker)}`);
    if (r.status === 200 && r.text.includes(marker) && await extractRumbleDataBlock(r.text, vkey, serverName, ctx)) return true;
  } catch (e) { say('  rumble embedPage failed:', e.message); }
  return false; // WebView fallback impossible in Node
}
const rumbleFailedVkeys = new Map(); // v20 TTL cache (module-level, NEVER cleared across episodes by design)
async function extractRumble(embedUrl, referer, serverName, ctx) { // Kotlin:2316
  const before = ctx.links.length;
  const emitted = () => ctx.links.length > before;
  const vkey = (embedUrl.match(/\/embed\/([A-Za-z0-9_]+)/) || [])[1] ?? (embedUrl.match(/vkey=([A-Za-z0-9_]+)/) || [])[1];
  const rumbleHeaders = { 'User-Agent': browserUA, 'Accept': 'application/json, text/plain, */*', 'Accept-Language': 'en-US,en;q=0.9', Referer: 'https://rumble.com/', Origin: 'https://rumble.com' };
  if (vkey) {
    const failUntil = rumbleFailedVkeys.get(vkey);
    if (failUntil && Date.now() < failUntil) { say(`  rumble fast-skip ${vkey} (cached failure)`); return false; }
    if (await tryExtractRumbleFromEmbedHtml(embedUrl, vkey, serverName, ctx)) return true;
  }
  const gM3u8 = async (u) => { for (const g of await generateM3u8(serverName, u, 'https://rumble.com')) ctx.links.push({ ...g, referer: 'https://rumble.com' }); };
  const tarEmit = (json, { m3u8Type = false } = {}) => {
    const tb = /"ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})/.exec(json);
    if (!tb) return 0; let n = 0;
    const re = /"(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"/g; let m;
    while ((m = re.exec(tb[1])) !== null) {
      const u = m[2].replaceAll('\\/', '/').replaceAll('\\u0026', '&');
      if (!u) continue;
      const qLabel = m[1];
      const quality = qLabel === '2160' || qLabel === '1440' ? 2160 : qLabel === '1080' ? 1080 : qLabel === '720' ? 720 : qLabel === '480' ? 480 : qLabel === '360' ? 360 : Q.Unknown;
      ctx.links.push({ server: serverName, name: `${serverName} ${qLabel}p`, url: u, type: (m3u8Type ? 'M3U8' : u.includes('.m3u8') ? 'M3U8' : 'VIDEO'), quality, referer: 'https://rumble.com' });
      n++;
    }
    return n;
  };
  if (vkey) {
    try {
      const r = await req(`https://rumble.com/api/Media?vkey=${vkey}`, { headers: rumbleHeaders });
      const jsonText = r.text;
      say(`  rumble api/Media code=${r.status} len=${jsonText.length}`);
      const isHtml = jsonText.length > 200 && (/^<!doctype|^<html|<head/i.test(jsonText));
      const src = isHtml ? ((jsonText.match(/<script[^>]*class\s*=\s*"[^">]*embedded_video_data[^">]*"[^>]*>([\s\S]*?)<\/script>/i) || [null, ''])[1]) : jsonText;
      if (isHtml) say(`  rumble api/Media returned HTML; embedded_video_data len=${src.length}`);
      if (src) {
        const fixed = s => s.replaceAll('\\/', '/').replaceAll('\\u0026', '&').replaceAll('&amp;', '&');
        const auto = /"hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"/.exec(src);
        if (auto && !emitted()) { try { await gM3u8(fixed(auto[1])); if (emitted()) return true; } catch {} }
        if (!emitted()) {
          const re = /"(ld|sd|hd)"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"/g; let m;
          while ((m = re.exec(src)) !== null) { try { await gM3u8(fixed(m[2])); } catch {} }
          if (emitted()) return true;
        }
        if (!emitted() && tarEmit(src) > 0) return true;
        if (!emitted()) {
          const re = /(https?:\/\/[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)/g; let m;
          while ((m = re.exec(src)) !== null) ctx.links.push({ server: serverName, name: serverName, url: fixed(m[1]), type: m[1].includes('.m3u8') ? 'M3U8' : 'VIDEO', quality: Q.Unknown, referer: 'https://rumble.com' });
          if (emitted()) return true;
        }
      }
    } catch (e) { say('  rumble api/Media failed:', e.message); }
    if (!emitted()) { // MÉTODO 1.5 — 4 alternative endpoints
      const altHeaders = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': '*/*', 'Accept-Language': 'en-US,en;q=0.9,es;q=0.8', Referer: `${MAIN}/`, Origin: MAIN,
        'Sec-Fetch-Dest': 'script', 'Sec-Fetch-Mode': 'no-cors', 'Sec-Fetch-Site': 'cross-site',
      };
      try {
        const r = await req(`https://rumble.com/api/Media/get.json?vkey=${vkey}`, { headers: { ...altHeaders, 'Accept': 'application/json, text/plain, */*', 'Sec-Fetch-Dest': 'empty', 'Sec-Fetch-Mode': 'cors' } });
        say(`  rumble get.json code=${r.status} len=${r.text.length}`);
        if (r.status === 200 && (r.text.startsWith('{') || r.text.startsWith('['))) {
          const t = r.text;
          const auto = /"hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"/.exec(t);
          if (auto) { try { await gM3u8(auto[1].replaceAll('\\/', '/').replaceAll('\\u0026', '&')); if (emitted()) return true; } catch {} }
          if (!emitted() && tarEmit(t) > 0) return true;
          if (!emitted()) {
            const re = /(https?:\/\/[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)/g; let m;
            while ((m = re.exec(t)) !== null) ctx.links.push({ server: serverName, name: serverName, url: m[1].replaceAll('\\/', '/').replaceAll('\\u0026', '&'), type: m[1].includes('.m3u8') ? 'M3U8' : 'VIDEO', quality: Q.Unknown, referer: 'https://rumble.com' });
            if (emitted()) return true;
          }
        }
      } catch {}
      try {
        // Kotlin:2698 uses "https://rumble.com/embedjs/$vkey" (literal). Real API is embedJS/u3/?request=video&v=.
        const r = await req(`https://rumble.com/embedjs/${vkey}`, { headers: altHeaders });
        say(`  rumble embedJS(literal kotlin url) code=${r.status} len=${r.text.length}`);
        if (r.status === 200 && r.text.length > 1000) {
          const t = r.text;
          const auto = /"hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"/.exec(t);
          if (auto) { try { await gM3u8(auto[1].replaceAll('\\/', '/').replaceAll('\\u0026', '&')); } catch {} }
          tarEmit(t);
          if (ctx.links.length === before) {
            const re = /(https?:\/\/[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)/g; let m;
            while ((m = re.exec(t)) !== null) ctx.links.push({ server: serverName, name: serverName, url: m[1].replaceAll('\\/', '/').replaceAll('\\u0026', '&'), type: m[1].includes('.m3u8') ? 'M3U8' : 'VIDEO', quality: Q.Unknown, referer: 'https://rumble.com' });
          }
          if (emitted()) return true;
        }
      } catch {}
      if (!emitted()) try {
        const r = await req(`https://rumble.com/api/Media/oembed?url=${encodeURIComponent(embedUrl)}`, { headers: altHeaders });
        say(`  rumble oembed code=${r.status} len=${r.text.length}`);
        if (r.status === 200 && r.text.startsWith('{')) {
          const re = /(https?:\/\/[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)/g; let m;
          while ((m = re.exec(r.text)) !== null) { ctx.links.push({ server: serverName, name: serverName, url: m[1], type: m[1].includes('.m3u8') ? 'M3U8' : 'VIDEO', quality: Q.Unknown, referer: 'https://rumble.com' }); return true; }
        }
      } catch {}
      if (!emitted()) try {
        const r = await req(`https://rumble.com/${vkey}`, { headers: { ...altHeaders, 'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8', 'Sec-Fetch-Dest': 'document', 'Sec-Fetch-Mode': 'navigate' } });
        say(`  rumble publicPage code=${r.status} len=${r.text.length}`);
        if (r.status === 200 && r.text.length > 5000) {
          const auto = /"hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"/.exec(r.text);
          if (auto) { try { await gM3u8(auto[1].replaceAll('\\/', '/').replaceAll('\\u0026', '&')); if (emitted()) return true; } catch {} }
          const re = /(https?:\/\/[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*)/g; let m;
          while ((m = re.exec(r.text)) !== null) { const u = m[1].replaceAll('\\/', '/').replaceAll('\\u0026', '&'); if (u.includes('rmbl.ws') || u.includes('.m3u8') || u.includes('.mp4')) { ctx.links.push({ server: serverName, name: serverName, url: u, type: u.includes('.m3u8') ? 'M3U8' : 'VIDEO', quality: Q.Unknown, referer: 'https://rumble.com' }); return true; } }
        }
      } catch {}
    }
  }
  if (!emitted()) { // embed page with donghualife referer (Kotlin:2850)
    try {
      const embedHeaders = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
        Referer: `${MAIN}/`, Origin: MAIN, 'Sec-Fetch-Dest': 'iframe', 'Sec-Fetch-Mode': 'navigate', 'Sec-Fetch-Site': 'cross-site', 'Sec-Fetch-User': '?1', 'Upgrade-Insecure-Requests': '1',
      };
      const r = await req(embedUrl, { headers: embedHeaders });
      const html = r.text;
      say(`  rumble embedPage(dl referer) code=${r.status} len=${html.length}`);
      if (r.status === 200 && html.length > 5000) {
        const auto = /"hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"/.exec(html);
        if (auto) { try { await generateM3u8(serverName, auto[1].replaceAll('\\/', '/').replaceAll('\\u0026', '&'), referer).then(gl => gl.forEach(g => ctx.links.push({ ...g, referer }))); if (emitted()) return true; } catch {} }
        if (!emitted() && tarEmit(html, { m3u8Type: true }) > 0) return true;
        for (const pat of [/(["'])(https?:\/\/[^"']*rmbl\.ws[^"']*\.mp4[^"']*)\1/, /(["'])(https?:\/\/[^"']*rmbl\.ws[^"']*)\1/]) {
          const ms = [...html.matchAll(pat)];
          if (ms.length) { for (const m of ms) { const u = m[2]; const quality = u.includes('1080') ? 1080 : u.includes('720') ? 720 : u.includes('480') ? 480 : Q.Unknown; ctx.links.push({ server: serverName, name: `${serverName} ${Math.trunc(quality / 1000)}p`, url: u, type: 'VIDEO', quality, referer }); } return emitted(); }
        }
      }
    } catch {}
  }
  if (!emitted() && vkey) rumbleFailedVkeys.set(vkey, Date.now() + 10 * 60 * 1000);
  return emitted();
}

// extractStreamable (Kotlin:3292)
async function extractStreamable(embedUrl, referer, serverName, ctx) {
  try {
    const html = await getText(embedUrl, { headers: { Referer: referer, 'User-Agent': browserUA } });
    const seen = new Set();
    const re = /["'](\/\/cdn-cf-[^"']*streamable\.com\/video\/[^"']+\.mp4[^"']*)["']/g; let m;
    while ((m = re.exec(html)) !== null) {
      let u = m[1].startsWith('//') ? 'https:' + m[1] : m[1];
      u = u.replaceAll('&amp;', '&').replaceAll('\\u0026', '&').replaceAll('\\/', '/');
      if (seen.has(u)) continue; seen.add(u);
      const quality = u.includes('/video/mp4-mobile/') ? 360 : u.includes('/video/mp4/') ? 720 : Q.Unknown;
      ctx.links.push({ server: serverName, name: `${serverName} ${Math.trunc(quality / 1000)}p`, url: u, type: 'VIDEO', quality, referer: 'https://streamable.com/' });
    }
    if (seen.size) return true;
  } catch {}
  ctx.delegated.push({ label: serverName, url: embedUrl, why: 'loadExtractor(streamable) delegado a la app' });
  return false;
}

// CE.extractOkRu (CommonExtractors.kt:441)
async function ceExtractOkRu(embedUrl0, siteReferer, linkName, ctx) {
  try {
    const url = embedUrl0.startsWith('//') ? 'https:' + embedUrl0 : embedUrl0;
    const html = await getText(url, { headers: { Referer: siteReferer, 'User-Agent': browserUA } });
    const decodeUnicode = s => s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
    const dm = html.match(/data-options="([^"]+)"/);
    if (!dm) return false;
    const options = decodeUnicode(dm[1].replaceAll('&quot;', '"').replaceAll('\\/', '/'));
    let found = false;
    const re = /"(?:[a-zA-Z]*ManifestUrl|ondemandHls)":"([^"]+)"/g;
    const cands = [...options.matchAll(re)].map(x => x[1]);
    for (const cand of cands) {
      let ok = false;
      try { ok = (await getText(cand, { headers: { Referer: url, 'User-Agent': browserUA } })).trimStart().startsWith('#EXTM3U'); } catch {}
      if (!ok) continue;
      try { for (const g of await generateM3u8(linkName, cand, url)) ctx.links.push({ ...g, referer: url }); found = true; } catch {}
      break; // firstOrNull semantics
    }
    const pRe = /"name":"(mobile|lowest|low|sd|hd|full|super)","url":"([^"]+)"/g; let pm;
    while ((pm = pRe.exec(options)) !== null) {
      const progressive = decodeUnicode(pm[2]);
      if (!progressive.startsWith('http')) continue;
      const full = progressive.startsWith('//') ? 'https:' + progressive : progressive;
      const quality = pm[1] === 'full' || pm[1] === 'super' ? 1080 : pm[1] === 'hd' ? 720 : pm[1] === 'sd' ? 480 : 360;
      ctx.links.push({ server: linkName, name: linkName, url: full, type: 'VIDEO', quality, referer: url });
      found = true;
    }
    return found;
  } catch { return false; }
}
// local extractOkRu (Kotlin:3333): CE first, then own data-options / metadata / og:video / direct (WebView skipped)
async function extractOkRu(videoUrl, referer, serverName, ctx) {
  const before = ctx.links.length;
  try { if (await ceExtractOkRu(videoUrl, referer, serverName, ctx)) return true; } catch {}
  try {
    const r = await req(videoUrl, { headers: { Referer: referer, 'User-Agent': browserUA } });
    const html = r.text;
    say(`  okRu v19: code=${r.status} len=${html.length}`);
    const dm = html.match(/data-options="([^"]+)"/);
    if (dm) {
      const optionsJson = dm[1].replaceAll('&quot;', '"').replaceAll('&amp;', '&');
      const unescaped = optionsJson.replace(/\\+u0026/g, '&').replace(/\\+\//g, '/');
      let emittedAny = false;
      for (const m of unescaped.matchAll(/(https?:\/\/[^"]+\.m3u8[^"]*)/g)) {
        try { for (const g of await generateM3u8(serverName, m[1], videoUrl)) { ctx.links.push({ ...g, referer: videoUrl }); emittedAny = true; } } catch {}
      }
      if (emittedAny) return true;
      for (const m of unescaped.matchAll(/(https?:\/\/[^"]+\.mp4[^"]*)/g)) {
        ctx.links.push({ server: serverName, name: serverName, url: m[1], type: 'VIDEO', quality: Q.Unknown, referer: videoUrl });
        emittedAny = true;
      }
      if (emittedAny) return true;
    }
    if (ctx.links.length === before) {
      const mid = (videoUrl.match(/video(?:embed)?\/(\d+)/) || [])[1];
      if (mid) { // extractOkRuViaMetadata (Kotlin:3418)
        try {
          const meta = await req(`https://ok.ru/dk?cmd=videoPlayerMetadata&mid=${mid}`, { method: 'POST', headers: { 'User-Agent': browserUA, Referer: videoUrl, 'Accept': '*/*', 'Content-Type': 'application/x-www-form-urlencoded' } });
          if (meta.text.trimStart().startsWith('{')) {
            const qMap = { full: 1080, hd: 720, sd: 480, low: 360, lowest: 240, mobile: 240 };
            const videosBlock = (/"videos"\s*:\s*(\[.*?\])/s.exec(meta.text) || [null, null])[1];
            const text = videosBlock ?? meta.text;
            let n = 0;
            for (const m of text.matchAll(/"name"\s*:\s*"([a-z]+)"\s*,\s*"url"\s*:\s*"([^"]+)"/g)) {
              const u = m[2].replace(/\\+\//g, '/').replace(/\\+u0026/g, '&');
              if (!u.startsWith('http')) continue;
              ctx.links.push({ server: serverName, name: `${serverName} ${m[1]}`, url: u, type: 'VIDEO', quality: qMap[m[1]] ?? Q.Unknown, referer: videoUrl });
              n++;
            }
            if (n) return true;
          }
        } catch {}
      }
      const og = html.match(/<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']/);
      if (og) { ctx.links.push({ server: serverName, name: serverName, url: og[1], type: 'VIDEO', quality: Q.Unknown, referer: videoUrl }); return true; }
      const gen = html.match(/(https?:\/\/[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)/);
      if (gen) { ctx.links.push({ server: serverName, name: serverName, url: gen[1], type: gen[1].includes('.m3u8') ? 'M3U8' : 'VIDEO', quality: Q.Unknown, referer: videoUrl }); return true; }
      say('  okRu: WebView fallback impossible in Node (extractOkRuViaWebView skipped)');
    }
    return ctx.links.length > before;
  } catch { return false; }
}

// extractVidhide (Kotlin:3619) — Dean Edwards unpacker
async function extractVidhide(embedUrl, referer, serverName, ctx) {
  const before = ctx.links.length;
  try {
    const html = await getText(embedUrl, { headers: { Referer: referer, 'User-Agent': browserUA } });
    const pm = /\}\('(.*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)/s.exec(html);
    if (!pm) { say('  vidhide: packer not found'); return false; }
    const p = pm[1]; const base = parseInt(pm[2], 10); const words = pm[4].split('|');
    const baseChars = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
    const decodeWord = w => {
      if (!(base >= 2 && base <= baseChars.length)) { const n = parseInt(w, base); return Number.isNaN(n) ? null : n; }
      let n = 0;
      for (const c of w) { const d = baseChars.indexOf(c); if (d < 0 || d >= base) return null; n = n * base + d; }
      return n;
    };
    const unpacked = p.replace(/[0-9a-zA-Z]+/g, w => { const idx = decodeWord(w); return idx != null && idx >= 0 && idx < words.length ? (words[idx] || w) : w; });
    const hlsUrls = new Map();
    for (const m of unpacked.matchAll(/(https?:\/\/[^"'\s\\]+\.m3u8[^"'\s\\]*)/g)) {
      const u = m[1].replaceAll('\\/', '/').replace(/\\+u0026/g, '&').replaceAll('&amp;', '&');
      if (u.includes('.mp4')) continue;
      const q = u.includes('1080') ? 1080 : u.includes('720') ? 720 : u.includes('480') ? 480 : Q.Unknown;
      if (!hlsUrls.has(u)) hlsUrls.set(u, q);
    }
    for (const [u, q] of hlsUrls) {
      try { for (const g of await generateM3u8(serverName, u, embedUrl)) ctx.links.push({ ...g, referer: embedUrl }); }
      catch { ctx.links.push({ server: serverName, name: q === Q.Unknown ? serverName : `${serverName} ${Math.trunc(q / 1000)}p`, url: u, type: 'M3U8', quality: q, referer: embedUrl }); }
    }
    if (ctx.links.length === before) {
      for (const m of unpacked.matchAll(/(https?:\/\/[^"'\s\\]+\.mp4[^"'\s\\]*)/g)) {
        const u = m[1].replaceAll('\\/', '/').replaceAll('\\u0026', '&').replaceAll('&amp;', '&');
        const q = u.includes('1080') ? 1080 : u.includes('720') ? 720 : u.includes('480') ? 480 : Q.Unknown;
        ctx.links.push({ server: serverName, name: `${serverName} ${Math.trunc(q / 1000)}p`, url: u, type: 'VIDEO', quality: q, referer: embedUrl });
      }
    }
    return ctx.links.length > before;
  } catch (e) { say('  vidhide error:', e.message); return false; }
}

// extractOdysee RPC (Kotlin:1544)
async function extractOdysee(embedUrl, referer, serverName, ctx) {
  try {
    const path = (embedUrl.match(/\/embed\/([^?]+)/) || [])[1] ?? '';
    if (!path) return false;
    const decodedPath = decodeURIComponent(path);
    const claimUri = 'lbry://' + decodedPath;
    const claimName = decodedPath.substring(decodedPath.lastIndexOf('/') + 1).replace(/:[^:]*$/, '');
    const sig = (embedUrl.match(/[?&]signature=([A-Za-z0-9]+)/) || [])[1] ?? '';
    const sigTs = (embedUrl.match(/[?&]signature_ts=(\d+)/) || [])[1] ?? '';
    const rpcHeaders = { 'User-Agent': browserUA, 'Accept': '*/*', 'Content-Type': 'application/json', Origin: 'https://odysee.com', Referer: 'https://odysee.com/' };
    const resolveResp = (await req('https://api.na-backend.odysee.com/api/v1/proxy?m=resolve', { method: 'POST', headers: rpcHeaders, body: JSON.stringify({ jsonrpc: '2.0', method: 'resolve', params: { urls: [claimUri] } }) })).text;
    const claimId = (/"claim_id"\s*:\s*"([0-9a-f]{40})"/.exec(resolveResp) || [])[1] ?? '';
    if (!claimId || !claimName) { say(`  odysee resolve sin claim: ${resolveResp.slice(0, 120)}`); return false; }
    const getResp = (await req('https://api.na-backend.odysee.com/api/v1/proxy?m=get', { method: 'POST', headers: rpcHeaders, body: JSON.stringify({ jsonrpc: '2.0', method: 'get', params: { uri: `lbry://${claimName}#${claimId}`, signature: sig, signature_ts: sigTs } }) })).text;
    const streamingUrl = (/"streaming_url"\s*:\s*"([^"]+)"/.exec(getResp) || [])[1]?.replaceAll('\\u002F', '/').replaceAll('\\/', '/') ?? '';
    if (!streamingUrl) { say(`  odysee get sin streaming_url: ${getResp.slice(0, 140)}`); return false; }
    ctx.links.push({ server: serverName, name: serverName, url: streamingUrl, type: 'VIDEO', quality: Q.Unknown, referer: 'https://odysee.com/' });
    return true;
  } catch (e) { say('  odysee error:', e.message); return false; }
}

/* ===================== generateM3u8 (CloudStream M3u8Helper) ===================== */
async function generateM3u8(name, url, referer) {
  const body = await getText(url, { headers: { Referer: referer, 'User-Agent': browserUA } });
  const links = [];
  if (body.includes('#EXT-X-STREAM-INF')) {
    const lines = body.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const inf = lines[i].match(/#EXT-X-STREAM-INF:[^\n]*BANDWIDTH=(\d+)/);
      if (inf && lines[i + 1] && !lines[i + 1].startsWith('#')) {
        const res = lines[i].match(/RESOLUTION=(\d+)x(\d+)/);
        let q = res ? parseInt(res[2], 10) : Q.Unknown;
        if (!res) { const bw = parseInt(inf[1], 10); q = bw >= 2500000 ? 1080 : bw >= 1200000 ? 720 : bw >= 600000 ? 480 : bw >= 350000 ? 360 : Q.Unknown; }
        links.push({ server: name, name, url: resolveUrl(url, lines[i + 1].trim()), type: 'M3U8', quality: q, referer });
      }
    }
    if (!links.length) links.push({ server: name, name, url, type: 'M3U8', quality: Q.Unknown, referer });
  } else links.push({ server: name, name, url, type: 'M3U8', quality: Q.Unknown, referer });
  return links;
}

/* ===================== emitEpisodeServers (Kotlin:1676) ===================== */
async function emitEpisodeServers(servers, referer, ctx) {
  let anyEmitted = false;
  const cdnHeaders = { Origin: MAIN, 'User-Agent': browserUA };
  for (const [serverName0, serverUrl] of servers) {
    const before = ctx.links.length;
    const emittedHere = () => ctx.links.length > before;
    const name = (serverName0 || '').trim() || 'Server';
    const serverUrlFixed = serverUrl.endsWith('.m3') ? serverUrl + 'u8'
      : serverUrl.endsWith('.m3u') ? serverUrl + '8'
        : (serverUrl.includes('chunklist.m3') && !serverUrl.includes('chunklist.m3u8')) ? serverUrl.replace('chunklist.m3', 'chunklist.m3u8')
          : serverUrl;
    const normalizedUrl = serverUrlFixed.includes('ok.ru/videoembed/') ? serverUrlFixed.replace('ok.ru/videoembed/', 'www.ok.ru/video/')
      : (serverUrlFixed.includes('ok.ru') && !serverUrlFixed.includes('www.ok.ru')) ? serverUrlFixed.replace('ok.ru', 'www.ok.ru')
        : serverUrlFixed;
    void cdnHeaders;
    try {
      if (serverUrlFixed.includes('vidhide') || serverUrlFixed.includes('morencius.com')) {
        if (!await extractVidhide(serverUrlFixed, referer, name, ctx)) ctx.delegated.push({ label: name, url: serverUrlFixed, why: 'loadExtractor(vidhide) delegado' });
      } else if (serverUrlFixed.includes('rumble.com')) {
        if (!await extractRumble(serverUrlFixed, referer, name, ctx) && !emittedHere()) ctx.delegated.push({ label: name, url: serverUrlFixed, why: 'loadExtractor(rumble) delegado' });
      } else if (serverUrlFixed.includes('dailymotion.com') || serverUrlFixed.includes('geo.dailymotion.com')) {
        if (!await extractDailymotion(serverUrlFixed, referer, name, ctx) && !emittedHere()) ctx.delegated.push({ label: name, url: serverUrlFixed, why: 'loadExtractor(DM) delegado' });
      } else if (serverUrlFixed.includes('streamable.com')) {
        if (!await extractStreamable(serverUrlFixed, referer, name, ctx)) ctx.delegated.push({ label: name, url: serverUrlFixed, why: 'loadExtractor(streamable) delegado' });
      } else if (serverUrlFixed.includes('ok.ru')) {
        if (!await extractOkRu(serverUrlFixed, referer, name, ctx)) ctx.delegated.push({ label: name, url: normalizedUrl, why: 'loadExtractor(okru) delegado' });
      } else if (serverUrlFixed.includes('odysee.com')) {
        if (!await extractOdysee(serverUrlFixed, referer, name, ctx)) ctx.delegated.push({ label: name, url: serverUrlFixed, why: 'loadExtractor(odysee) delegado' });
      } else if (serverUrlFixed.endsWith('.mp4') || serverUrlFixed.endsWith('.m3u8') || serverUrlFixed.includes('chunklist') || serverUrlFixed.includes('index.m3u8') || serverUrlFixed.includes('r2.cloudflarestorage') || serverUrlFixed.includes('hcdn.dev') || serverUrlFixed.includes('donghualife.com/video') || serverUrlFixed.includes('donghualife.com/episodes') || serverUrlFixed.includes('donghualife.com/movie')) {
        const type = (serverUrlFixed.includes('.m3u8') || serverUrlFixed.includes('chunklist') || serverUrlFixed.includes('index.m3u8')) ? 'M3U8' : 'VIDEO';
        ctx.links.push({ server: name, name, url: serverUrlFixed, type, quality: Q.Unknown, referer, headers: cdnHeaders });
      } else if (serverUrlFixed.includes('voe.sx')) {
        ctx.delegated.push({ label: name, url: serverUrlFixed, why: 'loadExtractor(voe) delegado' });
      } else if (serverUrlFixed.includes('filemoon') || serverUrlFixed.includes('moonplayer')) {
        ctx.delegated.push({ label: name, url: serverUrlFixed, why: 'loadExtractor(filemoon) delegado' });
      } else {
        ctx.delegated.push({ label: name, url: serverUrlFixed, why: 'loadExtractor(otros) delegado' });
      }
    } catch (e) { say(`  emit error ${name}: ${e.message}`); }
    if (emittedHere()) anyEmitted = true;
  }
  return anyEmitted;
}

/* ===================== loadEpisodeLinks (Kotlin:1054, non-WebView chain) ===================== */
async function loadLinks(data, ctx) {
  ctx.emittedDmVids.clear(); ctx.emittedDmSubs.clear(); // Kotlin:976 — cleared per episode
  const cleanUrl = data.includes('##') ? data.split('##')[0] : data;
  const r0 = await req(cleanUrl, { headers: browserHeaders, timeout: 60000 });
  let rsc = extractRscPayload(r0.text);
  ctx.webviewGated = rsc.trim() === '' || (rsc.length < 50000 && !rsc.includes('"activeEpisodeId":') && !rsc.includes('"sources":') && !rsc.includes('"token":'));
  say(`  loadLinks http=${r0.status} htmlLen=${r0.text.length} rscLen=${rsc.length} webviewGated=${ctx.webviewGated}`);
  if (ctx.webviewGated) say('  BOT_DETECTED: RSC reducido -> en la app iría a WebView; Node sigue con los fallbacks HTTP');

  const url = cleanUrl;
  const activeEpId = (/"activeEpisodeId":"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"/.exec(rsc) || [])[1] ?? '';
  let anyEmitted = false;

  if (rsc.length < 50000) { // v12 HTML_SCAN (runs BEFORE tokens; early-return if it emits)
    const htmlServers = extractServersFromHtml(r0.text);
    say(`  HTML_SCAN servers=${htmlServers.length}`);
    if (htmlServers.length && await emitEpisodeServers(htmlServers, url, ctx)) return true;
  }
  if (activeEpId) { // tokens first
    const epSources = extractSourcesNearEpisode(rsc, activeEpId);
    say(`  epSources with tokens: ${epSources.length} [${epSources.map(s => s.label).join(', ')}]`);
    for (const src of epSources) {
      if (!src.token) continue;
      const resolved = await resolveTokenViaPlayerApi(src.token, url);
      if (!resolved) continue;
      if (await emitEpisodeServers([[src.label, resolved]], url, ctx)) anyEmitted = true;
    }
  }
  if (!anyEmitted && activeEpId) {
    const epServers = extractServersForEpisode(rsc, activeEpId);
    say(`  direct servers: ${epServers.length} [${epServers.map(s => s[0]).join(', ')}]`);
    if (epServers.length && await emitEpisodeServers(epServers, url, ctx)) anyEmitted = true;
  }
  if (!anyEmitted) {
    const urlPath = url.substring(url.indexOf('/watch/') + 7);
    const um = /^(.+)-(\d+)-(\d+)$/.exec(urlPath);
    if (um) {
      const seasonSlug = um[1]; const epNum = parseInt(um[3], 10);
      if (epNum > 0) {
        let eps = extractServersByNumber(rsc, seasonSlug, epNum);
        if (!eps.length) eps = extractServersByEpisodeTitle(rsc, epNum);
        if (eps.length && await emitEpisodeServers(eps, url, ctx)) anyEmitted = true;
      }
    } else {
      const act = extractActiveEpisodeServers(rsc);
      if (act.length && await emitEpisodeServers(act, url, ctx)) anyEmitted = true;
    }
  }
  if (!anyEmitted) { // v17 sweep all tokens in payload
    const all = extractAllSourcesFromRsc(rsc);
    say(`  v17 sweep: ${all.length} sources with tokens`);
    for (const [src] of all) {
      if (!src.token) continue;
      const resolved = await resolveTokenViaPlayerApi(src.token, url);
      if (resolved && await emitEpisodeServers([[src.label, resolved]], url, ctx)) anyEmitted = true;
    }
  }
  if (!anyEmitted && ctx.webviewGated) say('  WEBVIEW-GATED: sin fuentes por HTTP; la app dependería del WebView');
  return anyEmitted;
}

/* ===================== seasons (Kotlin:886) ===================== */
function extractSeasonsFromPayload(payload) {
  const seasons = [];
  const sStart = payload.indexOf('"seasons":[');
  if (sStart < 0) return seasons;
  const start = sStart + '"seasons":['.length;
  const end = balancedArray(payload, start);
  const arr = payload.substring(start, end);
  const pattern = /\{"id":"[^"]+","slug":"([^"]+)","(?:label|name)":"([^"]+)"(,"year":(null|"[^"]*"),"isSpecial":(true|false),"order":(\d+)|,"coverImage":"[^"]*","isSpecial":(true|false),"episodeCount":(\d+))/g;
  let m;
  while ((m = pattern.exec(arr)) !== null) {
    const slug = m[1], label = m[2];
    const isSpecial = m[5] ? m[5].toLowerCase() === 'true' : m[7].toLowerCase() === 'true';
    const episodeCount = (m[6] ? parseInt(m[6], 10) : (m[8] ? parseInt(m[8], 10) : 0)) || 0; // NB: Kotlin uses g6=order as count on watch-shape
    const order = m[6] ? parseInt(m[6], 10) : seasons.size + 1;
    const nextIdx = arr.indexOf('"slug":"', m.index + 1);
    const block = arr.substring(m.index, nextIdx < 0 || nextIdx <= m.index ? arr.length : nextIdx);
    const iPos = block.indexOf('"initialEpisodes":[');
    let episodeNumbers = [];
    if (iPos >= 0) {
      const as = iPos + '"initialEpisodes":['.length;
      const ae = balancedArray(block, as);
      episodeNumbers = [...block.substring(as, ae).matchAll(/"number":(\d+)/g)].map(x => parseInt(x[1], 10));
    }
    seasons.push({ slug, label, episodeCount, isSpecial, firstEpNumber: episodeNumbers[0] ?? 0, episodeNumbers, order });
  }
  return seasons;
}

/* ===================== discovery ===================== */
async function discoverSeries() {
  const html = await getText(`${MAIN}/`, { headers: browserHeaders, timeout: 60000 });
  const rsc = extractRscPayload(html);
  const hrefMap = new Map();
  for (const m of rsc.matchAll(/"watchHref":"([^"]+)","seriesHref":"([^"]+)"/g)) hrefMap.set(m[1], m[2]);
  say(`  home: html=${html.length} rsc=${rsc.length} hrefMap=${hrefMap.size}`);
  const regionAt = html.indexOf('latest-episodes-scroll');
  const region = regionAt < 0 ? html : html.substring(regionAt, regionAt + 200000);
  const watchHrefs = [...region.matchAll(/href="(\/watch\/[^"]+)"/g)].map(m => m[1]);
  const series = [];
  const seen = new Set();
  for (const wh of watchHrefs) {
    let target = hrefMap.get(wh);
    if (!target) {
      const uuidPref = wh.match(/^\/watch\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/);
      if (uuidPref) for (const [k, v] of hrefMap) if (k.startsWith(uuidPref[0])) { target = v; break; }
    }
    target = target ?? wh;
    if (!target.startsWith('/series/')) continue;
    if (seen.has(target)) continue;
    seen.add(target);
    series.push(target);
    if (series.length >= 3) break;
  }
  return series.map(s => `${MAIN}${s}`);
}
async function loadSeriesEpisodes(seriesUrl) { // load() port -> episode list
  const html = await getText(seriesUrl, { headers: browserHeaders, timeout: 60000 });
  const rsc = extractRscPayload(html);
  const seasons = extractSeasonsFromPayload(rsc).sort((a, b) => a.order - b.order);
  const out = [];
  seasons.forEach((season, idx) => {
    const seasonNum = idx + 1;
    const nums = season.episodeNumbers.length ? season.episodeNumbers : Array.from({ length: season.episodeCount }, (_, i) => (season.firstEpNumber > 0 ? season.firstEpNumber : 1) + i);
    for (const epNum of nums) out.push({ url: `${MAIN}/watch/${season.slug}-${epNum}`, season: seasonNum, ep: epNum });
  });
  return out.sort((a, b) => a.season - b.season || a.ep - b.ep);
}

/* ===================== validation (same engine as matrix_animexin.mjs) ===================== */
async function fetchRange(url, referer, headers, range = 'bytes=0-200000') {
  return req(url, { headers: { Referer: referer, Range: range, 'User-Agent': browserUA, ...(headers || {}) } });
}
function tsStreamTypes(bytes) {
  const PKT = 188; let pmtPid = null;
  let aligned = 0;
  while (aligned + PKT < bytes.length && (bytes[aligned] !== 0x47 || bytes[aligned + PKT] !== 0x47)) aligned++;
  if (bytes[aligned] !== 0x47) return null;
  for (let p = aligned; p + PKT <= bytes.length; p += PKT) {
    if (bytes[p] !== 0x47) continue;
    const pid = ((bytes[p + 1] & 0x1F) << 8) | bytes[p + 2];
    const pusi = (bytes[p + 1] >> 6) & 1;
    const afc = (bytes[p + 3] >> 3) & 3; // adaptation_field_control = bits 4-3 of byte 3
    if (afc === 0 || afc === 1) continue;
    let pay = p + 4;
    if (afc === 3) pay += 1 + bytes[p + 4];
    if (pay >= bytes.length) continue;
    // ok.ru-style muxers omit the pointer_field: section may start directly at payload.
    const sectAt = tid => {
      const ok = i => i + 12 < bytes.length && bytes[i] === tid && (bytes[i + 1] & 0x80);
      if (ok(pay)) return pay;
      const ptr = bytes[pay]; const i2 = pay + 1 + ptr;
      if (ptr > 0 && ok(i2)) return i2;
      if (ptr === 0 && ok(pay + 1)) return pay + 1; // standard muxer, pointer_field=0
      return -1;
    };
    if (pid === 0 && pusi) {
      const i = sectAt(0x00);
      if (i >= 0 && i + 11 < bytes.length) pmtPid = ((bytes[i + 10] & 0x1F) << 8) | bytes[i + 11];
    } else if (pmtPid !== null && pid === pmtPid && pusi) {
      const i = sectAt(0x02);
      if (i < 0 || i + 15 >= bytes.length) continue;
      const sectLen = ((bytes[i + 1] & 0x0F) << 8) | bytes[i + 2];
      const endSec = Math.min(i + 3 + sectLen - 4, bytes.length - 5);
      const pil = ((bytes[i + 10] & 0x0F) << 8) | bytes[i + 11];
      let j = i + 12 + pil; const types = [];
      while (j + 4 <= endSec) { const st = bytes[j]; const elen = ((bytes[j + 3] & 0x0F) << 8) | bytes[j + 4]; types.push(st); j += 5 + elen; }
      if (types.length) return types;
    }
  }
  return null;
}
const TS_AUDIO = new Set([0x03, 0x04, 0x0f, 0x11, 0x81, 0x82, 0x87, 0x06]);
const TS_VIDEO = new Set([0x01, 0x02, 0x10, 0x1b, 0x24, 0x15, 0x27]);
function mp4Handlers(bytes) {
  const out = []; const hdlr = Buffer.from('hdlr'); let idx = 0;
  while ((idx = bytes.indexOf(hdlr, idx)) !== -1) { if (idx + 16 <= bytes.length) out.push(bytes.subarray(idx + 12, idx + 16).toString('latin1')); idx += 4; }
  return out;
}
async function validateM3u8(link) {
  let master;
  try { master = await req(link.url, { headers: { Referer: link.referer ?? `${MAIN}/`, ...link.headers } }); }
  catch (e) { return { ok: false, why: `master GET fail: ${e.message}` }; }
  const body = master.text ?? '';
  if (!body.trimStart().startsWith('#EXTM3U')) return { ok: false, why: `no #EXTM3U (status ${master.status})` };
  if (/<vmap|<VMAP/.test(body)) return { ok: false, why: 'VMAP ad XML' };
  const isMaster = body.includes('#EXT-X-STREAM-INF');
  const masterHasAudioGroup = /#EXT-X-MEDIA:[^\n]*TYPE=AUDIO/.test(body);
  let variantBody, variantUrl;
  if (isMaster) {
    const lines = body.split(/\r?\n/); let first = null;
    for (let i = 0; i < lines.length; i++) if (lines[i].startsWith('#EXT-X-STREAM-INF') && lines[i + 1] && !lines[i + 1].startsWith('#')) { first = lines[i + 1].trim(); break; }
    if (!first) return { ok: false, why: 'master sin variantes' };
    variantUrl = resolveUrl(master.finalUrl || link.url, first);
    try { variantBody = (await req(variantUrl, { headers: { Referer: link.referer ?? `${MAIN}/`, ...link.headers } })).text; }
    catch (e) { return { ok: false, why: `variant GET fail: ${e.message}` }; }
  } else { variantBody = body; variantUrl = link.url; }
  if (/<vmap|<VMAP|<VAST/.test(variantBody)) return { ok: false, why: 'VMAP/VAST ad xml' };
  const segLine = variantBody.split(/\r?\n/).find(l => l.trim() !== '' && !l.startsWith('#'));
  if (!segLine || !variantBody.includes('#EXTINF')) return { ok: false, why: 'no media playlist (sin segmentos)' };
  const segUrl = resolveUrl(variantUrl, segLine.trim());
  const mapUri = (variantBody.match(/#EXT-X-MAP:[^\n]*URI="([^"]+)"/) || [])[1];
  let audio = masterHasAudioGroup, how = 'master AUDIO group';
  if (!audio) {
    const s = await fetchRange(segUrl, link.referer ?? `${MAIN}/`, link.headers);
    const bytes = s.buf;
    if (bytes.length && bytes[0] === 0x47) {
      const types = tsStreamTypes(bytes);
      const hasV = !!types && types.some(t => TS_VIDEO.has(t));
      audio = !!types && types.some(t => TS_AUDIO.has(t));
      how = `TS pid-types=${types ? types.map(t => '0x' + t.toString(16)).join(',') : 'PAT/PMT no encontrado'}`;
      if (!types) return { ok: false, why: 'TS sin PAT/PM parseable (no verificable)' };
      if (!hasV && !audio) return { ok: false, why: 'segmentos TS sin tracks A/V' };
    } else if (mapUri || /\.m4s($|\?)/.test(segUrl)) {
      const initBytes = mapUri ? (await fetchRange(resolveUrl(variantUrl, mapUri), link.referer ?? `${MAIN}/`, link.headers)).buf : bytes;
      const handlers = mp4Handlers(initBytes);
      audio = handlers.includes('soun');
      how = `fMP4 hdlr=[${handlers.join(',')}]`;
    } else { audio = true; how = 'magic desconocido'; }
  }
  if (!audio) return { ok: false, why: `SIN AUDIO: video-only HLS (${how})` };
  return { ok: true, why: how };
}
async function validateVideo(link) {
  try {
    const r = await fetchRange(link.url, link.referer ?? `${MAIN}/`, link.headers, 'bytes=0-31');
    const ct = (r.headers['content-type'] ?? '').toLowerCase();
    if (!(r.status === 200 || r.status === 206)) return { ok: false, why: `HTTP ${r.status}` };
    if (ct.startsWith('video/')) return { ok: true, why: ct };
    const magic = r.buf.length >= 12 && r.buf.subarray(4, 8).toString('latin1') === 'ftyp' ? 'ftyp' : (r.buf[0] === 0x47 ? 'TS' : '');
    if (magic) return { ok: true, why: `${ct || 'no-ct'} + magic ${magic}` };
    return { ok: false, why: `content-type ${ct || 'desconocido'}` };
  } catch (e) { return { ok: false, why: `GET fail: ${e.message}` }; }
}

/* ===================== main ===================== */
(async () => {
  say('== DonghuaLife matrix: discovering series from home #latest-episodes-scroll ==');
  const seriesUrls = await discoverSeries();
  say(`  series discovered: ${seriesUrls.join(', ')}`);
  const chosen = [];
  const perSeries = [];
  for (const sUrl of seriesUrls) {
    const eps = await loadSeriesEpisodes(sUrl);
    say(`  ${sUrl}: ${eps.length} episodes`);
    perSeries.push([sUrl, eps]);
  }
  for (const [sUrl, eps] of perSeries) if (eps.length) chosen.push({ ...eps[eps.length - 1], series: sUrl, origin: 'RECENT' });
  for (const [sUrl, eps] of perSeries) if (eps.length > 5) chosen.push({ ...eps[0], series: sUrl, origin: 'OLD' });
  const five = chosen.slice(0, 5);
  if (five.length < 5) say(`  WARNING: only ${five.length} episodes discovered`);

  const rows = [];
  for (const ep of five) {
    say(`\n== loadLinks: ${ep.url} (${ep.origin}) ==`);
    const ctx = { links: [], subs: [], delegated: [], emittedSubs: new Set(), emittedDmVids: new Set(), emittedDmSubs: new Set(), webviewGated: false };
    let any = false;
    try { any = await loadLinks(ep.url, ctx); } catch (e) { say('  loadLinks fatal:', e.message); }
    say(`  found=${any} links=${ctx.links.length} subs=${ctx.subs.length} delegated=${ctx.delegated.map(d => d.label).join('|')} webviewGated=${ctx.webviewGated}`);
    const uniq = []; const seenU = new Set();
    for (const l of ctx.links) { if (!seenU.has(l.url)) { seenU.add(l.url); uniq.push(l); } }
    for (const l of uniq) {
      l.result = l.type === 'M3U8' ? await validateM3u8(l) : await validateVideo(l);
      say(`   [${l.result.ok ? 'OK ' : 'BAD'}] ${l.server} -> ${l.type} ${qualName(l.quality)} ${l.url.slice(0, 110)} :: ${l.result.why}`);
    }
    const playable = uniq.filter(l => l.result.ok).length;
    const verdict = uniq.length === 0 ? (ctx.webviewGated ? 'FAIL(webview-gated)' : 'FAIL') : playable === uniq.length ? 'PASS' : playable > 0 ? 'PARTIAL' : 'FAIL';
    rows.push({
      ep: ep.url.replace(`${MAIN}/watch/`, ''), origin: `${ep.origin} t${ep.season}e${ep.ep}`,
      emitted: uniq.length, playable, verdict, webviewGated: ctx.webviewGated,
      hosts: uniq.map(l => { try { return new URL(l.url).hostname; } catch { return '?'; } }).filter((v, i, a) => a.indexOf(v) === i).join(','),
      reason: uniq.filter(l => !l.result.ok).map(l => `${(() => { try { return new URL(l.url).hostname; } catch { return '?'; } })()}:${l.result.why.slice(0, 55)}`).join(' ; ')
        || (uniq.length ? '' : ctx.webviewGated ? 'webview-gated: RSC truncado y ningún endpoint HTTP dio fuentes' : 'loadLinks=false'),
      delegated: ctx.delegated.map(d => d.label).join('|'),
      links: uniq,
    });
  }
  console.log('\n================== MATRIX: DonghuaLifeProvider (donghualife.com) ==================');
  for (const r of rows) {
    console.log(`- ${r.ep} [${r.origin}] | sources=${r.emitted} | playable=${r.playable} | ${r.verdict} | hosts=${r.hosts || '-'} | delegated=${r.delegated || '-'} | ${r.reason}`);
    for (const l of r.links) console.log(`    ${l.type}\t${qualName(l.quality)}\t${(() => { try { return new URL(l.url).hostname; } catch { return '?'; } })()}\t${l.result.ok ? 'PLAY' : 'FAIL'}\t${l.url.length > 96 ? l.url.slice(0, 96) + '...' : l.url}${l.result.ok ? '' : ' (' + l.result.why + ')'}`);
  }
  const pass = rows.filter(r => r.verdict === 'PASS').length;
  console.log(`\nSUMMARY: ${pass}/${rows.length} PASS, ${rows.filter(r => r.verdict === 'PARTIAL').length} PARTIAL, ${rows.filter(r => r.verdict.startsWith('FAIL')).length} FAIL`);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
