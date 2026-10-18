import fs from "fs";
const h = fs.readFileSync(new URL("./byse_pow.js", import.meta.url), "utf8");
for (const kw of ["nonce", "challenge", "digest", "SHA", "0000"]) {
  let i = -1, c = 0;
  while (((i = h.indexOf(kw, i + 1)) >= 0) && c < 3) {
    c++;
    console.log(`-- ${kw} @${i}:`, h.slice(Math.max(0, i - 200), i + 300).replace(/\s+/g, " ").slice(0, 480));
    console.log();
  }
}
