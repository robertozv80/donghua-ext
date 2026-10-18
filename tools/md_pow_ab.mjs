// Prueba A/B del PoW de bysekoze: ¿es SHA-256 real o el hash custom?
import crypto from "node:crypto";

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const code = process.argv[2] || "7q7xqhtu6lve";
const H = {
  "User-Agent": UA, "Content-Type": "application/json", Accept: "application/json",
  Origin: "https://www.mundodonghua.com", Referer: "https://www.mundodonghua.com/",
  "X-Embed-Origin": "https://www.mundodonghua.com", "X-Embed-Referer": "https://www.mundodonghua.com/",
  "X-Embed-Parent": "https://www.mundodonghua.com/",
};
const api = (p, body, extra) => fetch(`https://bysekoze.com/api/videos/${code}${p}`, {
  method: "POST", headers: { ...H, ...(extra || {}) }, body: JSON.stringify(body),
}).then((r) => r.json());

const cap = await api("/embed/captcha", {});
console.log("captcha:", JSON.stringify(cap).slice(0, 240));
const nonce = cap.pow_nonce, diff = cap.pow_difficulty, powToken = cap.pow_token;

const lzb = (buf) => { let t = 0; for (const b of buf) { if (b === 0) { t += 8; continue; } t += Math.clz32(b) - 24; break; } return t; };

let r = null;
const trySolution = async (label, solution, bits) => {
  const v = await api("/embed/captcha/verify", { pow_token: powToken, solution: String(solution) });
  console.log(`${label}: solution=${solution} bits=${bits} -> ${JSON.stringify(v).slice(0, 200)}`);
  if (v.token) r = v.token;
  return !!v.token;
};

if (!r) {
  let c = 0;
  const t0 = Date.now();
  for (;;) {
    const d = crypto.createHash("sha256").update(nonce + ":" + c).digest();
    if (lzb(d) >= diff) break;
    c++;
  }
  const bits = lzb(crypto.createHash("sha256").update(nonce + ":" + c).digest());
  console.log(`A encontrado en ${c} iter / ${Date.now() - t0}ms`);
  await trySolution("A sha256(utf8)", c, bits);
}
if (!r) {
  let c = 0;
  for (;;) { if (/^0{4,}/.test(crypto.createHash("sha256").update(nonce + ":" + c).digest("hex"))) break; c++; }
  const hex = crypto.createHash("sha256").update(nonce + ":" + c).digest("hex");
  await trySolution("B hex 4 ceros", c, hex.slice(0, 8));
}
if (!r) {
  // C: sha256 deBuffer(nonce) + bytes del contador
  const nb = Buffer.from(nonce);
  let c = 0;
  for (;;) {
    const h = crypto.createHash("sha256").update(nb).update(Buffer.from([(c >> 24) & 255, (c >> 16) & 255, (c >> 8) & 255, c & 255])).digest();
    if (lzb(h) >= diff) break;
    c++;
  }
  await trySolution("C sha256(nonce||be32)", c, lzb(crypto.createHash("sha256").update(nb).update(Buffer.from([0, 0, 0, c])).digest()));
}
if (!r) {
  // D: sha256(nonce + String(c)) sin separador
  let c = 0;
  for (;;) { if (lzb(crypto.createHash("sha256").update(nonce + String(c)).digest()) >= diff) break; c++; }
  await trySolution("D sha256(nonce+c)", c, lzb(crypto.createHash("sha256").update(nonce + String(c)).digest()));
}

if (r) {
  const fp = { token: r, viewer_id: crypto.randomUUID(), device_id: crypto.randomUUID(), confidence: 0.9 };
  const pb = await api("/embed/playback", { fingerprint: fp }, { "X-Captcha-Token": r });
  console.log("playback keys:", Object.keys(pb || {}));
  const enc = pb.playback;
  const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");
  const kp = enc.key_parts || [];
  const verN = typeof enc.version === "number" ? enc.version : parseInt(String(enc.version).match(/\d+/)?.[0] || "0", 10);
  const sel = [];
  for (const i of [verN, 31 - verN]) if (i >= 1 && i <= kp.length && kp[i - 1]) sel.push(kp[i - 1]);
  if (!sel.length) sel.push(...kp);
  const key = Buffer.concat(sel.map(b64u));
  console.log("keylen", key.length, "verN", verN, "kp.len", kp.length, "iv.len", b64u(enc.iv).length);
  try {
    const d = crypto.createDecipheriv("aes-256-gcm", key, b64u(enc.iv));
    const plain = Buffer.concat([d.update(b64u(enc.payload)), d.final()]).toString("utf8");
    const j = JSON.parse(plain);
    console.log("=== JSON DESCIFRADO ===");
    console.log(JSON.stringify(j, null, 1).slice(0, 4000));
  } catch (e) { console.log("descifrado fallo:", e.message); }
}