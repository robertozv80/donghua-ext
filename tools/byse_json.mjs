const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
const r = await fetch("https://byselapuix.com/api/videos/ohmv3woxtd8d?embed=1", {
  headers: { "User-Agent": UA, Referer: "https://byselapuix.com/e/ohmv3woxtd8d", Accept: "application/json" },
});
const j = await r.json();
console.log("claves raiz:", Object.keys(j).join(","));
console.log("playback keys:", Object.keys(j.playback).join(","));
for (const k of Object.keys(j.playback)) {
  const v = j.playback[k];
  console.log(`  ${k} =`, typeof v === "string" && v.length > 90 ? v.slice(0, 90) + `…(${v.length})` : v);
}
