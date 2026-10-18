// Diag LmAnime: paginas /v/9..12 de a-mortals-journey-episode-01
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const get = (u) => fetch(u, { headers: { 'User-Agent': UA } }).then(r => r.text());

// Primero la pag del episodio: select.mirror
const ev = await get('https://lmanime.com/a-mortals-journey-to-immortality-episode-01/');
const opts = [...ev.matchAll(/<option[^>]*value="([^"]*)"[^>]*>([^<]*)<\/option>/g)].map(m => [m[2].trim(), m[1]]);
console.log('MIRROR options del episodio:');
opts.forEach(([l, v]) => console.log('  ', l, '->', v.slice(0, 90)));

for (const [l, v] of opts) {
  if (!/^https?:\/\//.test(v)) { console.log('  ! option sin URL:', l, v.slice(0, 50)); continue; }
  try {
    const t = await get(v);
    const ifrs = [...t.matchAll(/<iframe[^>]*src="([^"]+)"/g)].map(m => m[1]);
    console.log('  ', l, '-> iframes:', ifrs.length, ifrs.slice(0, 3));
    // el site puede poner el iframe via JS (base64)
    if (!ifrs.length) {
      const b64 = [...t.matchAll(/"value"\s*:\s*"([A-Za-z0-9+/=]{60,})"/g)].map(m => m[1]).slice(0, 2);
      console.log('     b64 candidates:', b64.map(x => x.slice(0, 60)));
    }
  } catch (e) { console.log('  ', l, 'ERR', e.message); }
}
