const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = (u, ref) => fetch(u, { headers: { "User-Agent": UA, ...(ref ? { Referer: ref } : {}) } }).then((r) => r.text());

const ficha = await get("https://animexin.dev/anime/shrouding-the-heavens/");
console.log("== eplister de shrouding-the-heavens (primeros 5):");
const lis = [...ficha.matchAll(/<li[^>]*>\s*<a[^>]+href="([^"]+)"[\s\S]{0,600}?<div class="epl-num">([^<]*)<\/div>[\s\S]{0,300}?<div class="epl-title">([^<]*)<\/div>/g)];
lis.slice(0, 5).forEach((m) => console.log(`   ep ${m[2]} -> ${m[1]} (${m[3].trim()})`));
if (lis.length === 0) {
  const raw = [...ficha.matchAll(/class="eplister"[\s\S]{0,2000}?href="([^"]+)"/g)];
  console.log("   (regex amplia) hrefs:", raw.slice(0, 5).map((m) => m[1]));
}

// URL del usuario (con typo "shrounding") vs real
for (const u of [
  "https://animexin.dev/shrounding-the-heavens-episode-1-subtitle-indonesia-english/",
  "https://animexin.dev/shrouding-the-heavens-episode-1-subtitle-indonesia-english/",
]) {
  const r = await fetch(u, { headers: { "User-Agent": UA } });
  const t = await r.text();
  console.log(`\n== ${u}\n   HTTP ${r.status} len=${t.length} | title="${(t.match(/<title>([^<]*)<\/title>/) || [])[1] ?? "?"}" | mirrors=${(t.match(/select class="mirror/gi) || []).length}`);
}

// Pagina real de un episodio reciente (ep1 real desde eplister si existe)
const epUrl = lis[0]?.[1] || (ficha.match(/class="eplister"[\s\S]{0,2000}?href="([^"]+)"/) || [])[1];
if (epUrl) {
  console.log("\n== EP real:", epUrl);
  const ep = await get(epUrl);
  console.log("   len:", ep.length, "| title:", (ep.match(/<title>([^<]*)<\/title>/) || [])[1]);
  console.log("   select.mirror:", (ep.match(/select class="mirror/gi) || []).length);
  const opts = [...ep.matchAll(/<option[^>]*value="([^"]*)"[^>]*>([^<]*)<\/option>/g)];
  opts.slice(0, 10).forEach((o) => console.log(`   - "${o[2].trim()}" len=${o[1].length} nl=${/\n/.test(o[1])}`));
  for (const o of opts.slice(0, 8)) {
    try {
      const dec = Buffer.from(o[1].trim(), "base64").toString("utf8");
      console.log(`   [${o[2].trim()}] -> ${dec.replace(/\s+/g, " ").slice(0, 180)}`);
    } catch (e) {
      console.log(`   [${o[2].trim()}] ERR ${e.message}`);
    }
  }
}
