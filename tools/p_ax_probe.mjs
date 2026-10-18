const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}catch(e){return{status:-1,text:String(e)}}}
const et=(await g("https://animexin.dev/supreme-god-emperor-episode-644-indonesia-english-sub/")).text
const opts=[...et.matchAll(/<option value="([A-Za-z0-9+/=]{20,})"[^>]*>([^<]*)<\/option>/g)]
for(const o of opts){
  const label=o[2].trim()
  if(!/All Sub/.test(label))continue
  let iframe="";try{iframe=Buffer.from(o[1],"base64").toString("utf8")}catch(e){}
  const src=iframe.match(/src=["']([^"']+)["']/)?.[1]||"(sin src)"
  console.log(label,"->",src.slice(0,110))
  if(/dailymotion/i.test(src)){
    const vid=src.match(/video=([a-z0-9]+)/i)?.[1]||src.match(/embed\/video\/([a-z0-9]+)/i)?.[1]
    if(vid){
      const md=await g(`https://www.dailymotion.com/player/metadata/video/${vid}?embedder=https%3A%2F%2Fanimexin.dev%2F&integration=inline`,{Referer:"https://animexin.dev/"})
      const j=JSON.parse(md.text)
      console.log("   DM",vid,"err:",j.error?.code||"OK","| title:",(j.title||"").slice(0,40),"| subs:",j.subtitles?Object.keys(j.subtitles.data||{}).join(","):"none")
      const q=Object.values(j.qualities||{})[0]
      if(q)console.log("   m3u8:",(Array.isArray(q)?q[0]:q).url?.slice(0,90))
    }
  } else {
    const r2=await g(src.startsWith("//")?"https:"+src:src,{Referer:"https://animexin.dev/"})
    console.log("   embed fetch:",r2.status,"len",r2.text.length,"| m3u8:",/m3u8/i.test(r2.text),"| vtt/srt:",/\.(vtt|srt)/i.test(r2.text),"| eval packed:",/eval\(function\(p,a,c,k,e/.test(r2.text))
    const tracks=[...new Set([...r2.text.matchAll(/https?:\/\/[^"'\s<>]+\.(?:vtt|srt)[^"'\s<>]*/gi)].map(m=>m[0]))].slice(0,3)
    if(tracks.length)console.log("   subs:",tracks.join(" | ").slice(0,200))
  }
}
