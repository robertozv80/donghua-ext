#!/usr/bin/env node
/*
 * Live validation of AnimeXinProvider (https://animexin.dev).
 * Faithful Node port of:
 *   AnimeXinProvider/src/main/kotlin/com/animexin/AnimeXinProvider.kt
 *   common/src/CommonExtractors.kt  (extractDailymotion/extractRumble/extractDood/extractOkRu/extractGdrivePlayer)
 * Nothing in the repo's Kotlin is modified.
 */
const MAIN = 'https://animexin.dev';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const DM_REFERER = 'https://www.dailymotion.com/';
const Q = { Unknown: -2, P360: 360, P480: 480, P720: 720, P1080: 1080 };
const qualName = q => (q === Q.Unknown || q == null ? 'UNKNOWN' : q + 'p');

const say = (...a) => console.error(...a);

async function req(url, { referer = null, headers = {}, method = 'GET', body = null, timeout = 25000 } = {}) {
  const h = { 'User-Agent': UA, ...headers };
  if (referer && !h['Referer']) h['Referer'] = referer;
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), timeout);
  try {
    const r = await fetch(url, { method, headers: h, body: body ?? undefined, signal: ac.signal, redirect: 'follow' });
    const buf = Buffer.from(await r.arrayBuffer());
    return { status: r.status, headers: Object.fromEntries(r.headers.entries()), buf, text: buf.toString('utf8'), finalUrl: r.url };
  } finally { clearTimeout(t); }
}
async function getText(url, opts = {}) { const r = await req(url, opts); return r.text ?? ''; }
function resolveUrl(base, href) { try { return new URL(href, base).href; } catch { return href; } }

/* ============================ CommonExtractors.kt port ============================ */

// CE.unescJson (CommonExtractors.kt:35) — verbatim semantics
function unescJson(s) {
  let out = ''; let i = 0;
  while (i < s.length) {
    const c = s[i];
    if (c === '\\' && i + 1 < s.length) {
      const n = s[i + 1];
      if (n === 'u') {
        const hex = s.substring(i + 2, Math.min(i + 6, s.length));
        const code = /^[0-9a-fA-F]{1,4}$/.test(hex) ? parseInt(hex, 16) : null;
        if (code != null && !Number.isNaN(code)) { out += String.fromCharCode(code); i += 6; }
        else { out += n; i += 2; }
      } else if (n === 'n') { out += '\n'; i += 2; }
      else if (n === 't') { out += '\t'; i += 2; }
      else if (n === 'r') { out += '\r'; i += 2; }
      else { out += n; i += 2; }
    } else { out += c; i++; }
  }
  return out;
}

