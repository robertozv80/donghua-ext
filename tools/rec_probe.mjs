const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = async (u) => {
  try {
    const r = await fetch(u, { headers: { "User-Agent": UA } });
    return { s: r.status, t: await r.text() };
  } catch (e) {
    return { s: -1, t: "", err: e.message };
  }
};

const pages = [
  ["AnimeXin", "https://animexin.dev/the-guardian-of-daxia/"],
  ["LmAnime", "https://lmanime.com/a-mortals-journey-to-immortality-season-3/"],
  ["Lucifer", "https://luciferdonghua.org/anime/supreme-sword-god/"],
];

for (const [name, url] of pages) {
  const { s, t } = await get(url);
  console.log(`\n##### ${name} (${s}) ${url} len=${t.length}`);
  // headings that could mark a related block
  const heads = [...t.matchAll(/<(h2|h3)[^>]*>([\s\S]{0,80}?)<\/\1>/gi)].map((m) => m[2].replace(/<[^>]+>/g, "").trim()).filter(Boolean);
  console.log("  headings:", [...new Set(heads)].slice(0, 14).join(" | "));
  for (const kw of ["Recommended", "Related", "Similar", "You May Also Like", "También", "Tambien", "May Also"]) {
    const i = t.search(new RegExp(kw, "i"));
    if (i >= 0) console.log(`  [${kw}] ctx:`, t.slice(i - 120, i + 160).replace(/\s+/g, " "));
  }
  // eplister/series-list count and search endpoint sanity
  const arts = [...t.matchAll(/<article[\s\S]*?<\/article>/g)].length;
  console.log("  articles on series page:", arts);
}

// search endpoints
console.log("\n##### search endpoints");
for (const [name, u] of [
  ["AnimeXin s=", "https://animexin.dev/?s=guardian"],
  ["LmAnime s=", "https://lmanime.com/?s=mortal"],
  ["Lucifer s=", "https://luciferdonghua.org/?s=supreme"],
]) {
  const { s, t } = await get(u);
  const arts = [...t.matchAll(/<article[\s\S]*?<\/article>/g)];
  const first = arts[0] ? (/<a[^>]*href="([^"]+)"[^>]*title="([^"]*)"/.exec(arts[0]) || []) : [];
  console.log(`  ${name} (${s}) articles=${arts.length} firstUrl=${first[1] || "-"} title=${first[2] || "-"}`);
}
