#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""1) Estructura del area de póster en ficha jkdesa (biao-ren-er)
2) Cuántas cards del catálogo usan jkdesa vs animegratis
3) ¿Existen variantes de cover MAYORES en los CDNs?"""
import gzip, re, ssl, sys, urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

def fetch(url, referer=None, head_only=False):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    if referer:
        req.add_header("Referer", referer)
    r = urllib.request.urlopen(req, timeout=30, context=CTX)
    data = r.read()
    if r.headers.get("Content-Encoding") == "gzip":
        data = gzip.decompress(data)
    return data, r.headers

# 1) Estructura del póster en biao-ren-er
html, _ = fetch("https://animegratis.net/donghua/biao-ren-er")
html = html.decode("utf-8", errors="replace")
print("=== 1) Ficha biao-ren-er: imgs con jkdesa o 'Portada de' ===")
for m in re.finditer(r'<img[^>]+>', html, re.S):
    tag = m.group(0)
    if "jkdesa" in tag or "Portada de" in tag or "fetchpriority" in tag:
        print("-" * 70)
        print(tag[:600])
        print()

# 2) Conteo de CDNs en el catálogo
cat, _ = fetch("https://animegratis.net/donghua")
cat = cat.decode("utf-8", errors="replace")
cards = re.findall(r'<a\s+href="/donghua/([^"]+)"[^>]*>\s*<img\s+src="([^"]+)"', cat)
from collections import Counter
c = Counter("jkdesa" in u for _, u in cards)
print(f"=== 2) Catalogo: {len(cards)} cards, por CDN: {dict(c)} ===")
for slug, u in cards:
    if "jkdesa" in u:
        print(f"  jkdesa: {slug} -> {u}")

# 3) Variantes mayores en cdn.animegratis.net
print()
print("=== 3) Variantes de cover MAYORES (kanata-kara, anime) ===")
base = "https://cdn.animegratis.net/anime/kanata-kara/images/"
for name in ("cover.webp", "cover-300x450.webp", "cover-450x675.webp",
             "cover-600x900.webp", "cover-750x1125.webp", "cover-1200x1800.webp",
             "cover-1080x1620.webp", "cover-large.webp", "cover-hd.webp"):
    try:
        data, hdrs = fetch(base + name)
        print(f"  {name}: {len(data)}B {hdrs.get('Content-Type')}")
    except Exception as e:
        print(f"  {name}: {getattr(e, 'code', e)}")

print()
print("=== 3b) Variantes en jkdesa (biao-ren-er) ===")
for name in ("biao-ren-er.jpg", "biao-ren-er-300x450.jpg", "biao-ren-er-600x900.jpg",
             "biao-ren-er.webp", "biao-ren-er.png"):
    url = "https://cdn.jkdesa.com/assets/images/animes/image/" + name
    try:
        data, hdrs = fetch(url)
        print(f"  {name}: {len(data)}B {hdrs.get('Content-Type')}")
    except Exception as e:
        print(f"  {name}: {getattr(e, 'code', e)}")

print()
print("=== 3c) ¿La webp de animegratis acepta transformaciones? (cover.webp sin ext) ===")
for name in ("cover-240x360.webp", "cover-300x450.webp"):
    url = "https://cdn.animegratis.net/donghua/qu-ni-de-dao/images/" + name
    try:
        data, hdrs = fetch(url)
        print(f"  qu-ni-de-dao/{name}: {len(data)}B {hdrs.get('Content-Type')}")
    except Exception as e:
        print(f"  qu-ni-de-dao/{name}: {getattr(e, 'code', e)}")
