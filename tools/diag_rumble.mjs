const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
for (const [n, vid, ref] of [
  ['lucifer43', 'v7gney2', 'https://luciferdonghua.org/beyond-times-gaze-episode-43-english-subtitle/'],
  ['seriesATG', 'v7gk9bu', 'https://seriesdonghua.com/against-the-gods-episodio-58/'],
]) {
  const api = `https://rumble.com/embedJS/u3/?request=video&v=${vid}&embed=${encodeURIComponent(ref)}`;
  try {
    const t = await (await fetch(api, { headers: { 'User-Agent': UA, Referer: 'https://rumble.com/' } })).text();
    console.log('===', n, vid, 'len', t.length);
    try {
      const j = JSON.parse(t);
      console.log(' keys:', Object.keys(j).join(','));
      if (j.ua) { for (const [q, u] of Object.entries(j.ua)) console.log('  ', q, String(u).slice(0, 100)); }
      if (j.hls) console.log('  hls:', String(j.hls).slice(0, 110));
      if (j.error) console.log('  err:', j.error);
    } catch { console.log(t.slice(0, 300)); }
  } catch (e) { console.log(n, 'ERR', e.message); }
}
