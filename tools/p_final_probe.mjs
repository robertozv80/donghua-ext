const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}catch(e){return{status:-1,text:String(e)}}}
// ==== donghuazone: 3 episode pages + search
const dzEps=["https://www.donghuazone.com/2026/10/swallowed-star-episode-245-4k-indo-sub.html","https://www.donghuazone.com/2026/09/gu-an-episode-12-4k-multi-sub.html","https://www.donghuazone.com/2026/09/martial-master-episode-698-4k-multi-sub.html"]
for(const u of dzEps){
  const r=await g(u)
  if(r.status!==200){console.log("dz",u,r.status);continue}
  const btns=[...r.text.matchAll(/<button[^>]*onclick="changeServer\(this,\s*'([^']*)'\)"[^>]*>([^<]*)</g)].map(m=>`${m[2].trim()}=${m[1].slice(0,60)}`)
  const labels=[...new Set([...r.text.matchAll(/search\/label\/([^"'?\/]+)/g)].map(m=>decodeURIComponent(m[1])))]
  console.log("\ndz ep:",u.split("/").pop(),"| labels:",labels.join(","))
  console.log("  servers:",btns.join(" | ")||"(ninguno)")
  const dmv=[...new Set([...r.text.matchAll(/(?:geo\.dailymotion\.com\/player\/[a-z0-9]+\.html\?video=|dailymotion\.com\/embed\/video\/)([a-z0-9]+)/gi)].map(m=>m[1]))]
  console.log("  DM ids:",dmv.join(","))
}
const dzs=await g("https://www.donghuazone.com/search?q=swallowed")
console.log("\ndz search:",dzs.status,"posts:",(dzs.text.match(/post-outer-container/g)||[]).length)
console.log("  sample:",[...new Set([...dzs.text.matchAll(/href='(https:\/\/www\.donghuazone\.com\/\d{4}\/[^']+\.html)'/g)].map(m=>m[1]))].slice(0,3).join(" | "))
// ==== animexin: all mirror labels for 2 episodes
console.log("\n===== animexin")
const axEps=["https://animexin.dev/supreme-god-emperor-episode-644-indonesia-english-sub/","https://animexin.dev/gu-an-episode-12-indonesia-english-sub/"]
for(const u of axEps){
  const r=await g(u)
  const opts=[...r.text.matchAll(/<option value="([A-Za-z0-9+/=]{20,})"[^>]*>([^<]*)<\/option>/g)]
  console.log(u.split("/").pop(),"->",opts.map(o=>o[2].trim()).join(" | "))
}
// animexin series page: eplister episode list & genres
const ax=(await g("https://animexin.dev/anime/supreme-god-emperor/")).text
console.log("\nax genres:",[...new Set([...ax.matchAll(/genres\/([a-z-]+)\//g)].map(m=>m[1]))].slice(0,10).join(","))
console.log("ax ep count eplister:",(ax.match(/eplister/g)||[]).length,"| ep links:",[...new Set([...ax.matchAll(/href="(https:\/\/animexin\.dev\/[^"]*episode[^"]*)"/g)].map(m=>m[1]))].length)
// ==== lucifer pagination for sections
console.log("\n===== lucifer paginacion")
for(const u of ["https://luciferdonghua.org/anime/?status=completed&sub=&order=latest&page=2","https://luciferdonghua.org/anime/page/2/?status=completed&sub=&order=latest"]){
  const r=await g(u)
  console.log(u.slice(28),"->",r.status,"articles:",(r.text.match(/<article/gi)||[]).length)
}
const lm2=await g("https://lmanime.com/anime/page/2/?status=completed&order=latest")
console.log("lmanime completed page2 ->",lm2.status,"articles:",(lm2.text.match(/<article/gi)||[]).length)
