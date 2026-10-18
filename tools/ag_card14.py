#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Simular findBestPosterUrl (primer img[src*=cdn.animegratis]) sobre
fichas de donghua, anime y peliculas, y comparar con el póster real
(img con alt 'Portada de'). Busca casos donde el primero NO es el póster."""
import gzip, re, ssl, sys, urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

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

def simulate(html):
    """Devuelve (bestPoster_findBestPosterUrl, real_poster_alt, todos_los_cdn_imgs)."""
    # findBestPosterUrl paso 1: primer img con src*=cdn.animegratis y bestImageUrl http
    findbest = None
    for m in re.finditer(r'<img[^>]+>', html, re.S):
        tag = m.group(0)
        s = re.search(r'\ssrc="([^"]*)"', tag)
        if s and "cdn.animegratis" in s.group(1):
            # bestImageUrl: src, data-src, data-fallback, data-fb2 (primer http)
            for attr in ("src", "data-src", "data-fallback", "data-fb2"):
                v = re.search(r'\s' + attr + r'="([^"]*)"', tag)
                if v and v.group(1).startswith("http") and v.group(1) != "data:,":
                    findbest = v.group(1)
                    break
            if findbest:
                break
    # póster real: img con alt="Portada de ..."
    real = None
    for m in re.finditer(r'<img[^>]+alt="Portada de[^"]*"[^>]*>', html, re.S):
        tag = m.group(0)
        s = re.search(r'\ssrc="([^"]*)"', tag)
        if s:
            real = s.group(1)
            break
    # og:image
    og = None
    m = re.search(r'<meta[^>]+property="og:image"[^>]+content="([^"]*)"', html)
    if not m:
        m = re.search(r'<meta[^>]+content="([^"]*)"[^>]+property="og:image"', html)
    og = m.group(1) if m else None
    # todos los cdn imgs (primeros 6)
    allcdn = []
    for m in re.finditer(r'<img[^>]+src="(https://cdn\.animegratis\.net/[^"]+)"[^>]*>', html, re.S):
        allcdn.append(m.group(1))
        if len(allcdn) >= 6:
            break
    return findbest, real, og, allcdn

FICHAS = [
    ("donghua-serie", "https://animegratis.net/donghua/the-first-immortal-of-sword-art"),
    ("donghua-serie2", "https://animegratis.net/donghua/qu-ni-de-dao"),
    ("donghua-serie3", "https://animegratis.net/donghua/biao-ren-er"),
    ("anime-serie", "https://animegratis.net/anime/kanata-kara"),
    ("anime-serie2", "https://animegratis.net/anime/meitantei-precure"),
]

for name, url in FICHAS:
    try:
        html = fetch(url)
    except Exception as e:
        print(f"[{name}] {url}\n  ERROR: {e}\n")
        continue
    findbest, real, og, allcdn = simulate(html)
    print(f"[{name}] {url}")
    print(f"  findBestPosterUrl -> {findbest}")
    print(f"  poster real (alt) -> {real}")
    print(f"  og:image          -> {og}")
    print(f"  coincide: {findbest == real}")
    for i, u in enumerate(allcdn):
        print(f"    cdn[{i}]: {u}")
    print()
