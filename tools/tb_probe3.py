# -*- coding: utf-8 -*-
"""v24.13 - Terabox parte 3: cadena completa sin login.
A) embed con www (capturar el error del GET anterior)
B) share page /s/1<surl>: jsToken / shareid / uk / sekey
C) shorturlinfo con jsToken
D) share/download con jsToken+shareid+uk+fid -> dlink
E) dlink con Range
"""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, ssl, sys, time, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, to=40, extra=None):
    h = {'User-Agent': UA, 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'}
    if refs: h['Referer'] = refs
    if extra: h.update(extra)
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r), r.headers, r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace'), e.headers, e.geturl()
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}', None, url

def show(hh, pref):
    if hh:
        for k in ['Content-Type', 'Content-Range', 'Content-Length', 'Location']:
            v = hh.get(k)
            if v: print(f'  {pref}{k}: {v[:120]}')

surl = 'NFqQvtSCh-IORMMEksjxHQ'
fid = '848169177574147'
uk = '4400288918367'
emb_url = (f'https://terabox.com/sharing/embed?surl={surl}&resolution=1080&autoplay=true'
           f'&mute=false&uk={uk}&fid={fid}&slid=')

print('=== A) embed (www y sin www) ===')
for u in [emb_url, emb_url.replace('https://terabox.com', 'https://www.terabox.com')]:
    st, body, hh, fu = get(u, refs='https://tiodonghua.lat/')
    print('GET', u[:70], '-> HTTP', st, 'len', len(body), '| final:', fu[:80])
    if st == -1: print('   error:', body[:120])
    show(hh, '   ')
    if st == 200:
        for pat in ['jsToken', 'shareid', '"uk"', 'dlink', 'playUrl', 'videoList']:
            print(f'   {pat}: {body.count(pat)}')
        apis = sorted(set(re.findall(r'https?://[a-z0-9.-]*terabox[^"\'\s\\<>]+', body)))[:10]
        for a in apis: print('   API:', a[:140])
    time.sleep(1)

print('=== B) share page /s/1<surl> ===')
st, sp, hh, fu = get('https://terabox.com/s/1' + surl, refs='https://terabox.com/')
print('HTTP', st, 'len', len(sp), '| final:', fu[:100])
if st != -1:
    for pat in ['jsToken', 'shareid', 'sekey', 'STOKEN', '__INITIAL_STATE__']:
        print(f'  {pat}: {sp.count(pat)}')
    tok = ''
    for rx in [r'jsToken["\'\s:=]+([A-Za-z0-9%._-]{40,})', r'"jsToken":"([^"]+)"']:
        mt = re.search(rx, sp)
        if mt: tok = mt.group(1); break
    print('  jsToken:', (tok[:60] + '...') if tok else 'NO ENCONTRADO')
    shid = re.search(r'"shareid"?["\s:=]+(\d{6,})', sp)
    ukp = re.search(r'"uk"?["\s:=]+(\d{6,})', sp)
    sk = re.search(r'"sekey":"([^"]+)"', sp)
    print('  shareid:', shid.group(1) if shid else None, '| uk:', ukp.group(1) if ukp else None,
          '| sekey:', (sk.group(1)[:20] + '..') if sk else None)
    print('  cookies:', [c.name for c in cj])
    if tok:
        print('=== C) shorturlinfo con jsToken ===')
        u = (f'https://www.terabox.com/api/shorturlinfo?app_id=250528&web=1&channel=dubox'
             f'&clienttype=0&jsToken={urllib.parse.quote(tok, safe="")}&shorturl={surl}&root=1')
        st2, j2, hh2, fu2 = get(u, refs='https://terabox.com/')
        print('HTTP', st2, 'len', len(j2), ':', j2[:400])
        try:
            jj = json.loads(j2)
            shid2 = jj.get('shareid') or jj.get('shareId')
            uk2 = jj.get('uk') or jj.get('share Uk') or uk
            print('  shareid', shid2, 'uk', uk2, 'title', jj.get('title'))
        except Exception:
            shid2 = shid.group(1) if shid else None
            uk2 = uk
        print('=== D) share/download ===')
        u2 = (f'https://www.terabox.com/share/download?app_id=250528&web=1&channel=dubox'
              f'&clienttype=0&jsToken={urllib.parse.quote(tok, safe="")}'
              f'&shareid={shid2 or ""}&uk={uk2}&fid_list=[{fid}]&primaryid={shid2 or ""}'
              f'&product=share&nozip=0')
        st3, j3, hh3, fu3 = get(u2, refs='https://terabox.com/')
        print('HTTP', st3, ':', j3[:400])
        try:
            jj3 = json.loads(j3)
            dl = jj3.get('dlink')
            print('  errno', jj3.get('errno'), '| dlink:', (dl[:150] + '...') if dl else None)
            if dl:
                print('=== E) dlink Range 0-1023 ===')
                st4, b4, hh4, fu4 = get(dl + '&origin=dlna', refs=None, to=60,
                                        extra={'Range': 'bytes=0-1023'})
                print('HTTP', st4, '| len', len(b4), '| magic:', b4[:16])
                show(hh4, '  ')
                print('  final url:', fu4[:180])
        except Exception as e:
            print('  parse:', e)
