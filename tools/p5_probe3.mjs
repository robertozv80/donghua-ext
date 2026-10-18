const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
// ===== donghuazone player data
const et=(await g("https://www.donghuazone.com/2026/10/swallowed-star-episode-245-4k-indo-sub.html")).text
for(const kw of ["player","embed","iframe","m3u8","mp4","watch","server","jwplayer","vidmoly","streamwish","filemoon","dailymotion","ok.ru","rumble","odysee","drivesafe","blogger.com/video","video_url","data-src=\"https://www.blogger.com"]){
  const c=(et.match(new RegExp(kw.replace(/[.*+?^${}()|[\]\\]/g,"\\$&"),"gi"))||[]).length
  if(c)console.log("kw",kw,"x",c)
}
// find script with video config
const scr=[...et.matchAll(/<script[^>]*>([\s\S]{200,8000}?)<\/script>/g)]
for(const s of scr){
  if(/player|video|embed|iframe/i.test(s[1])&&!/google-analytics|adsbygoogle|gtag/i.test(s[1])){
    console.log("\n--- script snippet ---")
    console.log(s[1].slice(0,700).replace(/\s+/g," "))
    break
  }
}
// ===== animecube episodes in flight
const dt=(await g("https://animecube.live/anime/sword-of-coming")).text
const esc=[...dt.matchAll(/\\\\\"episodes\\\\\"/g)].map(m=>m.index)
console.log("\nescaped episodes idx:",esc.length)
if(esc.length){
  const i=esc[0]
  console.log("ctx:",dt.slice(i-200,i+600).replace(/\s+/g," ").slice(0,700))
}
// find play/watch links pattern in flight
const pl=[...dt.matchAll(/\/(?:watch|play)\/[^"\\]{2,60}/g)].map(m=>m[0])
console.log("watch/play links:",[...new Set(pl)].slice(0,10).join(" "))
