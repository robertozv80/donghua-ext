// Flujo completo: PoW literal -> playback -> descifrado -> master HLS -> subtítulos
// (fusión de segmentos VTT en un archivo único) + volcado de证据 para el provider.
import fs from "node:fs";
import crypto from "node:crypto";

const src = fs.readFileSync("tools/_byse_pow.js", "utf8");
const s0 = src.indexOf("be=512,lt=be-1"), s1 = src.indexOf("export{", s0);
const { Er, wr, gr } = new Function(src.slice(s0, s1) + "\nreturn { Er, wr, gr };")();

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const H = {
  "User-Agent": UA, "Content-Type": "application/json", Accept: "application/json",
  Origin: "https://www.mundodonghua.com", Referer: "https://www.mundodonghua.com/",
  "X-Embed-Origin": "https://www.mundodonghua.com", "X-Embed-Referer": "https://www.mundodonghua.com/",
  "X-Embed-Parent": "https://www.mundodonghua.com/",
};
const code = process.argv[2];
const api = (p, body, extra) => fetch(`https://bysekoze.com/api/videos/${code}${p}`, {
  method: "POST", headers: { ...H, ...(extra || {}) }, body: JSON.stringify(body),
}).then((r) => r.json());

const cap = await api("/embed/captcha", {});
const t0 = Date.now();
const sol = await Er(cap.pow_nonce, cap.pow_difficulty);
console.log(`PoW dificultad=${cap.pow_difficulty} solution=${sol} en ${Date.now() - t0}ms bits=${wr(gr(new TextEncoder().encode(cap.pow_nonce + ":" + sol)))}`);
const v = await api("/embed/captcha/verify", { pow_token: cap.pow_token, solution: sol });
if (!v.token) { console.log("verify fallo:", JSON.stringify(v)); process.exit(1); }
const fp = { token: v.token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
const pb = await api("/embed/playback", { fingerprint: fp }, { "X-Captcha-Token": v.token });
const enc = pb.playback;

const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");
const kp = enc.key_parts, ver = Number(enc.version);
const key = Buffer.concat([ver, 31 - ver].filter((i) => i >= 1 && i <= kp.length).map((i) => b64u(kp[i - 1])));
const iv = b64u(enc.iv), ct = b64u(enc.payload);
const d = crypto.createDecipheriv("aes-256-gcm", key, iv);
d.setAuthTag(ct.subarray(ct.length - 16));
const dec = JSON.parse(Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]).toString("utf8"));
console.log("=== playback descifrado ===");
console.log(JSON.stringify(dec, null, 1).slice(0, 2200));

const get = async (url, headers = {}) => {
  const r = await fetch(url, { headers: { "User-Agent": UA, ...headers } });
  return { status: r.status, text: await r.text() };
};
for (const s of dec.sources || []) {
  console.log(`\n### ${s.label} ${s.mime_type} -> ${s.url.slice(0, 120)}...`);
  const m = await get(s.url, { Referer: "https://bysekoze.com/" });
  console.log("master", m.status, m.text.length, "bytes;", (m.text.match(/#EXT-X-STREAM-INF/g) || []).length, "variantes");
  const media = m.text.match(/#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*/g) || [];
  if (!media.length) { console.log("  sin EXT-X-MEDIA de subtítulos"); continue; }
  for (const line of media) {
    const uri = line.match(/URI="([^"]+)"/)?.[1];
    const name = line.match(/NAME="([^"]*)"/)?.[1];
    const lang = line.match(/LANGUAGE="([^"]*)"/)?.[1];
    console.log(`  MEDIA name="${name}" lang="${lang}" uri=${uri}`);
    if (!uri) continue;
    const abs = new URL(uri, s.url).toString();
    const pl = await get(abs, { Referer: s.url });
    const segs = [...pl.text.matchAll(/^(?!#)([^\r\n]+)$/gm)].map((m) => m[1].trim()).filter(Boolean);
    console.log("  playlist", pl.status, pl.text.length, "b,", segs.length, "segmentos");
    console.log("  primeras lineas:", JSON.stringify(pl.text.split(/\r?\n/).slice(0, 8)));
    let out = "WEBVTT\n\n", n = 0;
    for (const sg of segs) {
      const sr = await get(new URL(sg, abs).toString(), { Referer: s.url });
      if (sr.status !== 200 || !sr.text.trim()) { console.log("   seg KO", sr.status, sg.slice(-30)); continue; }
      n++;
      let t = sr.text.replace(/\r/g, "").replace(/^WEBVTT[^\n]*\n/, "").replace(/^NOTE[^\n]*\n/gm, "").replace(/^\d+[ \t]*\n/gm, "");
      out += t.trim() + "\n";
    }
    const f = `tools/_fm_${code}_merged.vtt`;
    fs.writeFileSync(f, out);
    console.log(`  VTT fusionado: ${n}/${segs.length} segmentos, ${out.length} chars -> ${f}`);
    console.log("  muestra:", JSON.stringify(out.slice(0, 400)));
  }
}