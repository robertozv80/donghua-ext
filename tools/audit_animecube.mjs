import crypto from "crypto";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const BASE = "https://animecube.live";
const hex = () => crypto.randomBytes(16).toString("hex");
function decrypt(b64, keyMaterial) {
  try {
    const data = Buffer.from(b64, "base64");
    const key = crypto.createHash("sha256").update(keyMaterial, "utf8").digest();
    const iv = data.subarray(0, 12);
    const d = new crypto.Decipheriv("aes-256-gcm", key, iv);
    d.setAuthTag(data.subarray(-16));
    return Buffer.concat([d.update(data.subarray(12, data.length - 16)), d.final()]).toString("utf8");
  } catch (e) { return null; }
}
(async () => {
  // registry
  let reg = null, etag = null;
  {
    const r = await fetch(`${BASE}/api/anime-sources-versions`, { headers: { "User-Agent": UA, Accept: "application/json" } });
    const body = await r.text();
    if (body.includes("\"bySeason\"")) reg = body;
    console.log("registry plain?", typeof reg, r.status, "len", body.length, "| sample:", body.slice(0, 80));
    if (!reg) {
      const obf = hex();
      const r2 = await fetch(`${BASE}/api/anime-sources-versions`, { headers: { "User-Agent": UA, Accept: "application/json", "X-Obf": obf } });
      const b2 = await r2.text();
      etag = r2.headers.get("x-registry-etag");
      console.log("encrypted:", r2.status, "etag:", etag, "len", b2.length, "sample:", b2.slice(0, 100));
      const d = b2.split("\"d\":\"")[1]?.split("\"")[0];
      reg = decrypt(d, `${obf}|${etag}`);
      console.log("decrypted len:", reg ? reg.length : null, reg ? reg.slice(0, 150) : null);
    }
  }
  const regRaw = reg || "";
  // list slugs in registry
  const slugs = [...new Set([...regRaw.matchAll(/\"([a-z0-9-]+)\":\s*\{\s*\"primary-/g)].map(m => m[1]))];
  console.log("registry slugs (" + slugs.length + "):", slugs.slice(0, 50).join(", "));
  // check target slugs
  const targets = ["a-good-day-to-ascend", "beyond-time-s-gaze"];
  for (const t of targets) {
    const i = regRaw.indexOf(t);
    console.log(`-- ${t}: in registry? ${i >= 0}`);
    if (i >= 0) console.log("   ctx:", regRaw.slice(Math.max(0, i - 60), i + 300));
  }
})();
