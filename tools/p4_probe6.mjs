const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,"Accept":"application/json",...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
for(const p of ["/api/anime/sword-of-coming/episode/sword-of-coming-tab-1-ep-1/sources","/api/anime/sword-of-coming/episode/tab-1-ep-1/sources"]){
  const r=await g("https://animecube.live"+p)
  console.log(p,"->",r.status,"len",r.text.length)
  if(r.status===200){console.log(r.text.slice(0,1500));break}
}
