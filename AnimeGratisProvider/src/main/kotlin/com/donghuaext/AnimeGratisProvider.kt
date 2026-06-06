package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == "$mainUrl/"
        val url = if (isHomePage) {
            request.data
        } else {
            if (page > 1) "${request.data}?page=$page" else request.data
        }

        val doc = app.get(url, headers = headers, timeout = 30).document

        val home = if (isHomePage) {
            // Homepage: sección "Nuevos Episodios" / "En Emisión"
            // El sitio usa Astro SSR con Tailwind CSS
            // Los episodios están en #emisionGrid con <a data-home-episode-card>
            val emisionCards = doc.select("a[data-home-episode-card]")
            if (emisionCards.isNotEmpty()) {
                emisionCards.mapNotNull { link ->
                    parseHomeEpisodeCard(link)
                }
            } else {
                // Fallback: buscar en #emisionGrid
                val gridCards = doc.select("#emisionGrid > a[href*=\"episodio\"]")
                if (gridCards.isNotEmpty()) {
                    gridCards.mapNotNull { link ->
                        parseHomeEpisodeCard(link)
                    }
                } else {
                    // Último fallback: cualquier <a> con href a episodio
                    doc.select("a[href*=\"episodio\"]").mapNotNull { link ->
                        parseHomeEpisodeCard(link)
                    }
                }
            }
        } else {
            // Directorio: tarjetas de anime en #animeGrid
            val dirCards = doc.select("#animeGrid div.anime-card")
            if (dirCards.isNotEmpty()) {
                dirCards.mapNotNull {
                    val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
                    val href = it.selectFirst("h3 a")?.attr("href")
                        ?: it.selectFirst("a:first-child")?.attr("href")
                        ?: return@mapNotNull null
                    val poster = it.selectFirst("img")?.attr("src")
                        ?: it.selectFirst("img")?.attr("data-fallback")
                    val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                    newAnimeSearchResponse(title, resolveUrl(href)) {
                        this.posterUrl = resolveUrl(poster ?: "")
                        addDubStatus(dubstat)
                    }
                }
            } else {
                doc.select("div.anime-card").mapNotNull {
                    val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
                    val href = it.selectFirst("h3 a")?.attr("href")
                        ?: it.selectFirst("a:first-child")?.attr("href")
                        ?: return@mapNotNull null
                    val poster = it.selectFirst("img")?.attr("src")
                        ?: it.selectFirst("img")?.attr("data-fallback")
                    val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                    newAnimeSearchResponse(title, resolveUrl(href)) {
                        this.posterUrl = resolveUrl(poster ?: "")
                        addDubStatus(dubstat)
                    }
                }
            }
        }

        val hasNext = if (isHomePage) false
            else doc.select("link[rel=\"next\"]").isNotEmpty()
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    /**
     * Parsea una tarjeta de episodio del homepage
     */
    private fun parseHomeEpisodeCard(link: org.jsoup.nodes.Element): SearchResponse? {
        val href = link.attr("href") ?: return null
        if (!href.contains("episodio")) return null

        val img = link.selectFirst("img")
        val title = img?.attr("alt") ?: link.selectFirst("p.home-anime-title")?.text() ?: link.text()
        val poster = img?.attr("src") ?: img?.attr("data-fallback")
        // Limpiar título: "Nombre Episodio 9" → "Nombre"
        val cleanTitle = title.replace(Regex("\\s*Episodio\\s*\\d+"), "").trim()
        // Convertir URL episodio a URL serie: /anime/slug/episodio-N → /anime/slug-anime
        val seriesUrl = episodeUrlToSeriesUrl(href)
        val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
        val dubstat = if (cleanTitle.contains("Latino") || cleanTitle.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
        return newAnimeSearchResponse(cleanTitle, seriesUrl) {
            this.posterUrl = resolveUrl(poster ?: "")
            addDubStatus(dubstat, epNum)
        }
    }

    /**
     * Convierte URL de episodio a URL de serie
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
        // Búsqueda del lado del cliente: el sitio NO filtra desde el servidor
        val queryLower = query.lowercase(Locale.getDefault())
        val results = ArrayList<SearchResponse>()
        var page = 1
        var maxPages = 5

        while (page <= maxPages) {
            val url = if (page > 1) "$mainUrl/directorio?page=$page" else "$mainUrl/directorio"
            val doc = app.get(url, headers = headers, timeout = 30).document

            val pageResults = doc.select("#animeGrid div.anime-card, div.anime-card").mapNotNull {
                val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
                if (!title.lowercase(Locale.getDefault()).contains(queryLower)) return@mapNotNull null

                val href = it.selectFirst("h3 a")?.attr("href")
                    ?: it.selectFirst("a:first-child")?.attr("href")
                    ?: return@mapNotNull null
                val image = it.selectFirst("img")?.attr("src")
                    ?: it.selectFirst("img")?.attr("data-fallback")
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                    this.posterUrl = resolveUrl(image ?: "")
                    addDubStatus(dubstat)
                }
            }
            results.addAll(pageResults)

            if (results.size >= 20) break

            val hasNext = doc.select("link[rel=\"next\"]").isNotEmpty()
            if (!hasNext) break
            page++
        }

        return results
    }

    // Modelos para parsear JSON-LD (sin anotaciones - nombres de campo coinciden con claves JSON)
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

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 30).document

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
        val description = jsonLdDescription ?: doc.selectFirst("h2:contains(Sinopsis) + p")?.text()
            ?: doc.selectFirst("h2")?.nextElementSibling()?.text() ?: ""
        // Poster: primero JSON-LD, luego img de la sección grid, luego og:image
        val poster = jsonLdImage
            ?: doc.selectFirst("section img[src*=\"cover\"]")?.attr("src")
            ?: doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[data-fallback]")?.attr("data-fallback")
            ?: ""
        val genres = if (jsonLdGenres.isNotEmpty()) jsonLdGenres
            else doc.select("a[href^=\"/directorio/genero/\"]").map { it.text() }

        // Status: buscar en los spans de info
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

        // Buscar episodios: selector principal a[data-episode] dentro de #episodes-grid
        val epCards = doc.select("#episodes-grid a[data-episode]")
        if (epCards.isNotEmpty()) {
            epCards.amap { epCard ->
                val href = epCard.attr("href")
                val epNum = epCard.attr("data-episode")?.toIntOrNull()
                    ?: Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                if (href.isNotEmpty()) {
                    episodes.add(
                        newEpisode(resolveUrl(href)) {
                            this.episode = epNum
                        }
                    )
                }
            }
        } else {
            // Fallback: buscar .episode-card sin data-episode
            val epCards2 = doc.select("#episodes-grid a.episode-card")
            if (epCards2.isNotEmpty()) {
                epCards2.amap { epCard ->
                    val href = epCard.attr("href")
                    val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                    if (href.isNotEmpty()) {
                        episodes.add(
                            newEpisode(resolveUrl(href)) {
                                this.episode = epNum
                            }
                        )
                    }
                }
            } else {
                // Último fallback: cualquier enlace a episodio
                doc.select("a[href*=\"episodio\"]").amap { epCard ->
                    val href = epCard.attr("href")
                    val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                    if (href.isNotEmpty()) {
                        episodes.add(
                            newEpisode(resolveUrl(href)) {
                                this.episode = epNum
                            }
                        )
                    }
                }
            }
        }

        // Si hay pocos episodios pero el JSON-LD indica más, generar las URLs faltantes
        if (episodes.size < (jsonLdEpCount ?: 0)) {
            val totalEps = jsonLdEpCount ?: episodes.size
            if (totalEps > episodes.size) {
                val slugWithAnime = url.substringAfter("/anime/")
                val slug = slugWithAnime.removeSuffix("-anime").removeSuffix("/")

                for (ep in 1..totalEps) {
                    val epExists = episodes.any { it.episode == ep }
                    if (!epExists) {
                        val epUrl = "$mainUrl/anime/$slug/episodio-$ep"
                        episodes.add(
                            newEpisode(epUrl) {
                                this.episode = ep
                            }
                        )
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
        val doc = app.get(data, headers = headers, timeout = 30).document

        var foundLinks = false

        // Método 1: extraer URLs de los botones de servidor (data-url)
        doc.select("button.server-btn[data-url]").amap { btn ->
            val videoUrl = btn.attr("data-url")
            val serverName = btn.attr("data-server") ?: "Server"

            if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                try {
                    when {
                        // zilla-networks: intentar extraer m3u8/mp4 directamente
                        videoUrl.contains("zilla-networks.com") || videoUrl.contains("player.zilla") -> {
                            try {
                                val response = app.get(videoUrl, referer = data, headers = headers, timeout = 15)
                                val text = response.text
                                if (text.contains(".m3u8") || text.contains("#EXTM3U")) {
                                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                                    val m3u8Url = m3u8Regex.find(text)?.value
                                    if (!m3u8Url.isNullOrEmpty()) {
                                        generateM3u8(serverName, m3u8Url, data).forEach(callback)
                                        foundLinks = true
                                    }
                                } else if (text.contains(".mp4")) {
                                    val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
                                    val mp4Url = mp4Regex.find(text)?.value
                                    if (!mp4Url.isNullOrEmpty()) {
                                        callback(
                                            newExtractorLink(source = serverName, name = serverName, url = mp4Url) {
                                                this.referer = data
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                        foundLinks = true
                                    }
                                }
                            } catch (_: Exception) {
                                try {
                                    loadExtractor(videoUrl, data, subtitleCallback, callback)
                                    foundLinks = true
                                } catch (_: Exception) {}
                            }
                        }
                        else -> {
                            try {
                                loadExtractor(videoUrl, data, subtitleCallback, callback)
                                foundLinks = true
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Método 2: buscar data-url en cualquier elemento (no solo buttons)
        if (!foundLinks) {
            doc.select("[data-url]").amap { el ->
                val videoUrl = el.attr("data-url")
                if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                    try {
                        loadExtractor(videoUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    } catch (_: Exception) {}
                }
            }
        }

        // Método 3: buscar iframe del video
        if (!foundLinks) {
            doc.select("iframe").amap { iframe ->
                val src = iframe.attr("src") ?: iframe.attr("data-src")
                if (src.isNotEmpty()) {
                    val fullSrc = resolveUrl(src)
                    if (fullSrc.startsWith("http")) {
                        try {
                            when {
                                fullSrc.contains("zilla-networks.com") || fullSrc.contains("player.zilla") -> {
                                    try {
                                        val response = app.get(fullSrc, referer = data, headers = headers, timeout = 15)
                                        val text = response.text
                                        if (text.contains(".m3u8") || text.contains("#EXTM3U")) {
                                            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                                            val m3u8Url = m3u8Regex.find(text)?.value
                                            if (!m3u8Url.isNullOrEmpty()) {
                                                generateM3u8("HLS", m3u8Url, data).forEach(callback)
                                                foundLinks = true
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                                else -> {
                                    try {
                                        loadExtractor(fullSrc, data, subtitleCallback, callback)
                                        foundLinks = true
                                    } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // Método 4: buscar URL de video directamente en scripts de la página
        if (!foundLinks) {
            for (script in doc.select("script")) {
                val scriptData = script.data()
                if (scriptData.contains("m3u8") || scriptData.contains("mp4")) {
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                    m3u8Regex.find(scriptData)?.value?.let { url ->
                        try {
                            generateM3u8("Server", url, data).forEach(callback)
                            foundLinks = true
                        } catch (_: Exception) {}
                    }
                    if (!foundLinks) {
                        val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
                        mp4Regex.find(scriptData)?.value?.let { url ->
                            callback(
                                newExtractorLink(source = "Server", name = "Server", url = url) {
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
}
