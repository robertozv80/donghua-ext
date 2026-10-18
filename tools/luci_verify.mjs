const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const t = await (await fetch("https://luciferdonghua.org/anime/supreme-sword-god/", { headers: { "User-Agent": UA } })).text();
const s = t.indexOf("eplister"), e = t.indexOf("</ul>", s);
const rows = [...t.slice(s, e).matchAll(/<li[\s\S]*?<\/li>/g)].map((m) => m[0]).map((r) => ({
  href: /href="([^"]+)"/.exec(r)?.[1] || "",
  num: /class="epl-num">([^<]*)</.exec(r)?.[1]?.trim(),
  title: /class="epl-title">([^<]*)</.exec(r)?.[1]?.trim(),
}));
const EP = /Episode\s+(\d+(?:-\d+)?)/i;
const out = rows.filter((r) => /-episode-\d+/.test(r.href)).map((r) => {
  const urlNum = /-episode-(\d+(?:-\d+)?)/i.exec(r.href)?.[1];
  const epNum = urlNum ? parseInt(urlNum.split("-")[0], 10) : (r.num?.match(/^\d+$/) ? parseInt(r.num) : null);
  let name = r.title ?? r.num;
  const tNum = EP.exec(r.title || "")?.[1];
  if (urlNum && tNum && tNum !== urlNum) name = r.title.replace(tNum, urlNum);
  return { epNum, name, url: r.href.replace("https://luciferdonghua.org/", "") };
});
const nums = out.map((o) => o.epNum);
console.log("episodes:", out.length);
const dupes = nums.filter((n, i) => nums.indexOf(n) !== i);
console.log("duplicate episode numbers after fix:", dupes.length ? dupes.join(",") : "none");
for (const n of [46, 56, 95, 96, 97]) {
  const m = out.filter((o) => o.epNum === n);
  m.forEach((x) => console.log(`  ep ${n}: "${x.name}" -> ${x.url}`));
}
const missing = [];
for (let i = 1; i <= 97; i++) if (!nums.includes(i)) missing.push(i);
console.log("missing 1..97:", missing.join(",") || "none");
