// Fetch metadata DM para los 3 videos hard-fail del checklist
// 195 Mortals 195: k2Q3TqOldOCVSaLmtNM (animexin)
// beyond43: k33DFNIat57NWELmoGm (lucifer)
// 698402910: k34CwFzmu8HzYjLgqv4 (DonghuaWorld? no, es un Dailymotion de Mundo)
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const get = (u, ref) => fetch(u, { headers: { 'User-Agent': UA, ...(ref ? { Referer: ref } : {}) } }).then(r => r.text());
const trades = [
  ['195 Mortals', 'k2Q3TqOldOCVSaLmtNM', 'https://animexin.dev/'],
  ['beyond43', 'k33DFNIat57NWELmoGm', 'https://luciferdonghua.org/'],
  ['k34 Mundo', 'k34CwFzmu8HzYjLgqv4', 'https://www.donghuaworld.com/'],
];
const probeCandidates = { muxed: [], fmp4: [] };
for (const [n, vid, ref] of trades) {
  try {
    const t = await get(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(ref)}&integration=inline`, ref);
    let j; try { j = JSON.parse(t); } catch { console.log(n, 'no JSON'); continue; }
    if (j.error) { console.log(n, 'API ERR', j.error.title ?? '?'); continue; }
    console.log('\n===', n, vid, JSON.stringify(j.title));
    const urls = [...new Set([...j.matchAll(/https:\\?\/\\?\/[^"\\]+/g)].map(m => m[0]).filter(u => /\.m3u8|\.mp4/.test(u)))];
    console.log(' urls crudos:');
    urls.slice(0, 20).forEach(u => console.log('   ', u.replace(/\\\//g, '/').slice(0, 110)));
    if (j.qualities) {
      console.log(' QUALITIES:');
      for (const [q, arr] of Object.entries(j.qualities)) {
        (arr ?? []).forEach(e => {
          console.log(`    ${q} [${e.type}] ${(e.url||'').slice(0,120)}`);
          if ((e.url||'').includes('fmp4')) probeCandidates.fmp4.push({ vid, url: e.url.replace(/\\\//g,'/'), ref });
          if ((e.url||'').includes('mp4_h264_aac')) probeCandidates.muxed.push({ vid, url: e.url.replace(/\\\//g,'/'), ref });
        });
      }
    } else console.log(' qualities: NO');
    if (j.subtitles?.data) console.log(' subs:', Object.keys(j.subtitles.data).join(','));
  } catch (e) { console.log(n, 'ERR', e.message); }
}

// Probar fetch de un master fmp4 y un muxed: ver referers
console.log('\n== PROBE WARM fetch de manifests:');
for (const c of [...probeCandidates.muxed.slice(0,2), ...probeCandidates.fmp4.slice(0,2)]) {
  try {
    const kind = c.url.includes('mp4_h264_aac') ? 'MUXED' : 'FMP4';
    const t = await get(c.url, 'https://www.dailymotion.com/');
    const lines = t.split(/\r?\n/).filter(l => l.startsWith('#EXT-X-STREAM-INF') || l.startsWith('#EXT-X-MEDIA'));
    console.log('---', c.vid, kind, 'len', t.length, 'lines:', lines.length);
    lines.slice(0, 30).forEach(l => console.log('    ', l.slice(0, 160)));
  } catch (e) { console.log('PROBE ERR', c.vid, e.message); }
}
