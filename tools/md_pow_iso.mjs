// Aísla la diferencia entre mi transcripción y el bundle inyectando mi `ye` en el
// código original (reemplazo literal de texto, sin re-evaluar a mano).
import fs from "node:fs";
const src = fs.readFileSync("tools/_byse_pow.js", "utf8");
const s0 = src.indexOf("be=512,lt=be-1"), s1 = src.indexOf("export{", s0);
const code = src.slice(s0, s1);
const refGr = new Function(code + "\nreturn gr;")();
const refYe = new Function(code + "\nreturn ye;")();

const mineYeSrc = `function ye(t){t[0]=(t[0]+t[1])>>>0,t[3]=rotl32(t[3]^t[0],16),t[2]=(t[2]+t[3])>>>0,t[1]=rotl32(t[1]^t[2],12),t[0]=(t[0]+t[1])>>>0,t[3]=rotl32(t[3]^t[0],8),t[2]=(t[2]+t[3])>>>0,t[1]=rotl32(t[1]^t[2],7)}`;
const rot = `const rotl32=(x,n)=>((x<<n)|(x>>>(32-n)))>>>0;`;
const mineGrSrc = code.replace(/function ye\(t\)\{[^}]*\}/, mineYeSrc);
const mixGr = new Function(rot + mineGrSrc + "\nreturn gr;")();
const mineYe = new Function(rot + mineYeSrc + "\nreturn ye;")();

for (const t of [[1, 2, 3, 4], [0xffffffff, 0, 0x12345678, 0x9abcdef0], [5, 7, 11, 13], [0, 0, 0, 0]]) {
  const a = [...t], b = [...t];
  refYe(a); mineYe(b);
  console.log("ye", JSON.stringify(t), "->", a.join(",") , "|", b.join(","), a.every((x, i) => x === b[i]) ? "OK" : "DIFIERE");
}
console.log("--- gr ---");
for (const s of ["a", "abc", "2e59e31ae6d7bdecd4eea8e7d99b15e3:0"]) {
  const u = new Uint8Array([...s].map((c) => c.charCodeAt(0) & 255));
  const a = [...refGr(u)], b = [...mixGr(u)];
  console.log(JSON.stringify(s), a.every((x, i) => x === b[i]) ? "OK" : "DIFIERE");
  if (!a.every((x, i) => x === b[i])) {
    console.log("  ref:", a.map((x) => x.toString(16)).join(" "));
    console.log("  mix:", b.map((x) => x.toString(16)).join(" "));
  }
}