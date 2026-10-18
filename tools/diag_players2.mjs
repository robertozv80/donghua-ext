const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = (u, ref) => fetch(u, { headers: { "User-Agent": UA, ...(ref ? { Referer: ref } : {}) } }).then((r) => r.text());

// 1) shrounding ep1 (el caso del usuario)
console.log("== shrounding-the-heavens-episode-1 mirrors");
const ep = await get("https://animexin.dev/shrounding-the-heavens-episode-1-subtitle-indonesia-english/");
const opts = [...ep.matchAll(/<option[^>]*value="([^"]*)"[^>]*>([^<]*)<\/option>/g)];
console.log("   opciones:", opts.length);
for (const o of opts) {
  try {
    const dec = Buffer.from(o[1].trim(), "base64").toString("utf8");
    const src = (dec.match(/src=["']([^"']+)["']/) || [])[1] || "(sin src)";
    console.log(`   [${o[2].trim()}] -> ${src.slice(0, 130)}`);
  } catch (e) {
    console.log(`   [${o[2].trim()}] DECODE ERR ${e.message}`);
  }
}

// 2) LmAnime /v/9/ y /v/10/ (Español y Español Alternative)
for (const v of ["v/9/", "v/10/", "v/11/", "v/12/"]) {
  const u = `https://lmanime.com/glorious-revenge-of-ye-feng-episode-01/${v}`;
  const page = await get(u, "https://lmanime.com/glorious-revenge-of-ye-feng-episode-01/");
  const ifr = [...page.matchAll(/<iframe[^>]+src="([^"]+)"[^>]*>/g)].map((m) => m[1]);
  const title = (page.match(/<title>([^<]*)<\/title>/) || [])[1] || "?";
  console.log(`\n== LmAnime ${v} [${title.slice(0, 40)}] iframes:`, ifr.length ? ifr.map((s) => s.slice(0, 110)) : "(ninguno)");
}

// 3) Lucifer: DM metadata + Rumble API
console.log("\n== Lucifer DM k4ni3IdBNxNeHQL4zCW");
try {
  const dmUrl = "https://www.dailymotion.com/player/metadata/video/k4ni3IdBNxNeHQL4zCW?embedder=" + encodeURIComponent("https://luciferdonghua.org/") + "&integration=inline";
  const dm = await get(dmUrl, "https://luciferdonghua.org/");
  const j = JSON.parse(dm);
  console.log("   title:", j.title, "| error:", j.error ?? "-", "| qualities:", j.qualities ? Object.keys(j.qualities).join(",") : "NO", "| subs:", j.subtitles ? Object.keys(j.subtitles.data || {}).join(",") : "NO");
} catch (e) { console.log("   ERR:", e.message); }

console.log("== Lucifer Rumble");
for (const vid of ["v7e85ru", "v7e85ls"]) {
  const embed = `https://rumble.com/embed/${vid}/?pub=4qfz5q`;
  const api = "https://rumble.com/embedJS/u3/?request=video&v=" + vid + "&embed=" + encodeURIComponent(embed);
  try {
    const r = await fetch(api, { headers: { "User-Agent": UA, Referer: "https://rumble.com/" } });
    const t = await r.text();
    let j = null; try { j = JSON.parse(t); } catch {}
    const mp4s = j ? Object.keys(j).filter((k) => /^\d+$/.test(k)) : [];
    console.log(`   ${vid}: HTTP ${r.status} len=${t.length} title=${j?.title ?? "?"} mp4s=${mp4s.join("/")} hls=${j?.hls ? "SI(" + j.hls.url?.slice(0, 60) + ")" : "NO"}`);
  } catch (e) { console.log(`   ${vid} ERR:`, e.message); }
}
