# -*- coding: utf-8 -*-
"""v24.13 - Terabox parte 5: jsToken = hex dentro de fn("<HEX>") del embed.
shorturlinfo -> shareid/uk -> share/download -> dlink -> Range.
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

surl = 'NFqQvtSCh-IORMMEksjxHQ'
fid = '848169177574147'
uk = '4400288918367'
emb = (f'https://www.terabox.com/sharing/embed?surl={surl}&resolution=1080&autoplay=true'
       f'&mute=false&uk={uk}&fid={fid}&slid=')

st, html, hh, fu = get(emb, refs='https://tiodonghua.lat/')
print('embed HTTP', st, 'len', len(html))
tok = ''
for m in re.finditer(r'jsToken', html):
    win = html[max(0, m.start() - 60):m.start() + 700]
    dec = urllib.parse.unquote(win)
    mt = re.search(r'fn\("([A-F0-9a-f]{40,})"\)', dec)
    if mt:
        tok = mt.group(1)
        break
print('jsToken hex:', (tok[:64] + f'... ({len(tok)} chars)') if tok else 'NO ENCONTRADO')

if not tok:
    sys.exit(1)

print('=== shorturlinfo con jsToken ===')
u = (f'https://www.terabox.com/api/shorturlinfo?app_id=250528&web=1&channel=dubox'
     f'&clienttype=0&jsToken={tok}&shorturl={surl}&root=1')
st2, j2, hh2, fu2 = get(u, refs='https://www.terabox.com/')
print('HTTP', st2, 'len', len(j2))
print(j2[:900])
shid = None
try:
    jj = json.loads(j2)
    shid = jj.get('shareid')
    print('errno', jj.get('errno'), '| shareid', shid, '| uk', jj.get('uk'), '| title', jj.get('title'))
except Exception as e:
    print('parse:', e)

if shid:
    print('=== share/download ===')
    u2 = (f'https://www.terabox.com/share/download?app_id=250528&web=1&channel=dubox'
          f'&clienttype=0&jsToken={tok}&shareid={shid}&uk={uk}&fid_list=[{fid}]'
          f'&primaryid={shid}&product=share&nozip=0')
    st3, j3, hh3, fu3 = get(u2, refs='https://www.terabox.com/')
    print('HTTP', st3, ':', j3[:500])
    try:
        jj3 = json.loads(j3)
        dl = jj3.get('dlink')
        print('errno', jj3.get('errno'), '| dlink:', (dl[:170] + '...') if dl else None)
        if dl:
            print('=== Range sobre dlink ===')
            for refs, tag in [(None, 'sin referer'), ('https://www.terabox.com/', 'referer terabox')]:
                st4, b4, hh4, fu4 = get(dl + '&origin=dlna', refs=refs, to=60,
                                         extra={'Range': 'bytes=0-1023'})
                print(f'  [{tag}] HTTP {st4} | len {len(b4)} | magic {b4[:16]}')
                if hh4:
                    print('    CT:', hh4.get('Content-Type'), '| CR:', hh4.get('Content-Range'))
                print('    final url:', fu4[:200])
                if st4 in (200, 206):
                    break
    except Exception as e:
        print('parse:', e)
