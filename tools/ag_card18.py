#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Estructura COMPLETA de una card de /donghua (picture/source/img)
y de /directorio. Ver donde vive el poster de mayor calidad."""
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

html = fetch("https://animegratis.net/donghua")
# primera card de catalogo
m = re.search(r'<a\s+href="/donghua/[^"]+"[^>]*>.*?</a>', html, re.S)
print("=== CARD /donghua COMPLETA ===")
print(m.group(0)[:1500] if m else "no encontrada")
print()
# todas las source srcset de la pagina
print("=== <source> con srcset en /donghua (no logo) ===")
n = 0
for sm in re.finditer(r'<source[^>]+srcset="[^"]*"[^>]*>', html, re.S):
    tag = sm.group(0)
    if "logo" in tag:
        continue
    print(tag[:400])
    n += 1
    if n >= 6:
        break
if n == 0:
    print("(ninguna)")
print()
print("=== ¿existen variantes MAYORES que cover.webp? (probes) ===")
def probe(u):
    try:
        req = urllib.request.Request(u, headers={"User-Agent": UA})
        r = urllib.request.urlopen(req, timeout=15, context=CTX)
        d = r.read()
        return f"{len(d)}B {r.headers.get('Content-Type')}"
    except Exception as e:
        return getattr(e, "code", str(e))
for v in ("cover-300x450.webp", "cover-450x675.webp", "cover-600x900.webp",
          "cover.webp", "cover-240x360.webp", "cover-180x270.webp"):
    print(f"  donghua/the-first-immortal-of-sword-art/images/{v}: {probe('https://cdn.animegratis.net/donghua/the-first-immortal-of-sword-art/images/'+v)}")
