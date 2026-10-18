const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u)=>{const r=await fetch(u,{headers:{"User-Agent":UA}});return{status:r.status,text:await r.text()}}
for (const u of ["https://animegratis.net/","https://animegratis.net/catalogo","https://animegratis.net/donghua"]) {
  const r=await g(u)
  const srcsets=[...r.text.matchAll(/<img[^>]*srcset="([^"]+)"[^>]*>/g)].map(m=>m[1])
  console.log(`\n### ${u} status=${r.status} len=${r.text.length} imgs=${(r.text.match(/<img/g)||[]).length} srcset=${srcsets.length}`)
  srcsets.slice(0,4).forEach(s=>console.log("   ", s.slice(0,260)))
  const imgs=[...r.text.matchAll(/<img[^>]{0,400}>/g)].map(m=>m[0])
  imgs.slice(0,3).forEach(s=>console.log("   IMG:", s.replace(/\s+/g," ").slice(0,260)))
}
