#!/usr/bin/env python3
"""Extrae los literales de patron de cada llamada Regex( en los .kt indicados
y genera un Java que los compila, para detectar patrones invalidos en JVM."""
import re
import sys
import pathlib

files = sys.argv[1:]
out = []
case_insensitive_note = []

def kt_unescape(s: str) -> str:
    """Convierte un literal normal de Kotlin (con escapes) al texto real."""
    res = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "n": res.append("\n"); i += 2; continue
            if n == "t": res.append("\t"); i += 2; continue
            if n == "r": res.append("\r"); i += 2; continue
            if n == "\\": res.append("\\"); i += 2; continue
            if n == '"': res.append('"'); i += 2; continue
            if n == "$": res.append("$"); i += 2; continue
            if n == "u":
                hex4 = s[i + 2:i + 6]
                res.append(chr(int(hex4, 16))); i += 6; continue
            res.append(n); i += 2; continue
        res.append(c); i += 1
    return "".join(res)

for f in files:
    p = pathlib.Path(f)
    src = p.read_text(encoding="utf-8")
    i = 0
    idx = 0
    while True:
        j = src.find("Regex(", i)
        if j < 0:
            break
        k = j + len("Regex(")
        # saltar espacios
        while k < len(src) and src[k] in " \t\r\n":
            k += 1
        if src.startswith('"""', k):
            # raw string de 3+ comillas
            m = re.match(r'"{3,}', src[k:])
            nq = len(m.group(0))
            start = k + nq
            end = src.find('"""', start)
            if end < 0:
                i = k; continue
            content = src[start:end]
            idx += 1
            out.append((p.name, idx, content, True))
            i = end + 3
        elif src[k] == '"':
            # literal normal: leer hasta la comilla de cierre no escapada
            q = k + 1
            buf = []
            while q < len(src):
                c = src[q]
                if c == "\\" and q + 1 < len(src):
                    buf.append(c); buf.append(src[q + 1]); q += 2; continue
                if c == '"':
                    break
                buf.append(c); q += 1
            content = "".join(buf)
            idx += 1
            out.append((p.name, idx, kt_unescape(content), False))
            i = q + 1
        else:
            i = k

print("// generado por scan_regex.py")
print("import java.util.regex.Pattern;")
print("public class RegexScan {")
print("    static void t(String where, String p, int flags) {")
print('        try { Pattern.compile(p, flags); System.out.println("OK   " + where + "  ->  " + p); }')
print('        catch (Exception e) { System.out.println("FAIL " + where + "  ->  " + p); System.out.println("      " + e.getMessage()); }')
print("    }")
print("    public static void main(String[] a) {")
for name, idx, content, is_raw in out:
    # escapar para literal Java
    j = content.replace("\\", "\\\\").replace('"', '\\"')
    flags = 0
    print(f'        t("{name}#{idx}", "{j}", {flags});')
print("    }")
print("}")
