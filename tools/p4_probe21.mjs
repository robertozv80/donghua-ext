const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}catch(e){return{status:-1,text:String(e)}}}
import {webcrypto as c} from "node:crypto"
import {Buffer} from "node:buffer"
const hex=()=>Array.from(c.getRandomValues(new Uint8Array(16))).map(e=>e.toString(16).padStart(2,"0")).join("")
const dec=async(obf,ver,b64)=>{const key=await c.subtle.importKey("raw",new Uint8Array(await c.subtle.digest("SHA-256",new TextEncoder().encode(`${obf}|${ver}`))),{name:"AES-GCM"},false,["decrypt"]);const b=Buffer.from(b64,"base64");return JSON.parse(new TextDecoder().decode(await c.subtle.decrypt({name:"AES-GCM",iv:b.slice(0,12)},key,b.slice(12))))}
const obfR=hex()
const regR=await g("https://animecube.live/api/anime-sources-versions",{"X-Obf":obfR})
const regJ=JSON.parse(regR.text)
const reg=regJ.d?await dec(obfR,regR.headers.get("x-registry-etag"),regJ.d):regJ
const slug="swallowed-star"
const regS=reg.bySeason[slug]
console.log("tabs:",JSON.stringify(regS).slice(0,400))
for(const [ptId,seasons] of Object.entries(regS)){
  for(const [seId,ver] of Object.entries(seasons)){
    // need an episode id for this season; use last known ep number per season? use ep-1 first
    const o=hex()
    const r=await g(`https://animecube.live/api/anime/${slug}/episode/${slug}-${seId}-ep-186/sources?v=${encodeURIComponent(ver)}&primaryTabId=${ptId}&seasonId=${seId}`,{"X-Obf":o})
    if(r.status!==200){console.log(ptId,seId,r.status,r.text.slice(0,50));continue}
    const out=await dec(o,ver,JSON.parse(r.text).d)
    const srcs=out.sources||[]
    console.log(ptId,seId,"->",srcs.map(s=>`${s.platform}:${s.videoId}`).join(" ")||"(vacio)")
    for(const s of srcs.filter(x=>x.platform==="dailymotion").slice(0,1)){
      const md=await g(`https://www.dailymotion.com/player/metadata/video/${s.videoId}?embedder=https%3A%2F%2Fanimecube.live%2F&integration=inline`,{"Referer":"https://animecube.live/"})
      const j=JSON.parse(md.text)
      console.log("   DM",s.videoId,"err:",j.error?.code||"OK","| q:",Object.keys(j.qualities||{}).length,"| title:",(j.title||"").slice(0,30))
    }
  }
}
