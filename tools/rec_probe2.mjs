const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = async (u) => {
  try {
    const r = await fetch(u, { headers: { "User-Agent": UA } });
    return { s: r.status, t: await r.text() };
  } catch (e) { return { s: -1, t: "", err: e.message }; }
};

const { s, t } = await get("https://lmanime.com/a-record-of-a-mortals-journey-to-immortality-season-05/");
console.log(`LmAnime series page (${s}) len=${t.length}`);
const heads = [...t.matchAll(/<(h2|h3)[^>]*>([\s\S]{0,90}?)<\/\1>/gi)].map((m) => m[2].replace(/<[^>]+>/g, "").trim()).filter(Boolean);
console.log("  headings:", [...new Set(heads)].slice(0, 16).join(" | "));
for (const kw of ["Recommended Series", "Related", "Similar"]) {
  const i = t.search(new RegExp(kw, "i"));
  if (i >= 0) console.log(`  [${kw}]`, t.slice(i - 100, i + 200).replace(/\s+/g, " "));
}
console.log("  articles:", [...t.matchAll(/<article[\s\S]*?<\/article>/g)].length);

console.log("\n=== catalogs / pagination ===");
for (const [name, u] of [
  ["LmAnime /anime/", "https://lmanime.com/anime/"],
  ["LmAnime /anime/page/2/", "https://lmanime.com/anime/page/2/"],
  ["Lucifer /anime/", "https://luciferdonghua.org/anime/"],
  ["Lucifer /anime/page/2/", "https://luciferdonghua.org/anime/page/2/"],
  ["AnimeXin /page/2/", "https://animexin.dev/page/2/"],
  ["AnimeXin /anime/", "https://animexin.dev/anime/"],
]) {
  const r = await get(u);
  const arts = [...r.t.matchAll(/<article[\s\S]*?<\/article>/g)].length;
  console.log(`  ${name}: ${r.s} len=${r.t.length} articles=${arts}`);
}
