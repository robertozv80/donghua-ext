const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

const r = await fetch("https://animecube.live/", { headers: { "User-Agent": UA } });
const t = await r.text();

const hrefs = new Set();
const re = /href="([^"]+)"/g;
let m;
while ((m = re.exec(t)) !== null) hrefs.add(m[1]);

const g = [...hrefs].filter((h) => /genre|browse|schedule|list/i.test(h));
console.log("genre-ish hrefs:");
g.slice(0, 40).forEach((h) => console.log("  ", h));
console.log("total hrefs:", hrefs.size);

const idx = t.toLowerCase().indexOf("genre");
console.log("CTX:", t.slice(Math.max(0, idx - 250), idx + 350).replace(/\s+/g, " "));
