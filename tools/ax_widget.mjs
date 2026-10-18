const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const html = await (await fetch("https://animexin.dev/genres/action/", { headers: { "User-Agent": UA } })).text();

// find nearest preceding opening tag with a class/id before each numscore
const idxs = [];
let i = -1;
while ((i = html.indexOf('class="numscore"', i + 1)) >= 0) idxs.push(i);
console.log("numscore blocks:", idxs.length);

// Look for the widget wrapper: walk up by locating enclosing <div ... class="...">
function wrapperBefore(pos) {
  const seg = html.slice(0, pos);
  const ms = [...seg.matchAll(/<div[^>]*class="([^"]+)"[^>]*>/g)];
  return ms.slice(-4).map((m) => m[1]);
}
for (const p of idxs.slice(0, 3)) {
  console.log("  near", p, "->", wrapperBefore(p).join(" || "));
  console.log("     ctx:", html.slice(p - 400, p + 60).replace(/\s+/g, " ").slice(-320));
}

// Identify list containers present
const lists = [...html.matchAll(/<div[^>]*class="([^"]*(?:wpop|list|widget|sidebar|excerpt|series)[^"]*)"[^>]*>/gi)].slice(0, 20).map((m) => m[1]);
console.log("\ncandidate containers:", [...new Set(lists)].join(", "));

// Does the page include a ranked list of series with numbers?
const ct = [...html.matchAll(/<div class="ctr">(\d+)<\/div>[\s\S]{0,400}?<a class="series" href="([^"]+)"/g)];
console.log("\nranked series entries:", ct.length);
ct.slice(0, 8).forEach((m) => console.log("   #" + m[1], m[2]));
