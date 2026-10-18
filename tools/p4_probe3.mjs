const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const rsc=(await g("https://animecube.live/anime/sword-of-coming?_rsc=1",{RSC:"1"})).text
// context around 'player'
let idx=0;let n=0
for(const m of rsc.matchAll(/player/gi)){console.log("player ctx:",rsc.slice(Math.max(0,m.index-150),m.index+150).replace(/\s+/g," ").slice(0,260));if(++n>2)break}
// find hrefs in rsc
console.log("\nrsc hrefs:",[...new Set([...rsc.matchAll(/"href":"([^"]{2,80})"/g)].map(m=>m[1]))].slice(0,20).join(" | "))
// watch pages?
console.log("\nrsc watch:",[...new Set([...rsc.matchAll(/\/watch[^"\\\s]{0,60}/g)].map(m=>m[0]))].slice(0,10).join(" | "))
// sitemap
for(const u of ["https://animecube.live/sitemap.xml","https://animecube.live/robots.txt"]){
  const r2=await g(u)
  console.log("\n",u,r2.status)
  console.log(r2.text.slice(0,900))
}
// genre page probe
for(const p of ["/browse","/browse?genre=action","/genre/action","/genres","/catalog","/browse?genres=action"]){
  const r3=await g("https://animecube.live"+p)
  console.log("\ntry",p,r3.status,"len",r3.text.length)
}
