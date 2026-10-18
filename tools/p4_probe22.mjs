const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const home=(await g("https://animecube.live/")).text
// card around /anime/ link with img
const i=home.indexOf('href="/anime/sword-of-coming?from=home"')
console.log("card ctx:",home.slice(i-600,i+300).replace(/\s+/g," "))
console.log("\nog metas:")
const det=(await g("https://animecube.live/anime/sword-of-coming")).text
for(const m of det.matchAll(/<meta[^>]+(?:property|name)="(og:[a-z:]+|description|keywords)"[^>]+content="([^"]{0,200})"/gi))console.log(" ",m[1],"=",m[2].slice(0,120))
// how many unique /anime/ links in home
console.log("\nanime links in home:",[...new Set([...home.matchAll(/href="\/anime\/([a-z0-9-]+)(\?[^"]*)?"/g)].map(m=>m[1]))].length)
// sitemap series count
const sm=(await g("https://animecube.live/sitemap.xml")).text
console.log("sitemap anime urls:",(sm.match(/<loc>https:\/\/animecube\.live\/anime\//g)||[]).length)
