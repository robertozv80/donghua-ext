#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""¿Las cards nuevas traen srcset? Volcar attrs completos de las
imgs de card en /donghua, /anime y homepage. Si hay srcset con
variantes mayores, aprovecharlo en parseDonghuaCards."""
import gzip, re, ssl, sys, urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    r = urllib.request.urlopen(req, timeout=30, context=CTX)
    data = r.read()
    if r.headers.get("Content-Encoding") == "gzip":
        data = gzip.decompress(data)
    return data.decode("utf-8", errors="replace")

for name, url in [("catalogo /donghua", "https://animegratis.net/donghua"),
                  ("catalogo /anime", "https://animegratis.net/anime"),
                  ("homepage", "https://animegratis.net/")]:
    html = fetch(url)
    print(f"=== {name} ===")
    n = 0
    for m in re.finditer(r'<img[^>]+>', html, re.S):
        tag = m.group(0)
        if "srcset" in tag:
            print(tag[:700])
            print()
            n += 1
            if n >= 4:
                break
    if n == 0:
        # mostrar primera img de card completa igualmente
        for m in re.finditer(r'<a\s+href="/donghua/[^"]+"[^>]*>\s*<img[^>]+>', html, re.S):
            print("(sin srcset) tarjeta:")
            print(m.group(0)[:700])
            print()
            break
    print()
