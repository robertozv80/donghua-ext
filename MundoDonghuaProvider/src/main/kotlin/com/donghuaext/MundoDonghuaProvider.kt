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
                episodeCards.mapNotNull { card ->
                    parseEpisodeCard(card)
                }
            } else {
                // Fallback: buscar todas las md-card que link a /ver/
                doc.select("div.md-card").mapNotNull { card ->
                    val href = card.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    if (href.contains("/ver/")) {
                        parseEpisodeCard(card)
                    } else {
                        null
                    }
                }
            }
        } else {
            // Listas de donghuas: link a /donghua/
            doc.select("div.md-card").mapNotNull { card ->
                val title = card.selectFirst("h3.md-card-title")?.text() ?: return@mapNotNull null
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

    /**
     * Parsea una tarjeta de episodio del homepage
     */
    private fun parseEpisodeCard(card: org.jsoup.nodes.Element): SearchResponse? {
        val title = card.selectFirst("h3.md-card-title")?.text() ?: return null
        val poster = card.selectFirst("div.md-card-img img")?.attr("src")
        val href = card.selectFirst("a")?.attr("href") ?: return null

        // Saltar episodios limitados/VIP
        val isLimited = card.hasClass("limited") || card.selectFirst("i.fa-lock") != null
            || card.selectFirst(".md-card-meta span")?.text()?.contains("Limitado") == true

        // Convertir URL de episodio a URL de donghua
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

    /**
     * Convierte URL de episodio a URL de donghua
     * /ver/{slug}/{ep_num} → /donghua/{slug}
     */
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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/busquedas/${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(searchUrl, timeout = 120).document

        return doc.select("div.md-card").mapNotNull { card ->
            val title = card.selectFirst("h3.md-card-title")?.text() ?: return@mapNotNull null
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
        // Si la URL es de un episodio, convertir a página de donghua
        val donghuaUrl = if (url.contains("/ver/")) {
            convertEpisodeToDonghuaUrl(url)
        } else {
            url
        }

        val doc = app.get(donghuaUrl, timeout = 120).document

        // FIX: Poster usa URLs relativas (/thumbs/...) → necesita resolveUrl()
        val poster = doc.selectFirst("div.md-detail-poster > img")?.attr("src")
            ?: doc.selectFirst("div.md-detail-banner-bg > img")?.attr("src")
            ?: doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: ""
        val resolvedPoster = resolveUrl(poster)

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
                posterUrl = resolvedPoster
                plot = description
                tags = genres
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
            posterUrl = resolvedPoster
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
            showStatus = status
            plot = description
            tags = genres
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

        var foundLinks = false

        // Detectar qué pestañas de servidores existen
        val serverTabs = doc.select("button.md-server-tab[data-target]").map {
            it.attr("data-target") to it.text()?.trim()
        }
        val hasTamamo = serverTabs.any { it.first == "tamamo" }
        val hasAsura = serverTabs.any { it.first == "asura" }
        val hasFmoon = serverTabs.any { it.first == "fmoon" }
        val hasAmagi = serverTabs.any { it.first == "amagi" }

        // Extraer videos desde el JavaScript packed (Dean Edwards packing)
        for (script in doc.select("script")) {
            val scriptData = script.data()
            if (scriptData.contains("eval(function(p,a,c,k,e")) {
                val packedRegex = Regex("eval\\(function\\(p,a,c,k,e,.*\\)\\)")
                val packedList = packedRegex.findAll(scriptData).map { it.value }.toList()
                for (packed in packedList) {
                    try {
                        val unpack = getAndUnpack(packed)

                        // ===== Asura (HLS m3u8) - Servidor principal sin anuncios =====
                        if (unpack.contains("asura_player") || unpack.contains("redirector")) {
                            // Extraer slug del redirector
                            val redirectorRegex = Regex("redirector\\.php\\?slug=([A-Za-z0-9+/=]+)")
                            val asuraSlug = redirectorRegex.find(unpack)?.destructured?.component1()
                            if (!asuraSlug.isNullOrEmpty()) {
                                try {
                                    val m3u8Url = "https://www.mdplayer.xyz/nemonicplayer/redirector.php?slug=$asuraSlug"
                                    generateM3u8("Asura", m3u8Url, datafix).forEach(callback)
                                    foundLinks = true
                                } catch (_: Exception) {
                                    // Fallback: intentar con mdnemonicplayer
                                    try {
                                        val m3u8Url2 = "https://www.mdnemonicplayer.xyz/nemonicplayer/redirector.php?slug=$asuraSlug"
                                        generateM3u8("Asura", m3u8Url2, datafix).forEach(callback)
                                        foundLinks = true
                                    } catch (_: Exception) {}
                                }
                            }

                            // También buscar URL m3u8 directa en el unpack
                            val fileRegex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                            val fileUrl = fileRegex.find(unpack)?.destructured?.component1()
                            if (!fileUrl.isNullOrEmpty()) {
                                try {
                                    generateM3u8("Asura", fileUrl, datafix).forEach(callback)
                                    foundLinks = true
                                } catch (_: Exception) {}
                            }
                        }

                        // ===== Tamamo / Protea (Dailymotion vía API) =====
                        if (unpack.contains("protea_tab") || unpack.contains("tamamo") || unpack.contains("api_donghua")) {
                            // Extraer el slug para la API
                            val slugPatterns = listOf(
                                Regex("""slug["']?\s*:\s*["']([A-Za-z0-9+/=]+)["']"""),
                                Regex("""data\s*:\s*\{\s*["']slug["']\s*:\s*["']([A-Za-z0-9+/=]+)["']"""),
                            )
                            for (pattern in slugPatterns) {
                                val slug = pattern.find(unpack)?.destructured?.component1()
                                if (!slug.isNullOrEmpty()) {
                                    try {
                                        // Paso 1: Llamar a la API
                                        val apiResp = app.get("$mainUrl/api_donghua.php?slug=$slug", headers = reqHEAD, timeout = 15).text
                                        // Paso 2: Parsear respuesta JSON: [{"url":"BASE64_KEY"}] o {"id":{"url":"key"}}
                                        try {
                                            // Formato: {"0": {"url": "KEY"}} o [{"url": "KEY"}]
                                            val keyRegex = Regex("""\"url\"\s*:\s*\"([^\"]+)\"""")
                                            val apiKey = keyRegex.find(apiResp)?.destructured?.component1()
                                            if (!apiKey.isNullOrEmpty()) {
                                                // Paso 3: Obtener página del reproductor Dailymotion
                                                val playerUrl = "https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=$apiKey"
                                                val playerResp = app.get(playerUrl, headers = reqHEAD, timeout = 15).text
                                                // Paso 4: Extraer ID del video Dailymotion
                                                val dmIdPatterns = listOf(
                                                    Regex("""video\s*:\s*["']([A-Za-z0-9]+)["']"""),
                                                    Regex("""DM\.player\([^,]+,\s*\{[^}]*video\s*:\s*["']([A-Za-z0-9]+)["']"""),
                                                    Regex("""video["']\s*:\s*["']([A-Za-z0-9]+)["']"""),
                                                )
                                                for (dmPattern in dmIdPatterns) {
                                                    val vidID = dmPattern.find(playerResp)?.destructured?.component1()
                                                    if (!vidID.isNullOrEmpty()) {
                                                        val dmUrl = "https://www.dailymotion.com/embed/video/$vidID"
                                                        try {
                                                            loadExtractor(dmUrl, data, subtitleCallback, callback)
                                                        } catch (_: Exception) {
                                                            extractDailymotion(vidID, datafix, "Tamamo", callback)
                                                        }
                                                        foundLinks = true
                                                        break
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    } catch (_: Exception) {}
                                    break
                                }
                            }
                        }

                        // ===== Fmoon / FileMoon (bysekoze.com / streamembed.com) =====
                        val fmRegexes = listOf(
                            Regex("bysekoze\\.com/e/([a-zA-Z0-9]+)"),
                            Regex("streamembed\\.com/e/([a-zA-Z0-9]+)"),
                        )
                        for (fmRegex in fmRegexes) {
                            val fmId = fmRegex.find(unpack)?.destructured?.component1()
                            if (!fmId.isNullOrEmpty()) {
                                val fmDomain = if (fmRegex.pattern.contains("bysekoze")) "bysekoze.com" else "streamembed.com"
                                try {
                                    loadExtractor("https://$fmDomain/e/$fmId", data, subtitleCallback, callback)
                                    foundLinks = true
                                } catch (_: Exception) {}
                                break
                            }
                        }

                        // ===== Voe (voe.sx) =====
                        val voeRegex = Regex("voe\\.sx/e/([a-zA-Z0-9]+)")
                        val voeId = voeRegex.find(unpack)?.destructured?.component1()
                        if (!voeId.isNullOrEmpty()) {
                            try {
                                loadExtractor("https://voe.sx/e/$voeId", data, subtitleCallback, callback)
                                foundLinks = true
                            } catch (_: Exception) {
                                extractVoe("https://voe.sx/e/$voeId", datafix, "Voe", callback)
                                foundLinks = true
                            }
                        }

                        // ===== VidHide (vidhidepro.com) =====
                        val vhRegex = Regex("vidhidepro\\.com/v/([a-zA-Z0-9]+)")
                        val vhId = vhRegex.find(unpack)?.destructured?.component1()
                        if (!vhId.isNullOrEmpty()) {
                            try {
                                loadExtractor("https://vidhidepro.com/v/$vhId", data, subtitleCallback, callback)
                                foundLinks = true
                            } catch (_: Exception) {}
                        }

                        // ===== StreamWish (embedwish.com) =====
                        val swRegex = Regex("embedwish\\.com/e/([a-zA-Z0-9]+)")
                        val swId = swRegex.find(unpack)?.destructured?.component1()
                        if (!swId.isNullOrEmpty()) {
                            try {
                                loadExtractor("https://embedwish.com/e/$swId", data, subtitleCallback, callback)
                                foundLinks = true
                            } catch (_: Exception) {}
                        }

                        // ===== Fallback: buscar URLs m3u8/mp4 en el unpack =====
                        if (!foundLinks) {
                            val urlRegex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
                            for (match in urlRegex.findAll(unpack)) {
                                val foundUrl = match.value
                                try {
                                    if (foundUrl.contains(".m3u8")) {
                                        generateM3u8("Server", foundUrl, datafix).forEach(callback)
                                    } else {
                                        callback(newExtractorLink(source = "Server", name = "Server", url = foundUrl) {
                                            this.referer = datafix
                                            this.quality = Qualities.Unknown.value
                                        })
                                    }
                                    foundLinks = true
                                } catch (_: Exception) {}
                            }
                        }

                        // ===== Buscar URLs de iframes conocidos en el unpack =====
                        if (!foundLinks) {
                            val iframeRegexes = listOf(
                                Regex("""(https?://voe\.sx/e/[^\s"'<>]+)"""),
                                Regex("""(https?://[^\s"'<>]*bysekoze\.com/e/[^\s"'<>]+)"""),
                                Regex("""(https?://[^\s"'<>]*embedwish\.com/e/[^\s"'<>]+)"""),
                                Regex("""(https?://[^\s"'<>]*vidhidepro\.com/v/[^\s"'<>]+)"""),
                                Regex("""(https?://[^\s"'<>]*streamembed\.com/e/[^\s"'<>]+)"""),
                            )
                            for (regex in iframeRegexes) {
                                for (match in regex.findAll(unpack)) {
                                    try {
                                        loadExtractor(match.value, data, subtitleCallback, callback)
                                        foundLinks = true
                                    } catch (_: Exception) {}
                                }
                            }
                        }

                    } catch (_: Exception) {}
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

        return foundLinks
    }

    /**
     * Extracción manual de video Dailymotion
     */
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

    /**
     * Extracción manual de video Voe.sx
     */
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
}
