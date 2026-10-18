const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = async (u) => (await fetch(u, { headers: { "User-Agent": UA } })).text();

let series = process.argv[2];
if (!series) {
  const s = await get("https://luciferdonghua.org/?s=Supreme+Sword+God");
  const links = [...new Set([...s.matchAll(/href="(https:\/\/luciferdonghua\.org\/[a-z0-9-]+\/)"/gi)].map((m) => m[1]))];
  console.log("search results:", links.slice(0, 12).join("\n"));
  series = links.find((l) => /supreme-sword-god/.test(l));
}
console.log("\nseries:", series);
const t = await get(series);
const lis = [...t.matchAll(/<li>\s*<a[^>]*href="([^"]+)"[\s\S]*?<\/li>/g)].map((m) => m[0]);
console.log("li entries:", lis.length, "| eplister present:", /eplister/.test(t));
const rows = [];
for (const li of lis) {
  const href = /href="([^"]+)"/.exec(li)?.[1];
  const num = /class="epl-num">([^<]*)</.exec(li)?.[1]?.trim();
  const title = /class="epl-title">([^<]*)</.exec(li)?.[1]?.trim();
  if (href && /episode/i.test(href)) rows.push({ num, title, href: href.replace("https://luciferdonghua.org/", "") });
}
console.log("episode rows:", rows.length);
const byNum = {};
for (const r of rows) (byNum[r.num || "?"] ||= []).push(r);
for (const k of Object.keys(byNum).filter((k) => byNum[k].length > 1)) {
  console.log(`DUPLICATE num ${k}:`);
  byNum[k].forEach((r) => console.log("   title:", r.title, "| href:", r.href));
}
for (const n of ["46", "47", "55", "56", "57", "95", "96", "97"]) {
  const r = byNum[n];
  if (r) r.forEach((x) => console.log(`  num=${n} title="${x.title}" href=${x.href}`));
  else console.log(`  num=${n} ABSENT`);
}
