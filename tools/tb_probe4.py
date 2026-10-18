# -*- coding: utf-8 -*-
"""v24.13 - Terabox parte 4: jsToken del EMBED (476KB) -> shorturlinfo / download.
Guarda el HTML en tools/tb_embed.html para grep.
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

print('=== GET embed ===')
st, html, hh, fu = get(emb, refs='https://tiodonghua.lat/')
print('HTTP', st, 'len', len(html))
with open('tools/tb_embed.html', 'w', encoding='utf-8', errors='replace') as f:
    f.write(html)

print('=== contexto jsToken ===')
for m in re.finditer(r'jsToken', html):
    a, b = max(0, m.start() - 120), m.start() + 180
    print('...', html[a:b].replace('\n', ' ')[:300])
    print('---')

tok = ''
for rx in [r'"jsToken"\s*:\s*"([^"]+)"', r'jsToken["\']?\s*[:=]\s*["\']([^"\']{40,})["\']',
           r'jsToken=([^&"\'\s<]{40,})']:
    mt = re.search(rx, html)
    if mt:
        tok = mt.group(1)
        print('jsToken EXTRAIDO len', len(tok), ':', tok[:80])
        break

print('=== otras APIs en el embed ===')
apis = sorted(set(re.findall(r'(?:https?:)?//[a-z0-9.-]*terabox[^"\'\s<>\\]*(?:api|share|embed|list|download|stream)[^"\'\s<>\\]*', html)))
for a in apis[:20]: print('  ', a[:160])
# llamadas ajax en el JS inline
for m in re.finditer(r'url\s*:\s*["\']([^"\']{6,120})["\']', html):
    u = m.group(1)
    if 'api' in u or 'share' in u or 'download' in u:
        print('  ajax url:', u[:140])

print('=== cookies tras embed ===')
print([c.name for c in cj])

if tok:
    print('=== shorturlinfo con jsToken ===')
    u = (f'https://www.terabox.com/api/shorturlinfo?app_id=250528&web=1&channel=dubox'
         f'&clienttype=0&jsToken={urllib.parse.quote(tok, safe="")}&shorturl={surl}&root=1')
    st2, j2, hh2, fu2 = get(u, refs='https://www.terabox.com/')
    print('HTTP', st2, 'len', len(j2))
    print(j2[:700])
    try:
        jj = json.loads(j2)
        shid = jj.get('shareid')
        print('errno', jj.get('errno'), '| shareid', shid, '| uk', jj.get('uk'))
        if shid:
            print('=== share/download con jsToken ===')
            u2 = (f'https://www.terabox.com/share/download?app_id=250528&web=1&channel=dubox'
                  f'&clienttype=0&jsToken={urllib.parse.quote(tok, safe="")}'
                  f'&shareid={shid}&uk={jj.get("uk", uk)}&fid_list=[{fid}]'
                  f'&primaryid={shid}&product=share&nozip=0')
            st3, j3, hh3, fu3 = get(u2, refs='https://www.terabox.com/')
            print('HTTP', st3, ':', j3[:400])
            try:
                dl = json.loads(j3).get('dlink')
                if dl:
                    print('DLINK:', dl[:180])
                    st4, b4, hh4, fu4 = get(dl + '&origin=dlna', refs=None, to=60,
                                            extra={'Range': 'bytes=0-1023'})
                    print('Range HTTP', st4, '| len', len(b4), '| magic:', b4[:16])
                    if hh4:
                        print('  CT:', hh4.get('Content-Type'), '| CR:', hh4.get('Content-Range'))
            except Exception as e:
                print('  parse:', e)
    except Exception as e:
        print('parse:', e)
else:
    print('SIN jsToken extraible del embed')
