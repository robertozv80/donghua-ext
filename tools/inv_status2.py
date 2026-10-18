# -*- coding: utf-8 -*-
"""DOM vs RSC del badge en varias paginas de donghualife."""
import re
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

for url in ["https://donghualife.com/series/el-inmortal-renegado",
            "https://donghualife.com/series/against-sky-supreme",
            "https://donghualife.com/series/alquimia-suprema"]:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as r:
        p = r.read().decode("utf-8", "replace")
    scripts = "".join(re.findall(r"<script.*?</script>", p, re.S))
    outside = re.sub(r"<script.*?</script>", "", p, flags=re.S)
    dom_badges = re.findall(r'text-xs font-black uppercase tracking-widest">([^<]{2,40})<', outside)
    print(url.rsplit("/", 1)[-1], "| DOM badges:", dom_badges)
