import crypto from "crypto";

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const BASE = "https://animecube.live";
const randHex = () => crypto.randomBytes(16).toString("hex");
const sha256 = (s) => crypto.createHash("sha256").update(s, "utf8").digest();

function decrypt(b64, keyMaterial) {
  try {
    const data = Buffer.from(b64, "base64");
    const key = sha256(keyMaterial);
    const iv = data.subarray(0, 12);
    const ct = data.subarray(12);
    const tag = ct.subarray(ct.length - 16);
    const body = ct.subarray(0, ct.length - 16);
    const d = crypto.createDecipheriv("aes-256-gcm", key, iv);
    d.setAuthTag(tag);
    return Buffer.concat([d.update(body), d.final()]).toString("utf8");
  } catch (e) {
    return null;
  }
}

async function get(u, h = {}) {
  const r = await fetch(u, { headers: { "User-Agent": UA, ...h } });
  return { status: r.status, text: await r.text(), headers: r.headers };
}

// --- registry ---
const base = { "User-Agent": UA, Accept: "application/json" };
const r1 = await get(`${BASE}/api/anime-sources-versions`, base);
let body = r1.text;
console.log("registry plain? status", r1.status, "len", body.length, "has bySeason", body.includes('"bySeason"'), "has \"d\"", body.includes('"d":"'), "etag-hdr", r1.headers.get("x-registry-etag"));
if (!body.includes('"bySeason"')) {
  const obf = randHex();
  const r2 = await get(`${BASE}/api/anime-sources-versions`, { ...base, "X-Obf": obf });
  const etag = r2.headers.get("x-registry-etag");
  console.log("  obf retry: etag", etag, "len", r2.text.length, "head", r2.text.slice(0, 120));
  const d = r2.text.split('"d":"')[1]?.split('"')[0];
  if (d && etag) {
    const dec = decrypt(d, `${obf}|${etag}`);
    console.log("  decrypted?", dec ? dec.slice(0, 200) : "FAILED");
    if (dec) body = dec;
  }
}

// --- pick slug/season from argv ---
const epId = process.argv[2] || "a-good-day-to-ascend-tab-1-ep-1";
const m = /^(.+)-(tab-[\w-]+)-ep-(\d+(?:-\d+)?)$/.exec(epId);
const slug = m[1], seId = m[2];
console.log("slug", slug, "seId", seId);

// parse registry
const bySeason = body.slice(body.indexOf('"bySeason"'));
const re = new RegExp(`"${slug}":\\s*\\{`, "g");
const idx = body.indexOf(`"${slug}":{`);
console.log("slug in registry:", idx >= 0);
let primaryTabId = null, version = null;
if (idx >= 0) {
  const objStart = body.indexOf("{", idx + slug.length + 2);
  // find primary- in the following 800 chars
  const seg = body.slice(idx, idx + 600);
  console.log("  seg:", seg.slice(0, 400));
  const pm = /"(primary-[\w-]+)":\s*\{/.exec(seg);
  const vm = new RegExp(`"${seId}":"([^"]+)"`).exec(seg);
  primaryTabId = pm?.[1] || null;
  version = vm?.[1] || null;
}
console.log("primaryTabId", primaryTabId, "version", version);

// --- sources ---
if (primaryTabId && version) {
  const obf = randHex();
  const u = `${BASE}/api/anime/${slug}/episode/${epId}/sources?v=${encodeURIComponent(version)}&primaryTabId=${encodeURIComponent(primaryTabId)}&seasonId=${encodeURIComponent(seId)}`;
  const rs = await get(u, { "User-Agent": UA, Accept: "application/json", "X-Obf": obf });
  console.log("sources status", rs.status, "len", rs.text.length);
  let payload = rs.text;
  if (!payload.includes('"sources"')) {
    const d = payload.split('"d":"')[1]?.split('"')[0];
    const dec = d ? decrypt(d, `${obf}|${version}`) : null;
    console.log("  sources encrypted; decrypted?", dec ? dec.slice(0, 300) : "FAILED");
    payload = dec || "";
  } else {
    console.log("  sources plain head:", payload.slice(0, 400));
  }
  const srcs = [...payload.matchAll(/"platform":"([\w-]+)"\s*,\s*(?:"privateId":"[^"]*"\s*,\s*)?"quality":"([^"]*)"\s*,\s*"videoId":"([\w-]+)"/g)];
  console.log("  parsed sources:", srcs.length);
  srcs.forEach((s) => console.log("    ", s[1], s[3], s[2]));
}
