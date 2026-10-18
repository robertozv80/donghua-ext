const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
// ===== donghuazone server buttons
const et=(await g("https://www.donghuazone.com/2026/10/swallowed-star-episode-245-4k-indo-sub.html")).text
const btns=[...et.matchAll(/<button[^>]+id=["'](?:btn\d+)["'][^>]*>[\s\S]{0,200}?<\/button>|<button[^>]+class=["'][^"']*serverBtn[^"']*["'][^>]*>[\s\S]{0,300}?<\/button>/gi)].map(m=>m[0].replace(/\s+/g," "))
console.log("buttons:",btns.slice(0,10).join("\n  "))
// find all changeServer/window.onload urls and button ids mapping
console.log("\nonload area:",et.match(/window\.onload=function\(\)[\s\S]{0,400}/)?.[0]?.replace(/\s+/g," "))
// generic: all geo.dailymotion occurrences
console.log("\nall DM urls:",[...new Set([...et.matchAll(/https:\/\/geo\.dailymotion\.com\/player\/[a-z0-9]+\.html\?video=[a-z0-9]+/gi)].map(m=>m[0]))].join(" | "))
// onclick attributes
console.log("\nonclicks:",[...new Set([...et.matchAll(/onclick=["'][^"']*['"]/g)].map(m=>m[0]).filter(s=>s.includes("changeServer")))].slice(0,10).join("\n  "))
// ===== animecube RSC
const rsc=await g("https://animecube.live/anime/sword-of-coming?_rsc=1",{RSC:"1"})
console.log("\nanimecube RSC:",rsc.status,"len",rsc.text.length)
console.log("sample:",rsc.text.replace(/\s+/g," ").slice(0,400))
