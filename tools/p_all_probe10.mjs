const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}
const dmMeta=async(vid,ref)=>{
  const r=await g(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(ref)}&integration=inline`)
  if(r.status!==200)return{status:r.status}
  const j=JSON.parse(r.text)
  return{status:r.status,title:j.title,qualities:Object.keys(j.qualities||{}),subs:j.subtitles?Object.keys(j.subtitles.data||{}):null,firstSub:j.subtitles&&Object.values(j.subtitles.data||{})[0]?.urls?.[0]?.slice(0,80),m3u8:Object.values(j.qualities||{}).flat()[0]?.url?.slice(0,80)}
}
const refs={
 animecube:["x9r2zum","https://animecube.live/"],
 lucifer:["k7eomfV7KT568aL60CW","https://luciferdonghua.org/"],
 animexin:["k2lZ4MBdo4u8VpKzOBk","https://animexin.dev/"],
}
for(const [k,[vid,ref]] of Object.entries(refs)){
  console.log(k,vid,JSON.stringify(await dmMeta(vid,ref)))
}
// animecube search api?
const home=(await g("https://animecube.live/")).text
const chunks=[...new Set([...home.matchAll(/\/_next\/static\/chunks\/[^"'\\]+\.js/g)].map(m=>m[0]))]
const apis=new Set()
for(const c of chunks){
  const r=await g("https://animecube.live"+c)
  if(r.status!==200)continue
  for(const m of r.text.matchAll(/["'`](\/api\/[a-zA-Z0-9\/_?&=${}\[\].-]{2,80})["'`]/g))apis.add(m[1])
}
console.log("\nanimecube home APIs:",[...apis].join(" | "))
// home sections in SSR
console.log("\nanimecube sections:",[...home.matchAll(/<h2[^>]*>([^<]{2,40})<\/h2>/g)].map(m=>m[1].trim()).join(" | "))
