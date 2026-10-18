const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,h={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...h}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const tests=[
 ["animecube","https://geo.dailymotion.com/player.html?video=x9q56ag",{"Referer":"https://animecube.live/","Origin":"https://animecube.live"}],
 ["animecube2","https://geo.dailymotion.com/player.html?video=x9pegg6",{"Referer":"https://animecube.live/","Origin":"https://animecube.live"}],
 ["donghuazone","https://geo.dailymotion.com/player/x110cm.html?video=xbiat66",{"Referer":"https://www.donghuazone.com/","Origin":"https://www.donghuazone.com"}],
 ["lucifer","https://geo.dailymotion.com/player/x1h6z6.html?video=k7eomfV7KT568aL60CW",{"Referer":"https://luciferdonghua.org/","Origin":"https://luciferdonghua.org"}],
]
for(const [k,u,h] of tests){
  const r=await g(u,h)
  console.log(k,"->",r.status,"len",r.text.length, r.status!==200?("body:"+r.text.slice(0,60)):"")
  if(r.status===200){
    const j=r.text.match(/"error":\{"[^}]+\}/)?.[0]
    console.log("  err field:",j||"none")
  }
}
// metadata with Origin too
const md=await g("https://www.dailymotion.com/player/metadata/video/x9q56ag?embedder=https%3A%2F%2Fanimecube.live%2F&integration=inline",{"Referer":"https://animecube.live/","Origin":"https://animecube.live"})
console.log("\nmetadata x9q56ag con Origin:",md.status,JSON.parse(md.text).error?.code||"sin error")
const md2=await g("https://www.dailymotion.com/player/metadata/video/x9pegg6?embedder=https%3A%2F%2Fanimecube.live%2F&integration=inline",{"Referer":"https://animecube.live/","Origin":"https://animecube.live"})
console.log("metadata x9pegg6 con Origin:",md2.status,JSON.parse(md2.text).error?.code||"sin error")
