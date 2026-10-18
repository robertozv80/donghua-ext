const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const html = await (await fetch("https://animecube.live/", { headers: { "User-Agent": UA } })).text();
console.log("home len", html.length);
const un = html.split('\\"').join('"').split("\\u0026").join("&");

const metaRe = /"coverImage":"([^"]+)"[^}]*?"genres":\[[^\]]*\][^}]*?"slug":"([a-z0-9-]+)"[^}]*?"title":"((?:[^"\\]|\\.)*)"/g;
const items = {};
let m;
while ((m = metaRe.exec(un))) items[m[2]] = { poster: m[1], title: m[3] };
console.log("catalog items (coverImage+genres+slug+title):", Object.keys(items).length);

// simpler: just coverImage ... slug
const simple = /"coverImage":"([^"]+)"[^}]{0,400}?"slug":"([a-z0-9-]+)"/g;
const simpleItems = {};
while ((m = simple.exec(un))) simpleItems[m[2]] = m[1];
console.log("catalog items (coverImage..slug):", Object.keys(simpleItems).length);

for (const q of ["perfect-world", "soul-land-2", "beyond-time", "a-good-day"]) {
  const hits = Object.keys(simpleItems).filter((s) => s.includes(q));
  console.log(`\n## ${q}: ${hits.join(", ") || "NONE"}`);
  for (const h of hits) {
    console.log(`   slug=${h}`);
    console.log(`   simplePoster=${simpleItems[h]}`);
    console.log(`   strictItem=${items[h] ? JSON.stringify(items[h]).slice(0, 160) : "(not matched by strict regex)"}`);
  }
}
// dump raw context for one
const i = un.indexOf('"slug":"perfect-world');
console.log("\nraw ctx around perfect-world:\n", un.slice(Math.max(0, i - 700), i + 120).replace(/\s+/g, " "));
