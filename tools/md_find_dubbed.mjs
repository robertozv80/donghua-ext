// Busca series DOBLADAS en mundodonghua y extrae codigos bysekoze de sus episodios.
import zlib from "node:zlib";

const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

async function req(url, { headers = {} } = {}) {
  const r = await fetch(url, { headers: { "User-Agent": UA, ...headers }, redirect: "follow" });
  const buf = Buffer.from(await r.arrayBuffer());
  let text = "";
  try { text = (r.headers.get("content-encoding") === "gzip" && buf.length && buf[0] === 0x1f) ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch { text = ""; }
  return { status: r.status, text, buf };
}

// 1) home: listar enlaces de series y cualquier marca de doblaje
const home = await req(MAIN);
console.log("home status", home.status, "bytes", home.buf.length);
const slugs = new Set();
for (const m of home.text.matchAll(/href="\/serie\/([a-z0-9-]+)\//g)) slugs.add(m[1]);
for (const m of home.text.matchAll(/href="\/ver\/([a-z0-9-]+)\//g)) slugs.add(m[1]);
console.log("slugs en home:", [...slugs].slice(0, 40));
// ¿hay marca "doblaje"/"latino"/"castellano"?
for (const kw of ["Doblaje", "Doblada", "Latino", "Castellano", "Subtitulada", "doblaje", "VS", "VSO"]) {
  const n = (home.text.match(new RegExp(kw, "g")) || []).length;
  if (n) console.log(`  kw "${kw}": ${n}`);
}

// 2) endpoint de busqueda conocido del sitio (prueba varios)
for (const q of ["soul+land", "battle+through+the+heavens", "asura"]) {
  for (const ep of ["/search?q=" + q, "/search/" + q, "/?s=" + q]) {
    const r = await req(MAIN + ep);
    const found = [...r.text.matchAll(/href="(\/(?:serie|ver)\/[a-z0-9-]+\/?)"/g)].map((m) => m[1]);
    if (found.length) {
      console.log(`\nbusqueda ${ep} -> status ${r.status}`);
      console.log("  ", [...new Set(found)].slice(0, 12));
      break;
    }
  }
}
