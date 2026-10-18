const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u)=>{const r=await fetch(u,{headers:{"User-Agent":UA}});return{status:r.status,text:await r.text()}}
const M="https://www.mundodonghua.com"
for (const slug of ["1st-kiss","apotheosis","against-the-gods"]) {
  const d=await g(`${M}/donghua/${slug}`)
  const ep=d.text.match(/href="([^"]*\/ver\/[^"]+)"/)?.[1]
  if(!ep){console.log(slug,"sin ep");continue}
  const e=await g(M+ep)
  const subs=[...e.text.matchAll(/https?:\/\/[^\s"'<>]+\.(?:srt|vtt|ass|sub)/gi)].map(m=>m[0])
  console.log(slug, ep, "epstatus",e.status, "| .srt/.vtt en HTML:", subs.length, subs.slice(0,5).join(" "))
  console.log("   menciona 'subtitulo'?", /subtitul/i.test(e.text), "| 'caption'?", /caption/i.test(e.text))
}
