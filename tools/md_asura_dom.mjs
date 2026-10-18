// Muestra el bloque desempaquetado que contiene el player Asura
// para ver la URL base real que usa el sitio.
import zlib from "node:zlib";
const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
async function req(url, { headers = {} } = {}) {
  const r = await fetch(url, { headers: { "User-Agent": UA, ...headers }, redirect: "follow" });
  const buf = Buffer.from(await r.arrayBuffer());
  let text = ""; try { text = buf.toString("utf8"); } catch {}
  return { status: r.status, text };
}
function unpackOne(line) {
  try {
    if (!line.endsWith("))")) return null;
    const out = eval("(" + line.slice(5, -1) + ")");
    if (typeof out !== "string" || out.includes("function(p,a,c,k,e")) return null;
    return out;
  } catch { return null; }
}
const slug = process.argv[2] || "against-the-gods";
const dDoc = await req(`${MAIN}/donghua/${slug}`);
const epUrl = MAIN + (dDoc.text.match(/href="([^"]*\/ver\/[^"]+)"/)?.[1] || "");
const eDoc = await req(epUrl);
console.log("ep: " + epUrl);
let i = 0;
for (const m of eDoc.text.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
  const u = unpackOne(m[0]);
  if (!u) continue;
  i++;
  const tag = u.includes("redirector") || u.includes("asura") ? "ASURA" : u.includes("tamamo") || u.includes("protea") || u.includes("api_donghua") ? "TAMAMO" : u.includes("bysekoze") || u.includes("filemoon") ? "FM" : u.includes("vidhide") ? "VH" : u.includes("vgembed") ? "VG" : u.includes("embedwish") || u.includes("streamwish") ? "SW" : u.includes("voe") ? "VOE" : "?";
  console.log(`\n===== bloque ${i} [${tag}] (${u.length} chars) =====`);
  // mostrar solo las lineas con URLs/player setup
  const lines = u.split("\n").filter((l) => /https?:|jwplayer|setup|sources|file|player|redirector|api_donghua|slug/i.test(l));
  for (const l of lines.slice(0, 25)) console.log("  " + l.slice(0, 160));
}
