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
        val doc = app.get(url, timeout = 120).document

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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/busquedas/${java.net.URLEncoder.encode(query, "UTF-8")}"
        return app.get(searchUrl, timeout = 120).document
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

        val doc = app.get(seriesUrl, timeout = 120).document
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
            "En emisión" -> ShowStatus.Ongoing
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
        val doc = app.get(data, timeout = 120).document

        // VIDEO_MAP_JSON ya NO está ofuscado - aparece en texto claro en los scripts
        var videoMapJsonStr: String? = null

        for (script in doc.select("script")) {
            val scriptData = script.data()
            if (scriptData.contains("VIDEO_MAP_JSON")) {
                val patterns = listOf(
                    Regex("""const\s+VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                    Regex("""VIDEO_MAP_JSON\s*=\s*(\{[^}]+\})"""),
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

        // Si encontramos VIDEO_MAP_JSON, parsear y extraer enlaces
        if (videoMapJsonStr != null) {
            val videoMap: VideoMapJson? = try {
                parseJson<VideoMapJson>(videoMapJsonStr)
            } catch (_: Exception) {
                null
            }

            if (videoMap != null) {
                // ===== Asura → Dailymotion =====
                videoMap.asura?.let { rawValue ->
                    val asuraVal = decodeDoubleEncoded(rawValue)
                    if (asuraVal.isNotEmpty()) {
                        val dmUrl = "https://www.dailymotion.com/embed/video/$asuraVal"
                        try {
                            loadExtractor(dmUrl, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (_: Exception) {
                            try {
                                extractDailymotion(asuraVal, data, "Daily", callback)
                                foundLinks = true
                            } catch (_: Exception) {}
                        }
                    }
                }

                // ===== Skadi → ok.ru =====
                videoMap.skadi?.let { rawValue ->
                    val skadiUrl = decodeDoubleEncoded(rawValue)
                    if (skadiUrl.startsWith("http")) {
                        try {
                            loadExtractor(skadiUrl, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (_: Exception) {
                            try {
                                extractOkRu(skadiUrl, data, "Ok.ru", callback)
                                foundLinks = true
                            } catch (_: Exception) {}
                        }
                    }
                }

                // ===== Fembed → Variable (Rumble, StreamSB, likessb, etc.) =====
                videoMap.fembed?.let { rawValue ->
                    val fembedUrl = decodeDoubleEncoded(rawValue)
                    if (fembedUrl.startsWith("http")) {
                        when {
                            fembedUrl.contains("rumble.com") -> {
                                try {
                                    extractRumble(fembedUrl, data, "Rumble", callback)
                                    foundLinks = true
                                } catch (_: Exception) {}
                            }
                            fembedUrl.contains("likessb.com") || fembedUrl.contains("streamsb") -> {
                                try {
                                    loadExtractor(fembedUrl, data, subtitleCallback, callback)
                                    foundLinks = true
                                } catch (_: Exception) {
                                    try {
                                        extractGenericVideo(fembedUrl, data, "StreamSB", callback)
                                        foundLinks = true
                                    } catch (_: Exception) {}
                                }
                            }
                            else -> {
                                try {
                                    loadExtractor(fembedUrl, data, subtitleCallback, callback)
                                    foundLinks = true
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                // ===== Tape → Odysee =====
                videoMap.tape?.let { rawValue ->
                    val odyseeUrl = decodeDoubleEncoded(rawValue)
                    if (odyseeUrl.startsWith("http")) {
                        try {
                            extractOdysee(odyseeUrl, data, "Odysee", callback)
                            foundLinks = true
                        } catch (_: Exception) {}
                    }
                }

                // ===== Amagi → Voe.sx =====
                videoMap.amagi?.let { rawValue ->
                    val voeUrl = decodeDoubleEncoded(rawValue)
                    if (voeUrl.startsWith("http")) {
                        try {
                            loadExtractor(voeUrl, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (_: Exception) {
                            try {
                                extractVoe(voeUrl, data, "Voe", callback)
                                foundLinks = true
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        // Fallback: buscar iframes directamente en la página
        if (!foundLinks) {
            doc.select("iframe").amap { iframe ->
                val src = iframe.attr("src") ?: iframe.attr("data-src")
                if (src.isNotEmpty()) {
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

        // Último fallback: buscar URLs de video en los scripts
        if (!foundLinks) {
            for (script in doc.select("script")) {
                val scriptData = script.data()
                val iframePatterns = listOf(
                    Regex("""(https?://[^"'\s<>]*dailymotion\.com/embed/[^"'\s<>]+)"""),
                    Regex("""(https?://[^"'\s<>]*rumble\.com/embed/[^"'\s<>]+)"""),
                    Regex("""(https?://[^"'\s<>]*ok\.ru/videoembed/[^"'\s<>]+)"""),
                    Regex("""(https?://[^"'\s<>]*voe\.sx/e/[^"'\s<>]+)"""),
                )
                for (pattern in iframePatterns) {
                    for (match in pattern.findAll(scriptData)) {
                        try {
                            loadExtractor(match.value, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (_: Exception) {}
                    }
                }
                if (foundLinks) break
            }
        }

        return foundLinks
    }

    // ========== FUNCIONES AUXILIARES (a nivel de clase) ==========

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

    private suspend fun extractDailymotion(
        videoId: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
            val response = app.get(embedUrl, referer = referer, timeout = 15)
            val html = response.text

            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
            for (match in m3u8Regex.findAll(html)) {
                try {
                    generateM3u8(serverName, match.value, embedUrl).forEach(callback)
                    return
                } catch (_: Exception) {}
            }

            val mp4Regex = Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""")
            for (match in mp4Regex.findAll(html)) {
                callback(
                    newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractOkRu(
        videoUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(videoUrl, referer = referer, timeout = 15)
            val html = response.text

            val videoUrlRegex = Regex(""""(https?://[^"]+\.(?:mp4|m3u8)[^"]*)"""")
            for (match in videoUrlRegex.findAll(html)) {
                val url = match.destructured.component1()
                if (url.contains(".m3u8")) {
                    try {
                        generateM3u8(serverName, url, videoUrl).forEach(callback)
                        return
                    } catch (_: Exception) {}
                } else if (url.contains(".mp4")) {
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
                    return
                }
            }

            val ogRegex = Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""")
            val ogMatch = ogRegex.find(html)
            if (ogMatch != null) {
                callback(
                    newExtractorLink(source = serverName, name = serverName, url = ogMatch.destructured.component1()) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractRumble(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(embedUrl, referer = referer, timeout = 30)
            val html = response.text

            val jsonConfigRegex = Regex(""""ua":\s*\{[^}]*"mp4":\s*\[([^\]]+)\]""")
            val jsonMatch = jsonConfigRegex.find(html)
            if (jsonMatch != null) {
                val mp4Array = jsonMatch.destructured.component1()
                val urlRegex = Regex(""""(https?://[^"]+\.mp4[^"]*)"""")
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
                }
                return
            }

            val m3u8Patterns = listOf(
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?)"""),
            )
            for (pattern in m3u8Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val url = match.destructured.component1()
                    generateM3u8(serverName, url, referer).forEach(callback)
                    return
                }
            }

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
                    return
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractOdysee(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val streamUrl = embedUrl.replace("/$/embed/", "/$/stream/")
            callback(
                newExtractorLink(source = serverName, name = serverName, url = streamUrl) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (_: Exception) {}
    }

    private suspend fun extractVoe(
        videoUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(videoUrl, referer = referer, timeout = 15)
            val html = response.text

            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
            for (match in m3u8Regex.findAll(html)) {
                try {
                    generateM3u8(serverName, match.value, videoUrl).forEach(callback)
                    return
                } catch (_: Exception) {}
            }

            val mp4Regex = Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""")
            for (match in mp4Regex.findAll(html)) {
                callback(
                    newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractGenericVideo(
        videoUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(videoUrl, referer = referer, timeout = 15)
            val html = response.text

            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
            for (match in m3u8Regex.findAll(html)) {
                try {
                    generateM3u8(serverName, match.value, videoUrl).forEach(callback)
                    return
                } catch (_: Exception) {}
            }

            val mp4Regex = Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""")
            for (match in mp4Regex.findAll(html)) {
                callback(
                    newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        } catch (_: Exception) {}
    }
}
