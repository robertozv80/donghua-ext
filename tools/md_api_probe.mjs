// Sonda de endpoints de la API de bysekoze buscando subtítulos / captions.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const code = process.argv[2] || "kapr9js9ixdp";
const H = {
  "User-Agent": UA, Accept: "application/json",
  Origin: "https://www.mundodonghua.com", Referer: "https://www.mundodonghua.com/",
  "X-Embed-Origin": "https://www.mundodonghua.com", "X-Embed-Referer": "https://www.mundodonghua.com/",
};
const paths = [
  ["GET", `/api/videos/${code}`], ["GET", `/api/videos/${code}/tracks`], ["GET", `/api/videos/${code}/captions`],
  ["GET", `/api/videos/${code}/subtitles`], ["GET", `/api/videos/${code}/embed`], ["GET", `/api/videos/${code}/embed/config`],
  ["GET", `/api/videos/${code}/embed/sources`], ["GET", `/api/videos/${code}/embed/tracks`],
  ["POST", `/api/videos/${code}/embed/tracks`], ["GET", `/api/videos/${code}/media`],
  ["GET", `/api/videos/${code}/embed/captions`], ["GET", `/api/search?q=${code}`],
  ["GET", `/api/config`], ["GET", `/api/videos/${code}/embed/playback/options`],
];
for (const [m, p] of paths) {
  try {
    const r = await fetch(`https://bysekoze.com${p}`, { method: m, headers: { ...H, ...(m === "POST" ? { "Content-Type": "application/json" } : {}) }, body: m === "POST" ? "{}" : undefined });
    const t = await r.text();
    console.log(`${m} ${r.status} ${p}\n   ${t.slice(0, 260).replace(/\s+/g, " ")}`);
  } catch (e) { console.log(`${m} ERR ${p}: ${e.message}`); }
}