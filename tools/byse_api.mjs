import fs from "fs";
const h = fs.readFileSync(new URL("./byse_video.js", import.meta.url), "utf8");
for (const p of ["/api/videos/", "/api/videos/stream/", "/api/videos/folders/"]) {
  let i = -1, c = 0;
  while (((i = h.indexOf(p, i + 1)) >= 0) && c < 3) {
    c++;
    console.log(p, "@", i, "::", h.slice(Math.max(0, i - 200), i + 250).replace(/\s+/g, " ").slice(0, 400));
    console.log("---");
  }
}
// live test on the embed id
const id = "ohmv3woxtd8d";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
for (const u of [
  `https://byselapuix.com/api/videos/${id}?embed=1`,
  `https://byselapuix.com/api/videos/${id}`,
  `https://byselapuix.com/api/videos/stream/${id}`,
]) {
  try {
    const r = await fetch(u, { headers: { "User-Agent": UA, Referer: "https://byselapuix.com/e/" + id, Accept: "application/json" } });
    const t = await r.text();
    console.log("\nLIVE", u, "->", r.status, "len", t.length);
    console.log(t.slice(0, 700));
  } catch (e) { console.log("EXC", u, e.message); }
}
