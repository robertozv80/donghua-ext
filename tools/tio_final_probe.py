# -*- coding: utf-8 -*-
"""Valida MP4 de rumble (Range) y servidores restantes del ep16."""
import http.cookiejar, urllib.request, urllib.parse, re, sys, gzip, io
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
opener.addheaders = [('User-Agent', UA)]

def get(url, refs=None, to=25, rng=None):
    h = {}
    if refs:
        h['Referer'] = refs
    if rng:
        h['Range'] = rng
    req = urllib.request.Request(url, headers=h)
    try:
        r = opener.open(req, timeout=to)
        raw = r.read(2048)
        return r.status, dict(r.headers), raw[:120]
    except Exception as e:
        return -1, {}, f'{type(e).__name__}: {e}'.encode()

# 1) MP4 rumble con Range (como ExoPlayer)
mp4 = 'https://hugh.cdn.rumble.cloud/video/s8/2/V/c/_/s/Vc_sq.haa.mp4'
st, hd, body = get(mp4, refs='https://rumble.com/', rng='bytes=0-1023')
print('RUMBLE MP4 1080p:', st, hd.get('Content-Type'), hd.get('Content-Range'), hd.get('Content-Length'))
print('  head:', body[:60])

# 2) servidores ep16
tests = {
    'fmoonembed (Filemon)': 'https://fmoonembed.pro/e/haf88b903bs5',
    'd000d (DS)': 'https://d000d.com/e/l11psrsxlflx',
    'vidhidevip (VOE)': 'https://vidhidevip.com/v/yxx00ztsreu6',
    'asnwish ep16 (SW)': 'https://asnwish.com/e/jbfi4d0udxcz',
}
for name, u in tests.items():
    st, body = 0, ''
    try:
        req = urllib.request.Request(u, headers={'User-Agent': UA, 'Referer': 'https://tiodonghua.lat/'})
        r = opener.open(req, timeout=20)
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        st, body = r.status, raw.decode('utf-8', 'replace')
    except Exception as e:
        st, body = -1, f'{type(e).__name__}: {e}'
    m3u8 = len(re.findall(r'\.m3u8', body))
    packer = 'eval(function(p,a,c,k,e,' in body
    alive = 'no longer available' not in body and st == 200 and len(body) > 3000
    print(f'{name}: {st} len={len(body)} m3u8hits={m3u8} packer={packer} alive~={alive}')

# 3) JSON completo de embedJS para ver todas las claves
st, body = 0, ''
try:
    api = 'https://rumble.com/embedJS/u3/?request=video&v=v4g6xit&embed=' + urllib.parse.quote('https://rumble.com/embed/v4g6xit/?pub=3am9tf', safe='')
    req = urllib.request.Request(api, headers={'User-Agent': UA, 'Referer': 'https://rumble.com/'})
    r = opener.open(req, timeout=20)
    body = r.read().decode('utf-8', 'replace')
    print('EMBEDJS KEYS:', list(re.findall(r'"([a-z_]+)":', body))[:20])
    print(body)
except Exception as e:
    print('embedjs err', e)
