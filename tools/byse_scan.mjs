import fs from "fs";
const h = fs.readFileSync(new URL("./byse.js", import.meta.url), "utf8");
for (const kw of ["m3u8", "\"/api", "endpoint", "baseURL", "/video/", "sources", "playlist", "md5"]) {
  const count = h.split(kw).length - 1;
  console.log(kw, "->", count);
}
// how are fetches built
const fetches = [...h.matchAll(/fetch\((.+?)(?:,\{|\)\.then)/g)].slice(0, 10).map(m => m[1].slice(0, 120));
fetches.forEach(f => console.log("fetch:", f));
// paths constants
const paths = [...new Set([...h.matchAll(/"(\/[a-z0-9]+(?:\/[a-z0-9-_:.$\\{}]+)+)"/g)].map(m => m[1]))]
  .filter(p => !p.includes("reactjs") && p.length < 60).slice(0, 40);
paths.forEach(p => console.log("path:", p));
const i = h.indexOf("m3u8");
console.log("m3u8 ctx:", i > 0 ? h.slice(i - 260, i + 200).replace(/\s+/g, " ") : "none");
