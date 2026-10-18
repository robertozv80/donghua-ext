// Simula EXACTAMENTE el flujo nuevo del extractor Fm (Kotlin) en Node:
//  1) GET publica /api/videos/{code} -> playback
//  2) decryptPayload con etiqueta GCM separada
//  3) quality + deteccion del master
import crypto from "node:crypto";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const API = "https://bysekoze.com";
const ORIGIN = "https://www.mundodonghua.com";
const apiHeaders = { "User-Agent": UA, Accept: "application/json", Origin: ORIGIN, Referer: ORIGIN + "/", "X-Embed-Origin": ORIGIN, "X-Embed-Referer": ORIGIN + "/", "X-Embed-Parent": ORIGIN + "/" };
const b64UrlDecode = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");

function decryptPayload(enc) {
  const keyParts = enc.key_parts;
  if (!keyParts) return null;
  const version = Number(String(enc.version).match(/\d+/)?.[0] ?? NaN);
  if (!Number.isFinite(version)) return null;
  const selected = [];
  for (const idx of [version, 31 - version]) {
    const part = idx >= 1 && idx <= keyParts.length ? keyParts[idx - 1] : null;
    if (part) selected.push(part);
  }
  if (!selected.length) selected.push(...keyParts);
  const key = Buffer.concat(selected.map(b64UrlDecode));
  if (![16, 24, 32].includes(key.length)) return null;
  const iv = b64UrlDecode(enc.iv);
  const payload = b64UrlDecode(enc.payload);
  if (payload.length <= 16) return null;
  const tag = payload.subarray(payload.length - 16);
  const body = payload.subarray(0, payload.length - 16);
  try {
    const c = crypto.createDecipheriv("aes-256-gcm", key, iv);
    c.setAuthTag(tag);
    return JSON.parse(Buffer.concat([c.update(body), c.final()]).toString("utf8"));
  } catch { return null; }
}

for (const code of process.argv.slice(2)) {
  const r = await fetch(`${API}/api/videos/${code}`, { headers: apiHeaders });
  const txt = await r.text();
  if (!txt.includes('"playback"')) { console.log(code, "sin playback:", txt.slice(0, 100)); continue; }
  const video = JSON.parse(txt);
  const dec = decryptPayload(video.playback);
  if (!dec) { console.log(code, "descifrado FALLO"); continue; }
  let masterForSubs = null;
  console.log(`\n### ${code}  fuentes=${dec.sources.length} tracks=${(video.tracks || []).length}`);
  for (const s of dec.sources) {
    const isHls = /mpegurl/i.test(s.mime_type || "") || s.url.includes(".m3u8");
    if (isHls && !masterForSubs && s.url.split("?")[0].endsWith("master.m3u8")) masterForSubs = s.url;
    const q = s.height >= 1080 ? 1080 : s.height >= 720 ? 720 : s.height >= 480 ? 480 : 0;
    console.log(`  ${isHls ? "HLS" : "MP4"} ${s.label} -> calidad=${q} ${s.url.slice(0, 95)}`);
  }
  // paso de subtitulos: como en emitSubtitles
  if (!(video.tracks || []).length && !masterForSubs) { console.log("  sin subtitulos (no hay tracks ni master)"); continue; }
  const mr = await fetch(masterForSubs, { headers: { "User-Agent": UA, Referer: API + "/" } });
  const mt = await mr.text();
  const media = mt.match(/#EXT-X-MEDIA:[^\n]*/g) || [];
  console.log(`  master ${mr.status} ${mt.length}b  EXT-X-MEDIA=${media.length}`, media.map((m) => m.slice(0, 120)).join(" | ") || "(ninguna)");
}