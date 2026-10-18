const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = (u, ref) => fetch(u, { headers: { "User-Agent": UA, Referer: ref } }).then((r) => r.text());

const HLS_RE = /"url":"(https:\/\/[^"]+?\.m3u8[^"]*)"/g;
const MP4_RE = /"(\d{3,4})":\s*\[\s*\{\s*"type":\s*"video\/mp4"\s*,\s*"url":\s*"([^"]+)"/g;
const SUBS_RE = /"(es[a-z-]*|en[a-z-]*)":\s*\{[^{}]*?"urls":\["([^"]+)"/g;

for (const [name, vid, ref] of [
  ["lmanime-esp", "k5kmTfN2UaL96gzvLfz", "https://lmanime.com/"],
  ["animexin-allsub", "k1slp8qR9ukY7ez4V3W", "https://animexin.dev/"],
  ["lucifer-646", "k4ni3IdBNxNeHQL4zCW", "https://luciferdonghua.org/"],
]) {
  const url = `https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(ref)}&integration=inline`;
  const raw = await get(url, ref);
  console.log(`\n== ${name} (${vid}) raw len=${raw.length}`);
  console.log("   contiene .m3u8:", (raw.match(/\.m3u8/g) || []).length, "x | contiene mp4:", (raw.match(/video\/mp4/g) || []).length, "x");
  const hls = [...raw.matchAll(HLS_RE)].map((m) => m[1]);
  console.log("   HLS_RE matches:", hls.length, hls.slice(0, 2).map((u) => u.slice(0, 100)));
  const mp4 = [...raw.matchAll(MP4_RE)].map((m) => m[2]);
  console.log("   MP4_RE matches:", mp4.length, mp4.slice(0, 2).map((u) => u.slice(0, 90)));
  const subs = [...raw.matchAll(SUBS_RE)].map((m) => m[1] + "->" + m[2].slice(0, 60));
  console.log("   SUBS_RE matches:", subs.length, subs.slice(0, 3));
  // qualities block crudo
  const qi = raw.indexOf('"qualities"');
  if (qi >= 0) console.log("   qualities raw:", raw.slice(qi, qi + 400).replace(/\s+/g, " "));
  else console.log("   NO qualities key");
  // donde esta el m3u8 si no matcheo HLS_RE
  const mi = raw.indexOf(".m3u8");
  if (mi >= 0 && hls.length === 0) console.log("   ctx m3u8:", raw.slice(Math.max(0, mi - 200), mi + 100).replace(/\s+/g, " "));
}
