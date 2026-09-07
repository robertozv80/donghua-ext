package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import kotlin.collections.ArrayList

class DonghuaLifeProvider : MainAPI() {

    override var mainUrl = "https://donghualife.com"
    override var name = "DonghuaLife"
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
        "$mainUrl/" to "Últimos Episodios",
        "$mainUrl/donghuas" to "Donghuas",
        "$mainUrl/en-emision" to "En Emisión",
        "$mainUrl/finalizado" to "Finalizados",
        "$mainUrl/movies" to "Películas",
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
        val url = if (isHomePage) {
            if (page > 1) "$mainUrl/?page=${page - 1}" else request.data
        } else {
            if (page > 1) "${request.data}?page=${page - 1}" else request.data
        }
        val doc = app.get(url, timeout = 120).document

        val home = if (isHomePage) {
            doc.select(".views-row .episode, div.episode").mapNotNull { ep ->
                if (ep.selectFirst("div.patreon") != null || ep.selectFirst(".patreon span") != null) return@mapNotNull null
                val titleEl = ep.selectFirst("div.titulo") ?: return@mapNotNull null
                val subtitleEl = ep.selectFirst("div.subtitulo")
                val title = titleEl.text().trim()
                val epHref = ep.selectFirst("div.imagen a")?.attr("href") ?: return@mapNotNull null
                val poster = ep.selectFirst("div.imagen img")?.attr("src")
                val epNum = subtitleEl?.text()?.let {
                    Regex("Episodio\\s*(\\d+)", RegexOption.IGNORE_CASE).find(it)?.destructured?.component1()?.toIntOrNull()
                }
                val seriesUrl = episodeUrlToSeriesUrl(epHref)
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, seriesUrl) {
                    this.posterUrl = resolveUrl(poster ?: "")
                    addDubStatus(dubstat, epNum)
                }
            }
        } else {
            val isMovies = request.data.contains("/movies")
            val cardSelector = if (isMovies) ".views-row .movie" else ".views-row .serie"
            doc.select(cardSelector).mapNotNull {
                val title = it.selectFirst(".titulo")?.text() ?: return@mapNotNull null
                val poster = it.selectFirst(".imagen img")?.attr("src")
                val href = it.selectFirst(".imagen a")?.attr("href") ?: return@mapNotNull null
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, resolveUrl(href)) {
                    this.posterUrl = resolveUrl(poster ?: "")
                    addDubStatus(dubstat)
                }
            }
        }

        val hasNext = if (isHomePage) {
            doc.select("ul.js-pager__items a, nav.pager a[href*=\"page=\"]").isNotEmpty()
        } else {
            doc.select("nav.pager a[href*=\"page=\"]").isNotEmpty()
        }
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun episodeUrlToSeriesUrl(epHref: String): String {
        val href = resolveUrl(epHref)
        val regex = Regex("/episode/(.+)-(\\d+)-episodio-x(\\d+)")
        val match = regex.find(href)
        return if (match != null) {
            val slug = match.destructured.component1()
            "$mainUrl/series/$slug"
        } else {
            href
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?search_api_fulltext=$query", timeout = 120).document
        val searchContainer = doc.selectFirst("div.region-content") ?: doc
        return searchContainer.select(".views-row .serie").mapNotNull {
            val title = it.selectFirst(".titulo")?.text() ?: return@mapNotNull null
            val href = it.selectFirst(".imagen a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst(".imagen img")?.attr("src")
            val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
            newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                this.posterUrl = resolveUrl(image ?: "")
                addDubStatus(dubstat)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val seriesUrl = if (url.contains("/episode/")) {
            try {
                val epDoc = app.get(url, timeout = 120).document
                val seriesLink = epDoc.selectFirst("a.home-serie")?.attr("href")
                if (seriesLink != null) {
                    resolveUrl(seriesLink)
                } else {
                    episodeUrlToSeriesUrl(url)
                }
            } catch (_: Exception) {
                episodeUrlToSeriesUrl(url)
            }
        } else {
            url
        }

        val doc = app.get(seriesUrl, timeout = 120).document
        val posterRaw = doc.selectFirst(".field--name-field-poster img.image-style-poster")?.attr("src")
            ?: doc.selectFirst(".poster img")?.attr("src")
            ?: doc.selectFirst(".poster img")?.attr("data-src")
            ?: doc.selectFirst("article.node img.image-style-poster")?.attr("src")
            ?: doc.selectFirst(".imagen-node img.image-style-poster")?.attr("src")
            ?: doc.selectFirst("article.node img.image-style-node-series")?.attr("src")
            ?: doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("head meta[name=image]")?.attr("content")
            ?: ""
        val poster = resolveUrl(posterRaw)
        val title = doc.selectFirst(".titulo .field--name-title")?.text()
            ?: doc.selectFirst(".titulo h2 a span")?.text()
            ?: doc.selectFirst("head meta[property=og:title]")?.attr("content")?.replace(Regex("\\s*[|\\-–].*$"), "")
            ?: ""
        val description = doc.selectFirst(".descripcion .field--name-field-synopsis")?.text() ?: ""
        val genres = doc.select(".genero .field--name-field-genero .field__item a").map { it.text() }
        val status = when (doc.selectFirst(".estado .field--name-field-estado a")?.text()?.trim()) {
            "En Emisión" -> ShowStatus.Ongoing
            "En Pausa" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }

        val isMovie = seriesUrl.contains("/movie/") || genres.any { it.equals("Película", ignoreCase = true) }
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        if (isMovie) {
            return newMovieLoadResponse(title, seriesUrl, TvType.AnimeMovie, seriesUrl) {
                posterUrl = poster
                plot = description
                tags = genres
            }
        }

        val episodes = ArrayList<Episode>()

        val seasonLinks = doc.select(".temporada .view-temporadas .views-row .serie .imagen a, .temporada .serie .imagen a")
            .map { resolveUrl(it.attr("href")) }

        if (seasonLinks.isNotEmpty()) {
            seasonLinks.forEachIndexed { idx, seasonUrl ->
                val seasonDoc = app.get(seasonUrl, timeout = 120).document
                val seasonTitle = seasonDoc.selectFirst(".titulo .field--name-title")?.text()
                    ?: seasonDoc.selectFirst(".titulo h2 a span")?.text()
                    ?: ""
                val seasonNum = Regex("temporada\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(seasonTitle)?.destructured?.component1()?.toIntOrNull()
                    ?: (idx + 1)

                extractEpisodesFromSeasonPage(seasonDoc, seasonNum, episodes)

                val lastPageLink = seasonDoc.selectFirst("li.pager__item--last a")
                val maxPage = lastPageLink?.attr("href")?.let {
                    Regex("page=(\\d+)").find(it)?.destructured?.component1()?.toIntOrNull() ?: 0
                } ?: 0

                for (pageNum in 1..maxPage) {
                    try {
                        val pageUrl = if (seasonUrl.contains("?")) {
                            "$seasonUrl&page=$pageNum"
                        } else {
                            "$seasonUrl?page=$pageNum"
                        }
                        val pageDoc = app.get(pageUrl, timeout = 120).document
                        extractEpisodesFromSeasonPage(pageDoc, seasonNum, episodes)
                    } catch (_: Exception) {}
                }
            }
        } else {
            if (seriesUrl.contains("/season/")) {
                val seasonNum = Regex("/season/.+-(\\d+)$").find(seriesUrl)?.destructured?.component1()?.toIntOrNull() ?: 1
                extractEpisodesFromSeasonPage(doc, seasonNum, episodes)

                val lastPageLink = doc.selectFirst("li.pager__item--last a")
                val maxPage = lastPageLink?.attr("href")?.let {
                    Regex("page=(\\d+)").find(it)?.destructured?.component1()?.toIntOrNull() ?: 0
                } ?: 0

                for (pageNum in 1..maxPage) {
                    try {
                        val pageUrl = if (seriesUrl.contains("?")) {
                            "$seriesUrl&page=$pageNum"
                        } else {
                            "$seriesUrl?page=$pageNum"
                        }
                        val pageDoc = app.get(pageUrl, timeout = 120).document
                        extractEpisodesFromSeasonPage(pageDoc, seasonNum, episodes)
                    } catch (_: Exception) {}
                }
            } else {
                extractEpisodesFromSeasonPage(doc, 1, episodes)

                val lastPageLink = doc.selectFirst("li.pager__item--last a")
                val maxPage = lastPageLink?.attr("href")?.let {
                    Regex("page=(\\d+)").find(it)?.destructured?.component1()?.toIntOrNull() ?: 0
                } ?: 0

                for (pageNum in 1..maxPage) {
                    try {
                        val pageUrl = "$seriesUrl?page=$pageNum"
                        val pageDoc = app.get(pageUrl, timeout = 120).document
                        extractEpisodesFromSeasonPage(pageDoc, 1, episodes)
                    } catch (_: Exception) {}
                }
            }
        }

        return newAnimeLoadResponse(title, seriesUrl, tvType) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes.sortedWith(compareBy({ it.season }, { it.episode })))
            showStatus = status
            plot = description
            tags = genres
        }
    }

    private fun extractEpisodesFromSeasonPage(
        doc: org.jsoup.nodes.Document,
        seasonNum: Int,
        episodes: ArrayList<Episode>
    ) {
        doc.select("table.table-hover tbody tr").map { row ->
            val epNum = row.selectFirst("th[scope=row]")?.text()?.toIntOrNull()
            val epLink = row.selectFirst("td a[href^=\"/episode/\"]")?.attr("href")
                ?: row.selectFirst("td a[href]")?.attr("href")
            val isVip = row.selectFirst("td")?.text()?.contains("VIP") == true

            if (epLink != null && !isVip) {
                episodes.add(
                    newEpisode(resolveUrl(epLink)) {
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 120).document

        val isRestricted = doc.selectFirst("div.patreon-restricted-message") != null
            || doc.selectFirst("article.patreon-restricted") != null
        if (isRestricted) return false

        doc.select("a.toggle-enlace[data-video]").forEach { link ->
            val videoUrl = link.attr("data-video")
            val serverName = link.attr("title")?.trim() ?: "Server"
            if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                try {
                    when {
                        videoUrl.contains("rumble.com") -> {
                            extractRumble(videoUrl, data, serverName, callback)
                        }
                        videoUrl.contains("streamable.com") -> {
                            extractStreamable(videoUrl, data, serverName, subtitleCallback, callback)
                        }
                        videoUrl.contains("dailymotion.com") || videoUrl.contains("geo.dailymotion.com") -> {
                            val videoId = Regex("video=([A-Za-z0-9]+)").find(videoUrl)?.destructured?.component1()
                                ?: Regex("/video/([A-Za-z0-9]+)").find(videoUrl)?.destructured?.component1()
                            if (!videoId.isNullOrEmpty()) {
                                extractDailymotionApi(videoId, data, serverName, callback)
                            } else {
                                loadExtractor(videoUrl, data, subtitleCallback, callback)
                            }
                        }
                        // ====== FIX: OK.RU con extractor manual ======
                        videoUrl.contains("ok.ru") -> {
                            if (!extractOkRu(videoUrl, data, serverName, callback)) {
                                val okUrl = videoUrl.replace("https://ok.ru", "http://ok.ru")
                                try { loadExtractor(okUrl, data, subtitleCallback, callback) } catch (_: Exception) {}
                            }
                        }
                        else -> {
                            try { loadExtractor(videoUrl, data, subtitleCallback, callback) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        doc.select("iframe#iframe-episode, div.embed iframe, div#video-container iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                try {
                    val fullSrc = resolveUrl(src)
                    when {
                        fullSrc.contains("rumble.com") -> {
                            extractRumble(fullSrc, data, "Rumble", callback)
                        }
                        fullSrc.contains("streamable.com") -> {
                            extractStreamable(fullSrc, data, "Stremeable", subtitleCallback, callback)
                        }
                        fullSrc.contains("dailymotion.com") || fullSrc.contains("geo.dailymotion.com") -> {
                            val videoId = Regex("video=([A-Za-z0-9]+)").find(fullSrc)?.destructured?.component1()
                                ?: Regex("/video/([A-Za-z0-9]+)").find(fullSrc)?.destructured?.component1()
                            if (!videoId.isNullOrEmpty()) {
                                extractDailymotionApi(videoId, data, "Dailymotion", callback)
                            }
                        }
                        fullSrc.contains("ok.ru") -> {
                            if (!extractOkRu(fullSrc, data, "OK.ru", callback)) {
                                try { loadExtractor(fullSrc.replace("https://ok.ru", "http://ok.ru"), data, subtitleCallback, callback) } catch (_: Exception) {}
                            }
                        }
                        else -> {
                            try { loadExtractor(fullSrc, data, subtitleCallback, callback) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return true
    }

    // ========== EXTRACTOR MANUAL PARA OK.RU ==========
    private suspend fun extractOkRu(
    videoUrl: String,
    referer: String,
    serverName: String,
    callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            val dataMatch = Regex("""data-options="([^"]+)"""").find(html)
            if (dataMatch != null) {
                val optionsJson = dataMatch.destructured.component1().replace("&quot;", "\"").replace("&amp;", "&")
                for (match in Regex("""(https?://[^"]+\.(?:mp4|m3u8)[^"]*)""").findAll(optionsJson)) {
                    callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to USER_AGENT)  // 👈 NUEVO
                    })
                    return true
                }
            }
            Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""").find(html)?.let { m ->
                callback(newExtractorLink(source = serverName, name = serverName, url = m.destructured.component1()) {
                    this.referer = videoUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to USER_AGENT)  // 👈 NUEVO
                })
                return true
            }
            for (match in Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                    this.referer = videoUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to USER_AGENT)  // 👈 NUEVO
                })
                return true
            }
        } catch (_: Exception) {}
        return false
    }
    // ========== EXTRACTOR DAILYMOTION API ==========
    private suspend fun extractDailymotionApi(
        videoId: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val jsonText = app.get(apiUrl,
                referer = "https://www.dailymotion.com/embed/video/$videoId",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                timeout = 15L).text

            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(jsonText)) {
                try {
                    generateM3u8(serverName, match.value, "https://www.dailymotion.com").forEach(callback)
                    return true
                } catch (_: Exception) {}
            }
            val mp4Urls = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(jsonText).map { it.value }.distinct().toList()
            if (mp4Urls.isNotEmpty()) {
                for (url in mp4Urls) {
                    val q = when {
                        url.contains("1080") -> Qualities.P1080.value
                        url.contains("720") -> Qualities.P720.value
                        url.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                    callback(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = url) {
                        this.referer = "https://www.dailymotion.com"
                        this.quality = q
                    })
                }
                return true
            }
        } catch (_: Exception) {}

        try {
            loadExtractor("https://www.dailymotion.com/embed/video/$videoId", referer, subtitleCallback = {}, callback)
            return true
        } catch (_: Exception) {}

        return false
    }

    // ========== EXTRACTOR RUMBLE ==========
    private suspend fun extractRumble(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(embedUrl, referer = referer, timeout = 30)
            val html = response.text

            // HLS auto
            val hlsAutoPatterns = listOf(
                Regex(""""hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)""""),
                Regex(""""hls"\s*:\s*\{\s*"url"\s*:\s*"([^"]+\.m3u8[^"]*)""""),
                Regex(""""url"\s*:\s*"(https?://rumble\.com/hls-vod/[^"]+\.m3u8[^"]*)""""),
            )
            for (pattern in hlsAutoPatterns) {
                val m = pattern.find(html)
                if (m != null) {
                    val url = m.destructured.component1()
                        .replace("\\/", "/")
                        .replace("\\u0026", "&")
                    try {
                        generateM3u8(serverName, url, referer).forEach(callback)
                        return
                    } catch (_: Exception) {}
                }
            }

            // ua.tar qualities
            val tarBlockMatch = Regex(""""ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})""").find(html)
            if (tarBlockMatch != null) {
                val tarBlock = tarBlockMatch.groupValues[1]
                Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
                    val qLabel = match.groupValues[1]
                    val url = match.groupValues[2]
                        .replace("\\/", "/")
                        .replace("\\u0026", "&")
                    val quality = when (qLabel) {
                        "2160", "1440" -> Qualities.P2160.value
                        "1080" -> Qualities.P1080.value
                        "720" -> Qualities.P720.value
                        "480" -> Qualities.P480.value
                        "360" -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName ${qLabel}p",
                            url = url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.quality = quality
                        }
                    )
                }
                return
            }

            // mp4 array legacy
            val jsonPatterns = listOf(
                Regex(""""ua"\s*:\s*\{[^}]*"mp4"\s*:\s*\[([^\]]+)\]"""),
                Regex(""""mp4"\s*:\s*\[([^\]]+)\]"""),
            )
            for (pattern in jsonPatterns) {
                val jsonMatch = pattern.find(html)
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
                            newExtractorLink(
                                source = serverName,
                                name = "$serverName ${quality / 1000}p",
                                url = url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer
                                this.quality = quality
                            }
                        )
                    }
                    return
                }
            }

            // rmbl.ws CDN
            val rmblPatterns = listOf(
                Regex("""["'](https?://[^"']*rmbl\.ws[^"']*\.mp4[^"']*)["']"""),
                Regex("""(https?://[^\s"'<>]*rmbl\.ws[^\s"'<>]*\.mp4[^\s"'<>]*)"""),
                Regex("""["'](https?://[^"']*rmbl\.ws[^"']*)["']"""),
            )
            for (pattern in rmblPatterns) {
                val matches = pattern.findAll(html).toList()
                if (matches.isNotEmpty()) {
                    for (match in matches) {
                        val url = match.destructured.component1()
                        val quality = when {
                            url.contains("1080") -> Qualities.P1080.value
                            url.contains("720") -> Qualities.P720.value
                            url.contains("480") -> Qualities.P480.value
                            url.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        callback(
                            newExtractorLink(
                                source = serverName,
                                name = "$serverName ${quality / 1000}p",
                                url = url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer
                                this.quality = quality
                            }
                        )
                    }
                    return
                }
            }

            // m3u8 genérico
            val m3u8Patterns = listOf(
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?)"""),
            )
            for (pattern in m3u8Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val url = match.destructured.component1()
                    try {
                        generateM3u8(serverName, url, referer).forEach(callback)
                        return
                    } catch (_: Exception) {}
                }
            }

            // mp4 genérico
            val mp4Patterns = listOf(
                Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),
                Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)"""),
            )
            for (pattern in mp4Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val url = match.destructured.component1()
                    val quality = when {
                        url.contains("1080") -> Qualities.P1080.value
                        url.contains("720") -> Qualities.P720.value
                        url.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName ${quality / 1000}p",
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = quality
                        }
                    )
                    return
                }
            }

            // og:video
            val ogVideoPattern = Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""")
            val ogMatch = ogVideoPattern.find(html)
            if (ogMatch != null) {
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = ogMatch.destructured.component1(),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // ========== EXTRACTOR STREAMABLE ==========
    private suspend fun extractStreamable(
        embedUrl: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(embedUrl, referer = referer, timeout = 15L).text
            val seen = mutableSetOf<String>()
            val mp4Pattern = Regex("""["'](//cdn-cf-[^"']*streamable\.com/video/[^"']+\.mp4[^"']*)["']""")
            for (match in mp4Pattern.findAll(html)) {
                var url = match.groupValues[1]
                if (url.startsWith("//")) url = "https:$url"
                url = url.replace("&amp;", "&").replace("\\u0026", "&").replace("\\/", "/")
                if (url in seen) continue
                seen.add(url)
                val quality = when {
                    url.contains("/video/mp4-mobile/") -> Qualities.P360.value
                    url.contains("/video/mp4/") -> Qualities.P720.value
                    else -> Qualities.Unknown.value
                }
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = "$serverName ${quality / 1000}p",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://streamable.com/"
                        this.quality = quality
                    }
                )
            }
            if (seen.isNotEmpty()) return
        } catch (_: Exception) {}
        try { loadExtractor(embedUrl, referer, subtitleCallback, callback) } catch (_: Exception) {}
    }
}
