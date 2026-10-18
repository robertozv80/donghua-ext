const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
for(const [k,u] of Object.entries({lucifer:"https://luciferdonghua.org/anime/swallowed-star-season-4/",lmanime:"https://lmanime.com/tiger-crane/",animexin:"https://animexin.dev/anime/supreme-god-emperor/"})){
  const t=await g(u)
  const li=t.text.match(/<li class="[\s\S]{0,600}?eplister[\s\S]{0,900}?<\/li>/)?.[0]
  const one=[...t.text.matchAll(/<li[^>]*class="[^"]*wp-manga-chapter[^"]*"[^>]*>[\s\S]{0,500}?<\/li>/g)].slice(0,1)
  const epLi=[...t.text.matchAll(/<li[^>]*>[\s\S]{0,80}?<a[^>]+href="(https:[^"]*episode[^"]*)"[^>]*>[\s\S]{0,300}?<\/a>[\s\S]{0,50}?<\/li>/gi)].slice(0,1)
  console.log("\n==",k)
  console.log("epl-num:",/epl-num/.test(t.text),"| epl-title:",/epl-title/.test(t.text),"| epl-date:",/epl-date/.test(t.text))
  // sample li html
  const i=t.text.indexOf('eplister')
  const seg=t.text.slice(i, i+1500)
  const firstLi=seg.match(/<li[\s\S]{0,700}?<\/li>/)?.[0]
  console.log("first li:",firstLi?.replace(/\s+/g," ").slice(0,600))
}
