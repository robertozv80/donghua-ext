import fs from "fs";
const h = fs.readFileSync(new URL("./byse.js", import.meta.url), "utf8");

console.log("== assets js refs:");
(new Set([...h.matchAll(/assets\/[A-Za-z0-9._-]+\.js/g)].map(m => m[0]))).forEach(x => console.log("  ", x));

console.log("== dynamic imports:");
(new Set([...h.matchAll(/import\((.{1,60}?)\)/g)].map(m => m[1].slice(0, 60)))).forEach(x => console.log("  ", x));

console.log("== websocket urls:");
(new Set([...h.matchAll(/wss?:\/\/[^\s"'`]{1,80}/g)].map(m => m[0]))).forEach(x => console.log("  ", x));

console.log("== 'video' strings:");
([...new Set([...h.matchAll(/"[a-zA-Z0-9/.$_-]*video[a-zA-Z0-9/._-]*"/g)].map(m => m[0]))].slice(0, 25)).forEach(x => console.log("  ", x));

console.log("== tag/stream urls:");
(new Set([...h.matchAll(/https?:\/\/[a-zA-Z0-9._-]+\/[a-zA-Z0-9._/-]{0,40}/g)].map(m => m[0])))
  .forEach(x => console.log("  ", x.slice(0, 90)));
