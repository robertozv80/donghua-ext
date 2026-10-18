// Triage rápido de servidor: para cada serie, descubre el capítulo y los ids de servidor presentes.
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

const slugs = [
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

for (const slug of slugs) {
  const doc = await req(MAIN + slug);
  const firstEp = doc.text.match(/href="([^"]*\/ver\/[^"]+)"/)?.[1]
    ?? doc.text.match(/href='([^']*\/ver\/[^']*)'/)?.[1]
    ?? MAIN + "/" + (doc.text.match(/([a-zA-Z0-9-]+\/ver\/[a-zA-Z0-9-]+)/)?.[1] || "");
  if (!firstEp || !firstEp.includes("/ver/")) {
    console.log(`${slug}: NO EP (page ${doc.status})`);
    continue;
  }
  const epDoc = await req(firstEp);
  const ids = {
    fm: epDoc.text.match(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1],
    vg: epDoc.text.match(/vgembed\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1],
    sw: epDoc.text.match(/embedwish\.com\/[ev]\/([a-zA-Z0-9]+)/)?.[1],
    vh: epDoc.text.match(/vidhidepro\.com\/[ve]\/([a-zA-Z0-9]+)/)?.[1],
    asura: epDoc.text.match(/redirector\.php\?slug=([A-Za-z0-9+/=]+)/)?.[1],
    tamamo: [...epDoc.text.matchAll(/api_donghua\.php\?slug=([A-Za-z0-9+/=]+)/g)].map((m) => m[1]).at(0),
  };
  const present = Object.entries(ids).filter(([, v]) => v).map(([k]) => k);
  console.log(`${slug}#${firstEp.split("/").pop()} -> ${present.length ? present.join(",") : "NONE"}`);
}
