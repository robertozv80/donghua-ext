# -*- coding: utf-8 -*-
"""Prueba si el video ok.ru de cada ep esta vivo (flujo con cookies como navegador)."""
import http.cookiejar, urllib.request, urllib.parse, re, sys

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
opener.addheaders = [('User-Agent', UA), ('Accept-Language', 'en-US,en;q=0.8'), ('Accept', 'text/html,application/xhtml+xml,*/*;q=0.8')]

def get(url, refs=None, to=25):
    req = urllib.request.Request(url, headers=({'Referer': refs} if refs else {}))
    try:
        r = opener.open(req, timeout=to)
        return r.status, r.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, str(e)

tests = {
    '15': 'https://ok.ru/videoembed/7021709363905',
    '16': 'https://ok.ru/videoembed/7037430336193',
    '17': 'https://ok.ru/videoembed/7043367307969',
}
if len(sys.argv) > 1:
    tests = {sys.argv[1]: tests[sys.argv[1]]}

for ep, embed in tests.items():
    st, html = get(embed, refs='https://tiodonghua.lat/')
    has_opts = 'data-options' in html
    has_hls = 'hlsManifestUrl' in html or 'ondemandHls' in html
    actor = re.search(r'"movieName":"([^"]*)"', html)
    print('EP', ep, embed, '->', st, len(html), 'data-options=', has_opts, 'hls=', has_hls, 'name=', (actor.group(1)[:40] if actor else None))
