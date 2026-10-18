# -*- coding: utf-8 -*-
"""v24.13 - Terabox parte 9: receta completa (dm.1024tera.com):
1) /wap/share/filelist?surl=..&clearCache=1 -> jsToken (fn%28%22HEX%22%29)
2) /api/shorturlinfo?shorturl=1<surl>&t=&dp-logid= -> shareid/uk/sign/timestamp/fs_id
3) /share/download -> dlink -> Range
"""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, ssl, sys, time, json, random
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, to=60, extra=None):
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
BASE = 'https://dm.1024tera.com'
refs = f'{BASE}/wap/share/filelist?surl={surl}'

print('=== 1) /wap/share/filelist (plantea cookies + jsToken) ===')
st, html, hh, fu = get(f'{BASE}/wap/share/filelist?surl={surl}&clearCache=1', refs=refs)
print('HTTP', st, 'len', len(html), '| final:', fu[:90])
print('cookies:', [c.name for c in cj])
tok = ''
for rx in [r'fn%28%22([A-Fa-f0-9]+)%22%29', r'fn\("([A-Fa-f0-9]+)"\)']:
    mt = re.search(rx, html)
    if mt: tok = mt.group(1); break
print('jsToken:', (tok[:50] + f'...({len(tok)})') if tok else 'NO ENCONTRADO')
if not tok:
    sys.exit(1)

print('=== 2) /api/shorturlinfo ===')
params = {
    'app_id': '250528', 'shorturl': '1' + surl, 'root': '1',
    'web': '1', 'channel': 'dubox', 'clienttype': '0',
    'jsToken': tok, 't': str(int(time.time())),
    'dp-logid': str(random.randint(400_000_000_000_000_000, 999_999_999_999_999_999)),
}
u = BASE + '/api/shorturlinfo?' + urllib.parse.urlencode(params)
st2, j2, hh2, fu2 = get(u, refs=refs, extra={'Origin': BASE, 'Accept': 'application/json, text/plain, */*'})
print('HTTP', st2, 'len', len(j2))
print(j2[:600])
info = {}
try:
    info = json.loads(j2)
except Exception:
    pass
if info.get('errno') != 0:
    print('shorturlinfo errno', info.get('errno'), '-> probar terabox.com')
    BASE2 = 'https://www.terabox.com'
    refs2 = f'{BASE2}/wap/share/filelist?surl={surl}'
    st, html, hh, fu = get(f'{BASE2}/wap/share/filelist?surl={surl}&clearCache=1', refs=refs2)
    print('  filelist HTTP', st, 'len', len(html), '| cookies:', [c.name for c in cj])
    tok = ''
    for rx in [r'fn%28%22([A-Fa-f0-9]+)%22%29', r'fn\("([A-Fa-f0-9]+)"\)']:
        mt = re.search(rx, html)
        if mt: tok = mt.group(1); break
    print('  jsToken:', (tok[:40] + f'...({len(tok)})') if tok else 'NO ENCONTRADO')
    if tok:
        params['jsToken'] = tok
        u = BASE2 + '/api/shorturlinfo?' + urllib.parse.urlencode(params)
        st2, j2, hh2, fu2 = get(u, refs=refs2, extra={'Origin': BASE2, 'Accept': 'application/json, text/plain, */*'})
        print('  shorturlinfo HTTP', st2, ':', j2[:400])
        try: info = json.loads(j2)
        except Exception: info = {}
    BASE = BASE2

if info.get('errno') != 0:
    print('=== NO VIABLE: shorturlinfo errno', info.get('errno'), '===')
    sys.exit(1)

shareid = info.get('shareid'); uk = info.get('uk')
sign = info.get('sign'); ts = info.get('timestamp')
files = info.get('list', [])
fsid = next((f_.get('fs_id') for f_ in files if str(f_.get('fs_id')) == fid), None) or (files[0].get('fs_id') if files else None)
print('shareid', shareid, '| uk', uk, '| sign len', len(sign or ''), '| timestamp', ts)
for f_ in files[:3]:
    print('  file:', f_.get('fs_id'), f_.get('server_filename'), f_.get('size'))

print('=== 3) /share/download ===')
dl_params = {
    'app_id': '250528', 'web': '1', 'channel': 'dubox', 'clienttype': '0',
    'jsToken': tok, 'shareid': str(shareid), 'uk': str(uk),
    'sign': sign, 'timestamp': str(ts), 'primaryid': str(shareid),
    'fid_list': json.dumps([int(fsid)]), 'product': 'share', 'nozip': '0',
    'dp-logid': str(random.randint(400_000_000_000_000_000, 999_999_999_999_999_999)),
}
u2 = BASE + '/share/download?' + urllib.parse.urlencode(dl_params)
st3, j3, hh3, fu3 = get(u2, refs=refs, extra={'Origin': BASE, 'Accept': 'application/json, text/plain, */*'})
print('HTTP', st3, ':', j3[:400])
try:
    j3j = json.loads(j3)
    dl = j3j.get('dlink')
    print('errno', j3j.get('errno'), '| dlink:', (dl[:160] + '...') if dl else None)
    if dl:
        print('=== 4) dlink Range 0-1023 ===')
        st4, b4, hh4, fu4 = get(dl + '&origin=dlna', refs=None, to=90, extra={'Range': 'bytes=0-1023'})
        print('HTTP', st4, '| len', len(b4), '| magic:', b4[:16])
        if hh4:
            print('  CT:', hh4.get('Content-Type'), '| CR:', hh4.get('Content-Range'), '| CL:', hh4.get('Content-Length'))
        print('  final url:', fu4[:220])
except Exception as e:
    print('parse:', e)
