# -*- coding: utf-8 -*-
"""Sustituye el bloque fallback (lineas 510-525 CRLF) por la version con orEmpty."""
import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

p = r'..\DonghuaLifeProvider\src\main\kotlin\com\donghuaext\DonghuaLifeProvider.kt'
data = open(p, 'rb').read()

start_marker = b'        if (statusText.isBlank()) {\r\n'
end_marker = b'        for (span in pillSpans) {\r\n'
i = data.find(start_marker)
j = data.find(end_marker)
assert i != -1 and j != -1 and i < j, (i, j)

snip = open('dl_fallback_block.snippet', 'rb').read().replace(b'\r\n', b'\n')
# el snippet termina en '\n\n' (linea en blanco); el original tenia 1 linea en blanco antes del for
assert snip.endswith(b'\n\n')

new = data[:i] + snip[:-1] + data[j:]
open(p, 'wb').write(new)
print('OK', len(data), '->', len(new))
