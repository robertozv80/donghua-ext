import fs from "fs";
const h = fs.readFileSync(new URL("./byse_video.js", import.meta.url), "utf8");
const show = (label, re, n = 12) => {
  const hits = [...new Set([...h.matchAll(re)].map(m => m[0]))].slice(0, n);
  console.log(`== ${label} (${hits.length}):`);
  hits.forEach(x => console.log("  ", x.slice(0, 110)));
};
show("urls", /https?:\/\/[a-zA-Z0-9._-]+\/?[a-zA-Z0-9._/-]{0,50}/g);
show("api paths", /\/api\/[a-zA-Z0-9._/-]{1,60}/g);
show("ws", /wss?:\/\/[^\s"'`]{1,80}/g);
show("fetch+", /fetch\((.{1,70}?)(?:,\{\)|\)\s*\.then)/gs);
show("m3u8 ctx", /m3u8/g, 1);
const i = h.indexOf("m3u8");
if (i > 0) console.log("ctx:", h.slice(i - 300, i + 250).replace(/\s+/g, " "));
show("localStorage keys", /localStorage\.(?:get|set)Item\("[^"]+"/g);
show("EventSource/SSE", /EventSource|text\/event-stream/g, 3);
show("hex/base64 fns", /(?:atob|btoa|fromCharCode\(parseInt)/g, 3);
