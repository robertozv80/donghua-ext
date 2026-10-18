#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Reproducir la request EXACTA de CloudStream3 contra AnimeGratis.
USER_AGENT real extraido del jar: Chrome/116 Windows desktop.
Compara HTML (hash, longitud, imgs cdn, primer poster) con fetch plano.
Tambien imprime response headers (senales de bot detection)."""
import gzip, hashlib, re, ssl, sys, urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

CS_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

# Headers EXACTOS del provider (map "headers" en AnimeGratisProvider.kt)
CS_HEADERS = {
    "User-Agent": CS_UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
}

PLAIN_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

def fetch(url, headers):
    h = dict(headers)
    req = urllib.request.Request(url, headers=h)
    r = urllib.request.urlopen(req, timeout=30, context=CTX)
    data = r.read()
    enc = r.headers.get("Content-Encoding")
    if enc == "gzip":
        data = gzip.decompress(data)
    html = data.decode("utf-8", errors="replace")
    return html, r.headers

def summarize(name, html, hdrs):
    cdn_imgs = re.findall(r'<img[^>]+src="(https://cdn\.animegratis\.net/[^"]+)"', html)
    poster_candidates = [u for u in cdn_imgs if "/images/cover" in u]
    print(f"[{name}] len={len(html)} sha1={hashlib.sha1(html.encode()).hexdigest()[:12]}")
    print(f"  imgs cdn: {len(cdn_imgs)}, covers: {len(poster_candidates)}")
    print(f"  primera cover: {poster_candidates[0] if poster_candidates else 'NINGUNA'}")
    # respuesta headers relevantes
    for k in ("Server", "CF-Ray", "X-Cache", "Cache-Control", "Vary", "Content-Type"):
        v = hdrs.get(k)
        if v:
            print(f"  hdr {k}: {v}")
    return html

PAGES = [
    ("catalogo", "https://animegratis.net/donghua", None),
    ("homepage", "https://animegratis.net/", None),
    ("ficha-inmortal", "https://animegratis.net/donghua/the-first-immortal-of-sword-art", "https://animegratis.net/donghua"),
    ("ficha-qu-ni", "https://animegratis.net/donghua/qu-ni-de-dao", "https://animegratis.net/donghua"),
    ("genero-accion", "https://animegratis.net/donghua/genero/accion", None),
]

for name, url, ref in PAGES:
    print("=" * 90)
    print("PAGINA:", name, url)
    # A) request exacta CloudStream
    h_cs = dict(CS_HEADERS)
    if ref:
        h_cs["Referer"] = ref
    html_cs, hdr_cs = fetch(url, h_cs)
    summarize("CloudStream-exact", html_cs, hdr_cs)
    # B) fetch plano (UA nuevo)
    h_plain = {"User-Agent": PLAIN_UA, "Accept-Language": "es-ES,es;q=0.8"}
    if ref:
        h_plain["Referer"] = ref
    html_plain, hdr_plain = fetch(url, h_plain)
    summarize("plano", html_plain, hdr_plain)
    same = html_cs == html_plain
    print(f"  -> HTML IDENTICO: {same}")
    if not same:
        # primeras diferencias
        import difflib
        sm = difflib.SequenceMatcher(None, html_cs, html_plain)
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag != "equal":
                print(f"  diff {tag} cs[{i1}:{i2}] plain[{j1}:{j2}]:")
                print(f"    CS:    {html_cs[i1:i1+200]!r}")
                print(f"    PLAIN: {html_plain[j1:j1+200]!r}")
                break
