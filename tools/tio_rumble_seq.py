# -*- coding: utf-8 -*-
"""Secuencia como navegador: GET embed page (cookies) -> API embedJS."""
import http.cookiejar, urllib.request, urllib.parse, re, sys, time
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
opener.addheaders = [('User-Agent', UA), ('Accept-Language', 'es-ES,es;q=0.8')]

def call(url, refs=None):
    h = {'Referer': refs} if refs else {}
    req = urllib.request.Request(url, headers=h)
    try:
        r = opener.open(req, timeout=25)
        return r.status, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read(500).decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

emb = 'https://rumble.com/embed/v4g6xit/?pub=3am9tf'
api = 'https://rumble.com/embedJS/u3/?request=video&v=v4g6xit&embed=' + urllib.parse.quote(emb, safe='')

print('cookies antes:', len(list(cj)))
st1, body1 = call(emb, refs='https://tiodonghua.lat/')
print('embed page:', st1, 'len', len(body1), '| cookies ahora:', [c.name for c in cj])

st2, body2 = call(api, refs=emb)
print('embedJS tras embed page:', st2, 'len', len(body2))
fixed = body2.replace('\\/', '/')
links = re.findall(r'"(\d{3,4})":\["(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"', fixed)
print('calidades:', sorted(int(q) for q, _ in links))
if not links:
    print('body head:', body2[:300])
