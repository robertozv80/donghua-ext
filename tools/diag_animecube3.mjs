// Diag AnimeCube II: sinopsis real + sitemap duplicados
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const slug = 'a-good-day-to-ascend';
const html = await (await fetch('https://animecube.live/anime/' + slug, { headers: { 'User-Agent': UA } })).text();
console.log('len', html.length);
// Buscar textos largos en el HTML (sinopsis real)
const texts = [...html.matchAll(/>([^<>{}]{80,600})</g)].map(m => m[1].trim()).filter(t => !t.includes('function') && !t.includes('{') && !t.includes('window.'));
console.log('textos largos:', texts.length);
texts.slice(0, 8).forEach(t => console.log('  *', t.slice(0, 200)));
// donde aparece la palabra Watch/Donghua/Sinop
for (const kw of ['A Good Day', 'synopsis', 'description', 'Watch ', 'cultivat']) {
  const re = new RegExp('.{60}' + kw + '.{160}', 'i');
  const m = html.match(re);
  if (m) console.log('KW [' + kw + ']:', m[0].replace(/\s+/g, ' '));
}
// JSON-LD
const jld = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map(m => m[1]);
console.log('\nJSON-LD count:', jld.length);
jld.forEach((j, i) => console.log('  ', i, j.slice(0, 400)));

// sitemap duplicados
const sm = await (await fetch('https://animecube.live/sitemap.xml', { headers: { 'User-Agent': UA } })).text();
const slugs = [...sm.matchAll(/<loc>\/anime\/([a-z0-9-]+)<\/loc>/g)].map(m => m[1]);
const slugs2 = [...sm.matchAll(/<loc>https:\/\/animecube\.live\/anime\/([a-z0-9-]+)<\/loc>/g)].map(m => m[1]);
const all = (slugs.length ? slugs : slugs2);
const dupCount = {};
all.forEach(s => dupCount[s] = (dupCount[s] || 0) + 1);
const dups = Object.entries(dupCount).filter(([, n]) => n > 1);
console.log('\nmismoSlugDuplicadoEnSitemap:', dups.length, dups.slice(0, 10));
// variantes de slug comparten base
const bases = {};
all.forEach(s => { const b = s.replace(/-season-\d+$/, '').replace(/-\d+$/, ''); if (!bases[b]) bases[b] = []; bases[b].push(s); });
const multi = Object.entries(bases).filter(([, arr]) => arr.length > 1);
console.log('slugs mismo base (busqueda duplicada):', multi.length, multi.slice(0, 10));
