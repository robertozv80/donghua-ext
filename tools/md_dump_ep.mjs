#!/usr/bin/env node
// Dump de un episodio: tabs + bloques desempaquetados completos.
import zlib from "node:zlib";

const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

async function req(url, { headers = {} } = {}) {
  const r = await fetch(url, { headers: { "User-Agent": UA, ...headers }, redirect: "follow" });
  const buf = Buffer.from(await r.arrayBuffer());
  let text = "";
  try { text = (r.headers.get("content-encoding") === "gzip" && buf.length && buf[0] === 0x1f) ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
  return { status: r.status, text, buf };
}

function unpackOne(line) {
  try {
    if (!line.endsWith("))")) return null;
    const expr = "(" + line.slice(5, -1) + ")";
    const out = eval(expr);
    if (typeof out !== "string" || out.includes("function(p,a,c,k,e")) return null;
    return out;
  } catch { return null; }
}

const [slug, ep] = process.argv.slice(2);
const url = `${MAIN}/ver/${slug}/${ep}`;
console.log(`### ${url}`);
const d = await req(url);
console.log(`status=${d.status} bytes=${d.buf.length}`);

// tabs
for (const m of d.text.matchAll(/<button[^>]*md-server-tab[^>]*data-target="([^"]+)"[^>]*>([^<]*)/g)) {
  console.log(`TAB: ${m[1]} = "${m[2].trim()}"`);
}
// formularios/acciones de tabs (data-action, onclick, ajax endpoints)
for (const m of d.text.matchAll(/<(?:button|a|div)[^>]*(?:server|tab)[^>]*>/gi)) {
  const tag = m[0];
  if (/data-(?:target|action|src|url|ajax|endpoint)/i.test(tag)) console.log(`WIDGET: ${tag.slice(0, 300)}`);
}

// scripts con eval
let n = 0;
for (const m of d.text.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
  const u = unpackOne(m[0]);
  console.log(`\n===== BLOCK ${++n} (${m[0].length}B packed) =====`);
  console.log(u ? u.slice(0, 3000) : "(no se pudo desempaquetar)");
}
// cualquier otro script con "player" o "embed"
let k = 0;
for (const s of d.text.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)) {
  const body = s[1];
  if (body.includes("eval(function") || body.length < 50) continue;
  if (/player|embed|iframe|m3u8|vidhide|vgembed|embedwish|amagi|asura|tamamo|dailymotion/i.test(body)) {
    console.log(`\n===== RAW SCRIPT ${++k} (${body.length}B) =====`);
    console.log(body.slice(0, 3000));
  }
}
// iframes en el HTML
for (const m of d.text.matchAll(/<iframe[^>]+>/g)) console.log(`\nIFRAME: ${m[0]}`);
