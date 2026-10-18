# -*- coding: utf-8 -*-
"""Analiza la respuesta real de cada servidor embed del ep15."""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, ssl, sys

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
ctx = ssl.create_default_context()

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj),
                                     urllib.request.HTTPSHandler(context=ctx))

def get(url, refs=None, to=25, extra=None):
    h = {'User-Agent': UA, 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'}
    if refs:
        h['Referer'] = refs
    if extra:
        h.update(extra)
    req = urllib.request.Request(url, headers=h)
    try:
        r = opener.open(req, timeout=to)
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        return r.status, raw.decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

servers = {
    'DM rumble': 'https://rumble.com/embed/v4g6xit/?pub=3am9tf',
    'OK ok.ru': 'https://ok.ru/videoembed/7021709363905',
    'Filemon embedme': 'https://embedme.xyz/e/3gvdetxx4qak',
    'ST streamtape': 'https://streamtape.com/e/AK21Yz4zJgFw0j/',
    'DS d000d': 'https://d000d.com/e/otdajwymw5hv',
    'VOE voe.sx': 'https://voe.sx/e/5cfj6k0asryc',
    'SW asnwish': 'https://asnwish.com/e/81h63bc2e06j',
}
refs = 'https://tiodonghua.lat/episodios/the-secrets-of-star-divine-arts-episodio-15-sub-espanol/'
only = sys.argv[1] if len(sys.argv) > 1 else None
for name, url in servers.items():
    if only and only.lower() not in name.lower():
        continue
    st, body = get(url, refs=refs)
    print('=' * 70)
    print(name, url, '->', st, 'len', len(body))
    if st != 200:
        print(' ', body[:200])
        continue
    m3u8 = re.findall(r'https?://[^"\'\s<>\\]+\.m3u8[^"\'\s<>\\]*', body)
    mp4 = re.findall(r'https?://[^"\'\s<>\\]+\.mp4[^"\'\s<>\\]*', body)
    packer = 'eval(function(p,a,c,k,e,' in body
    print('  m3u8 count', len(m3u8), '| mp4 count', len(mp4), '| packer', packer)
    for u in m3u8[:3]:
        print('   M3U8:', u[:130])
    for u in mp4[:3]:
        print('   MP4 :', u[:130])
    # scripts inline largos (posible fuente ofuscada)
    scripts = re.findall(r'<script[^>]*>(.*?)</script>', body, re.DOTALL)
    big = [s for s in scripts if len(s) > 800]
    print('  scripts>800:', len(big))
    for s in big[:2]:
        txt = re.sub(r'\s+', ' ', s)[:400]
        print('   SCRIPT:', txt)
