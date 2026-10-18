const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}catch(e){return{status:-1,text:String(e)}}}
import {webcrypto as c} from "node:crypto"
import {Buffer} from "node:buffer"
const hex=()=>Array.from(c.getRandomValues(new Uint8Array(16))).map(e=>e.toString(16).padStart(2,"0")).join("")
const dec=async(obf,ver,b64)=>{const key=await c.subtle.importKey("raw",new Uint8Array(await c.subtle.digest("SHA-256",new TextEncoder().encode(`${obf}|${ver}`))),{name:"AES-GCM"},false,["decrypt"]);const b=Buffer.from(b64,"base64");return JSON.parse(new TextDecoder().decode(await c.subtle.decrypt({name:"AES-GCM",iv:b.slice(0,12)},key,b.slice(12))))}
const t=(await g("https://animecube.live/anime/battle-through-the-heavens?_rsc=1",{RSC:"1"})).text
const epArr=JSON.parse(t.match(/"episodes":(\[\{"id":[\s\S]{0,200}?\}\])/)[1])
console.log("episode obj:",JSON.stringify(epArr[0]))
// find primaryTabs structure
const ptCtx=t.match(/"primaryTabs":[\s\S]{0,600}/)?.[0]
console.log("\nprimaryTabs ctx:",ptCtx?.replace(/\s+/g," ").slice(0,500))
// sources with exact id
const obfR=hex()
const regR=await g("https://animecube.live/api/anime-sources-versions",{"X-Obf":obfR})
const regJ=JSON.parse(regR.text)
const reg=regJ.d?await dec(obfR,regR.headers.get("x-registry-etag"),regJ.d):regJ
const regS=reg.bySeason["battle-through-the-heavens"]
const ptId="primary-1",seId="tab-1",ver=regS[ptId][seId]
const o=hex()
const epId=epArr[0].id
const r=await g(`https://animecube.live/api/anime/battle-through-the-heavens/episode/${epId}/sources?v=${encodeURIComponent(ver)}&primaryTabId=${ptId}&seasonId=${seId}`,{"X-Obf":o})
console.log("\nsources:",r.status,r.text.slice(0,100))
if(r.status===200){const out=await dec(o,ver,JSON.parse(r.text).d)
  console.log("out:",JSON.stringify(out).slice(0,500))
  for(const s of out.sources||[]){
    const md=await g(`https://www.dailymotion.com/player/metadata/video/${s.videoId}?embedder=https%3A%2F%2Fanimecube.live%2F&integration=inline`)
    const j=JSON.parse(md.text)
    console.log("  DM",s.videoId,"goodSub:",s.goodSub,"err:",j.error?.code||"ok","title:",(j.title||"").slice(0,40),"q:",Object.keys(j.qualities||{}).length,"subs:",j.subtitles?Object.keys(j.subtitles.data||{}).join(","):"none")
  }
}
