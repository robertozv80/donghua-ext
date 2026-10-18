const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}catch(e){return{status:-1,text:String(e)}}}
const dmMeta=async(vid)=>{
  const r=await g(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=https%3A%2F%2Fluciferdonghua.org%2F&integration=inline`)
  if(r.status!==200)return{status:r.status}
  const j=JSON.parse(r.text)
  const q=Object.values(j.qualities||{})[0]
  return{status:r.status,title:(j.title||"").slice(0,40),subs:j.subtitles?Object.keys(j.subtitles.data||{}):null,url:Array.isArray(q)?q[0]?.url?.slice(0,60):q?.url?.slice(0,60)}
}
// lucifer: sample 3 episode pages, extract DM video ids from base64 iframes
const luciferEps=["https://luciferdonghua.org/swallowed-star-season-4-episode-159-engsub/","https://luciferdonghua.org/gu-an-episode-12-english-subtitle/","https://luciferdonghua.org/martial-master-episode-697-english-subtitles/"]
for(const u of luciferEps){
  const r=await g(u)
  if(r.status!==200){console.log("lucifer",u,"->",r.status);continue}
  const opts=[...r.text.matchAll(/<option value="([A-Za-z0-9+/=]{20,})"[^>]*>([^<]*)<\/option>/g)]
  console.log("\nlucifer",u.split("/").pop())
  for(const o of opts.slice(0,6)){
    let iframe=""
    try{iframe=Buffer.from(o[1],"base64").toString("utf8")}catch(e){}
    const dmv=iframe.match(/(?:dailymotion\.com\/(?:embed\/video|player\/[a-z0-9]+\.html\?video=)|geo\.dailymotion\.com\/player\/[a-z0-9]+\.html\?video=)([a-z0-9]+)/i)?.[1]
    const rum=iframe.match(/rumble\.com\/embed\/([a-z0-9]+)/i)?.[1]
    const okr=iframe.match(/ok\.ru\/videoembed\/(\d+)/i)?.[1]
    const sp=iframe.match(/streamplay[^"']+/i)?.[0]
    const info=dmv?JSON.stringify(await dmMeta(dmv)):(rum?"rumble:"+rum:(okr?"ok:"+okr:(sp?"streamplay":"other:"+iframe.slice(0,60))))
    console.log("  ",o[2].trim(),"->",info)
  }
}
// animecube: more sources samples
console.log("\n===== animecube more sources")
import {webcrypto as c} from "node:crypto"
import {Buffer} from "node:buffer"
const hex=()=>Array.from(c.getRandomValues(new Uint8Array(16))).map(e=>e.toString(16).padStart(2,"0")).join("")
const dec=async(obf,ver,b64)=>{const key=await c.subtle.importKey("raw",new Uint8Array(await c.subtle.digest("SHA-256",new TextEncoder().encode(`${obf}|${ver}`))),{name:"AES-GCM"},false,["decrypt"]);const b=Buffer.from(b64,"base64");return JSON.parse(new TextDecoder().decode(await c.subtle.decrypt({name:"AES-GCM",iv:b.slice(0,12)},key,b.slice(12))))}
const obfReg=hex()
const regR=await g("https://animecube.live/api/anime-sources-versions",{"X-Obf":obfReg})
console.log("registry etag:",regR.headers.get("x-registry-etag"))
let regJ
try{regJ=JSON.parse(regR.text)}catch(e){console.log("registry parse fail",e);regJ={}}
let reg
if(regJ.d){const etag=regR.headers.get("x-registry-etag");reg=await dec(obfReg,etag,regJ.d)}
else reg=regJ
for(const slug of ["sword-of-coming","battle-through-the-heavens","renegade-immortal"]){
  const s=reg.bySeason[slug];if(!s){console.log(slug,"no registry");continue}
  const ptId=Object.keys(s)[0],seId=Object.keys(s[ptId])[0],ver=s[ptId][seId]
  const obf=hex()
  const r=await g(`https://animecube.live/api/anime/${slug}/episode/${slug}-${seId}-ep-1/sources?v=${ver}&primaryTabId=${ptId}&seasonId=${seId}`,{"X-Obf":obf})
  if(r.status!==200){console.log(slug,"sources",r.status,r.text.slice(0,80));continue}
  const out=await dec(obf,ver,JSON.parse(r.text).d)
  console.log(slug,seId,JSON.stringify(out).slice(0,400))
  const dm=out.sources?.find(x=>x.platform==="dailymotion")
  if(dm)console.log("   DM meta:",JSON.stringify(await dmMeta(dm.videoId)))
}
