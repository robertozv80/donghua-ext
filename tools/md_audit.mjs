#!/usr/bin/env node
// Auditoria en vivo de servidores de MundoDonghua.
// Para cada episodio: tabs de servidores -> embeds del packed JS ->
// prueba cada servidor en vivo y reporta VIVO/MUERTO.
import zlib from "node:zlib";

const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

async function req(url, { method = "GET", headers = {}, body = null, timeoutMs = 20000 } = {}) {
  const h = { "User-Agent": UA, ...headers };
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, {
      method, headers: h, signal: ctrl.signal,
      body: body === null ? undefined : typeof body === "string" ? body : JSON.stringify(body),
      redirect: "manual",
    });
    const buf = Buffer.from(await r.arrayBuffer());
    let text = "";
    try { text = (r.headers.get("content-encoding") === "gzip" && buf.length && buf[0] === 0x1f) ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
    return { status: r.status, headers: r.headers, text, buf };
  } catch (e) {
    return { status: 0, headers: null, text: "", buf: Buffer.alloc(0), err: e.message };
  } finally { clearTimeout(t); }
}

// Desempaquetador Dean Edwards: la línea completa
// eval(function(p,a,c,k,e,d){...}(args)) -> (function(...){...}(args))
function unpackOne(line) {
  try {
    if (!line.endsWith("))")) return null;
    const expr = "(" + line.slice(5, -1) + ")";
    const out = eval(expr);
    if (typeof out !== "string" || out.includes("function(p,a,c,k,e")) return null;
    return out;
  } catch { return null; }
}

function unpackAll(html) {
  const out = [];
  for (const m of html.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
    const u = unpackOne(m[0]);
    if (u) out.push(u);
  }
  return out;
}

