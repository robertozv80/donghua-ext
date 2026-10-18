const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u)=>{const r=await fetch(u,{headers:{"User-Agent":UA}});return{status:r.status,text:await r.text()}}
const urls=["https://animegratis.net/donghua/qu-ni-de-dao","https://animegratis.net/donghua/shiguang-dailiren-iii","https://animegratis.net/anime/meitantei-precure"]
for(const u of urls){
  const r=await g(u)
  const all=[...r.text.matchAll(/<img[^>]*>/g)].map(m=>m[0])
  const withSS=all.filter(t=>/srcset=/.test(t))
  console.log(`\n### ${u} status=${r.status} imgs=${all.length} con srcset=${withSS.length}`)
  withSS.slice(0,5).forEach(t=>console.log("   ", t.replace(/\s+/g," ").slice(0,300)))
  // medidas de los CDN de las imagenes
  const cdn=[...new Set(all.map(t=>(t.match(/src="(https:[^"]+)"/)||[])[1]).filter(Boolean))]
  console.log("   ejemplo CDN:", cdn.slice(0,3).join("\n              "))
}
