// Recolecta códigos de bysekoze de varios episodios y los guarda para el barrido.
import fs from "node:fs";
const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const OUT = "tools/_fm_codes.txt";
const get = async (url, ms = 12000) => {
  const c = new AbortController();
  const t = setTimeout(() => c.abort(), ms);
  try {
    const r = await fetch(url, { headers: { "User-Agent": UA }, signal: c.signal });
    return { status: r.status, text: await r.text() };
  } catch { return { status: 0, text: "" }; } finally { clearTimeout(t); }
};
const unpackAll = (html) => {
  const out = [];
  for (const m of html.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
    try {
      if (!m[0].endsWith(")")) continue;
      const u = eval("(" + m[0].slice(5, -1) + ")");
      if (typeof u === "string" && !u.includes("function(p,a,c,k,e")) out.push(u);
    } catch {}
  }
  return out;
};
const have = new Set(fs.existsSync(OUT) ? fs.readFileSync(OUT, "utf8").split("\n").filter(Boolean) : []);
const list = await get(`${MAIN}/lista-donghuas`);
const slugs = [...new Set([...list.text.matchAll(/href="(\/donghua\/[^"]+)"/g)].map((m) => m[1].slice(8)))];
console.log("series:", slugs.length, "| ya conocidos:", have.size);
let i = 0;
while (have.size < 40 && i < slugs.length * 3) {
  const slug = slugs[i % slugs.length];
  i++;
  const d = await get(`${MAIN}/donghua/${slug}`);
  const eps = [...new Set([...d.text.matchAll(/href="([^"]*\/ver\/[^"]+)"/g)].map((m) => m[1]))];
  if (!eps.length) continue;
  const ep = eps[Math.floor(Math.random() * eps.length)];
  const e2 = await get(MAIN + ep);
  const hay = [e2.text, ...unpackAll(e2.text)].join("\n");
  const fm = hay.match(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  if (fm && !have.has(fm)) {
    have.add(fm);
    fs.appendFileSync(OUT, fm + "\n");
    console.log("nuevo", fm, "de", slug, ep.split("/").pop());
  }
}
console.log("total códigos:", have.size);