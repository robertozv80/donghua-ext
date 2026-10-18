#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""¿El contenido de los pósters difiere entre títulos?
Descarga covers de varios slugs + og:image y compara sha1 del binario.
Si el contenido es IGUAL con URLs distintas -> el usuario ve 'el mismo
poster' aunque el parseo sea correcto."""
import hashlib, ssl, sys, urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

def fetch_bytes(url, referer=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    if referer:
        req.add_header("Referer", referer)
    r = urllib.request.urlopen(req, timeout=30, context=CTX)
    return r.read(), r.headers

SLUGS = [
    ("the-first-immortal-of-sword-art", "webp"),
    ("qu-ni-de-dao", "jpg"),
    ("jiyi-guanli-ju-2026", "webp"),
    ("shi-wangzhe-a-6ta-temporada", "webp"),
    ("yi-nian-yong-heng-wanjie-ji", "webp"),
    ("zeri-feisheng", "jpg"),
]

print("=== A) Covers del CDN (src de las cards/fichas) ===")
seen = {}
for slug, ext in SLUGS:
    url = f"https://cdn.animegratis.net/donghua/{slug}/images/cover.{ext}"
    try:
        data, hdrs = fetch_bytes(url, "https://animegratis.net/")
        h = hashlib.sha1(data).hexdigest()[:16]
        ct = hdrs.get("Content-Type")
        print(f"  {slug}: {len(data)}B sha1={h} type={ct}")
        seen.setdefault(h, []).append(slug)
    except Exception as e:
        print(f"  {slug}: ERROR {e}")

dupes = {h: s for h, s in seen.items() if len(s) > 1}
print(f"  -> hashes duplicados (mismo contenido): {dupes if dupes else 'NINGUNO - todos distintos'}")

print()
print("=== B) og:image de la API (/api/og/{slug}.png?kind=donghua) ===")
seen2 = {}
for slug, _ in SLUGS[:4]:
    url = f"https://animegratis.net/api/og/{slug}.png?kind=donghua&v=20260927b"
    try:
        data, hdrs = fetch_bytes(url, "https://animegratis.net/")
        h = hashlib.sha1(data).hexdigest()[:16]
        ct = hdrs.get("Content-Type")
        print(f"  {slug}: {len(data)}B sha1={h} type={ct}")
        seen2.setdefault(h, []).append(slug)
    except Exception as e:
        print(f"  {slug}: ERROR {e}")

dupes2 = {h: s for h, s in seen2.items() if len(s) > 1}
print(f"  -> hashes duplicados: {dupes2 if dupes2 else 'NINGUNO - todos distintos'}")

print()
print("=== C) Variantes srcset de homepage (180x270 / 240x360 / cover) ===")
for slug in ("kanata-kara", "meitantei-precure", "onegai-aipri"):
    for variant in ("cover-180x270.webp", "cover-240x360.webp", "cover.webp"):
        url = f"https://cdn.animegratis.net/anime/{slug}/images/{variant}"
        try:
            data, hdrs = fetch_bytes(url, "https://animegratis.net/")
            print(f"  {slug}/{variant}: {len(data)}B sha1={hashlib.sha1(data).hexdigest()[:16]}")
        except Exception as e:
            print(f"  {slug}/{variant}: ERROR {e}")
