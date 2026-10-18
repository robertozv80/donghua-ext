const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}catch(e){return{status:-1,text:String(e)}}}
const dmMeta=async(vid,ref)=>{
  const r=await g(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=${encodeURIComponent(ref)}&integration=inline`)
  if(r.status!==200)return{status:r.status}
  const j=JSON.parse(r.text)
  const q=Object.values(j.qualities||{})[0]
  return{status:r.status,title:(j.title||"").slice(0,40),q:Object.keys(j.qualities||{}),subs:j.subtitles?Object.keys(j.subtitles.data||{}):null,url:Array.isArray(q)?q[0]?.url?.slice(0,70):q?.url?.slice(0,70),err:j.error}
}
for(const ref of ["https://animecube.live/","https://animecube.live"]){
  console.log(ref,JSON.stringify(await dmMeta("x9r2zum",ref)))
}
// embed page probe
const emb=await g("https://www.dailymotion.com/embed/video/x9r2zum",{Referer:"https://animecube.live/"})
console.log("embed page:",emb.status,"len",emb.text.length,"| has player config:",/metadata|video\.mp4|m3u8/i.test(emb.text))
