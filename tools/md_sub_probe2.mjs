// Prueba rutas de subtítulos en el CDN de bysekoze.
import crypto from "node:crypto";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const H = { "User-Agent": UA, Accept: "application/json", Origin: "https://www.mundodonghua.com", Referer: "https://www.mundodonghua.com/" };
const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");
const code = process.argv[2] || "kapr9js9ixdp";
const j = await (await fetch(`https://bysekoze.com/api/videos/${code}`, { headers: H })).json();
const enc = j.playback;
const kp = enc.key_parts, ver = Number(enc.version);
const key = Buffer.concat([ver, 31 - ver].filter((i) => i >= 1 && i <= kp.length).map((i) => b64u(kp[i - 1])));
const ct = b64u(enc.payload);
const d = crypto.createDecipheriv("aes-256-gcm", key, b64u(enc.iv));
d.setAuthTag(ct.subarray(ct.length - 16));
const dec = JSON.parse(Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]).toString("utf8"));
const master = dec.sources[0].url;
console.log("master:", master);
const q = master.includes("?") ? master.slice(master.indexOf("?")) : "";
const u = new URL(master);
const base = `${u.origin}${u.pathname.replace(/\/master\.m3u8$/, "")}`;
console.log("base:", base);
const cands = [
  `${u.origin}${u.pathname}`, `${base}/index.m3u8${q}`, `${base}/subtitles.m3u8${q}`, `${base}/subs.m3u8${q}`,
  `${base}/lang/eng/${code}_eng/index.m3u8${q}`, `${base}/lang/spa/${code}_spa/index.m3u8${q}`,
  `${base}/${code}.vtt${q}`, `${base}/subs/${code}.vtt${q}`, `${base}/captions/${code}.vtt${q}`,
  `${u.origin}/hls2/${u.pathname.split("/")[3]}/${u.pathname.split("/")[4]}/lang/eng/${code}_eng/index.m3u8${q}`,
];
for (const c of cands) {
  try {
    const r = await fetch(c, { headers: { "User-Agent": UA, Referer: "https://bysekoze.com/" } });
    const t = await r.text();
    console.log(`${r.status} ${String(t.length).padStart(6)}b  ${c.replace(q, "?<token>").slice(0, 110)}  :: ${JSON.stringify(t.slice(0, 70))}`);
  } catch (e) { console.log("ERR", c.slice(0, 90), e.message); }
}