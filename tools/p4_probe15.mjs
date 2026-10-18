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
for(const slug of ["renegade-immortal","swallowed-star","lord-of-mysteries","ling-cage","tales-of-herding-gods","the-demon-hunter"]){
  const regS=reg.bySeason[slug]
  if(!regS){console.log(slug,"sin registry");continue}
  const ptId=Object.keys(regS)[0],seId=Object.keys(regS[ptId])[0],ver=regS[ptId][seId]
  // read episodes from rsc
  const t=(await g(`https://animecube.live/anime/${slug}?_rsc=1`,{RSC:"1"})).text
  const key=t.indexOf('"episodes":[{"id":')
  let eps=[]
  if(key>=0){let s=key+11,depth=0,i=s
    for(;i<t.length;i++){const ch=t[i];if(ch==="[")depth++;else if(ch==="]"){depth--;if(depth===0)break}}
    try{eps=JSON.parse(t.slice(s,i+1))}catch(e){console.log(slug,"parse err")}}
  const ep=eps[Math.min(eps.length-1,2)]
  if(!ep){console.log(slug,"sin episodios en rsc");continue}
  const o=hex()
  const r=await g(`https://animecube.live/api/anime/${slug}/episode/${ep.id}/sources?v=${encodeURIComponent(ver)}&primaryTabId=${ptId}&seasonId=${seId}`,{"X-Obf":o})
  if(r.status!==200){console.log(slug,ep.id,r.status,r.text.slice(0,60));continue}
  const out=await dec(o,ver,JSON.parse(r.text).d)
  const srcs=out.sources||[]
  console.log(slug,"| ep",ep.numberDisplay,"| sources:",srcs.map(s=>`${s.platform}:${s.videoId}${s.goodSub?"[goodSub]":""}`).join(" ")||"(vacio)")
  for(const s of srcs.filter(x=>x.platform==="dailymotion").slice(0,2)){
    const md=await g(`https://www.dailymotion.com/player/metadata/video/${s.videoId}?embedder=https%3A%2F%2Fanimecube.live%2F&integration=inline`)
    const j=JSON.parse(md.text)
    console.log("   DM",s.videoId,"err:",j.error?.code||"ok","| title:",(j.title||"").slice(0,30),"| q:",Object.keys(j.qualities||{}).length,"| subs:",j.subtitles?Object.keys(j.subtitles.data||{}).join(","):"none")
  }
}
