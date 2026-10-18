// Ejecuta LITERALMENTE las funciones del bundle de bysekoze (extraidas del propio
// archivo) para resolver el PoW -> descarta cualquier error de transcripcion.
import fs from "node:fs";
import crypto from "node:crypto";

const src = fs.readFileSync("tools/_byse_pow.js", "utf8");
const grab = (name) => {
  const re = new RegExp(`(?:function ${name}\\([^)]*\\)\\{|const ${name}=[^;]+;|let ${name}=[^;]+;|var ${name}=[^;]+;)`);
  let i = src.search(re);
  if (i < 0) throw new Error("no encontrada: " + name);
  if (src.slice(i - 6, i) === "async ") i -= 6;
  let depth = 0, started = false, j = i;
  for (; j < src.length; j++) {
    const ch = src[j];
    if (ch === "{") { depth++; started = true; }
    else if (ch === "}") { depth--; if (started && depth === 0) { j++; break; } }
    else if (ch === ";" && !started) { j++; break; }
  }
  return src.slice(i, j);
};
const pieces = ["ye", "gr", "wr", "yr", "Er"].map(grab);
pieces.push("const pr=()=>new Promise(t=>{setTimeout(t,0)});");
console.log("--- extraidas ---");
for (const p of pieces) console.log(p.slice(0, 120).replace(/\s+/g, " ") + " ...");
const factory = new Function(`return (async () => {
  const be=512, lt=be-1, dr=2, lr=2654435761, hr=2246822519;
  const re=(t,e)=>(t<<e|t>>>32-e)>>>0;
  const ht=(t,e)=>Math.imul(t,e)>>>0;
  ${pieces.join("\n")}
  return { Er, gr, wr };
})();`);
const { Er, gr, wr } = await factory();

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
console.log("captcha:", JSON.stringify(cap).slice(0, 180));
const t0 = Date.now();
const sol = await Er(cap.pow_nonce, cap.pow_difficulty);
console.log("solución (código del bundle):", sol, "en", Date.now() - t0, "ms");
// comprobacion: mismo hash que el de mi transcripcion manual
console.log("wr(gr(nonce:sol)) =", wr(gr(new TextEncoder().encode(cap.pow_nonce + ":" + sol))));
const v = await api("/embed/captcha/verify", { pow_token: cap.pow_token, solution: sol });
console.log("verify:", JSON.stringify(v).slice(0, 300));

if (v.token) {
  const fp = { token: v.token, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
  const pb = await api("/embed/playback", { fingerprint: fp }, { "X-Captcha-Token": v.token });
  console.log("playback keys:", Object.keys(pb || {}));
  const enc = pb.playback;
  const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");
  const kp = enc.key_parts || [];
  const verN = typeof enc.version === "number" ? enc.version : parseInt(String(enc.version).match(/\d+/)?.[0] || "0", 10);
  const sel = [];
  for (const i of [verN, 31 - verN]) if (i >= 1 && i <= kp.length && kp[i - 1]) sel.push(kp[i - 1]);
  if (!sel.length) sel.push(...kp);
  const key = Buffer.concat(sel.map(b64u));
  try {
    const d = crypto.createDecipheriv("aes-256-gcm", key, b64u(enc.iv));
    const plain = Buffer.concat([d.update(b64u(enc.payload)), d.final()]).toString("utf8");
    fs.writeFileSync(`tools/_fm_${code}.json`, plain);
    const j = JSON.parse(plain);
    console.log("=== JSON DESCIFRADO (claves) ===", Object.keys(j));
    console.log(JSON.stringify(j, null, 1).slice(0, 3500));
  } catch (e) { console.log("descifrado fallo:", e.message, "keylen", key.length, "verN", verN); }
}