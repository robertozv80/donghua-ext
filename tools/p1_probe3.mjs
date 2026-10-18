const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const ep=await g("https://lmanime.com/tiger-crane-episode-24/")
const t=ep.text
// find the player container region
const idx=t.indexOf("dailymotion")
console.log("dm idx:",idx)
console.log("---- around dailymotion ----")
console.log(t.slice(idx-3000, idx+1500).replace(/\s+/g," "))
