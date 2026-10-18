import fs from "fs";
const files = ["byse_video.js", "byse.js"].map(f => [f, fs.readFileSync(new URL(`./${f}`, import.meta.url), "utf8")]);
for (const [name, h] of files) {
  console.log("\n##########", name, "len", h.length);
  for (const kw of ["playback", "decrypt", "AesGcm", "aes_gcm", "GCM", "deriveKey", "PBKDF2", "scrypt", "hashKey", "sha256", "SHA-256", "importKey"]) {
    let i = -1, c = 0;
    while (((i = h.indexOf(kw, i + 1)) >= 0) && c < 4) {
      c++;
      console.log(`-- ${kw} @${i}:`, h.slice(Math.max(0, i - 150), i + 260).replace(/\s+/g, " ").slice(0, 380));
    }
    if (c === 0) console.log(`-- ${kw}: (sin usos)`);
  }
}
