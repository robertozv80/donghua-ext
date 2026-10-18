# -*- coding: utf-8 -*-
"""v24.13 - Terabox parte 10: /share/streaming anonimo.
A) sign+timestamp de shorturlinfo (receta rishi058), jsToken vacio
B) sign=md5(clienttype+channel+browserid+ts) (formula del embed), jsToken del page
Contar segmentos del m3u8 devuelto (1 chunk vs todos).
"""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, sys, time, json, random, hashlib
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
         'Accept': '*/*'}
    if refs: h['Referer'] = refs
    if extra: h.update(extra)
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r), r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace'), e.geturl()
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}', url

surl = 'NFqQvtSCh-IORMMEksjxHQ'
fid = '848169177574147'
BASE = 'https://dm.1024tera.com'
refs = f'{BASE}/wap/share/filelist?surl={surl}'

st, html, fu = get(f'{BASE}/wap/share/filelist?surl={surl}&clearCache=1', refs=refs)
tok = ''
for rx in [r'fn%28%22([A-Fa-f0-9]+)%22%29', r'fn\("([A-Fa-f0-9]+)"\)']:
    mt = re.search(rx, html)
    if mt: tok = mt.group(1); break
browserid = next((c.value for c in cj if c.name == 'browserid'), '')
print('filelist HTTP', st, '| jsToken:', bool(tok), '| browserid:', browserid[:16] + '..')

params = {'app_id': '250528', 'shorturl': '1' + surl, 'root': '1', 'web': '1',
          'channel': 'dubox', 'clienttype': '0', 'jsToken': tok, 't': str(int(time.time())),
          'dp-logid': str(random.randint(400_000_000_000_000_000, 999_999_999_999_999_999))}
st2, j2, fu2 = get(BASE + '/api/shorturlinfo?' + urllib.parse.urlencode(params),
                   refs=refs, extra={'Origin': BASE, 'Accept': 'application/json, text/plain, */*'})
info = json.loads(j2)
print('shorturlinfo errno', info.get('errno'))
shareid, uk = info['shareid'], info['uk']
sign, ts = info['sign'], info['timestamp']
print('shareid', shareid, 'uk', uk, 'sign', sign[:16] + '..', 'ts', ts)

def show_m3u8(tag, stx, text, fux):
    print('=' * 70)
    print(tag, '-> HTTP', stx)
    if stx != 200:
        print('  cuerpo:', text[:200]); return
    t = text.strip()
    if not t.startswith('#EXTM3U'):
        print('  NO m3u8:', text[:200]); return
    segs = [l for l in t.split('\n') if l.strip() and not l.startswith('#')]
    print('  m3u8 VALIDO | segmentos:', len(segs))
    print('  cabecera:', t[:300].replace('\n', ' | ')[:300])
    for s in segs[:3]: print('   seg:', s[:180])

# A) sign de shorturlinfo, jsToken vacio
q = urllib.parse.urlencode({
    'uk': str(uk), 'shareid': str(shareid), 'type': 'M3U8_AUTO_1080', 'fid': str(fid),
    'sign': sign, 'timestamp': str(ts), 'jsToken': '', 'esl': '1', 'isplayer': '1',
    'ehps': '1', 'clienttype': '0', 'app_id': '250528', 'web': '1', 'channel': 'dubox',
    'dp-logid': str(random.randint(400_000_000_000_000_000, 999_999_999_999_999_999)),
})
stA, tA, fuA = get(f'{BASE}/share/streaming?{q}', refs=refs)
show_m3u8('A) sign shorturlinfo', stA, tA, fuA)
time.sleep(1)
stA2, tA2, fuA2 = get(f'{BASE}/share/streaming?{q}', refs=refs)
show_m3u8('A2) repeticion (random chunk?)', stA2, tA2, fuA2)

# B) md5(clienttype + channel + browserid + ts) como el embed
ts2 = str(int(time.time()))
signB = hashlib.md5(('0' + 'dubox' + browserid + ts2).encode()).hexdigest()
qB = urllib.parse.urlencode({
    'uk': str(uk), 'shareid': str(shareid), 'type': 'M3U8_AUTO_1080', 'fid': str(fid),
    'sign': signB, 'timestamp': ts2, 'jsToken': tok, 'esl': '1', 'isplayer': '1',
    'ehps': '1', 'clienttype': '0', 'app_id': '250528', 'web': '1', 'channel': 'dubox',
    'short_link': surl,
})
stB, tB, fuB = get(f'{BASE}/share/streaming?{qB}', refs=refs)
show_m3u8('B) sign md5 embed', stB, tB, fuB)
