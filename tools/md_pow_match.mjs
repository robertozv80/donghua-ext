// Determina si la diferencia con el bundle es el acceso r[s+1] fuera de rango.
import fs from "node:fs";
const src = fs.readFileSync("tools/_byse_pow.js", "utf8");
const s0 = src.indexOf("be=512,lt=be-1"), s1 = src.indexOf("export{", s0);
const code = src.slice(s0, s1);
const ref = new Function(code + "\nreturn gr;")();
const zeroFixed = new Function(code.replace("ht(r[s+1<],lr)", "ht(s+1<be?r[s+1]:0,lr)") + "\nreturn gr;")();
const wrapped = new Function(code.replace("ht(r[s+1<],lr)", "ht(r[(s+1)&lt],lr)") + "\nreturn gr;")();
console.log("sustitucion cero aplicada:", code.includes("ht(r[s+1<],lr)"));

// --- mi transcripcion (la del Kotlin, ya con t[3]^t[0]) ---
const rotl32 = (x, n) => ((x << n) | (x >>> (32 - n))) >>> 0;
function mixRound(t) {
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 16);
  t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 12);
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 8);
  t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 7);
}
function mineGr(data, wrap) {
  const size = 512, mask = size - 1, lr = 2654435761 >>> 0, hr = 2246822519 >>> 0;
  const e = [1779033703, 3144134277 >>> 0, 1013904242, 2773480762 >>> 0];
  for (const b of data) { e[0] = (e[0] + b) >>> 0; e[0] = rotl32(e[0], 7); mixRound(e); }
  for (let i = 0; i < 8; i++) mixRound(e);
  const table = new Array(size);
  for (let i = 0; i < size; i++) { mixRound(e); table[i] = (e[0] ^ e[2]) >>> 0; }
  for (let r = 0; r < 2; r++) for (let s = 0; s < size; s++) {
    let c = (table[s] + table[table[s] & mask]) >>> 0;
    c = rotl32(c, 13);
    const nx = wrap ? table[(s + 1) & mask] : (s + 1 < size ? table[s + 1] : 0);
    c = (c ^ Math.imul(nx, lr)) >>> 0;
    table[s] = c; e[0] = (e[0] ^ c) >>> 0; mixRound(e);
  }
  const out = new Array(8), group = size / 8;
  for (let i = 0; i < 8; i++) {
    mixRound(e);
    let acc = e[0]; const base = i * group;
    for (let j = 0; j < group; j++) { const d = table[base + j]; acc = (acc + d) >>> 0; acc = rotl32(acc, 5); acc = (acc ^ Math.imul(d, hr)) >>> 0; }
    out[i] = (acc ^ e[2]) >>> 0;
  }
  return out;
}
const eq = (a, b) => a.every((x, i) => x === b[i]);
for (const s of ["a", "abc", "2e59e31ae6d7bdecd4eea8e7d99b15e3:0"]) {
  const u = new Uint8Array([...s].map((c) => c.charCodeAt(0) & 255));
  const R = [...ref(u)], Z = [...zeroFixed(u)], W = [...wrapped(u)];
  const M0 = [...mineGr(u, false)], M1 = [...mineGr(u, true)];
  console.log(JSON.stringify(s.slice(0, 40)),
    "| ref==zeroFix:", eq(R, Z),
    "| ref==wrapped:", eq(R, W),
    "| mio(cero)==ref:", eq(M0, R),
    "| mio(cero)==zeroFix:", eq(M0, Z),
    "| mio(wrap)==wrapped:", eq(M1, W));
}