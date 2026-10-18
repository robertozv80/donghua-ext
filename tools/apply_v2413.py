# -*- coding: utf-8 -*-
"""v24.13 - Ediciones verbatim:
TD) 1) skip Terabox en processTdEmbed  2) reorden vivos en loadLinks  3) tdServerPriority
SD) 4) extractSdRumble: API embedJS primero + warm-up __cf_bm (HTML queda como fallback)
"""
import sys

TD = 'TioDonghuaProvider/src/main/kotlin/com/donghuatiodonghua/TioDonghuaProvider.kt'
SD = 'SeriesDonghuaProvider/src/main/kotlin/com/donghuaext/SeriesDonghuaProvider.kt'

def apply(path, old, new, count=1):
    with open(path, 'rb') as f:
        b = f.read().decode('utf-8')
    n = b.count(old)
    if n != count:
        print(f'ERROR {path}: se esperaban {count}, encontradas {n} para:\n{old[:120]}...')
        sys.exit(1)
    b = b.replace(old, new)
    with open(path, 'wb') as f:
        f.write(b.encode('utf-8'))
    print(f'OK {path}: reemplazo aplicado ({count})')

# ---------- TD 1: skip Terabox ----------
apply(TD, r'''        when {
            // v24.3: streaming P2P/WebRTC (API JSON con token, peers y MSE);
            // ExoPlayer no puede reproducirlo: no gastar peticiones.
            u.contains("playerp2p.online") -> {}
''', r'''        when {
            // v24.3: streaming P2P/WebRTC (API JSON con token, peers y MSE);
            // ExoPlayer no puede reproducirlo: no gastar peticiones.
            u.contains("playerp2p.online") -> {}
            // v24.13: Terabox (servidor "TB"): reproductor propio sin fuente
            // estatica en el HTML (476KB de SPA) y SIN API publica anonima:
            // share/list devuelve los metadatos (errno 0) pero sin dlink,
            // shorturlinfo responde errno 0 con shareid/uk/sign pero dlink
            // VACIO, /share/download esta envuelto en check-login en el JS del
            // propio sitio (errno 2 anonimo) y /share/streaming responde errno
            // 130 (y aunque funcionara, el player anonimo recibe chunks
            // aleatorios de ~4 min por request, inservible en ExoPlayer).
            // No extraible: saltar (ahorra 2 requests muertas por episodio).
            u.contains("terabox") -> {}
''')

# ---------- TD 2: reordenar results por prioridad ----------
apply(TD, r'''                }.map { it.await() }
            }
            results.forEach { (rawEmbed, label) ->
                val embedUrl = tdNormalizeEmbed(rawEmbed)
                if (embedUrl != null) {
                    processTdEmbed(embedUrl, data, label, subtitleCallback, cb)
                }
            }
''', r'''                }.map { it.await() }
            }
            // v24.13: auditoria de servidores (12 episodios x 8 opciones AJAX):
            // vivos Dailymotion 7/7 y Rumble 5/6 (calidades directas), Drive 3/3
            // y OK 2/4; muertos de forma estable VOE/Filemon/StreamTape/Terabox/
            // SW/DS (0 de N). Emitir los vivos primero (sortedByDescending es
            // estable: dentro de cada grupo se conserva el orden del sitio) para
            // que la reproduccion por defecto caiga en un servidor que responde.
            val ordered = results.sortedByDescending { (raw, _) ->
                tdServerPriority(tdNormalizeEmbed(raw) ?: "")
            }
            ordered.forEach { (rawEmbed, label) ->
                val embedUrl = tdNormalizeEmbed(rawEmbed)
                if (embedUrl != null) {
                    processTdEmbed(embedUrl, data, label, subtitleCallback, cb)
                }
            }
''')

