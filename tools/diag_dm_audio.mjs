// Diag: metadata DM de videos sin audio, probando embedders distintos + master fmp4
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const get = (u, ref) => fetch(u, { headers: { 'User-Agent': UA, ...(ref ? { Referer: ref } : {}) } }).then(r => r.text());

for (const [n, vid, ref] of [
  ['mortals195', '698330462', 'https://animexin.dev/'],
  ['mortals195ep', '698330462', 'https://animexin.dev/a-record-of-mortals-journey-to-immortality-episode-195-indonesia-english-sub/'],
  ['jadeS4', '664242314', 'https://animexin.dev/'],
  ['beyond43', '699834066', 'https://luciferdonghua.org/'],
]) {
  try {
    const t = await get(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(ref)}&integration=inline`, ref);
    let j;
    try { j = JSON.parse(t); } catch { console.log(n, ': no JSON, len', t.length); continue; }
    if (j.error) { console.log(n, 'API ERR', j.error?.title ?? j.error?.message ?? '?'); continue; }
    console.log('===', n, vid, JSON.stringify(j.title || ''));
    const all = [...new Set([...t.matchAll(/https:\\?\/\\?\/[^"\\]+?\.m3u8[^"\\]*/g)].map(m => m[0].replace(/\\\//g, '/')))];
    console.log(' m3u8 raw:'); all.forEach(u => console.log('   ', u));
    console.log(' muxed(h264_aac):', all.filter(u => /mp4_h264_aac/.test(u)));
    const q = j.qualities ? Object.entries(j.qualities).map(([k, v]) => `${k}:${(v?.[0]?.url ?? '').slice(0, 80)}`) : 'NO';
    console.log(' qualities:', String(q).slice(0, 500));
    // master manifest: mirar variantes y grupo de audio
    const master = all.find(u => /manifest\.m3u8/.test(u)) || all[0];
    if (master) {
      try {
        const mt = await get(master, 'https://www.dailymotion.com/');
        console.log(' MASTER head (3000):', mt.slice(0, 3000).replace(/\n/g, '\n   '));
      } catch (e) { console.log(' master ERR', e.message); }
    }
  } catch (e) { console.log(n, 'ERR', e.message); }
}
