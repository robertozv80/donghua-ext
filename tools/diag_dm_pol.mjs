// Diag: embedder correcto = la URL del embed DM (geo.dailymotion.com), no la del sitio.
// En las paginas del sitio el iframe suele ser geo.dailymotion.com/player.html?video=X
// con src que apunta a dailymotion.com -> si embedder solo pega el sitio, DM rechaza.
// Politicas conocidas de DM: embedder debe coincidir con el HOST que carga el player.
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const get = (u, ref, extra = {}) => fetch(u, { headers: { 'User-Agent': UA, ...(ref ? { Referer: ref } : {}), ...extra } }).then(r => r.text());

// Página del sitio -> ver el iframe real
const pages = [
  ['mortals195', 'https://animexin.dev/a-record-of-mortals-journey-to-immortality-episode-195-indonesia-english-sub/'],
  ['beyond43', 'https://luciferdonghua.org/beyond-times-gaze-episode-43-english-subtitle/'],
];
for (const [n, u] of pages) {
  try {
    const t = await get(u, 'https://www.google.com/');
    const ifr = [...t.matchAll(/<iframe[^>]*>/gi)].map(m => m[0]).filter(x => /dailymotion/i.test(x));
    console.log(n, 'iframes DM:', ifr.slice(0, 4));
  } catch (e) { console.log(n, 'ERR', e.message); }
}

// Con el iframe geo.dailymotion.com verificado, probar metadata con 3 embedders
const tests = [
  ['site', 'https://animexin.dev/'],
  ['noEmbedder', ''],
  ['geoPlayer', 'https://geo.dailymotion.com/player.html?video=k1slp8qR9ukY7ez4V3W'],
  ['embedDm', 'https://www.dailymotion.com/embed/video/k1slp8qR9ukY7ez4V3W'],
];
for (const [n, ref] of tests) {
  try {
    const t = await get('https://www.dailymotion.com/player/metadata/video/k1slp8qR9ukY7ez4V3W?embedder=' + encodeURIComponent(ref) + '&integration=inline', ref);
    let j = null; try { j = JSON.parse(t); } catch {}
    if (j && j.error) console.log('embedder=' + n, 'ERR', j.error.title);
    else {
      console.log('embedder=' + n, 'OK title=', JSON.stringify(j?.title||''), 'qualities=', j?.qualities ? Object.keys(j.qualities).join(',') : 'NO');
    }
  } catch (e) { console.log('embedder=' + n, 'ERR', e.message); }
}
