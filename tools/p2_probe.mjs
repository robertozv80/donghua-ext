const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const epLinks=(t,host)=>[...t.matchAll(new RegExp(`href="(https?://[^"]*(?:episode|ep-)[^"]*)"`, 'gi'))].map(m=>m[1])
for(const S of ["https://luciferdonghua.org/anime/swallowed-star-season-4/","https://animexin.dev/anime/supreme-god-emperor/"]){
  console.log("=====",S)
  const ser=await g(S)
  const st=ser.text
  console.log("series:",ser.status,"len",st.length,"eplister:",/eplister/.test(st))
  const eps=[...new Set([...st.matchAll(/href="([^"]*(?:episode|-ep)[^"]*)"/gi)].map(m=>m[1]))].filter(u=>new URL(u,S).host===new URL(S).host).slice(0,4)
  console.log("ep sample:",eps.slice(0,3).join(" || "))
  if(!eps.length)continue
  const ep=await g(eps[0])
  const et=ep.text
  console.log("EP",eps[0],"->",ep.status,"len",et.length)
  console.log("  mirror select:",/class="mirror"/.test(et))
  const opts=[...et.matchAll(/<option value="([^"]*)"[^>]*>([^<]*)<\/option>/g)]
  console.log("  options:",opts.slice(0,10).map(o=>o[2].trim()+"->"+o[1]).join(" || "))
  console.log("  iframes:",[...et.matchAll(/<iframe[^>]+src="([^"]+)"/gi)].map(m=>m[1]).slice(0,4).join(" | "))
  console.log("  data-hash:",(et.match(/data-hash/gi)||[]).length,"| player-option:",(et.match(/player-option/gi)||[]).length)
  // try first mirror option if exists
  const m1=opts.find(o=>o[1]&&o[1].length>5)
  if(m1){const r2=await g(m1[1]);console.log("  mirror",m1[2].trim(),"->",r2.status,"iframes:",[...r2.text.matchAll(/<iframe[^>]+src="([^"]+)"/gi)].map(m=>m[1]).slice(0,2).join(" | "))}
}
// listing URLs for both
for(const u of ["https://luciferdonghua.org/anime/?status=completed&sub=&order=latest","https://luciferdonghua.org/anime/?genre%5B%5D=action&genre%5B%5D=adventure&genre%5B%5D=martial-arts&status=&type=&order=latest","https://luciferdonghua.org/genres/action/","https://animexin.dev/genres/action/","https://animexin.dev/anime/?status=&type=&order=update"]){
  const r=await g(u)
  console.log("\nlisting",u,"->",r.status,"articles:",(r.text.match(/<article/gi)||[]).length)
  console.log("  pages:",[...r.text.matchAll(/href="(https?:\/\/[^"]*(?:page\/\d+|paged=\d+)[^"]*)"/gi)].map(m=>m[1]).slice(0,2).join(" | "))
}
