# -*- coding: utf-8 -*-
"""v24.13 - dump embedJS de un rumble de SeriesDonghua (estructura de ua)."""
import urllib.request, urllib.parse, re, sys, http.cookiejar
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
emb = 'https://rumble.com/embed/v7e3nsk/?pub=3qe03r'
# warm-up __cf_bm: visitar el embed antes de la API
req0 = urllib.request.Request(emb, headers={'User-Agent': UA, 'Referer': 'https://seriesdonghua.com/'})
html0 = opener.open(req0, timeout=30).read().decode('utf-8', 'replace')
print('warm-up embed len', len(html0))
vid = re.search(r'rumble\.com/(?:embed/)?(v[a-zA-Z0-9]+)', emb).group(1)
api = 'https://rumble.com/embedJS/u3/?request=video&v=' + vid + '&embed=' + urllib.parse.quote(emb, safe='')
req = urllib.request.Request(api, headers={'User-Agent': UA, 'Referer': emb})
j = opener.open(req, timeout=30).read().decode('utf-8', 'replace')
print('API len', len(j))
print(j[:1500])
# y validar el hls-vod del HTML
html = html0.replace('\\/', '/')
hls = re.search(r'"hls":\{"url":"(https://rumble\.com/hls-vod/[^"]+)"', html)
print('hls-vod:', hls.group(1)[:140] if hls else None)
if hls:
    req3 = urllib.request.Request(hls.group(1), headers={'User-Agent': UA, 'Referer': emb})
    body = opener.open(req3, timeout=30).read().decode('utf-8', 'replace')
    print('--- playlist ---')
    print(body[:600])
