// Diag Lucifer: cards del home + /schedule/
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const get = (u) => fetch(u, { headers: { 'User-Agent': UA } }).then(r => r.text());

const t = await get('https://luciferdonghua.org/');
const arts = [...t.matchAll(/<article[\s\S]*?<\/article>/g)].map(m => m[0]).slice(0, 6);
console.log('== HOME, articles:', arts.length);
arts.forEach((a, i) => {
  const titleA = [...a.matchAll(/title="([^"]+)"/g)].map(x => x[1])[0] || '';
  const alt = [...a.matchAll(/alt="([^"]+)"/g)].map(x => x[1])[0] || '';
  const href = [...a.matchAll(/href="([^"]+)"/g)].map(x => x[1])[0] || '';
  const tt = (a.match(/class="[^"]*tt[\s\S]{0,80}?>([^<]+)</) || [])[1] || '';
  console.log(`  [${i}] title="${titleA}" alt="${alt}" tt="${tt.trim()}" href=${href.slice(0, 70)}`);
});

const sch = await get('https://luciferdonghua.org/schedule/');
console.log('\n== /schedule/ len', sch.length);
// estructura por dias
for (const d of ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']) {
  const i = sch.search(new RegExp(d, 'i'));
  console.log('  ', d, 'idx', i);
}
const i0 = sch.search(/Monday/i);
if (i0 >= 0) console.log(sch.slice(Math.max(0, i0 - 600), i0 + 900).replace(/\s+/g, ' '));
