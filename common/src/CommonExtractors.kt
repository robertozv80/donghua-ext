package com.donghuaext.common

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Bloque subtitles.data del metadata de Dailymotion (obj balanceado). */
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

    /** Subs: pistas es* y en* del bloque subtitles. */
    private fun emitDmSubtitles(
        json: String,
        emittedSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        dmSubtitlesBlock(json)?.let { block ->
            Regex(""""(es[a-z-]*|en[a-z-]*|fr|pt|ru|tr|pl|ar|bn|fa|id|ms|it|de|hi|th|vi)":\s*\{[^{}]*?"urls":\["([^"]+)"""")
                .findAll(block)
                .map { it.groupValues[1] to unescJson(it.groupValues[2]) }
                .distinctBy { it.second }
                .sortedBy { (code, _) ->
                    when {
                        code.startsWith("es") -> 0
                        code.startsWith("en") -> 1
                        else -> 2
                    }
                }
                .forEach { (code, url) ->
                    if (emittedSubs.add(url)) {
                        subtitleCallback.invoke(
                            SubtitleFile(dmSubLangName(code), url)
                        )
                    }
                }
        }
    }

    private fun dmSubLangName(code: String): String = when {
        code.startsWith("es") -> "Spanish"
        code.startsWith("en") -> if (code.contains("auto")) "English (auto)" else "English"
        code.startsWith("fr") -> "French"
        code.startsWith("pt") -> "Portuguese"
        code.startsWith("ru") -> "Russian"
        code.startsWith("tr") -> "Turkish"
        code.startsWith("pl") -> "Polish"
        code.startsWith("ar") -> "Arabic"
        code.startsWith("bn") -> "Bengali"
        code.startsWith("fa") -> "Persian"
        code.startsWith("id") -> "Indonesian"
        code.startsWith("ms") -> "Malay"
        code.startsWith("it") -> "Italian"
        code.startsWith("de") -> "German"
        code.startsWith("hi") -> "Hindi"
        code.startsWith("th") -> "Thai"
        code.startsWith("vi") -> "Vietnamese"
        else -> code
    }

    /**
     * Dailymotion via metadata API.
     *
     * FIX v6 (2026-10, el importante): NO expandir el master con generateM3u8.
     * Las variantes fmp4 (`/h264_fhd/3/manifest.m3u8`) NO traen audio: el audio
     * vive en grupos EXT-X-MEDIA del master y al partirlo el reproductor pierde
     * la asociación -> "video sin audio". Además duplicaba entradas (av1 + h264
     * de la misma calidad). Ahora se emite:
     *  1) progresivos mp4 del metadata (video/mp4 .mp4) — VIDEO;
     *  2) HLS muxed `mp4_h264_aac` (audio integrado en la playlist) — M3U8;
     *  3) el master cdndirector "auto" COMPLETO como UN link M3U8: ExoPlayer
     *     resuelve los grupos de audio y descarta el AV1 si el dispositivo
     *     no lo soporta.
     *  AV1: nunca se emite como link independiente.
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
        if (emittedVids.contains(vid)) return true
        val referer = embedderReferer.ifBlank { "https://animexin.dev" }
        return try {
            val metaUrl = "https://www.dailymotion.com/player/metadata/video/$vid" +
                "?embedder=" + java.net.URLEncoder.encode(referer, "UTF-8") + "&integration=inline" +
                "&app=com.dailymotion.neon"
            // DM escapa las barras del JSON: desescapar antes de aplicar regex.
            val json = app.get(metaUrl, referer = referer, headers = headersWithUA(customUA), timeout = 20L)
                .text.replace("\\/", "/").replace("\\u0026", "&")

            emitDmSubtitles(json, emittedSubs, subtitleCallback)

            var found = false
            val emittedUrls = mutableSetOf<String>()

            suspend fun emit(u: String, hls: Boolean, q: Int?) {
                val url = u.takeIf { it.startsWith("http") } ?: return
                if (!emittedUrls.add(url)) return
                val qualitySuffix = when (q) {
                    Qualities.P2160.value -> " 2160p"
                    Qualities.P1440.value -> " 1440p"
                    Qualities.P1080.value -> " 1080p"
                    Qualities.P720.value -> " 720p"
                    Qualities.P480.value -> " 480p"
                    Qualities.P360.value -> " 360p"
                    else -> ""
                }
                val displayName = if (qualitySuffix.isNotEmpty() &&
                    !Regex("\\b\\d{3,4}p\\b", RegexOption.IGNORE_CASE).containsMatchIn(linkName)
                ) linkName + qualitySuffix else linkName
                callback(
                    newExtractorLink(
                        source = linkName, name = displayName, url = url,
                        type = if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = DM_REFERER
                        this.quality = q ?: Qualities.Unknown.value
                    }
                )
                found = true
            }

            // v8 (2026-10): una sola ruta válida por video + validación.
            // Bug v7 que corregimos tras los reportes:
            //  - "Formatos duplicados": los masters + progresivos del mismo
            //    video salían como varias fuentes (duplicando la lista).
            //  - "Error ERROR_CODE_PARSING": el fallback picking his first
            //    `.dailymotion.com/cdn/manifest/...` match del JSON que era
            //    `advertising.ad_url` (dmxleo + auth=...) que responde VMAP
            //    XML de anuncios, no un m3u8, y ExoPlayer lo rechaza.
            // Nuevo flujo: un master HLS del campo "qualities" metadata
            // (incl. pref cdndirector o diretamente de un DC), validado
            // con GET antes de emitir (#EXTM3U), luego presets muxed mp4
            // _h264_aac/DM, y si nada de eso funciona, progresivos mp4.
            // El ad VMAP / dmxleo con auth= nunca se emite.

            suspend fun tryPlaylist(u: String, hlsQ: Int?): Boolean {
                if (u.isBlank() || !u.startsWith("http")) return false
                if (u.contains("dmxleo", true) || u.contains("advertising", true) || u.contains("auth=", true)) {
                    return false
                }
                // El token "sec" del master es de vida corta y esta casado con
                // el referer de Dailymotion: a veces la validacion responde 403
                // aunque el reproductor (otra pila TLS) lo abre. Por eso el
                // master SIEMPRE se emite como respaldo, nunca se descarta.
                val master = try {
                    app.get(u, referer = DM_REFERER, headers = headersWithUA(customUA), timeout = 12L).text
                } catch (_: Exception) { "" }
                if (!master.trimStart().startsWith("#EXTM3U")) {
                    emit(u, true, hlsQ)
                    return true
                }
                // Expansion propia (no generateM3u8): las variantes 4K del master
                // suelen ser AV1/HEVC y dan pantalla negra sin audio en dispositivos
                // sin decodificador, asi que solo se publican las H264 con audio
                // muxed (mp4a). El master se conserva como opcion "Auto".
                Regex("""#EXT-X-STREAM-INF:([^\n]*)\n([^\n]+)""").findAll(master).forEach { m ->
                    val attrs = m.groupValues[1]
                    val variant = m.groupValues[2].trim()
                    val codecs = Regex("""CODECS="([^"]+)"""").find(attrs)?.groupValues?.get(1).orEmpty()
                    if (codecs.contains("av01", true) || codecs.contains("hev1", true) ||
                        codecs.contains("hvc1", true)
                    ) return@forEach
                    val h = Regex("""RESOLUTION=\d+x(\d+)""").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                    val q = when {
                        h == null -> hlsQ ?: Qualities.Unknown.value
                        h >= 2160 -> Qualities.P2160.value
                        h >= 1440 -> Qualities.P1440.value
                        h >= 1080 -> Qualities.P1080.value
                        h >= 720 -> Qualities.P720.value
                        h >= 480 -> Qualities.P480.value
                        h >= 360 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    val origin = Regex("""^https?://[^/]+""").find(u)?.value ?: u
                    val abs = when {
                        variant.startsWith("http") -> variant
                        variant.startsWith("/") -> origin + variant
                        else -> "$origin/$variant"
                    }
                    emit(abs, true, q)
                }
                emit(u, true, hlsQ)   // "Auto": conserva las variantes AV1 para quien las soporte
                return true
            }

            // 1) Master HLS del campo qualities del metadata. El propio URL
            //    es lo que da el server mp4_h264_aac y audio-embedded.
            var emittedAny = false
            Regex(""""auto":\[\{"url":"(https://[^"]+)"""").findAll(json).forEach { m ->
                if (emittedAny) return@forEach
                if (m.groupValues[1].contains("dailymotion.com")) {
                    emittedAny = tryPlaylist(unescJson(m.groupValues[1]), Qualities.Unknown.value)
                }
            }
            // Sometimes the qualities obj may include type before url: parse both orders.
            if (!emittedAny) {
                Regex(""""auto":\[\{[^}]*?"type":"[^"]+","url":"(https://[^"]+)"""").findAll(json).forEach { m ->
                    if (emittedAny) return@forEach
                    if (m.groupValues[1].contains("dailymotion.com")) {
                        emittedAny = tryPlaylist(unescJson(m.groupValues[1]), Qualities.Unknown.value)
                    }
                }
            }
            // 2) Presets muxed vCRN (v7 legacy path, aplicado a los reportes
            //    para el get fp4 aac que antes funcionaba; keep as fallback).
            if (!emittedAny) {
                val masterAuto = Regex("""https://[a-z0-9.-]+\.dailymotion\.com/cdn/manifest/video/[a-z0-9]+\.m3u8[^"']*""",
                    RegexOption.IGNORE_CASE).find(json)?.value
                val dc = masterAuto?.let {
                    Regex("https://([a-z0-9.-]+)\\.dailymotion\\.com", RegexOption.IGNORE_CASE)
                        .find(it)?.groupValues?.get(1) ?: "dmxleo"
                }.takeIf { it != null && !it.startsWith("cdndirector") }
                val vid2 = masterAuto?.let { Regex("video/([a-z0-9]+)\\.m3u8", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                if (dc != null && vid2 != null) {
                    for (preset in listOf("H264-1280x720", "H264-1920x1080", "H264-854x480")) {
                        val u = "https://$dc.dailymotion.com/cdn/$preset/vCRN/mp4_h264_aac.m3u8?video=$vid2"
                        if (tryPlaylist(u, Qualities.P720.value)) { emittedAny = true; break }
                    }
                }
            }
            // 3) Otros masters "dmcdn" del metadata (muxed mp4_h264_aac) — excluye AV1.
            if (!emittedAny) {
                Regex("""https://[^"']+?dmcdn[^"']*?\.m3u8[^"']*""")
                    .findAll(json)
                    .map { it.value }
                    .filter { !it.contains("/av1", true) && !it.contains("_av1_", true) }
                    .forEach { if (!emittedAny && tryPlaylist(it, null)) emittedAny = true }
            }
            if (emittedAny) {
                emittedVids.add(vid)
                return found   // Una ruta por video: evita duplicados.
            }

            // 4) Último recurso: progresivos mp4 del qualities map. Los fMP4 de
            //    DM son streams video-only (por eso el histórico "sin audio");
            //    pero es mejor una fuente que se pueda abrir que reportar nada.
            Regex(""""(\d{3,4})":\[\{"type":"([^"]+)","url":"([^"]+)"""").findAll(json).forEach { m ->
                val h = m.groupValues[1].toIntOrNull()
                val url = unescJson(m.groupValues[3])
                val q = when {
                    h == null -> Qualities.Unknown.value
                    h >= 1080 -> Qualities.P1080.value
                    h >= 720 -> Qualities.P720.value
                    h >= 480 -> Qualities.P480.value
                    h >= 360 -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                if (url.contains("/av1", ignoreCase = true) || url.contains("_av1_", ignoreCase = true)) {
                    // skip AV1: negro / sin audio
                } else {
                    emit(url, url.contains(".m3u8"), q)
                }
            }

            // Último recurso para videos privados de Dailymotion. El metadata
            // suele traer solo qualities.auto (cdndirector); se emite únicamente
            // el master real, nunca advertising.ad_url / dmxleo.
            if (!found) {
                val candidate = Regex("""auto":\[\{[^}]*"url":"(https://[^"]+)"""").find(json)
                    ?.groupValues?.get(1)?.let { unescJson(it) }
                    ?: Regex("""https://[a-z0-9.-]+\.dailymotion\.com/cdn/manifest/video/[a-z0-9]+\.m3u8[^"']*""",
                        RegexOption.IGNORE_CASE).find(json)?.value
                if (candidate != null &&
                    !candidate.contains("dmxleo", true) &&
                    !candidate.contains("advertising", true) &&
                    !candidate.contains("auth=", true)
                ) {
                    android.util.Log.d(
                        "DMExtract",
                        "fallback master sin validar ${candidate.substringAfter("://").substringBefore("/")}"
                    )
                    emit(candidate, true, Qualities.Unknown.value)
                }
            }
            if (found) emittedVids.add(vid)
            found
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Rumble. Flujo: embedJS (u3) con doble intento + visita previa al embed
     * para el reto de Cloudflare; mp4 progresivos del JSON.
     * NOTA 2026-10: si el video fue ELIMINADO de Rumble el endpoint responde
     * {} / 410 "Video not found" — no es fallo del extractor.
     */
    suspend fun extractRumble(
        embedUrl: String,
        siteReferer: String,
        linkName: String,
        customUA: String? = null,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val vid = Regex("""rumble\.com/(?:embed/)?(v[0-9a-z]+)(?:[-/_.@?]|$)""", RegexOption.IGNORE_CASE)
                .find(embedUrl)?.groupValues?.get(1) ?: return false
            val api = "https://rumble.com/embedJS/u3/?request=video&v=$vid" +
                "&embed=" + java.net.URLEncoder.encode(embedUrl, "UTF-8")
            suspend fun emit(json: String): Int {
                val fixed = json.replace("\\/", "/")
                val uaMap = Regex(""""(\d{3,4})":\["(https://[a-z0-9.-]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"""")
                var n = 0
                uaMap.findAll(fixed).forEach { m ->
                    val h = m.groupValues[1].toIntOrNull()
                    callback(
                        newExtractorLink(source = linkName, name = linkName, url = m.groupValues[2], type = ExtractorLinkType.VIDEO) {
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
    suspend fun extractGdrivePlayer(
        embedUrl: String,
        siteReferer: String,
        linkName: String,
        customUA: String? = null,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(embedUrl, referer = siteReferer, headers = headersWithUA(customUA), timeout = 20L).text
            // El jugador JW del embed viaja XOR-eado: k="<hex>", b=atob("..."),
            // o = b[i] ^ k[i % k.len]; (0,eval)(o). En el JS decodificado queda
            // HLS="hlsplaylist.php?s=...&idhls=....m3u8" (relativo al embed host).
            val script = Regex("<script>([\\s\\S]*?)</script>").findAll(html)
                .map { it.groupValues[1] }
                .firstOrNull { it.contains("atob(") && it.contains("k=\"") } ?: return false
            val key = Regex("k=\"([0-9a-f]+)\"").find(script)?.groupValues?.get(1) ?: return false
            val b64 = Regex("atob(\"([^\"]+)\")").find(script)?.groupValues?.get(1) ?: return false
            val raw = try { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) } catch (_: Exception) { return false }
            val keyBytes = key.toByteArray(Charsets.ISO_8859_1)
            val out = ByteArray(raw.size)
            for (i in raw.indices) out[i] = (raw[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            val decoded = String(out, Charsets.ISO_8859_1).replace("\\/", "/")
            val hlsRel = Regex("HLS=\"([^\"]+\\.m3u8)\"").find(decoded)?.groupValues?.get(1) ?: return false
            val origin = Regex("^https?://[^/]+").find(embedUrl)?.value ?: return false
            val playlist = if (hlsRel.startsWith("http")) hlsRel else "$origin/$hlsRel"
            val text = try { app.get(playlist, referer = embedUrl, headers = headersWithUA(customUA), timeout = 20L).text } catch (_: Exception) { "" }
            if (!text.trimStart().startsWith("#EXTM3U")) return false
            var emitted = false
            try {
                generateM3u8(linkName, playlist, origin).forEach { callback(it); emitted = true }
            } catch (_: Exception) {}
            if (!emitted) {
                callback(
                    newExtractorLink(source = linkName, name = linkName, url = playlist, type = ExtractorLinkType.M3U8) {
                        this.referer = origin
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            true
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
                            newExtractorLink(source = linkName, name = linkName, url = full, type = ExtractorLinkType.VIDEO) {
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
                newExtractorLink(source = linkName, name = linkName, url = finalUrl, type = ExtractorLinkType.VIDEO) {
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
