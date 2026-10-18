// Sonda del playlist de subtítulos HLS de bysekoze para rgcgmsu5j63y.
import https from "node:https";
import http from "node:http";

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

function get(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith("https") ? https : http;
    const r = mod.get(url, { headers: { "User-Agent": UA, ...headers } }, (res) => {
      let d = [];
      res.on("data", (c) => d.push(c));
      res.on("end", () => resolve({ code: res.statusCode, ct: res.headers["content-type"], body: Buffer.concat(d).toString("utf8") }));
    });
    r.on("error", reject);
    r.setTimeout(20000, () => { r.destroy(new Error("timeout")); });
  });
}

// 1) API captcha+playback ya funciona en md_byse.mjs; aquí hardcodeamos el master conocido.
// Obtener el master primero desde el flujo de md_byse.mjs es complejo; usamos la URL del probe anterior.
// En su lugar, reproducimos el flujo mínimo: captcha -> pow -> verify -> playback -> decrypt.
// Para eso importamos los pasos del bundle... más simple: leer el master de la sonda previa.
// La sonda md_subs3.mjs ya extrajo seg URLs; reconstruimos el index.m3u8 a partir de seg-1.

const SEG1 = process.argv[2] || "https://edge1-vienna-sprintcdn.owphbf24.com/hls2/05/06343/lang/eng/rgcgmsu5j63y_eng/seg-1.vtt?t=reA1pWgDNriyd7i1FXiQyHz7748HnyoOnMIbAJ6-OQY&s=1791232152&e=10800&f=31717923&srv=1050&asn=27947&sp=5500&p=0&fr=rgcgmsu5j63y";

const base = SEG1.slice(0, SEG1.lastIndexOf("/") + 1);
const qs = SEG1.slice(SEG1.indexOf("?") + 1);
const code = "rgcgmsu5j63y";

const indexUrl = `${base}index.m3u8?${qs}`;
const master = await get(indexUrl);
console.log("=== index.m3u8:", master.code, master.ct);
console.log(master.body.slice(0, 2000));

// pruebas de nombres alternativos en el mismo path
for (const name of ["all.vtt", "combined.vtt", "merge.vtt", "seg-all.vtt", "seg-0.vtt", "seg-1-35.vtt", "seg_1.vtt", "subtitle_eng.vtt", "eng.vtt", "rgcgmsu5j63y_eng.vtt", "index.vtt"]) {
  const r = await get(`${base}${name}?${qs}`).catch((e) => ({ code: "ERR", body: String(e) }));
  const interesting = r.code === 200 && (r.body || "").includes("WEBVTT");
  console.log(`${name}: ${r.code}${interesting ? "  <-- VTT OK!" : ""}`);
  if (interesting) console.log(r.body.slice(0, 200));
}
