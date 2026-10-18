import io

P = "MundoDonghuaProvider/src/main/kotlin/com/donghuaext/MundoDonghuaProvider.kt"
s = io.open(P, encoding="utf-8", newline="").read().replace("\r\n", "\n")

def sub(old, new, n=1):
    global s
    c = s.count(old)
    assert c == n, "esperaba %d, hay %d para: %r" % (n, c, old[:90])
    s = s.replace(old, new)

# ---- coleccion de embeds y de enlaces "en espera" ----
sub("""        val seenEmbeds = LinkedHashSet<String>()""",
"""        val seenEmbeds = LinkedHashSet<String>()
        // v24.15: los embeds se RECOLECTAN primero y se procesan al final ordenados
        // por prioridad de servidor (auditoria en vivo, ver mdServerPriority), y
        // los enlaces directos de los servidores caidos (Asura/Tamamo, que
        // dependen de www.mdnemonicplayer.xyz) se emiten en el ultimo lugar.
        val embedsFound = LinkedHashSet<String>()
        val deadLinks = ArrayList<ExtractorLink>()""")

# ---- Asura ----
sub("""                                    generateM3u8("Asura", m3u8Url, datafix).forEach(callback)
                                } catch (_: Exception) {
                                    // Fallback: intentar generar M3U8 sin verificar
                                    try {
                                        val m3u8Url = "https://www.mdnemonicplayer.xyz/nemonicplayer/redirector.php?slug=$asuraSlug"
                                        generateM3u8("Asura", m3u8Url, datafix).forEach(callback)""",
"""                                    deadLinks.addAll(generateM3u8("Asura", m3u8Url, datafix))
                                } catch (_: Exception) {
                                    // Fallback: intentar generar M3U8 sin verificar
                                    try {
                                        val m3u8Url = "https://www.mdnemonicplayer.xyz/nemonicplayer/redirector.php?slug=$asuraSlug"
                                        deadLinks.addAll(generateM3u8("Asura", m3u8Url, datafix))""")

sub("""                                    generateM3u8("Asura", fileUrl, datafix).forEach(callback)""",
    """                                    deadLinks.addAll(generateM3u8("Asura", fileUrl, datafix))""")

# ---- Tamamo ----
sub("""                                                            try { generateM3u8("Tamamo", m.value, "https://www.dailymotion.com").forEach(callback); break } catch (_: Exception) {}""",
    """                                                            try { deadLinks.addAll(generateM3u8("Tamamo", m.value, "https://www.dailymotion.com")); break } catch (_: Exception) {}""")

sub("""                                                                callback(newExtractorLink(source = "Tamamo", name = "Tamamo ${q/1000}p", url = mp4Url) {
                                                                    this.referer = "https://www.dailymotion.com"
                                                                    this.quality = q
                                                                })""",
"""                                                                deadLinks.add(newExtractorLink(source = "Tamamo", name = "Tamamo ${q/1000}p", url = mp4Url) {
                                                                    this.referer = "https://www.dailymotion.com"
                                                                    this.quality = q
                                                                })""")

# ---- fallback generico ----
sub("""                                if (foundUrl.contains(".m3u8")) {
                                    generateM3u8("Server", foundUrl, datafix).forEach(callback)
                                } else {
                                    callback(newExtractorLink(source = "Server", name = "Server", url = foundUrl) {
                                        this.referer = datafix
                                        this.quality = Qualities.Unknown.value
                                    })
                                }""",
"""                                if (foundUrl.contains(".m3u8")) {
                                    deadLinks.addAll(generateM3u8("Server", foundUrl, datafix))
                                } else {
                                    deadLinks.add(newExtractorLink(source = "Server", name = "Server", url = foundUrl) {
                                        this.referer = datafix
                                        this.quality = Qualities.Unknown.value
                                    })
                                }""")

