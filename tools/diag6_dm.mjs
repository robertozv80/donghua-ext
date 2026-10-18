const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const get = (u, ref) => fetch(u, { headers: { 'User-Agent': UA, ...(ref ? { Referer: ref } : {}) } }).then(r => r.text());
const fmp4s = [], muxeds = [];
for (const [n, vid, ref] of [
  ['195 Mortals', 'k2Q3TqOldOCVSaLmtNM', 'https://animexin.dev/'],
  ['beyond43', 'k33DFNIat57NWELmoGm', 'https://luciferdonghua.org/'],
  ['ATG058', 'k34CwFzmu8HzYjLgqv4', 'https://www.donghuaworld.com/'],
]) {
  try {
    const t = await get(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(ref)}&integration=inline`, ref);
    const j = JSON.parse(t);
    if (j.error) { console.log(n, 'API ERR', j.error.title ?? '?'); continue; }
    console.log('\n===', n, vid, JSON.stringify(j.title));
    if (j.qualities) {
      console.log(' QUALITIES:');
      for (const [q, arr] of Object.entries(j.qualities)) {
        (arr ?? []).forEach(e => {
          const url = (e.url ?? '').replace(/\\\//g, '/');
          console.log(`    ${q} [${e.type}] ${url.slice(0, 120)}`);
          if (url.includes('fmp4')) fmp4s.push({ vid, url, kind: n });
          else if (url.includes('mp4_h264_aac')) muxeds.push({ vid, url, kind: n });
        });
      }
    } else console.log(' qualities: NO');
    if (j.subtitles?.data) console.log(' subs:', Object.keys(j.subtitles.data).join(','));
  } catch (e) { console.log(n, 'ERR', e.message); }
}
console.log('\n== WARM PROBE manifests ==');
for (const c of [...muxeds.slice(0, 2), ...fmp4s.slice(0, 2)]) {
  try {
    const kind = c.url.includes('mp4_h264_aac') ? 'MUXED' : 'FMP4';
    const t = await get(c.url, 'https://www.dailymotion.com/');
    const lines = t.split(/\r?\n/).filter(l => l.startsWith('#EXT-X-STREAM-INF') || l.startsWith('#EXT-X-MEDIA'));
    console.log('---', c.kind, c.vid, kind, 'len', t.length, 'infMedia:', lines.length);
    lines.slice(0, 25).forEach(l => console.log('    ', l.slice(0, 180)));
  } catch (e) { console.log('PROBE ERR', e.message); }
}
