const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = async (u) => {
  try { const r = await fetch(u, { headers: { "User-Agent": UA } }); return { s: r.status, t: await r.text() }; }
  catch (e) { return { s: -1, t: "", err: e.message }; }
};

// AnimeCube series page
const ac = await get("https://animecube.live/anime/beyond-time-s-gaze");
console.log(`AnimeCube series page (${ac.s}) len=${ac.t.length}`);
const acUn = ac.t.split('\\"').join('"');
for (const kw of ["Recommended", "Similar", "related", "You May", "For You", "More Like"]) {
  const i = acUn.search(new RegExp(kw, "i"));
  if (i >= 0) console.log(`  [${kw}]`, acUn.slice(i - 80, i + 120).replace(/\s+/g, " "));
}
console.log("  has coverImage:", /"coverImage":"([^"]+)"/.exec(acUn)?.[1]?.slice(0, 80));
console.log("  series-slug hrefs:", (acUn.match(/href="\/anime\/[a-z0-9-]+"/g) || []).length);
console.log("  api-like paths:", [...new Set([...acUn.matchAll(/"\/api\/[^"]+"/g)].map((m) => m[0]))].slice(0, 6));

// DonghuaZone post page
const dz = await get("https://www.donghuazone.com/2026/06/lingwu-continent-episode-183-4k-indo.html");
console.log(`\nDonghuaZone post (${dz.s}) len=${dz.t.length}`);
for (const kw of ["related", "Related", "You may", "Popular", "Recommend"]) {
  const i = dz.t.search(new RegExp(kw, "i"));
  if (i >= 0) console.log(`  [${kw}]`, dz.t.slice(i - 80, i + 120).replace(/\s+/g, " "));
}
