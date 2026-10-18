const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,"Accept-Language":"es-ES,es;q=0.9,en;q=0.5",...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const SITES={
 P1_lmanime:"https://lmanime.com/",
 P2_lucifer:"https://luciferdonghua.org/",
 P3_animexin:"https://animexin.dev/",
 P4_animecube:"https://animecube.live/",
 P5_donghuazone:"https://www.donghuazone.com/"
}
for(const [k,u] of Object.entries(SITES)){
  const r=await g(u)
  const t=r.text
  console.log("=====",k,u,"status",r.status,"len",t.length)
  if(r.status!==200){console.log("  BODY:",t.slice(0,200));continue}
  console.log("  title:",t.match(/<title>([^<]*)<\/title>/i)?.[1])
  // theme hints
  const themes=[]
  if(/dooplay/i.test(t))themes.push("dooplay")
  if(/madara|wp-manga|manga-subtitle/i.test(t))themes.push("madara")
  if(/admin-ajax|wp-content\/themes\/([a-z0-9_-]+)/i.test(t)){themes.push("wp-theme:"+([...t.matchAll(/wp-content\/themes\/([a-z0-9_-]+)/gi)].map(m=>m[1]).filter((v,i,a)=>a.indexOf(v)===i).join("|"))) }
  if(/tsukuyomi|blixbox|eplister|releases hothome/i.test(t))themes.push("tsukuyomi-like")
  console.log("  themes:",themes.join(" "))
  // main nav links
  const navs=[...t.matchAll(/<a[^>]+href="(https?:\/\/[^"]*|\/[^"]*)"[^>]*>([^<]{2,40})<\/a>/g)].slice(0,40)
  console.log("  nav sample:", navs.map(n=>n[2].trim()+" -> "+n[1]).filter(s=>!/^\s*$/.test(s)).slice(0,18).join(" || "))
  // article structure sample
  const arts=[...t.matchAll(/<(?:article|div)[^>]+class="([^"]*(?:item|post|card|box|shortstory|ml-item|listupd|page-item)[^"]*)"/gi)].slice(0,10)
  console.log("  card classes:", arts.map(a=>a[1]).filter((v,i,a)=>a.indexOf(v)===i).slice(0,8).join(" || "))
  // section headers
  const heads=[...t.matchAll(/<h[23][^>]*>([^<]{2,50})<\/h[23]>/gi)].map(m=>m[1].trim()).slice(0,15)
  console.log("  h2/h3:", heads.join(" | "))
}
