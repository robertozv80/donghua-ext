#!/usr/bin/env node
// Auditoria v2 de servidores de MundoDonghua.
// - Asura: prueba mdplayer.xyz Y mdnemonicplayer.xyz (el dominio del sitio cambió)
// - Tamamo: slug b64 va como {"slug":"..."} en el unpacked
// - Vhide/Vg/Sw: sigue redirects para distinguir 301 canonico de 404
import zlib from "node:zlib";

const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

async function req(url, { method = "GET", headers = {}, body = null, timeoutMs = 8000, follow = true } = {}) {
  const h = { "User-Agent": UA, ...headers };
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, {
      method, headers: h, signal: ctrl.signal, redirect: follow ? "follow" : "manual",
      body: body === null ? undefined : typeof body === "string" ? body : JSON.stringify(body),
    });
    const buf = Buffer.from(await r.arrayBuffer());
    let text = "";
    try { text = (r.headers.get("content-encoding") === "gzip" && buf.length && buf[0] === 0x1f) ? zlib.gunzipSync(buf).toString("utf8") : buf.toString("utf8"); } catch {}
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
function unpackAll(html) {
  const out = [];
  for (const m of html.matchAll(/eval\(function\(p,a,c,k,e,[^\r\n]+/g)) {
    const u = unpackOne(m[0]);
    if (u) out.push(u);
  }
  return out;
}

function extractServers(html, unpacked) {
  const hay = [html, ...unpacked].join("\n");
  const res = { asura: [], asuraAlt: [], tamamo: [], fm: [], vhide: [], vg: [], sw: [], voe: [] };
  // asura: el slug va en jwplayer setup sources file:...redirector.php?slug=XXX
  for (const m of hay.matchAll(/redirector\.php\?slug=([A-Za-z0-9+/=]+)/g)) res.asura.push(m[1]);
  // tamamo: {"slug":"..."} o slug: "..."
  for (const m of hay.matchAll(/slug"?\s*:\s*"([A-Za-z0-9+/=]{16,})"/g)) res.tamamo.push(m[1]);
  for (const m of hay.matchAll(/api_donghua\.php\?slug=([A-Za-z0-9+/=]+)/g)) res.tamamo.push(m[1]);
  for (const m of hay.matchAll(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/g)) res.fm.push(m[1]);
  for (const m of hay.matchAll(/filemoon\.to\/[ev]\/([a-zA-Z0-9]+)/g)) res.fm.push(m[1]);
  for (const m of hay.matchAll(/vidhidepro\.com\/[ve]\/([a-zA-Z0-9]+)/g)) res.vhide.push(m[1]);
  for (const m of hay.matchAll(/vgembed\.com\/[ev]\/([a-zA-Z0-9]+)/g)) res.vg.push(m[1]);
  for (const m of hay.matchAll(/embedwish\.com\/[ev]\/([a-zA-Z0-9]+)/g)) res.sw.push(m[1]);
  for (const m of hay.matchAll(/streamwish\.to\/[ev]\/([a-zA-Z0-9]+)/g)) res.sw.push(m[1]);
  for (const m of hay.matchAll(/voe\.sx\/[ev]\/([a-zA-Z0-9]+)/g)) res.voe.push(m[1]);
  for (const k of Object.keys(res)) res[k] = [...new Set(res[k])];
  return res;
}

// ==== probes ====
async function probeAsura(slug, referer) {
  const out = [];  for (const base of ["https://www.mdplayer.xyz/nemonicplayer", "https://www.mdnemonicplayer.xyz/nemonicplayer"]) {
    const r = await req(`${base}/redirector.php?slug=${slug}`, {
      headers: { Referer: referer, "X-Requested-With": "XMLHttpRequest" }, follow: false,
    });
    const loc = r.headers?.get("location") || "";
    const ok = r.status === 200 && (r.text || "").includes("#EXTM3U");
    const m3u8 = r.status === 200;
    out.push({ base: base.replace("https://www.", ""), status: r.status, ok, detail: `${r.status} ${(r.text || "").slice(0, 40).replace(/\s/g, " ")}`, loc: loc.slice(0, 90) });
  }
  return out;
}

async function probeTamamo(slug, referer) {
  const api = await req(`${MAIN}/api_donghua.php?slug=${encodeURIComponent(slug)}`, {
    headers: { "X-Requested-With": "XMLHttpRequest", Referer: referer, Accept: "*/*" },
  });
  let key = null;
  try { const j = JSON.parse(api.text || "{}"); key = Array.isArray(j) ? j[0]?.url : j?.url; } catch {}
  if (!key) return { ok: false, detail: `api ${api.status}: ${api.text.slice(0, 60)}` };
  for (const base of ["https://www.mdplayer.xyz/nemonicplayer", "https://www.mdnemonicplayer.xyz/nemonicplayer"]) {
    const player = await req(`${base}/dmplayer.php?key=${encodeURIComponent(key)}`, { headers: { Referer: referer } });
    const vid = player.text.match(/video\s*:\s*['"]([A-Za-z0-9]+)['"]/)?.[1];
    if (vid) return await dmMeta(vid, base);
  }
  return { ok: false, detail: `dmplayer sin video id` };
}
async function dmMeta(vid, base = "") {
  const meta = await req(`https://www.dailymotion.com/player/metadata/video/${vid}`, {
    headers: { Referer: `https://www.dailymotion.com/embed/video/${vid}`, Accept: "application/json" },
  });
  const hasM3u8 = meta.text.includes(".m3u8");
  let subs = "";
  try { const mj = JSON.parse(meta.text); if (mj.subtitles?.length) subs = ` SUBS=${mj.subtitles.map((s) => s.lang).join(",")}`; } catch {}
  return { ok: hasM3u8, detail: `dm ${vid} ${meta.status} m3u8=${hasM3u8}${subs} [${base.replace("https://www.", "").replace("/nemonicplayer", "")}]` };
}

async function probeFm(code) {
  const r = await req(`https://bysekoze.com/api/videos/${code}/embed/captcha`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json", Origin: MAIN, Referer: MAIN + "/" },
    body: {},
  });
  return { ok: r.status === 200 && r.text.includes("pow_nonce"), detail: `${r.status} ${r.text.slice(0, 50)}` };
}

// Para embeds tipo StreamWish: el embed real está en /e/ID (HTML del player)
async function probeEmbed(url, referer) {
  const r = await req(url, { headers: { Referer: referer } });
  const t = r.text || "";
  const ct = (r.headers?.get("content-type") || "").split(";")[0];
  const dead404 = r.status === 404 || r.status === 410;
  const gone = /no longer available|file is no|video (was |is )?(deleted|removed)|This video/i.test(t);
  const isPlayer = /jwplayer|video-js|plyr|sources\s*:|setup\(|file\s*:|\.m3u8|\.mp4/i.test(t);
  return { ok: !dead404 && !gone && (isPlayer || r.buf.length > 30000), status: r.status, ct, bytes: r.buf.length, isPlayer, gone: gone || dead404 };
}

// ===== MAIN =====
const args = process.argv.slice(2);
const SLUGS = args.length ? args : ["combat-continent-2"];

const report = [];
for (const slug of SLUGS) {
  console.log(`\n########## SERIE: ${slug} ##########`);
  const dDoc = await req(`${MAIN}/donghua/${slug}`);
  if (dDoc.status !== 200) { console.log(`  /donghua/ -> ${dDoc.status}`); continue; }
  const eps = [...dDoc.text.matchAll(/<a[^>]*class="md-ep-link"[^>]*href="([^"]+)"[^>]*>/g)].map((m) => m[1]);
  const epList = eps.length ? eps : [...dDoc.text.matchAll(/href="([^"]*\/ver\/[^"]+)"/g)].map((m) => m[1]);
  if (!epList.length) { console.log("  sin episodios"); continue; }
  const total = epList.length;
  console.log(`  episodios: ${total} (listado ${total > 1 && epList[0].includes("/" + total + "/") ? "invertido" : "ordenado"})`);
  const picks = [...new Set([0, total - 1])].sort((a, b) => a - b);
  for (const idx of picks) {
    let epUrl = epList[idx].startsWith("http") ? epList[idx] : MAIN + epList[idx];
    console.log(`\n  === ep #${idx + 1}: ${epUrl} ===`);
    const eDoc = await req(epUrl);
    if (eDoc.status !== 200) { console.log(`    HTML ${eDoc.status}`); continue; }
    const unpacked = unpackAll(eDoc.text);
    const servers = extractServers(eDoc.text, unpacked);
    const counts = Object.fromEntries(Object.entries(servers).filter(([, v]) => v.length).map(([k, v]) => [k, v.length]));
    console.log(`    embeds: ${JSON.stringify(counts)}`);

    for (const [srv, ids] of Object.entries(servers)) {
      if (!ids.length) continue;
      const id = ids[0];
      let res;
      try {
        if (srv === "asura") res = await probeAsura(id, epUrl);
        else if (srv === "tamamo") res = [await probeTamamo(id, epUrl)];
        else if (srv === "fm") res = [await probeFm(id)];
        else if (srv === "vhide") res = [await probeEmbed(`https://vidhidepro.com/e/${id}`, epUrl)];
        else if (srv === "vg") res = [await probeEmbed(`https://vgembed.com/e/${id}`, epUrl)];
        else if (srv === "sw") res = [await probeEmbed(`https://embedwish.com/e/${id}`, epUrl)];
        else if (srv === "voe") res = [await probeEmbed(`https://voe.sx/e/${id}`, epUrl)];
        else continue;
      } catch (e) { res = [{ ok: false, detail: e.message }]; }
      for (const r of res) {
        report.push({ slug, ep: idx + 1, srv, id, ...r });
        const tag = srv === "asura" ? `asura@${r.base}` : srv;
        console.log(`    [${r.ok ? "VIVO" : "MUERTO"}] ${tag} -> ${r.status ?? ""} ${r.detail ?? ""} ${r.loc ? "-> " + r.loc : ""}`);
      }
    }
  }
}

console.log("\n\n========== RESUMEN ==========");
const agg = {};
for (const r of report) {
  const key = r.srv === "asura" ? `asura@${r.base || "?"}` : r.srv;
  agg[key] = agg[key] || { vivo: 0, muerto: 0, eps: [] };
  agg[key][r.ok ? "vivo" : "muerto"]++;
  agg[key].eps.push(`${r.slug.slice(0, 16)}#${r.ep}:${r.ok ? "V" : "M"}`);
}
for (const [srv, a] of Object.entries(agg)) {
  console.log(`${srv}: VIVOS ${a.vivo} / MUERTOS ${a.muerto}  [${a.eps.join(", ")}]`);
}
