// Descarga el bundle JS del embed de bysekoze y busca la lógica del PoW.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const code = process.argv[2] || "7q7xqhtu6lve";
const r = await fetch(`https://bysekoze.com/e/${code}`, { headers: { "User-Agent": UA, Referer: "https://www.mundodonghua.com/" } });
const html = await r.text();
console.log("embed status", r.status, "len", html.length);
const srcs = [...html.matchAll(/src="([^"]+\.js[^"]*)"/g)].map((m) => m[1]);
console.log("scripts:", srcs.length);
for (const s of srcs.slice(0, 40)) console.log("  ", s.slice(0, 140));
const inline = [...html.matchAll(/<script(?![^>]*src)[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
console.log("inline blocks:", inline.map((x) => x.length));
const all = html + "\n" + inline.join("\n");
for (const pat of [/pow_/g, /leading/gi, /sha256/gi, /sha-256/gi, /captcha/gi, /difficulty/gi, /digest/gi, /createHash/gi, /subtle/gi]) {
  const hits = [...all.matchAll(pat)];
  if (hits.length) {
    console.log(`\n--- ${pat} x${hits.length} ---`);
    for (const h of hits.slice(0, 6)) console.log("  ..." + all.slice(Math.max(0, h.index - 200), h.index + 260).replace(/\s+/g, " ") + "...");
  }
}