# ---------- TD 3: tdServerPriority tras serverLabelFromUrl ----------
apply(TD, r'''    /** Nombre de servidor legible a partir del host. */
    private fun serverLabelFromUrl(url: String): String {
        return try {
            val host = java.net.URI(url).host ?: return "Server"
            host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            "Server"
        }
    }
''', r'''    /** Nombre de servidor legible a partir del host. */
    private fun serverLabelFromUrl(url: String): String {
        return try {
            val host = java.net.URI(url).host ?: return "Server"
            host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            "Server"
        }
    }

    /**
     * v24.13: prioridad del servidor por host para ordenar la emision de
     * enlaces (ver auditoria en loadLinks). Mas alto = emite antes.
     */
    private fun tdServerPriority(embedUrl: String): Int {
        val u = embedUrl.lowercase()
        return when {
            u.contains("rumble.com") -> 3
            u.contains("dailymotion.com") || u.contains("dai.ly") -> 3
            u.contains("drive.google.com") -> 2
            u.contains("ok.ru") || u.contains("odnoklassniki") -> 1
            else -> 0
        }
    }
''')

# ---------- SD 4: extractSdRumble con API embedJS ----------
apply(SD, r'''    /**
     * Rumble (v24.2): el embed JS trae un mapa de calidades
     * "360":{"url":"...mp4","meta":{...,"h":360}}; emitir cada una.
     */
    private suspend fun extractSdRumble(embedUrl: String, referer: String, name: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(embedUrl, referer = referer,
''', r'''    /**
     * Rumble (v24.2): el embed JS trae un mapa de calidades
     * "360":{"url":"...mp4","meta":{...,"h":360}}; emitir cada una.
     *
     * v24.13 (port del fix de TioDonghua v24.12): el embed estandar
     * (rumble.com/embed/VID) puede NO traer el video en el HTML; la API
     * publica embedJS exige el parametro embed= o responde 403. Estrategia:
     * 1) API embedJS con MP4 directos de hugh.cdn.rumble.cloud; 2) HTML del
     * embed como fallback. Las subidas nuevas sirven rendiciones ".tar" (TAR
     * de segmentos TS, NO reproducibles como MP4 directo): no se emiten; el
     * HTML de esas trae hls-vod valido y el fallback HLS las cubre.
     */
    private suspend fun extractSdRumble(embedUrl: String, referer: String, name: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            // 1) API embedJS: embed= es OBLIGATORIO (verificado: sin el
            // parametro la API responde 403 Forbidden).
            val vid = Regex("rumble\\.com/(?:embed/)?(v[a-zA-Z0-9]+)").find(embedUrl)?.groupValues?.get(1)
            if (vid != null) {
                val api = "https://rumble.com/embedJS/u3/?request=video&v=$vid" +
                    "&embed=" + java.net.URLEncoder.encode(embedUrl, "UTF-8")
                val uaMap = Regex("\"(\\d{3,4})\":\\[\"(https://[a-z0-9.]*rumble\\.cloud/video/[A-Za-z0-9/._-]+\\.mp4)\"")
                suspend fun emit(json: String): Int {
                    val fixed = json.replace("\\/", "/")
                    var n = 0
                    uaMap.findAll(fixed).forEach { m ->
                        val h = m.groupValues[1].toIntOrNull()
                        callback(newExtractorLink(source = name, name = name, url = m.groupValues[2]) {
                            this.referer = "https://rumble.com/"
                            this.quality = when {
                                h == null -> Qualities.Unknown.value
                                h >= 1080 -> Qualities.P1080.value
                                h >= 720 -> Qualities.P720.value
                                h >= 480 -> Qualities.P480.value
                                else -> Qualities.P360.value
                            }
                        })
                        n++
                    }
                    return n
                }
                var json = try {
                    app.get(api, referer = "https://rumble.com/",
                        headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L).text
                } catch (_: Exception) { "" }
                var count = if (json.isNotBlank()) emit(json) else 0
                if (count == 0) {
                    // WAF de Cloudflare: la API responde 403 sin cookie __cf_bm.
                    // Visitar el embed primero (la sesion conserva la cookie) y reintentar.
                    try {
                        app.get(embedUrl, referer = referer,
                            headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L)
                    } catch (_: Exception) {}
                    json = try {
                        app.get(api, referer = embedUrl,
                            headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L).text
                    } catch (_: Exception) { "" }
                    if (json.isNotBlank()) count = emit(json)
                }
                if (count > 0) return true
            }
            // 2) Fallback: HTML del embed (hls-vod de las subidas nuevas .tar,
            // mapa de calidades y primer mp4 de videos antiguos).
            val html = app.get(embedUrl, referer = referer,
''')

print('TODAS LAS EDICIONES APLICADAS')
