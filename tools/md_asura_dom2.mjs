// Volca el contenido COMPLETO de cada bloque unpacked (iframes).
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
for (const m of eDoc.text.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
  const u = unpackOne(m[0]);
  if (!u) continue;
  if (!/iframe|player|redirector|api_donghua|jwplayer/i.test(u)) continue;
  console.log("==================================================");
  console.log(u);
  console.log("");
}
