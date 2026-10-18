# -*- coding: utf-8 -*-
"""Donde vive el badge de estado: DOM vs payload RSC."""
import re
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
req = urllib.request.Request("https://donghualife.com/series/demons-ascension", headers={"User-Agent": UA})
with urllib.request.urlopen(req, timeout=20) as r:
    p = r.read().decode("utf-8", "replace")

# dividir: HTML "visible" (fuera de scripts) vs scripts RSC
scripts = re.findall(r"<script.*?</script>", p, re.S)
inside = "".join(scripts)
outside = re.sub(r"<script.*?</script>", "", p, flags=re.S)
print("total:", len(p), "| en scripts:", len(inside), "| fuera de scripts:", len(outside))

for name, chunk in [("DOM (fuera scripts)", outside), ("SCRIPTS/RSC", inside)]:
    hits = re.findall(r"Finalizado|En Emisi.n|Emisi.n", chunk)
    print(f"{name}: {len(hits)} hits", hits[:5])

# contexto del hit en scripts
m = re.search(r"lucide-calendar w-4 h-4 text-sky-300", inside)
if m:
    print("badge en RSC ctx:", inside[m.start():m.start() + 260].replace("\r\n", " ")[:250])
mo = re.search(r"Finalizado", outside)
if mo:
    print("badge en DOM ctx:", outside[max(0, mo.start() - 200):mo.end() + 120].replace("\r\n", " ")[:300])
