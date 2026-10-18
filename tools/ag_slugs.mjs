const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u)=>{const r=await fetch(u,{headers:{"User-Agent":UA}});return{status:r.status,text:await r.text()}}
const r=await g("https://animegratis.net/")
const a=[...new Set([...r.text.matchAll(/href="\/anime\/([a-z0-9-]+)"/g)].map(m=>m[1]))]
const d=[...new Set([...r.text.matchAll(/href="\/donghua\/([a-z0-9-]+)"/g)].map(m=>m[1]))]
console.log("anime:",a.slice(0,10).join(" "))
console.log("donghua:",d.slice(0,6).join(" "))
