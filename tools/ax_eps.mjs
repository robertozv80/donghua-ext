const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const r = await fetch("https://animexin.dev/the-guardian-of-daxia/", { headers: { "User-Agent": UA } });
const t = await r.text();
const eps = [...t.matchAll(/href="(https:\/\/animexin\.dev\/[^"]*episode[^"]*)"[^>]*>/gi)].map(m=>m[1]);
console.log([...new Set(eps)].slice(0,10).join("\n"));
