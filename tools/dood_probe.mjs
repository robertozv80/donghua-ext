const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

async function get(u, ref, extra = {}) {
  const r = await fetch(u, {
    headers: { "User-Agent": UA, ...(ref ? { Referer: ref } : {}), ...extra },
    redirect: "manual",
  });
  const text = await r.text().catch(() => "");
  return { status: r.status, text, headers: r.headers, loc: r.headers.get("location") };
}

const ref = "https://animexin.dev/";
const id = process.argv[2] || "z3lacan00d6kmr2mcsng8fzfogrcmw0t";
const embed = `https://dooood.com/e/${id}`;

console.log("== 1) GET embed", embed);
const e = await get(embed, ref);
console.log("   status:", e.status, "len:", e.text.length);
console.log("   snippet head:", e.text.slice(0, 600).replace(/\s+/g, " "));

const keyPatterns = ["pass_md5", "/pass_md5/", "dsplayer", "eval(function", "makePlay", "getElementById('nor'", "window.location", "$.get", "$.post", "token", "expiry"];
for (const p of keyPatterns) {
  const i = e.text.indexOf(p);
  if (i >= 0) console.log(`   [${p}] ->`, e.text.slice(Math.max(0, i - 150), i + 220).replace(/\s+/g, " "));
}

// find pass_md5 path
const m = e.text.match(/pass_md5\/[A-Za-z0-9_\-/]+/);
console.log("   pass_md5 path:", m ? m[0] : "NOT FOUND");

if (m) {
  const path = m[0].replace(/^pass_md5\//, "");
  console.log("== 2) GET /pass_md5/" + path);
  const p = await get("https://dooood.com/pass_md5/" + path, embed, { "X-Requested-With": "XMLHttpRequest" });
  console.log("   status:", p.status, "body:", JSON.stringify(p.text.slice(0, 300)));

  if (p.status === 200 && p.text.length > 5 && !p.text.startsWith("<")) {
    const token = p.text.trim();
    const rnd = Math.random().toString(36).slice(2, 12) + Math.random().toString(36).slice(2, 12).slice(0, 0);
    const mp4 = `${token}${"abcdefghij".slice(0,10)}?token=${path}&expiry=${Date.now()}`;
    console.log("== 3) candidate mp4:", mp4);
  }
}

// Also check for the newer dooood "video" API patterns
const apiIdx = e.text.search(/\/api\/|getVideo|sources|m3u8|\.mp4/);
if (apiIdx >= 0) console.log("   api hint:", e.text.slice(Math.max(0, apiIdx - 120), apiIdx + 200).replace(/\s+/g, " "));

// Try known alternate hosts
for (const host of ["https://dsvplay.com", "https://dood.to", "https://playmogo.com", "https://dooood.com", "https://d000d.com"]) {
  try {
    const r = await get(`${host}/e/${id}`, ref);
    console.log(`   host ${host}: ${r.status} len=${r.text.length} passmd5=${/pass_md5/.test(r.text)}`);
  } catch (err) {
    console.log(`   host ${host}: ERR ${err.message}`);
  }
}
