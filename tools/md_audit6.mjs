// Auditoría de servidores de MundoDonghua — instance única, acumulador persistente en disco.
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

async function req(url, { method = "GET", headers = {}, body = null, timeoutMs = 12000, follow = true } = {}) {
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

function isLivePlayer(text, gone) { return !gone && /jwplayer|video-js|plyr|setup\(|sources\s*:|file\s*:|\.m3u8|\.mp4/i.test(text); }

// ---------- descubrir slugs (no persistente; renovar cada sesión) ----------
const slugs = new Set();
for (let p = 1; p <= 3; p++) {
  const url = p === 1 ? `${MAIN}/lista-donghuas` : `${MAIN}/lista-donghuas/page/${p}`;
  const r = await req(url);
  if (r.status !== 200) break;
  for (const m of r.text.matchAll(/href="(\/donghua\/[^"]+)"/g)) slugs.add(m[1].slice(8));
  console.error(`discovery page ${p}: ${r.status}, total=${slugs.size}`);
}

// orden por prioridad: slugs que usan vh/vg/sw antes (para medir obsolescencia rápido),
// pero mantener dispersión.
const ordered = [...slugs];
ordered.sort((a, b) => (ordered === a ? -1 : 0) || String(a).localeCompare(String(b)));

const todo = ordered.length > 16 ? ordered : ordered;
const acc = loadAcc();
let done = 0;

for (const slug of todo) {
  if (done >= 16) break;
  const eDoc = await req(`${MAIN}/donghua/${slug}`);
  const epMatch = eDoc.text.match(/href="([^"]*\/ver\/[^"]+)"/);
  if (!epMatch) continue;
  const epUrl = epMatch[1].startsWith("http") ? epMatch[1] : MAIN + epMatch[1];
  const eDoc2 = await req(epUrl);
  const hay = [eDoc2.text, ...unpackAll(eDoc2.text)].join("\n");
  const tag = `${slug}#${epUrl.split("/").pop()}`;

  const asuraSlug = hay.match(/redirector\.php\?slug=([A-Za-z0-9+/=]+)/)?.[1];
  const tamamoSlug = [...hay.matchAll(/slug"?\s*:\s*"([A-Za-z0-9+/=]{8,})"/g)].map((m) => m[1])[0] || hay.match(/api_donghua\.php\?slug=([A-Za-z0-9+/=]+)/)?.[1];
  const fmCode = hay.match(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  const vhId = hay.match(/vidhidepro\.com\/[ve]\/([a-zA-Z0-9]+)/)?.[1];
  const vgId = hay.match(/vgembed\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  const swId = hay.match(/embedwish\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1];
  const voeId = hay.match(/voe\.sx\/[ev]\/([a-zA-Z0-9]+)/)?.[1];

  // FM (bysekoze) — captcha solo (es el vivo de referencia)
  if (fmCode) {
    const cap = await req(`https://bysekoze.com/api/videos/${fmCode}/embed/captcha`, { method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json", Origin: MAIN, Referer: MAIN + "/" }, body: {} });
    const alive = cap.status === 200 && cap.text.includes("pow_nonce");
    acc["fm"] = String(alive ? "1" : (parseInt(acc["fm"] || "0") + (alive ? 1 : 0)));
    done++;
    if (alive) console.log(`[VIVO] fm -> ${tag}`);
  }

  // ASURA
  if (asuraSlug) {
    for (const base of ["mdplayer.xyz/nemonicplayer", "mdnemonicplayer.xyz/nemonicplayer"]) {
      const r = await req(`https://www.${base}/redirector.php?slug=${asuraSlug}`, { headers: { Referer: epUrl, "X-Requested-With": "XMLHttpRequest" } });
      const gone = r.text.includes("no longer available") || r.text.includes("not found") || r.text.includes("File is not");
      const alive = r.status === 200 && /m3u8|mp4|sources|setup|jwplayer|video/i.test(r.text) && !gone;
      const key = `asura@${base}`;
      acc[key] = String(alive ? "1" : (parseInt(acc[key] || "0") + (alive ? 1 : 0)));
      done++;
      if (alive) console.log(`[VIVO] ${key} -> ${tag}`);
      else console.log(`[MUERTO] ${key} -> ${tag} (${r.status} ${r.buf.length}b)`);
    }
  }

  // TAMAMO
  if (tamamoSlug) {
    const api = await req(`${MAIN}/api_donghua.php?slug=${encodeURIComponent(tamamoSlug)}`, { headers: { "X-Requested-With": "XMLHttpRequest", Referer: epUrl } });
    let key = null;
    try { const j = JSON.parse(api.text); key = Array.isArray(j) ? j[0]?.url : j?.url; } catch {}
    if (key) {
      const player = await req(`https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=${encodeURIComponent(key)}`, { headers: { Referer: epUrl } });
      const gone = player.text.includes("no longer available") || player.text.includes("not found") || player.text.includes("File is not");
      const alive = player.status === 200 && isLivePlayer(player.text, gone);
      const k = "tamamo";
      acc[k] = String(alive ? "1" : (parseInt(acc[k] || "0") + (alive ? 1 : 0)));
      done++;
      if (alive) console.log(`[VIVO] tamamo -> ${tag}`);
      else console.log(`[MUERTO] tamamo -> ${tag} (api ${api.status}, dmplayer ${player.status} ${player.buf.length}b)`);
    } else {
      const k = "tamamo";
      acc[k] = String(parseInt(acc[k] || "0") + 1);
      done++;
      console.log(`[MUERTO] tamamo -> ${tag} (api sin key ${api.status} ${api.text.slice(0, 40)})`);
    }
  }

  // VH / VG / SW / VOE
  for (const [nombre, id, dom] of [["vh", vhId, "vidhidepro.com"], ["vg", vgId, "vgembed.com"], ["sw", swId, "embedwish.com"], ["voe", voeId, "voe.sx"]]) {
    if (!id) continue;
    // probar /v/ primero (vidhide tiene tanto /e/ como /v/)
    const paths = dom === "vidhidepro.com" ? ["/v/", "/e/"] : ["/e/"];
    for (const path of paths) {
      const r = await req(`https://${dom}${path}${id}`, { headers: { Referer: epUrl } });
      const gone = /no longer available|file (is )?not (found|available)|deleted|404/i.test(r.text);
      const alive = r.status === 200 && isLivePlayer(r.text, gone);
      const key = `${nombre}@${path.replace(/\//g, "")}`;
      acc[key] = String(alive ? "1" : (parseInt(acc[key] || "0") + (alive ? 1 : 0)));
      done++;
      if (alive) {
        console.log(`[VIVO] ${key} -> ${tag}`);
        break; // solo un path vivo cuenta
      } else {
        console.log(`[MUERTO] ${key} -> ${tag} (${path} ${r.status} ${r.buf.length}b ${gone ? "gone" : "player"})`);
      }
    }
  }
}

saveAcc(acc);
console.log("\n========== RESUMEN ==========");
const order = ["fm", "asura@mdplayer.xyznemonicplayer", "asura@mdnemonicplayer.xyznememonicplayer", "tamamo", "vh@v", "vh@e", "vg@e", "sw@e", "voe@e"];
for (const k of order) {
  const v = acc[k] || "0";
  console.log(`${k}: muestras vivas=${v}`);
}
for (const [k, v] of Object.entries(acc).sort()) {
  if (!order.includes(k)) console.log(`${k}: vivas=${v}`);
}
