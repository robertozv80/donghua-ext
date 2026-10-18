const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = async (u) => (await fetch(u, { headers: { "User-Agent": UA } })).text();
const SEP = `"])</script><script>self.__next_f.push([1,"`;
const STOP = new Set(["the", "and", "of", "a", "an", "to", "in", "for", "with", "its", "is", "are"]);
const tokens = (t) => t.toLowerCase().normalize("NFD").replace(/\p{Mn}+/gu, "").replace(/[^a-z0-9]+/g, " ")
  .split(" ").filter((x) => x.length >= 3 && !STOP.has(x));
const acBase = (s) => s.replace(/-season-\d+$/i, "").replace(/-\d+$/, "");

// --- AnimeCube ---
const home = await get("https://animecube.live/");
const un = home.split(SEP).join("").split('\\"').join('"').split("\\u0026").join("&");
const cat = new Map();
for (const m of un.matchAll(/"coverImage":"([^"]+)"[^}]*?"slug":"([a-z0-9-]+)"[^}]*?"title":"((?:[^"\\]|\\.)*)"/g)) {
  cat.set(m[2], { poster: m[1], title: m[3] });
}
for (const m of un.matchAll(/"rating":([0-9.]+)[^}]*?"slug":"([a-z0-9-]+)"/g)) {
  const c = cat.get(m[2]); if (c) c.rating = parseFloat(m[1]);
}
console.log("AnimeCube catalog:", cat.size);

function acRecs(slug, title) {
  const items = [...cat.entries()].filter(([s]) => s !== slug).map(([s, v]) => ({ slug: s, ...v }));
  const bs = acBase(slug);
  const own = new Set(tokens(title));
  const seasons = items.filter((c) => bs && acBase(c.slug) === bs)
    .sort((a, b) => (Number(/(-season)?-(\d+)$/.exec(a.slug)?.[2] ?? 0)) - (Number(/(-season)?-(\d+)$/.exec(b.slug)?.[2] ?? 0)));
  const used = new Set(seasons.map((s) => s.slug));
  const ranked = items.filter((c) => !used.has(c.slug)).map((c) => {
    const t = c.title || c.slug;
    const overlap = tokens(t).filter((x) => own.has(x)).length;
    const sameBase = bs && acBase(c.slug) === bs;
    return { c, score: overlap + (sameBase ? 3 : 0), len: tokens(t).length };
  }).sort((a, b) => b.score - a.score || b.len - a.len);
  const out = [];
  seasons.forEach((s) => out.push(s.title));
  ranked.filter((r) => r.score > 0).forEach((r) => { if (out.length < 16) out.push(r.c.title + ` [sim=${r.score}]`); });
  ranked.filter((r) => r.score <= 0).forEach((r) => { if (out.length < 16) out.push(r.c.title + " [random]"); });
  return out.slice(0, 16);
}
console.log("\n=== AnimeCube recs para 'Beyond Time's Gaze' ===");
acRecs("beyond-time-s-gaze", "Beyond Time's Gaze").slice(0, 10).forEach((t) => console.log("   -", t));
console.log("\n=== AnimeCube recs para 'Soul Land 2 The Peerless Tang Clan' ===");
acRecs("soul-land-2-the-peerless-tang-clan", "Soul Land 2 The Peerless Tang Clan").slice(0, 8).forEach((t) => console.log("   -", t));

// --- DonghuaZone ---
const FEED = "/feeds/posts/default";
const norm = (t) => t.toLowerCase().normalize("NFD").replace(/\p{Mn}+/gu, "").replace(/[^a-z0-9]+/g, " ").trim();
const labelOf = (e) => {
  const cats = (e.category || []).map((c) => c.term);
  const GENRE = new Set(["Action","Adventure","Comedy","Drama","Fantasy","Historical","Horror","Magic","Martial Arts","Mecha","Music","Mystery","Romance","School","Sci-Fi","Seinen","Shoujo","Shounen","Slice of Life","Space","Sport","Super Power","Supernatural","Thriller","Vampire","Isekai","Danger","Hot","Sub","Episode","Eps. Donghua"]);
  return [...cats].reverse().find((c) => !GENRE.has(c) && c !== "Eps. Donghua") || null;
};
console.log("\n=== DonghuaZone recs para 'Lingwu Continent' ===");
const q = norm("Lingwu Continent").replace(/\s+\d+$/, "").trim();
const qj = JSON.parse(await get(`https://www.donghuazone.com${FEED}?alt=json&q=${encodeURIComponent(q)}&max-results=25`));
const ents = qj.feed.entry || [];
console.log("  feed q=" + q, "entries:", ents.length);
const seenSeries = new Set();
ents.forEach((e) => {
  const label = labelOf(e);
  if (!label) return;
  const ln = norm(label);
  if (ln !== q && !ln.startsWith(q)) return;
  if (!seenSeries.add(ln)) return;
  const href = (e.link || []).find((l) => l.rel === "alternate")?.href;
  console.log("   - serie:", label, "->", e.title.$t, "|", href);
});
const rnd = 1 + Math.floor(Math.random() * 8) * 25;
const pj = JSON.parse(await get(`https://www.donghuazone.com${FEED}?alt=json&max-results=25&start-index=${rnd}`));
console.log("  random fill page start-index=" + rnd, "entries:", (pj.feed.entry || []).length);
