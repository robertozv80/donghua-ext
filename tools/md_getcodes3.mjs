// Depura los bloques eval del HTML.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

const EP = "https://www.mundodonghua.com/ver/against-the-gods/30";
const r = await fetch(EP, { headers: { "User-Agent": UA, Referer: "https://www.mundodonghua.com/" }, redirect: "manual" });
const text = await r.text();
console.log("status", r.status, "bytes", text.length);

// Buscar todas las apariciones de "eval(function"
let idx = 0;
let n = 0;
while (true) {
  const i = text.indexOf("eval(function", idx);
  if (i < 0) break;
  // Capturar hasta el final del eval: buscar el cierre "))" equilibrado
  // Mostrar los primeros 200 chars
  const snippet = text.slice(i, i + 220);
  console.log(`\n--- block ${n} @${i} ---`);
  console.log(snippet);
  n++;
  idx = i + 10;
}
console.log("\ntotal blocks:", n);
