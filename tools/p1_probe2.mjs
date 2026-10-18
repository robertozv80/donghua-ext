const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const S="https://lmanime.com"
// 1) series page
const ser=await g(S+"/tiger-crane/")
console.log("series page:",ser.status,"len",ser.text.length)
const st=ser.text
console.log("ep links:",[...st.matchAll(/href="(https:\/\/lmanime\.com\/[^"]*episode[^"]*)"/gi)].map(m=>m[1]).slice(0,5).join(" || "))
console.log("breadcrumb:",[...st.matchAll(/breadcrumb[^>]*>.{0,200}?href="([^"]+)"/gi)].map(m=>m[1]).slice(0,4))
console.log("status/genres:",st.match(/<span class="(?:genre|status)[^"]*">[^<]*<\/span>/gi)?.slice(0,6))
console.log("genres links:",[...st.matchAll(/href="(https:\/\/lmanime\.com\/genre[s]?\/[a-z-]+\/)"/gi)].map(m=>m[1]).filter((v,i,a)=>a.indexOf(v)===i).slice(0,10).join(" "))
console.log("has eplister:",/eplister/.test(st),"| epl-num:",/epl-num/.test(st),"| bigcontent:",/bigcontent/.test(st))
// 2) episode page
const epUrl=st.match(/href="(https:\/\/lmanime\.com\/[^"]*episode[^"]*)"/)?.[1]
console.log("\nEP:",epUrl)
if(epUrl){
  const ep=await g(epUrl)
  const et=ep.text
  console.log("ep page:",ep.status,"len",et.length)
  // servers
  console.log("data-hash count:",(et.match(/data-hash/gi)||[]).length)
  console.log("server items:",[...et.matchAll(/<li[^>]+class="[^"]*(?:server|player-option)[^"]*"[^>]*>/gi)].slice(0,8).map(m=>m[0]))
  // any iframe
  console.log("iframes:",[...et.matchAll(/<iframe[^>]+src="([^"]+)"/gi)].map(m=>m[1]).slice(0,6).join(" || "))
  // token/api hints
  for(const kw of ["admin-ajax","doo_player_ajax","dt_player","options.data","ts_player","player-option","_tsputts","token","options"]){
    const c=(et.match(new RegExp(kw,"gi"))||[]).length
    if(c)console.log("  kw",kw,"x",c)
  }
  // find inline player config
  const m=et.match(/<script[^>]*>([^<]{0,3000}options[^<]{0,3000})<\/script>/i)
  console.log("options script snippet:",m?.[1]?.slice(0,500))
}
// 3) listing URLs
for(const u of [S+"/anime/?status=completed&order=latest",S+"/genres/action/",S+"/genres/martial-arts/",S+"/?s=tiger"]){
  const r=await g(u)
  const arts=(r.text.match(/<article/gi)||[]).length
  console.log("\nlisting",u,r.status,"articles:",arts)
  console.log("  sample:",[...r.text.matchAll(/href="(https:\/\/lmanime\.com\/[^"]+)"/gi)].map(m=>m[1]).filter(u=>!/\/(genre|episode)/.test(u)).slice(0,3).join(" || "))
  console.log("  pagination:",[...r.text.matchAll(/href="(https:\/\/lmanime\.com\/[^"]*(?:page|\?paged)[^"]*)"/gi)].map(m=>m[1]).slice(0,3).join(" | "))
}
