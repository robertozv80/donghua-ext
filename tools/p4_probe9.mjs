const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
import {webcrypto as c} from "node:crypto"
import {Buffer} from "node:buffer"
const g=async(u,extra={})=>{const r=await fetch(u,{headers:{"User-Agent":UA,"Accept":"application/json",...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}
const hex=n=>Array.from(c.getRandomValues(new Uint8Array(16))).map(e=>e.toString(16).padStart(2,"0")).join("")
const sha256=async s=>new Uint8Array(await c.subtle.digest("SHA-256",new TextEncoder().encode(s)))
const decrypt=async(obf,verkey,b64)=>{
  const key=await c.subtle.importKey("raw",await sha256(`${obf}|${verkey}`),{name:"AES-GCM"},false,["decrypt"])
  const bin=Buffer.from(b64,"base64")
  const iv=bin.slice(0,12),rest=bin.slice(12)
  return JSON.parse(new TextDecoder().decode(await c.subtle.decrypt({name:"AES-GCM",iv},key,rest)))
}
;(async()=>{
  const obf=hex()
  const reg=await g("https://animecube.live/api/anime-sources-versions",{"X-Obf":obf})
  console.log("registry:",reg.status,"etag:",reg.headers.get("x-registry-etag")||reg.headers.get("X-Registry-ETag"))
  let data
  if(reg.text.startsWith("{")&&reg.text.includes("bySeason"))data=JSON.parse(reg.text)
  else{const etag=reg.headers.get("x-registry-etag");data=await decrypt(obf,etag,JSON.parse(reg.text).d)}
  console.log("registry keys:",Object.keys(data.bySeason||{}).slice(0,20).join(","))
  const soc=data.bySeason["sword-of-coming"]
  if(!soc){console.log("no sword-of-coming in registry; sample:",JSON.stringify(data.bySeason).slice(0,400));return}
  console.log("sword-of-coming registry:",JSON.stringify(soc).slice(0,300))
  // structure bySeason[slug][?][?]
  for(const [k,v] of Object.entries(soc)){
    console.log("  ",k,"->",typeof v==="object"?JSON.stringify(v).slice(0,200):v)
  }
  // call sources: need seasonId + primaryTabId + version
  // guess: outer key = seasonId ("primary-1"), inner = tabId ("tab-1")
  const seasonId=Object.keys(soc)[0], tabId=Object.keys(soc[seasonId])[0], version=soc[seasonId][tabId]
  console.log("params: primaryTabId="+seasonId+" seasonId="+tabId+" version="+version)
  const obf2=hex()
  const url=`https://animecube.live/api/anime/sword-of-coming/episode/sword-of-coming-${tabId}-ep-1/sources?v=${encodeURIComponent(version)}&primaryTabId=${encodeURIComponent(seasonId)}&seasonId=${encodeURIComponent(tabId)}`
  const r=await g(url,{"X-Obf":obf2})
  console.log("\nsources:",r.status,"len",r.text.length)
  if(r.status===200){
    const j=JSON.parse(r.text)
    console.log("keys:",Object.keys(j).join(","))
    if(j.d){
      const out=await decrypt(obf2,version,j.d)
      console.log("sources:",JSON.stringify(out).slice(0,1200))
    } else console.log("raw:",r.text.slice(0,800))
  } else console.log(r.text.slice(0,300))
})()
