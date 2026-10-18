// Auditoría AMPLIADA: más series + peliculas, todos los servidores, con motivo.
import zlib from "node:zlib";

const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

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
    try { text = (r.headers.get("content-encoding") || "").includes("gzip") && buf.length && buf[0] === 0x1f ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
    return { status: r.status, headers: r.headers, text, buf };
  } catch (e) {
    return { status: 0, headers: null, text: "", buf: Buffer.alloc(0), err: e.message };
  } finally { clearTimeout(t); }
}

function unpackOne(line) {
  try {
    if (!line.endsWith(")")) return null;
    const out = eval("(" + line.slice(5, -1) + ")");
    if (typeof out !== "string" || out.includes("function(p,a,c,k,e")) return null;
    return out;
  } catch { return null; }
}
function unpackAll(html) { const out = []; for (const m of html.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) { const u = unpackOne(m[0]); if (u) out.push(u); } return out; }

// ---------- descubrir contenido ----------
const slugs = new Set();
for (let p = 1; p <= 3; p++) {
  const url = p === 1 ? `${MAIN}/lista-donghuas` : `${MAIN}/lista-donghuas/page/${p}`;
  const r = await req(url);
  for (const m of r.text.matchAll(/href="(\/donghua\/[^"]+)"/g)) slugs.add(m[1].slice(8));
  console.error(`descubrimiento pagina ${p}: ${r.status}, total slugs=${slugs.size}`);
}
const slugList = [...slugs];
// mezclar: tomar una muestra dispersa (primero, medio, último de cada grupo)
const sample = [];
const step = Math.max(1, Math.floor(slugList.length / 14));
for (let i = 0; i < slugList.length && sample.length < 14; i += step) sample.push(slugList[i]);
console.error(`muestra: ${sample.join(", ")}`);

const results = new Map(); // servidor -> {live, dead:[motivo#slug]}
function rec(server, alive, detail, slugEp) {
  if (!results.has(server)) results.set(server, { live: 0, dead: 0, details: [] });
  const r = results.get(server);
  if (alive) r.live++; else r.dead++;
  r.details.push(`${alive ? "V" : "M"} ${slugEp}: ${detail}`);
}

