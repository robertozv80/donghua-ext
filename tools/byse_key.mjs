import fs from "fs";
const h = fs.readFileSync(new URL("./byse_video.js", import.meta.url), "utf8");
// Find the GCM decrypt helpers around the first "aes/gcm" mention
const i = h.indexOf("aes/gcm: invalid ghash tag");
console.log("=== alrededor de la implementación AES:");
console.log(h.slice(Math.max(0, i - 3000), i + 500).replace(/\n/g, " "));
