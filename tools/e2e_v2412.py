# -*- coding: utf-8 -*-
"""Validacion E2E v24.12:
A) DonghuaLife tag Estado: replica el parseo de pills + fallback RSC sobre
   las series de prueba y muestra statusText/showStatus/tags resultantes.
B) TioDonghua ep15/16: replica loadLinks + extractTdRumble NUEVO (API embedJS)
   y confirma que se obtienen MP4 por calidad.
"""
import http.cookiejar, urllib.request, urllib.parse, re, sys, gzip, io, time
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def get_retry(url, refs=None, to=25, data=None, tries=3):
    st, body = -1, ''
    for k in range(tries):
        st, body = get(url, refs=refs, to=to, data=data)
        if st == 200:
            return st, body
        time.sleep(2.5 * (k + 1))
    return st, body

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
opener.addheaders = [('User-Agent', UA)]

def get(url, refs=None, to=25, data=None):
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

print('================ A) DONGHUALIFE: TAG ESTADO ================')

def extract_rsc_payload(html):
    payloads = re.findall(r'self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)', html)
    sb = []
    for p in payloads:
        d = p.replace('\\"', '"').replace('\\\\', '\\').replace('\\n', '\n').replace('\\/', '/').replace('\\u0026', '&')
        sb.append(d)
    return ''.join(sb)

def dl_status(series_url):
    st, html = get_retry(series_url, to=40, tries=4)
    if st != 200:
        print(' ', series_url, 'HTTP', st)
        return
    # DOM pills (como Jsoup: clases separadas por espacio)
    spans = re.findall(r'<span class="text-xs font-black uppercase tracking-widest"[^>]*>(.*?)</span>', html)
    statusText = ''
    releaseDate = ''
    status = None
    for raw in spans:
        text = re.sub(r'<[^>]+>', '', raw).strip()
        if not text:
            continue
        if any(k in text.lower() for k in ('emisión', 'emision', 'pausa', 'ongoing')):
            status, statusText = 'Ongoing', text
        elif any(k in text.lower() for k in ('finalizado', 'completed')):
            status, statusText = 'Completed', text
        elif re.match(r'\d+\s+de?\s*[a-záéíóú]+,?\s+\d{4}$', text, re.I) or re.match(r'\d+\s+[a-záéíóú]+,?\s+\d{4}$', text, re.I):
            releaseDate = text
    if not statusText:
        rsc = extract_rsc_payload(html)
        m = re.search(r'"text-xs font-black uppercase tracking-widest","children":"([^"]+)"', rsc)
        if m:
            statusText = m.group(1).strip()
            if any(k in statusText.lower() for k in ('finalizado', 'completed')):
                status = 'Completed'
            elif any(k in statusText.lower() for k in ('emisión', 'emision', 'pausa', 'ongoing')):
                status = 'Ongoing'
    print(' ', series_url.rsplit("/", 1)[-1] or series_url)
    print('   statusText =', repr(statusText), '| showStatus =', status, '| releaseDate =', releaseDate)

dl_status('https://donghualife.com/series/el-inmortal-renegado')
dl_status('https://donghualife.com/series/demons-ascension')

print('================ B) TIODONGHUA: EP15/16 RUMBLE ================')

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

def process_rumble(embedUrl):
    """Replica el extractTdRumble v24.12: API embedJS primero."""
    vid = re.search(r'rumble\.com/(?:embed/)?(v[a-zA-Z0-9]+)', embedUrl)
    if not vid:
        return []
    api = 'https://rumble.com/embedJS/u3/?request=video&v=' + vid.group(1) + \
          '&embed=' + urllib.parse.quote(embedUrl, safe='')
    st, json_ = get_retry(api, refs='https://rumble.com/', tries=4)
    if st != 200:
        print('   embedJS HTTP', st)
        return []
    fixed = json_.replace('\\/', '/')
    links = []
    for m in re.finditer(r'"(\d{3,4})":\["(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"', fixed):
        links.append((int(m.group(1)), m.group(2)))
    return links

def load_links_sim(ep):
    page = f'https://tiodonghua.lat/episodios/the-secrets-of-star-divine-arts-episodio-{ep}-sub-espanol/'
    st, html = get(page)
    li = re.findall(r"<li id='player-option-(\d+)' class='dooplay_player_option' data-type='([^']*)' data-post='([^']*)' data-nume='([^']*)'>", html)
    labels = dict(re.findall(r"<li id='player-option-(\d+)'[^>]*><i[^>]*></i><span class='title'>([^<]*)</span>", html))
    print(' EP', ep, '-', len(li), 'opciones')
    total = 0
    for (idx, dtype, dpost, dnume) in li:
        st, resp = get_retry('https://tiodonghua.lat/wp-admin/admin-ajax.php',
                       data={'action': 'doo_player_ajax', 'post': dpost, 'nume': dnume, 'type': dtype or 'tv'}, refs=page, tries=2)
        emb = td_normalize_embed(fetch_td_player_embed(resp))
        label = labels.get(idx, '?')
        if not emb:
            print(f'   {label}: (sin embed)')
            continue
        if 'rumble.com' in emb:
            links = process_rumble(emb)
            for q, u in sorted(links):
                # validar cabecera del mp4 con Range
                req = urllib.request.Request(u, headers={'User-Agent': UA, 'Referer': 'https://rumble.com/', 'Range': 'bytes=0-63'})
                try:
                    r = opener.open(req, timeout=15)
                    ct = r.headers.get('Content-Type')
                    print(f'   {label}: MP4 {q}p OK [{r.status} {ct}] {u[:70]}')
                    total += 1
                except Exception as e:
                    print(f'   {label}: MP4 {q}p FALLO {e}')
        else:
            print(f'   {label}: {emb[:80]} (otro host, sin rama dedicada)')
    print('   => enlaces rumble validados:', total)

time.sleep(4)
load_links_sim('15')
time.sleep(4)
load_links_sim('16')