# ---- embeds: recolectar en vez de procesar ----
for pat, url in [
    ("mdHandleEmbed(fmUrl, seenEmbeds, data, subtitleCallback, callback)", "fmUrl"),
    ('mdHandleEmbed("https://voe.sx/e/$voeId", seenEmbeds, data, subtitleCallback, callback)', '"https://voe.sx/e/$voeId"'),
    ('mdHandleEmbed("https://vidhidepro.com/$prefix/$vhId", seenEmbeds, data, subtitleCallback, callback)', '"https://vidhidepro.com/$prefix/$vhId"'),
    ('mdHandleEmbed("https://vgembed.com/e/$kagaId", seenEmbeds, data, subtitleCallback, callback)', '"https://vgembed.com/e/$kagaId"'),
    ('mdHandleEmbed("https://embedwish.com/e/$swId", seenEmbeds, data, subtitleCallback, callback)', '"https://embedwish.com/e/$swId"'),
]:
    sub(pat, "embedsFound.add(%s)" % url)

sub("""                        for (regex in iframeRegexes) {
                            for (match in regex.findAll(unpack)) {
                                mdHandleEmbed(match.value, seenEmbeds, data, subtitleCallback, callback)
                            }
                        }""",
"""                        for (regex in iframeRegexes) {
                            for (match in regex.findAll(unpack)) {
                                embedsFound.add(match.value)
                            }
                        }""")

sub("""        for (regex in MD_RAW_EMBED_REGEXES) {
            for (match in regex.findAll(rawHtml)) {
                mdHandleEmbed(match.value, seenEmbeds, data, subtitleCallback, callback)
            }
        }""",
"""        for (regex in MD_RAW_EMBED_REGEXES) {
            for (match in regex.findAll(rawHtml)) {
                embedsFound.add(match.value)
            }
        }""")

# ---- cierre: procesar embeds por prioridad y luego los enlaces caidos ----
sub("""        for (regex in MD_RAW_EMBED_REGEXES) {
            for (match in regex.findAll(rawHtml)) {
                embedsFound.add(match.value)
            }
        }

        return true""",
"""        for (regex in MD_RAW_EMBED_REGEXES) {
            for (match in regex.findAll(rawHtml)) {
                embedsFound.add(match.value)
            }
        }

        // v24.15: AUDITORIA DE SERVIDORES en vivo (20 series x 1 episodio, tools/_md_audit7.log):
        //   Fm/bysekoze  20/20 vivos (ademas solo hay 1 fuente y trae subtitulos)
        //   Sw/embedwish  5/12 vivos (los otros responden 200 con 426 B "no longer available")
        //   Vh/vidhidepro 7/17 vivos (los otros 200 con 419 B "no longer available")
        //   Vg/vgembed   0/7  (404 "no longer available")   -> no se elimina, puede revivir
        //   Voe          0/17 (404)                          -> no se elimina
        //   Asura/Tamamo 0/12 -> ambos cuelgan de www.mdnemonicplayer.xyz, que
        //     responde HTTP 522 (Cloudflare sin origen) -> se emiten los ultimos.
        // sortedByDescending es estable: dentro de cada grupo se conserva el
        // orden del propio sitio.
        embedsFound.sortedByDescending { mdServerPriority(it) }
            .forEach { mdHandleEmbed(it, seenEmbeds, data, subtitleCallback, callback) }
        deadLinks.forEach(callback)

        return true""")

# ---- funcion de prioridad ----
sub("""    private fun resolveUrl(url: String): String {""",
"""    /**
     * v24.15: prioridad de emision por servidor (auditoria en vivo, loadLinks).
     * Mas alto = se emite antes. Los servidores muertos se conservan (pueden
     * revivir y no todas las series usan los mismos), solo van al final.
     */
    private fun mdServerPriority(embedUrl: String): Int {
        val u = embedUrl.lowercase()
        return when {
            u.contains("bysekoze.com") -> 4
            u.contains("embedwish.com") || u.contains("streamwish.to") -> 3
            u.contains("vidhidepro.com") -> 2
            else -> 0
        }
    }

    private fun resolveUrl(url: String): String {""")

io.open(P, "w", encoding="utf-8", newline="").write(s.replace("\n", "\r\n"))
print("prioridad de servidores aplicada")