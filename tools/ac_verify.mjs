const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const SEP = `"])</script><script>self.__next_f.push([1,"`;

const html = await (await fetch("https://animecube.live/", { headers: { "User-Agent": UA } })).text();
const joined = html.split(SEP).join("");
const un = joined.split('\\"').join('"').split("\\u0026").join("&");

const metaRe = /"coverImage":"([^"]+)"[^}]*?"genres":\[[^\]]*\][^}]*?"slug":"([a-z0-9-]+)"[^}]*?"title":"((?:[^"\\]|\\.)*)"/g;
const genreRe = /"genres":\[([^\]]*)\][^}]*?"slug":"([a-z0-9-]+)"/g;
const merged = new Map();
let m;
while ((m = metaRe.exec(un))) merged.set(m[2], { poster: m[1], title: m[3], genres: [] });
while ((m = genreRe.exec(un))) {
  const g = m[1].split(",").map((x) => x.trim().replace(/^"|"$/g, "")).filter(Boolean);
  const prev = merged.get(m[2]);
  merged.set(m[2], prev ? { ...prev, genres: g } : { poster: "", title: "", genres: g });
}
console.log("catalog items:", merged.size);
for (const s of ["perfect-world", "soul-land-2-the-peerless-tang-clan", "beyond-time-s-gaze"]) {
  const it = merged.get(s);
  console.log(`  ${s}: poster=${it ? it.poster : "MISSING"}`);
}
// validate URLs resolve
for (const s of ["perfect-world", "soul-land-2-the-peerless-tang-clan"]) {
  const p = merged.get(s)?.poster;
  if (!p) continue;
  const r = await fetch(p, { headers: { "User-Agent": UA } });
  console.log(`  HEAD ${s}: ${r.status} ${r.headers.get("content-type")}`);
}

// RSC endpoint chunk check
const rsc = await (await fetch("https://animecube.live/anime/beyond-time-s-gaze?_rsc=1", { headers: { "User-Agent": UA, RSC: "1" } })).text();
console.log("\nRSC len:", rsc.length, "sep count:", rsc.split(SEP).length - 1, "starts:", rsc.slice(0, 80).replace(/\s+/g, " "));
console.log("RSC has coverImage:", /"coverImage":"([^"]+)"/.exec(rsc)?.[1]?.slice(0, 120));
