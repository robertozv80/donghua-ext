import fs from "node:fs";
import crypto from "node:crypto";
const enc = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const b64u = (s) => Buffer.from(String(s).trim().replace(/-/g, "+").replace(/_/g, "/"), "base64");
const kp = enc.key_parts, iv = b64u(enc.iv), payload = b64u(enc.payload);
console.log("kp", kp.length, "iv", iv.length, "payload", payload.length, "version", enc.version);
console.log("longitudes de cada parte:", kp.map((p) => b64u(p).length).join(","));
let found = null;
for (let i = 0; i < kp.length && !found; i++) {
  for (let j = 0; j < kp.length && !found; j++) {
    for (const order of [[i, j], [j, i]]) {
      const key = Buffer.concat(order.map((k) => b64u(kp[k])));
      if (![16, 24, 32].includes(key.length)) continue;
      try {
        const d = crypto.createDecipheriv("aes-256-gcm", key, iv);
        const plain = Buffer.concat([d.update(payload), d.final()]).toString("utf8");
        found = { order, key: key.toString("hex"), plain };
        break;
      } catch {}
    }
  }
}
if (found) { console.log("CLAVE ENCONTRADA con indices", found.order, "\n", found.plain.slice(0, 2500)); fs.writeFileSync(process.argv[2].replace("_enc.json", ".json"), found.plain); }
else console.log("ningun par de partes funciona");