// CE.dmSubtitlesBlock (CommonExtractors.kt:62) — string/escape-aware balance
function dmSubtitlesBlock(json) {
  const start = json.indexOf('"subtitles"');
  if (start < 0) return null;
  const dataIdx = json.indexOf('"data"', start);
  if (dataIdx < 0) return null;
  const open = json.indexOf('{', dataIdx);
  if (open < 0) return null;
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

function dmSubLangName(code) {
  const c = code.toLowerCase();
  if (c.startsWith('es')) return 'Spanish';
  if (c.startsWith('en')) return c.includes('auto') ? 'English (auto)' : 'English';
  const map = { fr: 'French', pt: 'Portuguese', ru: 'Russian', tr: 'Turkish', pl: 'Polish', ar: 'Arabic', bn: 'Bengali', fa: 'Persian', id: 'Indonesian', ms: 'Malay', it: 'Italian', de: 'German', hi: 'Hindi', th: 'Thai', vi: 'Vietnamese' };
  for (const k in map) if (c.startsWith(k)) return map[k];
  return code;
}

// CE.dmVideoId (CommonExtractors.kt:90)
function dmVideoId(src) {
  const m = /(?:geo\.dailymotion\.com\/player(?:\/[a-z0-9]+)?\.html\?video=|dailymotion\.com\/embed\/video\/)([A-Za-z0-9]+)/i.exec(src);
  return m ? m[1] : null;
}

// CE.extractDailymotion v8 (CommonExtractors.kt:160) — one route per video, DM master emitted FULL (no generateM3u8)
async function extractDailymotion(iframeSrc, embedderReferer, linkName, ctx) {
  let vid = dmVideoId(iframeSrc) || (/^[A-Za-z0-9]+$/.test(iframeSrc.trim()) ? iframeSrc.trim() : null);
  if (!vid) return false;
  if (ctx.emittedVids.has(vid)) return true; // dedupe by video across mirrors
  const referer = (embedderReferer || '').trim() === '' ? MAIN : embedderReferer;
  try {
    const metaUrl = `https://www.dailymotion.com/player/metadata/video/${vid}` +
      '?embedder=' + encodeURIComponent(referer) + '&integration=inline&app=com.dailymotion.neon';
    const raw = await getText(metaUrl, { referer, headers: { 'User-Agent': UA } });
    const json = raw.replaceAll('\\/', '/').replaceAll('\\u0026', '&');

    // subs (CE.emitDmSubtitles lines 97-122)
    const block = dmSubtitlesBlock(json);
    if (block) {
      const foundSubs = [];
      const re = /"(es[a-z-]*|en[a-z-]*|fr|pt|ru|tr|pl|ar|bn|fa|id|ms|it|de|hi|th|vi)":\s*\{[^{}]*?"urls":\["([^"]+)"/g;
      let m;
      while ((m = re.exec(block)) !== null) foundSubs.push([m[1], unescJson(m[2])]);
      const uniq = [];
      for (const s of foundSubs) if (!uniq.some(x => x[1] === s[1])) uniq.push(s);
      uniq.sort((a, b) => {
        const w = c => (c.toLowerCase().startsWith('es') ? 0 : c.toLowerCase().startsWith('en') ? 1 : 2);
        return w(a[0]) - w(b[0]);
      });
      for (const [code, url] of uniq) if (!ctx.emittedSubs.has(url)) { ctx.emittedSubs.add(url); ctx.subs.push({ lang: dmSubLangName(code), url }); }
    }

    let found = false;
    const emittedUrls = new Set();
    const emit = (u, hls, q) => {
      const url = u; if (!url.startsWith('http')) return;
      if (emittedUrls.has(url)) return;
      emittedUrls.add(url);
      const suffix = q === Q.P1080 ? ' 1080p' : q === Q.P720 ? ' 720p' : q === Q.P480 ? ' 480p' : q === Q.P360 ? ' 360p' : '';
      const display = suffix && !/\b\d{3,4}p\b/i.test(linkName) ? linkName + suffix : linkName;
      ctx.links.push({ server: linkName, name: display, url, type: hls ? 'M3U8' : 'VIDEO', quality: q ?? Q.Unknown, referer: DM_REFERER });
      found = true;
    };

    // tryPlaylist (line 227): NEVER emits dmxleo/advertising/auth= URLs
    const tryPlaylist = (u, hlsQ) => {
      if (!u || u.trim() === '' || !u.startsWith('http')) return false;
      if (/dmxleo/i.test(u) || /advertising/i.test(u) || u.includes('auth=')) return false;
      emit(u, true, hlsQ);
      return true;
    };

    let emittedAny = false;
    // 1) "auto":[{"url":"..."}]
    {
      const re = /"auto":\[\{"url":"(https:\/\/[^"]+)"/g; let m;
      while (!emittedAny && (m = re.exec(json)) !== null) {
        if (m[1].includes('dailymotion.com')) emittedAny = tryPlaylist(unescJson(m[1]), Q.Unknown);
      }
    }
    // 1b) "auto":[{...,"type":"...","url":"..."}]
    if (!emittedAny) {
      const re = /"auto":\[\{[^}]*?"type":"[^"]+","url":"(https:\/\/[^"]+)"/g; let m;
      while (!emittedAny && (m = re.exec(json)) !== null) {
        if (m[1].includes('dailymotion.com')) emittedAny = tryPlaylist(unescJson(m[1]), Q.Unknown);
      }
    }
    // 2) legacy vCRN muxed presets
    if (!emittedAny) {
      const masterAuto = (json.match(/https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i) || [null])[0];
      const dc = masterAuto ? ((masterAuto.match(/https:\/\/([a-z0-9.-]+)\.dailymotion\.com/i) || [])[1] ?? 'dmxleo') : null;
      const dcOk = dc && dc !== 'dmxleo' ? dc : null;
      const dc2 = dcOk && !dcOk.startsWith('cdndirector') ? dcOk : null;
      const vid2 = masterAuto ? ((masterAuto.match(/video\/([a-z0-9]+)\.m3u8/i) || [])[1] ?? null) : null;
      if (dc2 && vid2) {
        for (const preset of ['H264-1280x720', 'H264-1920x1080', 'H264-854x480']) {
          const u = `https://${dc2}.dailymotion.com/cdn/${preset}/vCRN/mp4_h264_aac.m3u8?video=${vid2}`;
          if (tryPlaylist(u, Q.P720)) { emittedAny = true; break; }
        }
      }
    }
    // 3) other dmcdn masters, AV1 excluded
    if (!emittedAny) {
      const re = /https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"']*/g; let m;
      while ((m = re.exec(json)) !== null) {
        const u = m[0];
        if (/\/av1/i.test(u) || /_av1_/i.test(u)) continue;
        if (!emittedAny && tryPlaylist(u, null)) emittedAny = true;
      }
    }
    if (emittedAny) { ctx.emittedVids.add(vid); return found; } // ONE route per video

    // 4) last resort: progressive mp4 from qualities map (skip AV1)
    {
      const re = /"(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"/g; let m;
      while ((m = re.exec(json)) !== null) {
        const h = parseInt(m[1], 10);
        const url = unescJson(m[3]);
        const q = Number.isNaN(h) ? Q.Unknown : h >= 1080 ? Q.P1080 : h >= 720 ? Q.P720 : h >= 480 ? Q.P480 : h >= 360 ? Q.P360 : Q.Unknown;
        if (/\/av1/i.test(url) || /_av1_/i.test(url)) continue; // skip AV1 (negro/sin audio)
        emit(url, url.includes('.m3u8'), q);
      }
    }
    // final unvalidated fallback (lines 311-327)
    if (!found) {
      let candidate = null;
      const m1 = /auto":\[\{[^}]*"url":"(https:\/\/[^"]+)"/.exec(json);
      if (m1) candidate = unescJson(m1[1]);
      if (!candidate) candidate = (json.match(/https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i) || [null])[0];
      if (candidate && !/dmxleo/i.test(candidate) && !/advertising/i.test(candidate) && !candidate.includes('auth=')) {
        say('  DM fallback master sin validar', candidate.split('://')[1]?.split('/')[0] ?? candidate.slice(0, 60));
        emit(candidate, true, Q.Unknown);
      }
    }
    if (found) ctx.emittedVids.add(vid);
    return found;
  } catch (e) { say('  DM error:', e.message); return false; }
}

// CE.extractRumble (CommonExtractors.kt:341) — embedJS/u3 with retry after visiting the embed
async function extractRumble(embedUrl, siteReferer, linkName, ctx) {
  try {
    const vm = embedUrl.match(/rumble\.com\/(?:embed\/)?(v[0-9a-z]+)(?:[-/_.@?]|$)/i);
    const vid = vm ? vm[1] : null; if (!vid) return false;
    const api = `https://rumble.com/embedJS/u3/?request=video&v=${vid}&embed=${encodeURIComponent(embedUrl)}`;
    const doEmit = (json) => {
      const fixed = json.replaceAll('\\/', '/');
      const re = /"(\d{3,4})":\["(https:\/\/[a-z0-9.-]*rumble\.cloud\/video\/[A-Za-z0-9\/._-]+\.mp4)"/g;
      let m, n = 0;
      while ((m = re.exec(fixed)) !== null) {
        const h = parseInt(m[1], 10);
        ctx.links.push({
          server: linkName, name: linkName, url: m[2], type: 'VIDEO',
          quality: Number.isNaN(h) ? Q.Unknown : h >= 1080 ? Q.P1080 : h >= 720 ? Q.P720 : h >= 480 ? Q.P480 : Q.P360,
          referer: 'https://rumble.com/',
        });
        n++;
      }
      return n;
    };
    let json = '';
    try { json = await getText(api, { referer: 'https://rumble.com/', headers: { 'User-Agent': UA } }); } catch { json = ''; }
    let count = json.trim() !== '' ? doEmit(json) : 0;
    if (count === 0) {
      try { await getText(embedUrl, { referer: siteReferer, headers: { 'User-Agent': UA } }); } catch {}
      try { json = await getText(api, { referer: embedUrl, headers: { 'User-Agent': UA } }); } catch { json = ''; }
      if (json.trim() !== '') count = doEmit(json);
    }
    return count > 0;
  } catch { return false; }
}

// CE.extractDood (CommonExtractors.kt:508)
const DOOD_RAND = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
async function extractDood(embedUrl, siteReferer, linkName, ctx) {
  try {
    const html = await getText(embedUrl, { referer: siteReferer, headers: { 'User-Agent': UA } });
    const m = html.match(/\/pass_md5\/([A-Za-z0-9._-]+)\/([A-Za-z0-9._-]+)/);
    if (!m) return false;
    const path = m[0]; const token = m[2];
    const origin = (embedUrl.match(/^https?:\/\/[^\/]+/) || [])[0];
    if (!origin) return false;
    const base = (await getText(`${origin}${path}`, {
      referer: embedUrl, headers: { 'User-Agent': UA, 'X-Requested-With': 'XMLHttpRequest' },
    })).trim();
    if (base === '' || !base.startsWith('http')) return false;
    // VTT subtitle tracks (CE line 533)
    const re = /\{src:'([^']+)',\s*label:'([^']*)',kind:'captions',srclang:'[^']*'\}/g; let sm;
    while ((sm = re.exec(html)) !== null) {
      let raw = sm[1]; if (raw.startsWith('data:')) continue;
      const url = raw.startsWith('//') ? 'https:' + raw : raw;
      const label = (sm[2] || '').trim() || 'Subtitle';
      if (!ctx.emittedSubs.has(url)) { ctx.emittedSubs.add(url); ctx.subs.push({ lang: label, url }); }
    }
    let rnd = ''; for (let i = 0; i < 10; i++) rnd += DOOD_RAND[Math.floor(Math.random() * DOOD_RAND.length)];
    const finalUrl = `${base}${rnd}?token=${token}&expiry=${Date.now()}`;
    ctx.links.push({ server: linkName, name: linkName, url: finalUrl, type: 'VIDEO', quality: Q.Unknown, referer: `${origin}/` });
    return true;
  } catch (e) { say('  DOOD error:', e.message); return false; }
}

// CE.extractOkRu (CommonExtractors.kt:441) — data-options, #EXTM3U-validated HLS + progressives
async function extractOkRu(embedUrl0, siteReferer, linkName, ctx) {
  try {
    const url = embedUrl0.startsWith('//') ? 'https:' + embedUrl0 : embedUrl0;
    const html = await getText(url, { referer: siteReferer, headers: { 'User-Agent': UA } });
    const decodeUnicode = s => s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
    const dm = html.match(/data-options="([^"]+)"/);
    if (!dm) return false;
    const options = decodeUnicode(dm[1].replaceAll('&quot;', '"').replaceAll('\\/', '/'));
    let found = false;
    const hlsRe = /"(?:[a-zA-Z]*ManifestUrl|ondemandHls)":"([^"]+)"/g;
    let candidates = [], hm;
    while ((hm = hlsRe.exec(options)) !== null) candidates.push(hm[1]);
    for (const cand of candidates) {
      let ok = false;
      try { ok = (await getText(cand, { referer: url, headers: { 'User-Agent': UA } })).trimStart().startsWith('#EXTM3U'); } catch { ok = false; }
      if (!ok) continue;
      const gl = await generateM3u8(linkName, cand, url);
      for (const g of gl) ctx.links.push({ ...g, referer: url });
      found = true; break; // firstOrNull in Kotlin
    }
    const pRe = /"name":"(mobile|lowest|low|sd|hd|full|super)","url":"([^"]+)"/g; let pm;
    while ((pm = pRe.exec(options)) !== null) {
      let progressive = decodeUnicode(pm[2]);
      if (!progressive.startsWith('http')) continue;
      const full = progressive.startsWith('//') ? 'https:' + progressive : progressive;
      const quality = ({ full: Q.P1080, super: Q.P1080, hd: Q.P720, sd: Q.P480 })[pm[1]] ?? Q.P360;
      ctx.links.push({ server: linkName, name: linkName, url: full, type: 'VIDEO', quality, referer: url });
      found = true;
    }
    return found;
  } catch { return false; }
}

// CE.extractGdrivePlayer (CommonExtractors.kt:393) — XOR k/atob packed JW player
async function extractGdrivePlayer(embedUrl, siteReferer, linkName, ctx) {
  try {
    const html = await getText(embedUrl, { referer: siteReferer, headers: { 'User-Agent': UA } });
    const scriptRe = /<script>([\s\S]*?)<\/script>/g;
    let script = null, sm;
    while ((sm = scriptRe.exec(html)) !== null) {
      if (sm[1].includes('atob(') && sm[1].includes('k="')) { script = sm[1]; break; }
    }
    if (!script) return false;
    const key = (script.match(/k="([0-9a-f]+)"/) || [])[1]; if (!key) return false;
    const b64 = (script.match(/atob\("([^"]+)"\)/) || [])[1]; if (!b64) return false;
    const raw = Buffer.from(b64, 'base64');
    const keyBytes = Buffer.from(key, 'latin1');
    const out = Buffer.alloc(raw.length);
    for (let i = 0; i < raw.length; i++) out[i] = raw[i] ^ keyBytes[i % keyBytes.length];
    const decoded = out.toString('latin1').replaceAll('\\/', '/');
    const hlsRel = (decoded.match(/HLS="([^"]+\.m3u8)"/) || [])[1]; if (!hlsRel) return false;
    const origin = (embedUrl.match(/^https?:\/\/[^\/]+/) || [])[0]; if (!origin) return false;
    const playlist = hlsRel.startsWith('http') ? hlsRel : `${origin}/${hlsRel}`;
    let text = '';
    try { text = await getText(playlist, { referer: embedUrl, headers: { 'User-Agent': UA } }); } catch { text = ''; }
    if (!text.trimStart().startsWith('#EXTM3U')) return false;
    let emitted = false;
    try { const gl = await generateM3u8(linkName, playlist, origin); for (const g of gl) { ctx.links.push({ ...g, referer: origin }); emitted = true; } } catch {}
    if (!emitted) ctx.links.push({ server: linkName, name: linkName, url: playlist, type: 'M3U8', quality: Q.Unknown, referer: origin });
    return true;
  } catch (e) { say('  GDRIVE error:', e.message); return false; }
}

/* ===================== CloudStream M3u8Helper.generateM3u8 (expansion) ===================== */
async function generateM3u8(name, url, referer) {
  const body = await getText(url, { referer, headers: { 'User-Agent': UA } });
  const links = [];
  if (body.includes('#EXT-X-STREAM-INF')) {
    const lines = body.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const inf = lines[i].match(/#EXT-X-STREAM-INF:[^\n]*BANDWIDTH=(\d+)/);
      if (inf && lines[i + 1] && !lines[i + 1].startsWith('#')) {
        const res = lines[i].match(/RESOLUTION=(\d+)x(\d+)/);
        let q = res ? parseInt(res[2], 10) : Q.Unknown;
        if (!res) { const bw = parseInt(inf[1], 10); q = bw >= 2500000 ? Q.P1080 : bw >= 1200000 ? Q.P720 : bw >= 600000 ? Q.P480 : bw >= 350000 ? Q.P360 : Q.Unknown; }
        links.push({ server: name, name, url: resolveUrl(url, lines[i + 1].trim()), type: 'M3U8', quality: q, referer });
      }
    }
    if (links.length === 0) links.push({ server: name, name, url, type: 'M3U8', quality: Q.Unknown, referer });
  } else {
    links.push({ server: name, name, url, type: 'M3U8', quality: Q.Unknown, referer });
  }
  return links;
}

/* ===================== provider-local: extractOdysee (AnimeXinProvider.kt:525) ===================== */
async function extractOdysee(embedUrl, referer, linkName, ctx) {
  try {
    const html = await getText(embedUrl, { referer, headers: { 'User-Agent': UA } });
    let found = false;
    const re = /"hls":"(https:[^"]+?\.m3u8[^"]*)"/g; let m;
    while ((m = re.exec(html)) !== null) {
      try { const gl = await generateM3u8(linkName, unescJson(m[1]), 'https://odysee.com/'); for (const g of gl) { ctx.links.push({ ...g, referer: 'https://odysee.com/' }); found = true; } } catch {}
    }
    if (!found) {
      const re2 = /"videoUrl":"(https:[^"]+?\.mp4[^"]*)"/g; let m2;
      while ((m2 = re2.exec(html)) !== null) {
        ctx.links.push({ server: linkName, name: linkName, url: unescJson(m2[1]), type: 'VIDEO', quality: Q.Unknown, referer: 'https://odysee.com/' });
        found = true;
      }
    }
    return found;
  } catch { return false; }
}

/* ===================== loadLinks (AnimeXinProvider.kt:385) ===================== */
const DOOD_HOST = /(?:playmogo|do+od|dsvplay|ds2play|d000d|doply|doodstream)/i;
const prio = label => {
  const l = label.toLowerCase();
  if (l.includes('all sub')) return 0;
  if (l.includes('dailymotion') && l.includes('english')) return 1;
  if (l.includes('dailymotion')) return 2;
  if (l.includes('odysee') && l.includes('english')) return 3;
  if (l.includes('rumble')) return 4;
  if (l.includes('ok.ru')) return 5;
  if (l.includes('dood')) return 6;
  if (l.includes('odysee')) return 7;
  return 8;
};

async function loadLinks(data, ctx) {
  const document = await getText(data, { headers: { 'User-Agent': UA } });
  const mirrors = [];
  const selRe = /<select[^>]*class="[^"]*mirror[^"]*"[\s\S]*?<\/select>/g;
  let sel;
  while ((sel = selRe.exec(document)) !== null) {
    const optRe = /<option[^>]*value="([^"]*)"[^>]*>([\s\S]*?)<\/option>/g;
    let om;
    while ((om = optRe.exec(sel[0])) !== null) {
      const b64 = (om[1] || '').trim();
      if (b64.length < 20) continue;
      const html = Buffer.from(b64, 'base64').toString('utf8');
      mirrors.push([om[2].replace(/<[^>]*>/g, '').trim(), html]);
    }
  }
  if (mirrors.length === 0) return false;
  let found = false;
  for (const [label, iframeHtml] of mirrors.slice().sort((a, b) => prio(a[0]) - prio(b[0]))) {
    const srcM = iframeHtml.match(/src=["']([^"']+)["']/);
    if (!srcM) continue;
    const src = srcM[1];
    const fixed = src.startsWith('//') ? 'https:' + src : src;
    let ok = false;
    try {
      if (/dailymotion/i.test(fixed)) ok = await extractDailymotion(fixed, `${MAIN}/`, label, ctx);
      else if (/rumble\.com/i.test(fixed)) ok = await extractRumble(fixed, `${MAIN}/`, label, ctx);
      else if (DOOD_HOST.test(fixed)) ok = await extractDood(fixed, `${MAIN}/`, label, ctx);
      else if (/ok\.ru/i.test(fixed)) ok = await extractOkRu(fixed, `${MAIN}/`, label, ctx);
      else if (/odysee\.com/i.test(fixed)) ok = await extractOdysee(fixed, data, label, ctx);
      else if (/youtube\.com|youtu\.be/i.test(fixed)) { ctx.delegated.push({ label, url: fixed, why: 'YouTube: delegated to app loadExtractor (cannot run in Node)' }); ok = false; }
      else if (/gdriveplayer/i.test(fixed)) ok = await extractGdrivePlayer(fixed, `${MAIN}/`, label, ctx);
      else { ctx.skipped.push({ label, url: fixed }); }
    } catch (e) { say('  dispatch error', label, e.message); }
    if (ok) found = true;
  }
  return found;
}

