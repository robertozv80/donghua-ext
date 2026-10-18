const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}catch(e){return{status:-1,text:String(e)}}}
import {webcrypto as c} from "node:crypto"
import {Buffer} from "node:buffer"
const hex=()=>Array.from(c.getRandomValues(new Uint8Array(16))).map(e=>e.toString(16).padStart(2,"0")).join("")
const dec=async(obf,ver,b64)=>{const key=await c.subtle.importKey("raw",new Uint8Array(await c.subtle.digest("SHA-256",new TextEncoder().encode(`${obf}|${ver}`))),{name:"AES-GCM"},false,["decrypt"]);const b=Buffer.from(b64,"base64");return JSON.parse(new TextDecoder().decode(await c.subtle.decrypt({name:"AES-GCM",iv:b.slice(0,12)},key,b.slice(12))))}
// RSC parse of anime data
const rscOf=async slug=>{
  const r=await g(`https://animecube.live/anime/${slug}?_rsc=1`,{RSC:"1"})
  const t=r.text
  // find anime data: episodes arrays
  const eps=[...t.matchAll(/"episodes":(\[\{"id":[\s\S]{0,200}?\}\])/g)]
  return {t,eps:eps.map(m=>m[1])}
}
const {t,eps}=await rscOf("battle-through-the-heavens")
console.log("episodes arrays:",eps.length)
eps.slice(0,3).forEach((e,i)=>console.log("  arr",i,JSON.parse(e).slice(0,2).map(x=>x.id).join(","),"...total",JSON.parse(e).length))
// primaryTabs/seasons structure
const tabs=[...t.matchAll(/"id":"(tab-[\w-]+)"/g)].map(m=>m[1])
console.log("tab ids:",[...new Set(tabs)].join(","))
const seasons=[...new Set([...t.matchAll(/"seasons?":"?([\w-]+)"?/g)].map(m=>m[1]))].slice(0,10)
console.log("season-ish:",seasons.join(","))
// registry
const obfR=hex()
const regR=await g("https://animecube.live/api/anime-sources-versions",{"X-Obf":obfR})
const regJ=JSON.parse(regR.text)
const reg=regJ.d?await dec(obfR,regR.headers.get("x-registry-etag"),regJ.d):regJ
const regS=reg.bySeason["battle-through-the-heavens"]
console.log("registry btth:",JSON.stringify(regS).slice(0,300))
// test sources for a few eps
const obf=hex()
const trySrc=async(seId,epN,ptId)=>{
  const ver=regS?.[ptId]?.[seId]
  if(!ver)return console.log(seId,"ep",epN,"no version for",ptId)
  const o=hex()
  const r=await g(`https://animecube.live/api/anime/battle-through-the-heavens/episode/battle-through-the-heavens-${seId}-ep-${epN}/sources?v=${encodeURIComponent(ver)}&primaryTabId=${encodeURIComponent(ptId)}&seasonId=${encodeURIComponent(seId)}`,{"X-Obf":o})
  if(r.status!==200)return console.log(seId,"ep",epN,r.status,r.text.slice(0,60))
  const out=await dec(o,ver,JSON.parse(r.text).d)
  console.log("sources",seId,"ep",epN,":",JSON.stringify(out.sources)?.slice(0,300))
  const dm=out.sources?.find(x=>x.platform==="dailymotion")
  if(dm){
    const md=await g(`https://www.dailymotion.com/player/metadata/video/${dm.videoId}?embedder=https%3A%2F%2Fanimecube.live%2F&integration=inline`)
    const j=JSON.parse(md.text)
    console.log("   DM",dm.videoId,"err:",j.error?.code,"| title:",(j.title||"").slice(0,30),"| q:",Object.keys(j.qualities||{}).length,"| subs:",j.subtitles?Object.keys(j.subtitles.data||{}).join(","):"none")
  }
}
await trySrc("tab-1",1,"primary-1")
await trySrc("tab-1",5,"primary-1")
await trySrc(Object.keys(regS["primary-1"]||{}).filter(k=>k!=="tab-1")[0]||"tab-1",1,"primary-1")
