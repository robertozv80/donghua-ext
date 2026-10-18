const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
// ===== donghuazone episode page
const ep=await g("https://www.donghuazone.com/2026/10/swallowed-star-episode-245-4k-indo-sub.html")
const et=ep.text
console.log("dz ep:",ep.status,"len",et.length)
console.log("iframes:",[...et.matchAll(/<iframe[^>]+src=["']([^"']+)["']/gi)].map(m=>m[1]).slice(0,8).join(" || "))
// mirrors/servers
const sel=et.match(/<select[^>]*class=["'][^"']*mirror[^"']*["'][^>]*>[\s\S]*?<\/select>/)?.[0]
if(sel)console.log("mirror options:",[...sel.matchAll(/<option[^>]*>([^<]*)</g)].map(m=>m[1].trim()).join(" | "))
// video urls
console.log("video urls:",[...new Set([...et.matchAll(/https?:\/\/[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*/gi)].map(m=>m[0]))].slice(0,6).map(s=>s.slice(0,110)).join("\n  "))
// subs
console.log("vtt/srt:",[...new Set([...et.matchAll(/https?:\/\/[^"'\s<>]+\.(?:vtt|srt)[^"'\s<>]*/gi)].map(m=>m[0]))].slice(0,5).join(" | "))
// labels
console.log("labels:",[...new Set([...et.matchAll(/href=["']https:\/\/www\.donghuazone\.com\/search\/label\/([^"'?\/]+)/g)].map(m=>decodeURIComponent(m[1])))].slice(0,20).join(" | "))
// breadcrumb series link
console.log("breadcrumb:",[...et.matchAll(/<a[^>]+href=["'](https:\/\/www\.donghuazone\.com\/(?!20\d\d)[^"']+)["'][^>]*>([^<]{3,60})</g)].slice(0,8).map(m=>m[2].trim()+"->"+m[1]).join(" || "))
// ===== label listing
for(const lab of ["Action","Adventure","Martial%20Arts"]){
  const r=await g(`https://www.donghuazone.com/search/label/${lab}`)
  console.log(`\nlabel ${lab}:`,r.status,"articles:",(r.text.match(/post-outer-container/g)||[]).length)
  console.log("  pager:",[...new Set([...r.text.matchAll(/href=["'](https:\/\/www\.donghuazone\.com\/search[^"']*updated-max[^"']*)["']/g)].map(m=>m[1]))].slice(0,2).map(decodeURIComponent).join(" | "))
}
// ===== animecube flight data
console.log("\n===== animecube flight")
const det=await g("https://animecube.live/anime/sword-of-coming")
const dt=det.text
const idxs=[...dt.matchAll(/episode/gi)].map(m=>m.index)
console.log("'episode' occurrences:",idxs.length)
if(idxs.length){const i=idxs[0];console.log("ctx:",dt.slice(i-150,i+250).replace(/\s+/g," "))}
// try common api paths
for(const p of ["/api/series/sword-of-coming","/api/anime/sword-of-coming","/api/episodes?series=sword-of-coming","/_next/data"]){
  const r=await g("https://animecube.live"+p)
  console.log("try",p,r.status,(r.text||"").length)
}
