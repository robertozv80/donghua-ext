# -*- coding: utf-8 -*-
"""Reemplaza extractTdRumble en TioDonghuaProvider.kt (bytes verbatim)."""
import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

p = r'..\TioDonghuaProvider\src\main\kotlin\com\donghuatiodonghua\TioDonghuaProvider.kt'
data = open(p, 'rb').read()

start_marker = b'/**\n * v24: extractor de Rumble.'
end_marker = b'/**\n * v24: extractor de StreamTape.'
i = data.find(start_marker)
j = data.find(end_marker)
assert i != -1 and j != -1 and i < j, (i, j)

snip = open('tio_rumble_new.ktsnippet', 'rb').read().replace(b'\r\n', b'\n')
assert snip.endswith(b'\n\n')

new = data[:i] + snip[:-1] + data[j:]
assert b'v24.12' in new
assert new.count(b'private suspend fun extractTdRumble') == 1
assert new.find(b'extractor de StreamTape') > new.find(b'embedJS')

open(p, 'wb').write(new)
print('OK old_len', len(data), 'new_len', len(new))
