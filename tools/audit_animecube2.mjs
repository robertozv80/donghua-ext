import crypto from "crypto";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const BASE = "https://animecube.live";
const hex = () => crypto.randomBytes(16).toString("hex");
function decrypt(b64, keyMaterial) {
  try {
    const data = Buffer.from(b64, "base64");
    const key = crypto.createHash("sha256").update(keyMaterial, "utf8").digest();
    const iv = data.subarray(0, 12);
    const d = new crypto.Decipheriv("aes-256-gcm", key, iv);
    d.setAuthTag(data.subarray(-16));
    return Buffer.concat([d.update(data.subarray(12, data.length - 16)), d.final()]).toString("utf8");
  } catch (e) { return null; }
}
(async () => {
  // Case with single tab-1: episode id must be "<slug>-<tabId>-ep-<N>"
  const cases = [
    { epId: "a-good-day-to-ascend-tab-1-ep-1", slug: "a-good-day-to-ascend", pt: "primary-1", se: "tab-1", ver: "n0MGVldCM4Xy" },
    { epId: "beyond-time-s-gaze-tab-1766889196697-ep-1", slug: "beyond-time-s-gaze", pt: "primary-1", se: "tab-1766889196697", ver: "ztReKNU7ftKW" },
  ];
  for (const c of cases) {
    const obf = hex();
    const u = `${BASE}/api/anime/${c.slug}/episode/${c.epId}/sources?v=${encodeURIComponent(c.ver)}&primaryTabId=${encodeURIComponent(c.pt)}&seasonId=${encodeURIComponent(c.se)}`;
    const r = await fetch(u, { headers: { "User-Agent": UA, Accept: "application/json", "X-Obf": obf } });
    const text = await r.text();
    console.log("==", c.slug, r.status, "len", text.length);
    let payload = text;
    if (!text.includes("\"sources\"")) {
      const d = text.split("\"d\":\"")[1]?.split("\"")[0] || "";
      payload = decrypt(d, `${obf}|${c.ver}`) || "";
      console.log("   encrypted d len:", d.length, "decrypted:", payload ? payload.length : null);
    }
    if (payload) {
      console.log("   payload:", payload.slice(0, 600));
      // dm extraction attempt
      for (const m of payload.matchAll(/"platform":"([\w-]+)"[^{}]*?"videoId":"([\w-]+)"/g)) {
        console.log("   source:", m[1], m[2]);
        if (m[1] === "dailymotion") {
          const vid = m[2];
          const vid2 = payload.match(/"privateId":"([\w-]+)"[^{}]*?"videoId":"[\w-]+"/);
          const idc = vid2?.[1] || vid;
          const mu = `https://www.dailymotion.com/player/metadata/video/${idc}?embedder=${encodeURIComponent(BASE)}&integration=inline`;
          const mr = await fetch(mu, { headers: { "User-Agent": UA, Referer: "https://www.dailymotion.com/embed/video/" + idc } });
          const j = await mr.json().catch(() => null);
          if (!j) console.log("      DM meta: no json");
          else {
            if (j.error) console.log("      DM meta ERROR:", JSON.stringify(j.error));
            else {
              const q = j.qualities || {};
              console.log("      DM qualities keys:", Object.keys(q).join(","), "| subs?", !!(j.subtitles || j.subtitleRenditions));
            }
          }
        }
      }
    }
  }
})();
