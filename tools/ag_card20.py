#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Dimensiones reales: cover.webp vs variantes vs og:image PNG.
Y campos im/fb/fb2 del #ssr-init del directorio."""
import gzip, re, ssl, struct, sys, urllib.request

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

def webp_size(d):
    # RIFF....WEBP VP8/VP8L/VP8X
    if d[:4] != b"RIFF" or d[8:12] != b"WEBP":
        return None
    fmt = d[12:16]
    if fmt == b"VP8 ":
        return struct.unpack("<HH", d[26:30])
    if fmt == b"VP8L":
        b = d[21] | (d[22] << 8) | (d[23] << 16)
        return ((b & 0x3FFF) + 1, ((b >> 14) & 0x3FFF) + 1)
    if fmt == b"VP8X":
        w = int.from_bytes(d[24:27], "little") + 1
        h = int.from_bytes(d[27:30], "little") + 1
        return (w, h)
    return None

def png_size(d):
    if d[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", d[16:24])

def jpeg_size(d):
    i = 2
    while i < len(d) - 9:
        if d[i] != 0xFF:
            i += 1
            continue
        m = d[i + 1]
        if m in (0xC0, 0xC1, 0xC2, 0xC3):
            return (struct.unpack(">H", d[i + 7:i + 9])[0], struct.unpack(">H", d[i + 5:i + 7])[0])
        ln = struct.unpack(">H", d[i + 2:i + 4])[0]
        i += 2 + ln
    return None

def size_of(d, ct):
    if "webp" in (ct or ""):
        return webp_size(d)
    if "png" in (ct or ""):
        return png_size(d)
    if "jpeg" in (ct or ""):
        return jpeg_size(d)
    return None

slug = "the-first-immortal-of-sword-art"
print("=== donghua:", slug, "===")
for v in ("cover.webp", "cover.jpg"):
    u = f"https://cdn.animegratis.net/donghua/{slug}/images/{v}"
    try:
        d, h = fetch_bytes(u, "https://animegratis.net/")
        print(f"  {v}: {len(d)}B {size_of(d, h.get('Content-Type'))}")
    except Exception as e:
        print(f"  {v}: {getattr(e, 'code', e)}")
u = f"https://animegratis.net/api/og/{slug}.png?kind=donghua&v=20260927b"
try:
    d, h = fetch_bytes(u, "https://animegratis.net/")
    print(f"  og png: {len(d)}B {size_of(d, h.get('Content-Type'))}")
except Exception as e:
    print(f"  og png: {getattr(e, 'code', e)}")

print()
print("=== ssr-init /directorio: campos im/fb/fb2 ===")
h = fetch_bytes("https://animegratis.net/directorio")[0]
html = h.decode("utf-8", errors="replace")
m = re.search(r'<script id="ssr-init"[^>]*>(.*?)</script>', html, re.S)
if m:
    raw = m.group(1)
    # sacar los primeros objetos con im
    for fm in list(re.finditer(r'\{"t":"[^"]+","sl":"[^"]+"[^{}]*?"im":"([^"]+)"[^{}]*?\}', raw))[:4]:
        obj = fm.group(0)
        im = re.search(r'"im":"([^"]+)"', obj)
        fb = re.search(r'"fb":"([^"]*)"', obj)
        fb2 = re.search(r'"fb2":"([^"]*)"', obj)
        t = re.search(r'"t":"([^"]+)"', obj)
        print(f"  {t.group(1) if t else '?'}")
        print(f"    im : {im.group(1) if im else None}")
        print(f"    fb : {fb.group(1) if fb else None}")
        print(f"    fb2: {fb2.group(1) if fb2 else None}")
        if im:
            try:
                d, hh = fetch_bytes(im.group(1), "https://animegratis.net/")
                print(f"    -> im size: {len(d)}B {size_of(d, hh.get('Content-Type'))}")
            except Exception as e:
                print(f"    -> im ERROR {getattr(e, 'code', e)}")
else:
    print("  no ssr-init")
