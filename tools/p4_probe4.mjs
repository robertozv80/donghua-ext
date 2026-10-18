const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const dt=(await g("https://animecube.live/anime/sword-of-coming")).text
const chunks=[...new Set([...dt.matchAll(/\/_next\/static\/chunks\/[^"'\\]+\.js/g)].map(m=>m[0]))]
console.log("chunks:",chunks.length)
const apis=new Set(),vids=new Set()
for(const c of chunks){
  const r=await g("https://animecube.live"+c)
  if(r.status!==200)continue
  for(const m of r.text.matchAll(/["'`](\/api\/[a-zA-Z0-9\/_?&=${}\[\].-]{2,80})["'`]/g))apis.add(m[1])
  for(const m of r.text.matchAll(/["'`](https?:\/\/[^"'`]{5,100}?\.(?:m3u8|mp4)[^"'`]{0,40})["'`]/g))vids.add(m[1])
  for(const m of r.text.matchAll(/["'`](\/(?:api\/)?(?:watch|play|stream|video|episode)[a-zA-Z0-9\/_?&=-]{0,60})["'`]/g))apis.add(m[1])
}
console.log("\nAPI paths:",[...apis].slice(0,40).join("\n  "))
console.log("\nvideo templates:",[...vids].slice(0,10).join("\n  "))
