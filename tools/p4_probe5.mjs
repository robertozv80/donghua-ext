const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const dt=(await g("https://animecube.live/anime/sword-of-coming")).text
const chunks=[...new Set([...dt.matchAll(/\/_next\/static\/chunks\/[^"'\\]+\.js/g)].map(m=>m[0]))]
for(const c of chunks){
  const r=await g("https://animecube.live"+c)
  if(r.status!==200)continue
  const t=r.text
  if(t.includes("/api/anime")){
    console.log("=== chunk",c)
    let i=0,n=0
    while((i=t.indexOf("/api/anime",i))>=0&&n<6){
      console.log("  ctx:",t.slice(Math.max(0,i-260),i+260).replace(/\s+/g," ").slice(0,520))
      i+=10;n++
    }
    // also sources-versions ctx
    const j=t.indexOf("anime-sources-versions")
    if(j>=0)console.log("  sources ctx:",t.slice(Math.max(0,j-300),j+300).replace(/\s+/g," ").slice(0,600))
  }
}
// try endpoints
for(const p of ["/api/anime-sources-versions","/api/anime/sword-of-coming","/api/anime/sword-of-coming/episodes","/api/anime/sword-of-coming/tab-1/ep-1"]){
  const r=await g("https://animecube.live"+p,{Accept:"application/json"})
  console.log("\ntry",p,"->",r.status,"len",r.text.length,"|",r.text.slice(0,160).replace(/\s+/g," "))
}
