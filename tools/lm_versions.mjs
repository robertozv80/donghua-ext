const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const get = async (u, ref) => (await fetch(u, { headers: { "User-Agent": UA, ...(ref ? { Referer: ref } : {}) } })).text();

const cases = [
  ["beyond-times-gaze ep10 v/5 (Multisub)", "https://lmanime.com/beyond-times-gaze-episode-10/v/5/", "https://lmanime.com/beyond-times-gaze-episode-10/"],
  ["beyond-times-gaze ep10 v/1 (English)", "https://lmanime.com/beyond-times-gaze-episode-10/v/1/", "https://lmanime.com/beyond-times-gaze-episode-10/"],
  ["mortal's ep01 v/10 (Esp Alternative)", "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/v/10/", "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/"],
  ["mortal's ep01 v/11 (Esp Alternative)", "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/v/11/", "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/"],
  ["mortal's ep01 v/12 (Esp Alternative)", "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/v/12/", "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/"],
  ["mortal's ep01 v/9 (Español)", "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/v/9/", "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/"],
];

for (const [name, url, ref] of cases) {
  try {
    const t = await get(url, ref);
    const ifr = [...t.matchAll(/<iframe[^>]*src=["']([^"']+)["']/gi)].map((m) => m[1]);
    const inPembed = [...t.matchAll(/id="pembed"[\s\S]{0,900}?<\/div>/gi)].map((m) => m[0]);
    console.log(`\n## ${name} (len ${t.length})`);
    console.log("   iframes:", ifr.length ? ifr.join("\n             ") : "(none)");
    if (inPembed.length) {
      const inner = [...inPembed[0].matchAll(/src=["']([^"']+)["']/gi)].map((m) => m[1]);
      console.log("   pembed:", inner.join(" | ") || inPembed[0].slice(0, 200).replace(/\s+/g, " "));
    }
    console.log("   player-embed:", /player-embed/.test(t));
  } catch (e) {
    console.log(`\n## ${name}: ERR ${e.message}`);
  }
}
