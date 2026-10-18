const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,extra={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...extra}});return{status:r.status,text:await r.text(),headers:r.headers}}catch(e){return{status:-1,text:String(e)}}}
// mp4upload embed deeper
const mp=await g("https://www.mp4upload.com/embed-oqz3qlkn5nz3.html",{Referer:"https://lmanime.com/"})
console.log("mp4upload:",mp.status,"len",mp.text.length)
console.log("snippet:",mp.text.replace(/\s+/g," ").slice(0,700))
// animecube sources without X-Obf
const r=await g("https://animecube.live/api/anime/sword-of-coming/episode/sword-of-coming-tab-1-ep-1/sources?v=DTawsgdLtbkp&primaryTabId=primary-1&seasonId=tab-1",{Accept:"application/json"})
console.log("\nanimecube no-X-Obf:",r.status,r.text.slice(0,200))
