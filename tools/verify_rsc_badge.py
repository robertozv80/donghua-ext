# -*- coding: utf-8 -*-
"""Verifica el patron del badge en el payload RSC des-escapado (form post-extractRscPayload)."""
import re
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
req = urllib.request.Request("https://donghualife.com/series/el-inmortal-renegado", headers={"User-Agent": UA})
with urllib.request.urlopen(req, timeout=20) as r:
    raw = r.read().decode("utf-8", "replace")

# des-escape igual que extractRscPayload: \\\" -> " dentro de los chunks __next_f
chunks = re.findall(r'self\.__next_f\.push\(\[1,"(.*?)"\]\)', raw, re.S)
payload = ""
for c in chunks:
    payload += c.encode().decode("unicode_escape", errors="ignore")
print("payload len:", len(payload))

# patron directo (comillas reales)
pat = r'"text-xs font-black uppercase tracking-widest","children":"([^"]{2,60})"'
hits = re.findall(pat, payload)
print("badges RSC:", hits)
# patron alternativo: children antes de className
pat2 = r'"children":"([^"]{2,60})","className":"text-xs font-black uppercase tracking-widest"'
print("badges RSC inv:", re.findall(pat2, payload))
