const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const f=await g("https://www.donghuazone.com/feeds/posts/default/-/Martial%20Arts?alt=json&max-results=25")
const j=JSON.parse(f.text)
console.log("totalResults:",j.feed.openSearch$totalResults?.$t,"| entries:",(j.feed.entry||[]).length)
// HTML label page: find main post list
const lp=await g("https://www.donghuazone.com/search/label/Martial%20Arts?max-results=20")
console.log("\nlabel page:",lp.status,"len",lp.text.length)
// locate all .html links with context
const links=[...lp.text.matchAll(/href='(https:\/\/www\.donghuazone\.com\/\d{4}\/[^']+\.html)'/g)].map(m=>m[1])
console.log("post links:",[...new Set(links)].length)
console.log([...new Set(links)].slice(0,5).join("\n"))
// check where posts are (class around first link)
const i=lp.text.search(/https:\/\/www\.donghuazone\.com\/\d{4}\/[a-z0-9-]+\.html/)
if(i>0)console.log("\nctx:",lp.text.slice(Math.max(0,i-400),i+100).replace(/\s+/g," ").slice(-400))
// blog pager
console.log("\npager:",lp.text.match(/<a[^>]+class='blog-pager-older-link'[^>]+href='([^']+)'/)?.[1]?.slice(0,120)||"(sin pager)")
// Also: what does an actual martial-arts episode label look like? search feed
const s=await g("https://www.donghuazone.com/feeds/posts/default?alt=json&q=martial+master&max-results=2")
const sj=JSON.parse(s.text)
for(const e of (sj.feed.entry||[]).slice(0,1))console.log("\n'martial master' ep labels:",(e.category||[]).map(c=>c.term).join(","))
