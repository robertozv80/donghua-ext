const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
// 1) label con espacio usando +
const f=await g("https://www.donghuazone.com/feeds/posts/default/-/Martial+Arts?alt=json&max-results=10")
let n=0;try{n=(JSON.parse(f.text).feed.entry||[]).length}catch(e){}
console.log("feed Martial+Arts:",f.status,"entries:",n)
// 2) search q=
const s=await g("https://www.donghuazone.com/feeds/posts/default?alt=json&q=swallowed&max-results=8")
let sj=null;try{sj=JSON.parse(s.text)}catch(e){}
console.log("feed q=swallowed:",s.status,"entries:",(sj?.feed?.entry||[]).length)
for(const e of (sj?.feed?.entry||[]).slice(0,3))console.log("  -",e.title.$t)
// 3) rel=tag labels de la ficha serie
const ser=await g("https://www.donghuazone.com/2025/05/swallowed-star.html")
const tags=[...new Set([...ser.text.matchAll(/<a[^>]+rel='tag'[^>]+href='([^']+)'/g)].map(m=>m[1]))]
console.log("\nficha rel=tag labels:",tags.map(decodeURIComponent).join(" | "))
const tagNames=[...ser.text.matchAll(/<a[^>]+rel='tag'[^>]*>([^<]+)<\/a>/g)].map(m=>m[1].trim())
console.log("nombres:",tagNames.join(" | "))
// titulo de la ficha
console.log("ficha title:",ser.text.match(/<title>([^<]*)<\/title>/)?.[1])
