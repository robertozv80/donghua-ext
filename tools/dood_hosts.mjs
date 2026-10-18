const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const id = "qerhonglzwc5";
const hosts = ["playmogo.com","dsvplay.com","ds2play.com","ds2play.net","d000d.com","dood.to","dood.so","dood.watch","doodstream.com","doply.net","doodcdn.com","doodstream.co","videobin.top","dsvplay.net","doods.pro","dood.yt","doodapi.com","playmogo.co"];
for (const h of hosts) {
  for (const path of [`/e/${id}`, `/d/${id}`]) {
    try {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), 12000);
      const r = await fetch(`https://${h}${path}`, { headers: { "User-Agent": UA, Referer: "https://animexin.dev/" }, redirect: "follow", signal: ctrl.signal });
      clearTimeout(timer);
      const t = await r.text();
      const cf = /Just a moment|challenges\.cloudflare/.test(t);
      console.log(`${h}${path}: ${r.status} len=${t.length} cf=${cf} final=${r.url} passmd5=${/pass_md5/.test(t)}`);
    } catch (e) { console.log(`${h}${path}: ERR ${e.message}`); }
  }
}
