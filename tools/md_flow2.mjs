// Flujo bysekoze completo usando el PoW LITERAL del bundle, con volcado del
// playback cifrado y prueba de estrategias de clave.
import fs from "node:fs";
import crypto from "node:crypto";

const src = fs.readFileSync("tools/_byse_pow.js", "utf8");
const s0 = src.indexOf("be=512,lt=be-1"), s1 = src.indexOf("export{", s0);
const code = src.slice(s0, s1);
const { Er, wr, gr } = new Function(code + "\nreturn { Er, wr, gr };")();

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const H = {
  "User-Agent": UA, "Content-Type": "application/json", Accept: "application/json",
  Origin: "https://www.mundodonghua.com", Referer: "https://www.mundodonghua.com/",
  "X-Embed-Origin": "https://www.mundodonghua.com", "X-Embed-Referer": "https://www.mundodonghua.com/",
  "X-Embed-Parent": "https://www.mundodonghua.com/",
};
const code5 = process.argv[2];
const api = (p, body, extra) => fetch(`https://bysekoze.com/api/videos/${code5}${p}`, {
  method: "POST", headers: { ...H, ...(extra || {}) }, body: JSON.stringify(body),
}).then((r) => r.json());

const cap = await api("/embed/captcha", {});
console.log("captcha:", JSON.stringify(cap).slice(0, 150));
const t0 = Date.now();
const sol = await Er(cap.pow_nonce, cap.pow_difficulty);
console.log("PoW:", sol, "en", Date.now() - t0, "ms, bits =", wr(gr(new TextEncoder().encode(cap.pow_nonce + ":" + sol))));
const v = await api("/embed/captcha/verify", { pow_token: cap.pow_token, solution: sol });
console.log("verify:", JSON.stringify(v).slice(0, 120));
if (!v.token) process.exit(1);

const fp = { token: v.token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
const pb = await api("/embed/playback", { fingerprint: fp }, { "X-Captcha-Token": v.token });
const enc = pb.playback;
fs.writeFileSync(`tools/_fm_${code5}_enc.json`, JSON.stringify(enc, null, 1));
console.log("playback:", JSON.stringify({ algorithm: enc.algorithm, version: enc.version, iv_len: String(enc.iv || "").length, payload_len: String(enc.payload || "").length, key_parts: (enc.key_parts || []).length }));
console.log("otros campos del playback:", Object.keys(enc).join(","));

const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");
const kp = enc.key_parts || [];
const ver = String(enc.version).trim();
const idx = ver >= "1" && ver <= "20" ? [Number(ver), 31 - Number(ver)].filter((i) => i >= 1 && i <= kp.length) : [];
const strategies = {
  "ws(idx)": idx.map((i) => kp[i - 1]),
  "ws(todo)": kp,
  "ws(idx) rev": idx.map((i) => kp[i - 1]).reverse(),
};
const iv = b64u(enc.iv), payload = b64u(enc.payload);
console.log("iv bytes:", iv.length, "payload bytes:", payload.length, "idx:", idx.join(","));
for (const [name, parts] of Object.entries(strategies)) {
  if (!parts.length) continue;
  const key = Buffer.concat(parts.map(b64u));
  try {
    const d = crypto.createDecipheriv("aes-256-gcm", key, iv);
    const plain = Buffer.concat([d.update(payload), d.final()]).toString("utf8");
    fs.writeFileSync(`tools/_fm_${code5}.json`, plain);
    console.log(`\n*** ${name} OK (key ${key.length}B) -> tools/_fm_${code5}.json`);
    console.log(JSON.stringify(JSON.parse(plain), null, 1).slice(0, 3000));
    break;
  } catch (e) { console.log(`${name} fallo (key ${key.length}B): ${e.message}`); }
}