const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
const ep=await g("https://lmanime.com/tiger-crane-episode-24/")
const t=ep.text
const m=t.match(/https:\/\/www\.dailymotion\.com\/embed\/video\/[a-z0-9]+[^"'\s]*/i)
console.log("embed:",m?.[0])
const i=t.indexOf(m?.[0]||"zzz")
console.log("---- context ----")
console.log(t.slice(i-2500, i+800).replace(/\s+/g," "))
