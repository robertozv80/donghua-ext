# -*- coding: utf-8 -*-
"""Simula el flujo de loadLinks de TioDonghuaProvider (v24.5) para ep15/16/17."""
import urllib.request, urllib.parse, re, sys

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'

def fetch(url, data=None, refs=None, to=25):
    h = {'User-Agent': UA}
    if refs:
        h['Referer'] = refs
    req = urllib.request.Request(url, data=(urllib.parse.urlencode(data).encode() if data else None), headers=h)
    try:
        r = urllib.request.urlopen(req, timeout=to)
        return r.status, r.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, str(e)

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

ep = sys.argv[1] if len(sys.argv) > 1 else '15'
page = f'https://tiodonghua.lat/episodios/the-secrets-of-star-divine-arts-episodio-{ep}-sub-espanol/'
st, html = fetch(page, refs='https://tiodonghua.lat/')
print('pagina', st, len(html))
postid = re.search(r'postid-(\d+)', html).group(1)
li = re.findall(r"<li id='player-option-(\d+)' class='dooplay_player_option' data-type='([^']*)' data-post='([^']*)' data-nume='([^']*)'>", html)
labels = dict(re.findall(r"<li id='player-option-(\d+)'[^>]*><i[^>]*></i><span class='title'>([^<]*)</span>", html))
print('postid', postid, 'opciones', len(li), 'labels', labels)

results = []
for (idx, dtype, dpost, dnume) in li[:8]:
    st, resp = fetch('https://tiodonghua.lat/wp-admin/admin-ajax.php',
                     data={'action': 'doo_player_ajax', 'post': dpost, 'nume': dnume, 'type': dtype or 'tv'},
                     refs=page)
    embed = td_normalize_embed(fetch_td_player_embed(resp))
    results.append((idx, labels.get(idx, '?'), embed))
    print('nume', dnume, labels.get(idx, '?'), '->', embed)

# Sonda por extractor: que devolveria processTdEmbed
for idx, lab, embed in results:
    if not embed:
        continue
    if 'rumble.com' in embed:
        st, body = fetch(embed, refs='https://tiodonghua.lat/')
        has_hls = 'hls-vod' in body
        print('  probe', lab, 'rumble', st, len(body), 'hls-vod=', has_hls)
    elif 'ok.ru' in embed:
        st, body = fetch(embed, refs='https://tiodonghua.lat/')
        has = 'data-options' in body
        print('  probe', lab, 'ok.ru', st, len(body), 'data-options=', has)
    elif 'streamtape' in embed:
        st, body = fetch(embed, refs='https://tiodonghua.lat/')
        has = 'get_video' in body
        print('  probe', lab, 'streamtape', st, len(body), 'get_video=', has)
    elif 'terabox' in embed or 'duboku' in embed:
        print('  probe', lab, 'terabox -> sin rama propia (loadExtractor?)')
    elif 'd000d' in embed:
        st, body = fetch(embed, refs='https://tiodonghua.lat/')
        print('  probe', lab, 'd000d', st, len(body), 'm3u8=', body.count('.m3u8'), 'packer=', ('eval(function(p,a,c,k,e' in body))
    elif 'voe' in embed:
        st, body = fetch(embed, refs='https://tiodonghua.lat/')
        print('  probe', lab, 'voe', st, len(body), 'm3u8=', body.count('.m3u8'), 'packer=', ('eval(function(p,a,c,k,e' in body))
    elif 'embedme' in embed:
        st, body = fetch(embed, refs='https://tiodonghua.lat/')
        print('  probe', lab, 'embedme', st, len(body), 'm3u8=', body.count('.m3u8'), 'packer=', ('eval(function(p,a,c,k,e' in body))
    else:
        print('  probe', lab, embed[:70])
