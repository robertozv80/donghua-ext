package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.util.*
import kotlin.collections.ArrayList

class AnimeGratisProvider : MainAPI() {

    override var mainUrl = "https://animegratis.net"
    override var name = "AnimeGratis"
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
        "$mainUrl/directorio" to "Directorio",
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

    // NOTA: NO incluir "Accept-Encoding" - NiceHttp lo gestiona automáticamente.
    // Si forzamos "br" (brotli) y NiceHttp no lo soporta, el HTML llega ilegible.
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
    )

    // ========== MODELOS PARA #ssr-init (directorio) ==========
    data class SsrInit(
        val animes: List<SsrAnime>? = null,
        val total: Int? = null,
        val totalPages: Int? = null,
        val page: Int? = null,
    )

    data class SsrAnime(
        val t: String? = null,       // title
        val sl: String? = null,      // slug
        val sy: String? = null,      // synopsis
        val sc: Double? = null,      // score
        val st: String? = null,      // status: "Finalizado"|"En emisión"|"Próximamente"
        val g: List<SsrGenre>? = null, // genres
        val ty: String? = null,      // type: "series"|"movie"|"ova"|"ona"|"especial"
        val im: String? = null,      // image (webp)
        val fb: String? = null,      // fallback image 1
        val fb2: String? = null,     // fallback image 2 (jpg)
        val yr: String? = null,      // year
        val isMovie: Boolean? = null,
    )

    data class SsrGenre(
        val n: String? = null,  // name
        val s: String? = null,  // slug
    )

    // ========== MODELOS PARA JSON-LD ==========
    data class TvSeriesJsonLd(
        val name: String? = null,
        val alternateName: String? = null,
        val description: String? = null,
        val image: String? = null,
        val genre: Any? = null,
        val numberOfEpisodes: Int? = null,
        val datePublished: String? = null,
        val aggregateRating: AggregateRating? = null,
        val productionCompany: ProductionCompany? = null,
    )

    data class AggregateRating(
        val ratingValue: Double? = null,
        val bestRating: Double? = null,
        val ratingCount: Int? = null,
    )

    data class ProductionCompany(
        val name: String? = null,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == "$mainUrl/"
        val url = if (isHomePage) {
            request.data
        } else {
            if (page > 1) "${request.data}?page=$page" else request.data
        }

        val doc = app.get(url, headers = headers, timeout = 30L).document

        val home = if (isHomePage) {
            // Homepage: buscar tarjetas de episodios
            val emisionCards = doc.select("a[data-home-episode-card]")
            if (emisionCards.isNotEmpty()) {
                emisionCards.mapNotNull { parseHomeEpisodeCard(it) }
            } else {
                // Fallback: buscar enlaces a episodios
                doc.select("a[href*=\"episodio\"]").mapNotNull { parseHomeEpisodeCard(it) }
            }
        } else {
            // Directorio: preferir #ssr-init JSON (más confiable)
            val ssrInit = doc.selectFirst("script#ssr-init")?.data()
            if (!ssrInit.isNullOrEmpty()) {
                try {
                    val ssr = parseJson<SsrInit>(ssrInit)
                    ssr.animes?.mapNotNull { anime ->
                        val title = anime.t ?: return@mapNotNull null
                        val slug = anime.sl ?: return@mapNotNull null
                        val poster = anime.fb2 ?: anime.fb ?: anime.im ?: ""
                        val href = "$mainUrl/anime/$slug-anime"
                        val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                        newAnimeSearchResponse(title, href) {
                            this.posterUrl = resolveUrl(poster)
                            addDubStatus(dubstat)
                        }
                    } ?: emptyList()
                } catch (_: Exception) {
                    parseDirectorioHtml(doc)
                }
            } else {
                parseDirectorioHtml(doc)
            }
        }

        val hasNext = if (isHomePage) false
            else doc.select("link[rel=\"next\"]").isNotEmpty()
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun parseDirectorioHtml(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("div.anime-card").mapNotNull {
            val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("h3 a")?.attr("href")
                ?: it.selectFirst("a:first-child")?.attr("href")
                ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-fb2")
                ?: it.selectFirst("img")?.attr("data-fallback")
                ?: it.selectFirst("img")?.attr("src")
            val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
            newAnimeSearchResponse(title, resolveUrl(href)) {
                this.posterUrl = resolveUrl(poster ?: "")
                addDubStatus(dubstat)
            }
        }
    }

    private fun parseHomeEpisodeCard(link: org.jsoup.nodes.Element): SearchResponse? {
        val href = link.attr("href") ?: return null
        if (!href.contains("episodio")) return null

        val img = link.selectFirst("img")
        val title = img?.attr("alt") ?: link.selectFirst("p.home-anime-title")?.text() ?: link.text()
        val poster = img?.attr("data-fb2") ?: img?.attr("data-fallback") ?: img?.attr("src")
        val cleanTitle = title.replace(Regex("\\s*Episodio\\s*\\d+"), "").trim()
        val seriesUrl = episodeUrlToSeriesUrl(href)
        val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
        val dubstat = if (cleanTitle.contains("Latino") || cleanTitle.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
        return newAnimeSearchResponse(cleanTitle, seriesUrl) {
            this.posterUrl = resolveUrl(poster ?: "")
            addDubStatus(dubstat, epNum)
        }
    }

    /**
     * /anime/{slug}/episodio-{N} → /anime/{slug}-anime
     */
    private fun episodeUrlToSeriesUrl(epHref: String): String {
        val fullUrl = resolveUrl(epHref)
        val regex = Regex("/anime/([^/]+)/episodio-\\d+")
        val match = regex.find(fullUrl)
        return if (match != null) {
            val slug = match.destructured.component1()
            "$mainUrl/anime/$slug-anime"
        } else {
            fullUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val queryLower = query.lowercase(Locale.getDefault())
        val results = ArrayList<SearchResponse>()

        // Intentar primero con el #ssr-init JSON del directorio
        val doc = app.get("$mainUrl/directorio", headers = headers, timeout = 30L).document
        val ssrInit = doc.selectFirst("script#ssr-init")?.data()
        if (!ssrInit.isNullOrEmpty()) {
            try {
                val ssr = parseJson<SsrInit>(ssrInit)
                ssr.animes?.filter { anime ->
                    anime.t?.lowercase(Locale.getDefault())?.contains(queryLower) == true
                }?.mapNotNull { anime ->
                    val title = anime.t ?: return@mapNotNull null
                    val slug = anime.sl ?: return@mapNotNull null
                    val poster = anime.fb2 ?: anime.fb ?: anime.im ?: ""
                    val href = "$mainUrl/anime/$slug-anime"
                    val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                    results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = resolveUrl(poster)
                        addDubStatus(dubstat)
                    })
                }
                if (results.size >= 20) return results
            } catch (_: Exception) {}
        }

        // Fallback: scrapear HTML si no hay JSON
        if (results.isEmpty()) {
            val pageResults = doc.select("div.anime-card").mapNotNull {
                val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
                if (!title.lowercase(Locale.getDefault()).contains(queryLower)) return@mapNotNull null
                val href = it.selectFirst("h3 a")?.attr("href")
                    ?: it.selectFirst("a:first-child")?.attr("href")
                    ?: return@mapNotNull null
                val image = it.selectFirst("img")?.attr("data-fb2")
                    ?: it.selectFirst("img")?.attr("data-fallback")
                    ?: it.selectFirst("img")?.attr("src")
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                    this.posterUrl = resolveUrl(image ?: "")
                    addDubStatus(dubstat)
                }
            }
            results.addAll(pageResults)
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 30L).document

        var jsonLdTitle: String? = null
        var jsonLdDescription: String? = null
        var jsonLdImage: String? = null
        var jsonLdGenres: List<String> = emptyList()
        var jsonLdEpCount: Int? = null

        doc.select("script[type=application/ld+json]").amap { script ->
            try {
                val jsonStr = script.data()
                if (jsonStr.contains("TVSeries") || jsonStr.contains("Movie")) {
                    val jsonLd = parseJson<TvSeriesJsonLd>(jsonStr)
                    jsonLdTitle = jsonLd.name
                    jsonLdDescription = jsonLd.description
                    jsonLdImage = jsonLd.image
                    jsonLdEpCount = jsonLd.numberOfEpisodes
                    jsonLdGenres = when (jsonLd.genre) {
                        is String -> listOf(jsonLd.genre as String)
                        is List<*> -> (jsonLd.genre as List<*>).filterIsInstance<String>()
                        else -> emptyList()
                    }
                }
            } catch (_: Exception) {}
        }

        val title = jsonLdTitle ?: doc.selectFirst("h1")?.text() ?: ""
        val description = jsonLdDescription
            ?: doc.select("h2").amap { h2 ->
                if (h2.text().contains("Sinopsis", ignoreCase = true)) {
                    h2.nextElementSibling()?.text()
                } else null
            }.firstOrNull()
            ?: ""
        // Poster: JSON-LD → img con data-fb2 → data-fallback → src → og:image
        val poster = jsonLdImage
            ?: doc.selectFirst("img[data-fb2]")?.attr("data-fb2")
            ?: doc.selectFirst("img[data-fallback]")?.attr("data-fallback")
            ?: doc.selectFirst("img[src*=\"cdn.animegratis\"]")?.attr("src")
            ?: doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: ""
        val genres = if (jsonLdGenres.isNotEmpty()) jsonLdGenres
            else doc.select("a[href^=\"/directorio/genero/\"]").map { it.text() }

        val statusText = doc.select("span").map { it.text() }.find {
            it.contains("En emisión") || it.contains("Finalizado") || it.contains("Próximamente")
        }
        val status = when {
            statusText?.contains("En emisión") == true -> ShowStatus.Ongoing
            statusText?.contains("Finalizado") == true -> ShowStatus.Completed
            else -> null
        }

        val typeText = doc.select("span").map { it.text() }.find {
            it.contains("Serie") || it.contains("OVA") || it.contains("ONA") || it.contains("Película") || it.contains("Especial")
        }
        val tvType = when {
            typeText?.contains(Regex("Película|Movie")) == true -> TvType.AnimeMovie
            typeText?.contains(Regex("OVA|Especial")) == true -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = ArrayList<Episode>()

        // Buscar episodios: a.episode-card[data-episode] dentro de #episodes-grid
        val epCards = doc.select("#episodes-grid a.episode-card[data-episode]")
        if (epCards.isNotEmpty()) {
            epCards.amap { epCard ->
                val href = epCard.attr("href")
                val epNum = epCard.attr("data-episode")?.toIntOrNull()
                    ?: Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                if (href.isNotEmpty()) {
                    episodes.add(newEpisode(resolveUrl(href)) { this.episode = epNum })
                }
            }
        } else {
            // Fallback: buscar cualquier enlace a episodio
            doc.select("a[href*=\"episodio\"]").amap { epCard ->
                val href = epCard.attr("href")
                val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                if (href.isNotEmpty()) {
                    episodes.add(newEpisode(resolveUrl(href)) { this.episode = epNum })
                }
            }
        }

        // Si JSON-LD indica más episodios de los encontrados, generar URLs
        if (episodes.size < (jsonLdEpCount ?: 0)) {
            val totalEps = jsonLdEpCount ?: episodes.size
            if (totalEps > episodes.size) {
                val slugWithAnime = url.substringAfter("/anime/")
                val slug = slugWithAnime.removeSuffix("-anime").removeSuffix("/")

                for (ep in 1..totalEps) {
                    val epExists = episodes.any { it.episode == ep }
                    if (!epExists) {
                        val epUrl = "$mainUrl/anime/$slug/episodio-$ep"
                        episodes.add(newEpisode(epUrl) { this.episode = ep })
                    }
                }
            }
        }

        if (episodes.isEmpty() && tvType == TvType.AnimeMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster
                plot = description
                tags = genres
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            posterUrl = poster
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
        val doc = app.get(data, headers = headers, timeout = 30L).document

        var foundLinks = false

        // ====== Método 1: Extraer URLs de botones de servidor ======
        // Los botones tienen data-url, data-server, data-lang-group
        val serverButtons = doc.select("button.server-btn[data-url]")
        if (serverButtons.isNotEmpty()) {
            serverButtons.amap { btn ->
                val videoUrl = btn.attr("data-url")
                val serverName = btn.attr("data-server")?.ifBlank { "Server" } ?: "Server"

                if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                    foundLinks = processVideoUrl(videoUrl, data, serverName, subtitleCallback, callback) || foundLinks
                }
            }
        }

        // ====== Método 2: Buscar data-url en cualquier elemento ======
        if (!foundLinks) {
            doc.select("[data-url]").amap { el ->
                val videoUrl = el.attr("data-url")
                if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                    foundLinks = processVideoUrl(videoUrl, data, "Server", subtitleCallback, callback) || foundLinks
                }
            }
        }

        // ====== Método 3: Buscar iframe del video ======
        if (!foundLinks) {
            doc.select("iframe").amap { iframe ->
                val src = iframe.attr("src")?.ifBlank { iframe.attr("data-src") }
                if (!src.isNullOrEmpty()) {
                    val fullSrc = resolveUrl(src)
                    if (fullSrc.startsWith("http")) {
                        foundLinks = processVideoUrl(fullSrc, data, "HLS", subtitleCallback, callback) || foundLinks
                    }
                }
            }
        }

        // ====== Método 4: Buscar URLs en scripts ======
        if (!foundLinks) {
            for (script in doc.select("script")) {
                val scriptData = script.data()
                if (scriptData.contains(".m3u8") || scriptData.contains(".mp4")) {
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                    m3u8Regex.find(scriptData)?.value?.let { m3u8Url ->
                        try {
                            generateM3u8("Server", m3u8Url, data).forEach(callback)
                            foundLinks = true
                            return@let
                        } catch (_: Exception) {}
                    }
                    if (!foundLinks) {
                        val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
                        mp4Regex.find(scriptData)?.value?.let { mp4Url ->
                            callback(
                                newExtractorLink(source = "Server", name = "Server", url = mp4Url) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundLinks = true
                        }
                    }
                }
                if (foundLinks) break
            }
        }

        return foundLinks
    }

    /**
     * Procesa una URL de video: intenta loadExtractor primero, luego extracción manual
     */
    private suspend fun processVideoUrl(
        videoUrl: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Intentar loadExtractor (CloudStream tiene extractores integrados para muchos servidores)
        try {
            loadExtractor(videoUrl, referer, subtitleCallback, callback)
            found = true
        } catch (_: Exception) {}

        // Si loadExtractor falló, intentar extracción manual según el servidor
        if (!found) {
            when {
                // zilla-networks: extraer de la página del player
                videoUrl.contains("zilla-networks.com") || videoUrl.contains("player.zilla") -> {
                    found = extractFromPlayerPage(videoUrl, referer, serverName, callback)
                }
                // Dailymotion: usar API de metadata
                videoUrl.contains("dailymotion.com") -> {
                    val videoId = Regex("dailymotion\\.com/(?:embed/)?video/([a-zA-Z0-9]+)").find(videoUrl)?.destructured?.component1()
                    if (!videoId.isNullOrEmpty()) {
                        found = extractDailymotionApi(videoId, referer, serverName, callback)
                    }
                }
                // ok.ru: intentar extraer del HTML del embed
                videoUrl.contains("ok.ru") -> {
                    found = extractOkRu(videoUrl, referer, serverName, callback)
                }
                // streamwish, streamtape, mixdrop, filemoon, etc.: reintentar loadExtractor con diferentes parámetros
                else -> {
                    // Último intento: buscar URLs de video en la página
                    found = extractFromPlayerPage(videoUrl, referer, serverName, callback)
                }
            }
        }

        return found
    }

    /**
     * Extrae URLs de video (m3u8/mp4) de una página de player genérica
     */
    private suspend fun extractFromPlayerPage(
        playerUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val response = app.get(playerUrl, referer = referer, headers = headers, timeout = 15L)
            val text = response.text

            // Buscar m3u8
            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            for (match in m3u8Regex.findAll(text)) {
                try {
                    generateM3u8(serverName, match.value, playerUrl).forEach(callback)
                    found = true
                    return true
                } catch (_: Exception) {}
            }

            // Buscar mp4
            if (!found) {
                val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
                for (match in mp4Regex.findAll(text)) {
                    callback(
                        newExtractorLink(source = serverName, name = serverName, url = match.value) {
                            this.referer = playerUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                    return true
                }
            }

            // Buscar URLs en datos JSON dentro de scripts
            if (!found) {
                val urlInJsonRegex = Regex(""""(https?://[^"]+(?:\.m3u8|\.mp4)[^"]*)"""")
                for (match in urlInJsonRegex.findAll(text)) {
                    val url = match.destructured.component1()
                    if (url.contains(".m3u8")) {
                        try {
                            generateM3u8(serverName, url, playerUrl).forEach(callback)
                            found = true
                            return true
                        } catch (_: Exception) {}
                    } else if (url.contains(".mp4")) {
                        callback(
                            newExtractorLink(source = serverName, name = serverName, url = url) {
                                this.referer = playerUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
        return found
    }

    /**
     * Extracción de Dailymotion usando la API de metadata del player
     * GET https://www.dailymotion.com/player/metadata/video/{videoId}
     */
    private suspend fun extractDailymotionApi(
        videoId: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Método 1: API de metadata del player
        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val response = app.get(
                apiUrl,
                referer = "https://www.dailymotion.com/embed/video/$videoId",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                ),
                timeout = 15L
            )
            val jsonText = response.text

            // Buscar m3u8 en la respuesta JSON
            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""")
            for (match in m3u8Regex.findAll(jsonText)) {
                try {
                    generateM3u8(serverName, match.value, "https://www.dailymotion.com").forEach(callback)
                    found = true
                    return true
                } catch (_: Exception) {}
            }

            // Buscar mp4 en la respuesta JSON
            if (!found) {
                val mp4Regex = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""")
                val mp4Urls = mp4Regex.findAll(jsonText).map { it.value }.distinct().toList()
                for (url in mp4Urls) {
                    val quality = when {
                        url.contains("1080") || url.contains("x1080") -> Qualities.P1080.value
                        url.contains("720") || url.contains("x720") -> Qualities.P720.value
                        url.contains("480") || url.contains("x480") -> Qualities.P480.value
                        url.contains("380") || url.contains("x360") -> Qualities.P360.value
                        url.contains("240") || url.contains("x240") -> Qualities.P240.value
                        else -> Qualities.Unknown.value
                    }
                    callback(
                        newExtractorLink(source = serverName, name = "$serverName ${quality / 1000}p", url = url) {
                            this.referer = "https://www.dailymotion.com"
                            this.quality = quality
                        }
                    )
                    found = true
                }
                if (found) return true
            }
        } catch (_: Exception) {}

        // Método 2: Scrapear la página embed
        if (!found) {
            try {
                val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
                val response = app.get(embedUrl, referer = referer, headers = headers, timeout = 15L)
                val html = response.text

                val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""")
                for (match in m3u8Regex.findAll(html)) {
                    try {
                        generateM3u8(serverName, match.value, embedUrl).forEach(callback)
                        found = true
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
                    found = true
                    return true
                }
            } catch (_: Exception) {}
        }

        return found
    }

    /**
     * Extracción de ok.ru: buscar URL de video en la página embed
     */
    private suspend fun extractOkRu(
        videoUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val response = app.get(videoUrl, referer = referer, headers = headers, timeout = 15L)
            val html = response.text

            // Buscar data-options con JSON que contiene video URLs
            val dataOptionsRegex = Regex("""data-options="([^"]+)"""")
            val dataMatch = dataOptionsRegex.find(html)
            if (dataMatch != null) {
                val optionsJson = dataMatch.destructured.component1()
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                val videoUrlInOptions = Regex("""(https?://[^"]+\.(?:mp4|m3u8)[^"]*)""").find(optionsJson)?.value
                if (!videoUrlInOptions.isNullOrEmpty()) {
                    callback(
                        newExtractorLink(source = serverName, name = serverName, url = videoUrlInOptions) {
                            this.referer = videoUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }

            // Buscar og:video meta tag
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

            // Buscar URLs de video directamente en el HTML
            val videoUrlRegex = Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""")
            for (match in videoUrlRegex.findAll(html)) {
                callback(
                    newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        } catch (_: Exception) {}
        return found
    }
}
