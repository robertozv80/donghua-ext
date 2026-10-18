# -*- coding: utf-8 -*-
"""Sustituye el bloque API de extractTdRumble por la version con warm-up."""
import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

p = r'..\TioDonghuaProvider\src\main\kotlin\com\donghuatiodonghua\TioDonghuaProvider.kt'
data = open(p, 'rb').read()
before = len(data)

start_marker = (b'        // 1) API embedJS: vid = /embed/VID (o watch /VID). embed= es OBLIGATORIO\n'
                b'        // (verificado: sin el par\xc3\xa1metro la API responde 403 Forbidden).\n')
end_marker = b'        // 2) Fallback: HTML del embed (videos antiguos con el data JSON embebido)\n'
i = data.find(start_marker)
j = data.find(end_marker)
assert i != -1 and j != -1 and i < j, (i, j)

snip = open('tio_rumble_apiblock.snippet', 'rb').read().replace(b'\r\n', b'\n')
assert snip.endswith(b'\n')

new = data[:i] + snip + data[j:]
assert new.count(b'embedJS') >= 2
assert b'__cf_bm' in new
open(p, 'wb').write(new)
print('OK', before, '->', len(new))
