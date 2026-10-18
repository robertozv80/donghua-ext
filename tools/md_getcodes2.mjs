// Despaqueta los eval() del HTML de episodios vivos y extrae embeds bysekoze.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

const EPISODES = [
  "https://www.mundodonghua.com/ver/against-the-gods/30",
  "https://www.mundodonghua.com/ver/apotheosis/52",
  "https://www.mundodonghua.com/ver/black-and-white-warriors/12",
];

async function get(url) {
  const r = await fetch(url, { headers: { "User-Agent": UA, Referer: "https://www.mundodonghua.com/" }, redirect: "manual" });
  return { status: r.status, text: await r.text() };
}

function unpack(packed) {
  // El bloque es: eval(function(p,a,c,k,e,...){...}(...))  -> produce un string
  // Lo envolvemos para capturar el resultado en vez de evaluar.
  const inner = packed.replace(/^eval\(/, "").replace(/\)\s*$/, "");
  const fn = eval("(" + inner + ")");
  const out = fn();
  return typeof out === "string" ? out : String(out);
}

for (const ep of EPISODES) {
  const r = await get(ep);
  const blocks = [...r.text.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)].map((m) => m[0]);
  const codes = new Set();
  let unpackedCount = 0;
  for (const b of blocks) {
    try {
      const u = unpack(b);
      unpackedCount++;
      for (const m of u.matchAll(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/g)) codes.add(m[1]);
    } catch (e) { /* ignore */ }
  }
  console.log(`${ep} -> status=${r.status} blocks=${blocks.length} unpacked=${unpackedCount} bysekoze=${JSON.stringify([...codes])}`);
}
