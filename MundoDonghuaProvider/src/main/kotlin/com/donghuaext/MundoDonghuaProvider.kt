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
            // FIX: Buscar imágenes también en data-src y usar resolveUrl para URLs relativas
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
                val poster = card.selectFirst("div.md-card-img img")?.let { getBestImgSrc(it) }
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
     * Obtiene la mejor URL de imagen de un elemento img.
     * Prioriza data-src (lazy loading) sobre src, y maneja URLs relativas.
     */
    private fun getBestImgSrc(imgEl: org.jsoup.nodes.Element): String {
        val dataSrc = imgEl.attr("data-src")?.trim()
        if (!dataSrc.isNullOrEmpty() && dataSrc.startsWith("http")) return dataSrc
        if (!dataSrc.isNullOrEmpty() && dataSrc.startsWith("/")) return dataSrc

        val src = imgEl.attr("src")?.trim()
        if (!src.isNullOrEmpty() && !src.contains("data:image")) return src

        // Último intento: noscript fallback
        val noscriptSrc = imgEl.parent()?.selectFirst("noscript img")?.attr("src")?.trim()
        if (!noscriptSrc.isNullOrEmpty()) return noscriptSrc

        return src ?: ""
    }

    /**
     * Parsea una tarjeta de episodio del homepage
     * Las tarjetas link a /ver/{slug}/{ep_num} - convertir a /donghua/{slug}
     */
    private fun parseEpisodeCard(card: org.jsoup.nodes.Element): SearchResponse? {
        val title = card.selectFirst("h3.md-card-title")?.text() ?: return null
        val poster = card.selectFirst("div.md-card-img img")?.let { getBestImgSrc(it) }
        val href = card.selectFirst("a")?.attr("href") ?: return null

        // Saltar episodios limitados/VIP
        val isLimited = card.hasClass("limited") || card.selectFirst("i.fa-lock") != null
            || card.selectFirst(".md-card-meta span")?.text()?.contains("Limitado") == true

        // Convertir URL de episodio a URL de donghua
        // /ver/{slug}/{ep_num} → /donghua/{slug}
        val donghuaUrl = convertEpisodeToDonghuaUrl(href)

        // Extraer número de episodio del título o URL
        val epNum = Regex("Episodio\\s*(\\d+)").find(title)?.destructured?.component1()?.toIntOrNull()
            ?: Regex("/(\\d+)/?$").find(href)?.destructured?.component1()?.toIntOrNull()

        // Limpiar título (quitar "Episodio X")
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
     * /ver/{slug}/{ep_num}/{token} → /donghua/{slug} (episodios limitados)
     */
    private fun convertEpisodeToDonghuaUrl(href: String): String {
        val fullUrl = resolveUrl(href)
        // Patrón: /ver/{slug}/{ep_num} o /ver/{slug}/{ep_num}/{token}
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
     * FIX CRÍTICO: La búsqueda usa /busquedas/{query} (path segment)
     * NO /busquedas/?donghua={query} (query parameter) que devuelve TODO sin filtrar
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/busquedas/${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(searchUrl, timeout = 120).document

        return doc.select("div.md-card").mapNotNull { card ->
            val title = card.selectFirst("h5.md-card-title")?.text()
                ?: card.selectFirst("h3.md-card-title")?.text()
                ?: card.selectFirst(".md-card-title")?.text()
                ?: return@mapNotNull null
            val href = card.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = card.selectFirst("div.md-card-img img")?.let { getBestImgSrc(it) }
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
        // FIX: Usar getBestImgSrc para manejar lazy loading de imágenes
        val poster = doc.selectFirst("div.md-detail-poster > img")?.let { getBestImgSrc(it) }
            ?: doc.selectFirst("div.md-detail-banner-bg > img")?.let { getBestImgSrc(it) }
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
        // Todos los episodios están renderizados en la página (sin paginación)
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

        // Para películas sin episodios, devolver respuesta de película
        if (episodes.isEmpty() && tvType == TvType.AnimeMovie) {
            return newMovieLoadResponse(title, donghuaUrl, TvType.AnimeMovie, donghuaUrl) {
                posterUrl = resolvedPoster
                plot = description
                tags = genres
            }
        }

        // Si no hay episodios en la página de detalle, intentar construir URLs
        if (episodes.isEmpty()) {
            val slug = donghuaUrl.substringAfter("/donghua/")
            // Buscar el número total de episodios en la información de la página
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

        // Detectar qué pestañas de servidores existen
        val serverTabs = doc.select("button.md-server-tab[data-target]").map {
            it.attr("data-target") to it.text()?.trim()
        }
        val hasTamamo = serverTabs.any { it.first == "tamamo" }
        val hasAsura = serverTabs.any { it.first == "asura" }
        val hasFmoon = serverTabs.any { it.first == "fmoon" }
        val hasVhide = serverTabs.any { it.first == "vhide" }
        val hasKaga = serverTabs.any { it.first == "kaga" }
        val hasSwish = serverTabs.any { it.first == "swish" }

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
                                } catch (_: Exception) {
                                    // Fallback: intentar generar M3U8 sin verificar
                                    try {
                                        val m3u8Url = "https://www.mdplayer.xyz/nemonicplayer/redirector.php?slug=$asuraSlug"
                                        generateM3u8("Asura", m3u8Url, datafix).forEach(callback)
                                    } catch (_: Exception) {}
                                }
                            }

                            // También buscar URL m3u8 directa en el unpack
                            val fileRegex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                            val fileUrl = fileRegex.find(unpack)?.destructured?.component1()
                            if (!fileUrl.isNullOrEmpty()) {
                                try {
                                    generateM3u8("Asura", fileUrl, datafix).forEach(callback)
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
                                        // Paso 2: Parsear respuesta JSON: [{"url":"BASE64_KEY"}]
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
                                                    // Intentar extracción directa via API
                                                    try {
                                                        val apiUrl = "https://www.dailymotion.com/player/metadata/video/$vidID"
                                                        val jsonText = app.get(apiUrl,
                                                            referer = "https://www.dailymotion.com/embed/video/$vidID",
                                                            headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                                                            timeout = 15L).text
                                                        for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(jsonText)) {
                                                            try { generateM3u8("Tamamo", m.value, "https://www.dailymotion.com").forEach(callback); break } catch (_: Exception) {}
                                                        }
                                                        val mp4Urls = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(jsonText).map { it.value }.distinct().toList()
                                                        if (mp4Urls.isNotEmpty()) {
                                                            for (mp4Url in mp4Urls) {
                                                                val q = when {
                                                                    mp4Url.contains("1080") -> Qualities.P1080.value
                                                                    mp4Url.contains("720") -> Qualities.P720.value
                                                                    mp4Url.contains("480") -> Qualities.P480.value
                                                                    else -> Qualities.Unknown.value
                                                                }
                                                                callback(newExtractorLink(source = "Tamamo", name = "Tamamo ${q/1000}p", url = mp4Url) {
                                                                    this.referer = "https://www.dailymotion.com"
                                                                    this.quality = q
                                                                })
                                                            }
                                                        }
                                                    } catch (_: Exception) {}
                                                    // Fallback: loadExtractor
                                                    val dmUrl = "https://www.dailymotion.com/embed/video/$vidID"
                                                    try { loadExtractor(dmUrl, data, subtitleCallback, callback) } catch (_: Exception) {}
                                                    break
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                    break
                                }
                            }
                        }

                        // ===== Fmoon / FileMoon (bysekoze.com) =====
                        val fmPatterns = listOf(
                            Regex("bysekoze\\.com/e/([a-zA-Z0-9]+)"),
                            Regex("filemoon\\.to/e/([a-zA-Z0-9]+)"),
                            Regex("fmoonplay.*?src=['\"]([^'\"]+)['\"]"),
                        )
                        for (fmPattern in fmPatterns) {
                            val fmMatch = fmPattern.find(unpack)
                            if (fmMatch != null) {
                                val fmUrl = if (fmPattern.pattern.contains("src=")) {
                                    fmMatch.destructured.component1()
                                } else {
                                    "https://bysekoze.com/e/${fmMatch.destructured.component1()}"
                                }
                                try {
                                    loadExtractor(fmUrl, data, subtitleCallback, callback)
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
                            } catch (_: Exception) {}
                        }

                        // ===== Vhide / VidHide (vidhidepro.com) =====
                        val vhPatterns = listOf(
                            Regex("vidhidepro\\.com/v/([a-zA-Z0-9]+)"),
                            Regex("vidhidepro\\.com/e/([a-zA-Z0-9]+)"),
                        )
                        for (vhPattern in vhPatterns) {
                            val vhId = vhPattern.find(unpack)?.destructured?.component1()
                            if (!vhId.isNullOrEmpty()) {
                                try {
                                    val prefix = if (vhPattern.pattern.contains("/v/")) "v" else "e"
                                    loadExtractor("https://vidhidepro.com/$prefix/$vhId", data, subtitleCallback, callback)
                                } catch (_: Exception) {}
                                break
                            }
                        }

                        // ===== Kaga / VgEmbed (vgembed.com) ===== NEW SERVER
                        val kagaPatterns = listOf(
                            Regex("vgembed\\.com/e/([a-zA-Z0-9]+)"),
                            Regex("vgembed\\.com/v/([a-zA-Z0-9]+)"),
                        )
                        for (kagaPattern in kagaPatterns) {
                            val kagaId = kagaPattern.find(unpack)?.destructured?.component1()
                            if (!kagaId.isNullOrEmpty()) {
                                try {
                                    loadExtractor("https://vgembed.com/e/$kagaId", data, subtitleCallback, callback)
                                } catch (_: Exception) {}
                                break
                            }
                        }

                        // ===== Swish / StreamWish (embedwish.com) =====
                        val swPatterns = listOf(
                            Regex("embedwish\\.com/e/([a-zA-Z0-9]+)"),
                            Regex("streamwish\\.to/e/([a-zA-Z0-9]+)"),
                        )
                        for (swPattern in swPatterns) {
                            val swId = swPattern.find(unpack)?.destructured?.component1()
                            if (!swId.isNullOrEmpty()) {
                                try {
                                    loadExtractor("https://embedwish.com/e/$swId", data, subtitleCallback, callback)
                                } catch (_: Exception) {}
                                break
                            }
                        }

                        // ===== Fallback: buscar URLs m3u8/mp4 en el unpack =====
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
                            } catch (_: Exception) {}
                        }

                        // ===== Buscar URLs de iframes conocidos en el unpack =====
                        val iframeRegexes = listOf(
                            Regex("""(https?://voe\.sx/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*bysekoze\.com/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*embedwish\.com/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*vidhidepro\.com/[ve]/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*vgembed\.com/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*streamwish\.to/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*filemoon\.to/e/[^\s"'<>]+)"""),
                        )
                        for (regex in iframeRegexes) {
                            for (match in regex.findAll(unpack)) {
                                try {
                                    loadExtractor(match.value, data, subtitleCallback, callback)
                                } catch (_: Exception) {}
                            }
                        }

                    } catch (_: Exception) {}
                }
            }
        }

        return true
    }
}
