const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const SEP = `"])</script><script>self.__next_f.push([1,"`;
const home = await (await fetch("https://animecube.live/", { headers: { "User-Agent": UA } })).text();
const un = home.split(SEP).join("").split('\\"').join('"').split("\\u0026").join("&");
const STOP = new Set(["the", "and", "of", "a", "an", "to", "in", "for", "with", "its", "is", "are"]);
const tok = (t) => t.toLowerCase().normalize("NFD").replace(/\p{Mn}+/gu, "").replace(/[^a-z0-9]+/g, " ").split(" ").filter((x) => x.length >= 3 && !STOP.has(x));
const base = (s) => s.replace(/-season-\d+$/i, "").replace(/-\d+$/, "");
const re = new RegExp('"coverImage":"([^"]+)"[^}]*?"slug":"([a-z0-9-]+)"[^}]*?"title":"((?:[^"\\\\]|\\\\.)*)"', "g");
const cat = [...un.matchAll(re)].map((m) => ({ slug: m[2], title: m[3] }));
console.log("catalog:", cat.length);
function recs(slug, title) {
  const bs = base(slug), own = new Set(tok(title));
  const seasons = cat.filter((c) => c.slug !== slug && bs && base(c.slug) === bs);
  const used = new Set(seasons.map((s) => s.slug));
  const ranked = cat.filter((c) => c.slug !== slug && !used.has(c.slug)).map((c) => {
    const t = c.title || c.slug;
    const overlap = tok(t).filter((x) => own.has(x)).length;
    const sb = bs && base(c.slug) === bs;
    return { t, score: overlap + (sb ? 3 : 0) };
  }).sort((a, b) => b.score - a.score);
  const out = [];
  seasons.forEach((s) => out.push(s.title + " [SEASON]"));
  ranked.filter((r) => r.score > 0).forEach((r) => { if (out.length < 16) out.push(r.t + ` [sim=${r.score}]`); });
  return out.slice(0, 6);
}
console.log("\n=== perfect-world ===");
recs("perfect-world", "Perfect World").forEach((t) => console.log("   -", t));
console.log("\n=== soul-land-2-the-peerless-tang-clan ===");
recs("soul-land-2-the-peerless-tang-clan", "Soul Land 2 The Peerless Tang Clan").forEach((t) => console.log("   -", t));
console.log("\n=== ling-cage ===");
recs("ling-cage", "Ling Cage").forEach((t) => console.log("   -", t));
