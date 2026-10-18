// AnimeCube III: Movies hrefs en home + campos de la ficha en flight
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const home = await (await fetch('https://animecube.live/', { headers: { 'User-Agent': UA } })).text();
// todas las secciones idx de 'Movies' o 'Movie'
for (const kw of ['Movies', 'Movie', 'Recommend']) {
  const idx = home.indexOf(kw);
  console.log('KW', kw, 'idx', idx);
  if (idx > 0) console.log(' ctx:', home.slice(Math.max(0, idx - 320), idx + 500).replace(/\s+/g, ' ').slice(0, 780));
}
// hrefs del home con /anime/ y con ?query
const hrefs = [...new Set([...home.matchAll(/href="([^"]*\/[^"]*anime[^"]*)"/g)].map(m => m[1]))];
console.log('\nhrefs anime total:', hrefs.length);
const withQ = hrefs.filter(h => h.includes('?'));
console.log('con query:', withQ.slice(0, 10));
const movies = hrefs.filter(h => /movie/i.test(h));
console.log('href con movie:', movies.slice(0, 10));

// Flight: mostrar todas las CLAVES del objeto principal de una ficha movie/clasico
const slug = 'a-good-day-to-ascend';
const flight = await (await fetch('https://animecube.live/anime/' + slug + '?_rsc=1', { headers: { 'User-Agent': UA, RSC: '1' } })).text();
// objeto principal: buscar "slug":"a-good-day-to-ascend"
const i = flight.indexOf('"slug":"' + slug + '"');
if (i > 0) {
  const start = flight.lastIndexOf('{', i);
  // extraer objeto balanceado
  let d = 0, inS = false, esc = false, end = start;
  for (let n = start; n < flight.length; n++) {
    const c = flight[n];
    if (esc) { esc = false; continue; }
    if (c === '\\' && inS) { esc = true; continue; }
    if (c === '"') inS = !inS;
    if (!inS && c === '{') d++;
    if (!inS && c === '}') { d--; if (!d) { end = n; break; } }
  }
  const obj = flight.slice(start, end + 1);
  const keys = [...obj.matchAll(/(?:^|[, {])"([A-Za-z]+)":/g)].map(m => m[1]).filter((v, ix, a) => a.indexOf(v) === ix);
  console.log('\nKEYS del objeto principal:', keys.join(','));
  // valores de campos candidatas a sinopsis
  for (const k of ['description', 'synopsis', 'plot', 'summary', 'overview', 'story', 'about', 'sinopsis']) {
    const m = obj.match(new RegExp('"' + k + '":"(?:[^"\\\\]|\\\\.)*"'));
    if (m) console.log('  ' + k + ':', m[0].slice(0, 240));
  }
}
