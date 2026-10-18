// Busca bysekoze directamente en el HTML crudo.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const EP = "https://www.mundodonghua.com/ver/against-the-gods/30";
const r = await fetch(EP, { headers: { "User-Agent": UA, Referer: "https://www.mundodonghua.com/" }, redirect: "manual" });
const text = await r.text();
console.log("status", r.status, "bytes", text.length);

// ¿aparece "bysekoze" literal?
let i = text.indexOf("bysekoze");
console.log("bysekoze literal index:", i);
if (i >= 0) console.log("contexto:", JSON.stringify(text.slice(i - 120, i + 120)));

// ¿aparece en el packed? buscar "byse" (símbolo empaquetado puede ser corto)
// Mostrar todos los dominios de player presentes
for (const m of text.matchAll(/https?:\/\/[a-z0-9.-]*(player|nemonic|embed|wish|vgembed|vidhide|bysekoze|filemoon|voe|dailymotion)[a-z0-9.-]*\.[a-z]+/gi)) {
  console.log("dom:", m[0]);
}
// iframes
for (const m of text.matchAll(/<iframe[^>]*src="([^"]+)"[^>]*>/g)) console.log("iframe:", m[1]);
