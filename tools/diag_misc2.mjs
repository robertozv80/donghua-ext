const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = (u, ref) => fetch(u, { headers: { "User-Agent": UA, ...(ref ? { Referer: ref } : {}) } }).then((r) => r.text());

// 1) AnimeXin: numscore y .spe de la ficha
const ax = await get("https://animexin.dev/anime/shrouding-the-heavens/");
const ns = (ax.match(/class="numscore"[^>]*>([^<]*)</) || [])[1];
console.log("== animexin numscore:", ns);
const axSpe = ax.match(/class="spe">([\s\S]{0,700})/);
console.log("   spe head:", axSpe ? axSpe[1].replace(/\s+/g, " ").slice(0, 500) : "(no)");

// breadcrumb del episodio shrounding ep1 (para confirmar la ficha real de la serie)
const ep = await get("https://animexin.dev/shrounding-the-heavens-episode-1-subtitle-indonesia-english/");
const crumb = [...ep.matchAll(/<div class="ts-breadcrumb">([\s\S]{0,600}?)<\/div>/g)];
console.log("\n== breadcrumb ep1:", crumb[0] ? crumb[0][1].match(/href="([^"]+)"/g)?.slice(0, 6) : "(no)");

// 2) DonghuaZone: post de la serie (sinopsis/poster)
const feed = await get("https://www.donghuazone.com/feeds/posts/default?alt=json&q=" + encodeURIComponent("swallowed star") + "&max-results=6");
const j = JSON.parse(feed);
for (const e of j.feed.entry || []) {
  const isEp = /Episode\s+\d+/i.test(e.title.$t);
  console.log("\n== entry:", e.title.$t.slice(0, 60), "| ep?", isEp, "| labels:", e.category?.map((c) => c.term).join(","));
  if (!isEp) {
    const c = e.content.$t;
    console.log("   thumb:", e.media$thumbnail?.url?.slice(0, 90));
    console.log("   content head:", c.replace(/\s+/g, " ").slice(0, 700));
    console.log("   published:", e.published?.$t);
  }
}
