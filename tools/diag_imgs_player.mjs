const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

// 1) Imagenes SIN headers (como pide Coil/CloudStream al pintar posters)
const imgs = [
  ["lmanime", "https://lmanime.com/wp-content/uploads/2022/08/Swallowed-Star-S5-768x1077-1.jpg"],
  ["animexin", "https://animexin.dev/wp-content/uploads/2026/04/STH-Movie-768x1077.jpg"],
  ["lucifer", "https://luciferdonghua.org/wp-content/uploads/2024/07/Supreme-God-Emperor.webp"],
  ["animecube", "https://images.animecube.live/assets/images/donghua/series/ling-cage/s2/posters/ling-cage-s2.jpg"],
];
console.log("== IMAGENES sin headers / con UA / con UA+referer:");
for (const [name, u] of imgs) {
  for (const [mode, h] of [["sin-nada", {}], ["UA", { "User-Agent": UA }], ["UA+ref", { "User-Agent": UA, Referer: new URL(u).origin + "/" }]]) {
    try {
      const r = await fetch(u, { headers: h });
      const ct = r.headers.get("content-type") || "?";
      console.log(`   ${name} [${mode}] -> ${r.status} ${ct} ${(r.headers.get("content-length") || "?")}B`);
    } catch (e) {
      console.log(`   ${name} [${mode}] -> ERR ${e.message}`);
    }
  }
}

// 2) Player del episodio AnimeXin sin select.mirror
const ep = await fetch("https://animexin.dev/shrouding-the-heavens-episode-1-subtitle-indonesia-english/", { headers: { "User-Agent": UA } }).then((r) => r.text());
console.log("\n== AnimeXin ep1: estructura del player");
console.log("   len:", ep.length);
for (const pat of ["select", "mirror", "pembed", "iframe", "player", "server", "dailymotion", "dood", "odysee"]) {
  const n = (ep.match(new RegExp(pat, "gi")) || []).length;
  console.log(`   ${pat}: ${n}x`);
}
// iframes directos
const ifr = [...ep.matchAll(/<iframe[^>]+src="([^"]+)"[^>]*>/g)].map((m) => m[1]);
console.log("   iframes src:", ifr.slice(0, 10));
// bloques server-btn
const btns = [...ep.matchAll(/<div[^>]+class="([^"]*server[^"]*)"[^>]*>/gi)].map((m) => m[1]);
console.log("   clases server:", btns.slice(0, 10));
//mirar la zona con "All Sub"
const i = ep.indexOf("All Sub");
console.log("   contexto 'All Sub':", i >= 0 ? ep.slice(Math.max(0, i - 300), i + 400).replace(/\s+/g, " ") : "(no aparece 'All Sub')");
// base64 en data attributes
const b64attr = [...ep.matchAll(/(data-[a-z]+)="((?:[A-Za-z0-9+/]{40,}={0,2}))"/g)].map((m) => m[1] + " len=" + m[2].length);
console.log("   attrs base64:", b64attr.slice(0, 10));
