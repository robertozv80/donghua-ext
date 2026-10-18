// Reproduce la lógica del fix de póster por ficha (AnimeGratis v24.15) en vivo:
// para varias fichas devuelve (a) lo que daba el selector viejo y (b) el nuevo.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
const M = "https://animegratis.net";
const g = async (u) => { const r = await fetch(u, { headers: { "User-Agent": UA } }); return { status: r.status, text: await r.text() }; };

const slugs = process.argv.slice(2);
for (const slug of slugs) {
  for (const kind of ["donghua", "anime"]) {
    const url = `${M}/${kind}/${slug}`;
    const r = await g(url);
    if (r.status !== 200) continue;
    const imgs = [...r.text.matchAll(/<img[^>]*>/g)].map((m) => m[0]);
    const attr = (tag, name) => (tag.match(new RegExp(`${name}="([^"]*)"`)) || [])[1] || "";
    // viejo: primer img[src*=cdn.animegratis]
    const viejo = imgs.map((t) => attr(t, "src")).find((s) => s.includes("cdn.animegratis")) || "(ninguno)";
    // nuevo: img[loading=eager][width=220] con alt no vacio y sin aria-hidden
    const cands = imgs.filter((t) => attr(t, "loading") === "eager" && attr(t, "width") === "220"
      && attr(t, "alt").trim() !== "" && !/aria-hidden/.test(t));
    const nuevo = cands.length ? attr(cands[0], "src") : "(ninguno)";
    const ok = nuevo !== "(ninguno)" && nuevo !== viejo;
    console.log(`${ok ? "OK " : "!! "} ${kind}/${slug}  poster=${attr(nuevo.split("/").pop() ? cands[0] || "" : "", "alt")}`);
    console.log(`     viejo: ${viejo.slice(0, 95)}`);
    console.log(`     nuevo: ${nuevo.slice(0, 95)}`);
    console.log(`     cands=${cands.length}`);
  }
}