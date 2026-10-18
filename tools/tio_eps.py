# -*- coding: utf-8 -*-
"""Compara servidores de ep15/16/17 de the-secrets-of-star-divine-arts en tiodonghua.lat."""
import re
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

for ep in [15, 16, 17]:
    url = f"https://tiodonghua.lat/episodios/the-secrets-of-star-divine-arts-episodio-{ep}-sub-espanol/"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=25) as r:
            p = r.read().decode("utf-8", "replace")
        print(f"=== EP {ep}: http 200, {len(p)} bytes ===")
        iframes = re.findall(r'<iframe[^>]*src="([^"]+)"', p)
        print("iframes:", iframes if iframes else "NINGUNO")
        # data-vid / opciones de servidor estilo animeflash/xtream
        opts = re.findall(r'data-(?:vid|url|link|server|opt)="([^"]+)"', p)
        print("data-attrs:", opts[:10])
        # enlaces directos conocidos
        for host in ["ok.ru", "rumble.com", "dailymotion", "odysee", "streamable", "vidhide",
                     "morencius", "filemoon", "voe", "mp4upload", "yourupload", "streamtape",
                     "safe.encrypt", "megacloud", "hайд", "swarm", "bvio", "swhoi", "guahey"]:
            n = len(re.findall(re.escape(host), p))
            if n:
                print(f"  host '{host}': {n}")
    except Exception as e:
        print(f"=== EP {ep}: FAIL {e} ===")
