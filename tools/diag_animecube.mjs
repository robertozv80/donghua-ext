// Diag AnimeCube: flight (temporadas), registry, sources y sinopsis
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const BASE = 'https://animecube.live';
const H = { 'User-Agent': UA };
const slugs = ['a-good-day-to-ascend', 'beyond-time-s-gaze'];

const balanced = (s, from, openCh, closeCh) => {
  const open = s.indexOf(openCh, from);
  if (open < 0) return null;
  let d = 0, inS = false, esc = false;
  for (let i = open; i < s.length; i++) {
    const c = s[i];
    if (esc) { esc = false; continue; }
    if (c === '\\' && inS) { esc = true; continue; }
    if (c === '"') inS = !inS;
    if (!inS && c === openCh) d++;
    else if (!inS && c === closeCh) { d--; if (!d) return s.slice(open, i + 1); }
  }
  return null;
};

async function main() {
  // registry
  const reg = await (await fetch(BASE + '/api/anime-sources-versions', { headers: H })).text();
  console.log('registry rawardless head:', reg.slice(0, 120));
  const plain = reg.includes('"bySeason"') ? reg : null;
  console.log('registry plain?', !!plain, 'len', reg.length);
  let bySeason = null;
  if (plain) { bySeason = plain; } else { console.log('registry CIFRADA -> decrypt KG'); return; }
  for (const s of slugs) {
    const m = bySeason.match(new RegExp('"' + s + '":\\s*(\\{)'));
    console.log('\n===', s, 'slug en registro?', !!m);
    if (!m) continue;
    const obj = balanced(bySeason, m.index + s.length + 2, '{', '}');
    const j = JSON.parse(obj);
    for (const [ptId, se] of Object.entries(j)) {
      for (const [seId, v] of Object.entries(se)) {
        console.log('  ver:', ptId, seId, v);
        const eps = [...obj.matchAll(new RegExp('"' + seId + '"', 'g'))];
      }
      // sources para ep 1: id = slug-seId-ep-1
      const seId2 = Object.keys(se)[0];
      const epId = s + '-' + seId2 + '-ep-1';
      const url = BASE + '/api/anime/' + s + '/episode/' + epId + '/sources?v=' + encodeURIComponent(Object.values(se)[0]) + '&primaryTabId=' + ptId + '&seasonId=' + seId2;
      try {
        const src = await (await fetch(url, { headers: { ...H, Accept: 'application/json' } })).text();
        console.log('  sources ep1 len', src.length, 'head:', src.slice(0, 400));
      } catch (e) { console.log('  sources ERR', e.message); }
    }
  }
  // flight para temporadas
  for (const s of slugs) {
    const flight = await (await fetch(BASE + '/anime/' + s + '?_rsc=1', { headers: { ...H, RSC: '1' } })).text();
    console.log('\n=== FLIGHT', s, 'len', flight.length);
    const eps = [...flight.matchAll(/"id":"([\w-]-ep-[\w-]+)"/g)].map(m => m[1]);
    console.log(' episode ids en flight:', eps.slice(0, 10));
    // sinopsis
    const desc = (flight.match(/"description":"((?:[^"\\]|\\.)*)"/) || [])[1];
    const synopsis = (flight.match(/"synopsis":"((?:[^"\\]|\\.)*)"/) || [])[1];
    console.log(' description:', (desc || '').slice(0, 180));
    console.log(' synopsis:', (synopsis || '').slice(0, 180));
  }
  // SSR html: meta description
  const html = await (await fetch(BASE + '/anime/' + slugs[0], { headers: H })).text();
  const md = (html.match(/<meta name="description" content="([^"]*)"/) || [])[1];
  const ogd = (html.match(/<meta property="og:description" content="([^"]*)"/) || [])[1];
  console.log('\nmeta description:', md);
  console.log('og:description:', ogd);
}
main().catch(e => console.log('FATAL', e));