/* ===================== discovery: getMainPage / load (Kotlin:77 / 164) ===================== */
async function getLatestEpisodes() {
  const html = await getText(`${MAIN}/`, { headers: { 'User-Agent': UA } });
  const region = html.split('class="listupd normal"')[1] ?? '';
  const arts = region.match(/<article[\s\S]*?<\/article>/g) ?? [];
  const out = [];
  for (const art of arts) {
    const a = art.match(/<a[^>]*href="([^"]+)"[^>]*>/);
    if (!a) continue;
    const t = (art.match(/title="([^"]*)"/) || [])[1] ?? (art.match(/<h2[^>]*>([\s\S]*?)<\/h2>/) || [])[1]?.replace(/<[^>]*>/g, '').trim() ?? '';
    if (a[1] && t) out.push({ url: a[1], title: t });
  }
  return out;
}
async function seriesFromEpisode(epUrl) { // resolveSeriesUrlFromEpisode (Kotlin:359) — .ts-breadcrumb a
  const html = await getText(epUrl, { headers: { 'User-Agent': UA } });
  const hrefs = [...html.matchAll(/itemprop="item"\s+href="([^"]+)"/g)].map(m => m[1]);
  const root = `${MAIN}/`;
  const cands = hrefs.filter(h => h !== '' && h !== root && h !== epUrl && !h.includes('?') && !/-episode-\d+/i.test(h));
  return cands.length ? cands[cands.length - 1] : epUrl;
}
async function episodesOfSeries(seriesUrl) { // load() .eplister li a[href] (Kotlin:210)
  const html = await getText(seriesUrl, { headers: { 'User-Agent': UA } });
  const at = html.indexOf('class="eplister"');
  const region = at < 0 ? html : html.slice(at);
  const seen = new Set(); const eps = [];
  const re = /<a href="([^"]+)"[^>]*>\s*<div class="epl-num">(\d+)<\/div>\s*<div class="epl-title[^>]*>([^<]*)</g;
  let m;
  while ((m = re.exec(region)) !== null) {
    const u = m[1];
    if (!u || !/-episode-\d+/i.test(u) || seen.has(u)) continue;
    seen.add(u);
    eps.push({ url: u, ep: parseInt(m[2], 10), title: m[3].trim() });
  }
  return eps.sort((a, b) => a.ep - b.ep);
}

