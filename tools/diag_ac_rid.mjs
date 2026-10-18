const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const slug = 'a-good-day-to-ascend';
const H = { 'User-Agent': UA, RSC: '1' };

const h1 = await (await fetch(`https://animecube.live/anime/${slug}?_rsc=1`, { headers: H })).text();
console.log('== _rsc=1, RSC:1 -> len', h1.length);

// ids tipo ep
const eps = [...new Set([...h1.matchAll(/"id":"(a-good-day[^"]*ep-[^"]*)"/g)].map(m => m[1]))];
console.log('ep ids:', eps);

// buscar 'episodes":['
const idx = h1.indexOf('"episodes":[');
console.log('idx episodes:', idx, h1.slice(idx, idx + 500));

// probar distintas variantes de fetch RSC
for (const u of [
  `https://animecube.live/anime/${slug}.txt?_rsc=1`,
  `https://animecube.live/anime/${slug}?_rsc=1`,
]) {
  const r = await fetch(u, { headers: { ...H, 'Next-Router-Prefetch': '1' } });
  const t = await r.text();
  console.log('---', u, '->', t.length, 'hasEp:', t.includes('"episodes"'));
}
// variante con query from&season&episode como la web
const r2 = await fetch('https://animecube.live/anime/' + slug + '?from=home&season=tab-1&episode=' + slug + '-tab-1-ep-1', { headers: { ...H, RSC: '1' } });
const t2 = await r2.text();
console.log('--- con query episode ->', t2.length, 'hasEp:', t2.includes('"episodes"'));
const i2 = t2.indexOf('"episodes":[');
console.log(t2.slice(i2, i2 + 400));
// sinopsis deep
for (const k of ['synopsis', 'descriptionS', 'overview', 'plot', 'story']) {
  const i = t2.toLowerCase().indexOf(k.toLowerCase());
  if (i >= 0) console.log('[' + k + ']', t2.slice(i, i + 240));
}
