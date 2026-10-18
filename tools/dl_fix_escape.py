# -*- coding: utf-8 -*-
"""Normaliza \\x22 -> \\\" dentro del fallback RSC recien anadido."""
import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

p = r'..\DonghuaLifeProvider\src\main\kotlin\com\donghuaext\DonghuaLifeProvider.kt'
data = open(p, 'rb').read()
old = b'\x5c\x5cx22'   # \\x22 en el fuente Kotlin
new = b'\x5c\x22'      # \" en el fuente Kotlin
n = data.count(old)
assert n == 7, n
data = data.replace(old, new)
open(p, 'wb').write(data)
print('reemplazos:', n)
