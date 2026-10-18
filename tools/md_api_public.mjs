// Barrido rápido por la API pública de bysekoze (sin PoW) para ver `tracks`.
import fs from "node:fs";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const H = { "User-Agent": UA, Accept: "application/json", Origin: "https://www.mundodonghua.com", Referer: "https://www.mundodonghua.com/" };
const codes = fs.readFileSync("tools/_fm_codes.txt", "utf8").split("\n").filter(Boolean);
let conTracks = 0, ok = 0, fail = 0;
const out = [];
for (const code of codes) {
  try {
    const r = await fetch(`https://bysekoze.com/api/videos/${code}`, { headers: H });
    if (!r.ok) { fail++; out.push(`${code} HTTP ${r.status}`); continue; }
    const j = await r.json();
    ok++;
    const t = j.tracks || [];
    const dec = j.playback ? "playback" : "sin playback";
    if (t.length) { conTracks++; out.push(`${code} TRACKS=${t.length} ${JSON.stringify(t)}`); }
    else out.push(`${code} tracks=0 fuentes=${(j.playback ? "?" : "?")} ${dec}`);
    console.log(`${code} ${r.status} tracks=${t.length} ${t.length ? JSON.stringify(t).slice(0, 500) : ""}`);
  } catch (e) { fail++; out.push(`${code} ERR ${e.message}`); console.log(code, "ERR", e.message); }
}
fs.writeFileSync("tools/_fm_api_scan.txt", out.join("\n") + "\n");
console.log(`\nresumen: ok=${ok} fail=${fail} conTracks=${conTracks}`);