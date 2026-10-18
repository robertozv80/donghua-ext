const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,"Referer":"https://animecube.live/",...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}catch(e){return{status:-1,text:String(e)}}}
for(const vid of ["x9q56ag","x9pegg6","x9nm9gm"]){
  const r=await g(`https://geo.dailymotion.com/player.html?video=${vid}`)
  console.log("\n== player.html",vid,r.status,"len",r.text.length)
  const meta=r.text.match(/metadata[^"]*url[^"]*":"([^"]+)"/i)?.[1]
  const m=r.text.match(/https:\/\/www\.dailymotion\.com\/player\/metadata\/[a-z0-9]+\/video\/[a-z0-9]+[^"']*/i)?.[0]
  console.log("  metadata url:",m?.slice(0,120)||meta?.slice(0,120))
  if(m){const md=await g(m.replace(/&amp;/g,"&"))
    console.log("  metadata fetch:",md.status)
    if(md.status===200){const j=JSON.parse(md.text);console.log("  err:",j.error?.code||"none","| q:",Object.keys(j.qualities||{}).length,"| subs:",j.subtitles?Object.keys(j.subtitles.data||{}).join(","):"none")}
  }
  // Also check error in player html
  console.log("  has error text:",/DM0\d\d|private|not available/i.test(r.text)?r.text.match(/DM0\d\d|"[^"]*(?:private|not available)[^"]*"/i)?.[0]:"no")
}
