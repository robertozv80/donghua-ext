const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const r = await fetch("https://animexin.dev/jade-dynasty-season-4-final-season-episode-1-indonesia-english-sub/", { headers: { "User-Agent": UA } });
const t = await r.text();
console.log("status", r.status, "len", t.length);
// base64 mirrors
const opts = [...t.matchAll(/<option[^>]*value="([^"]{20,})"[^>]*>([^<]+)<\/option>/g)];
console.log("options:", opts.length);
const seen = new Set();
for (const [, b64, label] of opts) {
  try {
    const html = Buffer.from(b64, "base64").toString("utf8");
    const src = (html.match(/src=["']([^"']+)["']/) || [])[1] || "";
    console.log(`- "${label.trim()}" -> ${src}`);
  } catch {}
}
// raw dood occurrences
console.log("\ndood hosts in page:", [...new Set([...t.matchAll(/https?:\/\/[^"'\s]*(?:dood|dsvplay|playmogo|d000d|doply|ds2play|doods?\.)[^"'\s]*/gi)].map(m=>m[0]))].slice(0,20));
