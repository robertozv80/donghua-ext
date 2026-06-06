package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URI
import java.nio.charset.Charset
import kotlin.collections.ArrayList

class SeriesDonghuaProvider : MainAPI() {

    override var mainUrl = "https://seriesdonghua.com"
    override var name = "SeriesDonghua"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.OVA,
        TvType.AnimeMovie,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Nuevos Episodios",
        "$mainUrl/todos-los-donghuas" to "Todos los Donghuas",
        "$mainUrl/donghuas-en-emision" to "En Emisión",
        "$mainUrl/donghuas-finalizados" to "Finalizados",
    )

    private fun resolveUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            url.isNotBlank() -> "$mainUrl/$url"
            else -> ""
        }
    }

    // ========== getMainPage ==========
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == "$mainUrl/"
        val url = if (page > 1) {
            if (isHomePage) request.data else "${request.data}?pag=$page"
        } else {
            request.data
        }
        val doc = app.get(url, timeout = 120L).document

        val home = if (isHomePage) {
            doc.select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a").mapNotNull { link ->
                val titleEl = link.selectFirst("h5") ?: return@mapNotNull null
                val title = titleEl.text().trim()
                val poster = link.selectFirst("div.img img")?.attr("src")
                val href = link.attr("href") ?: return@mapNotNull null
                if (!href.contains("episodio")) return@mapNotNull null
                val seriesUrl = convertEpisodeToSeriesUrl(href)
                val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\s*Episodio\\s*\\d+"), "").trim()
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(cleanTitle, seriesUrl) {
                    this.posterUrl = resolveUrl(poster ?: "")
                    addDubStatus(dubstat, epNum)
                }
            }
        } else {
            doc.select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a").mapNotNull { link ->
                val title = link.selectFirst("h5")?.text() ?: return@mapNotNull null
                val poster = link.selectFirst("div.img img")?.attr("src")
                val href = link.attr("href") ?: return@mapNotNull null
                if (href.contains("episodio")) return@mapNotNull null
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, resolveUrl(href)) {
                    this.posterUrl = resolveUrl(poster ?: "")
                    addDubStatus(dubstat)
                }
            }
        }

        val hasNext = doc.select("ul.pagination li a").any { it.attr("href").contains("pag=${page + 1}") }
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun convertEpisodeToSeriesUrl(href: String): String {
        val fullUrl = resolveUrl(href)
        val path = fullUrl.substringAfter(mainUrl).trim('/')
        val regex = Regex("^(.+)-episodio-\\d+$")
        val match = regex.find(path)
        return if (match != null) {
            "$mainUrl/${match.destructured.component1()}/"
        } else {
            fullUrl
        }
    }

    // ========== search ==========
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/busquedas/${java.net.URLEncoder.encode(query, "UTF-8")}"
        return app.get(searchUrl, timeout = 120L).document
            .select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a").mapNotNull { link ->
                val title = link.selectFirst("h5")?.text() ?: return@mapNotNull null
                val href = link.attr("href") ?: return@mapNotNull null
                if (href.contains("episodio")) return@mapNotNull null
                val image = link.selectFirst("div.img img")?.attr("src")
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                    this.posterUrl = resolveUrl(image ?: "")
                    addDubStatus(dubstat)
                }
            }
    }

    // ========== load ==========
    override suspend fun load(url: String): LoadResponse {
        val seriesUrl = if (url.contains("-episodio-")) {
            convertEpisodeToSeriesUrl(url)
        } else {
            url
        }

        val doc = app.get(seriesUrl, timeout = 120L).document
        val poster = doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.banner-side-serie, div.side-banner div.image")?.attr("style")?.let {
                Regex("background-image:\\s*url\\(['\"]?([^'\")\\s]+)").find(it)?.destructured?.component1()
            }
            ?: ""
        val title = doc.selectFirst("div.ls-title-serie")?.text()
            ?: doc.selectFirst("head meta[property=og:title]")?.attr("content")?.replace(Regex("\\s*[|\\-–].*$"), "")
            ?: ""
        val description = doc.selectFirst("div.text-justify.fc-dark p, div.text-justify.fc-dark")?.text() ?: ""
        val genres = doc.select("a.generos span.label.label-primary.f-bold").map { it.text() }
        val status = when (doc.selectFirst("span.badge.bg-default")?.text()?.trim()) {
            "En emisión", "En Emisión" -> ShowStatus.Ongoing
            "Finalizada" -> ShowStatus.Completed
            else -> null
        }

        val typeInfo = doc.select("div.row div.col-md-6 p.fc-dark, p.fc-dark").map { it.text() }.joinToString(" ")
        val tvType = when {
            typeInfo.contains(Regex("Tipo.*Pel.cula", RegexOption.IGNORE_CASE)) -> TvType.AnimeMovie
            typeInfo.contains(Regex("Tipo.*OVA|Tipo.*Especial", RegexOption.IGNORE_CASE)) -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = ArrayList<Episode>()
        doc.select("div.donghua-list-scroll ul.donghua-list a, ul.donghua-list a").map { epLink ->
            val href = epLink.attr("href")
            val epTitle = epLink.selectFirst("blockquote.message")?.text() ?: ""
            val epNum = Regex("-episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                ?: Regex("(\\d+)\\s*$").find(epTitle)?.value?.toIntOrNull()
                ?: Regex("-\\s*(\\d+)\\s*$").find(epTitle)?.destructured?.component1()?.toIntOrNull()
            episodes.add(
                newEpisode(resolveUrl(href)) {
                    this.episode = epNum
                    this.name = epTitle
                }
            )
        }

        if (episodes.isEmpty() && tvType == TvType.AnimeMovie) {
            return newMovieLoadResponse(title, seriesUrl, TvType.AnimeMovie, seriesUrl) {
                posterUrl = poster
                plot = description
                tags = genres
            }
        }

        return newAnimeLoadResponse(title, seriesUrl, tvType) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
            showStatus = status
            plot = description
            tags = genres
        }
    }

    // ========== Modelo VIDEO_MAP_JSON ==========
    data class VideoMapJson(
        val asura: String? = null,
        val skadi: String? = null,
        val fembed: String? = null,
        val tape: String? = null,
        val amagi: String? = null,
    )

    // ================================================================
    //  DECODIFICADOR DEL JAVASCRIPT OFUSCADO (Smart Packer)
    // ================================================================
    // El sitio usa eval(function(h,u,n,t,e,r){...}(encoded,base,charset,offset,delimIdx,...))
    // para ofuscar VIDEO_MAP_JSON. Este decodificador reimplementa el algoritmo en Kotlin.
    //
    // Algoritmo:
    //   1. charset n = "FSUfvxBAu" (ejemplo)
    //   2. delimiter = n[e] (ej. n[6] = 'B')
    //   3. Split h por delimiter → tokens
    //   4. Para cada token: reemplazar cada char por su índice en n → string de dígitos
    //   5. Convertir de base-e (ej. base-6) a decimal
    //   6. Restar offset t (ej. 18)
    //   7. Convertir a byte → concatenar
    //   8. Decodificar como UTF-8
    // ================================================================

    /**
     * Decodifica el JavaScript ofuscado del tipo Smart Packer.
     * Retorna el texto decodificado, o null si falla.
     */
    private fun decodeSmartPacker(
        encodedStr: String,
        base: Int,
        charset: String,
        offset: Int,
        delimIdx: Int
    ): String? {
        if (charset.isEmpty() || delimIdx < 0 || delimIdx >= charset.length) return null
        if (base < 2 || base > 64) return null

        val delimiter = charset[delimIdx]

        // Digitos para la conversión de base (estándar: 0-9, a-z, A-Z, +, /)
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"

        // Split por delimiter
        val tokens = encodedStr.split(delimiter)

        val bytes = ArrayList<Byte>()
        for (token in tokens) {
            if (token.isEmpty()) continue

            // Reemplazar cada char por su índice en el charset → string de dígitos
            val digitStr = StringBuilder()
            for (c in token) {
                val idx = charset.indexOf(c)
                if (idx < 0) return null  // Carácter no reconocido
                digitStr.append(digits[idx])
            }

            // Convertir de base-e a decimal usando los digitos estándar
            var num = 0L
            for (c in digitStr.toString()) {
                val digitVal = digits.indexOf(c)
                if (digitVal < 0 || digitVal >= base) return null
                num = num * base + digitVal
            }

            // Restar offset y convertir a byte
            val charCode = num - offset
            if (charCode < 0 || charCode > 255) return null
            bytes.add(charCode.toByte())
        }

        // Decodificar como UTF-8 (equivalente a decodeURIComponent(escape(r)))
        return try {
            String(bytes.toByteArray(), Charset.forName("UTF-8"))
        } catch (_: Exception) {
            String(bytes.toByteArray(), Charsets.ISO_8859_1)
        }
    }

    /**
     * Busca y decodifica el eval() ofuscado en el HTML de la página de episodio.
     * Retorna el texto decodificado que contiene VIDEO_MAP_JSON, o null si falla.
     */
    private fun decodeObfuscatedScript(html: String): String? {
        // Patrón 1: eval(function(h,u,n,t,e,r){...}("encoded", 44, "FSUfvxBAu", 18, 6, 11))
        // El patrón captura: encoded_str, base, charset, offset, delimIdx
        val evalPatterns = listOf(
            // Formato Smart Packer con parámetros posicionales
            Regex("""eval\(function\s*\(\s*h\s*,\s*u\s*,\s*n\s*,\s*t\s*,\s*e\s*,\s*r\s*\)\s*\{[^}]+\}\s*\(\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)"""),
            // Variante con comillas simples
            Regex("""eval\(function\s*\(\s*h\s*,\s*u\s*,\s*n\s*,\s*t\s*,\s*e\s*,\s*r\s*\)\s*\{[^}]+\}\s*\(\s*'([^']+)'\s*,\s*(\d+)\s*,\s*'([^']+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)"""),
        )

        for (pattern in evalPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                val encodedStr = match.destructured.component1()
                val base = match.destructured.component2().toIntOrNull() ?: continue
                val charset = match.destructured.component3()
                val offset = match.destructured.component4().toIntOrNull() ?: continue
                val delimIdx = match.destructured.component5().toIntOrNull() ?: continue
                // component6 is the unused 'r' parameter

                val decoded = decodeSmartPacker(encodedStr, base, charset, offset, delimIdx)
                if (decoded != null && decoded.contains("VIDEO_MAP_JSON")) {
                    return decoded
                }
            }
        }

        // Fallback: buscar patrones más flexibles del eval call
        // Intentar con regex que captura toda la llamada eval
        val flexiblePattern = Regex(
            """eval\(function\s*\(\s*\w+\s*,\s*\w+\s*,\s*\w+\s*,\s*\w+\s*,\s*\w+\s*,\s*\w+\s*\)\s*\{.*?\}\s*\(\s*["']([^"']+)["']\s*,\s*(\d+)\s*,\s*["']([^"']+)["']\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val flexMatch = flexiblePattern.find(html)
        if (flexMatch != null) {
            val encodedStr = flexMatch.destructured.component1()
            val base = flexMatch.destructured.component2().toIntOrNull() ?: return null
            val charset = flexMatch.destructured.component3()
            val offset = flexMatch.destructured.component4().toIntOrNull() ?: return null
            val delimIdx = flexMatch.destructured.component5().toIntOrNull() ?: return null

            val decoded = decodeSmartPacker(encodedStr, base, charset, offset, delimIdx)
            if (decoded != null && decoded.contains("VIDEO_MAP_JSON")) {
                return decoded
            }
        }

        return null
    }

    /**
     * Extrae VIDEO_MAP_JSON del texto decodificado.
     * Retorna el string JSON de VIDEO_MAP_JSON, o null si no se encuentra.
     */
    private fun extractVideoMapJson(decodedScript: String): String? {
        val patterns = listOf(
            Regex("""const\s+VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
            Regex("""VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
            Regex("""VIDEO_MAP_JSON\s*=\s*(\{.*?\})\s*;?"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(decodedScript)
            if (match != null) {
                return match.destructured.component1()
            }
        }
        return null
    }

    // ========== loadLinks ==========
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, timeout = 120L)
        val doc = response.document
        val html = response.text

        var foundLinks = false

        // ====== PASO 1: Decodificar el JavaScript ofuscado ======
        // VIDEO_MAP_JSON NO está en el HTML crudo — se inyecta vía eval() ofuscado
        val decodedScript = decodeObfuscatedScript(html)
        val videoMapJsonStr = if (decodedScript != null) {
            extractVideoMapJson(decodedScript)
        } else {
            // Fallback: buscar directamente en los scripts (por si la ofuscación cambia)
            var found: String? = null
            for (script in doc.select("script")) {
                val scriptData = script.data()
                if (scriptData.contains("VIDEO_MAP_JSON")) {
                    val patterns = listOf(
                        Regex("""const\s+VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                        Regex("""VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                        Regex("""VIDEO_MAP_JSON\s*=\s*(\{.*?\})\s*;?"""),
                    )
                    for (pattern in patterns) {
                        val match = pattern.find(scriptData)
                        if (match != null) {
                            found = match.destructured.component1()
                            break
                        }
                    }
                    if (found != null) break
                }
            }
            found
        }

        // ====== PASO 2: Parsear VIDEO_MAP_JSON y extraer enlaces ======
        if (videoMapJsonStr != null) {
            val videoMap: VideoMapJson? = try {
                parseJson<VideoMapJson>(videoMapJsonStr)
            } catch (_: Exception) {
                null
            }

            if (videoMap != null) {
                // ===== Asura → Dailymotion =====
                videoMap.asura?.let { rawValue ->
                    val videoId = decodeDoubleEncoded(rawValue)
                    if (videoId.isNotEmpty()) {
                        foundLinks = extractDailymotion(videoId, data, "Dailymotion", subtitleCallback, callback) || foundLinks
                    }
                }

                // ===== Skadi → ok.ru =====
                videoMap.skadi?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try {
                            loadExtractor(url, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (_: Exception) {
                            foundLinks = extractOkRu(url, data, "ok.ru", callback) || foundLinks
                        }
                    }
                }

                // ===== Fembed → Rumble / StreamSB / genérico =====
                videoMap.fembed?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try {
                            loadExtractor(url, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (_: Exception) {
                            when {
                                url.contains("rumble.com") ->
                                    foundLinks = extractRumble(url, data, "Rumble", callback) || foundLinks
                                url.contains("likessb.com") || url.contains("streamsb") ->
                                    foundLinks = extractGenericVideo(url, data, "StreamSB", callback) || foundLinks
                                else ->
                                    foundLinks = extractGenericVideo(url, data, "Server", callback) || foundLinks
                            }
                        }
                    }
                }

                // ===== Tape → Odysee =====
                videoMap.tape?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try {
                            loadExtractor(url, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (_: Exception) {
                            foundLinks = extractOdysee(url, data, "Odysee", callback) || foundLinks
                        }
                    }
                }

                // ===== Amagi → Voe.sx =====
                videoMap.amagi?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try {
                            loadExtractor(url, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (_: Exception) {
                            foundLinks = extractVoe(url, data, "Voe", callback) || foundLinks
                        }
                    }
                }
            }
        }

        // ====== Fallback 1: Buscar iframes directamente ======
        if (!foundLinks) {
            doc.select("iframe").amap { iframe ->
                val src = listOf("src", "data-src")
                    .map { iframe.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() }
                if (src != null) {
                    val fullSrc = resolveUrl(src)
                    if (fullSrc.startsWith("http")) {
                        try {
                            loadExtractor(fullSrc, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // ====== Fallback 2: Buscar URLs de video en el HTML completo ======
        if (!foundLinks) {
            val iframePatterns = listOf(
                Regex("""(https?://[^"'\s<>]*dailymotion\.com/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*rumble\.com/embed/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*ok\.ru/videoembed/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*voe\.sx/e/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*odysee\.com/[^"'\s<>]+)"""),
            )
            for (pattern in iframePatterns) {
                for (match in pattern.findAll(html)) {
                    try {
                        loadExtractor(match.value, data, subtitleCallback, callback)
                        foundLinks = true
                    } catch (_: Exception) {}
                }
                if (foundLinks) break
            }
        }

        // ====== Fallback 3: player.php proxy ======
        if (!foundLinks) {
            for (match in Regex("""player\.php\?url=(https?://[^"'\s<>]+)""").findAll(html)) {
                val proxyUrl = match.destructured.component1()
                try {
                    loadExtractor(proxyUrl, data, subtitleCallback, callback)
                    foundLinks = true
                } catch (_: Exception) {
                    foundLinks = extractGenericVideo(proxyUrl, data, "Proxy", callback) || foundLinks
                }
                if (foundLinks) break
            }
        }

        return foundLinks
    }

    // ========== Decodificador de valores doble-encoded ==========
    private fun decodeDoubleEncoded(value: String): String {
        var result = value.trim()
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        result = result.replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        return result.trim()
    }

    // ========== Extractores de video ==========

    private suspend fun extractDailymotion(
        videoId: String, referer: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Método 1: loadExtractor
        try {
            loadExtractor("https://www.dailymotion.com/embed/video/$videoId", referer, subtitleCallback, callback)
            return true
        } catch (_: Exception) {}

        // Método 2: API de metadata del player
        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val jsonText = app.get(apiUrl,
                referer = "https://www.dailymotion.com/embed/video/$videoId",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                timeout = 15L
            ).text

            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(jsonText)) {
                try { generateM3u8(serverName, match.value, "https://www.dailymotion.com").forEach(callback); return true } catch (_: Exception) {}
            }
            val mp4Urls = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(jsonText).map { it.value }.distinct().toList()
            if (mp4Urls.isNotEmpty()) {
                for (url in mp4Urls) {
                    val quality = when {
                        url.contains("1080") -> Qualities.P1080.value
                        url.contains("720") -> Qualities.P720.value
                        url.contains("480") -> Qualities.P480.value
                        url.contains("380") || url.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    callback(newExtractorLink(source = serverName, name = "$serverName ${quality/1000}p", url = url) {
                        this.referer = "https://www.dailymotion.com"; this.quality = quality
                    })
                }
                return true
            }
        } catch (_: Exception) {}

        // Método 3: Scrape embed page
        try {
            val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
            val embedHtml = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(embedHtml)) {
                try { generateM3u8(serverName, match.value, embedUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (match in Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(embedHtml)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                    this.referer = embedUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    private suspend fun extractOkRu(
        videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            val dataMatch = Regex("""data-options="([^"]+)"""").find(html)
            if (dataMatch != null) {
                val optionsJson = dataMatch.destructured.component1().replace("&quot;", "\"").replace("&amp;", "&")
                for (match in Regex("""(https?://[^"]+\.(?:mp4|m3u8)[^"]*)""").findAll(optionsJson)) {
                    callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = videoUrl; this.quality = Qualities.Unknown.value
                    })
                    return true
                }
            }
            Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""").find(html)?.let { m ->
                callback(newExtractorLink(source = serverName, name = serverName, url = m.destructured.component1()) {
                    this.referer = videoUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
            for (match in Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                    this.referer = videoUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractRumble(
        embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 30L).text
            val jsonMatch = Regex(""""ua":\s*\{[^}]*"mp4":\s*\[([^\]]+)\]""").find(html)
            if (jsonMatch != null) {
                val mp4Array = jsonMatch.destructured.component1()
                var found = false
                Regex(""""(https?://[^"]+\.mp4[^"]*)"""").findAll(mp4Array).forEach { match ->
                    val url = match.destructured.component1()
                    val quality = when {
                        url.contains("1080") -> Qualities.P1080.value; url.contains("720") -> Qualities.P720.value
                        url.contains("480") -> Qualities.P480.value; url.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    callback(newExtractorLink(source = serverName, name = "$serverName ${quality/1000}p", url = url) {
                        this.referer = referer; this.quality = quality
                    })
                    found = true
                }
                if (found) return true
            }
            for (pattern in listOf(Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""), Regex("""(https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?)"""))) {
                pattern.find(html)?.let { match ->
                    try { generateM3u8(serverName, match.destructured.component1(), referer).forEach(callback); return true } catch (_: Exception) {}
                }
            }
            for (pattern in listOf(Regex("""["'](https?://[^"']+?rmbl\.ws[^"']*?\.mp4[^"']*)["']"""), Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""))) {
                pattern.find(html)?.let { match ->
                    callback(newExtractorLink(source = serverName, name = serverName, url = match.destructured.component1()) {
                        this.referer = referer; this.quality = Qualities.Unknown.value
                    })
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractOdysee(
        embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val streamUrl = embedUrl.replace("/$/embed/", "/$/stream/").replace("/embed/", "/stream/")
            callback(newExtractorLink(source = serverName, name = serverName, url = streamUrl) {
                this.referer = referer; this.quality = Qualities.Unknown.value
            })
            return true
        } catch (_: Exception) {}
        try {
            val html = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (match in Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                    this.referer = embedUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractVoe(
        videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(html)) {
                try { generateM3u8(serverName, match.value, videoUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (match in Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                    this.referer = videoUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
            for (match in Regex("""(?:source|src|url|hls)["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
                val url = match.destructured.component1()
                if (url.startsWith("http") && (url.contains(".m3u8") || url.contains(".mp4"))) {
                    if (url.contains(".m3u8")) {
                        try { generateM3u8(serverName, url, videoUrl).forEach(callback); return true } catch (_: Exception) {}
                    } else {
                        callback(newExtractorLink(source = serverName, name = serverName, url = url) {
                            this.referer = videoUrl; this.quality = Qualities.Unknown.value
                        })
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractGenericVideo(
        videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val text = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(text)) {
                try { generateM3u8(serverName, match.value, videoUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (match in Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(text)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                    this.referer = videoUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}
        return false
    }
}
