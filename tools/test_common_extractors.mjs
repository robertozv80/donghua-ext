// Test del extractor comun (CommonExtractors.extractDailymotion) replicado en Node.
// Valida con curl (la pila TLS de Node/undici recibe 403 de Akamai; curl y
// OkHttp/Cronet no). Comprueba: una sola ruta master por video, nunca un VMAP de
// anuncios (dmxleo/advertising/auth=), nunca AV1, y AUDIO real en las variantes
// (el reporte "sin audio" del proyecto).
//
// Uso: node tools/test_common_extractors.mjs [videoId ...]
import { execFileSync } from 'node:child_process';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
const DM_REFERER = 'https://www.dailymotion.com/';

function curl(url, referer = DM_REFERER) {
  try {
    const out = execFileSync('curl', ['-s', '-L', '-m', '30', '-A', UA, '-e', referer,
      '-w', '\n@@STATUS@@%{http_code}@@CT@@%{content_type}', url], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
    const i = out.lastIndexOf('\n@@STATUS@@');
    const body = i < 0 ? out : out.slice(0, i);
    const tail = i < 0 ? '' : out.slice(i + 1);
    return { status: +(tail.match(/@@STATUS@@(\d+)/) || [])[1] || 'ERR', ct: (tail.match(/@@CT@@(.*)$/) || [])[1] || '', body };
  } catch (e) {
    return { status: 'ERR', ct: '', body: String(e.message).slice(0, 120) };
  }
}
const unesc = s => s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16))).replace(/\\(.)/g, '$1');

