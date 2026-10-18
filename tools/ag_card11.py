#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Dump HTML crudo: cards catalogo, homepage y ficha de detalle.
Busca <picture>, <source, srcset en el markup real (no regex de card)."""
import gzip, re, ssl, sys, urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

def fetch(url, referer=None):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
    })
    if referer:
        req.add_header("Referer", referer)
    r = urllib.request.urlopen(req, timeout=30, context=CTX)
    data = r.read()
    if r.headers.get("Content-Encoding") == "gzip":
        data = gzip.decompress(data)
    return data.decode("utf-8", errors="replace")

def show(title, html, pattern, count=3, ctx=0):
    print("=" * 90)
    print(title)
    n = 0
    for m in re.finditer(pattern, html):
        s = max(0, m.start() - ctx)
        e = min(len(html), m.end() + ctx)
        print("-" * 70)
        print(html[s:e])
        n += 1
        if n >= count:
            break
    print(f"(total matches: {len(re.findall(pattern, html))})")

# 1) Catalogo: estructura de card completa
cat = fetch("https://animegratis.net/donghua")
show("CATALOGO /donghua: primera card <a href=/donghua/ completa (hasta </a>)",
     cat, r'<a\s+href="/donghua/[^"]+"[^>]*>.*?</a>', 2)
show("CATALOGO: todas las etiquetas <picture> (si las hay)", cat, r'<picture[^>]*>', 3)
show("CATALOGO: todos los srcset del documento", cat, r'srcset="[^"]*"', 5)

# 2) Homepage
home = fetch("https://animegratis.net/")
show("HOMEPAGE: primera card data-home-episode-card completa",
     home, r'<a[^>]*data-home-episode-card[^>]*>.*?</a>', 1)
show("HOMEPAGE: etiquetas <picture> y <source", home, r'<(?:picture|source)[^>]*>', 6)

# 3) Ficha de detalle: zona del poster
fic = fetch("https://animegratis.net/donghua/the-first-immortal-of-sword-art",
            "https://animegratis.net/donghua")
print("=" * 90)
print("FICHA: imagenes con contexto (busca cover)")
for m in re.finditer(r'<img[^>]+cover[^>]*>', fic):
    s = max(0, m.start() - 300)
    e = min(len(fic), m.end() + 100)
    print("-" * 70)
    print(fic[s:e])
    print()
show("FICHA: etiquetas <picture> y <source", fic, r'<(?:picture|source)[^>]*>', 6)
show("FICHA: srcset del documento", fic, r'srcset="[^"]*"', 4)

# 4) Otra ficha para comparar (que no sea inmortal)
fic2 = fetch("https://animegratis.net/donghua/qu-ni-de-dao",
             "https://animegratis.net/donghua")
print("=" * 90)
print("FICHA qu-ni-de-dao: primeras imgs")
for m in list(re.finditer(r'<img[^>]+>', fic2))[:5]:
    print("-" * 70)
    print(m.group(0))
