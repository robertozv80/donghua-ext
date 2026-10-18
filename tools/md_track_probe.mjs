const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u)=>{const r=await fetch(u,{headers:{"User-Agent":UA}});return{status:r.status,text:await r.text()}}
const M="https://www.mundodonghua.com"
const e=await g(M+"/ver/1st-kiss/16")
console.log("<track> en HTML:", (e.text.match(/<track[^>]*>/gi)||[]).length, JSON.stringify((e.text.match(/<track[^>]*>/gi)||[]).slice(0,3)))
const idx=e.text.search(/<video/i)
console.log("contexto video:", JSON.stringify(e.text.slice(Math.max(0,idx-300), idx+700)))
