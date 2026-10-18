const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
for(const lab of ["Action","Adventure","Martial Arts","Martial%20Arts"]){
  for(const kind of ["default","summary"]){
    const r=await g(`https://www.donghuazone.com/feeds/posts/${kind}/-/${lab}?alt=json&max-results=10`)
    let n=0
    try{n=(JSON.parse(r.text).feed.entry||[]).length}catch(e){}
    console.log(`feed/${kind}/-/${lab}:`,r.status,"entries:",n)
  }
}
// label page real posts (main area, not sidebar)
const lp=await g("https://www.donghuazone.com/search/label/Action?max-results=20")
// main posts container starts after 'blog-posts hfeed'
const main=lp.text.slice(lp.text.indexOf("blog-posts hfeed"), lp.text.indexOf("blog-pager")+3000)
const postLinks=[...new Set([...main.matchAll(/href='(https:\/\/www\.donghuazone\.com\/\d{4}\/[^']+\.html)'/g)].map(m=>m[1]))]
console.log("\nlabel Action main posts:",postLinks.length, "| pager:",/blog-pager/i.test(main))
// pagination via feed start-index works?
const p2=await g("https://www.donghuazone.com/feeds/posts/default?alt=json&max-results=5&start-index=6")
console.log("\nfeed start-index=6 entries:",(JSON.parse(p2.text).feed.entry||[]).length, JSON.parse(p2.text).feed.entry?.[0]?.title?.$t)
// series label feed: "Swallowed star"
const sf=await g("https://www.donghuazone.com/feeds/posts/default/-/Swallowed%20star?alt=json&max-results=25")
const sj=JSON.parse(sf.text)
console.log("\nfeed 'Swallowed star':",(sj.feed.entry||[]).length,"entries")
for(const e of (sj.feed.entry||[]).slice(0,4))console.log("  -",e.title.$t)
