# -*- coding: utf-8 -*-
"""Fix v24.12 en DonghuaLifeProvider.kt:
1) renombra var local showStatus -> parsedShowStatus (shadowing en el lambda);
2) asigna showStatus = parsedShowStatus en newAnimeLoadResponse;
3) fallback RSC para el badge de estado.
"""
import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

p = r'..\DonghuaLifeProvider\src\main\kotlin\com\donghuaext\DonghuaLifeProvider.kt'
data = open(p, 'rb').read()
orig_len = len(data)

def crlf(s):
    return s.replace(b'\n', b'\r\n') if b'\r\n' not in s else s

# 1) Renombrar la declaracion local y sus dos asignaciones (unicas, no-qualificadas)
old_decl = b'\r\n        var showStatus: ShowStatus? = null\r\n        var statusText = \x22\x22\r\n'
new_decl = b'\r\n        var parsedShowStatus: ShowStatus? = null\r\n        var statusText = \x22\x22\r\n'
assert data.count(old_decl) == 1, data.count(old_decl)
data = data.replace(old_decl, new_decl)

old_ongoing = b'\r\n                    showStatus = ShowStatus.Ongoing\r\n'
assert data.count(old_ongoing) == 1, data.count(old_ongoing)
data = data.replace(old_ongoing, b'\r\n                    parsedShowStatus = ShowStatus.Ongoing\r\n')

old_completed = b'\r\n                    showStatus = ShowStatus.Completed\r\n'
assert data.count(old_completed) == 1, data.count(old_completed)
data = data.replace(old_completed, b'\r\n                    parsedShowStatus = ShowStatus.Completed\r\n')

# 2) En newMovieLoadResponse no se referencia (solo ShowStatus.*). Verificar:
assert data.count(b'\r\n            showStatus?.let { this.showStatus = it }\r\n') == 0

# 3) Respuesta serie: asignacion con la variable renombrada
old_assign = b'\r\n            showStatus = showStatus\r\n'
assert data.count(old_assign) == 1, data.count(old_assign)
data = data.replace(old_assign, b'\r\n            showStatus = parsedShowStatus\r\n')

# 4) Fallback RSC: si no hubo pill en el DOM, buscar el badge en el payload RSC
anchor = b'        val pillSpans = doc.select(\x22span.text-xs.font-black.uppercase.tracking-widest\x22)\r\n'
assert data.count(anchor) == 1, data.count(anchor)
fallback = (
    b'        // v24.12 FALLBACK RSC: el badge de estado no siempre llega en el HTML\r\n'
    b'        // estatico (render client-side). En el payload RSC el badge aparece como\r\n'
    b'        // ...\"text-xs font-black uppercase tracking-widest\",\"children\":\"<texto>\".\r\n'
    b'        if (statusText.isBlank()) {\r\n'
    b'            val rscStatus = Regex(\r\n'
    b'                \x22\\\\x22text-xs font-black uppercase tracking-widest\\\\x22,\\\\x22children\\\\x22:\\\\x22([^\\\\x22]+)\\\\x22\x22\r\n'
    b'            ).find(rscPayload)?.groupValues?.get(1)?.trim()\r\n'
    b'            if (rscStatus.isNotBlank()) {\r\n'
    b'                statusText = rscStatus\r\n'
    b'                if (rscStatus.contains(\x22Finalizado\x22, ignoreCase = true) ||\r\n'
    b'                    rscStatus.contains(\x22Completed\x22, ignoreCase = true)\r\n'
    b'                ) parsedShowStatus = ShowStatus.Completed\r\n'
    b'                else if (rscStatus.contains(\x22Emisi\xC3\xB3n\x22, ignoreCase = true) ||\r\n'
    b'                    rscStatus.contains(\x22Emision\x22, ignoreCase = true) ||\r\n'
    b'                    rscStatus.contains(\x22Pausa\x22, ignoreCase = true) ||\r\n'
    b'                    rscStatus.contains(\x22Ongoing\x22, ignoreCase = true)\r\n'
    b'                ) parsedShowStatus = ShowStatus.Ongoing\r\n'
    b'            }\r\n'
    b'        }\r\n'
    b'\r\n'
)
data = data.replace(anchor, anchor + fallback)
assert len(data) > orig_len
open(p, 'wb').write(data)
print('OK', orig_len, '->', len(data))
