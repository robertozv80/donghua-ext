// Barre varios códigos bysekoze y registra la lista `tracks` (subtítulos) de cada uno.
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
const OUT = "tools/_fm_tracks.txt";
const seen = new Set(fs.existsSync(OUT) ? fs.readFileSync(OUT, "utf8").split("\n").filter(Boolean).map((l) => l.split("|")[0]) : []);
const api = (code, p, body, extra) => fetch(`https://bysekoze.com/api/videos/${code}${p}`, {
  method: "POST", headers: { ...H, ...(extra || {}) }, body: JSON.stringify(body),
}).then((r) => r.json());
const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");

async function flow(code) {
  const cap = await api(code, "/embed/captcha", {});
  if (!cap.pow_nonce) return { error: "captcha " + JSON.stringify(cap).slice(0, 80) };
  const sol = await Er(cap.pow_nonce, cap.pow_difficulty);
  const v = await api(code, "/embed/captcha/verify", { pow_token: cap.pow_token, solution: sol });
  if (!v.token) return { error: "verify " + JSON.stringify(v).slice(0, 80), diff: cap.pow_difficulty, ms: null };
  const fp = { token: v.token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
  const pb = await api(code, "/embed/playback", { fingerprint: fp }, { "X-Captcha-Token": v.token });
  const enc = pb.playback;
  if (!enc) return { error: "sin playback " + JSON.stringify(pb).slice(0, 80) };
  const kp = enc.key_parts, ver = Number(enc.version);
  const key = Buffer.concat([ver, 31 - ver].filter((i) => i >= 1 && i <= kp.length).map((i) => b64u(kp[i - 1])));
  const ct = b64u(enc.payload);
  try {
    const d = (await import("node:crypto")).createDecipheriv("aes-256-gcm", key, b64u(enc.iv));
    d.setAuthTag(ct.subarray(ct.length - 16));
    const dec = JSON.parse(Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]).toString("utf8"));
    return { dec, diff: cap.pow_difficulty };
  } catch (e) { return { error: "descifrado " + e.message }; }
}

const codes = process.argv.slice(2);
for (const code of codes) {
  if (seen.has(code)) { console.log("skip", code); continue; }
  const r = await flow(code);
  if (r.error) { console.log(code, "ERROR:", r.error); fs.appendFileSync(OUT, `${code}|ERROR ${r.error}\n`); seen.add(code); continue; }
  const t = r.dec.tracks || [];
  const line = `${code}|tracks=${t.length}|${JSON.stringify(t).slice(0, 400)}`;
  fs.appendFileSync(OUT, line + "\n");
  seen.add(code);
  console.log(code, `diff=${r.diff}`, `fuentes=${(r.dec.sources || []).length}`, `tracks=${t.length}`);
  if (t.length) console.log("  TRACKS:", JSON.stringify(t, null, 1).slice(0, 1200));
}