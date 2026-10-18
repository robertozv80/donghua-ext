#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Buscar srcset en imgs de CARDS (poster), no logos: homepage
(data-home-episode-card), /donghua, /directorio. Tolerar 404."""
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

for name, url in [("homepage", "https://animegratis.net/"),
                  ("catalogo /donghua", "https://animegratis.net/donghua"),
                  ("directorio", "https://animegratis.net/directorio")]:
    try:
        html = fetch(url)
    except Exception as e:
        print(f"=== {name}: ERROR {e} ===\n")
        continue
    print(f"=== {name} ===")
    # imgs con srcset que NO sean del logo
    hits = 0
    for m in re.finditer(r'<img[^>]+srcset="[^"]*"[^>]*>', html, re.S):
        tag = m.group(0)
        if "logo" in tag:
            continue
        print(tag[:800])
        print()
        hits += 1
        if hits >= 5:
            break
    if hits == 0:
        print("(ninguna img de card con srcset)")
        # ¿y imgs dentro de a[data-home-episode-card]?
        for m in re.finditer(r'<a[^>]*data-home-episode-card[^>]*>.*?</a>', html, re.S):
            print("card home:")
            print(m.group(0)[:800])
            break
    print()
