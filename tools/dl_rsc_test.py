# -*- coding: utf-8 -*-
"""Prueba la regex del fallback RSC (mismo algoritmo que DonghuaLifeProvider)."""
import urllib.request, re, sys, time
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'


def extract_rsc_payload(html):
    payloads = re.findall(r'self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)', html)
    sb = []
    for p in payloads:
        # MISMO orden de reemplazos que el provider Kotlin:
        # \" -> " ; \\ -> \ ; \n ; \/ ; \u0026
        d = (p.replace('\\"', '"')
              .replace('\\\\', '\\')
              .replace('\\n', '\n')
              .replace('\\/', '/')
              .replace('\\u0026', '&'))
        sb.append(d)
    return ''.join(sb)


for slug in ['el-inmortal-renegado', 'demons-ascension']:
    html = ''
    for k in range(3):
        try:
            req = urllib.request.Request(f'https://donghualife.com/series/{slug}', headers={'User-Agent': UA})
            html = urllib.request.urlopen(req, timeout=40).read().decode('utf-8', 'replace')
            break
        except Exception as e:
            print('retry', slug, e)
            time.sleep(3)
    rsc = extract_rsc_payload(html)
    m = re.search(r'"text-xs font-black uppercase tracking-widest","children":"([^"]+)"', rsc)
    print(slug, '-> badge RSC:', m.group(1) if m else None, '| payload len:', len(rsc))
