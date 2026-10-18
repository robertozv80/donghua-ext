const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
import fs from "fs";

// 1) AnimeCube: RSC de una ficha -> campos de la ficha (rating, duration, status, cover)
const r = await fetch("https://animecube.live/anime/renegade-immortal?_rsc=1", { headers: { "User-Agent": UA, RSC: "1" } });
const flight = await r.text();
fs.writeFileSync("tools/ac_flight.txt", flight);
console.log("== AnimeCube RSC len:", flight.length, "| tiene episodes:", flight.includes('"episodes"'), "| escaped slashes:", (flight.match(/\\\//g) || []).length);
// campos de ficha
for (const k of ["rating", "duration", "releaseDate", "status", "coverImage", "description", "synopsis", "year", "genres", "studio"]) {
  const m = flight.match(new RegExp('"' + k + '":("[^"]*"|[0-9.]+|\\[[^\\]]{0,120}\\])'));
  console.log(`   ${k}:`, m ? m[1].slice(0, 130) : "(no)");
}
// muestra de un objeto de episodio
const ei = flight.indexOf('"episodes"');
console.log("   episodes raw:", flight.slice(ei, ei + 500).replace(/\s+/g, " "));

// 2) DonghuaZone: post de serie (para poster/plot) + thumbnail de episodio
const feed = await fetch("https://www.donghuazone.com/feeds/posts/default/-/" + encodeURIComponent("Swallowed star") + "?alt=json&max-results=3", { headers: { "User-Agent": UA } }).then((x) => x.text());
const j = JSON.parse(feed);
const e0 = j.feed.entry[0];
console.log("\n== DonghuaZone entry0:", e0.title.$t, "| thumb:", e0.media$thumbnail?.url);
console.log("   labels:", e0.category.map((c) => c.term).join(", "));
console.log("   published:", e0.published.$t);
// content snippet
const content = e0.content.$t;
console.log("   content len:", content.length, "| head:", content.replace(/\s+/g, " ").slice(0, 400));

// 3) weserv proxy para imagen animexin
const w = await fetch("https://images.weserv.nl/?url=animexin.dev/wp-content/uploads/2026/04/STH-Movie-768x1077.jpg");
console.log("\n== weserv animexin:", w.status, w.headers.get("content-type"));
