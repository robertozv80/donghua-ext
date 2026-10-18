const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
// 1) lucifer DM geo metadata
const dm=await g("https://geo.dailymotion.com/player/x1h6z6.html?video=k7eomfV7KT568aL60CW",{Referer:"https://luciferdonghua.org/"})
console.log("dm geo html len:",dm.text.length)
const mid=dm.text.match(/"metadata":\{"url":"([^"]+)"/)?.[1]||dm.text.match(/https:\/\/www\.dailymotion\.com\/player\/metadata\/[^"']+/)?.[0]
console.log("metadata url:",mid)
if(mid){const j=await g(mid.replace(/&amp;/g,"&"));const meta=JSON.parse(j.text)
  console.log("  DM qualities:",Object.keys(meta.qualities||{}).join(","),"subs:",meta.subtitles?JSON.stringify(meta.subtitles).slice(0,300):"none")}
// 2) streamplay embed
const sp=await g("https://play.streamplay.co.in/embed/jOb7aL-SY3MA5ge",{Referer:"https://luciferdonghua.org/"})
console.log("\nstreamplay:",sp.status,"len",sp.text.length)
console.log("  has tracks:",/tracks|captions|\.vtt|\.srt/i.test(sp.text), "| has m3u8:",/m3u8/i.test(sp.text))
const src=(sp.text.match(/(?:file|source|src)["':\s=]+["'](https?:\/\/[^"']+)["']/gi)||[]).slice(0,5)
console.log("  sources:",src.join(" || ").slice(0,300))
// 3) animecube SPA
console.log("\n===== animecube")
const ac=await g("https://animecube.live/")
const act=ac.text
console.log("status",ac.status,"len",act.length)
console.log("scripts:",[...act.matchAll(/<script[^>]+src="([^"]+)"/gi)].map(m=>m[1]).slice(0,8).join(" || "))
console.log("next/nuxt hints:",["__NEXT_DATA__","__NUXT__","/api/","self.__next_f"].map(k=>k+":"+(act.includes(k)?1:0)).join(" "))
// look for API endpoints in html
console.log("api-ish:",[...new Set([...act.matchAll(/["'](\/api\/[^"']{2,60})["']/g)].map(m=>m[1]))].slice(0,12).join(" "))
// For You section: find item links
console.log("series links:",[...new Set([...act.matchAll(/href="(\/(?:anime|series|donghua)\/[^"]+)"/g)].map(m=>m[1]))].slice(0,10).join(" "))
console.log("detail-ish links:",[...new Set([...act.matchAll(/href="([^"]+)"[^>]*>(?=[^<]{0,80})/g)].map(m=>m[1]))].filter(u=>u&&!u.startsWith("#")&&!u.startsWith("//")&&u.split("/").length>2&&!/\.(css|js|png|svg|ico)/.test(u)).slice(0,15).join(" "))