// ---- replica de los pasos 1-4 de extractDailymotion ----
function pickRoutes(json) {
  const log = [];
  let chosen = null;
  const isAd = u => u.includes('dmxleo') || u.includes('advertising') || u.includes('auth=');
  const tryPlaylist = (u, tag) => {
    if (!u || !u.startsWith('http')) return false;
    if (isAd(u)) { log.push(`${tag}: RECHAZADO por filtro ads -> ${u.slice(0, 70)}`); return false; }
    log.push(`${tag}: ACEPTADO -> ${u.slice(0, 80)}`);
    chosen = chosen || { url: u, tag };
    return true;
  };
  let any = false;
  for (const m of json.matchAll(/"auto":\[\{"url":"(https:\/\/[^"]+)"/g))
    if (!any && m[1].includes('dailymotion.com')) any = tryPlaylist(unesc(m[1]), '1 qualities.auto');
  if (!any) for (const m of json.matchAll(/"auto":\[\{[^}]*?"type":"[^"]+","url":"(https:\/\/[^"]+)"/g))
    if (!any && m[1].includes('dailymotion.com')) any = tryPlaylist(unesc(m[1]), '1b qualities.auto(type first)');
  if (!any) {
    const masterAuto = json.match(/https:\/\/[a-z0-9.-]+\.dailymotion\.com\/cdn\/manifest\/video\/[a-z0-9]+\.m3u8[^"']*/i)?.[0];
    const dc = masterAuto && !/cdndirector/.test(masterAuto) ? masterAuto.match(/https:\/\/([a-z0-9.-]+)\.dailymotion\.com/i)?.[1] : null;
    const vid2 = masterAuto && masterAuto.match(/video\/([a-z0-9]+)\.m3u8/i)?.[1];
    if (dc && vid2) for (const p of ['H264-1280x720', 'H264-1920x1080', 'H264-854x480'])
      if (tryPlaylist(`https://${dc}.dailymotion.com/cdn/${p}/vCRN/mp4_h264_aac.m3u8?video=${vid2}`, `2 preset ${p}`)) { any = true; break; }
  }
  if (!any) for (const m of json.matchAll(/https:\/\/[^"']+?dmcdn[^"']*?\.m3u8[^"']*/g))
    if (!any && !/\/av1|_av1_/i.test(m[0])) any = tryPlaylist(m[0], '3 dmcdn master');
  const progressives = [];
  if (!any) for (const m of json.matchAll(/"(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"/g)) {
    const url = unesc(m[3]);
    if (/\/av1|_av1_/i.test(url)) continue;
    progressives.push({ h: +m[1], url });
  }
  return { chosen, progressives, log };
}

const ids = process.argv.slice(2);
if (!ids.length) ids.push('k5kmTfN2UaL96gzvLfz', 'k1slp8qR9ukY7ez4V3W', 'k4ni3IdBNxNeHQL4zCW', 'x1f5jda');

let pass = 0, fail = 0;
for (const vid of ids) {
  console.log(`\n================ ${vid} ================`);
  const site = 'https://animecube.live/';
  const meta = curl(
    `https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(site)}&integration=inline&app=com.dailymotion.neon`,
    site);
  if (meta.status !== 200) { console.log(`metadata HTTP=${meta.status}`); fail++; continue; }
  const json = meta.body.split('\\/').join('/').split('\\u0026').join('&');
  const err = json.match(/"error":\{[^}]*\}/)?.[0];
  if (err) { console.log(`metadata error: ${err.slice(0, 140)}`); fail++; continue; }

  const { chosen, progressives, log } = pickRoutes(json);
  log.forEach(l => console.log('  ' + l));
  const subs = [...json.matchAll(/"(es[a-z-]*|en[a-z-]*)":\s*\{[^{}]*?"urls":\["([^"]+)"/g)].map(m => m[1]);
  console.log(`  subtitulos: [${subs.join(',') || 'ninguno'}]  adFilters=${/dmxleo|advertising/.test(json) ? 'JSON contiene ads (correctamente filtrados)' : 'sin ads en JSON'}`);

  if (!chosen) {
    if (progressives.length) {
      const p = progressives[0];
      const r = curl(p.url, DM_REFERER);
      const ok = r.status === 200 && /video/i.test(r.ct);
      console.log(`  ruta 4 progresivo ${p.h}p: HTTP=${r.status} ct=${r.ct.slice(0, 30)} -> ${ok ? 'PASS' : 'FAIL'}`);
      ok ? pass++ : fail++;
    } else { console.log('  FAIL: ninguna ruta de video'); fail++; }
    continue;
  }

  const m = curl(chosen.url, DM_REFERER);
  const isPl = m.body.trimStart().startsWith('#EXTM3U');
  console.log(`  master(${chosen.tag}): HTTP=${m.status} ct=${m.ct.slice(0, 34)} playlist=${isPl}`);
  if (!isPl) { console.log(`  FAIL body: ${m.body.slice(0, 120).replace(/\n/g, ' ')}`); fail++; continue; }

  const streams = [...m.body.matchAll(/#EXT-X-STREAM-INF:([^\n]*)\n([^\n]+)/g)]
    .map(x => ({ attrs: x[1], url: new URL(x[2].trim(), chosen.url).href }));
  const av1 = streams.filter(s => /av1/i.test(s.attrs) || /\/av1|_av1_/i.test(s.url)).length;
  const codecs = streams.map(s => (s.attrs.match(/CODECS="([^"]+)"/) || [])[1]).filter(Boolean);
  const audioInMaster = /#EXT-X-MEDIA:TYPE=AUDIO/.test(m.body);
  console.log(`  variantes=${streams.length} res=[${streams.map(s => (s.attrs.match(/RESOLUTION=([\dx]+)/i) || [])[1]).join(' ')}] av1=${av1} audioMedia=${audioInMaster}`);
  console.log(`  codecs: ${[...new Set(codecs)].join(' | ').slice(0, 160)}`);

  // la variante mas alta debe traer audio (mp4a) en CODECS o en grupo AUDIO
  const top = streams.sort((a, b) => +((b.attrs.match(/BANDWIDTH=(\d+)/) || [])[1] || 0) - +((a.attrs.match(/BANDWIDTH=(\d+)/) || [])[1] || 0))[0];
  let verdict = 'FAIL';
  if (top) {
    const v = curl(top.url, DM_REFERER);
    const segs = (v.body.match(/^(?!#).+$/gm) || []).filter(l => l.trim()).length;
    const isVast = /<VAST|<VMAP/i.test(v.body.slice(0, 400));
    const hasAudio = /mp4a/i.test(top.attrs) || audioInMaster || /#EXT-X-MEDIA:TYPE=AUDIO/.test(v.body);
    const playable = v.status === 200 && v.body.trimStart().startsWith('#EXT') && segs > 0 && !isVast;
    console.log(`  top variante: HTTP=${v.status} segs=${segs} adXml=${isVast} audio=${hasAudio}`);
    verdict = playable && hasAudio ? 'PASS' : 'FAIL';
  } else verdict = 'FAIL(no variantes)';
  console.log(`  ${vid} -> ${verdict}`);
  verdict === 'PASS' ? pass++ : fail++;
}
console.log(`\n===== TOTAL: PASS=${pass} FAIL=${fail} =====`);
process.exit(fail ? 1 : 0);
