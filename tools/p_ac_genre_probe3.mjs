const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

const r = await fetch("https://animecube.live/", { headers: { "User-Agent": UA } });
const raw = await r.text();
const un = raw.replace(/\\"/g, '"').replace(/\\u0026/g, "&");

// 1) genre + slug
const genreRe = /"genres":\[([^\]]*)\][^}]*?"slug":"([a-z0-9-]+)"/g;
const items = [];
let m;
while ((m = genreRe.exec(un)) !== null) {
  items.push({ genres: m[1].split(",").map((s) => s.replace(/"/g, "").trim()).filter(Boolean), slug: m[2] });
}
console.log("genre+slug items:", items.length);

// 2) poster + slug + title (single combined pattern)
const metaRe = /"coverImage":"([^"]+)"[^}]*?"genres":\[[^\]]*\][^}]*?"slug":"([a-z0-9-]+)"[^}]*?"title":"((?:[^"\\]|\\.)*)"/g;
const meta = new Map();
let m2;
let mn = 0;
while ((m2 = metaRe.exec(un)) !== null) {
  mn++;
  meta.set(m2[2], { poster: m2[1], title: m2[3] });
}
console.log("meta items:", mn);
console.log("example:", JSON.stringify([...meta.entries()][0]));

// 3) coverage & genre counts from the merged view
const byGenre = new Map();
for (const it of items) for (const g of it.genres) byGenre.set(g, (byGenre.get(g) || 0) + 1);
console.log("Action:", byGenre.get("Action"), "Adventure:", byGenre.get("Adventure"), "Fantasy:", byGenre.get("Fantasy"), "Martial Arts:", byGenre.get("Martial Arts"));
const noMeta = items.filter((i) => !meta.has(i.slug)).map((i) => i.slug);
console.log("items without meta:", noMeta.length, noMeta.slice(0, 5));
