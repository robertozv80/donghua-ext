package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.Qualities
import kotlin.collections.ArrayList

class MundoDonghuaProvider : MainAPI() {

    override var mainUrl = "https://www.mundodonghua.com"
    override var name = "MundoDonghua"
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
        "$mainUrl/lista-donghuas" to "Donghuas",
        "$mainUrl/lista-donghuas-emision" to "En Emisión",
        "$mainUrl/lista-donghuas-finalizados" to "Finalizados",
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
            request.data
        } else {
            if (page > 1) "${request.data}/$page" else request.data
        }
        val doc = app.get(url, timeout = 120).document

        val home = if (isHomePage) {
            // Página principal: sección "Nuevos Episodios"
            val episodeCards = doc.select("div#nuevos-episodios-grid div.md-card")
            if (episodeCards.isNotEmpty()) {
                episodeCards.mapNotNull { card -> parseEpisodeCard(card) }
            } else {
                doc.select("div.md-card").mapNotNull { card ->
                    val href = card.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    if (href.contains("/ver/")) parseEpisodeCard(card) else null
                }
            }
        } else {
            // Listas de donghuas: link a /donghua/
            doc.select("div.md-card").mapNotNull { card ->
                val title = card.selectFirst("h5.md-card-title, h3.md-card-title")?.text() ?: return@mapNotNull null
                val poster = card.selectFirst("div.md-card-img img")?.attr("src")
                val href = card.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, resolveUrl(href)) {
                    this.posterUrl = resolveUrl(poster ?: "")
                    addDubStatus(dubstat)
                }
            }
        }

        val hasNext = if (isHomePage) {
            doc.select("#episodios-load-more, a.md-load-more").isNotEmpty()
        } else {
            doc.select("nav.md-pagination a, ul.pagination a").isNotEmpty()
        }

        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun parseEpisodeCard(card: org.jsoup.nodes.Element): SearchResponse? {
        val title = card.selectFirst("h5.md-card-title, h3.md-card-title")?.text() ?: return null
        val poster = card.selectFirst("div.md-card-img img")?.attr("src")
        val href = card.selectFirst("a")?.attr("href") ?: return null

        val isLimited = card.hasClass("limited") || card.selectFirst("i.fa-lock") != null
            || card.selectFirst(".md-card-meta span")?.text()?.contains("Limitado") == true

        val donghuaUrl = convertEpisodeToDonghuaUrl(href)

        val epNum = Regex("Episodio\\s*(\\d+)").find(title)?.destructured?.component1()?.toIntOrNull()
            ?: Regex("/(\\d+)/?$").find(href)?.destructured?.component1()?.toIntOrNull()

        val cleanTitle = title.replace(Regex("\\s*Episodio\\s*\\d+"), "").trim()
        val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed

        return newAnimeSearchResponse(cleanTitle, donghuaUrl) {
            this.posterUrl = resolveUrl(poster ?: "")
            addDubStatus(dubstat, epNum)
        }
    }

    private fun convertEpisodeToDonghuaUrl(href: String): String {
        val fullUrl = resolveUrl(href)
        val regex = Regex("/ver/([^/]+)")
        val match = regex.find(fullUrl)
        return if (match != null) {
            val slug = match.destructured.component1()
            "$mainUrl/donghua/$slug"
        } else {
            fullUrl
        }
    }

    /**
     * FIX: Búsqueda usa /busquedas/{query} (path segment, NO query parameter)
     * También corrige selectores: h5.md-card-title (no h3)
     */
    override suspend fun search(query: String): List<SearchResponse> {
        // URL codificada como path segment (NO como query parameter)
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/busquedas/$encodedQuery"
        val doc = app.get(searchUrl, timeout = 120).document

        return doc.select("div.md-card").mapNotNull { card ->
            val title = card.selectFirst("h5.md-card-title, h3.md-card-title")?.text() ?: return@mapNotNull null
            val href = card.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = card.selectFirst("div.md-card-img img")?.attr("src")
            val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
            newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                this.posterUrl = resolveUrl(image ?: "")
                addDubStatus(dubstat)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val donghuaUrl = if (url.contains("/ver/")) {
            convertEpisodeToDonghuaUrl(url)
        } else {
            url
        }

        val doc = app.get(donghuaUrl, timeout = 120).document
        val poster = doc.selectFirst("div.md-detail-poster > img")?.attr("src")
            ?: doc.selectFirst("div.md-detail-banner-bg > img")?.attr("src")
            ?: doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: ""
        val title = doc.selectFirst("h1.md-detail-title")?.text() ?: ""
        val description = doc.selectFirst("p.md-detail-synopsis")?.text() ?: ""
        val genres = doc.select("a.md-genre-tag").map { it.text() }
        val status = when (doc.selectFirst("span.md-emision-badge")?.text()?.trim()) {
            "En Emisión" -> ShowStatus.Ongoing
            "Finalizada" -> ShowStatus.Completed
            else -> null
        }

        val episodes = ArrayList<Episode>()
        doc.select("li.md-episode-item").map { epItem ->
            val link = epItem.selectFirst("a.md-ep-link")?.attr("href") ?: return@map
            val epNum = epItem.attr("data-ep")?.toIntOrNull()
                ?: Regex("/(\\d+)/?$").find(link)?.destructured?.component1()?.toIntOrNull()
            episodes.add(
                newEpisode(resolveUrl(link)) {
                    this.episode = epNum
                }
            )
        }

        val typeBadge = doc.selectFirst("span.md-card-badge")?.text()?.lowercase()
            ?: doc.selectFirst("span.md-badge-static")?.text()?.lowercase()
            ?: ""
        val tvType = when {
            typeBadge.contains("película") || typeBadge.contains("pelicula") -> TvType.AnimeMovie
            typeBadge.contains("ova") || typeBadge.contains("especial") -> TvType.OVA
            else -> TvType.Anime
        }

        if (episodes.isEmpty() && tvType == TvType.AnimeMovie) {
            return newMovieLoadResponse(title, donghuaUrl, TvType.AnimeMovie, donghuaUrl) {
                posterUrl = poster; plot = description; tags = genres
            }
        }

        if (episodes.isEmpty()) {
            val slug = donghuaUrl.substringAfter("/donghua/")
            val epCountText = doc.select("span.md-detail-meta-stat, p.md-info-item")
                .map { it.text() }.find { it.contains("episodio") || it.contains("Episodios") }
            val totalEps = Regex("(\\d+)").find(epCountText ?: "")?.value?.toIntOrNull() ?: 0

            for (ep in 1..totalEps) {
                episodes.add(
                    newEpisode("$mainUrl/ver/$slug/$ep") {
                        this.episode = ep
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, donghuaUrl, tvType) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
            showStatus = status; plot = description; tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 120).document
        val datafix = data.replace("ñ", "%C3%B1")

        val reqHEAD = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to datafix,
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "TE" to "trailers"
        )

        val serverTabs = doc.select("button.md-server-tab[data-target]").map {
            it.attr("data-target") to it.text().trim()
        }
        val hasTamamo = serverTabs.any { it.first == "tamamo" }
        val hasAsura = serverTabs.any { it.first == "asura" }

        for (script in doc.select("script")) {
            val scriptData = script.data()
            if (scriptData.contains("eval(function(p,a,c,k,e")) {
                val packedRegex = Regex("eval\\(function\\(p,a,c,k,e,.*\\)\\)")
                val packedList = packedRegex.findAll(scriptData).map { it.value }.toList()
                for (packed in packedList) {
                    try {
                        val unpack = getAndUnpack(packed)

                        if (unpack.contains("asura_player") || unpack.contains("redirector")) {
                            val redirectorRegex = Regex("redirector\\.php\\?slug=([A-Za-z0-9+/=]+)")
                            val asuraSlug = redirectorRegex.find(unpack)?.destructured?.component1()
                            if (!asuraSlug.isNullOrEmpty()) {
                                try {
                                    val m3u8Url = "https://www.mdplayer.xyz/nemonicplayer/redirector.php?slug=$asuraSlug"
                                    val testResp = app.get(m3u8Url, headers = reqHEAD, timeout = 15)
                                    val text = testResp.text
                                    if (text.contains("#EXTM3U") || text.contains(".m3u8")) {
                                        generateM3u8("Asura", m3u8Url, datafix).forEach(callback)
                                    }
                                } catch (_: Exception) {
                                    try {
                                        val m3u8Url = "https://www.mdplayer.xyz/nemonicplayer/redirector.php?slug=$asuraSlug"
                                        generateM3u8("Asura", m3u8Url, datafix).forEach(callback)
                                    } catch (_: Exception) {}
                                }
                            }

                            val fileRegex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                            val fileUrl = fileRegex.find(unpack)?.destructured?.component1()
                            if (!fileUrl.isNullOrEmpty()) {
                                try { generateM3u8("Asura", fileUrl, datafix).forEach(callback) } catch (_: Exception) {}
                            }
                        }

                        if (unpack.contains("protea_tab") || unpack.contains("tamamo") || unpack.contains("api_donghua")) {
                            val slugPatterns = listOf(
                                Regex("""slug["']?\s*:\s*["']([A-Za-z0-9+/=]+)["']"""),
                                Regex("""data\s*:\s*\{\s*["']slug["']\s*:\s*["']([A-Za-z0-9+/=]+)["']"""),
                            )
                            for (pattern in slugPatterns) {
                                val slug = pattern.find(unpack)?.destructured?.component1()
                                if (!slug.isNullOrEmpty()) {
                                    try {
                                        val apiResp = app.get("$mainUrl/api_donghua.php?slug=$slug", headers = reqHEAD, timeout = 15).text
                                        val keyRegex = Regex("""\"url\"\s*:\s*\"([^\"]+)\"""")
                                        val apiKey = keyRegex.find(apiResp)?.destructured?.component1()
                                        if (!apiKey.isNullOrEmpty()) {
                                            val playerUrl = "https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=$apiKey"
                                            val playerResp = app.get(playerUrl, headers = reqHEAD, timeout = 15).text
                                            val dmIdPatterns = listOf(
                                                Regex("""video\s*:\s*["']([A-Za-z0-9]+)["']"""),
                                                Regex("""DM\.player\([^,]+,\s*\{[^}]*video\s*:\s*["']([A-Za-z0-9]+)["']"""),
                                                Regex("""video["']\s*:\s*["']([A-Za-z0-9]+)["']"""),
                                            )
                                            for (dmPattern in dmIdPatterns) {
                                                val vidID = dmPattern.find(playerResp)?.destructured?.component1()
                                                if (!vidID.isNullOrEmpty()) {
                                                    val dmUrl = "https://www.dailymotion.com/embed/video/$vidID"
                                                    loadExtractor(dmUrl, data, subtitleCallback, callback)
                                                    break
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                    break
                                }
                            }
                        }

                        val fmRegex = Regex("bysekoze\\.com/e/([a-zA-Z0-9]+)")
                        val fmId = fmRegex.find(unpack)?.destructured?.component1()
                        if (!fmId.isNullOrEmpty()) {
                            try { loadExtractor("https://bysekoze.com/e/$fmId", data, subtitleCallback, callback) } catch (_: Exception) {}
                        }

                        val voeRegex = Regex("voe\\.sx/e/([a-zA-Z0-9]+)")
                        val voeId = voeRegex.find(unpack)?.destructured?.component1()
                        if (!voeId.isNullOrEmpty()) {
                            try { loadExtractor("https://voe.sx/e/$voeId", data, subtitleCallback, callback) } catch (_: Exception) {}
                        }

                        val vhRegex = Regex("vidhidepro\\.com/v/([a-zA-Z0-9]+)")
                        val vhId = vhRegex.find(unpack)?.destructured?.component1()
                        if (!vhId.isNullOrEmpty()) {
                            try { loadExtractor("https://vidhidepro.com/v/$vhId", data, subtitleCallback, callback) } catch (_: Exception) {}
                        }

                        val swRegex = Regex("embedwish\\.com/e/([a-zA-Z0-9]+)")
                        val swId = swRegex.find(unpack)?.destructured?.component1()
                        if (!swId.isNullOrEmpty()) {
                            try { loadExtractor("https://embedwish.com/e/$swId", data, subtitleCallback, callback) } catch (_: Exception) {}
                        }

                        val urlRegex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
                        for (match in urlRegex.findAll(unpack)) {
                            val foundUrl = match.value
                            try {
                                if (foundUrl.contains(".m3u8")) {
                                    generateM3u8("Server", foundUrl, datafix).forEach(callback)
                                } else {
                                    callback(newExtractorLink(source = "Server", name = "Server", url = foundUrl) {
                                        this.referer = datafix; this.quality = Qualities.Unknown.value
                                    })
                                }
                            } catch (_: Exception) {}
                        }

                        val iframeRegexes = listOf(
                            Regex("""(https?://voe\.sx/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*bysekoze\.com/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*embedwish\.com/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*vidhidepro\.com/v/[^\s"'<>]+)"""),
                        )
                        for (regex in iframeRegexes) {
                            for (match in regex.findAll(unpack)) {
                                try { loadExtractor(match.value, data, subtitleCallback, callback) } catch (_: Exception) {}
                            }
                        }

                    } catch (_: Exception) {}
                }
            }
        }

        return true
    }
}
