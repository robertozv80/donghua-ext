const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
// feed latest
const f=await g("https://www.donghuazone.com/feeds/posts/default?alt=json&max-results=5")
console.log("feed latest:",f.status,"len",f.text.length)
if(f.status===200){
  try{
    const j=JSON.parse(f.text)
    const entries=j.feed.entry||[]
    console.log("entries:",entries.length)
    for(const e of entries.slice(0,3)){
      const link=e.link.find(l=>l.rel==="alternate")?.href
      console.log(" -",e.title.$t,"|",link)
      console.log("   labels:",(e.category||[]).map(c=>c.term).join(","))
      console.log("   thumb:",e.media$thumbnail?.url?.slice(0,80))
      console.log("   content len:",(e.content?.$t||"").length)
    }
  }catch(e){console.log("parse err",e.message)}
}
// label feed
const fl=await g("https://www.donghuazone.com/feeds/posts/default/-/Martial%20Arts?alt=json&max-results=5")
console.log("\nfeed Martial Arts:",fl.status,"len",fl.text.length,"entries:",fl.status===200?((JSON.parse(fl.text).feed.entry||[]).length):"-")
// series ficha content: does it list episodes?
const ser=await g("https://www.donghuazone.com/2025/05/swallowed-star.html")
console.log("\nficha swallowed-star:",ser.status,"len",ser.text.length)
const epLinks=[...new Set([...ser.text.matchAll(/href=['"](https:\/\/www\.donghuazone\.com\/\d{4}\/[^']+\.html)['"]/g)].map(m=>m[1]))]
console.log("internal post links:",epLinks.length,"| sample:",epLinks.slice(0,4).join(" | "))
const contentIdx=ser.text.indexOf("post-body")
const seg=ser.text.slice(contentIdx,contentIdx+3000)
console.log("post-body ep links:",[...new Set([...seg.matchAll(/href=['"]([^'"]+\.html)['"]/g)].map(m=>m[1]))].slice(0,6).join(" | "))
