// Despaqueta los eval() (multi-línea) y extrae embeds bysekoze.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

const EPISODES = [
  "https://www.mundodonghua.com/ver/against-the-gods/30",
  "https://www.mundodonghua.com/ver/apotheosis/52",
  "https://www.mundodonghua.com/ver/black-and-white-warriors/12",
];

async function get(url) {
  const r = await fetch(url, { headers: { "User-Agent": UA, Referer: "https://www.mundodonghua.com/" }, redirect: "manual" });
  return { status: r.status, text: await r.text() };
}

// Captura un eval(...) completo con paréntesis equilibrados desde `start`.
function captureEval(text, start) {
  // start apunta a 'eval('
  const openIdx = text.indexOf("(", start);
  let depth = 0;
  let i = openIdx;
  let inStr = null;
  while (i < text.length) {
    const ch = text[i];
    if (inStr) {
      if (ch === "\\") { i += 2; continue; }
      if (ch === inStr) inStr = null;
      i++;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === "`") { inStr = ch; i++; continue; }
    if (ch === "(") depth++;
    else if (ch === ")") {
      depth--;
      if (depth === 0) return text.slice(start, i + 1);
    }
    i++;
  }
  return null;
}

function unpack(packed) {
  const inner = packed.replace(/^eval\(/, "").replace(/\)\s*$/, "");
  const fn = eval("(" + inner + ")");
  const out = fn();
  return typeof out === "string" ? out : String(out);
}

for (const ep of EPISODES) {
  const r = await get(ep);
  const codes = new Set();
  let unpacked = 0, blocks = 0;
  let idx = 0;
  while (true) {
    const i = r.text.indexOf("eval(function", idx);
    if (i < 0) break;
    blocks++;
    const packed = captureEval(r.text, i);
    if (packed) {
      try {
        const u = unpack(packed);
        unpacked++;
        for (const m of u.matchAll(/bysekoze\.com\/[ev]\/([a-zA-Z0-9]+)/g)) codes.add(m[1]);
      } catch (e) { /* ignore */ }
    }
    idx = i + 10;
  }
  console.log(`${ep} -> status=${r.status} blocks=${blocks} unpacked=${unpacked} bysekoze=${JSON.stringify([...codes])}`);
}
