# -*- coding: utf-8 -*-
"""v24.13 - Sonda Terabox (servidor TB de tiodonghua):
hay API publica extraible sin cookies de login (ndus)?
1) embed TB del ep15 via doo_player_ajax
2) HTML del embed: fuentes estaticas? APIs? jsToken?
3) share page /s/<surl>
4) shorturlinfo / share/list sin login (varios dominios)
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

def get(url, refs=None, to=30):
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

def post(url, data, refs=None, to=30):
    h = {'User-Agent': UA, 'Content-Type': 'application/x-www-form-urlencoded'}
    if refs: h['Referer'] = refs
    body = urllib.parse.urlencode(data).encode()
    try:
        r = opener.open(urllib.request.Request(url, data=body, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

def retry(fn, *a, **k):
    st, b = -1, ''
    for i in range(4):
        st, b = fn(*a, **k)
        if st == 200:
            return st, b
        time.sleep(2.5 * (i + 1))
    return st, b

print('=== 1) embed TB del ep15 (post 63374 nume 3) ===')
st, body = retry(post, 'https://tiodonghua.lat/wp-admin/admin-ajax.php',
                 {'action': 'doo_player_ajax', 'post': '63374', 'nume': '3', 'type': 'tv'},
                 refs='https://tiodonghua.lat/episodios/the-secrets-of-star-divine-arts-episodio-15-sub-espanol/')
m = re.search(r'"embed_url"\s*:\s*"((?:[^"\\]|\\.)*)"', body)
embed = m.group(1).replace('\\"', '"').replace('\\/', '/') if m else ''
print('HTTP', st, 'embed:', embed[:200])
ms = re.search(r'[?&]surl=([^&]+)', embed)
surl = ms.group(1) if ms else ''
print('surl:', surl)
if not embed:
    sys.exit(1)

print('=== 2) HTML del embed ===')
st, html = retry(get, embed, refs='https://tiodonghua.lat/')
print('HTTP', st, 'len', len(html))
for pat in ['jsToken', 'shorturl', 'dlink', 'videoList', 'ndus', 'window.__INITIAL', 'playUrl']:
    print(f'  {pat}: {html.count(pat)}')
mp4 = re.findall(r'https?://[^"\'\s\\]+\.mp4[^"\'\s\\]*', html)
m3u8 = re.findall(r'https?://[^"\'\s\\]+\.m3u8[^"\'\s\\]*', html)
print('  mp4:', len(mp4), '| m3u8:', len(m3u8))
apis = sorted(set(re.findall(r'https?://[a-z0-9.-]*terabox[^"\'\s\\<>]+', html)))[:12]
for u in apis: print('  URL terabox:', u[:150])
js = re.findall(r'src="(https?://[^"]+\.js[^"]*)"', html)[:10]
for u in js: print('  JS chunk:', u[:150])

if surl:
    print('=== 3) share page /s/' + surl + ' ===')
    st, sp = retry(get, 'https://terabox.com/s/' + surl, refs='https://terabox.com/')
    print('HTTP', st, 'len', len(sp))
    for pat in ['jsToken', 'shareid', '"uk"', '"fid"', 'file_list', 'dlink', 'ndus', 'login']:
        print(f'  {pat}: {sp.count(pat)}')
    mt = re.search(r'jsToken["\'\s:=]+([A-Za-z0-9%._-]{40,})', sp)
    print('  jsToken extraible:', bool(mt))

    print('=== 4) APIs publicas sin login ===')
    variants = {surl, '1' + surl}
    for base in ['https://www.terabox.com', 'https://www.terabox.app', 'https://terabox.com']:
        for s in sorted(variants):
            u = f'{base}/api/shorturlinfo?app_id=250528&web=1&channel=dubox&clienttype=0&shorturl={s}&root=1'
            st, j = get(u, refs='https://terabox.com/')
            errno, keys = '?', ''
            try:
                jj = json.loads(j)
                errno = jj.get('errno', '?')
                keys = ','.join(list(jj.keys())[:12])
            except Exception:
                pass
            print(f'  shorturlinfo {base} surl={s[:14]} HTTP {st} len {len(j)} errno {errno} keys [{keys}]')
            if st == 200 and errno == 0:
                print('   ', j[:500])
            time.sleep(1)
    for base in ['https://www.terabox.com', 'https://www.terabox.app']:
        u = f'{base}/share/list?app_id=250528&web=1&channel=dubox&clienttype=0&shorturl={surl}&root=1'
        st, j = get(u, refs='https://terabox.com/')
        print(f'  share/list {base} HTTP {st} len {len(j)}: {j[:220]}')
        time.sleep(1)