/* ===================== validation (player-side) ===================== */
async function fetchBytes(url, referer, range = 'bytes=0-65535') {
  const r = await req(url, { referer, headers: { 'User-Agent': UA, 'Range': range } });
  return r;
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
    if (afc === 0 || afc === 1) continue; // no payload
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
      if (i < 0) continue;
      if (i + 15 >= bytes.length) continue;
      const sectLen = ((bytes[i + 1] & 0x0F) << 8) | bytes[i + 2];
      const endSec = Math.min(i + 3 + sectLen - 4, bytes.length - 5);
      const pil = ((bytes[i + 10] & 0x0F) << 8) | bytes[i + 11];
      let j = i + 12 + pil; const types = [];
      while (j + 4 <= endSec) {
        const st = bytes[j]; const elen = ((bytes[j + 3] & 0x0F) << 8) | bytes[j + 4];
        types.push(st); j += 5 + elen;
      }
      if (types.length) return types;
    }
  }
  return null;
}
const TS_AUDIO = new Set([0x03, 0x04, 0x0f, 0x11, 0x81, 0x82, 0x87, 0x06]);
const TS_VIDEO = new Set([0x01, 0x02, 0x10, 0x1b, 0x24, 0x15, 0x27]);
function mp4Handlers(bytes) {
  const out = []; const hdlr = Buffer.from('hdlr');
  let idx = 0;
  while ((idx = bytes.indexOf(hdlr, idx)) !== -1) {
    if (idx + 16 <= bytes.length) out.push(bytes.subarray(idx + 12, idx + 16).toString('latin1'));
    idx += 4;
  }
  return out;
}
async function validateM3u8(link) {
  let master;
  try { master = await req(link.url, { referer: link.referer ?? MAIN + '/', headers: { 'User-Agent': UA } }); }
  catch (e) { return { ok: false, why: `master GET fail: ${e.message}` }; }
  const body = master.text ?? '';
  if (!body.trimStart().startsWith('#EXTM3U')) return { ok: false, why: `no #EXTM3U (status ${master.status}, head ${body.slice(0, 40).replace(/\n/g, ' ')})` };
  if (/<vmap|<VMAP/.test(body)) return { ok: false, why: 'VMAP ad XML' };
  const isMaster = body.includes('#EXT-X-STREAM-INF');
  const masterHasAudioGroup = /#EXT-X-MEDIA:[^\n]*TYPE=AUDIO/.test(body);
  let variantBody, variantUrl;
  if (isMaster) {
    const lines = body.split(/\r?\n/); let first = null;
    for (let i = 0; i < lines.length; i++) if (lines[i].startsWith('#EXT-X-STREAM-INF') && lines[i + 1] && !lines[i + 1].startsWith('#')) { first = lines[i + 1].trim(); break; }
    if (!first) return { ok: false, why: 'master sin variantes' };
    variantUrl = resolveUrl(master.finalUrl || link.url, first);
    try { variantBody = (await req(variantUrl, { referer: link.referer ?? MAIN + '/', headers: { 'User-Agent': UA } })).text; }
    catch (e) { return { ok: false, why: `variant GET fail: ${e.message}` }; }
  } else { variantBody = body; variantUrl = link.url; }
  if (/<vmap|<VMAP|<VAST/.test(variantBody)) return { ok: false, why: 'VMAP/VAST ad xml' };
  const segM = variantBody.split(/\r?\n/).find(l => l.trim() !== '' && !l.startsWith('#'));
  if (!segM || !variantBody.includes('#EXTINF')) return { ok: false, why: 'no media playlist (sin segmentos)' };
  // audio check on first segment
  const segUrl = resolveUrl(variantUrl, segM.trim());
  const mapUri = (variantBody.match(/#EXT-X-MAP:[^\n]*URI="([^"]+)"/) || [])[1];
  let audio = masterHasAudioGroup; // separate audio renditions satisfy ExoPlayer
  let how = 'master AUDIO group';
  if (!audio) {
    const s = await fetchBytes(segUrl, link.referer ?? MAIN + '/', 'bytes=0-200000');
    const bytes = s.buf;
    if (bytes.length && bytes[0] === 0x47) {
      const types = tsStreamTypes(bytes);
      audio = !!types && types.some(t => TS_AUDIO.has(t));
      const hasVideo = !!types && types.some(t => TS_VIDEO.has(t));
      how = `TS (video=${hasVideo}, audio=${audio})`;
      if (types && !hasVideo && !audio) return { ok: false, why: 'segmentos TS sin tracks A/V' };
    } else if (mapUri || /\.m4s($|\?)/.test(segUrl) || (bytes.length > 8 && bytes.subarray(4, 8).toString('latin1') === 'ftyp')) {
      if (mapUri) {
        const initUrl = resolveUrl(variantUrl, mapUri);
        const init = await fetchBytes(initUrl, link.referer ?? MAIN + '/', 'bytes=0-65535');
        const handlers = mp4Handlers(init.buf);
        audio = handlers.includes('soun') || body.includes('TYPE=AUDIO');
        how = `fMP4 hdlr=[${handlers.join(',')}]`;
      } else {
        const handlers = mp4Handlers(bytes);
        audio = handlers.includes('soun');
        how = `fMP4-in-segment hdlr=[${handlers.join(',')}]`;
      }
    } else {
      audio = true; how = 'magic desconocido, no se puede probar audio';
    }
  }
  if (!audio) return { ok: false, why: `SIN AUDIO: video-only HLS (${how})` };
  return { ok: true, why: how };
}
async function validateVideo(link) {
  try {
    const r = await req(link.url, { referer: link.referer ?? MAIN + '/', headers: { 'User-Agent': UA, 'Range': 'bytes=0-31' } });
    const ct = (r.headers['content-type'] ?? '').toLowerCase();
    if (!(r.status === 200 || r.status === 206)) return { ok: false, why: `HTTP ${r.status}` };
    if (ct.startsWith('video/')) return { ok: true, why: ct };
    const magic = r.buf.length >= 12 && r.buf.subarray(4, 8).toString('latin1') === 'ftyp' ? 'ftyp' : (r.buf[0] === 0x47 ? 'TS' : '');
    if (magic) return { ok: true, why: `${ct || 'no-ct'} + magic ${magic} (ExoPlayer sniffing)` };
    return { ok: false, why: `content-type ${ct || 'desconocido'} status ${r.status}` };
  } catch (e) { return { ok: false, why: `GET fail: ${e.message}` }; }
}

/* ===================== main ===================== */
(async () => {
  say('== AnimeXin matrix: discovering 5 real episodes (3-4 recent + 1 old) ==');
  const latest = await getLatestEpisodes();
  say(`  home .listupd.normal cards: ${latest.length}`);
  const episodes = [];
  const bySeries = new Set();
  for (const card of latest) {
    const seriesBase = card.url.split(/-episode-\d+/i)[0];
    if (bySeries.has(seriesBase)) continue;
    bySeries.add(seriesBase);
    episodes.push({ url: card.url, title: card.title, origin: 'HOME latest' });
    if (episodes.length >= 4) break;
  }
  // older episode: first episode of the series of the first home card
  if (episodes.length) {
    const seriesUrl = await seriesFromEpisode(episodes[0].url);
    say(`  series for old-episode: ${seriesUrl}`);
    const eps = await episodesOfSeries(seriesUrl);
    say(`  .eplister episodes: ${eps.length}`);
    if (eps.length) {
      const old = eps[0];
      if (!episodes.some(e => e.url === old.url)) episodes.push({ url: old.url, title: `${old.title || old.url}`, origin: 'OLDER ep (serie page)' });
      else if (eps[1]) episodes.push({ url: eps[1].url, title: eps[1].title, origin: 'OLDER ep (serie page)' });
    }
  }
  const chosen = episodes.slice(0, 5);
  const rows = [];
  for (const ep of chosen) {
    say(`\n== loadLinks: ${ep.url} (${ep.origin}) ==`);
    const ctx = { links: [], subs: [], skipped: [], delegated: [], emittedSubs: new Set(), emittedVids: new Set() };
    let any = false;
    try { any = await loadLinks(ep.url, ctx); } catch (e) { say('  loadLinks fatal:', e.message); }
    say(`  found=${any} links=${ctx.links.length} subs=${ctx.subs.length} skipped=${ctx.skipped.map(s => s.label).join('|')}`);
    const uniq = []; const seenU = new Set();
    for (const l of ctx.links) { if (!seenU.has(l.url)) { seenU.add(l.url); uniq.push(l); } }
    for (const l of uniq) {
      l.result = l.type === 'M3U8' ? await validateM3u8(l) : await validateVideo(l);
      say(`   [${l.result.ok ? 'OK ' : 'BAD'}] ${l.server} -> ${l.type} ${qualName(l.quality)} ${l.url.slice(0, 110)} :: ${l.result.why}`);
    }
    for (const s of ctx.subs) say(`   [SUB] ${s.lang}: ${s.url.slice(0, 100)}`);
    const playable = uniq.filter(l => l.result.ok).length;
    const verdict = uniq.length === 0 ? 'FAIL' : playable === uniq.length ? 'PASS' : playable > 0 ? 'PARTIAL' : 'FAIL';
    rows.push({
      ep: (ep.title || ep.url).slice(0, 58), origin: ep.origin, emitted: uniq.length, playable, verdict,
      hosts: uniq.map(l => new URL(l.url).hostname).filter((v, i, a) => a.indexOf(v) === i).join(','),
      reason: uniq.filter(l => !l.result.ok).map(l => `${new URL(l.url).hostname}:${l.result.why.slice(0, 60)}`).join(' ; ') || (uniq.length ? '' : 'loadLinks found=false'),
      links: uniq,
    });
  }
  console.log('\n================== MATRIX: AnimeXinProvider (animexin.dev) ==================');
  for (const r of rows) {
    console.log(`- ${r.ep} [${r.origin}] | sources=${r.emitted} | playable=${r.playable} | ${r.verdict} | hosts=${r.hosts || '-'} | ${r.reason}`);
    for (const l of r.links) console.log(`    ${l.type}\t${qualName(l.quality)}\t${new URL(l.url).hostname}\t${l.result.ok ? 'PLAY' : 'FAIL'}\t${l.url.length > 96 ? l.url.slice(0, 96) + '...' : l.url}${l.result.ok ? '' : ' (' + l.result.why + ')'}`);
  }
  const pass = rows.filter(r => r.verdict === 'PASS').length;
  console.log(`\nSUMMARY: ${pass}/${rows.length} PASS, ${rows.filter(r => r.verdict === 'PARTIAL').length} PARTIAL, ${rows.filter(r => r.verdict === 'FAIL').length} FAIL`);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
