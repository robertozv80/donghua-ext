const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const BASE = "https://www.donghuazone.com";
const FEED = "/feeds/posts/default";
const get = async (u) => (await fetch(u, { headers: { "User-Agent": UA } })).text();

// 1) Movies label feed
const mv = await get(`${BASE}${FEED}/-/Movie?alt=json&max-results=10`);
let mj = null;
try { mj = JSON.parse(mv); } catch (e) { console.log("Movie feed not JSON:", mv.slice(0, 120)); }
const mvE = mj?.feed?.entry || [];
console.log("Movie feed entries:", mvE.length, "total:", mj?.feed?.["openSearch$totalResults"]?.["$t"]);
mvE.slice(0, 5).forEach((e) => console.log("   ", e.title?.["$t"], "|", (e.link || []).find((l) => l.rel === "alternate")?.href));

// 2) series feed for Lingwu Continent: find its non-genre label
const q = await get(`${BASE}${FEED}?alt=json&q=${encodeURIComponent("lingwu-continent-episode-183-4k-indo")}&max-results=5`);
const qj = JSON.parse(q);
const ent = qj?.feed?.entry || [];
console.log("\nq= post entries:", ent.length);
if (ent[0]) {
  const cats = (ent[0].category || []).map((c) => c.term);
  console.log("   labels:", cats.join(", "));
  console.log("   thumb:", ent[0].media$thumbnail?.url);
  console.log("   content len:", ent[0].content?.$t?.length);
}

// 3) any rating on a Movie post page?
const firstUrl = (mvE[0]?.link || []).find((l) => l.rel === "alternate")?.href;
if (firstUrl) {
  const html = await get(firstUrl);
  console.log("\npost page:", firstUrl, "len", html.length);
  const hasRating = /numscore|ratingValue|itemprop="rating"|rtp|rating-prc/i.test(html);
  console.log("   rating markup present:", hasRating);
  const idx = html.search(/numscore|ratingValue|rating-prc/i);
  if (idx >= 0) console.log("   ctx:", html.slice(idx - 150, idx + 150).replace(/\s+/g, " "));
  // header/ficha elements
  for (const sel of ["og:image", "og:description", "entry-title", "timestamp", "post-body"]) {
    const i = html.indexOf(sel);
    console.log(`   [${sel}] ${i >= 0 ? "yes" : "no"}`);
  }
}
