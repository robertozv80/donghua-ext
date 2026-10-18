const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = (u, ref) => fetch(u, { headers: { "User-Agent": UA, ...(ref ? { Referer: ref } : {}) } }).then((r) => r.text());

// 1) DM: raw -> replace \/ -> / -> regexes del provider
const HLS_RE = /"url":"(https:\/\/[^"]+?\.m3u8[^"]*)"/g;
const SUBS_RE = /"(es[a-z-]*|en[a-z-]*)":\s*\{[^{}]*?"urls":\["([^"]+)"/g;
const MP4_RE = /"(\d{3,4})":\s*\[\s*\{\s*"type":\s*"video\/mp4"\s*,\s*"url":\s*"([^"]+)"/g;

for (const [name, vid, ref] of [
  ["lmanime-esp", "k5kmTfN2UaL96gzvLfz", "https://lmanime.com/"],
  ["animexin-allsub", "k1slp8qR9ukY7ez4V3W", "https://animexin.dev/"],
  ["lucifer-646", "k4ni3IdBNxNeHQL4zCW", "https://luciferdonghua.org/"],
]) {
  const raw = await get(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(ref)}&integration=inline`, ref);
  const fixed = raw.replace(/\\\//g, "/").replace(/\\u0026/g, "&");
  const hls = [...fixed.matchAll(HLS_RE)].map((m) => m[1]);
  const subs = [...fixed.matchAll(SUBS_RE)].map((m) => m[1]);
  const mp4 = [...fixed.matchAll(MP4_RE)].length;
  console.log(`${name}: HLS=${hls.length} subs=[${subs.join(",")}] mp4=${mp4}`);
  if (hls[0]) console.log(`   hls: ${hls[0].slice(0, 95)}`);
}

// 2) DonghuaZone label feed: hay post de serie (no episodio)?
const feed = JSON.parse(await get("https://www.donghuazone.com/feeds/posts/default/-/" + encodeURIComponent("Swallowed star") + "?alt=json&max-results=25"));
const entries = feed.feed.entry || [];
const nonEp = entries.filter((e) => !/Episode\s+\d+/i.test(e.title.$t));
console.log("\nDonghuaZone label feed: entradas=" + entries.length + " no-episodio=" + nonEp.length);
nonEp.slice(0, 2).forEach((e) => {
  console.log("   serie post:", e.title.$t.slice(0, 50), "| thumb:", (e.media$thumbnail?.url || "").slice(0, 70));
  console.log("   published:", e.published?.$t);
});
