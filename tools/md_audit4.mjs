// Verifica: dominio real del player Asura en el HTML + respuesta completa de SW/VG.
import zlib from "node:zlib";
const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
async function req(url, { headers = {}, timeoutMs = 15000 } = {}) {
  const h = { "User-Agent": UA, Accept: "*/*", "Accept-Language": "es-ES,es;q=0.9", ...headers };
  const ctrl = new AbortController(); const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, { headers: h, signal: ctrl.signal, redirect: "follow" });
    const buf = Buffer.from(await r.arrayBuffer());
    let text = ""; try { text = buf.toString("utf8"); } catch {}
    return { status: r.status, headers: r.headers, text, buf };
  } catch (e) { return { status: 0, text: "", buf: Buffer.alloc(0), err: e.message }; }
  finally { clearTimeout(t); }
}
const slug = process.argv[2] || "against-the-gods";
const dDoc = await req(`${MAIN}/donghua/${slug}`);
const epUrl = MAIN + (dDoc.text.match(/href="([^"]*\/ver\/[^"]+)"/)?.[1] || "");
const eDoc = await req(epUrl);
const html = eDoc.text;
console.log("=== iframes en el HTML ===");
for (const m of html.matchAll(/<iframe[^>]*src="([^"]+)"[^>]*>/g)) console.log("  iframe: " + m[1]);
console.log("=== referencias a dominios de player ===");
for (const m of html.matchAll(/https?:\/\/[a-z0-9.-]*(player|nemonic|embed|wish|vgembed|vidhide|bysekoze|filemoon|voe)[a-z0-9.-]*\.[a-z]+/gi)) console.log("  dom: " + m[0]);
console.log("=== src/data attrs con slug/embed ===");
for (const m of html.matchAll(/(?:src|data-src|data-id|data-slug|data-embed)=["']([^"']{10,120})["']/g)) console.log("  attr: " + m[1].slice(0, 110));

// Respuesta completa de SW
const swId = [html, ...[]].join("\n").match(/embedwish\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
if (swId) {
  const r = await req(`https://embedwish.com/e/${swId}`, { headers: { Referer: epUrl } });
  console.log(`\n=== SW embedwish/e/${swId} status=${r.status} bytes=${r.buf.length} ===`);
  console.log(r.text);
}
// Respuesta de VG
const vgId = html.match(/vgembed\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
if (vgId) {
  const r = await req(`https://vgembed.com/e/${vgId}`, { headers: { Referer: epUrl } });
  console.log(`\n=== VG vgembed/e/${vgId} status=${r.status} bytes=${r.buf.length} ===`);
  console.log(r.text.slice(0, 600));
}
// ¿Y la URL sin /e/, solo /v/?
if (vgId) {
  const r = await req(`https://vgembed.com/v/${vgId}`, { headers: { Referer: epUrl } });
  console.log(`\n=== VG vgembed/v/${vgId} status=${r.status} bytes=${r.buf.length} ===`);
  console.log(r.text.slice(0, 300));
}
