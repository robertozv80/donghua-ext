import fs from "fs";
const h = fs.readFileSync(new URL("./byse_pow.js", import.meta.url), "utf8");
console.log("len", h.length);
for (const kw of ["key_parts", "keyParts", "difficulty", "zero", "0000", "nonce", "challenge", "solve", "substring", "digest", "SHA", "WebCrypto"]) {
  const n = h.split(kw).length - 1;
  console.log(`${kw} -> ${n}`);
}
// dump interesting contexts
const ctxs = [];
for (const kw of ["key_parts", "keyParts", "difficulty"]) {
  let i = -1, c = 0;
  while (((i = h.indexOf(kw, i + 1)) >= 0) && c < 3) {
    c++;
    ctxs.push(`-- ${kw} @${i}:\n` + h.slice(Math.max(0, i - 260), i + 320).replace(/\s+/g, " ").slice(0, 560));
  }
}
ctxs.forEach(x => console.log(x, "\n"));
