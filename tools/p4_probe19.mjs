const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,h={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...h}});const sc=r.headers.getSetCookie?r.headers.getSetCookie():[];return{status:r.status,text:await r.text(),sc}}catch(e){return{status:-1,text:String(e),sc:[]}}}
;(async()=>{
  // step 1: hit geo player.html, collect cookies
  const r1=await g("https://geo.dailymotion.com/player.html?video=x9pegg6",{"Referer":"https://animecube.live/"})
  console.log("geo step:",r1.status,"cookies:",r1.sc.map(s=>s.split(";")[0]).join(" | "))
  const cookie=r1.sc.map(s=>s.split(";")[0]).join("; ")
  // step 2: metadata with cookies
  const r2=await g("https://www.dailymotion.com/player/metadata/video/x9pegg6?embedder=https%3A%2F%2Fanimecube.live%2F&integration=inline",{"Referer":"https://animecube.live/","Origin":"https://animecube.live",...(cookie?{"Cookie":cookie}:{})})
  const j=JSON.parse(r2.text)
  console.log("metadata w/ cookies:",r2.status,"err:",j.error?.code||"none","| q:",Object.keys(j.qualities||{}).length,"| subs:",j.subtitles?Object.keys(j.subtitles.data||{}).join(","):"none")
  // try luwan-style: sometimes needs ?video= param on metadata call
  const r3=await g("https://geo.dailymotion.com/player.html?video=xbiat66",{"Referer":"https://www.donghuazone.com/"})
  console.log("\ngeo generic dz:",r3.status)
  const r4=await g("https://geo.dailymotion.com/libs/dmplayer.js")
  console.log("libs/dmplayer.js:",r4.status,"len",r4.text.length)
})()
