# -*- coding: utf-8 -*-
"""Prueba la API embedJS de Rumble y lista servidores del ep16."""
import http.cookiejar, urllib.request, urllib.parse, re, sys, gzip, io
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
opener.addheaders = [('User-Agent', UA), ('Accept-Language', 'es-ES,es;q=0.8')]

def get(url, data=None, refs=None, to=25):
    h = {'Referer': refs} if refs else {}
    req = urllib.request.Request(url, data=(urllib.parse.urlencode(data).encode() if data else None), headers=h)
    try:
        r = opener.open(req, timeout=to)
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        return r.status, raw.decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

def fetch_td_player_embed(resp):
    m = re.search(r'"embed_url"\s*:\s*"((?:[^"\\]|\\.)*)"', resp)
    return m.group(1) if m else ''

def td_normalize_embed(raw):
    t = raw.replace('\\"', '"').replace('\\/', '/').strip()
    if t.startswith('http'):
        end = len(t)
        for i, ch in enumerate(t):
            if ch in ('"', '\\'):
                end = i
                break
        return t[:end].strip() or None
    if '<iframe' in t.lower() or '<div' in t.lower():
        m = re.search(r'src\s*=\s*["\']([^"\']+)["\']', t, re.IGNORECASE)
        if m:
            return m.group(1).strip()
    return None

# --- ep16: opciones y AJAX ---
page16 = 'https://tiodonghua.lat/episodios/the-secrets-of-star-divine-arts-episodio-16-sub-espanol/'
st, html16 = get(page16)
postid16 = re.search(r'postid-(\d+)', html16).group(1)
li16 = re.findall(r"<li id='player-option-(\d+)' class='dooplay_player_option' data-type='([^']*)' data-post='([^']*)' data-nume='([^']*)'>", html16)
labels16 = dict(re.findall(r"<li id='player-option-(\d+)'[^>]*><i[^>]*></i><span class='title'>([^<]*)</span>", html16))
print('EP16 postid', postid16, 'opciones', len(li16))
rumble16 = None
for (idx, dtype, dpost, dnume) in li16:
    st, resp = get('https://tiodonghua.lat/wp-admin/admin-ajax.php',
                   data={'action': 'doo_player_ajax', 'post': dpost, 'nume': dnume, 'type': dtype or 'tv'}, refs=page16)
    emb = td_normalize_embed(fetch_td_player_embed(resp))
    print('  nume', dnume, labels16.get(idx, '?'), '->', emb)
    if emb and 'rumble.com' in emb and rumble16 is None:
        rumble16 = emb

# --- Rumble embedJS API ---
for ep, emb in [('15', 'https://rumble.com/embed/v4g6xit/?pub=3am9tf'), ('16', rumble16)]:
    if not emb:
        continue
    vid = re.search(r'rumble\.com/embed/([a-z0-9]+)', emb).group(1)
    api = f'https://rumble.com/embedJS/u3/?request=video&v={vid}'
    st, body = get(api, refs=emb)
    print('=' * 60)
    print('EP', ep, 'vid', vid, 'embedJS', st, 'len', len(body))
    fixed = body.replace('\\/', '/')
    hls = re.findall(r'"hls":\{"url":"(https://rumble\.com/hls-vod/[^"]+)"', fixed)
    print('  hls:', hls[:1])
    mp4s = re.findall(r'"(\d{3,4})":\{"url":"(https://[^"]+\.mp4)"', fixed)
    print('  mp4s:', mp4s[:3])
    if hls:
        # validar el manifiesto
        st2, m3 = get(hls[0], refs='https://rumble.com/')
        print('  m3u8 validacion:', st2, m3[:60].replace('\n', ' '))