// Extrae embeds del HTML crudo + desempaquetado
function extractServers(html, unpacked) {
  const hay = [html, ...unpacked].join("\n");
  const res = {
    asura: [], tamamo: [], fm: [], vhide: [], vg: [], sw: [], voe: [], filemoon: [], streamwish: [],
  };
  for (const m of hay.matchAll(/redirector\.php\?slug=([A-Za-z0-9+/=]+)/g)) res.asura.push(m[1]);
  for (const m of hay.matchAll(/api_donghua\.php\?slug=([A-Za-z0-9+/=]+)/g)) res.tamamo.push(m[1]);
  for (const m of hay.matchAll(/slug\s*[:=]\s*['"]([A-Za-z0-9+/=]{8,})['"]/g)) res.tamamo.push(m[1]);
  for (const m of hay.matchAll(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/g)) res.fm.push(m[1]);
  for (const m of hay.matchAll(/filemoon\.to\/[ev]\/([a-zA-Z0-9]+)/g)) res.filemoon.push(m[1]);
  for (const m of hay.matchAll(/vidhidepro\.com\/[ve]\/([a-zA-Z0-9]+)/g)) res.vhide.push(m[1]);
  for (const m of hay.matchAll(/vgembed\.com\/[ev]\/([a-zA-Z0-9]+)/g)) res.vg.push(m[1]);
  for (const m of hay.matchAll(/embedwish\.com\/[ev]\/([a-zA-Z0-9]+)/g)) res.sw.push(m[1]);
  for (const m of hay.matchAll(/streamwish\.to\/[ev]\/([a-zA-Z0-9]+)/g)) res.streamwish.push(m[1]);
  for (const m of hay.matchAll(/voe\.sx\/[ev]\/([a-zA-Z0-9]+)/g)) res.voe.push(m[1]);
  for (const k of Object.keys(res)) res[k] = [...new Set(res[k])];
  return res;
}

// Tabs de servidores del HTML (button.md-server-tab[data-target])
function serverTabs(html) {
  const tabs = [];
  for (const m of html.matchAll(/<button[^>]*md-server-tab[^>]*data-target="([^"]+)"[^>]*>([^<]*)/g)) {
    tabs.push(`${m[1]}=${m[2].trim()}`);
  }
  return tabs;
}

// ===== PRUEBAS EN VIVO =====
async function probeAsura(slug, referer) {
  const r = await req(`https://www.mdnemonicplayer.xyz/nemonicplayer/redirector.php?slug=${encodeURIComponent(slug)}`, {
    headers: { Referer: referer, "X-Requested-With": "XMLHttpRequest" },
  });
  const ok = r.status === 200 && (r.text.includes("#EXTM3U") || (r.headers?.get("content-type") || "").includes("mpegurl"));
  const nMedia = (r.text || "").match(/#EXT-X-MEDIA/g)?.length || 0;
  return { ok, status: r.status, detail: ok ? `m3u8 mediaTags=${nMedia} ` + (r.text.match(/#EXT-X-STREAM-INF[^\\n]*/)?.[0]?.slice(0, 50) || "master") : r.text.slice(0, 60) };
}

async function probeTamamo(slug, main, referer) {
  const api = await req(`${main}/api_donghua.php?slug=${encodeURIComponent(slug)}`, {
    headers: { "X-Requested-With": "XMLHttpRequest", Referer: referer, Accept: "*/*" },
  });
  const key = JSON.parse(api.text || "{}")?.url;
  if (!key) return { ok: false, status: api.status, detail: "api sin key: " + api.text.slice(0, 50) };
  const player = await req(`https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=${encodeURIComponent(key)}`, { headers: { Referer: referer } });
  const vid = player.text.match(/video\s*:\s*['"]([A-Za-z0-9]+)['"]/)?.[1];
  if (!vid) return { ok: false, status: player.status, detail: "dmplayer sin video id" };
  const meta = await req(`https://www.dailymotion.com/player/metadata/video/${vid}`, {
    headers: { Referer: `https://www.dailymotion.com/embed/video/${vid}`, Accept: "application/json" },
  });
  const hasM3u8 = meta.text.includes(".m3u8");
  let subsInfo = "";
  try { const mj = JSON.parse(meta.text); if (mj.subtitles?.length) subsInfo = ` DM-SUBS=${mj.subtitles.map((s) => s.lang).join(",")}`; } catch {}
  return { ok: hasM3u8, status: meta.status, detail: `dm ${vid} metadata ${meta.status} m3u8=${hasM3u8}${subsInfo}` };
}

async function probeFm(code, referer) {
  const r = await req(`https://bysekoze.com/api/videos/${code}/embed/captcha`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json", Origin: MAIN, Referer: MAIN + "/" },
    body: {},
  });
  const ok = r.status === 200 && r.text.includes("pow_nonce");
  return { ok, status: r.status, detail: r.text.slice(0, 70) };
}

async function probeGeneric(url, referer) {
  const r = await req(url, { headers: { Referer: referer } });
  const ct = (r.headers?.get("content-type") || "");
  const isPlayer = /jwplayer|video-js|plyr|setup\(|sources\s*:|file\s*:|hls|m3u8/i.test(r.text);
  const dead = r.status === 404 || r.status === 410 || r.text.includes("no longer available") || r.text.includes("File is no");
  return { ok: r.status === 200 && !dead && (isPlayer || r.buf.length > 20000), status: r.status, detail: `${ct.split(";")[0]} ${r.buf.length}B player=${isPlayer}` };
}

// ===== MAIN =====
const args = process.argv.slice(2);
const SLUGS = args.length ? args : [
  "tales-of-demons-and-gods",
  "combat-continent-2",
  "the-first-immortal-of-sword-art",
  "star-divine-arts",
];

const report = [];
for (const slug of SLUGS) {
  console.log(`\n########## SERIE: ${slug} ##########`);
  const donghuaUrl = `${MAIN}/donghua/${slug}`;
  const dDoc = await req(donghuaUrl);
  if (dDoc.status !== 200) { console.log(`  /donghua/ -> ${dDoc.status}`); continue; }
  const eps = [...dDoc.text.matchAll(/<a[^>]*class="md-ep-link"[^>]*href="([^"]+)"[^>]*>/g)].map((m) => m[1]);
  const epList = eps.length ? eps : [...dDoc.text.matchAll(/href="([^"]*\/ver\/[^"]+)"/g)].map((m) => m[1]);
  if (!epList.length) { console.log("  sin episodios"); continue; }
  const total = epList.length;
  console.log(`  episodios: ${total}`);
  // primero, medio, ultimo (max 3)
  const picks = [...new Set([0, Math.floor(total / 2), total - 1])].sort((a, b) => a - b);
  for (const idx of picks) {
    const epUrl = epList[idx].startsWith("http") ? epList[idx] : MAIN + epList[idx];
    console.log(`\n  === ep #${idx + 1}: ${epUrl} ===`);
    const eDoc = await req(epUrl);
    if (eDoc.status !== 200) { console.log(`    HTML ${eDoc.status}`); continue; }
    const tabs = serverTabs(eDoc.text);
    console.log(`    tabs: [${tabs.join(", ")}]`);
    const unpacked = unpackAll(eDoc.text);
    console.log(`    packed blocks: ${unpacked.length}`);
    const servers = extractServers(eDoc.text, unpacked);
    const counts = Object.fromEntries(Object.entries(servers).filter(([, v]) => v.length).map(([k, v]) => [k, v.length]));
    console.log(`    embeds: ${JSON.stringify(counts)}`);
    // pistas de subtitulos en el HTML/episodio
    const subHints = [];
    for (const m of eDoc.text.matchAll(/(?:subtitle|subtitles|caption|srt|vtt)[^"']{0,40}["']?\s*[:=]\s*["']([^"']{5,120})["']/gi)) subHints.push(m[1]);
    if (subHints.length) console.log(`    SUBS HINTS: ${JSON.stringify(subHints.slice(0, 5))}`);

    // probar cada servidor presente
    for (const [srv, ids] of Object.entries(servers)) {
      if (!ids.length) continue;
      const id = ids[0];
      let res;
      try {
        if (srv === "asura") res = await probeAsura(id, epUrl);
        else if (srv === "tamamo") res = await probeTamamo(id, MAIN, epUrl);
        else if (srv === "fm") res = await probeFm(id, epUrl);
        else if (srv === "vhide") res = await probeGeneric(`https://vidhidepro.com/e/${id}`, epUrl);
        else if (srv === "vg") res = await probeGeneric(`https://vgembed.com/e/${id}`, epUrl);
        else if (srv === "sw") res = await probeGeneric(`https://embedwish.com/e/${id}`, epUrl);
        else if (srv === "streamwish") res = await probeGeneric(`https://streamwish.to/e/${id}`, epUrl);
        else if (srv === "filemoon") res = await probeGeneric(`https://filemoon.to/e/${id}`, epUrl);
        else if (srv === "voe") res = await probeGeneric(`https://voe.sx/e/${id}`, epUrl);
        else continue;
      } catch (e) { res = { ok: false, status: 0, detail: e.message }; }
      report.push({ slug, ep: idx + 1, srv, id, ...res });
      console.log(`    [${res.ok ? "VIVO" : "MUERTO"}] ${srv} (${id}) -> ${res.status} ${res.detail}`);
    }
  }
}

console.log("\n\n========== RESUMEN ==========");
const agg = {};
for (const r of report) {
  agg[r.srv] = agg[r.srv] || { vivo: 0, muerto: 0, eps: [] };
  agg[r.srv][r.ok ? "vivo" : "muerto"]++;
  agg[r.srv].eps.push(`${r.slug.slice(0, 18)}#${r.ep}:${r.ok ? "V" : "M"}`);
}
for (const [srv, a] of Object.entries(agg)) {
  console.log(`${srv}: VIVOS ${a.vivo} / MUERTOS ${a.muerto}  [${a.eps.join(", ")}]`);
}
