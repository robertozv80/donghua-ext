// ¿Afecta el acceso fuera de rango r[s+1] (que en JS da undefined -> imul 0)?
import fs from "node:fs";
const src = fs.readFileSync("tools/_byse_pow.js", "utf8");
const s0 = src.indexOf("be=512,lt=be-1"), s1 = src.indexOf("export{", s0);
const code = src.slice(s0, s1);
const refGr = new Function(code + "\nreturn gr;")();
// variante: envolver con ((s+1)&mask)
const wrapped = code.replace(/ht\(r\[s\+1<\],lr\)/g, "ht(r[(s+1)&lt],lr)");
const has = wrapped !== code;
const wrapGr = new Function(wrapped + "\nreturn gr;")();
console.log("reemplazo aplicado:", has);
for (const s of ["a", "abc", "2e59e31ae6d7bdecd4eea8e7d99b15e3:0", "2e59e31ae6d7bdecd4eea8e7d99b15e3:180455"]) {
  const u = new Uint8Array([...s].map((c) => c.charCodeAt(0) & 255));
  const a = [...refGr(u)], b = [...wrapGr(u)];
  console.log(JSON.stringify(s.slice(0, 42)), a.every((x, i) => x === b[i]) ? "IGUAL" : "DIFIERE");
}