const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
// A Mortal's Journey ep195 video (failing device playback per log) and a good one (Wu Dong Qian Kun)
const vids = [
  { id: "k2Q3TqOldOCVSaLmtNM", embedder: "https://animexin.dev" },
  { id: "k7ETrtMaFKN7uaC9lew", embedder: "https://animexin.dev" },
  { id: "k33DFNIat57NWELmoGm", embedder: "https://luciferdonghua.org" },
];
for (const { id, embedder } of vids) {
  const mu = `https://www.dailymotion.com/player/metadata/video/${id}?embedder=${encodeURIComponent(embedder)}&integration=inline`;
  const r = await fetch(mu, { headers: { "User-Agent": UA, Referer: `https://www.dailymotion.com/embed/video/${id}` } });
  const j = JSON.parse(await r.text());
  console.log("==", id, "keys:", Object.keys(j).join(",").slice(0, 200));
  if (j.error) { console.log("  error:", JSON.stringify(j.error)); continue; }
  const q = j.qualities || {};
  for (const k in q) q[k].forEach((x, i) => console.log(`  q${k}[${i}] ${x.type} ${String(x.url).slice(0, 130)}`));
  const auto = (q.auto || []).find(x => x.type.includes("mpegURL"));
  if (auto) {
    const mr = await fetch(auto.url, { headers: { "User-Agent": UA, Referer: `https://www.dailymotion.com/embed/video/${id}` } });
    const txt = await mr.text();
    console.log("  MASTER status", mr.status, "len", txt.length);
    const lines = txt.split("\n").filter(l => l.trim());
    for (const l of lines.slice(0, 60)) console.log("   |", l.slice(0, 160));
  }
}
