const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

async function get(url, referer, extra = {}) {
  const res = await fetch(url, { redirect: "follow", headers: { "User-Agent": UA, Referer: referer || url, ...extra } });
  const text = await res.text();
  return { status: res.status, text, finalUrl: res.url };
}

const CASES = [
  ["Rumble Lucifer (falla)", "https://rumble.com/embed/v7eh20k/?pub=4qowyg", "https://luciferdonghua.org"],
  ["Rumble AnimeXin AllSub", "https://rumble.com/embed/v7eh2hu/?pub=4qfz5q", "https://animexin.dev"],
  ["Streamwish seekplayer", "https://animexinfansub.seekplayer.vip/#88nf3", "https://animexin.dev"],
  ["Dood playmogo", "https://playmogo.com/e/1zi1ci998vyw", "https://animexin.dev"],
  ["Ok.ru Lucifer", "https://ok.ru/videoembed/15839027464883?nochat=1", "https://luciferdonghua.org"],
  ["Ok.ru LmAnime /v/10", "https://ok.ru/videoembed/4416786401831", "https://lmanime.com"],
  ["Filemoon LmAnime /v/11", "https://filemoon.sx/e/zvploy2z5rsd/AMJS1E01R_ES_1080P", "https://lmanime.com"],
  ["Fembed LmAnime /v/12", "https://www.fembed.com/v/q1e11ie-8zrmqzm", "https://lmanime.com"],
  ["BYSE TioDonghua", "https://byselapuix.com/e/ohmv3woxtd8d", "https://tiodonghua.lat"],
];

for (const [label, url, ref] of CASES) {
  try {
    const r = await get(url, ref);
    const t = r.text || "";
    console.log(`\n### ${label}: ${r.status} (final=${r.finalUrl.slice(0, 80)}) len=${t.length}`);
    const m3u8 = [...new Set([...t.matchAll(/https?:\/\/[^"'\s\\]+\.m3u8[^"'\s\\]*/g)].map(m => m[0]))].slice(0, 4);
    const mp4 = [...new Set([...t.matchAll(/"file":"(https?:[^"]+)"/g)].map(m => m[1]))].slice(0, 6);
    if (m3u8.length) m3u8.forEach(x => console.log("   m3u8:", x.slice(0, 140)));
    if (mp4.length) mp4.forEach(x => console.log("   file:", x.slice(0, 140)));
    if (/pass_md5/.test(t)) console.log("   >>> pass_md5 pattern PRESENT");
    if (/challenge-platform|cf_chl/.test(t)) console.log("   >>> CLOUDFLARE challenge");
    if (/function\(p,a,c,k,e|_0x[0-9a-f]|packed/.test(t)) console.log("   >>> packed/obfuscated JS");
    // rumble: abrir embed primero (como hace el extractor), luego embedJS
    if (/rumble\.com\/embed/.test(url)) {
      await get(url, ref);
      const rid = url.match(/v([a-zA-Z0-9]+)/)?.[1];
      const api = `https://rumble.com/embedJS/u3/?request=video&v=${rid}&embed=${encodeURIComponent(url)}`;
      const r2 = await get(api, url);
      console.log("   embedJS:", r2.status, "uaMap hits:", (r2.text.match(/"(1080|720|480|360)":"/g) || []).length);
      const um = [...r2.text.matchAll(/"(1080|720|480|360|2160)":\["(https:[^"]+?)",/g)];
      um.slice(0, 6).forEach(m => console.log("     ", m[1], m[2].slice(0, 110)));
    }
    // ok.ru: data-options
    if (/ok\.ru/.test(url)) {
      const opt = t.match(/data-options="([^"]+)"/);
      console.log("   data-options:", opt ? "SI (" + opt[1].length + " chars)" : "no");
      if (opt) {
        const dec = opt[1].replace(/&quot;/g, '"').replace(/\\\//g, "/").replace(/\\u([0-9a-fA-F]{4})/g, (m, h) => String.fromCharCode(parseInt(h, 16)));
        const hls = [...dec.matchAll(/"(?:\w*ManifestUrl|ondemandHls)":"([^"]+)"/g)].map(m => m[1]);
        console.log("   hls candidates:", hls.length, hls[0]?.slice(0, 120));
        const prog = [...dec.matchAll(/"name":"(mobile|lowest|low|sd|hd|full|super)","url":"([^"]+)"/g)].map(m => m[1] + ":" + m[2].slice(0, 70));
        prog.slice(0, 8).forEach(p => console.log("     prog:", p));
      }
    }
  } catch (e) {
    console.log(`\n### ${label}: EXC ${e.message}`);
  }
}
