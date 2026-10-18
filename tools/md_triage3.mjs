// Triage de servidor: descubre el episodio y lista los IDs de servidor presentes en HTML desempaquetado.
import zlib from "node:zlib";

const MAIN = "https://www.mundodonghua.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

async function req(url, t = 8000) {
  const ctrl = new AbortController();
  const x = setTimeout(() => ctrl.abort(), t);
  try {
    const r = await fetch(url, { headers: { "User-Agent": UA }, signal: ctrl.signal, redirect: "follow" });
    const b = Buffer.from(await r.arrayBuffer());
    const text = (r.headers.get("content-encoding") || "").includes("gzip") && b.length && b[0] === 0x1f ? zlib.gunzipSync(b).toString("utf8") : b.toString("utf8");
    return { status: r.status, text };
  } catch (e) {
    return { status: 0, text: "", err: e.message };
  } finally {
    clearTimeout(x);
  }
}

function pack(code) {
  try {
    if (!code.endsWith(")")) return null;
    const out = eval("(" + code.slice(5, -1) + ")");
    if (typeof out !== "string" || out.includes("function(p,a,c,k,e")) return null;
    return out;
  } catch {
    return null;
  }
}

const targets = [
  "/1st-kiss",
  "/a-paragon-of-wuhun",
  "/a-record-of-a-mortals-journey-to-immortality",
  "/a-record-of-a-mortals-journey-to-immortality-2",
  "/a-record-of-a-mortals-journey-to-immortality-2-sp",
  "/a-record-of-a-mortals-journey-to-immortality-3",
  "/a-record-of-a-mortals-journey-to-immortality-4",
  "/a-record-of-a-mortals-journey-to-immortality-5",
  "/a-record-of-a-mortals-journey-to-immortality-6",
  "/academia-cmfu-el-principe-del-billar",
  "/against-the-gods",
  "/against-the-gods-2",
  "/against-the-sky-supreme",
  "/almanac-master",
];

for (const slug of targets) {
  const r = await req(MAIN + slug);
  const m = r.text.match(/href="([^"]*\/ver\/[^"]+)"/);
  if (!m) {
    console.log(`${slug}: NO EP (status ${r.status})`);
    continue;
  }
  const ep = await req(MAIN + m[1]);
  const packs = [];
  for (const line of ep.text.split("\n")) {
    const m2 = line.match(/eval\(function\(p,a,c,k,e,[^\r\n]+/);
    if (m2) {
      const u = pack(line.slice(m2.index));
      if (u) packs.push(u);
    }
  }
  const hay = ep.text + packs.join("\n");
  const byse = hay.match(/bysekoze\.com\/([ev])\/([a-zA-Z0-9]+)/);
  const vg = hay.match(/vgembed\.com\/([ev])\/([a-zA-Z0-9]+)/);
  const sw = hay.match(/embedwish\.com\/([ev])\/([a-zA-Z0-9]+)/);
  const vh = hay.match(/vidhidepro\.com\/([ve])\/([a-zA-Z0-9]+)/);
  const asura = hay.match(/redirector\.php\?slug=([A-Za-z0-9+/=]+)/);
  const tamamo = hay.match(/api_donghua\.php\?slug=([A-Za-z0-9+/=]+)/);
  const present = [
    byse ? "fm:" + byse[2] : null,
    vg ? "vg:" + vg[2] : null,
    sw ? "sw:" + sw[2] : null,
    vh ? "vh:" + vh[2] : null,
    asura ? "asura:" + asura[1].slice(0, 12) : null,
    tamamo ? "tamamo:" + tamamo[1].slice(0, 12) : null,
  ].filter(Boolean);
  console.log(`${slug}#${m[1].split("/").pop()} -> ${present.length ? present.join(",") : "NONE"}`);
}
