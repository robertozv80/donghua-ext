// Por cada código: descifra el playback y revisa el master HLS en busca de
// EXT-X-MEDIA (SUBTITLES / AUDIO) y de subtítulos en el propio JSON.
import fs from "node:fs";
import crypto from "node:crypto";
const src = fs.readFileSync("tools/_byse_pow.js", "utf8");
const s0 = src.indexOf("be=512,lt=be-1"), s1 = src.indexOf("export{", s0);
const { Er } = new Function(src.slice(s0, s1) + "\nreturn { Er };")();
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const H = {
  "User-Agent": UA, "Content-Type": "application/json", Accept: "application/json",
  Origin: "https://www.mundodonghua.com", Referer: "https://www.mundodonghua.com/",
  "X-Embed-Origin": "https://www.mundodonghua.com", "X-Embed-Referer": "https://www.mundodonghua.com/",
  "X-Embed-Parent": "https://www.mundodonghua.com/",
};
const api = (c, p, b, e) => fetch(`https://bysekoze.com/api/videos/${c}${p}`, { method: "POST", headers: { ...H, ...(e || {}) }, body: JSON.stringify(b) }).then((r) => r.json());
const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");
const OUT = "tools/_fm_media.txt";

async function flow(code) {
  const cap = await api(code, "/embed/captcha", {});
  if (!cap.pow_nonce) { console.log(code, "sin captcha:", JSON.stringify(cap).slice(0, 100)); return null; }
  const sol = await Er(cap.pow_nonce, cap.pow_difficulty);
  const v = await api(code, "/embed/captcha/verify", { pow_token: cap.pow_token, solution: sol });
  if (!v.token) { console.log(code, "verify KO:", JSON.stringify(v).slice(0, 100)); return null; }
  const fp = { token: v.token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
  const pb = await api(code, "/embed/playback", { fingerprint: fp }, { "X-Captcha-Token": v.token });
  const enc = pb.playback;
  if (!enc) { console.log(code, "playback KO:", JSON.stringify(pb).slice(0, 120)); return null; }
  const kp = enc.key_parts, ver = Number(enc.version);
  const key = Buffer.concat([ver, 31 - ver].filter((i) => i >= 1 && i <= kp.length).map((i) => b64u(kp[i - 1])));
  const ct = b64u(enc.payload);
  const d = crypto.createDecipheriv("aes-256-gcm", key, b64u(enc.iv));
  d.setAuthTag(ct.subarray(ct.length - 16));
  return JSON.parse(Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]).toString("utf8"));
}

for (const code of process.argv.slice(2)) {
  let dec;
  try { dec = await flow(code); } catch (e) { console.log(code, "error", e.message); continue; }
  if (!dec) { console.log(code, "sin playback"); continue; }
  const srcs = dec.sources || [];
  console.log(`\n### ${code}  fuentes=${srcs.length} tracks=${JSON.stringify(dec.tracks).slice(0, 120)} otras claves=${Object.keys(dec).join(",")}`);
  for (const s of srcs) {
    const r = await fetch(s.url, { headers: { "User-Agent": UA, Referer: "https://bysekoze.com/" } });
    const t = await r.text();
    const media = t.match(/#EXT-X-MEDIA:[^\n]*/g) || [];
    console.log(`  ${s.label} ${s.mime_type} master ${r.status} ${t.length}b  EXT-X-MEDIA=${media.length}`);
    console.log("   variantes:", (t.match(/#EXT-X-STREAM-INF[^\n]*/g) || []).map((x) => x.slice(0, 80)).join(" | "));
    media.forEach((m) => console.log("   MEDIA:", m.slice(0, 220)));
    fs.appendFileSync(OUT, `${code} ${s.label} ${r.status} media=${media.length} ${JSON.stringify(media).slice(0, 400)}\n`);
  }
}