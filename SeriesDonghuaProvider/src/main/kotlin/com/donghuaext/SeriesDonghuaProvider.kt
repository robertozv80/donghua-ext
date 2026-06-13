package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
        "$mainUrl/##masvistos" to "Donghuas Más Vistos",
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
        val isMasVistos = request.data == "$mainUrl/##masvistos"
        val url = if (isHomePage || isMasVistos) {
            "$mainUrl/"
        } else {
            if (page > 1) "${request.data}?pag=$page" else request.data
        }
        val doc = app.get(url, timeout = 120L).document

        val home = when {
            isHomePage -> {
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
            }
            isMasVistos -> {
                parseMasVistos(doc)
            }
            else -> {
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
        }

        val hasNext = if (isHomePage || isMasVistos) {
            false
        } else {
            doc.select("ul.pagination li a").any { it.attr("href").contains("pag=${page + 1}") }
        }
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun parseMasVistos(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()

        val heading = doc.select("h4").firstOrNull { h ->
            h.text().trim().contains("Donghuas Más Vistos", ignoreCase = true)
        }

        if (heading != null) {
            var container: org.jsoup.nodes.Element? = heading.parent()
            while (container != null) {
                val rows = container.select("div.youplay-side-news")
                if (rows.isNotEmpty()) {
                    for (row in rows) {
                        val titleLink = row.selectFirst("h4 a") ?: row.selectFirst("a[title]")
                        val title = titleLink?.attr("title")?.takeIf { it.isNotEmpty() }
                            ?: titleLink?.text()?.trim()
                            ?: continue
                        val href = titleLink?.attr("href") ?: continue
                        val poster = row.selectFirst("a.angled-img img")?.attr("src")
                            ?: row.selectFirst("img")?.attr("src")
                        val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                        items.add(newAnimeSearchResponse(title, resolveUrl(href)) {
                            this.posterUrl = resolveUrl(poster ?: "")
                            addDubStatus(dubstat)
                        })
                    }
                    return items
                }
                container = container.parent()
            }
        }

        doc.select("div.youplay-side-news").forEach { row ->
            val titleLink = row.selectFirst("h4 a") ?: row.selectFirst("a[title]")
            val title = titleLink?.attr("title")?.takeIf { it.isNotEmpty() }
                ?: titleLink?.text()?.trim()
                ?: return@forEach
            val href = titleLink?.attr("href") ?: return@forEach
            if (href.contains("episodio")) return@forEach
            val poster = row.selectFirst("a.angled-img img")?.attr("src")
                ?: row.selectFirst("img")?.attr("src")
            val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
            items.add(newAnimeSearchResponse(title, resolveUrl(href)) {
                this.posterUrl = resolveUrl(poster ?: "")
                addDubStatus(dubstat)
            })
        }

        return items
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

    // ========== SEARCH — Fixed to exclude sidebar ==========
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/busquedas/${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(searchUrl, timeout = 120L).document

        // Scope to .col-md-9 (main content area only, exclude sidebar)
        val searchContainer = doc.selectFirst(".col-md-9") ?: doc
        return searchContainer
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
                posterUrl = poster; plot = description; tags = genres
            }
        }

        return newAnimeLoadResponse(title, seriesUrl, tvType) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode ?: 0 })
            showStatus = status; plot = description; tags = genres
        }
    }

    data class VideoMapJson(
        val asura: String? = null,
        val skadi: String? = null,
        val fembed: String? = null,
        val tape: String? = null,
        val amagi: String? = null,
    )

    private fun decodeSmartPacker(
        encodedStr: String, eParam: Int, charset: String, offset: Int
    ): String? {
        if (charset.isEmpty() || eParam < 2 || eParam > 62) return null
        if (eParam >= charset.length) return null

        val delimiter = charset[eParam]
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val hDigits = digits.substring(0, eParam)

        val tokens = encodedStr.split(delimiter)
        val bytes = ArrayList<Byte>()

        for (token in tokens) {
            if (token.isEmpty()) continue

            var digitStr = ""
            for (c in token) {
                val idx = charset.indexOf(c)
                if (idx < 0) return null
                digitStr += idx.toString()
            }

            val reversed = digitStr.reversed()
            var num = 0L
            for ((pos, ch) in reversed.withIndex()) {
                val idx = hDigits.indexOf(ch)
                if (idx >= 0) {
                    var power = 1L
                    repeat(pos) { power *= eParam }
                    num += idx * power
                }
            }

            val charCode = num - offset
            if (charCode < 0 || charCode > 255) return null
            bytes.add(charCode.toByte())
        }

        return try {
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            String(bytes.toByteArray(), Charsets.ISO_8859_1)
        }
    }

    private fun decodeObfuscatedScript(html: String): String? {
        val argPatterns = listOf(
            Regex("""\(\s*"([^"]{20,})"\s*,\s*(\d+)\s*,\s*"([^"]{2,30})"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)"""),
            Regex("""\(\s*'([^']{20,})'\s*,\s*(\d+)\s*,\s*'([^']{2,30})'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)"""),
        )

        for (pattern in argPatterns) {
            for (match in pattern.findAll(html)) {
                try {
                    val encodedStr = match.destructured.component1()
                    val charset = match.destructured.component3()
                    val offset = match.destructured.component4().toInt()
                    val eParam = match.destructured.component5().toInt()

                    val decoded = decodeSmartPacker(encodedStr, eParam, charset, offset)
                    if (decoded != null && decoded.contains("VIDEO_MAP_JSON")) {
                        return decoded
                    }
                } catch (_: Exception) { continue }
            }
        }

        return null
    }

    private fun extractVideoMapJson(decodedScript: String): String? {
        val patterns = listOf(
            Regex("""const\s+VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
            Regex("""VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
            Regex("""VIDEO_MAP_JSON\s*=\s*(\{.*?\})\s*;?"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(decodedScript)
            if (match != null) return match.destructured.component1()
        }
        return null
    }

    // ========== loadLinks — Improved with more fallbacks ==========
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, timeout = 120L)
        val doc = response.document
        val html = response.text

        var foundLinks = false

        val decodedScript = decodeObfuscatedScript(html)
        val videoMapJsonStr = if (decodedScript != null) {
            extractVideoMapJson(decodedScript)
        } else {
            var found: String? = null
            for (script in doc.select("script")) {
                val scriptData = script.data()
                if (scriptData.contains("VIDEO_MAP_JSON")) {
                    for (pattern in listOf(
                        Regex("""const\s+VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                        Regex("""VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                    )) {
                        val match = pattern.find(scriptData)
                        if (match != null) { found = match.destructured.component1(); break }
                    }
                    if (found != null) break
                }
            }
            found
        }

        if (videoMapJsonStr != null) {
            val videoMap: VideoMapJson? = try {
                parseJson<VideoMapJson>(videoMapJsonStr)
            } catch (_: Exception) { null }

            if (videoMap != null) {
                // Asura = Dailymotion (video ID, not URL)
                videoMap.asura?.let { rawValue ->
                    val videoId = decodeDoubleEncoded(rawValue)
                    if (videoId.isNotEmpty()) {
                        foundLinks = extractDailymotion(videoId, data, "Daily", subtitleCallback, callback) || foundLinks
                    }
                }

                // Skadi = ok.ru
                videoMap.skadi?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try { loadExtractor(url, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {
                            foundLinks = extractOkRu(url, data, "ok.ru", callback) || foundLinks
                        }
                    }
                }

                // Fembed = Rumble
                videoMap.fembed?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        if (url.contains("rumble.com")) {
                            foundLinks = extractRumble(url, data, "Rumble", callback) || foundLinks
                        } else {
                            try { loadExtractor(url, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {
                                foundLinks = extractGenericVideo(url, data, "Server", callback) || foundLinks
                            }
                        }
                    }
                }

                // Tape = Filemoon
                videoMap.tape?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try { loadExtractor(url, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {
                            foundLinks = extractGenericVideo(url, data, "Filemoon", callback) || foundLinks
                        }
                    }
                }

                // Amagi = Voe
                videoMap.amagi?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try { loadExtractor(url, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {
                            foundLinks = extractVoe(url, data, "Voe", callback) || foundLinks
                        }
                    }
                }
            }
        }

        // Fallback 1: Iframes
        if (!foundLinks) {
            doc.select("iframe").forEach { iframe ->
                val src = listOf("src", "data-src").map { iframe.attr(it).trim() }.firstOrNull { it.isNotBlank() }
                if (src != null) {
                    val fullSrc = resolveUrl(src)
                    if (fullSrc.startsWith("http")) {
                        try { loadExtractor(fullSrc, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {}
                    }
                }
            }
        }

        // Fallback 2: Regex URL search
        if (!foundLinks) {
            for (pattern in listOf(
                Regex("""(https?://[^"'\s<>]*dailymotion\.com/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*ok\.ru/videoembed/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*rumble\.com/embed/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*voe\.sx/e/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*filemoon\.[a-z]+/e/[^"'\s<>]+)"""),
            )) {
                for (match in pattern.findAll(html)) {
                    try { loadExtractor(match.value, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {}
                }
                if (foundLinks) break
            }
        }

        return foundLinks
    }

    private fun decodeDoubleEncoded(value: String): String {
        var result = value.trim()
        if (result.startsWith("\"") && result.endsWith("\"")) result = result.substring(1, result.length - 1)
        result = result.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
        if (result.startsWith("\"") && result.endsWith("\"")) result = result.substring(1, result.length - 1)
        return result.trim()
    }

    private suspend fun extractDailymotion(
        videoId: String, referer: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // If videoId looks like a full URL, extract the ID
        val id = if (videoId.startsWith("http")) {
            Regex("video/([A-Za-z0-9]+)").find(videoId)?.destructured?.component1() ?: return false
        } else {
            videoId
        }

        // Method 1: Dailymotion metadata API
        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$id"
            val json = app.get(apiUrl, referer = "https://www.dailymotion.com/embed/video/$id",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(json)) {
                try { generateM3u8(serverName, m.value, "https://www.dailymotion.com").forEach(callback); return true } catch (_: Exception) {}
            }
            val mp4s = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(json).map { it.value }.distinct().toList()
            if (mp4s.isNotEmpty()) {
                for (url in mp4s) {
                    val q = when { url.contains("1080") -> Qualities.P1080.value; url.contains("720") -> Qualities.P720.value; url.contains("480") -> Qualities.P480.value; else -> Qualities.Unknown.value }
                    callback(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = url) { this.referer = "https://www.dailymotion.com"; this.quality = q })
                }
                return true
            }
        } catch (_: Exception) {}

        // Method 2: loadExtractor
        try { loadExtractor("https://www.dailymotion.com/embed/video/$id", referer, subtitleCallback, callback); return true } catch (_: Exception) {}
        return false
    }

    private suspend fun extractOkRu(videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val html = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            val dataMatch = Regex("""data-options="([^"]+)"""").find(html)
            if (dataMatch != null) {
                val optionsJson = dataMatch.destructured.component1().replace("&quot;", "\"").replace("&amp;", "&")
                for (m in Regex("""(https?://[^"]+\.(?:mp4|m3u8)[^"]*)""").findAll(optionsJson)) {
                    callback(newExtractorLink(source = serverName, name = serverName, url = m.value) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                    return true
                }
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractRumble(embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Method 1: Direct HLS URL construction
        try {
            val videoId = Regex("rumble\\.com/embed/(v[a-zA-Z0-9]+)").find(embedUrl)?.destructured?.component1()
            if (!videoId.isNullOrEmpty()) {
                val hlsId = videoId.removePrefix("v")
                val hlsUrl = "https://rumble.com/hls-vod/$hlsId/playlist.m3u8"
                callback(newExtractorLink(source = serverName, name = "$serverName (Auto)", url = hlsUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}

        // Method 2: Parse embed page
        try {
            val html = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 30L).text
            // HLS URL from config
            val hlsPattern = Regex(""""hls"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)"""")
            hlsPattern.find(html)?.let { match ->
                val url = match.destructured.component1().replace("\\/", "/")
                callback(newExtractorLink(source = serverName, name = "$serverName (Auto)", url = url, type = ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
            // MP4 URLs from ua section
            val mp4Pattern = Regex(""""url"\s*:\s*"(https?://[^"]*rmbl\.ws[^"]*\.mp4[^"]*)"""")
            for (match in mp4Pattern.findAll(html)) {
                val url = match.destructured.component1().replace("\\/", "/")
                val q = when { url.contains("1080") -> Qualities.P1080.value; url.contains("720") -> Qualities.P720.value; url.contains("480") -> Qualities.P480.value; else -> Qualities.Unknown.value }
                callback(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = url, type = ExtractorLinkType.VIDEO) { this.referer = referer; this.quality = q })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractVoe(videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val html = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(html)) {
                try { generateM3u8(serverName, m.value, videoUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value, type = ExtractorLinkType.VIDEO) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractGenericVideo(videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val text = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(text)) {
                try { generateM3u8(serverName, m.value, videoUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(text)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value, type = ExtractorLinkType.VIDEO) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                return true
            }
        } catch (_: Exception) {}
        return false
    }
}
