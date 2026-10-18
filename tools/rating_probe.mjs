const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const targets = [
  ["LmAnime genre", "https://lmanime.com/genres/action/"],
  ["LmAnime completed", "https://lmanime.com/anime/?status=completed&order=latest"],
  ["Lucifer genre", "https://luciferdonghua.org/anime/?genre%5B%5D=action&genre%5B%5D=adventure&genre%5B%5D=martial-arts&status=&type=&order=latest"],
  ["Lucifer completed", "https://luciferdonghua.org/anime/?status=completed&sub=&order=latest"],
  ["DonghuaZone label Action", "https://www.donghuazone.com/search/label/Action?&max-results=10"],
];
for (const [name, url] of targets) {
  try {
    const r = await fetch(url, { headers: { "User-Agent": UA } });
    const t = await r.text();
    const arts = [...t.matchAll(/<article[\s\S]*?<\/article>/g)].map((m) => m[0]);
    const totalNs = (t.match(/numscore/g) || []).length;
    const artNs = arts.filter((a) => a.includes("numscore")).length;
    const attrRating = arms => (a) => /data-rating|ratingValue|rating"/.test(a);
    console.log(`\n## ${name} (${r.status}, len ${t.length})`);
    console.log(`   articles=${arts.length} numscore_total=${totalNs} articles_with_numscore=${artNs}`);
    const first = arts[0] || "";
    const m = first.match(/<div class="bt">[\s\S]{0,220}?<\/div>/);
    console.log("   first card 'bt':", m ? m[0].replace(/\s+/g, " ") : "(none)");
    const rt = [...first.matchAll(/class="([^"]*(?:rating|score|epx|sb)[^"]*)"/gi)].map((x) => x[1]);
    console.log("   rating-ish classes in card:", [...new Set(rt)].join(", ") || "(none)");
  } catch (e) {
    console.log(`\n## ${name}: ERR ${e.message}`);
  }
}
