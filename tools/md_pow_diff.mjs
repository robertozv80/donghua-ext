// Compara mi transcripción del hash con el código literal del bundle, aislando el
// punto exacto de divergencia (se evalúa el tramo final del bundle, sin regex).
import fs from "node:fs";

const src = fs.readFileSync("tools/_byse_pow.js", "utf8");
const startMarker = "be=512,lt=be-1";
const s0 = src.indexOf(startMarker);
if (s0 < 0) throw new Error("no encuentro el tramo del PoW");
const s1 = src.indexOf("export{", s0);
const code = src.slice(s0, s1).replace(/\bexport\b/g, "//export");
const bundle = new Function(`return (async () => {
${code}
return { gr, wr, Er, yr, be, ye, re, ht };
})();`);

// ---- mi transcripción (idéntica a la que está en el Kotlin) ----
const rotl32 = (x, n) => ((x << n) | (x >>> (32 - n))) >>> 0;
function mixRound(t) {
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 16);
  t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 12);
  t[0] = (t[0] + t[1]) >>> 0; t[3] = rotl32(t[3] ^ t[0], 8);
  t[2] = (t[2] + t[3]) >>> 0; t[1] = rotl32(t[1] ^ t[2], 7);
}
function mineGr(data, trace) {
  const size = 512, mask = size - 1, lr = 2654435761 >>> 0, hr = 2246822519 >>> 0;
  const e = [1779033703, 3144134277 >>> 0, 1013904242, 2773480762 >>> 0];
  for (const b of data) { e[0] = (e[0] + b) >>> 0; e[0] = rotl32(e[0], 7); mixRound(e); }
  if (trace) trace.push("tras bytes: " + e.join(","));
  for (let i = 0; i < 8; i++) mixRound(e);
  if (trace) trace.push("tras 8 rondas: " + e.join(","));
  const table = new Array(size);
  for (let i = 0; i < size; i++) { mixRound(e); table[i] = (e[0] ^ e[2]) >>> 0; }
  if (trace) trace.push("tabla[0..3]: " + table.slice(0, 4).join(",") + " tabla[511]=" + table[511]);
  for (let r = 0; r < 2; r++) for (let s = 0; s < size; s++) {
    let c = (table[s] + table[table[s] & mask]) >>> 0;
    c = rotl32(c, 13);
    c = (c ^ Math.imul(s + 1 < size ? table[s + 1] : 0, lr)) >>> 0;
    table[s] = c; e[0] = (e[0] ^ c) >>> 0; mixRound(e);
    if (trace && r === 0 && s < 3) trace.push(`ronda0 s=${s}: e=${e.join(",")}`);
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

const B = await bundle();
const inputs = ["a", "ab", "abc", "2e59e31ae6d7bdecd4eea8e7d99b15e3:0", "2e59e31ae6d7bdecd4eea8e7d99b15e3:180455"];
for (const s of inputs) {
  const u = new Uint8Array([...s].map((c) => c.charCodeAt(0) & 255));
  const a = [...B.gr(u)];
  const trace = [];
  const b = [...mineGr(u, trace)];
  console.log(JSON.stringify(s.slice(0, 42)), "igual:", a.every((x, i) => x === b[i]));
  if (!a.every((x, i) => x === b[i])) {
    console.log("  bundle:", a.map((x) => x.toString(16).padStart(8, "0")).join(" "));
    console.log("  mio   :", b.map((x) => x.toString(16).padStart(8, "0")).join(" "));
    trace.forEach((t) => console.log("   -", t));
  }
}