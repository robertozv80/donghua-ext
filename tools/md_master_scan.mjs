// Descifra (API pública, sin PoW) y revisa los master HLS en busca de subtítulos.
import fs from "node:fs";
import crypto from "node:crypto";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const H = { "User-Agent": UA, Accept: "application/json", Origin: "https://www.mundodonghua.com", Referer: "https://www.mundodonghua.com/" };
const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");
function decrypt(enc) {
  const kp = enc.key_parts, ver = Number(enc.version);
  const key = Buffer.concat([ver, 31 - ver].filter((i) => i >= 1 && i <= kp.length).map((i) => b64u(kp[i - 1])));
  const ct = b64u(enc.payload);
  const d = crypto.createDecipheriv("aes-256-gcm", key, b64u(enc.iv));
  d.setAuthTag(ct.subarray(ct.length - 16));
  return JSON.parse(Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]).toString("utf8"));
}
const codes = fs.readFileSync("tools/_fm_codes.txt", "utf8").split("\n").filter(Boolean);
const resumen = [];
let conSubs = 0;
for (const code of codes) {
  try {
    const r = await fetch(`https://bysekoze.com/api/videos/${code}`, { headers: H });
    if (!r.ok) continue;
    const j = await r.json();
    if (!j.playback) continue;
    const dec = decrypt(j.playback);
    for (const s of dec.sources || []) {
      const mr = await fetch(s.url, { headers: { "User-Agent": UA, Referer: "https://bysekoze.com/" } });
      const t = await mr.text();
      const media = t.match(/#EXT-X-MEDIA:[^\n]*/g) || [];
      const subs = media.filter((m) => m.includes("SUBTITLES"));
      console.log(`${code} ${s.label} master=${mr.status} media=${media.length} subs=${subs.length} variantes=${(t.match(/#EXT-X-STREAM-INF/g) || []).length}`);
      media.forEach((m) => console.log("    ", m.slice(0, 200)));
      if (subs.length) { conSubs++; resumen.push(`${code} ${s.label} ${JSON.stringify(subs)}`); fs.writeFileSync(`tools/_fm_master_${code}.m3u8`, t); }
    }
  } catch (e) { console.log(code, "ERR", e.message); }
}
fs.writeFileSync("tools/_fm_subs_masters.txt", resumen.join("\n") + "\n");
console.log("\nvideos con pista de subtitulos:", conSubs);