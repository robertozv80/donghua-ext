const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}catch(e){return{status:-1,text:String(e)}}}
const home=(await g("https://animecube.live/")).text
const chunks=[...new Set([...home.matchAll(/\/_next\/static\/chunks\/[^"'\\]+\.js/g)].map(m=>m[0])),...new Set([..."https://animecube.live/_next/static/chunks/app/anime/%5Bslug%5D/page-1022788f1ca7c777.js"].map(m=>m))]
for(const c of chunks){
  const r=await g("https://animecube.live"+c)
  if(r.status!==200)continue
  const t=r.text
  if(/geo\.dailymotion|dailymotion\.com\/player|DM_/i.test(t)){
    const hits=[...t.matchAll(/.{80}(?:geo\.dailymotion|dailymotion\.com\/player|embed\/video).{160}/gi)]
    if(hits.length){console.log("===",c);hits.slice(0,6).forEach(h=>console.log("  ",h[0].replace(/\s+/g," ")))}
  }
}
