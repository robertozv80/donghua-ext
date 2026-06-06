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

    // NO incluir Accept-Encoding — NiceHttp lo gestiona solo.
    // Si forzamos "br" (brotli), el HTML llega ilegible.
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
    )

    // Helper para obtener la mejor URL de imagen de un Element?
    // Prioriza src (siempre funciona en el navegador) sobre data-fallback/data-fb2
    private fun org.jsoup.nodes.Element?.bestImageUrl(): String {
        if (this == null) return ""
        // Probar múltiples atributos de imagen
        val candidates = mutableListOf<String>()
        for (attr in listOf("src", "data-src", "data-fallback", "data-fb2")) {
            val value = this.attr(attr).trim()
            if (value.isNotBlank() && value != "data:,") {
                candidates.add(value)
            }
        }
        // Preferir URLs CDN válidas sobre placeholders o data URIs
        return candidates.firstOrNull { it.startsWith("http") } ?: candidates.firstOrNull() ?: ""
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

    data class TvSeriesJsonLd(
        val name: String? = null,
        val description: String? = null,
        val image: Any? = null,
        val genre: Any? = null,
        val numberOfEpisodes: Int? = null,
    )

    // ========== getMainPage ==========
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == "$mainUrl/"
        val isDonghua = request.data == "$mainUrl/donghua"
        val url = if (page > 1) "${request.data}?page=$page" else request.data

        val doc = app.get(url, headers = headers, timeout = 30L).document

        val home = when {
            isHomePage -> {
                parseHomePageCards(doc)
            }
            isDonghua -> {
                parseDonghuaCards(doc)
            }
            else -> {
                parseDirectorioCards(doc)
            }
        }

        val hasNext = doc.select("link[rel=\"next\"]").isNotEmpty() || doc.select("a[href*=\"page=${page + 1}\"]").isNotEmpty()
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun parseHomePageCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()

        // Método 1: Buscar a[data-home-episode-card]
        val emisionCards = doc.select("a[data-home-episode-card]")
        if (emisionCards.isNotEmpty()) {
            for (link in emisionCards) {
                val parsed = parseHomeEpisodeCard(link)
                if (parsed != null) results.add(parsed)
            }
        }

        // Método 2: Fallback con selectores más amplios
        if (results.isEmpty()) {
            for (link in doc.select("a[href*=\"episodio\"]")) {
                val parsed = parseHomeEpisodeCard(link)
                if (parsed != null) results.add(parsed)
            }
        }

        // Método 3: Buscar por estructura genérica de cards en la página principal
        if (results.isEmpty()) {
            for (card in doc.select("article, div.card, div.anime-card, [class*=\"episode\"], [class*=\"card\"]")) {
                val link = if (card.tagName() == "a" && card.attr("href").contains("episodio")) card else card.selectFirst("a[href*=\"episodio\"]") ?: continue
                val parsed = parseHomeEpisodeCard(link)
                if (parsed != null) results.add(parsed)
            }
        }

        return results
    }

    private fun parseDonghuaCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("a[href^=\"/donghua/\"]").mapNotNull { link ->
            val href = link.attr("href")
            if (href.contains("episodio")) return@mapNotNull null
            val title = link.selectFirst("h3")?.text()?.trim()
                ?: link.selectFirst("h2")?.text()?.trim()
                ?: link.selectFirst("[class*=\"title\"]")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = link.selectFirst("img").bestImageUrl()
            newAnimeSearchResponse(title, resolveUrl(href)) {
                this.posterUrl = resolveUrl(poster)
                addDubStatus(DubStatus.Subbed)
            }
        }
    }

    private fun parseDirectorioCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        // Método 1: Intentar #ssr-init JSON
        val ssrInit = doc.selectFirst("script#ssr-init")?.data()
        if (!ssrInit.isNullOrEmpty()) {
            try {
                val ssr = parseJson<SsrInit>(ssrInit)
                val results = ssr.animes?.mapNotNull { anime ->
                    val title = anime.t ?: return@mapNotNull null
                    val slug = anime.sl ?: return@mapNotNull null
                    val poster = extractBestImageUrl(anime.im, anime.fb, anime.fb2)
                    val href = "$mainUrl/anime/$slug"
                    val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                    newAnimeSearchResponse(title, href) {
                        this.posterUrl = poster
                        addDubStatus(dubstat)
                    }
                } ?: emptyList()
                if (results.isNotEmpty()) return results
            } catch (_: Exception) {}
        }

        // Método 2: Fallback HTML
        return parseDirectorioHtml(doc)
    }

    /**
     * Extrae la mejor URL de imagen de los campos ssr-init.
     * Las URLs ya son absolutas (https://cdn.animegratis.net/...),
     * así que NO se pasan por resolveUrl() para evitar duplicación.
     */
    private fun extractBestImageUrl(im: String?, fb: String?, fb2: String?): String {
        // Priorizar im (webp) sobre fb (webp fallback) sobre fb2 (jpg)
        for (url in listOf(im, fb, fb2)) {
            if (url.isNullOrBlank()) continue
            val trimmed = url.trim()
            if (trimmed.startsWith("http")) return trimmed
        }
        return ""
    }

    private fun parseDirectorioHtml(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()

        // Selector amplio para tarjetas de anime
        val selectors = listOf(
            "div.anime-card",
            "article.anime-card",
            "[class*=\"anime-card\"]",
            "[class*=\"anime-item\"]",
        )

        for (selector in selectors) {
            val cards = doc.select(selector)
            if (cards.isNotEmpty()) {
                for (card in cards) {
                    val titleEl = card.selectFirst("h3 a") ?: card.selectFirst("a:first-child") ?: continue
                    val title = titleEl.text().trim()
                    if (title.isBlank()) continue
                    val href = titleEl.attr("href").takeIf { it.isNotBlank() } ?: continue
                    val poster = card.selectFirst("img").bestImageUrl()
                    val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                    results.add(newAnimeSearchResponse(title, resolveUrl(href)) {
                        this.posterUrl = resolveUrl(poster)
                        addDubStatus(dubstat)
                    })
                }
                if (results.isNotEmpty()) return results
            }
        }

        // Fallback final: buscar todos los links a /anime/ con img
        for (link in doc.select("a[href*=\"/anime/\"]")) {
            val href = link.attr("href")
            if (href.contains("episodio")) continue
            val title = link.selectFirst("h3, h2, [class*=\"title\"]")?.text()?.trim() ?: continue
            val poster = link.selectFirst("img").bestImageUrl()
            val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
            results.add(newAnimeSearchResponse(title, resolveUrl(href)) {
                this.posterUrl = resolveUrl(poster)
                addDubStatus(dubstat)
            })
        }

        return results
    }

    private fun parseHomeEpisodeCard(link: org.jsoup.nodes.Element): SearchResponse? {
        val href = link.attr("href")
        if (href.isEmpty() || !href.contains("episodio")) return null

        // Buscar imagen dentro del link con múltiples estrategias
        val img = link.selectFirst("img")
        val title = img?.attr("alt")?.trim()
            ?: link.selectFirst("p.home-anime-title, [class*=\"title\"], [class*=\"name\"]")?.text()?.trim()
            ?: link.ownText().trim().ifBlank { null }
            ?: return null
        val poster = img.bestImageUrl()
        val cleanTitle = title.replace(Regex("\\s*Episodio\\s*\\d+"), "").trim()
        if (cleanTitle.isBlank()) return null
        val seriesUrl = episodeUrlToSeriesUrl(href)
        val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
        val dubstat = if (cleanTitle.contains("Latino") || cleanTitle.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
        return newAnimeSearchResponse(cleanTitle, seriesUrl) {
            this.posterUrl = resolveUrl(poster)
            addDubStatus(dubstat, epNum)
        }
    }

    private fun episodeUrlToSeriesUrl(epHref: String): String {
        val fullUrl = resolveUrl(epHref)
        val animeMatch = Regex("/anime/([^/]+)/episodio-\\d+").find(fullUrl)
        if (animeMatch != null) {
            return "$mainUrl/anime/${animeMatch.destructured.component1()}"
        }
        val donghuaMatch = Regex("/donghua/([^/]+)/episodio-\\d+").find(fullUrl)
        if (donghuaMatch != null) {
            return "$mainUrl/donghua/${donghuaMatch.destructured.component1()}"
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
                    val poster = extractBestImageUrl(anime.im, anime.fb, anime.fb2)
                    val href = "$mainUrl/anime/$slug"
                    results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = poster
                        addDubStatus(if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed)
                    })
                }
            }
        } catch (_: Exception) {}

        // 2) Buscar en donghua
        try {
            val donghuaUrl = "$mainUrl/donghua?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val doc = app.get(donghuaUrl, headers = headers, timeout = 30L).document
            doc.select("a[href^=\"/donghua/\"]").mapNotNull { link ->
                val href = link.attr("href")
                if (href.contains("episodio")) return@mapNotNull null
                val title = link.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                val poster = link.selectFirst("img").bestImageUrl()
                results.add(newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                    this.posterUrl = resolveUrl(poster)
                    addDubStatus(DubStatus.Subbed)
                })
            }
        } catch (_: Exception) {}

        // 3) Fallback HTML si ssr-init no funcionó
        if (results.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/directorio", headers = headers, timeout = 30L).document
                doc.select("div.anime-card, [class*=\"anime-card\"], [class*=\"anime-item\"]").mapNotNull {
                    val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
                    if (!title.lowercase(Locale.getDefault()).contains(queryLower)) return@mapNotNull null
                    val href = it.selectFirst("h3 a")?.attr("href") ?: return@mapNotNull null
                    val image = it.selectFirst("img").bestImageUrl()
                    results.add(newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                        this.posterUrl = resolveUrl(image)
                        addDubStatus(if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed)
                    })
                }
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
                    // NO usar JSON-LD image si es /api/og/ (generada, no funciona como poster)
                    val imgVal = jsonLd.image
                    if (imgVal is String && !imgVal.contains("/api/og/")) {
                        jsonLdImage = imgVal
                    }
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
            ?: doc.select("h2").amap { if (it.text().contains("Sinopsis", true)) it.nextElementSibling()?.text() else null }.firstOrNull()
            ?: ""

        // Poster: Priorizar imágenes CDN reales sobre JSON-LD /api/og/ y og:image
        val poster = findBestPosterUrl(doc, jsonLdImage, isDonghua)

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
            doc.select("#dh-episodes-grid a[data-episode], a[data-episode]").amap { epCard ->
                val href = epCard.attr("href")
                val epNum = epCard.attr("data-episode")?.toIntOrNull()
                    ?: Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                if (href.isNotEmpty()) episodes.add(newEpisode(resolveUrl(href)) { this.episode = epNum })
            }
            if (episodes.isEmpty()) {
                doc.select("a[href*=\"episodio\"]").amap { epCard ->
                    val href = epCard.attr("href")
                    val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                    if (href.isNotEmpty()) episodes.add(newEpisode(resolveUrl(href)) { this.episode = epNum })
                }
            }
        } else {
            doc.select("#episodes-grid a.episode-card[data-episode], a.episode-card[data-episode]").amap { epCard ->
                val href = epCard.attr("href")
                val epNum = epCard.attr("data-episode")?.toIntOrNull()
                    ?: Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                if (href.isNotEmpty()) episodes.add(newEpisode(resolveUrl(href)) { this.episode = epNum })
            }
            if (episodes.isEmpty()) {
                doc.select("a[href*=\"episodio\"]").amap { epCard ->
                    val href = epCard.attr("href")
                    val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                    if (href.isNotEmpty()) episodes.add(newEpisode(resolveUrl(href)) { this.episode = epNum })
                }
            }
        }

        if (!isDonghua && episodes.size < (jsonLdEpCount ?: 0)) {
            val totalEps = jsonLdEpCount ?: episodes.size
            if (totalEps > episodes.size) {
                val slug = url.substringAfter("/anime/").removeSuffix("-anime").removeSuffix("/")
                for (ep in 1..totalEps) {
                    if (!episodes.any { it.episode == ep }) {
                        episodes.add(newEpisode("$mainUrl/anime/$slug/episodio-$ep") { this.episode = ep })
                    }
                }
            }
        }

        if (episodes.isEmpty() && tvType == TvType.AnimeMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster; plot = description; tags = genres
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
            showStatus = status; plot = description; tags = genres
        }
    }

    /**
     * Busca la mejor URL de poster para la página de detalle.
     * Prioriza imágenes CDN reales sobre imágenes generadas (/api/og/)
     * que CloudStream no puede cargar correctamente.
     */
    private fun findBestPosterUrl(
        doc: org.jsoup.nodes.Document,
        jsonLdImage: String?,
        isDonghua: Boolean
    ): String {
        // 1) Buscar imagen CDN en el HTML (SIEMPRE funciona)
        val cdnSelectors = listOf(
            "img[src*=\"cdn.animegratis\"]",
            "img[data-fallback*=\"cdn.animegratis\"]",
            "img[data-fb2*=\"cdn.animegratis\"]",
        )
        for (selector in cdnSelectors) {
            val img = doc.selectFirst(selector)
            if (img != null) {
                val url = img.bestImageUrl()
                if (url.startsWith("http")) return url
            }
        }

        // 2) Buscar img con data-fallback que no sea CDN
        val fallbackImg = doc.selectFirst("img[data-fallback]")
        if (fallbackImg != null) {
            val url = fallbackImg.attr("data-fallback").trim()
            if (url.startsWith("http")) return resolveUrl(url)
        }

        // 3) JSON-LD image (solo si NO es /api/og/)
        if (jsonLdImage != null && jsonLdImage.startsWith("http")) {
            return jsonLdImage
        }
        if (jsonLdImage != null && jsonLdImage.startsWith("/")) {
            val resolved = resolveUrl(jsonLdImage)
            if (!resolved.contains("/api/og/")) return resolved
        }

        // 4) og:image (a menudo es /api/og/ también, pero es el último recurso)
        val ogImage = doc.selectFirst("head meta[property=og:image]")?.attr("content")
        if (!ogImage.isNullOrBlank()) return ogImage

        return ""
    }

    // ========== loadLinks ==========
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers, timeout = 30L).document
        var foundLinks = false

        // Método 1: Botones de servidor (data-url)
        doc.select("button.server-btn[data-url], button.server-tab[data-url]").amap { btn ->
            val videoUrl = btn.attr("data-url").trim()
            val serverName = btn.text().trim().ifBlank { "Server" }
            if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                foundLinks = processVideoUrl(videoUrl, data, serverName, subtitleCallback, callback) || foundLinks
            }
        }

        // Método 2: data-url en cualquier elemento
        if (!foundLinks) {
            doc.select("[data-url]").amap { el ->
                val videoUrl = el.attr("data-url").trim()
                if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                    foundLinks = processVideoUrl(videoUrl, data, "Server", subtitleCallback, callback) || foundLinks
                }
            }
        }

        // Método 3: Iframe
        if (!foundLinks) {
            doc.select("iframe").amap { iframe ->
                val src = listOf("src", "data-src").map { iframe.attr(it).trim() }.firstOrNull { it.isNotBlank() }
                if (src != null) {
                    val fullSrc = resolveUrl(src)
                    if (fullSrc.startsWith("http")) {
                        foundLinks = processVideoUrl(fullSrc, data, "Player", subtitleCallback, callback) || foundLinks
                    }
                }
            }
        }

        // Método 4: URLs en scripts
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
                    found = extractFromPage(videoUrl, referer, serverName, callback)
                videoUrl.contains("dailymotion.com") -> {
                    val videoId = Regex("dailymotion\\.com/(?:embed/)?video/([a-zA-Z0-9]+)")
                        .find(videoUrl)?.destructured?.component1()
                    if (!videoId.isNullOrEmpty()) found = extractDailymotionApi(videoId, referer, serverName, callback)
                }
                videoUrl.contains("ok.ru") -> found = extractOkRu(videoUrl, referer, serverName, callback)
                else -> found = extractFromPage(videoUrl, referer, serverName, callback)
            }
        }
        return found
    }

    private suspend fun extractFromPage(playerUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val text = app.get(playerUrl, referer = referer, headers = headers, timeout = 15L).text
            for (match in Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").findAll(text)) {
                try { generateM3u8(serverName, match.value, playerUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (match in Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").findAll(text)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                    this.referer = playerUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractDailymotionApi(videoId: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
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
                    val q = when {
                        url.contains("1080") -> Qualities.P1080.value; url.contains("720") -> Qualities.P720.value
                        url.contains("480") -> Qualities.P480.value; else -> Qualities.Unknown.value
                    }
                    callback(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = url) {
                        this.referer = "https://www.dailymotion.com"; this.quality = q
                    })
                }
                return true
            }
        } catch (_: Exception) {}
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

    private suspend fun extractOkRu(videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
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
            Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""").find(html)?.let { m ->
                callback(newExtractorLink(source = serverName, name = serverName, url = m.destructured.component1()) {
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
