const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36';
const u = 'https://gdriveplayer.to/embed2.php?link=vvCVLHZJnk5fYgURQVTonAFJ1QaALZ%252BMLZ%252FLLvidf4mygTxArfYyQGj3xC';
const r = await fetch(u, { headers: { 'User-Agent': UA }, redirect: 'follow' });
const t = await r.text();
console.log('status', r.status, 'final', r.url.slice(0, 120), 'len', t.length);
const urls = [...new Set([...t.matchAll(/https?:\/\/[^"'\s<>]+/g)].map(m => m[0]))]
  .filter(x => /\.m3u8|\.mp4|filemoon|wish|embed|player/i.test(x)).slice(0, 12);
urls.forEach(x => console.log(' url:', x.slice(0, 140)));
if (t.includes('eval(function')) console.log(' PACKED-JS');
console.log('--head--');
console.log(t.slice(0, 500).replace(/\s+/g, ' '));
