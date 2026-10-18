import fs from "node:fs";
import crypto from "node:crypto";
const enc = JSON.parse(fs.readFileSync("tools/_fm_7q7xqhtu6lve_enc.json", "utf8"));
const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");
const kp = enc.key_parts, iv = b64u(enc.iv), payload = b64u(enc.payload);
const key = Buffer.concat([b64u(kp[11]), b64u(kp[18])]);
try {
  const d = crypto.createDecipheriv("aes-256-gcm", key, iv);
  const p = Buffer.concat([d.update(payload), d.final()]).toString("utf8");
  console.log("AUTO-TAG OK:", p.slice(0, 200));
} catch (e) { console.log("AUTO-TAG fallo:", e.message); }
const tag = payload.subarray(payload.length - 16), body = payload.subarray(0, payload.length - 16);
try {
  const d = crypto.createDecipheriv("aes-256-gcm", key, iv);
  d.setAuthTag(tag);
  const p = Buffer.concat([d.update(body), d.final()]).toString("utf8");
  console.log("SETTAG OK:", p.slice(0, 200));
} catch (e) { console.log("SETTAG fallo:", e.message); }
console.log("payload bytes", payload.length, "body", body.length, "tag", tag.length);
