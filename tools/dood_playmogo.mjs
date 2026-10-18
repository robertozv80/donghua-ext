const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
async function g(u, h = {}) {
  const r = await fetch(u, { headers: { "User-Agent": UA, ...h } });
  return { s: r.status, t: await r.text(), h: r.headers };
}
const ref = "https://animexin.dev/";
const id = "z3lacan00d6kmr2mcsng8fzfogrcmw0t";
for (const [name, url, h] of [
  ["playmogo direct", `https://playmogo.com/e/${id}`, { Referer: ref }],
  ["playmogo no-ref", `https://playmogo.com/e/${id}`, {}],
  ["playmogo accept", `https://playmogo.com/e/${id}`, { Referer: ref, Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8", "Accept-Language": "en-US,en;q=0.9" }],
]) {
  const r = await g(url, h);
  console.log(`\n## ${name}: ${r.s} len=${r.t.length} cf-ray=${r.h.get("cf-ray")||"-"} server=${r.h.get("server")}`);
  console.log(r.t.slice(0, 900).replace(/\s+/g, " "));
}
