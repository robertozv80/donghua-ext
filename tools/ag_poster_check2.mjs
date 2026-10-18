// Comprobación amplia del póster por ficha en AnimeGratis: resuelve el poster con
// la lógica nueva y verifica que la URL exista de verdad en el CDN.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const M = "https://animegratis.net";
const g = async (u, method = "GET") => { const r = await fetch(u, { method, headers: { "User-Agent": UA } }); return { status: r.status, text: method === "GET" ? await r.text() : "", len: r.headers.get("content-length") }; };
const attr = (tag, name) => (tag.match(new RegExp(`${name}="([^"]*)"`)) || [])[1] || "";

const list = await g(`${M}/donghua`);
const slugs = [...new Set([...list.text.matchAll(/href="\/donghua\/([a-z0-9-]+)"/g)].map((m) => m[1]))].slice(0, 12);
const animeList = await g(`${M}/`);
const animeSlugs = [...new Set([...animeList.text.matchAll(/href="\/anime\/([a-z0-9-]+)"/g)].map((m) => m[1]))].slice(0, 8);

let ok = 0, fail = 0;
for (const [kind, arr] of [["donghua", slugs], ["anime", animeSlugs]]) {
  for (const slug of arr) {
    const r = await g(`${M}/${kind}/${slug}`);
    if (r.status !== 200) { console.log(`${r.status} ${kind}/${slug}`); continue; }
    const imgs = [...r.text.matchAll(/<img[^>]*>/g)].map((m) => m[0]);
    const cands = imgs.filter((t) => attr(t, "loading") === "eager" && attr(t, "width") === "220"
      && attr(t, "alt").trim() !== "" && !/aria-hidden/.test(t));
    const poster = cands.length ? attr(cands[0], "src") : "";
    const jkdesa = /jkdesa/.test(r.text);
    let code = 0, size = "?";
    if (poster) { const h = await g(poster, "HEAD"); code = h.status; size = h.len || "?"; }
    const coincide = poster.includes(`/${kind}/${slug.replace(/-anime$/, "")}/`);
    if (code === 200 && coincide) ok++; else fail++;
    console.log(`${code === 200 && coincide ? "OK " : "!! "} ${kind}/${slug} -> ${code} ${size}B jkdesa=${jkdesa} ${poster.slice(0, 78)}`);
  }
}
console.log(`\nresumen: ok=${ok} fail=${fail}`);