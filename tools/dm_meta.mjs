const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const vid = process.argv[2] || "k4anlU4vS5uwfGLg7Ey";
const ref = process.argv[3] || "https://animexin.dev/";
const raw = await (await fetch(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(ref)}&integration=inline`, { headers: { "User-Agent": UA, Referer: ref } })).text();
const json = raw.split("\\/").join("/").split("\\u0026").join("&");

const hls = [...json.matchAll(/"url":"(https:\/\/[^"]+?\.m3u8[^"]*)"/g)].map((m) => m[1]);
console.log("HLS matches:", hls.length);
hls.slice(0, 3).forEach((u) => console.log("   ", u.slice(0, 130)));

const mp4 = [...json.matchAll(/(\d{3,4})":\s*\[\s*\{\s*"type":\s*"video\/mp4"\s*,\s*"url":\s*"([^"]+)"/g)];
console.log("MP4 matches:", mp4.length);
mp4.slice(0, 8).forEach((m) => console.log("   ", m[1], m[2].slice(0, 130)));

const qm = json.match(/"qualities":\{(.{0, 300})/s);
console.log("qualities head:", qm ? qm[1].replace(/\s+/g, " ") : "none");

const subsBlock = json.indexOf('"subtitles"');
console.log("has subtitles:", subsBlock >= 0);
