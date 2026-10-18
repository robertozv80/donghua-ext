#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""img src real de cards homepage (picture) + srcset en secciones
(status/genero). ¿El src es la version GRANDE o pequena?"""
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

html = fetch("https://animegratis.net/")
print("=== Homepage: primera card data-home-episode-card COMPLETA ===")
m = re.search(r'<a[^>]*data-home-episode-card[^>]*>.*?</a>', html, re.S)
print(m.group(0)[:1600] if m else "no encontrada")
print()
# ¿el img src es cover.webp o una variante pequena?
imgs = re.findall(r'<img\s+src="(https://cdn\.animegratis\.net/[^"]+)"', html)
from collections import Counter
kinds = Counter()
for u in imgs:
    if "cover-180x270" in u: kinds["cover-180x270"] += 1
    elif "cover-240x360" in u: kinds["cover-240x360"] += 1
    elif re.search(r"/cover\.(webp|jpg)", u): kinds["cover.base"] += 1
    else: kinds["otra"] += 1
print(f"imgs cdn homepage: {dict(kinds)}")
print()
for name, url in [("status En emision", "https://animegratis.net/donghua?status=En+emisi%C3%B3n"),
                  ("genero accion", "https://animegratis.net/donghua/genero/accion")]:
    h = fetch(url)
    pics = len(re.findall(r"<picture>", h))
    srcsets = len(re.findall(r"<source[^>]+srcset=", h))
    cardimgs = re.findall(r'<a\s+href="/donghua/[^"]+"[^>]*>\s*<img\s+src="([^"]+)"', h)
    print(f"=== {name}: picture={pics} source-srcset={srcsets} cards={len(cardimgs)} ===")
    for u in cardimgs[:3]:
        print(f"   {u}")
    # primera source srcset no-logo
    for sm in re.finditer(r'<source[^>]+srcset="([^"]+)"', h):
        if "logo" not in sm.group(0):
            print(f"   source: {sm.group(1)[:200]}")
            break
    print()
