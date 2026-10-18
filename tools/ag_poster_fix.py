#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Fix v24.15 AnimeGratis: bug del póster repetido en fichas cuyo póster real
# está en el CDN alternativo cdn.jkdesa.com (el primer img[src*=cdn.animegratis]
# del documento era la primera card de recomendaciones).
import sys

PATH = "AnimeGratisProvider/src/main/kotlin/com/donghuaext/AnimeGratisProvider.kt"
d = open(PATH, "rb").read().decode("utf-8")
assert d.count("\r\n") == d.count("\n"), "se esperaba CRLF puro"
NL = "\r\n"

OLD = (
    "    ): String {" + NL +
    "        // 1) Buscar imagen CDN en el HTML (SIEMPRE funciona)" + NL +
    "        val cdnSelectors = listOf(" + NL +
    "            \"img[src*=\\\"cdn.animegratis\\\"]\"," + NL +
    "            \"img[data-fallback*=\\\"cdn.animegratis\\\"]\"," + NL +
    "            \"img[data-fb2*=\\\"cdn.animegratis\\\"]\"," + NL +
    "        )"
)

NEW = (
    "    ): String {" + NL +
    "        // 0) Póster real de la ficha (fix v24.15 - bug del póster repetido)." + NL +
    "        //    El póster principal es el único img del documento con" + NL +
    "        //    loading=\"eager\" + width=\"220\" y alt no vacío (\"Portada de X\" en" + NL +
    "        //    donghua, \"Ver X online gratis - Portada oficial...\" en anime); el" + NL +
    "        //    fondo desenfocado del mismo recuadro va con alt=\"\" y aria-hidden," + NL +
    "        //    y las cards de recomendaciones/episodios son loading=\"lazy\" o" + NL +
    "        //    width=\"320\". Antes se tomaba el primer img[src*=cdn.animegratis]" + NL +
    "        //    del documento, que en las fichas cuyo póster real está en el CDN" + NL +
    "        //    alternativo (cdn.jkdesa.com) era la primera card de" + NL +
    "        //    recomendaciones: todos esos títulos cargaban el mismo póster." + NL +
    "        val posterCandidates = doc.select(\"img[loading=eager][width=220]\")" + NL +
    "            .filter { it.attr(\"alt\").isNotBlank() && !it.hasAttr(\"aria-hidden\") }" + NL +
    "        if (posterCandidates.isNotEmpty()) {" + NL +
    "            // Preferir el de la zona de póster (div.max-w-[220px])" + NL +
    "            val poster = posterCandidates.firstOrNull { img ->" + NL +
    "                img.parents().any { p ->" + NL +
    "                    p.tagName() == \"div\" && p.className().contains(\"max-w-[220px]\")" + NL +
    "                }" + NL +
    "            } ?: posterCandidates.first()" + NL +
    "            val url = poster.bestImageUrl()" + NL +
    "            if (url.startsWith(\"http\")) return url" + NL +
    "        }" + NL +
    "" + NL +
    "        // 1) Buscar imagen CDN en el HTML (SIEMPRE funciona)" + NL +
    "        val cdnSelectors = listOf(" + NL +
    "            \"img[src*=\\\"cdn.animegratis\\\"]\"," + NL +
    "            \"img[src*=\\\"cdn.jkdesa\\\"]\"," + NL +
    "            \"img[data-fallback*=\\\"cdn.animegratis\\\"]\"," + NL +
    "            \"img[data-fb2*=\\\"cdn.animegratis\\\"]\"," + NL +
    "        )"
)

n = d.count(OLD)
assert n == 1, f"OLD count = {n} (esperado 1)"
d = d.replace(OLD, NEW)
open(PATH, "wb").write(d.encode("utf-8"))
print("patched OK (findBestPosterUrl paso 0 + cdn.jkdesa)")
