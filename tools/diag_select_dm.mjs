const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = (u, ref) => fetch(u, { headers: { "User-Agent": UA, ...(ref ? { Referer: ref } : {}) } }).then((r) => r.text());

// 1) Marcado exacto de <select> en paginas de episodio
const pages = [
  ["lmanime", "https://lmanime.com/glorious-revenge-of-ye-feng-episode-01/"],
  ["animexin", "https://animexin.dev/shrounding-the-heavens-episode-1-subtitle-indonesia-english/"],
  ["lucifer", "https://luciferdonghua.org/supreme-god-emperor-episode-646-english-subtitles/"],
];
for (const [n, u] of pages) {
  const t = await get(u);
  const sel = [...t.matchAll(/<select[^>]*>/gi)].map((m) => m[0]);
  console.log(`${n}: selects=`, sel);
}

// 2) DM metadata de los videos clave
const dms = [
  ["lmanime esp k5kmTfN2UaL96gzvLfz", "k5kmTfN2UaL96gzvLfz", "https://lmanime.com/"],
  ["animexin allsub k1slp8qR9ukY7ez4V3W", "k1slp8qR9ukY7ez4V3W", "https://animexin.dev/"],
];
for (const [n, vid, ref] of dms) {
  try {
    const j = JSON.parse(await get(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(ref)}&integration=inline`, ref));
    console.log(`DM ${n}: title="${j.title}" error=${j.error ?? "-"} qualities=${j.qualities ? Object.keys(j.qualities).join(",") : "NO"} subs=${j.subtitles ? Object.keys(j.subtitles.data || {}).join(",") : "NO"}`);
  } catch (e) {
    console.log(`DM ${n}: ERR ${e.message}`);
  }
}

// 3) Ok.ru del All Sub / Hardsub English de shrounding ep1 y LmAnime v/10
for (const [n, u] of [
  ["ok.ru shrounding EN", "https://ok.ru/videoembed/5408459000372"],
  ["ok.ru lmanime v10", "https://ok.ru/videoembed/6373975853607"],
]) {
  try {
    const t = await get(u, "https://animexin.dev/");
    const has = /data-options="/.test(t);
    const hls = (t.match(/hlsManifestUrl[^,]{0,80}/) || [])[0];
    console.log(`${n}: len=${t.length} data-options=${has} hls="${hls ?? "-"}"`);
  } catch (e) {
    console.log(`${n}: ERR ${e.message}`);
  }
}

// 4) Dood: estructura del embed dooood.com
console.log("\n== Dood dooood.com/e/z3lacan00d6kmr2mcsng8fzfogrcmw0t");
try {
  const t = await get("https://dooood.com/e/z3lacan00d6kmr2mcsng8fzfogrcmw0t", "https://animexin.dev/");
  console.log("   len:", t.length);
  for (const pat of ["pass_md5", "dsplayer", "eval(function", "md5", "makeloop", "source"]) {
    const i = t.toLowerCase().indexOf(pat);
    if (i >= 0) console.log(`   [${pat}] ctx:`, t.slice(Math.max(0, i - 120), i + 160).replace(/\s+/g, " "));
  }
} catch (e) {
  console.log("   ERR", e.message);
}
