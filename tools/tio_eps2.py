# -*- coding: utf-8 -*-
"""Analisis profundo de la pagina del ep15 en tiodonghua.lat."""
import re
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
url = "https://tiodonghua.lat/episodios/the-secrets-of-star-divine-arts-episodio-15-sub-espanol/"
req = urllib.request.Request(url, headers={"User-Agent": UA})
with urllib.request.urlopen(req, timeout=25) as r:
    p = r.read().decode("utf-8", "replace")
    print("final URL:", r.geturl())

m = re.search(r"<title>([^<]+)</title>", p)
print("title:", m.group(1) if m else None)

# contexto del ok.ru
i = p.find("ok.ru")
print("OK.RU ctx:", p[max(0, i - 350):i + 200].replace("\r\n", " ")[:520])

# configs de player / endpoints ajax
for pat, name in [(r'admin-ajax\.php[^"\']*', "admin-ajax"),
                  (r'"(https?://[^"]*?/wp-json[^"]*)"', "wp-json"),
                  (r"(var|const|let)\s+(\w*(?:server|video|link|ep|player)\w*)\s*=", "js vars"),
                  (r'ajax[_a-z]*\s*[:=]\s*\{', "ajax cfg"),
                  (r'"(dt_player|dooplay|dtPlayer|player_a|play[a-z_]*)"', "player keys"),
                  (r'"episodio_?[a-z]*"', "ep keys"),
                  (r'p[0-9]\.contuhl|contuhlp', "contuhl")]:
    ms = re.findall(pat, p, re.I)
    print(f"'{name}': {len(ms)}", ms[:5])

# buscar el patron tipico de dooplay: links en <li class="dooplay_player"> o options con data-type/data-post
for pat, name in [(r'data-type="([^"]*)"', "data-type"),
                  (r'data-post="([^"]*)"', "data-post"),
                  (r'data-nume="([^"]*)"', "data-nume"),
                  (r'tv-players-list', "tv-players-list"),
                  (r'source_id|sourceId', "source_id")]:
    ms = re.findall(pat, p, re.I)
    print(f"'{name}': {len(ms)}", list(set(ms))[:6])
