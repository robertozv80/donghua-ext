import fs from "node:fs";
import crypto from "node:crypto";
const enc = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");
const kp = enc.key_parts;
console.log("parte[11] raw:", JSON.stringify(kp[11]));
console.log("parte[18] raw:", JSON.stringify(kp[18]));
console.log("parte[11] hex:", b64u(kp[11]).toString("hex"));
console.log("parte[18] hex:", b64u(kp[18]).toString("hex"));
console.log("parte[0]  raw:", JSON.stringify(kp[0]).slice(0, 120));
console.log("iv raw:", JSON.stringify(enc.iv), "hex:", b64u(enc.iv).toString("hex"));
console.log("payload raw[0..80]:", JSON.stringify(String(enc.payload).slice(0, 80)));
// probar con aes-gcm de node pero pasando la etiqueta al final explicita
const iv = b64u(enc.iv), ct = b64u(enc.payload);
console.log("payload bytes:", ct.length);
const keys = {
  "kp11|kp18": Buffer.concat([b64u(kp[11]), b64u(kp[18])]),
  "kp18|kp11": Buffer.concat([b64u(kp[18]), b64u(kp[11])]),
};
for (const [n, key] of Object.entries(keys)) {
  // separando etiqueta
  const tag = ct.subarray(ct.length - 16), body = ct.subarray(0, ct.length - 16);
  const d1 = crypto.createDecipheriv("aes-256-gcm", key, iv); d1.setAuthTag(tag);
  try { const p = Buffer.concat([d1.update(body), d1.final()]).toString("utf8"); console.log(n, "OK:", p.slice(0, 300)); }
  catch (e) { console.log(n, "fallo setAuthTag:", e.message); }
  // 96-bit tag
  const tag12 = ct.subarray(ct.length - 12), body2 = ct.subarray(0, ct.length - 12);
  try { const d2 = crypto.createDecipheriv("aes-256-gcm-96" in crypto ? "aes-256-gcm" : "aes-256-gcm", key, iv); d2.setAuthTag(tag12); const p2 = Buffer.concat([d2.update(body2), d2.final()]).toString("utf8"); console.log(n, "OK tag96:", p2.slice(0, 200)); } catch (e) { console.log(n, "fallo tag96:", e.message); }
}
