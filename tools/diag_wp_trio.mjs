const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = (u, ref) => fetch(u, { headers: { "User-Agent": UA, ...(ref ? { Referer: ref } : {}) } }).then((r) => r.text());

const imgInfo = (html, n = 2) => {
  const m = html.match(/<img[^>]+>/g) || [];
  return m.slice(0, n).map((t) =>
    t.replace(/\s+(alt|title|decoding|loading|width|height|srcset|sizes|fetchpriority|class)="[^"]*"/g, "").replace(/\s+/g, " ").trim()
  );
};

const speDump = (html) => {
  const i = html.indexOf('class="spe"');
  if (i < 0) return "(sin .spe)";
  return html.slice(i, i + 1200).replace(/\s+/g, " ");
};

const ratingDump = (html) => {
  const hits = [];
  for (const pat of ['class="rating', 'itemprop="ratingValue', 'class="rt"', '<strong class="rating', 'ratingValue']) {
    const i = html.indexOf(pat);
    if (i >= 0) hits.push(html.slice(Math.max(0, i - 100), i + 200).replace(/\s+/g, " "));
  }
  return hits.slice(0, 2);
};

async function wpSite(name, ficha, epPage) {
  console.log("\n########", name);
  const home = await get(ficha.replace(/\/anime\/.*$/, "/")); // home del sitio
  console.log("-- HOME article img[0..1]:");
  const arts = home.split(/<article[\s>]/).slice(1, 4);
  arts.forEach((a) => console.log("   ", imgInfo(a, 1)[0] || "(sin img)"));

  const f = await get(ficha);
  console.log("-- FICHA og:image:", (f.match(/<meta[^>]+og:image[^>]+>/g) || ["(no og:image)"])[0]);
  const thumb = f.match(/<div class="thumb[^"]*">[\s\S]{0,600}?<img[^>]+>/);
  console.log("-- FICHA .thumb img:", thumb ? thumb[0].replace(/\s+/g, " ").slice(0, 400) : "(no .thumb)");
  const tspi = f.match(/<img[^>]+ts-post-image[^>]*>/);
  console.log("-- FICHA ts-post-image:", tspi ? tspi[0].replace(/\s+/g, " ").slice(0, 400) : "(no ts-post-image)");
  console.log("-- FICHA .spe:", speDump(f).slice(0, 1100));
  console.log("-- FICHA rating:", ratingDump(f).join(" || ").slice(0, 500) || "(nada)");

  console.log("-- EPISODIO:", epPage);
  const ep = await get(epPage);
  const opts = [...ep.matchAll(/<option[^>]*value="([^"]*)"[^>]*>([^<]*)<\/option>/g)];
  console.log("   opciones select.mirror:", opts.length);
  opts.slice(0, 8).forEach((o) => console.log(`   - label="${o[2].trim()}" value.len=${o[1].length} head="${o[1].slice(0, 60).replace(/\n/g, "\\n")}" nl=${/\n/.test(o[1])}`));
  return { ep };
}

const lm = await wpSite("LmAnime", "https://lmanime.com/swallowed-star/", "https://lmanime.com/glorious-revenge-of-ye-feng-episode-01/");
console.log("\n== LmAnime opciones crudas (episode01):");
const rawOpts = [...lm.ep.matchAll(/<option[^>]*value="([^"]*)"[^>]*>([^<]*)<\/option>/g)];
rawOpts.forEach((o) => console.log(`   value="${o[1].slice(0, 90)}" label="${o[2].trim()}"`));

const ax = await wpSite("AnimeXin", "https://animexin.dev/anime/shrouding-the-heavens/", "https://animexin.dev/shrouding-the-heavens-episode-1-subtitle-indonesia-english/");
console.log("\n== AnimeXin decode opcion 1:");
const axOpts = [...ax.ep.matchAll(/<option[^>]*value="([^"]*)"[^>]*>([^<]*)<\/option>/g)];
for (const o of axOpts.slice(0, 6)) {
  try {
    const dec = Buffer.from(o[1].trim(), "base64").toString("utf8");
    console.log(`   [${o[2].trim()}] -> ${dec.replace(/\s+/g, " ").slice(0, 160)}`);
  } catch (e) {
    console.log(`   [${o[2].trim()}] DECODE ERR ${e.message}`);
  }
}

const lf = await wpSite("LuciferDonghua", "https://luciferdonghua.org/anime/supreme-god-emperor/", "https://luciferdonghua.org/supreme-god-emperor-episode-646-english-subtitles/");
console.log("\n== Lucifer decode opciones (ep646):");
const lfOpts = [...lf.ep.matchAll(/<option[^>]*value="([^"]*)"[^>]*>([^<]*)<\/option>/g)];
for (const o of lfOpts.slice(0, 8)) {
  try {
    const dec = Buffer.from(o[1].trim(), "base64").toString("utf8");
    console.log(`   [${o[2].trim()}] -> ${dec.replace(/\s+/g, " ").slice(0, 200)}`);
  } catch (e) {
    console.log(`   [${o[2].trim()}] DECODE ERR ${e.message}`);
  }
}
