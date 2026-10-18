const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const url = "https://ok.ru/videoembed/4416786401831";
const ref = "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/";
const r = await fetch(url, { headers: { "User-Agent": UA, Referer: ref } });
const html = await r.text();
console.log("status", r.status, "len", html.length);
console.log("has data-options:", html.includes("data-options"));
const raw = /data-options="([^"]+)"/.exec(html)?.[1];
if (!raw) { console.log("ctx:", html.slice(0, 300).replace(/\s+/g, " ")); process.exit(0); }
const dec = raw.replace(/&quot;/g, '"').split("\\/").join("/").replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
console.log("options len", dec.length);
const hls = [...dec.matchAll(/"(?:[a-zA-Z]*ManifestUrl|ondemandHls)":"([^"]+)"/g)].map((m) => m[1]);
console.log("hls candidates:", hls.length);
for (const h of hls.slice(0, 4)) {
  try {
    const t = await (await fetch(h, { headers: { "User-Agent": UA, Referer: url } })).text();
    console.log("   ", h.slice(0, 100), "-> m3u8:", t.trimStart().startsWith("#EXTM3U"));
  } catch (e) { console.log("   ", h.slice(0, 100), "ERR", e.message); }
}
const prog = [...dec.matchAll(/"name":"(mobile|lowest|low|sd|hd|full|super)","url":"([^"]+)"/g)];
console.log("progressive:", prog.length, prog.slice(0, 3).map((m) => m[1] + "=" + m[2].slice(0, 80)));
