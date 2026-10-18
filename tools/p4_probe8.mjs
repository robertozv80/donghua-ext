const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,"Accept":"application/json",...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const t=(await g("https://animecube.live/_next/static/chunks/app/anime/%5Bslug%5D/page-1022788f1ca7c777.js")).text
// find function ew definition
const defs=[...t.matchAll(/(?:function ew\([^)]*\)\{|(?:const|let|var) ew=|(?:function|const|let|var) ev=|function ev\([^)]*\)\{)/g)]
console.log("defs idx:",defs.map(d=>d.index))
for(const d of defs.slice(0,4)){
  console.log("\n=== def at",d.index)
  console.log(t.slice(d.index,d.index+1400).replace(/\s+/g," ").slice(0,1300))
}
