const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
async function get(u) {
  const r = await fetch(u, { headers: { "User-Agent": UA } });
  return r.text();
}
const pages = process.argv.slice(2);
if (!pages.length) pages.push("https://animexin.dev/?s=The+Guardian+of+Daxia");
for (const p of pages) {
  const t = await get(p);
  console.log(`\n===== ${p} (len ${t.length}) =====`);
  const opts = [...t.matchAll(/<option[^>]*value="([^"]{20,})"[^>]*>([^<]+)<\/option>/g)];
  const seen = new Set();
  opts.forEach(([, b64, label], i) => {
    let src = "";
    try { src = (Buffer.from(b64, "base64").toString("utf8").match(/src=["']([^"']+)["']/) || [])[1] || ""; } catch {}
    const dup = seen.has(src) ? "  <-- DUPLICATE" : "";
    seen.add(src);
    console.log(`  [${i}] "${label.trim()}" -> ${src}${dup}`);
  });
  // episode links if search page
  if (p.includes("?s=")) {
    const eps = [...t.matchAll(/<a[^>]*href="(https:\/\/animexin\.dev\/[^"]+)"[^>]*title="([^"]*)"/g)].slice(0, 8);
    console.log("  links:", eps.map((m) => `${m[2]} :: ${m[1]}`).join("\n          ") || "-");
  }
}
