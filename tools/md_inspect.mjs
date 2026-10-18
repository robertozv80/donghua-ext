#!/usr/bin/env node
// Inspeccion del unpacked de un episodio: todos los dominios mencionados,
// y contenido de embeds sw/vhide para ver por que no dan player.
import zlib from "node:zlib";
const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

async function req(url, { headers = {}, follow = true } = {}) {
  const r = await fetch(url, { headers: { "User-Agent": UA, ...headers }, redirect: follow ? "follow" : "manual" });
  const buf = Buffer.from(await r.arrayBuffer());
  let text = "";
  try { text = (r.headers.get("content-encoding") === "gzip" && buf.length && buf[0] === 0x1f) ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
  return { status: r.status, headers: r.headers, text, buf };
}
function unpackOne(line) {
  try {
    if (!line.endsWith("))")) return null;
    const out = eval("(" + line.slice(5, -1) + ")");
    if (typeof out !== "string" || out.includes("function(p,a,c,k,e")) return null;
    return out;
  } catch { return null; }
}

const slug = process.argv[2] || "combat-continent-2";
const ep = process.argv[3] || "87";
const dDoc = await req(`${MAIN}/donghua/${slug}`);
const eps = [...dDoc.text.matchAll(/href="([^"]*\/ver\/[^"]+)"/g)].map((m) => m[1]);
const epUrl = (eps[parseInt(ep) - 1] || eps[0]).startsWith("http") ? eps[parseInt(ep) - 1] : MAIN + eps[parseInt(ep) - 1];
console.log(`EP: ${epUrl}`);
const eDoc = await req(epUrl);

const unpacked = [];
for (const m of eDoc.text.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
  const u = unpackOne(m[0]);
  if (u) unpacked.push(u);
}
console.log(`unpacked: ${unpacked.length}`);
for (let i = 0; i < unpacked.length; i++) {
  const u = unpacked[i];
  const domains = [...new Set(u.match(/(?:https?:\/\/)?(?:www\.)?[a-z0-9-]+\.(?:xyz|com|to|net|org|cc|io|co|vip|site|online|live|fun|pw)(?:\/[a-zA-Z0-9_-]+)?/gi))];
  console.log(`\n--- bloque ${i} (${u.length} chars) dominios: ${domains.join(" | ")}`);
  // mostrar el setup de jwplayer / fuentes
  for (const mm of u.matchAll(/(?:jwplayer\([^)]*\)[\s\S]{0,700}?setup\([\s\S]{0,700}?)\);/g)) {
    console.log("  SETUP: " + mm[0].replace(/\s+/g, " ").slice(0, 500));
  }
}

// inspeccionar sw y vhide embeds del episodio
for (const [srv, url] of [
  ["sw", "https://embedwish.com/e/"],
  ["vhide", "https://vidhidepro.com/e/"],
]) {
  const re = srv === "sw" ? /embedwish\.com\/[ev]\/([a-zA-Z0-9]+)/ : /vidhidepro\.com\/[ve]\/([a-zA-Z0-9]+)/;
  const id = (eDoc.text + unpacked.join("\n")).match(re)?.[1];
  if (!id) { console.log(`\n${srv}: no id`); continue; }
  const r = await req(url + id, { headers: { Referer: epUrl } });
  const t = (r.text || "").replace(/\s+/g, " ");
  console.log(`\n${srv} ${url}${id}\n  status=${r.status} bytes=${r.buf.length} ct=${r.headers?.get("content-type")}`);
  console.log(`  texto: ${t.slice(0, 400)}`);
  const title = (r.text || "").match(/<title[^>]*>([^<]*)</i);
  if (title) console.log(`  <title>: ${title[1]}`);
}