for (const slug of sample) {
  const dDoc = await req(`${MAIN}/donghua/${slug}`);
  const epMatch = dDoc.text.match(/href="([^"]*\/ver\/[^"]+)"/);
  if (!epMatch) { console.log(`## ${slug}: sin episodios (ficha ${dDoc.status})`); continue; }
  const epUrl = epMatch[1].startsWith("http") ? epMatch[1] : MAIN + epMatch[1];
  const eDoc = await req(epUrl);
  const hay = [eDoc.text, ...unpackAll(eDoc.text)].join("\n");
  const tag = `${slug}#${epUrl.split("/").pop()}`;
  console.log(`\n## ${tag}  (ep HTML ${eDoc.status}, ${eDoc.buf.length}b)`);

  const asuraSlug = hay.match(/redirector\.php\?slug=([A-Za-z0-9+/=]+)/)?.[1];
  const tamamoSlug = [...hay.matchAll(/slug"?\s*:\s*"([A-Za-z0-9+/=]{8,})"/g)].map((m) => m[1])[0] || hay.match(/api_donghua\.php\?slug=([A-Za-z0-9+/=]+)/)?.[1];
  const fmCode = hay.match(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  const vhId = hay.match(/vidhidepro\.com\/[ve]\/([a-zA-Z0-9]+)/)?.[1];
  const vgId = hay.match(/vgembed\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  const swId = hay.match(/embedwish\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  const voeId = hay.match(/voe\.sx\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  console.log(`  ids: asura=${!!asuraSlug} tamamo=${!!tamamoSlug} fm=${!!fmCode} vh=${!!vhId} vg=${!!vgId} sw=${!!swId} voe=${!!voeId}`);

  // ASURA
  if (asuraSlug) {
    for (const base of ["https://www.mdplayer.xyz/nemonicplayer", "https://www.mdnemonicplayer.xyz/nemonicplayer"]) {
      const r = await req(`${base}/redirector.php?slug=${asuraSlug}`, { headers: { Referer: epUrl, "X-Requested-With": "XMLHttpRequest" } });
      const body = r.text.slice(0, 120).replace(/\s+/g, " ");
      const alive = r.status === 200 && /m3u8|mp4|sources|setup|jwplayer|video/i.test(r.text);
      rec(`asura@${base.replace("https://www.", "")}`, alive, `${r.status} ${r.buf.length}b ${body.slice(0, 60)}`, tag);
      console.log(`  [ASURA ${base.replace("https://www.", "")}] ${r.status} ${r.buf.length}b -> ${alive ? "VIVO" : "muerto"} :: ${body.slice(0, 80)}`);
    }
  }

  // TAMAMO
  if (tamamoSlug) {
    const api = await req(`${MAIN}/api_donghua.php?slug=${encodeURIComponent(tamamoSlug)}`, { headers: { "X-Requested-With": "XMLHttpRequest", Referer: epUrl } });
    let key = null;
    try { const j = JSON.parse(api.text); key = Array.isArray(j) ? j[0]?.url : j?.url; } catch {}
    if (!key) {
      rec("tamamo", false, `api ${api.status} sin key :: ${api.text.slice(0, 60).replace(/\s+/g, " ")}`, tag);
      console.log(`  [TAMAMO] api ${api.status} sin key :: ${api.text.slice(0, 80).replace(/\s+/g, " ")}`);
    } else {
      const player = await req(`https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=${encodeURIComponent(key)}`, { headers: { Referer: epUrl } });
      const alive = player.status === 200 && /m3u8|mp4|sources|setup|jwplayer|video/i.test(player.text);
      rec("tamamo", alive, `dmplayer ${player.status} ${player.buf.length}b`, tag);
      console.log(`  [TAMAMO] dmplayer ${player.status} ${player.buf.length}b -> ${alive ? "VIVO" : "muerto"}`);
    }
  }

  // FM (bysekoze) — captcha endpoint
  if (fmCode) {
    const cap = await req(`https://bysekoze.com/api/videos/${fmCode}/embed/captcha`, { method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json", Origin: MAIN, Referer: MAIN + "/" }, body: {} });
    const alive = cap.status === 200 && cap.text.includes("pow_nonce");
    rec("fm", alive, `captcha ${cap.status} :: ${cap.text.slice(0, 40).replace(/\s+/g, " ")}`, tag);
    console.log(`  [FM] captcha ${cap.status} -> ${alive ? "VIVO" : "muerto"} :: ${cap.text.slice(0, 60).replace(/\s+/g, " ")}`);
  }

  // VH / VG / SW / VOE
  for (const [nombre, id, dom] of [["VH", vhId, "vidhidepro.com"], ["VG", vgId, "vgembed.com"], ["SW", swId, "embedwish.com"], ["VOE", voeId, "voe.sx"]]) {
    if (!id) continue;
    for (const path of ["/e/", "/v/"]) {
      const r = await req(`https://${dom}${path}${id}`, { headers: { Referer: epUrl } });
      const title = r.text.match(/<title[^>]*>([^<]*)/)?.[1]?.trim() || "";
      const hasPlayer = /jwplayer|video-js|plyr|setup\(|sources\s*:|file\s*:|\.m3u8|\.mp4/i.test(r.text);
      const gone = /no longer available|file (is )?not (found|available)|deleted|404/i.test(r.text);
      const alive = r.status === 200 && hasPlayer && !gone;
      if (path === "/e/" || (alive && path === "/v/")) {
        rec(nombre, alive, `${path} ${r.status} ${r.buf.length}b player=${hasPlayer} gone=${gone} title="${title.slice(0, 40)}"`, tag);
        console.log(`  [${nombre}] ${path}${id} -> ${r.status} ${r.buf.length}b player=${hasPlayer} gone=${gone} -> ${alive ? "VIVO" : "muerto"} :: ${title.slice(0, 60)}`);
      }
      if (alive) break; // si /e/ ya va vivo, no probar /v/
    }
  }
}

console.log("\n========== RESUMEN ==========");
for (const [srv, r] of [...results.entries()].sort()) {
  console.log(`${srv}: VIVOS ${r.live} / MUERTOS ${r.dead}`);
  for (const d of r.details) console.log(`    ${d}`);
}
