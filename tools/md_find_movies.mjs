#!/usr/bin/env node
// Descubre contenido variado (series + peliculas) de mundodonghua para auditar.
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

// 1) lista general
const list = await req(`${MAIN}/lista-donghuas`);
console.log(`/lista-donghuas -> ${list.status}`);
const cards = [...list.text.matchAll(/<a[^>]*href="(\/donghua\/[^"]+)"[^>]*>/g)].map((m) => m[1]);
console.log(`cards: ${cards.length}`);
const uniq = [...new Set(cards)].slice(0, 40);
for (const c of uniq) console.log(`  ${c}`);

// 2) ¿hay pagina de peliculas? probes de paths comunes
for (const p of ["/pelis-donghua", "/peliculas", "/donghuas-peliculas", "/lista-peliculas", "/movies", "/peliculas-donghua"]) {
  const r = await req(MAIN + p);
  const n = (r.text.match(/\/donghua\//g) || []).length;
  console.log(`${p} -> ${r.status} donghuaLinks=${n}`);
  if (r.status === 200 && n > 3) {
    const mc = [...r.text.matchAll(/<a[^>]*href="(\/donghua\/[^"]+)"[^>]*>/g)].map((m) => m[1]);
    console.log(`  ejemplos: ${[...new Set(mc)].slice(0, 12).join(", ")}`);
  }
}
