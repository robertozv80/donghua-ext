const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u)=>{const r=await fetch(u,{headers:{"User-Agent":UA}});return{status:r.status,text:await r.text()}}
const urls=["https://animegratis.net/donghua","https://animegratis.net/directorio","https://animegratis.net/donghua/genero/accion","https://animegratis.net/donghua?q=drama","https://animegratis.net/anime"]
for(const u of urls){
  const r=await g(u)
  const attrs=new Set()
  for(const m of r.text.matchAll(/<img[^>]*>/g)){
    for(const a of m[0].matchAll(/([a-zA-Z-]+)="/g)) attrs.add(a[1])
  }
  const srcsets=[...r.text.matchAll(/<img[^>]*?srcset="([^"]+)"[^>]*>/g)].map(m=>m[1]).filter(s=>!s.includes("logo"))
  console.log(`\n### ${u} status=${r.status} len=${r.text.length} imgs=${(r.text.match(/<img/g)||[]).length} srcset(no-logo)=${srcsets.length}`)
  console.log("   attrs:", [...attrs].join(","))
  srcsets.slice(0,3).forEach(s=>console.log("    SS:", s.slice(0,300)))
  // Poster fields in SSR payload
  const posters=[...new Set([...r.text.matchAll(/"(poster|cover|thumb|image|cover_url|poster_url)"\s*:\s*"([^"]+)"/g)].map(m=>m[1]+"="+m[2]))]
  console.log("   campos poster:", posters.slice(0,4).join(" | "), " (total distintos", posters.length,")")
}
