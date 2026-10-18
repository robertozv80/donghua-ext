// Auditoría de MundoDonghua — acumulador persistente, reanudable.
import fs from "node:fs";
import zlib from "node:zlib";

const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const ACC = "tools/_md_audit_acc.txt";

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

async function req(url, { method = "GET", headers = {}, body = null, timeoutMs = 10000, follow = true } = {}) {
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

function loadAcc() {
  if (fs.existsSync(ACC)) {
    try { return Object.fromEntries((fs.readFileSync(ACC, "utf8").trim() || "").split("\n").map((l) => l.split("=").slice(0, 2))); } catch {}
  }
  return {};
}
function saveAcc(acc) { fs.writeFileSync(ACC, Object.entries(acc).map(([k, v]) => `${k}=${v}`).join("\n") + "\n"); }

function isLivePlayer(text) { return /jwplayer|video-js|plyr|setup\(|sources\s*:|file\s*:|\.m3u8|\.mp4/i.test(text); }

// ---------- descubrir slugs ----------
const slugs = new Set();
for (let p = 1; p <= 4; p++) {
  const url = p === 1 ? `${MAIN}/lista-donghuas` : `${MAIN}/lista-donghuas/page/${p}`;
  const r = await req(url, { timeoutMs: 8000 });
  if (r.status !== 200) break;
  for (const m of r.text.matchAll(/href="(\/donghua\/[^"]+)"/g)) slugs.add(m[1].slice(8));
  console.error(`discovery page ${p}: ${r.status}, total=${slugs.size}`);
}

// Priorizar slugs sin haber probado y los más probables de tener vg/sw/vh
const acc = loadAcc();
const neverChecked = [...slugs].filter((s) => {
  // si no hemos probado ninguno de estos servidores para el slug (aprox por tags)
  return true;
});

const ordered = [...slugs];
// Mezclar: alternar entre grupos para evitar que un solo provider concentre resultados.
const res = [];
const groupSize = Math.max(1, Math.floor(ordered.length / 20));
for (let i = 0; i < ordered.length && res.length < 20; i += groupSize) res.push(ordered[i]);
for (let i = 1; i < ordered.length && res.length < 20; i += groupSize) res.push(ordered[i]);

const todo = res.length ? res : ordered;

const sampled = new Set();
for (const slug of todo) {
  if (sampled.size >= 20) break;
  sampled.add(slug);
}

for (const slug of sampled) {
  const eDoc = await req(`${MAIN}/donghua/${slug}`);
  const epMatch = eDoc.text.match(/href="([^"]*\/ver\/[^"]+)"/);
  if (!epMatch) continue;
  const epUrl = epMatch[1].startsWith("http") ? epMatch[1] : MAIN + epMatch[1];
  const eDoc2 = await req(epUrl, { timeoutMs: 10000 });
  const hay = [eDoc2.text, ...unpackAll(eDoc2.text)].join("\n");
  const tag = `${slug}#${epUrl.split("/").pop()}`;

  const asuraSlug = hay.match(/redirector\.php\?slug=([A-Za-z0-9+/=]+)/)?.[1];
  const tamamoSlug = [...hay.matchAll(/slug"?\s*:\s*"([A-Za-z0-9+/=]{8,})"/g)].map((m) => m[1])[0] || hay.match(/api_donghua\.php\?slug=([A-Za-z0-9+/=]+)/)?.[1];
  const fmCode = hay.match(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  const vhId = hay.match(/vidhidepro\.com\/[ve]\/([a-zA-Z0-9]+)/)?.[1];
  const vgId = hay.match(/vgembed\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  const swId = hay.match(/embedwish\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  const voeId = hay.match(/voe\.sx\/[ev]\/([a-zA-Z0-9]+)/)?.[1];

  // FM (bysekoze) — captcha rápido
  if (fmCode) {
    const cap = await req(`https://bysekoze.com/api/videos/${fmCode}/embed/captcha`, { method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json", Origin: MAIN, Referer: MAIN + "/" }, body: {}, timeoutMs: 8000 });
    const alive = cap.status === 200 && cap.text.includes("pow_nonce");
    acc["fm"] = String(alive ? "1" : (parseInt(acc["fm"] || "0") + (alive ? 1 : 0)));
    console.log(`[FM] ${alive ? "VIVO" : "MUERTO"} -> ${tag}`);
  }

  // ASURA
  if (asuraSlug) {
    for (const base of ["mdplayer.xyz/nemonicplayer", "mdnemonicplayer.xyz/nemonicplayer"]) {
      const r = await req(`https://www.${base}/redirector.php?slug=${asuraSlug}`, { headers: { Referer: epUrl, "X-Requested-With": "XMLHttpRequest" }, timeoutMs: 6000 });
      const gone = /no longer available|not found|File is not/i.test(r.text);
      const alive = r.status === 200 && /m3u8|mp4|sources|setup|jwplayer|video/i.test(r.text) && !gone;
      const key = `asura@${base}`;
      acc[key] = String(alive ? "1" : (parseInt(acc[key] || "0") + (alive ? 1 : 0)));
      console.log(`[ASURA@${base.split(".")[0]}] ${alive ? "VIVO" : "MUERTO"} -> ${tag} (${r.status} ${r.buf.length}b)`);
    }
  }

  // TAMAMO
  if (tamamoSlug) {
    const api = await req(`${MAIN}/api_donghua.php?slug=${encodeURIComponent(tamamoSlug)}`, { headers: { "X-Requested-With": "XMLHttpRequest", Referer: epUrl }, timeoutMs: 6000 });
    let key = null;
    try { const j = JSON.parse(api.text); key = Array.isArray(j) ? j[0]?.url : j?.url; } catch {}
    if (key) {
      const player = await req(`https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=${encodeURIComponent(key)}`, { headers: { Referer: epUrl }, timeoutMs: 6000 });
      const alive = player.status === 200 && isLivePlayer(player.text) && !/no longer available|not found|File is not/i.test(player.text);
      acc["tamamo"] = String(alive ? "1" : (parseInt(acc["tamamo"] || "0") + (alive ? 1 : 0)));
      console.log(`[TAMAMO] ${alive ? "VIVO" : "MUERTO"} -> ${tag} (api ${api.status}, dmplayer ${player.status} ${player.buf.length}b)`);
    } else {
      acc["tamamo"] = String(parseInt(acc["tamamo"] || "0") + 1);
      console.log(`[TAMAMO] MUERTO -> ${tag} (api sin key ${api.status} ${api.text.slice(0, 40)})`);
    }
  }

  // VH / VG / SW / VOE
  for (const [nombre, id, dom] of [["vh", vhId, "vidhidepro.com"], ["vg", vgId, "vgembed.com"], ["sw", swId, "embedwish.com"], ["voe", voeId, "voe.sx"]]) {
    if (!id) continue;
    const paths = dom === "vidhidepro.com" ? ["/v/", "/e/"] : ["/e/"];
    for (const path of paths) {
      const r = await req(`https://${dom}${path}${id}`, { headers: { Referer: epUrl }, timeoutMs: 6000 });
      const gone = /no longer available|file (is )?not (found|available)|deleted|404/i.test(r.text);
      const alive = r.status === 200 && isLivePlayer(r.text) && !gone;
      const key = `${nombre}@${path.replace(/\//g, "")}`;
      acc[key] = String(alive ? "1" : (parseInt(acc[key] || "0") + (alive ? 1 : 0)));
      console.log(`[${nombre}@${path.replace(/\//g, "")}] ${alive ? "VIVO" : "MUERTO"} -> ${tag} (${path} ${r.status} ${r.buf.length}b ${gone ? "gone" : "player"})`);
      if (alive) break;
    }
  }

  saveAcc(acc);
}

saveAcc(acc);
console.log("\n========== RESUMEN ==========");
for (const k of ["fm", "asura@mdplayer.xyz/nemonicplayer", "asura@mdnemonicplayer.xyz/nemonicplayer", "tamamo", "vh@v", "vh@e", "vg@e", "sw@e", "voe@e"]) {
  if (acc[k]) console.log(`${k}: ${acc[k]}`);
}
for (const [k, v] of Object.entries(acc).sort()) {
  if (!["fm", "asura@mdplayer.xyz/nemonicplayer", "asura@mdnemonicplayer.xyz/nemonicplayer", "tamamo", "vh@v", "vh@e", "vg@e", "sw@e", "voe@e"].includes(k)) console.log(`${k}: ${v}`);
}
