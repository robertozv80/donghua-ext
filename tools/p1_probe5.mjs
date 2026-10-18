const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const t=(await g("https://lmanime.com/tiger-crane-episode-24/")).text
const sel=t.match(/<select class="mirror"[\s\S]*?<\/select>/)?.[0]
const opts=[...sel.matchAll(/<option value="([^"]*)"[^>]*>([^<]*)<\/option>/g)]
console.log("mirrors:")
opts.forEach(o=>console.log("  ",o[2].trim(),"->",o[1]))
// fetch each mirror page and see the iframe
for(const [lang,u] of opts.slice(1,9).map(o=>[o[2].trim(),o[1]])){
  if(!u)continue
  const r=await g(u)
  const ifr=[...r.text.matchAll(/<iframe[^>]+src="([^"]+)"/gi)].map(m=>m[1])
  console.log("\nmirror",lang,u,"status",r.status,"iframes:",ifr.slice(0,3).join(" | "))
  // also look for jwplayer/playerjs config with tracks
  const tracks=[...r.text.matchAll(/https?:\/\/[^"'\s<>]+\.(?:vtt|srt)[^"'\s<>]*/gi)].map(m=>m[0])
  if(tracks.length)console.log("   VTT/SRT in page:",tracks.slice(0,4).join(" | "))
  if(/tracks\s*[:=]/i.test(r.text))console.log("   has tracks config:",r.text.match(/tracks\s*[:=][^;]{0,200}/i)?.[0]?.slice(0,200))
}
