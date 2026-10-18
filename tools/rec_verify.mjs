const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = async (u) => (await fetch(u, { headers: { "User-Agent": UA } })).text();
const normalize = (t) =>
  t.toLowerCase().normalize("NFD").replace(/\p{Mn}+/gu, "").replace(/[^a-z0-9]+/g, " ").trim();
const baseSlugOf = (s) => s.replace(/-season-\d+$/i, "").replace(/-\d+$/, "");

// --- Simula fetchRecommendations del tema WordPress (LmAnime/Lucifer/AnimeXin) ---
async function wpRecs(mainUrl, seriesUrl, seriesTitle, articleSel, getCard) {
  const all = [];
  const seen = new Set([seriesUrl]);
  const selfSlug = seriesUrl.replace(/\/$/, "").split("/").pop();
  const baseSlug = baseSlugOf(selfSlug);
  const baseNorm = normalize(seriesTitle).replace(/\s+(?:season\s+)?\d+$/, "").trim();

  if (baseSlug && baseSlug !== selfSlug) {
    try {
      const doc = await get(`${mainUrl}/?s=${encodeURIComponent(baseSlug.replace(/-/g, " "))}`);
      const arts = [...doc.matchAll(/<article[\s\S]*?<\/article>/g)].map((m) => m[0]);
      const seasons = [];
      for (const a of arts) {
        const card = getCard(a);
        if (!card) continue;
        const slug = card.url.replace(/\/$/, "").split("/").pop();
        if (seen.has(slug) || !slug.startsWith(baseSlug)) continue;
        seen.add(slug);
        const num = Number(/(-season)?-(\d+)$/.exec(slug)?.[2] ?? 0);
        seasons.push([num, card]);
      }
      seasons.sort((x, y) => x[0] - y[0]);
      seasons.forEach(([, c]) => all.push(c));
    } catch (e) { console.log("  step1 ERR", e.message); }
  }
  return { all, baseSlug, baseNorm, seen };
}

const card = (art) => {
  const linkEl = /<a[^>]*href="([^"]+)"[^>]*title="([^"]*)"/.exec(art) ||
    /<a[^>]*title="([^"]*)"[^>]*href="([^"]+)"/.exec(art);
  if (!linkEl) return null;
  const url = /^https?:/.test(linkEl[1]) ? linkEl[1] : linkEl[2];
  const title = /^https?:/.test(linkEl[1]) ? linkEl[2] : linkEl[1];
  if (!url) return null;
  return { url, title };
};

console.log("=== LmAnime: A Record of A Mortal's Journey to Immortality Season 05 ===");
const lm = await wpRecs(
  "https://lmanime.com",
  "https://lmanime.com/a-record-of-a-mortals-journey-to-immortality-season-05/",
  "A Record of A Mortal\u2019s Journey to Immortality Season 05",
  "article", card
);
console.log("  baseSlug:", lm.baseSlug);
console.log("  temporadas encontradas:", lm.all.length);
lm.all.slice(0, 10).forEach((c) => console.log("    -", c.title, "|", c.url.replace("https://lmanime.com/", "")));

console.log("\n=== Lucifer: Supreme Sword God ===");
const lu = await wpRecs(
  "https://luciferdonghua.org",
  "https://luciferdonghua.org/anime/supreme-sword-god/",
  "Supreme Sword God", "article", card
);
console.log("  baseSlug:", lu.baseSlug, "| temporadas:", lu.all.length);

console.log("\n=== AnimeXin: The Guardian of Daxia ===");
const ax = await wpRecs(
  "https://animexin.dev",
  "https://animexin.dev/the-guardian-of-daxia/",
  "The Guardian of Daxia", "article", card
);
console.log("  baseSlug:", ax.baseSlug, "| temporadas:", ax.all.length);

// "Recommended Series" block parse check (AnimeXin + Lucifer)
for (const [name, url] of [
  ["AnimeXin", "https://animexin.dev/the-guardian-of-daxia/"],
  ["LmAnime", "https://lmanime.com/a-record-of-a-mortals-journey-to-immortality-season-05/"],
]) {
  const t = await get(url);
  const i = t.search(/Recommended Series/i);
  const tail = t.slice(i, i + 6000);
  const arts = [...tail.matchAll(/<article[\s\S]*?<\/article>/g)].map((m) => m[0]);
  // stop at the closing of that listupd
  const stop = tail.indexOf("</div></div>");
  const scope = tail.slice(0, stop > 0 ? stop : tail.length);
  const artsScoped = [...scope.matchAll(/<article[\s\S]*?<\/article>/g)].map((m) => m[0]);
  console.log(`\n  ${name} "Recommended Series": articles_in_block=${artsScoped.length}`);
  artsScoped.slice(0, 6).forEach((a) => { const c = card(a); if (c) console.log("     -", c.title); });
}
