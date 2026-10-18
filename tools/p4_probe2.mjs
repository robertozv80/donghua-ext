const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
// DM metadata for donghuazone video
const r=await g("https://www.dailymotion.com/player/metadata/video/xbiat66?embedder=https%3A%2F%2Fwww.donghuazone.com%2F&integration=inline")
console.log("DM metadata:",r.status)
if(r.status===200){const j=JSON.parse(r.text)
  console.log("title:",j.title,"| qualities:",Object.keys(j.qualities||{}).join(","))
  console.log("subtitles:",j.subtitles?JSON.stringify(j.subtitles).slice(0,600):"none")
  console.log("error:",j.error)
}
// animecube RSC content search
const rsc=(await g("https://animecube.live/anime/sword-of-coming?_rsc=1",{RSC:"1"})).text
for(const kw of ["episode","m3u8","player","episodes","season","tvId","series_id","id\":"]){
  const c=(rsc.match(new RegExp(kw,"gi"))||[]).length
  console.log("rsc kw",kw,":",c)
}
const i=rsc.indexOf("m3u8")
if(i>0)console.log("m3u8 ctx:",rsc.slice(i-200,i+300).replace(/\s+/g," "))
// look for numbered episode rows
const epRows=[...rsc.matchAll(/"episodeNumber":(\d+)/g)].slice(0,5).map(m=>m[1])
console.log("episodeNumber rows:",epRows.join(","))
const ctx=rsc.match(/"episodes":\[[\s\S]{0,400}/)?.[0]
console.log("episodes arr ctx:",ctx?.slice(0,400)?.replace(/\s+/g," "))
