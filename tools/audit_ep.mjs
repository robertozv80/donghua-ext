const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

async function get(url, referer, asText = true, method = "GET", extraHeaders = {}) {
  const res = await fetch(url, {
    method, redirect: "follow",
    headers: { "User-Agent": UA, Referer: referer || url, ...extraHeaders },
  });
  const text = asText ? await res.text() : null;
  return { status: res.status, text, finalUrl: res.url, headers: res.headers };
}

async function dmMeta(vid, embedder) {
  const url = `https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(embedder)}&integration=inline`;
  const r = await get(url, `https://www.dailymotion.com/embed/video/${vid}`);
  try { return JSON.parse(r.text); } catch { return { _raw: (r.text || "").slice(0, 200) }; }
}

function scanIframes(html) {
  const out = [];
  const re = /<iframe[^>]+src="([^"]+)"[^>]*>/gi;
  // capture with title/data attributes
  let m;
  while ((m = re.exec(html))) {
    const tag = m[0];
    const title = (tag.match(/title="([^"]*)"/) || [])[1] || "";
    const src = m[1];
    out.push({ src, title });
  }
  return out;
}

function classify(src) {
  const s = src.toLowerCase();
  if (s.includes("dailymotion.com") || s.includes("geo.dailymotion.com")) return "dailymotion";
  if (s.includes("dooood") || s.includes("dood.") || s.includes("playmogo") || s.includes("dsvplaylab") || s.includes("myvidplay") || s.includes("vendewise")) return "dood";
  if (s.includes("rumble.com")) return "rumble";
  if (s.includes("ok.ru") || s.includes("odnoklassniki")) return "okru";
  if (s.includes("streamwish") || s.includes("wishfast") || s.includes("awish") || s.includes("swhoi") || s.includes("datash") || s.includes("kitabmarkaz") || s.includes("azmat.hp") || s.includes("sigruni") || s.includes("tolerada") || s.includes("wishesiku") || s.includes("dhmcdn") || s.includes("gotoProgram") === false && s.includes("filemoon")) {
    // crude: filemoon vs streamwish both embedded players
  }
  if (s.includes("streamtape")) return "streamtape";
  if (s.includes("filemoon")) return "filemoon";
  if (s.includes("fembed") || s.includes("femax")) return "fembed";
  if (s.includes("byse")) return "byse";
  if (s.includes("mp4upload")) return "mp4upload";
  if (s.includes("gdrive") || s.includes("drive.google")) return "gdrive";
  if (s.includes("odysee")) return "odysee";
  if (s.includes("voe")) return "voe";
  return "other";
}

const CASES = [
  { label: "AnimeCube A Good Day to Ascend 1", url: "https://animecube.live/anime/a-good-day-to-ascend?from=home&season=tab-1&episode=a-good-day-to-ascend-tab-1-ep-1" },
  { label: "AnimeCube Beyond Time's Gaze 1", url: "https://animecube.live/anime/beyond-time-s-gaze?from=home&season=tab-1766889196697&episode=beyond-time-s-gaze-tab-1766889196697-ep-1" },
  { label: "AnimeXin Guardian of Daxia ep6", url: "https://animexin.dev/the-guardian-of-daxia-episode-6-indonesia-english-sub/" },
  { label: "AnimeXin Jade Dynasty S4 ep1", url: "https://animexin.dev/jade-dynasty-season-4-final-season-episode-1-indonesia-english-sub/" },
  { label: "AnimeXin A Mortal's Journey ep195", url: "https://animexin.dev/a-record-of-mortals-journey-to-immortality-episode-195-indonesia-english-sub/" },
  { label: "AnimeXin Wu Dong Qian Kun S5 ep1", url: "https://animexin.dev/martial-universe-wu-dong-qian-kun-season-5-episode-1-indonesia-english-sub/" },
  { label: "Lucifer Beyond Times Gaze ep43", url: "https://luciferdonghua.org/beyond-times-gaze-episode-43-english-subtitle/" },
  { label: "LmAnime ep1 /v/9", url: "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/v/9/" },
  { label: "LmAnime ep1 /v/10", url: "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/v/10/" },
  { label: "LmAnime ep1 /v/11", url: "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/v/11/" },
  { label: "LmAnime ep1 /v/12", url: "https://lmanime.com/a-mortals-journey-to-immortality-episode-01/v/12/" },
  { label: "TioDonghua Legend of Xian Wu 3 ep73", url: "https://tiodonghua.lat/episodios/legend-of-xian-wu-3-episodio-73-sub-espanol/" },
  { label: "DonghuaZone sample", url: "https://donghuazone.online/" },
];

for (const c of CASES) {
  try {
    const page = await get(c.url);
    if (page.status !== 200) { console.log(`\n### ${c.label}: HTTP ${page.status}`); continue; }
    const html = page.text || "";
    const iframes = scanIframes(html);
    console.log(`\n### ${c.label} (len=${html.length}, iframes=${iframes.length})`);
    const groups = {};
    for (const f of iframes) {
      const k = classify(f.src);
      (groups[k] = groups[k] || []).push(f.src.slice(0, 140) + (f.title ? ` [t=${f.title.slice(0, 40)}]` : ""));
    }
    for (const k in groups) console.log(`  ${k} x${groups[k].length}`);
    for (const f of iframes.slice(0, 25)) console.log(`   - [${classify(f.src)}] ${f.src.slice(0, 150)}`);

    // If DM iframes: pull first metadata and inspect qualities
    const dm = iframes.find(f => classify(f.src) === "dailymotion");
    if (dm) {
      const mm = dm.src.match(/(?:video=|embed\/video\/)([A-Za-z0-9]+)/);
      if (mm) {
        const meta = await dmMeta(mm[1], c.url);
        const q = meta.qualities || {};
        console.log(`  DM ${mm[1]} keys=${Object.keys(q).join(",")}`);
        for (const k in q) {
          q[k].forEach((x, i) => console.log(`   q${k}#${i} ${x.type} ${String(x.url).slice(0, 110)}`));
        }
        if (meta.manifests) console.log("  manifests:", JSON.stringify(meta.manifests).slice(0, 500));
        if (meta.error) console.log("  ERROR:", JSON.stringify(meta.error));
      }
    }
  } catch (e) {
    console.log(`\n### ${c.label}: EXC ${e.message}`);
  }
}
