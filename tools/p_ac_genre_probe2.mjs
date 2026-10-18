const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

const r = await fetch("https://animecube.live/", { headers: { "User-Agent": UA } });
const t = await r.text();

// Unescape the flight payload text so \" becomes "
const un = t.replace(/\\"/g, '"').replace(/\\u0026/g, "&");

// Count genre fields and collect distinct genres with slugs
const itemRe = /"genres":\[([^\]]*)\][^}]*?"slug":"([a-z0-9-]+)"/g;
const byGenre = new Map();
let m;
let n = 0;
while ((m = itemRe.exec(un)) !== null) {
  n++;
  const gl = m[1].split(",").map((s) => s.replace(/"/g, "").trim()).filter(Boolean);
  const slug = m[2];
  for (const g of gl) {
    if (!byGenre.has(g)) byGenre.set(g, new Set());
    byGenre.get(g).add(slug);
  }
}
console.log("items with genres:", n);
const sorted = [...byGenre.entries()].sort((a, b) => b[1].size - a[1].size);
console.log("distinct genres:", byGenre.size);
sorted.slice(0, 25).forEach(([g, s]) => console.log("  ", g, s.size));

// Also try reverse order key (slug before genres) since JSON key order varies
const itemRe2 = /"slug":"([a-z0-9-]+)"[^}]*?"genres":\[([^\]]*)\]/g;
let n2 = 0;
const byGenre2 = new Map();
while ((m = itemRe2.exec(un)) !== null) {
  n2++;
  const slug = m[1];
  const gl = m[2].split(",").map((s) => s.replace(/"/g, "").trim()).filter(Boolean);
  for (const g of gl) {
    if (!byGenre2.has(g)) byGenre2.set(g, new Set());
    byGenre2.get(g).add(slug);
  }
}
console.log("items (slug-first):", n2, "distinct genres:", byGenre2.size);
[...byGenre2.entries()].sort((a, b) => b[1].size - a[1].size).slice(0, 15).forEach(([g, s]) => console.log("  ", g, s.size));
