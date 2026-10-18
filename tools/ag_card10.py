#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Probar AnimeGratis con distintos User-Agents.
Hipotesis: el sitio sirve HTML distinto a UAs no-navegador (CloudStream usa
USER_AGENT de cloudstream3). Si con UA movil el src del img es generico y la
imagen real solo aparece en srcset -> explica el bug del poster repetido.
"""
import gzip, io, re, ssl, sys, urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

UAS = {
    "desktop": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "android_chrome": "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    "android_chrome_old": "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36",
    "cloudstream_cs3": "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36",
    "iphone": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
    "googlebot": "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
}

def fetch(url, ua, referer=None):
    req = urllib.request.Request(url, headers={
        "User-Agent": ua,
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

def cards_from_catalog(html):
    """Extraer (href, img-src, img-srcset, alt) de cards /donghua/ del catalogo."""
    out = []
    for m in re.finditer(r'<a\s+href="(/donghua/[^"]+)"[^>]*>(.*?)</a>', html, re.S):
        href, inner = m.group(1), m.group(2)
        img = re.search(r'<img[^>]+>', inner, re.S)
        if not img:
            continue
        tag = img.group(0)
        src = re.search(r'\ssrc="([^"]*)"', tag)
        srcset = re.search(r'\ssrcset="([^"]*)"', tag)
        alt = re.search(r'\salt="([^"]*)"', tag)
        out.append((href, src.group(1) if src else "", srcset.group(1) if srcset else "", alt.group(1) if alt else ""))
    return out

def cards_from_home(html):
    """Extraer cards a[data-home-episode-card]: source srcset + img src."""
    out = []
    for m in re.finditer(r'<a\s+[^>]*data-home-episode-card[^>]*>(.*?)</a>', html, re.S):
        inner = m.group(1)
        href = re.search(r'href="([^"]+)"', m.group(0))
        sources = re.findall(r'<source[^>]+srcset="([^"]+)"', inner)
        img = re.search(r'<img[^>]+>', inner, re.S)
        src = srcset = alt = ""
        if img:
            t = img.group(0)
            s = re.search(r'\ssrc="([^"]*)"', t)
            ss = re.search(r'\ssrcset="([^"]*)"', t)
            a = re.search(r'\salt="([^"]*)"', t)
            src = s.group(1) if s else ""
            srcset = ss.group(1) if ss else ""
            alt = a.group(1) if a else ""
        out.append((href.group(1) if href else "", sources, src, srcset, alt))
    return out

def detail_posters(html):
    """Primeras imagenes de una ficha de detalle con sus atributos."""
    out = []
    for m in re.finditer(r'<img[^>]+>', html, re.S):
        tag = m.group(0)
        src = re.search(r'\ssrc="([^"]*)"', tag)
        srcset = re.search(r'\ssrcset="([^"]*)"', tag)
        alt = re.search(r'\salt="([^"]*)"', tag)
        if src:
            out.append((src.group(1), srcset.group(1) if srcset else "", alt.group(1) if alt else ""))
        if len(out) >= 6:
            break
    return out

PAGES = {
    "catalogo /donghua": ("https://animegratis.net/donghua", None),
    "genero accion": ("https://animegratis.net/donghua/genero/accion", None),
    "homepage /": ("https://animegratis.net/", None),
    "ficha inmortal": ("https://animegratis.net/donghua/the-first-immortal-of-sword-art", "https://animegratis.net/donghua"),
}

for uaname, ua in UAS.items():
    print("=" * 100)
    print("UA:", uaname, "->", ua)
    for pagename, (url, ref) in PAGES.items():
        try:
            html = fetch(url, ua, ref)
        except Exception as e:
            print(f"  [{pagename}] ERROR: {e}")
            continue
        if "catalogo" in pagename or "genero" in pagename:
            cards = cards_from_catalog(html)
            print(f"  [{pagename}] {len(html)}B, {len(cards)} cards")
            for href, src, srcset, alt in cards[:5]:
                print(f"    {href}\n      src={src}\n      srcset={srcset[:120] if srcset else ''}\n      alt={alt[:70]}")
            srcs = {c[1] for c in cards}
            print(f"    -> srcs distintos: {len(srcs)} / {len(cards)} cards")
        elif "homepage" in pagename:
            cards = cards_from_home(html)
            print(f"  [{pagename}] {len(html)}B, {len(cards)} cards")
            for href, sources, src, srcset, alt in cards[:3]:
                print(f"    {href}\n      sources={sources}\n      src={src}\n      alt={alt[:60]}")
        else:
            imgs = detail_posters(html)
            print(f"  [{pagename}] {len(html)}B")
            for src, srcset, alt in imgs:
                print(f"    src={src}\n      srcset={srcset[:120] if srcset else ''}\n      alt={alt[:70]}")
    print()
