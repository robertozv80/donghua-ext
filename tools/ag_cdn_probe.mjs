const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const B="https://cdn.animegratis.net/donghua/qu-ni-de-dao/images"
const names=["cover.jpg","cover.webp","cover-large.webp","cover_big.webp","cover-1080.webp","cover_1080.webp","cover@2x.webp","cover-2x.webp","original.webp","original.jpg","poster.webp","cover-hires.webp","cover-full.webp","thumb.webp","cover-600.webp"]
for(const n of names){
  try{const r=await fetch(`${B}/${n}`,{method:"HEAD",headers:{"User-Agent":UA}});console.log(String(r.status).padEnd(4), String(r.headers.get("content-length")||"?").padStart(9), n, "|", r.headers.get("content-type"))}catch(e){console.log("ERR ",n,e.message)}
}
console.log("--- query params ---")
for(const q of ["?w=1200","?width=1200","?size=large","?quality=90"]){
  const r=await fetch(`${B}/cover.jpg${q}`,{method:"HEAD",headers:{"User-Agent":UA}})
  console.log(String(r.status).padEnd(4), String(r.headers.get("content-length")||"?").padStart(9), "cover.jpg"+q)
}
