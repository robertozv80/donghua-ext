const UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
const g=async(u,h={})=>{try{const r=await fetch(u,{headers:{"User-Agent":UA,...h}});return{status:r.status,text:await r.text()}}catch(e){return{status:-1,text:String(e)}}}
;(async()=>{
  const r=await g("https://geo.dailymotion.com/player/x110cm.html?video=x9pegg6",{"Referer":"https://animecube.live/"})
  console.log("status",r.status,"len",r.text.length)
  // search settings and metadata mentions
  const i=r.text.indexOf("metadata")
  console.log("metadata ctx:",r.text.slice(Math.max(0,i-300),i+300).replace(/\s+/g," "))
  const s=r.text.match(/"settings":\{[^}]{0,800}/)?.[0]
  console.log("\nsettings:",s)
  // error mention
  console.log("\nhas DM005/DM010:",/DM00\d|DM01\d/.test(r.text)?r.text.match(/DM0\d\d[^\"]{0,80}/)?.[0]:"no")
  console.log("has 'private':",/private/i.test(r.text))
})()
