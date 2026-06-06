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
        "$mainUrl/donghua" to "Donghuas",
        "$mainUrl/directorio" to "Directorio Anime",
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

    // NO incluir "Accept-Encoding" — NiceHttp lo gestiona automáticamente.
    // Si forzamos "br" (brotli) y no lo soporta, el HTML llega ilegible.
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
    )

    // ========== Helper para atributos de imagen ==========
    // Jsoup .attr() retorna "" (no null) si el atributo no existe.
    // Por eso necesitamos .isNotBlank() para que la cadena de fallback funcione.
    // NOTA: Solo una versión con receptor nullable — Kotlin/JVM no permite
    // dos extension functions con receptor Element y Element? (misma firma JVM).

    private fun org.jsoup.nodes.Element?.bestImageUrl(): String {
        if (this == null) return ""
        return listOf("data-fb2", "data-fallback", "src")
            .map { attr -> this.attr(attr).trim() }
            .firstOrNull { it.isNotBlank() } ?: ""
    }

    // ========== MODELOS PARA #ssr-init (directorio) ==========
    data class SsrInit(
        val animes: List<SsrAnime>? = null,
        val total: Int? = null,
        val totalPages: Int? = null,
        val page: Int? = null,
    )

    data class SsrAnime(
        val t: String? = null,
        val sl: String? = null,
        val sy: String? = null,
        val sc: Double? = null,
        val st: String? = null,
        val g: List<SsrGenre>? = null,
        val ty: String? = null,
        val im: String? = null,
        val fb: String? = null,
        val fb2: String? = null,
        val yr: String? = null,
        val isMovie: Boolean? = null,
    )

    data class SsrGenre(
        val n: String? = null,
        val s: String? = null,
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
    )

    // ========== getMainPage ==========
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == "$mainUrl/"
        val isDonghua = request.data == "$mainUrl/donghua"
        val url = if (page > 1) "${request.data}?page=$page" else request.data

        val doc = app.get(url, headers = headers, timeout = 30L).document

        val home = when {
            isHomePage -> {
                // Episodios recientes: a[data-home-episode-card]
                val emisionCards = doc.select("a[data-home-episode-card]")
                if (emisionCards.isNotEmpty()) {
                    emisionCards.mapNotNull { parseHomeEpisodeCard(it) }
                } else {
                    doc.select("a[href*=\"episodio\"]").mapNotNull { parseHomeEpisodeCard(it) }
                }
            }
            isDonghua -> {
                // Donghuas: scrapear tarjetas a[href^="/donghua/"]
                parseDonghuaCards(doc)
            }
            else -> {
                // Directorio: preferir #ssr-init JSON
                val ssrInit = doc.selectFirst("script#ssr-init")?.data()
                if (!ssrInit.isNullOrEmpty()) {
                    try {
                        val ssr = parseJson<SsrInit>(ssrInit)
                        ssr.animes?.mapNotNull { anime ->
                            val title = anime.t ?: return@mapNotNull null
                            val slug = anime.sl ?: return@mapNotNull null
                            val poster = anime.fb2?.takeIf { it.isNotBlank() }
                                ?: anime.fb?.takeIf { it.isNotBlank() }
                                ?: anime.im?.takeIf { it.isNotBlank() }
                                ?: ""
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
        }

        val hasNext = doc.select("link[rel=\"next\"]").isNotEmpty() || doc.select("a[href*=\"page=${page + 1}\"]").isNotEmpty()
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun parseDonghuaCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("a[href^=\"/donghua/\"]").mapNotNull { link ->
            val href = link.attr("href")
            // Evitar links a episodios o URLs extrañas
            if (href.contains("episodio")) return@mapNotNull null
            val title = link.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = link.selectFirst("img").bestImageUrl()
            val dubstat = DubStatus.Subbed
            newAnimeSearchResponse(title, resolveUrl(href)) {
                this.posterUrl = resolveUrl(poster)
                addDubStatus(dubstat)
            }
        }
    }

    private fun parseDirectorioHtml(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("div.anime-card").mapNotNull {
            val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("h3 a")?.attr("href")
                ?: it.selectFirst("a:first-child")?.attr("href")
                ?: return@mapNotNull null
            val poster = it.selectFirst("img").bestImageUrl()
            val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
            newAnimeSearchResponse(title, resolveUrl(href)) {
                this.posterUrl = resolveUrl(poster)
                addDubStatus(dubstat)
            }
        }
    }

    private fun parseHomeEpisodeCard(link: org.jsoup.nodes.Element): SearchResponse? {
        val href = link.attr("href") ?: return null
        if (!href.contains("episodio")) return null

        val img = link.selectFirst("img")
        val title = img?.attr("alt")?.trim()
            ?: link.selectFirst("p.home-anime-title")?.text()?.trim()
            ?: link.text()
        val poster = img.bestImageUrl()
        val cleanTitle = title.replace(Regex("\\s*Episodio\\s*\\d+"), "").trim()
        val seriesUrl = episodeUrlToSeriesUrl(href)
        val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
        val dubstat = if (cleanTitle.contains("Latino") || cleanTitle.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
        return newAnimeSearchResponse(cleanTitle, seriesUrl) {
            this.posterUrl = resolveUrl(poster)
            addDubStatus(dubstat, epNum)
        }
    }

    /**
     * /anime/{slug}/episodio-{N} → /anime/{slug}-anime
     * /donghua/{slug}/episodio-{N} → /donghua/{slug}
     */
    private fun episodeUrlToSeriesUrl(epHref: String): String {
        val fullUrl = resolveUrl(epHref)
        // Anime: /anime/{slug}/episodio-{N} → /anime/{slug}-anime
        val animeRegex = Regex("/anime/([^/]+)/episodio-\\d+")
        val animeMatch = animeRegex.find(fullUrl)
        if (animeMatch != null) {
            val slug = animeMatch.destructured.component1()
            return "$mainUrl/anime/$slug-anime"
        }
        // Donghua: /donghua/{slug}/episodio-{N} → /donghua/{slug}
        val donghuaRegex = Regex("/donghua/([^/]+)/episodio-\\d+")
        val donghuaMatch = donghuaRegex.find(fullUrl)
        if (donghuaMatch != null) {
            val slug = donghuaMatch.destructured.component1()
            return "$mainUrl/donghua/$slug"
        }
        return fullUrl
    }

    // ========== search ==========
    override suspend fun search(query: String): List<SearchResponse> {
        val queryLower = query.lowercase(Locale.getDefault())
        val results = ArrayList<SearchResponse>()

        // 1) Buscar en directorio (anime) usando #ssr-init
        try {
            val doc = app.get("$mainUrl/directorio", headers = headers, timeout = 30L).document
            val ssrInit = doc.selectFirst("script#ssr-init")?.data()
            if (!ssrInit.isNullOrEmpty()) {
                val ssr = parseJson<SsrInit>(ssrInit)
                ssr.animes?.filter { anime ->
                    anime.t?.lowercase(Locale.getDefault())?.contains(queryLower) == true
                }?.mapNotNull { anime ->
                    val title = anime.t ?: return@mapNotNull null
                    val slug = anime.sl ?: return@mapNotNull null
                    val poster = anime.fb2?.takeIf { it.isNotBlank() }
                        ?: anime.fb?.takeIf { it.isNotBlank() }
                        ?: anime.im?.takeIf { it.isNotBlank() }
                        ?: ""
                    val href = "$mainUrl/anime/$slug-anime"
                    val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                    results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = resolveUrl(poster)
                        addDubStatus(dubstat)
                    })
                }
            }
        } catch (_: Exception) {}

        // 2) Buscar en donghua (búsqueda server-side: /donghua?q=...)
        try {
            val donghuaUrl = "$mainUrl/donghua?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val doc = app.get(donghuaUrl, headers = headers, timeout = 30L).document
            val donghuaResults = doc.select("a[href^=\"/donghua/\"]").mapNotNull { link ->
                val href = link.attr("href")
                if (href.contains("episodio")) return@mapNotNull null
                val title = link.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                val poster = link.selectFirst("img").bestImageUrl()
                newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                    this.posterUrl = resolveUrl(poster)
                    addDubStatus(DubStatus.Subbed)
                }
            }
            results.addAll(donghuaResults)
        } catch (_: Exception) {}

        // 3) Fallback: buscar en HTML del directorio si ssr-init no funcionó
        if (results.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/directorio", headers = headers, timeout = 30L).document
                val pageResults = doc.select("div.anime-card").mapNotNull {
                    val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
                    if (!title.lowercase(Locale.getDefault()).contains(queryLower)) return@mapNotNull null
                    val href = it.selectFirst("h3 a")?.attr("href")
                        ?: it.selectFirst("a:first-child")?.attr("href")
                        ?: return@mapNotNull null
                    val image = it.selectFirst("img").bestImageUrl()
                    val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                    newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                        this.posterUrl = resolveUrl(image)
                        addDubStatus(dubstat)
                    }
                }
                results.addAll(pageResults)
            } catch (_: Exception) {}
        }

        return results.distinctBy { it.url }.take(30)
    }

    // ========== load ==========
    override suspend fun load(url: String): LoadResponse {
        val isDonghua = url.contains("/donghua/")
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
        val poster = jsonLdImage
            ?: doc.selectFirst("img[data-fb2]")?.attr("data-fb2")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[data-fallback]")?.attr("data-fallback")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[src*=\"cdn.animegratis\"]")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: ""
        val genres = if (jsonLdGenres.isNotEmpty()) jsonLdGenres
            else doc.select("a[href^=\"/directorio/genero/\"], a[href*=\"/genero/\"]").map { it.text() }

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

        if (isDonghua) {
            // Donghua: episodios en a[data-episode] o a[href*="episodio"]
            val epCards = doc.select("#dh-episodes-grid a[data-episode], a[data-episode]")
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
                doc.select("a[href*=\"episodio\"]").amap { epCard ->
                    val href = epCard.attr("href")
                    val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                    if (href.isNotEmpty()) {
                        episodes.add(newEpisode(resolveUrl(href)) { this.episode = epNum })
                    }
                }
            }
        } else {
            // Anime: episodios en a.episode-card[data-episode]
            val epCards = doc.select("#episodes-grid a.episode-card[data-episode], a.episode-card[data-episode]")
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
                doc.select("a[href*=\"episodio\"]").amap { epCard ->
                    val href = epCard.attr("href")
                    val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                    if (href.isNotEmpty()) {
                        episodes.add(newEpisode(resolveUrl(href)) { this.episode = epNum })
                    }
                }
            }
        }

        // Si JSON-LD indica más episodios de los encontrados, generar URLs
        if (!isDonghua && episodes.size < (jsonLdEpCount ?: 0)) {
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

    // ========== loadLinks ==========
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers, timeout = 30L).document
        var foundLinks = false

        // ====== Método 1: Botones de servidor (data-url) ======
        val serverButtons = doc.select("button.server-btn[data-url], button.server-tab[data-url]")
        if (serverButtons.isNotEmpty()) {
            serverButtons.amap { btn ->
                val videoUrl = btn.attr("data-url").trim()
                val serverName = btn.text().trim().ifBlank { "Server" }

                if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                    foundLinks = processVideoUrl(videoUrl, data, serverName, subtitleCallback, callback) || foundLinks
                }
            }
        }

        // ====== Método 2: data-url en cualquier elemento ======
        if (!foundLinks) {
            doc.select("[data-url]").amap { el ->
                val videoUrl = el.attr("data-url").trim()
                if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                    foundLinks = processVideoUrl(videoUrl, data, "Server", subtitleCallback, callback) || foundLinks
                }
            }
        }

        // ====== Método 3: Iframe del video ======
        if (!foundLinks) {
            doc.select("iframe").amap { iframe ->
                val src = listOf("src", "data-src")
                    .map { iframe.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() }
                if (src != null) {
                    val fullSrc = resolveUrl(src)
                    if (fullSrc.startsWith("http")) {
                        foundLinks = processVideoUrl(fullSrc, data, "Player", subtitleCallback, callback) || foundLinks
                    }
                }
            }
        }

        // ====== Método 4: URLs en scripts ======
        if (!foundLinks) {
            for (script in doc.select("script")) {
                val scriptData = script.data()
                if (scriptData.contains(".m3u8") || scriptData.contains(".mp4")) {
                    Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(scriptData)?.value?.let { url ->
                        try { generateM3u8("Server", url, data).forEach(callback); foundLinks = true } catch (_: Exception) {}
                    }
                    if (!foundLinks) {
                        Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").find(scriptData)?.value?.let { url ->
                            callback(newExtractorLink(source = "Server", name = "Server", url = url) {
                                this.referer = data; this.quality = Qualities.Unknown.value
                            })
                            foundLinks = true
                        }
                    }
                }
                if (foundLinks) break
            }
        }

        return foundLinks
    }

    private suspend fun processVideoUrl(
        videoUrl: String, referer: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try { loadExtractor(videoUrl, referer, subtitleCallback, callback); found = true } catch (_: Exception) {}
        if (!found) {
            when {
                videoUrl.contains("zilla-networks.com") || videoUrl.contains("player.zilla") ->
                    found = extractFromPlayerPage(videoUrl, referer, serverName, callback)
                videoUrl.contains("dailymotion.com") -> {
                    val videoId = Regex("dailymotion\\.com/(?:embed/)?video/([a-zA-Z0-9]+)")
                        .find(videoUrl)?.destructured?.component1()
                    if (!videoId.isNullOrEmpty())
                        found = extractDailymotionApi(videoId, referer, serverName, callback)
                }
                videoUrl.contains("ok.ru") ->
                    found = extractOkRu(videoUrl, referer, serverName, callback)
                else ->
                    found = extractFromPlayerPage(videoUrl, referer, serverName, callback)
            }
        }
        return found
    }

    private suspend fun extractFromPlayerPage(
        playerUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val text = app.get(playerUrl, referer = referer, headers = headers, timeout = 15L).text
            for (regex in listOf(
                Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
                Regex(""""(https?://[^"]+(?:\.m3u8|\.mp4)[^"]*)"""")
            )) {
                for (match in regex.findAll(text)) {
                    val url = match.destructured.component1()
                    if (url.contains(".m3u8")) {
                        try { generateM3u8(serverName, url, playerUrl).forEach(callback); return true } catch (_: Exception) {}
                    } else {
                        callback(newExtractorLink(source = serverName, name = serverName, url = url) {
                            this.referer = playerUrl; this.quality = Qualities.Unknown.value
                        })
                        return true
                    }
                }
            }
            Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").find(text)?.value?.let { url ->
                callback(newExtractorLink(source = serverName, name = serverName, url = url) {
                    this.referer = playerUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractDailymotionApi(
        videoId: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // API de metadata del player
        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val jsonText = app.get(apiUrl, referer = "https://www.dailymotion.com/embed/video/$videoId",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"), timeout = 15L).text
            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(jsonText)) {
                try { generateM3u8(serverName, match.value, "https://www.dailymotion.com").forEach(callback); return true } catch (_: Exception) {}
            }
            val mp4Urls = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(jsonText).map { it.value }.distinct().toList()
            if (mp4Urls.isNotEmpty()) {
                for (url in mp4Urls) {
                    val quality = when {
                        url.contains("1080") -> Qualities.P1080.value; url.contains("720") -> Qualities.P720.value
                        url.contains("480") -> Qualities.P480.value; url.contains("380") || url.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    callback(newExtractorLink(source = serverName, name = "$serverName ${quality/1000}p", url = url) {
                        this.referer = "https://www.dailymotion.com"; this.quality = quality
                    })
                }
                return true
            }
        } catch (_: Exception) {}
        // Fallback: scrape embed page
        try {
            val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
            val html = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(html)) {
                try { generateM3u8(serverName, match.value, embedUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (match in Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(html)) {
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
            Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""").find(html)?.let { match ->
                callback(newExtractorLink(source = serverName, name = serverName, url = match.destructured.component1()) {
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
}
