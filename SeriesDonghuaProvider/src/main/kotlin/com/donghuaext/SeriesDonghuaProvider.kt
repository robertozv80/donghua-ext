package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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

    /**
     * Convierte URL de episodio a URL de serie
     * /{slug}-episodio-{N}/ → /{slug}/
     */
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

    // ========== MODELO PARA VIDEO_MAP_JSON ==========
    data class VideoMapJson(
        val asura: String? = null,
        val skadi: String? = null,
        val fembed: String? = null,
        val tape: String? = null,
        val amagi: String? = null,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 120L).document
        val pageText = doc.html()

        // ====== Extraer VIDEO_MAP_JSON de los scripts ======
        // Formato: const VIDEO_MAP_JSON={"asura":"\"id\"","skadi":"\"url\"",...};
        var videoMapJsonStr: String? = null

        for (script in doc.select("script")) {
            val scriptData = script.data()
            if (scriptData.contains("VIDEO_MAP_JSON")) {
                // Probar varios patrones de regex
                val patterns = listOf(
                    Regex("""const\s+VIDEO_MAP_JSON\s*=\s*(\{[^;]+?\})\s*;"""),
                    Regex("""VIDEO_MAP_JSON\s*=\s*(\{[^;]+?\})\s*;"""),
                    Regex("""VIDEO_MAP_JSON\s*=\s*(\{.*?\})\s*;?"""),
                )
                for (pattern in patterns) {
                    val match = pattern.find(scriptData)
                    if (match != null) {
                        videoMapJsonStr = match.destructured.component1()
                        break
                    }
                }
                if (videoMapJsonStr != null) break
            }
        }

        var foundLinks = false

        // ====== Si encontramos VIDEO_MAP_JSON, parsear y extraer enlaces ======
        if (videoMapJsonStr != null) {
            val videoMap: VideoMapJson? = try {
                parseJson<VideoMapJson>(videoMapJsonStr)
            } catch (_: Exception) {
                null
            }

            if (videoMap != null) {
                // ===== Asura → Dailymotion =====
                // El valor de "asura" es un ID de video de Dailymotion (doble-encoded)
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
                                url.contains("rumble.com") -> {
                                    foundLinks = extractRumble(url, data, "Rumble", callback) || foundLinks
                                }
                                else -> {
                                    foundLinks = extractGenericVideo(url, data, "Server", callback) || foundLinks
                                }
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
                val src = iframe.attr("src")?.ifBlank { iframe.attr("data-src") }
                if (!src.isNullOrEmpty()) {
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

        // ====== Fallback 2: Buscar URLs de video en los scripts ======
        if (!foundLinks) {
            val iframePatterns = listOf(
                Regex("""(https?://[^"'\s<>]*dailymotion\.com/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*rumble\.com/embed/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*ok\.ru/videoembed/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*voe\.sx/e/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*odysee\.com/[^"'\s<>]+)"""),
            )
            for (pattern in iframePatterns) {
                for (match in pattern.findAll(pageText)) {
                    try {
                        loadExtractor(match.value, data, subtitleCallback, callback)
                        foundLinks = true
                    } catch (_: Exception) {}
                }
                if (foundLinks) break
            }
        }

        // ====== Fallback 3: Buscar player.php proxy ======
        if (!foundLinks) {
            val proxyRegex = Regex("""player\.php\?url=(https?://[^"'\s<>]+)""")
            for (match in proxyRegex.findAll(pageText)) {
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

    // ========== FUNCIONES AUXILIARES ==========

    /**
     * Decodifica un valor doble-encoded de VIDEO_MAP_JSON.
     * Ejemplo: "\"k4AnYPak3WSvSJGqQxE\"" → "k4AnYPak3WSvSJGqQxE"
     * Ejemplo: "\"https:\\/\\/ok.ru\\/videoembed\\/123\"" → "https://ok.ru/videoembed/123"
     */
    private fun decodeDoubleEncoded(value: String): String {
        var result = value.trim()
        // Quitar comillas exteriores (primer nivel de encoding JSON)
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        // Decodificar escapes JSON: \/ → /, \" → ", \\ → \
        result = result.replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        // Quitar comillas interiores (segundo nivel de encoding JSON)
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        return result.trim()
    }

    /**
     * Extrae video de Dailymotion usando:
     * 1) loadExtractor (si CloudStream lo soporta)
     * 2) API de metadata del player (/player/metadata/video/{id})
     * 3) Scrape de la página embed
     */
    private suspend fun extractDailymotion(
        videoId: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Método 1: loadExtractor con la URL embed
        try {
            val dmUrl = "https://www.dailymotion.com/embed/video/$videoId"
            loadExtractor(dmUrl, referer, subtitleCallback, callback)
            return true
        } catch (_: Exception) {}

        // Método 2: API de metadata del player de Dailymotion
        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val response = app.get(
                apiUrl,
                referer = "https://www.dailymotion.com/embed/video/$videoId",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json, text/plain, */*",
                    "Origin" to "https://www.dailymotion.com",
                ),
                timeout = 15L
            )
            val jsonText = response.text

            // Buscar m3u8 (HLS) en la respuesta
            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""")
            for (match in m3u8Regex.findAll(jsonText)) {
                try {
                    generateM3u8(serverName, match.value, "https://www.dailymotion.com").forEach(callback)
                    return true
                } catch (_: Exception) {}
            }

            // Buscar mp4 en la respuesta (múltiples calidades)
            val mp4Regex = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""")
            val mp4Urls = mp4Regex.findAll(jsonText).map { it.value }.distinct().toList()
            if (mp4Urls.isNotEmpty()) {
                var foundAny = false
                for (url in mp4Urls) {
                    val quality = when {
                        url.contains("1080") || url.contains("x1080") -> Qualities.P1080.value
                        url.contains("720") || url.contains("x720") -> Qualities.P720.value
                        url.contains("480") || url.contains("x480") -> Qualities.P480.value
                        url.contains("380") || url.contains("x360") -> Qualities.P360.value
                        url.contains("240") || url.contains("x240") -> Qualities.P240.value
                        url.contains("144") -> Qualities.P144.value
                        else -> Qualities.Unknown.value
                    }
                    callback(
                        newExtractorLink(source = serverName, name = "$serverName ${quality / 1000}p", url = url) {
                            this.referer = "https://www.dailymotion.com"
                            this.quality = quality
                        }
                    )
                    foundAny = true
                }
                if (foundAny) return true
            }
        } catch (_: Exception) {}

        // Método 3: Scrapear la página embed buscando URLs en el fuente
        try {
            val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
            val response = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L)
            val html = response.text

            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""")
            for (match in m3u8Regex.findAll(html)) {
                try {
                    generateM3u8(serverName, match.value, embedUrl).forEach(callback)
                    return true
                } catch (_: Exception) {}
            }

            val mp4Regex = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""")
            for (match in mp4Regex.findAll(html)) {
                callback(
                    newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * Extrae video de ok.ru:
     * Busca URLs de video en data-options, og:video, y el HTML del embed
     */
    private suspend fun extractOkRu(
        videoUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val response = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L)
            val html = response.text

            // Método 1: Buscar data-options con JSON que contiene video URLs
            val dataOptionsRegex = Regex("""data-options="([^"]+)"""")
            val dataMatch = dataOptionsRegex.find(html)
            if (dataMatch != null) {
                val optionsJson = dataMatch.destructured.component1()
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                val videoUrlRegex = Regex("""(https?://[^"]+\.(?:mp4|m3u8)[^"]*)""")
                for (match in videoUrlRegex.findAll(optionsJson)) {
                    val url = match.value
                    if (url.contains(".m3u8")) {
                        try {
                            generateM3u8(serverName, url, videoUrl).forEach(callback)
                            return true
                        } catch (_: Exception) {}
                    } else {
                        callback(
                            newExtractorLink(source = serverName, name = serverName, url = url) {
                                this.referer = videoUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return true
                    }
                }
            }

            // Método 2: Buscar og:video meta tag
            val ogRegex = Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""")
            val ogMatch = ogRegex.find(html)
            if (ogMatch != null) {
                callback(
                    newExtractorLink(source = serverName, name = serverName, url = ogMatch.destructured.component1()) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // Método 3: Buscar URLs de video directamente en el HTML
            val directUrlRegex = Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""")
            var found = false
            for (match in directUrlRegex.findAll(html)) {
                val url = match.value
                val quality = when {
                    url.contains("1080") || url.contains("hd1080") -> Qualities.P1080.value
                    url.contains("720") || url.contains("hd720") -> Qualities.P720.value
                    url.contains("480") || url.contains("sd480") -> Qualities.P480.value
                    url.contains("360") || url.contains("sd360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback(
                    newExtractorLink(source = serverName, name = "$serverName ${quality / 1000}p", url = url) {
                        this.referer = videoUrl
                        this.quality = quality
                    }
                )
                found = true
            }
            if (found) return true
        } catch (_: Exception) {}
        return false
    }

    /**
     * Extrae video de Rumble:
     * Busca la configuración JSON del player y URLs m3u8/mp4
     */
    private suspend fun extractRumble(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val response = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 30L)
            val html = response.text

            // Método 1: Buscar JSON config del player (variable __q o similar)
            val jsonConfigRegex = Regex(""""ua":\s*\{[^}]*"mp4":\s*\[([^\]]+)\]""")
            val jsonMatch = jsonConfigRegex.find(html)
            if (jsonMatch != null) {
                val mp4Array = jsonMatch.destructured.component1()
                val urlRegex = Regex(""""(https?://[^"]+\.mp4[^"]*)"""")
                var found = false
                urlRegex.findAll(mp4Array).forEach { match ->
                    val url = match.destructured.component1()
                    val quality = when {
                        url.contains("1080") -> Qualities.P1080.value
                        url.contains("720") -> Qualities.P720.value
                        url.contains("480") -> Qualities.P480.value
                        url.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    callback(
                        newExtractorLink(source = serverName, name = "$serverName ${quality / 1000}p", url = url) {
                            this.referer = referer
                            this.quality = quality
                        }
                    )
                    found = true
                }
                if (found) return true
            }

            // Método 2: Buscar m3u8 URL
            val m3u8Patterns = listOf(
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?)"""),
            )
            for (pattern in m3u8Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    try {
                        generateM3u8(serverName, match.destructured.component1(), referer).forEach(callback)
                        return true
                    } catch (_: Exception) {}
                }
            }

            // Método 3: Buscar mp4 URL (incluyendo rmbl.ws)
            val mp4Patterns = listOf(
                Regex("""["'](https?://[^"']+?rmbl\.ws[^"']*?\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),
            )
            for (pattern in mp4Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    callback(
                        newExtractorLink(source = serverName, name = serverName, url = match.destructured.component1()) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Extrae video de Odysee:
     * Construye la URL de stream a partir de la URL de embed
     */
    private suspend fun extractOdysee(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Método 1: Construir URL de stream reemplazando /embed/ por /stream/
            val streamUrl = embedUrl.replace("/$/embed/", "/$/stream/")
                .replace("/embed/", "/stream/")
            callback(
                newExtractorLink(source = serverName, name = serverName, url = streamUrl) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        } catch (_: Exception) {}

        // Método 2: Scrapear la página de embed buscando URL de video
        try {
            val response = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L)
            val html = response.text

            val videoUrlRegex = Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""")
            for (match in videoUrlRegex.findAll(html)) {
                callback(
                    newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Extrae video de Voe.sx:
     * Busca URLs de video en el HTML ofuscado
     */
    private suspend fun extractVoe(
        videoUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val response = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L)
            val html = response.text

            // Método 1: Buscar m3u8 URL
            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""")
            for (match in m3u8Regex.findAll(html)) {
                try {
                    generateM3u8(serverName, match.value, videoUrl).forEach(callback)
                    return true
                } catch (_: Exception) {}
            }

            // Método 2: Buscar mp4 URL
            val mp4Regex = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""")
            for (match in mp4Regex.findAll(html)) {
                callback(
                    newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // Método 3: Buscar URL codificada en base64 o en variables JavaScript
            // Voe a veces usa patrones como: var source = "..." o hls": "..."
            val sourceRegex = Regex("""(?:source|src|url|hls)["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            for (match in sourceRegex.findAll(html)) {
                val url = match.destructured.component1()
                if (url.startsWith("http") && (url.contains(".m3u8") || url.contains(".mp4"))) {
                    if (url.contains(".m3u8")) {
                        try {
                            generateM3u8(serverName, url, videoUrl).forEach(callback)
                            return true
                        } catch (_: Exception) {}
                    } else {
                        callback(
                            newExtractorLink(source = serverName, name = serverName, url = url) {
                                this.referer = videoUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Extracción genérica: busca URLs de video en cualquier página
     */
    private suspend fun extractGenericVideo(
        videoUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val response = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L)
            val text = response.text

            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""")
            for (match in m3u8Regex.findAll(text)) {
                try {
                    generateM3u8(serverName, match.value, videoUrl).forEach(callback)
                    return true
                } catch (_: Exception) {}
            }

            val mp4Regex = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""")
            for (match in mp4Regex.findAll(text)) {
                callback(
                    newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (_: Exception) {}
        return false
    }
}
