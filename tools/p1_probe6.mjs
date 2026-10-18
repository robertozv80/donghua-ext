const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const vids={es:"k1ljltgmSLSHisCDwTG",en:"k3LoYmACJCnlXFCDwTE"}
for(const [lang,id] of Object.entries(vids)){
  const r=await g(`https://www.dailymotion.com/player/metadata/video/${id}?embedder=https%3A%2F%2Flmanime.com%2F&integration=inline&api=postMessage`)
  console.log("== DM",lang,id,"status",r.status)
  if(r.status!==200){console.log(r.text.slice(0,200));continue}
  const j=JSON.parse(r.text)
  console.log("  title:",j.title,"| duration:",j.duration,"| qualities:",Object.keys(j.qualities||{}).join(","))
  const q1=Object.values(j.qualities||{})[0]
  if(q1)console.log("  sample url:",(Array.isArray(q1)?q1[0]:q1).url?.slice(0,90))
  const subs=j.subtitles
  if(subs){console.log("  SUBTITLES:",Object.keys(subs).join(","),"; data keys:",Object.keys(subs.data||{}).join(","))}
  else console.log("  subtitles: none")
  console.log("  error?",j.error)
}
// also probe one ok.ru embed
const ok=await g("https://ok.ru/videoembed/9015345154599?nochat=1",{Referer:"https://lmanime.com/"})
console.log("\nok.ru status",ok.status,"len",ok.text.length)
const ops=ok.text.match(/data-options="([^"]{0,200})/)
console.log("data-options present:",!!ops)
if(/"subtitleFiles"/.test(ok.text))console.log("ok.ru HAS subtitleFiles!")
const movies=ok.text.match(/"movieName":"([^"]{0,60})/)
console.log("movieName:",movies?.[1])
// mp4upload
const mp=await g("https://www.mp4upload.com/embed-oqz3qlkn5nz3.html",{Referer:"https://lmanime.com/"})
console.log("\nmp4upload status",mp.status,"len",mp.text.length)
console.log("has eval(packed):",/eval\(function\(p,a,c,k,e/.test(mp.text))
const mp4=mp.text.match(/(?:https?:)?\/\/[^"'\s]*\.mp4[^"'\s]*/)
console.log("direct mp4 in html:",mp4?.[0]?.slice(0,100))
