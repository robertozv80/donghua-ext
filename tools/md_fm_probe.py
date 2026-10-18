# -*- coding: utf-8 -*-
"""Sonda v24.14 - MundoDonghua servidor 'fm':
1) tabs de servidores (button.md-server-tab[data-target])
2) scripts packed -> unpack y buscar servidores
3) embeds crudos en HTML
"""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, sys, time
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, to=40):
    h = {'User-Agent': UA, 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'}
    if refs: h['Referer'] = refs
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

def retry(fn, *a, **k):
    st, b = -1, ''
    for i in range(3):
        st, b = fn(*a, **k)
        if st == 200: return st, b
        time.sleep(2.5 * (i + 1))
    return st, b

def js_unpack(packed):
    """Dean Edwards unpacker (p,a,c,k,e,d) igual que getAndUnpack de CloudStream."""
    m = re.search(r"\}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)", packed, re.S)
    if not m: return None
    p, a, c, k = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4).split('|')
    kdict = {str(i): w for i, w in enumerate(k) if w}
    def rep(tok):
        return kdict.get(tok, tok)
    words = re.split(r"(\b\w+\b)", p)
    return ''.join(rep(w) for w in words)

for ep in ['https://www.mundodonghua.com/ver/the-first-immortal-of-sword-art/1',
           'https://www.mundodonghua.com/ver/the-first-immortal-of-sword-art-2/1']:
    st, html = retry(get, ep)
    print('=' * 70)
    print('EP:', ep, '| HTTP', st, '| len', len(html))
    if st != 200:
        print(html[:300]); continue
    # 1) tabs
    tabs = re.findall(r'<button[^>]*md-server-tab[^>]*data-target="([^"]+)"[^>]*>(.*?)</button>', html, re.S)
    print('TABS:', [(t, re.sub(r'<[^>]+>', '', x).strip()) for t, x in tabs])
    # tabs con cualquier orden de atributos
    tabs2 = re.findall(r'<button[^>]*md-server-tab[^>]*>', html)
    print('BTN RAW:', tabs2)
    # 2) packed
    for i, script in enumerate(re.findall(r'<script[^>]*>(.*?)</script>', html, re.S)):
        if 'eval(function(p,a,c,k,e' not in script: continue
        print(f'--- script #{i} con packed (len {len(script)})')
        for pm in re.finditer(r'eval\(function\(p,a,c,k,e,[^\r\n]+', script):
            packed = pm.group(0)
            un = js_unpack(packed)
            if not un:
                print('   [unpack fallo, packed[:120]:', packed[:120])
                continue
            print('   UNPACK len', len(un))
            # servidores referenciados
            for kw in ['fm', 'fmoon', 'filemoon', 'asura', 'tamamo', 'vhide', 'kaga', 'swish',
                       'vidhidepro', 'bysekoze', 'vgembed', 'embedwish', 'streamwish', 'voe',
                       'mdnemonicplayer', 'redirector', 'api_donghua', 'iframe', 'src=']:
                if kw in un.lower():
                    # contexto
                    idx = un.lower().find(kw)
                    print(f'      [{kw}] ...{un[max(0,idx-60):idx+120]}...')
            urls = re.findall(r'https?://[^\s"\'<>]+', un)
            print('   URLS:', list(dict.fromkeys(urls))[:12])
    # 3) embeds crudos
    raws = re.findall(r'https?://[^\s"\'<>]*(?:bysekoze|filemoon|vidhidepro|vgembed|embedwish|streamwish|voe\.sx|dailymotion)[^\s"\'<>]*', html)
    print('RAW EMBEDS:', list(dict.fromkeys(raws))[:10])
    # 4) iframes
    ifr = re.findall(r'<iframe[^>]*src="([^"]+)"', html)
    print('IFRAMES:', list(dict.fromkeys(ifr))[:10])
    time.sleep(2)
