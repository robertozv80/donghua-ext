package com.donghuaext.common

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Extractores compartidos por TODOS los providers del repo (2026-10).
 *
 * Motivo: Dailymotion/Rumble/Ok.ru/Dood se implementaban 8+ veces, una por
 * provider, con pequeñas divergencias que provocaban los errores reportados:
 * pantalla negra/sin audio en Dailymotion (variantes av1 elegidas antes que
 * h264 y fuentes duplicadas), Rumble que funcionaba en unos providers y no en
 * otros, y servers ignorados por cortes tempranos (AnimeXin).
 *
 * Nota de build: cada módulo Gradle compila este fichero en su propio .cs3.
 * El sitio añade la carpeta common/ como srcDir en build.gradle.kts; NO usar
 * para lógica dependiente del sitio (packed players, RSC, feeds Blogger).
 */
object CommonExtractors {

    private const val DM_REFERER = "https://www.dailymotion.com/"

    /** UAs por defecto; los providers pueden pasar el suyo. */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private fun headersWithUA(custom: String?) = mapOf("User-Agent" to (custom ?: UA))

    /** Desescapa un string JSON (\\uXXXX, \\/, \\n, ...). */
    fun unescJson(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'u' -> {
                        val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6 }
                        else { sb.append(s[i + 1]); i += 2 }
                    }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(s[i + 1]); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** Bloque subtitles.data del metadata de Dailymotion (rmtegis: obj balanceado). */
    fun dmSubtitlesBlock(json: String): String? {
        val start = json.indexOf("\"subtitles\"")
        if (start < 0) return null
        val dataIdx = json.indexOf("\"data\"", start)
        if (dataIdx < 0) return null
        val open = json.indexOf('{', dataIdx)
        if (open < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in open until json.length) {
            val ch = json[i]
            if (esc) { esc = false; continue }
            when {
                ch == '\\' && inStr -> esc = true
                ch == '"' -> inStr = !inStr
                !inStr && ch == '{' -> depth++
                !inStr && ch == '}' -> {
                    depth--
                    if (depth == 0) return json.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /** Extrae el id de video desde un iframe/embed de Dailymotion. */
    fun dmVideoId(src: String): String? {
        return Regex(
            """(?:geo\.dailymotion\.com/player(?:/[a-z0-9]+)?\.html\?video=|dailymotion\.com/embed/video/)([A-Za-z0-9]+)""",
            RegexOption.IGNORE_CASE
        ).find(src)?.groupValues?.get(1)
    }

    /**
     * Dailymotion via metadata API.
     *
     * FIXES 2026-10 (aplican a todos los providers):
     *  1. Pantalla negra al inicio: el master del CDN listaba variantes AV1
     *     (`/av1_fhd/`) y H264 (`/h264_fhd/`); ExoPlayer tomaba la AV1 y muchos
     *     dispositivos no tienen decodificador -> negro/atajo con error
     *     setDataSource 0x80000000. Ahora el común le pide a generateM3u8 la
     *     lista y EMITE SOLO variantes no-av1 (si no queda ninguna, emite todas).
     *  2. Fuentes duplicadas ("...Dailymotion 1080p" x2): las dos variantes con
     *     el mismo NAME colisionaban + cada mirror del sitio repetía el mismo
     *     video. El común deduplica por URL de variante y por videoId.
     *  3. Preferencia h264 aunque exista mp4 progresivo, para que el orden
     *     interno no deje al usuario, por defecto, en una fuente sin audio.
     */
    suspend fun extractDailymotion(
        iframeSrc: String,
        embedderReferer: String,
        linkName: String,
        customUA: String? = null,
        emittedSubs: MutableSet<String>,
        emittedVids: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = dmVideoId(iframeSrc) ?: iframeSrc.trim().takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
            ?: return false
        // Dedupe por video: el mismo video puede listarse en varios mirrors.
        if (!emittedVids.add(vid)) return true
        val referer = embedderReferer.ifBlank { "https://animexin.dev" }
        return try {
            val metaUrl = "https://www.dailymotion.com/player/metadata/video/$vid" +
                "?embedder=" + java.net.URLEncoder.encode(referer, "UTF-8") + "&integration=inline"
            // DM escapa las barras del JSON: desescapar antes de aplicar regex.
            val json = app.get(metaUrl, referer = referer, headers = headersWithUA(customUA), timeout = 20L)
                .text.replace("\\/", "/").replace("\\u0026", "&")

            dmSubtitlesBlock(json)?.let { block ->
                Regex(""""(es[a-z-]*|en[a-z-]*)":\s*\{[^{}]*?"urls":\["([^"]+)"""")
                    .findAll(block)
                    .map { it.groupValues[1] to unescJson(it.groupValues[2]) }
                    .distinctBy { it.second }
                    .sortedBy { (code, _) -> if (code.startsWith("es")) 0 else 1 }
                    .forEach { (code, url) ->
                        if (emittedSubs.add(url)) {
                            subtitleCallback.invoke(
                                SubtitleFile(if (code.startsWith("es")) "Spanish" else "English", url)
                            )
                        }
                    }
            }

            var found = false
            val emittedUrls = mutableSetOf<String>()

            fun emitVariant(link: ExtractorLink) {
                if (emittedUrls.add(link.url)) callback(link)
            }

            // MP4 progresivos del metadata (cuando DM aún los entrega): audio
            // garantizado (muxed h264+aac), por eso se emiten PRIMERO.
            // (bucle for: Sequence.map no es inline y el builder de
            //  newExtractorLink exige contexto de corrutina)
            for (m in Regex(""""(\d{3,4})":\s*\[\s*\{\s*"type":\s*"video/mp4"\s*,\s*"url":\s*"([^"]+)"""").findAll(json)) {
                val h = m.groupValues[1].toIntOrNull()
                val link = newExtractorLink(source = linkName, name = linkName, url = unescJson(m.groupValues[2])) {
                    this.referer = DM_REFERER
                    this.quality = when {
                        h == null -> Qualities.Unknown.value
                        h >= 1080 -> Qualities.P1080.value
                        h >= 720 -> Qualities.P720.value
                        h >= 480 -> Qualities.P480.value
                        else -> Qualities.P360.value
                    }
                }
                emitVariant(link)
            }
            found = emittedUrls.isNotEmpty()

            // HLS: master "auto" (cdndirector). generateM3u8 expande a variantes;
            // el común descarta las AV1 y no repite URLs/etiquetas.
            Regex(""""url":"(https://[^"]+?\.m3u8[^"]*)"""").findAll(json)
                .map { unescJson(it.groupValues[1]) }
                .firstOrNull { u -> u.contains("cdndirector") || u.contains("dmcdn") || u.endsWith(".m3u8") }
                ?.let { hls ->
                    try {
                        val links = generateM3u8(linkName, hls, DM_REFERER) ?: emptyList()
                        val isAv1 = { u: String -> u.contains("/av1", ignoreCase = true) || u.contains("_av1_", ignoreCase = true) }
                        val chosen = links.filter { !isAv1(it.url) }.ifEmpty { links }
                        // Orden: h264 (codec clásico, decodifica en cualquier device) primero.
                        chosen.sortedBy { if (isAv1(it.url)) 1 else 0 }.forEach { emitVariant(it) }
                        if (chosen.isNotEmpty()) found = true
                    } catch (_: Exception) {}
                }

            // URLs m3u8 alternativas (old-style combinado mp4_h264_aac_*): llevan
            // audio muxed; emitir si no colisiona.
            Regex(""""url":"(https://[^"]+?mp4_h264_aac[^"]*\.m3u8[^"]*)"""").findAll(json)
                .map { unescJson(it.groupValues[1]) }
                .forEach { combined ->
                    try {
                        val links = generateM3u8(linkName, combined, DM_REFERER) ?: emptyList()
                        links.forEach { emitVariant(it) }
                        if (links.isNotEmpty()) found = true
                    } catch (_: Exception) {}
                }

            found
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Rumble. Flujo: embedJS (u3) con doble intento y visita previa al embed
     * para sobrevivir al reto de Cloudflare; extrae mp4 progresivos por
     * calidad de la respuesta JSON (`"1080":["https://...rumble.cloud/...mp4",...]`).
     */
    suspend fun extractRumble(
        embedUrl: String,
        siteReferer: String,
        linkName: String,
        customUA: String? = null,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val vid = Regex("""rumble\.com/(?:embed/)?(v[a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(embedUrl)?.groupValues?.get(1) ?: return false
            val api = "https://rumble.com/embedJS/u3/?request=video&v=$vid" +
                "&embed=" + java.net.URLEncoder.encode(embedUrl, "UTF-8")
            suspend fun emit(json: String): Int {
                val fixed = json.replace("\\/", "/")
                val uaMap = Regex(""""(\d{3,4})":\["(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"""")
                var n = 0
                uaMap.findAll(fixed).forEach { m ->
                    val h = m.groupValues[1].toIntOrNull()
                    callback(
                        newExtractorLink(source = linkName, name = linkName, url = m.groupValues[2]) {
                            this.referer = "https://rumble.com/"
                            this.quality = when {
                                h == null -> Qualities.Unknown.value
                                h >= 1080 -> Qualities.P1080.value
                                h >= 720 -> Qualities.P720.value
                                h >= 480 -> Qualities.P480.value
                                else -> Qualities.P360.value
                            }
                        }
                    )
                    n++
                }
                return n
            }
            var json = try { app.get(api, referer = "https://rumble.com/", headers = headersWithUA(customUA), timeout = 20L).text } catch (_: Exception) { "" }
            var count = if (json.isNotBlank()) emit(json) else 0
            if (count == 0) {
                // WAF Cloudflare: visitar el embed primero y reintentar; la
                // cookie del reto la guarda el cookieJar del cliente OkHttp.
                try { app.get(embedUrl, referer = siteReferer, headers = headersWithUA(customUA), timeout = 20L) } catch (_: Exception) {}
                json = try { app.get(api, referer = embedUrl, headers = headersWithUA(customUA), timeout = 20L).text } catch (_: Exception) { "" }
                if (json.isNotBlank()) count = emit(json)
            }
            count > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ok.ru via data-options del embed. Verifica con #EXTM3U y añade progresivos.
     */
    suspend fun extractOkRu(
        embedUrl0: String,
        siteReferer: String,
        linkName: String,
        customUA: String? = null,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val url = if (embedUrl0.startsWith("//")) "https:$embedUrl0" else embedUrl0
            val html = app.get(url, referer = siteReferer, headers = headersWithUA(customUA), timeout = 20L).text
            val decodeUnicode = { s: String ->
                Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { m ->
                    m.groupValues[1].toInt(16).toChar().toString()
                }
            }
            val options = Regex("""data-options="([^"]+)""").find(html)
                ?.groupValues?.get(1)
                ?.let { it.replace("&quot;", "\"").replace("\\/", "/").let(decodeUnicode) }
                ?: return false
            var found = false
            val hlsUrl = Regex(""""(?:[a-zA-Z]*ManifestUrl|ondemandHls)":"([^"]+)"""")
                .findAll(options).map { it.groupValues[1] }.firstOrNull { candidate ->
                    try {
                        app.get(candidate, referer = url, headers = headersWithUA(customUA), timeout = 20L)
                            .text.trimStart().startsWith("#EXTM3U")
                    } catch (_: Exception) { false }
                }
            if (hlsUrl != null) {
                try {
                    generateM3u8(linkName, hlsUrl, url).forEach { callback(it) }
                    found = true
                } catch (_: Exception) {}
            }
            Regex(""""name":"(mobile|lowest|low|sd|hd|full|super)","url":"([^"]+)"""")
                .findAll(options).forEach { m ->
                    val progressive = decodeUnicode(m.groupValues[2])
                    if (progressive.startsWith("http")) {
                        val full = if (progressive.startsWith("//")) "https:$progressive" else progressive
                        callback(
                            newExtractorLink(source = linkName, name = linkName, url = full) {
                                this.referer = url
                                this.quality = when (m.groupValues[1]) {
                                    "full", "super" -> Qualities.P1080.value
                                    "hd" -> Qualities.P720.value
                                    "sd" -> Qualities.P480.value
                                    else -> Qualities.P360.value
                                }
                            }
                        )
                        found = true
                    }
                }
            found
        } catch (_: Exception) {
            false
        }
    }

    /**
     * DoodStream (dooood.com -> playmogo.com / dominios embebibles).
     * Flujo anonimo verificado con OkHttp (la app pasa el reto de Cloudflare):
     *   1) GET /e/<id>  -> pagina con `$.get('/pass_md5/<hash>/<token>',...)` y
     *      `makePlay()` = 10 chars aleatorios + "?token=<token>&expiry=Date.now()".
     *   2) GET /pass_md5/<hash>/<token> (Referer: embed) -> prefijo de URL.
     *   3) URL final = prefijo + random10 + "?token=...&expiry=..." (mp4 progresivo).
     * La pagina lista pistas VTT multi-idioma (incluye Spanish) en srt.doodcdn.io.
     */
    suspend fun extractDood(
        embedUrl: String,
        siteReferer: String,
        linkName: String,
        customUA: String? = null,
        emittedSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(embedUrl, referer = siteReferer, headers = headersWithUA(customUA), timeout = 25L).text
            val m = Regex("""/pass_md5/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)""").find(html) ?: return false
            val path = m.value
            val token = m.groupValues[2]
            val origin = Regex("^https?://[^/]+").find(embedUrl)?.value ?: return false

            val base = app.get(
                "$origin$path",
                referer = embedUrl,
                headers = headersWithUA(customUA) + mapOf("X-Requested-With" to "XMLHttpRequest"),
                timeout = 25L
            ).text.trim()
            if (base.isEmpty() || !base.startsWith("http")) return false

            // Pistas de subtitulos VTT (multi-idioma) expuestas en la pagina
            Regex("""\{src:'([^']+)',\s*label:'([^']*)',kind:'captions',srclang:'[^']*'\}""")
                .findAll(html)
                .forEach { sm ->
                    val raw = sm.groupValues[1]
                    if (raw.startsWith("data:")) return@forEach
                    val url = if (raw.startsWith("//")) "https:$raw" else raw
                    val label = sm.groupValues[2].trim().ifEmpty { "Subtitle" }
                    if (emittedSubs.add(url)) subtitleCallback.invoke(SubtitleFile(label, url))
                }

            val rnd = (1..10).map { DOOD_RAND_CHARS.random() }.joinToString("")
            val finalUrl = "$base$rnd?token=$token&expiry=${System.currentTimeMillis()}"

            callback(
                newExtractorLink(source = linkName, name = linkName, url = finalUrl) {
                    this.referer = "$origin/"
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private const val DOOD_RAND_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
}
