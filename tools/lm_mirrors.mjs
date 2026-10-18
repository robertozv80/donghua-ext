const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = async (u, ref) => (await fetch(u, { headers: { "User-Agent": UA, ...(ref ? { Referer: ref } : {}) } })).text();

for (const ep of [
  "https://lmanime.com/beyond-times-gaze-episode-10/",
  "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/",
]) {
  const t = await get(ep);
  console.log(`\n===== ${ep} (len ${t.length}) =====`);
  const opts = [...t.matchAll(/<option[^>]*value="([^"]*)"[^>]*>([^<]*)<\/option>/g)];
  console.log("options:", opts.length);
  for (const [, v, lab] of opts) {
    const isSelf = v.includes("/v/") || v.includes("lmanime");
    console.log(`   "${lab.trim()}" -> ${v}${isSelf ? "" : ""}`);
  }
  // look at a couple /v/N/ pages for iframes
  const versions = [...new Set([...t.matchAll(/lmanime\.com\/[^"']*\/v\/(\d+)\//g)].map((m) => m[1]))];
  console.log("versions referenced:", versions.join(",") || "-");
}
