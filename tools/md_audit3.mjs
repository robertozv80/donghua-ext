// Sonda FIEL de servidores: visita la web primero (cookies UA) y vuelca
// respuestas crudas de Asura/Tamamo para ver el formato real.
import zlib from "node:zlib";

const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

// cookie jar simple
const jar = new Map();
function storeCookies(url, hdrs) {
  const sc = hdrs?.getSetCookie?.() || [];
  for (const c of sc) {
    const [pair] = c.split(";");
    const idx = pair.indexOf("=");
    if (idx > 0) jar.set(pair.slice(0, idx).trim(), pair.slice(idx + 1).trim());
  }
}
function cookieHeader() { return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join("; "); }

async function req(url, { method = "GET", headers = {}, body = null, timeoutMs = 15000, follow = true } = {}) {
  const h = { "User-Agent": UA, Accept: "*/*", "Accept-Language": "es-ES,es;q=0.9,en;q=0.8", ...headers };
  if (cookieHeader()) h["Cookie"] = cookieHeader();
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, { method, headers: h, signal: ctrl.signal, redirect: follow ? "follow" : "manual", body: body === null ? undefined : typeof body === "string" ? body : JSON.stringify(body) });
    const buf = Buffer.from(await r.arrayBuffer());
    storeCookies(url, r.headers);
    let text = "";
    try { text = (r.headers.get("content-type") || "").includes("gzip") ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
    return { status: r.status, headers: r.headers, text, buf };
  } catch (e) {
    return { status: 0, headers: null, text: "", buf: Buffer.alloc(0), err: e.message };
  } finally { clearTimeout(t); }
}

function unpackOne(line) {
  try {
    if (!line.endsWith("))")) return null;
    const out = eval("(" + line.slice(5, -1) + ")");
    if (typeof out !== "string" || out.includes("function(p,a,c,k,e")) return null;
    return out;
  } catch { return null; }
}
function unpackAll(html) { const out = []; for (const m of html.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) { const u = unpackOne(m[0]); if (u) out.push(u); } return out; }

const slug = process.argv[2] || "against-the-gods";
console.log(`### Contenido: ${slug}`);

// 1) visitar home y ficha para cookies
const home = await req(MAIN + "/");
console.log(`home: ${home.status} cookies=${jar.size}`);
const dDoc = await req(`${MAIN}/donghua/${slug}`);
console.log(`/donghua/: ${dDoc.status}`);
const epMatch = dDoc.text.match(/href="([^"]*\/ver\/[^"]+)"/);
if (!epMatch) { console.log("sin episodios"); process.exit(0); }
const epUrl = epMatch[1].startsWith("http") ? epMatch[1] : MAIN + epMatch[1];
console.log(`ep: ${epUrl}`);
const eDoc = await req(epUrl);
console.log(`ep HTML: ${eDoc.status} bytes=${eDoc.buf.length}`);

// extraer embeds
const hay = [eDoc.text, ...unpackAll(eDoc.text)].join("\n");
const asuraSlug = hay.match(/redirector\.php\?slug=([A-Za-z0-9+/=]+)/)?.[1];
const tamamoSlug = [...hay.matchAll(/slug"?\s*:\s*"([A-Za-z0-9+/=]{8,})"/g)].map((m) => m[1])[0] || hay.match(/api_donghua\.php\?slug=([A-Za-z0-9+/=]+)/)?.[1];
const fmCode = hay.match(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
const vhId = hay.match(/vidhidepro\.com\/[ve]\/([a-zA-Z0-9]+)/)?.[1];
const vgId = hay.match(/vgembed\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
const swId = hay.match(/embedwish\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
const voeId = hay.match(/voe\.sx\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
console.log(`ids: asura=${!!asuraSlug} tamamo=${!!tamamoSlug} fm=${!!fmCode} vh=${!!vhId} vg=${!!vgId} sw=${!!swId} voe=${!!voeId}`);

// ===== ASURA =====
if (asuraSlug) {
  for (const base of ["https://www.mdplayer.xyz/nemonicplayer", "https://www.mdnemonicplayer.xyz/nemonicplayer"]) {
    const r = await req(`${base}/redirector.php?slug=${asuraSlug}`, { headers: { Referer: epUrl, "X-Requested-With": "XMLHttpRequest", Accept: "*/*" } });
    const ct = r.headers?.get("content-type") || "";
    console.log(`\n[ASURA ${base.replace("https://www.", "")}] status=${r.status} ct=${ct} bytes=${r.buf.length}`);
    console.log(`  body[0:200]: ${r.text.slice(0, 200).replace(/\s+/g, " ")}`);
  }
}

// ===== TAMAMO =====
if (tamamoSlug) {
  const api = await req(`${MAIN}/api_donghua.php?slug=${encodeURIComponent(tamamoSlug)}`, { headers: { "X-Requested-With": "XMLHttpRequest", Referer: epUrl, Accept: "*/*" } });
  console.log(`\n[TAMAMO api_donghua] status=${api.status} bytes=${api.buf.length}`);
  console.log(`  body[0:300]: ${api.text.slice(0, 300).replace(/\s+/g, " ")}`);
  let key = null;
  try { const j = JSON.parse(api.text); key = Array.isArray(j) ? j[0]?.url : j?.url; } catch {}
  if (key) {
    const player = await req(`https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=${encodeURIComponent(key)}`, { headers: { Referer: epUrl } });
    console.log(`\n[TAMAMO dmplayer] status=${player.status} bytes=${player.buf.length}`);
    console.log(`  body[0:1500]:\n${player.text.slice(0, 1500)}`);
  }
}

// ===== EMBEDS genericos =====
for (const [nombre, id, dom] of [["VH", vhId, "vidhidepro.com"], ["VG", vgId, "vgembed.com"], ["SW", swId, "embedwish.com"], ["VOE", voeId, "voe.sx"]]) {
  if (!id) continue;
  const r = await req(`https://${dom}/e/${id}`, { headers: { Referer: epUrl } });
  const title = r.text.match(/<title[^>]*>([^<]*)/)?.[1] || "";
  const isPlayer = /jwplayer|video-js|plyr|setup\(|sources\s*:|file\s*:|\.m3u8|\.mp4/i.test(r.text);
  console.log(`\n[${nombre} ${dom}/e/${id}] status=${r.status} bytes=${r.buf.length} player=${isPlayer}`);
  console.log(`  title: ${title.trim().slice(0, 80)}`);
  console.log(`  body[0:160]: ${r.text.slice(0, 160).replace(/\s+/g, " ")}`);
}

// ===== FM (bysekoze) — captcha + playback completo =====
if (fmCode) {
  const cap = await req(`https://bysekoze.com/api/videos/${fmCode}/embed/captcha`, { method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json", Origin: MAIN, Referer: MAIN + "/" }, body: {} });
  console.log(`\n[FM captcha] status=${cap.status} body=${cap.text.slice(0, 80)}`);
}
