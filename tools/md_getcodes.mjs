// Extrae códigos de embed bysekoze de episodios vivos.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

const EPISODES = [
  "https://www.mundodonghua.com/ver/against-the-gods/30",
  "https://www.mundodonghua.com/ver/apotheosis/52",
  "https://www.mundodonghua.com/ver/black-and-white-warriors/12",
  "https://www.mundodonghua.com/ver/tales-of-demons-and-gods/1",
];

async function get(url) {
  const r = await fetch(url, { headers: { "User-Agent": UA, Referer: "https://www.mundodonghua.com/" }, redirect: "manual" });
  const t = await r.text();
  return { status: r.status, text: t };
}

for (const ep of EPISODES) {
  const r = await get(ep);
  const codes = [...r.text.matchAll(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/g)].map((m) => m[1]);
  const uniq = [...new Set(codes)];
  console.log(`${ep} -> status=${r.status} bytes=${r.text.length} bysekoze codes=${JSON.stringify(uniq)}`);
}
