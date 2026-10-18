const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
// ===== animecube detail
console.log("===== animecube detail")
const det=await g("https://animecube.live/anime/sword-of-coming")
const dt=det.text
console.log("detail:",det.status,"len",dt.length)
console.log("ep links:",[...new Set([...dt.matchAll(/href="(\/[^"]*(?:episode|ep\/|watch|season)[^"]*)"/gi)].map(m=>m[1]))].slice(0,8).join(" || "))
console.log("genres links:",[...new Set([...dt.matchAll(/href="(\/(?:genre|category|genres)[^"]*)"/gi)].map(m=>m[1]))].slice(0,10).join(" "))
// rsc payload hints
const rsc=dt.match(/self\.__next_f\.push\(\[1,"([^"]{100,400})"/)
console.log("next_f snippet:",rsc?.[1]?.slice(0,300))
// ===== P5 donghuazone home structure
console.log("\n===== donghuazone")
const dz=await g("https://www.donghuazone.com/")
const zt=dz.text
console.log("status",dz.status,"len",zt.length)
console.log("postbody:",/postbody/.test(zt),"| listupd:",/listupd/.test(zt),"| eplister:",/eplister/.test(zt))
console.log("article sample:",[...new Set([...zt.matchAll(/<(?:article|div)[^>]+class="([^"]*(?:latestpost|post|bs|episode)[^"]*)"/gi)].map(m=>m[1]))].slice(0,8).join(" || "))
const la=zt.indexOf("Latest Episode")
console.log("around Latest Episode:",zt.slice(la,la+1200).replace(/\s+/g," ").slice(0,1000))
