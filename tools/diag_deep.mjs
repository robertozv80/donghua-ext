const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = (u, ref) => fetch(u, { headers: { "User-Agent": UA, ...(ref ? { Referer: ref } : {}) } }).then((r) => r.text());

// 1) AnimeXin ep1: encontrar el mecanismo del player
const ep = await get("https://animexin.dev/shrouding-the-heavens-episode-1-subtitle-indonesia-english/");
console.log("== AnimeXin ep1: iframe crudo");
const i = ep.indexOf("<iframe");
console.log(ep.slice(Math.max(0, i - 400), i + 600).replace(/\s+/g, " "));
console.log("\n   busquedas:");
for (const pat of ["admin-ajax", "ajax", "getJSON", "playeroptions", "ts_player", "data-player", "hotstar", "embed", "base64", "atob", "window.btoa", "atob(", "getElementById"]) {
  const n = (ep.match(new RegExp(pat, "gi")) || []).length;
  if (n) console.log(`   ${pat}: ${n}x`);
}
// contexto del primer admin-ajax
const aj = ep.indexOf("admin-ajax");
if (aj >= 0) console.log("   ctx admin-ajax:", ep.slice(Math.max(0, aj - 500), aj + 300).replace(/\s+/g, " "));

// 2) LmAnime /v/9/
console.log("\n== LmAnime /v/9/ (Español)");
const v9 = await get("https://lmanime.com/glorious-revenge-of-ye-feng-episode-01/v/9/", "https://lmanime.com/glorious-revenge-of-ye-feng-episode-01/");
console.log("   len:", v9.length);
const ifr = [...v9.matchAll(/<iframe[^>]*>/g)].map((m) => m[0].replace(/\s+/g, " ").slice(0, 300));
console.log("   iframes:", ifr.length); ifr.forEach((f) => console.log("     ", f));
const pembed = v9.indexOf("pembed");
if (pembed >= 0) console.log("   ctx pembed:", v9.slice(Math.max(0, pembed - 200), pembed + 500).replace(/\s+/g, " "));

// 3) Lucifer: DM metadata + Rumble API
console.log("\n== Lucifer DM k4ni3IdBNxNeHQL4zCW");
const dmUrl = "https://www.dailymotion.com/player/metadata/video/k4ni3IdBNxNeHQL4zCW?embedder=" + encodeURIComponent("https://luciferdonghua.org/") + "&integration=inline";
try {
  const dm = await get(dmUrl, "https://luciferdonghua.org/");
  const j = JSON.parse(dm);
  console.log("   title:", j.title, "| error:", j.error, "| qualities keys:", j.qualities ? Object.keys(j.qualities).length : "NO");
  console.log("   subs:", j.subtitles ? Object.keys(j.subtitles.data || {}) : "NO");
} catch (e) { console.log("   DM ERR:", e.message); }

console.log("\n== Lucifer Rumble v7e85ru / v7e85ls");
for (const vid of ["v7e85ru", "v7e85ls"]) {
  const embed = `https://rumble.com/embed/${vid}/?pub=4qfz5q`;
  const api = "https://rumble.com/embedJS/u3/?request=video&v=" + vid + "&embed=" + encodeURIComponent(embed);
  try {
    const r = await fetch(api, { headers: { "User-Agent": UA, Referer: "https://rumble.com/" } });
    const t = await r.text();
    let j = null; try { j = JSON.parse(t); } catch {}
    console.log(`   ${vid}: HTTP ${r.status} len=${t.length} title=${j?.title ?? "?"} mp4s=${j ? Object.keys(j).filter(k => /^\d+$/.test(k)).length : "?"} hls=${j?.hls ? "SI" : "NO"}`);
    if (r.status !== 200 || !j) console.log("     head:", t.slice(0, 200));
  } catch (e) { console.log(`   ${vid} ERR:`, e.message); }
}
