const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const series = process.argv[2] || "https://luciferdonghua.org/anime/supreme-sword-god/";
const t = await (await fetch(series, { headers: { "User-Agent": UA } })).text();
const start = t.indexOf("eplister");
const end = t.indexOf("</ul>", start);
const block = t.slice(start, end + 5);
console.log("eplister block len:", block.length, "li count:", (block.match(/<li/g) || []).length);
const rows = [...block.matchAll(/<li[\s\S]*?<\/li>/g)].map((m) => m[0]);
const parsed = rows.map((r) => ({
  href: /href="([^"]+)"/.exec(r)?.[1]?.replace("https://luciferdonghua.org/", ""),
  num: /class="epl-num">([^<]*)</.exec(r)?.[1]?.trim(),
  title: /class="epl-title">([^<]*)</.exec(r)?.[1]?.trim(),
}));
console.log("rows:", parsed.length);
const byUrl = {};
for (const p of parsed) (byUrl[p.href] ||= []).push(p);
const dup = Object.entries(byUrl).filter(([, v]) => v.length > 1);
console.log("dup urls:", dup.length);
const byNum = {};
for (const p of parsed) (byNum[p.num || "?"] ||= []).push(p);
for (const [n, v] of Object.entries(byNum)) if (v.length > 1) {
  console.log(`dup num ${n}: ` + v.map((x) => `${x.title} [${x.href}]`).join(" || "));
}
console.log("\n--- rows 40..60 ---");
parsed.slice(40, 60).forEach((p) => console.log(`  num=${p.num} title="${p.title}" href=${p.href}`));
console.log("\n--- rows 90..100 ---");
parsed.slice(90, 102).forEach((p) => console.log(`  num=${p.num} title="${p.title}" href=${p.href}`));
