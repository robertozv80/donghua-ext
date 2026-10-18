# -*- coding: utf-8 -*-
"""Revisa VIP servers, asnwish y rumble en detalle."""
import sys as _sys
_sys.stdout.reconfigure(encoding='utf-8', errors='replace')
import http.cookiejar, urllib.request, urllib.parse, re, base64, sys, gzip, io

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
opener.addheaders = [('User-Agent', UA), ('Accept-Language', 'es-ES,es;q=0.8')]

def get(url, data=None, refs=None, to=25):
    h = {'Referer': refs} if refs else {}
    req = urllib.request.Request(url, data=(urllib.parse.urlencode(data).encode() if data else None), headers=h)
    try:
        r = opener.open(req, timeout=to)
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        return r.status, raw.decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

page = 'https://tiodonghua.lat/episodios/the-secrets-of-star-divine-arts-episodio-15-sub-espanol/'
st, html = get(page)
print('ep15 len', len(html))
# bloques is-vip-server
i = 0
vip = []
while True:
    i = html.find('is-vip-server', i + 1)
    if i == -1:
        break
    vip.append(html[max(0, i-300):i+300].replace('\n', ' '))
print('vip hits:', len(vip))
for v in vip[:4]:
    print('---VIP---')
    print(v[:500])

# AJAX para numes 9..12 (los VIP vienen despues de los 8 visibles?)
for nume in ['9', '10', '11', '12']:
    st, resp = get('https://tiodonghua.lat/wp-admin/admin-ajax.php',
                   data={'action': 'doo_player_ajax', 'post': '63374', 'nume': nume, 'type': 'tv'}, refs=page)
    print('AJAX nume', nume, st, repr(resp[:150]))

# asnwish sin gzip explicito
st, body = get('https://asnwish.com/e/81h63bc2e06j', refs='https://tiodonghua.lat/')
print('--- asnwish ---', st, len(body))
print(repr(body[:700]))

# rumble: buscar video data en el embed
st, body = get('https://rumble.com/embed/v4g6xit/?pub=3am9tf', refs='https://tiodonghua.lat/')
print('--- rumble ---', st, len(body))
print('  video= present:', 'video=' in body, '| RumblePlayer:', 'RumblePlayer' in body)
hls = re.findall(r'https://rumble\.com/hls-vod/[^"\'\\]+', body.replace('\\"', '"'))
print('  hls-vod urls:', hls[:2])
# eventos del player con vid
vid = re.findall(r'"(v[a-z0-9]{6})"', body[:20000])
print('  posibles vid:', vid[:10])
