const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const html = await (await fetch("https://animexin.dev/genres/action/", { headers: { "User-Agent": UA } })).text();
console.log("len", html.length);
const arts = [...html.matchAll(/<article[\s\S]*?<\/article>/g)].map((m) => m[0]);
console.log("articles:", arts.length);
console.log("\n--- first article ---\n", arts[0]);
console.log("\n--- rating-ish attributes ---");
for (const pat of ["rating", "numscore", "score", "data-rating", "epx", "sts", "Sb", "Sub", "Dub"]) {
  const c = (html.match(new RegExp(pat, "gi")) || []).length;
  console.log(`  ${pat}: ${c}`);
}
const idx = html.search(/numscore|ratingValue|data-rating/i);
if (idx >= 0) console.log("ctx:", html.slice(idx - 200, idx + 200).replace(/\s+/g, " "));
