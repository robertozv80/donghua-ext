package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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
            // Homepage con paginación infinite scroll: /?page=N
            if (page > 1) "$mainUrl/?page=${page - 1}" else request.data
        } else {
            if (page > 1) "${request.data}?page=${page - 1}" else request.data
        }
        val doc = app.get(url, timeout = 120).document

        val home = if (isHomePage) {
            // Página principal: sección "Últimos episodios agregados"
            // Omitir episodios VIP (div.patreon)
            doc.select(".views-row .episode, div.episode").mapNotNull { ep ->
                // Saltar episodios VIP
                if (ep.selectFirst("div.patreon") != null || ep.selectFirst(".patreon span") != null) return@mapNotNull null

                val titleEl = ep.selectFirst("div.titulo") ?: return@mapNotNull null
                val subtitleEl = ep.selectFirst("div.subtitulo")
                val title = titleEl.text().trim()
                val epHref = ep.selectFirst("div.imagen a")?.attr("href") ?: return@mapNotNull null
                val poster = ep.selectFirst("div.imagen img")?.attr("src")

                // Convertir URL de episodio a URL de serie
                val seriesUrl = episodeUrlToSeriesUrl(epHref)

                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, seriesUrl) {
                    this.posterUrl = resolveUrl(poster ?: "")
                    addDubStatus(dubstat)
                }
            }
        } else {
            // Listados de series (Donghuas, En Emisión, etc.)
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
            // Infinite scroll: hay botón "Cargar más"
            doc.select("ul.js-pager__items a, nav.pager a[href*=\"page=\"]").isNotEmpty()
        } else {
            doc.select("nav.pager a[href*=\"page=\"]").isNotEmpty()
        }
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    /**
     * Convierte URL de episodio a URL de serie
     * /episode/{slug}-{season}-episodio-x{num} → /series/{slug}
     *
     * Ejemplo: /episode/martial-master-1-episodio-x652 → /series/martial-master
     * Ejemplo: /episode/swallowed-star-3-1-episodio-x141 → /series/swallowed-star-3
     *
     * La lógica: el slug de la serie es todo antes del último "-{digito}-episodio-x{digito}"
     */
    private fun episodeUrlToSeriesUrl(epHref: String): String {
        val href = resolveUrl(epHref)
        // Patrón: /episode/{slug}-{season_num}-episodio-x{ep_num}
        // El {slug} puede contener guiones y números (ej: swallowed-star-3)
        // Pero el season_num siempre es un dígito simple antes de "-episodio-"
        val regex = Regex("/episode/(.+)-(\\d+)-episodio-x(\\d+)")
        val match = regex.find(href)
        return if (match != null) {
            val slug = match.destructured.component1()
            "$mainUrl/series/$slug"
        } else {
            // Fallback: si no coincide el patrón, devolver la URL tal cual
            // load() se encargará de obtener la serie desde la página del episodio
            href
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/search?search_api_fulltext=$query", timeout = 120).document
            .select(".views-row .serie").mapNotNull {
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
        // Si la URL es de un episodio, redirigir a la página de la serie
        val seriesUrl = if (url.contains("/episode/")) {
            // Obtener URL de serie desde la página del episodio (método más confiable)
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
        // Poster: múltiples selectores como fallback
        // Algunas series usan .poster img, otras .imagen-node img, otras solo og:image
        val poster = doc.selectFirst(".poster img")?.attr("src")
            ?: doc.selectFirst(".poster img")?.attr("data-src")
            ?: doc.selectFirst(".imagen-node img")?.attr("src")
            ?: doc.selectFirst(".imagen-node img")?.attr("data-src")
            ?: doc.selectFirst("article.node img.image-style-poster")?.attr("src")
            ?: doc.selectFirst("article.node img.image-style-node-series")?.attr("src")
            ?: doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("head meta[name=image]")?.attr("content")
            ?: ""
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

        // Serie: extraer temporadas y episodios
        val episodes = ArrayList<Episode>()

        // Buscar temporadas listadas en la página de la serie
        val seasonLinks = doc.select(".temporada .view-temporadas .views-row .serie .imagen a, .temporada .serie .imagen a")
            .map { resolveUrl(it.attr("href")) }

        if (seasonLinks.isNotEmpty()) {
            // Hay múltiples temporadas - cargar cada una CON paginación
            seasonLinks.forEachIndexed { idx, seasonUrl ->
                val seasonDoc = app.get(seasonUrl, timeout = 120).document
                val seasonTitle = seasonDoc.selectFirst(".titulo .field--name-title")?.text()
                    ?: seasonDoc.selectFirst(".titulo h2 a span")?.text()
                    ?: ""
                val seasonNum = Regex("temporada\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(seasonTitle)?.destructured?.component1()?.toIntOrNull()
                    ?: (idx + 1)

                // Extraer episodios de la primera página de la temporada
                extractEpisodesFromSeasonPage(seasonDoc, seasonNum, episodes)

                // Buscar paginación y cargar TODAS las páginas de episodios
                // Formato: /season/{slug}?page=N (0-indexed)
                val lastPageLink = seasonDoc.selectFirst("li.pager__item--last a")
                val maxPage = lastPageLink?.attr("href")?.let {
                    Regex("page=(\\d+)").find(it)?.destructured?.component1()?.toIntOrNull() ?: 0
                } ?: 0

                // Cargar páginas restantes (1 hasta maxPage)
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
            // No hay temporadas separadas, buscar si la URL es una página de temporada
            if (seriesUrl.contains("/season/")) {
                val seasonNum = Regex("/season/.+-(\\d+)$").find(seriesUrl)?.destructured?.component1()?.toIntOrNull() ?: 1
                extractEpisodesFromSeasonPage(doc, seasonNum, episodes)

                // Paginación para temporada directa
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
                // Es una página de serie sin temporadas explícitas
                // Verificar si hay tabla de episodios directamente
                extractEpisodesFromSeasonPage(doc, 1, episodes)

                // También buscar paginación en la tabla de episodios
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
            // Detectar episodio VIP por texto " - VIP" después del enlace
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

        // Verificar si es un episodio VIP
        val isRestricted = doc.selectFirst("div.patreon-restricted-message") != null
            || doc.selectFirst("article.patreon-restricted") != null
        if (isRestricted) return false

        // Extraer servidores de video desde los enlaces data-video
        // Formato: <a class="toggle-enlace" data-video="URL" title="Rumble">
        doc.select("a.toggle-enlace[data-video]").amap { link ->
            val videoUrl = link.attr("data-video")
            val serverName = link.attr("title")?.trim() ?: "Server"
            if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                try {
                    when {
                        // Rumble: extracción manual (no hay extractor nativo en CS3)
                        videoUrl.contains("rumble.com") -> {
                            extractRumble(videoUrl, data, serverName, callback)
                        }
                        // Dailymotion: usar loadExtractor con URL estándar
                        videoUrl.contains("dailymotion.com") || videoUrl.contains("geo.dailymotion.com") -> {
                            val videoId = Regex("video=([A-Za-z0-9]+)").find(videoUrl)?.destructured?.component1()
                                ?: Regex("/video/([A-Za-z0-9]+)").find(videoUrl)?.destructured?.component1()
                            if (!videoId.isNullOrEmpty()) {
                                val dmUrl = "https://www.dailymotion.com/embed/video/$videoId"
                                loadExtractor(dmUrl, data, subtitleCallback, callback)
                            } else {
                                // Intentar con la URL directamente
                                loadExtractor(videoUrl, data, subtitleCallback, callback)
                            }
                        }
                        // Ok.ru: necesita http:// (no https://) para loadExtractor
                        videoUrl.contains("ok.ru") -> {
                            val okUrl = videoUrl.replace("https://ok.ru", "http://ok.ru")
                            loadExtractor(okUrl, data, subtitleCallback, callback)
                        }
                        // Otros servidores: intentar con loadExtractor
                        else -> {
                            loadExtractor(videoUrl, data, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Método alternativo: buscar iframe directamente
        doc.select("iframe#iframe-episode, div.embed iframe, div#video-container iframe").amap { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                try {
                    val fullSrc = resolveUrl(src)
                    when {
                        fullSrc.contains("rumble.com") -> {
                            extractRumble(fullSrc, data, "Rumble", callback)
                        }
                        fullSrc.contains("dailymotion.com") || fullSrc.contains("geo.dailymotion.com") -> {
                            val videoId = Regex("video=([A-Za-z0-9]+)").find(fullSrc)?.destructured?.component1()
                                ?: Regex("/video/([A-Za-z0-9]+)").find(fullSrc)?.destructured?.component1()
                            if (!videoId.isNullOrEmpty()) {
                                loadExtractor("https://www.dailymotion.com/embed/video/$videoId", data, subtitleCallback, callback)
                            }
                        }
                        fullSrc.contains("ok.ru") -> {
                            loadExtractor(fullSrc.replace("https://ok.ru", "http://ok.ru"), data, subtitleCallback, callback)
                        }
                        fullSrc.contains("voe.sx") || fullSrc.contains("filemoon") -> {
                            loadExtractor(fullSrc, data, subtitleCallback, callback)
                        }
                        else -> {
                            loadExtractor(fullSrc, data, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return true
    }

    /**
     * Extracción manual de video Rumble (no tiene extractor nativo en CS3)
     * Rumble embed URLs: https://rumble.com/embed/v{ID}/?pub=...
     */
    private suspend fun extractRumble(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(embedUrl, referer = referer, timeout = 30)
            val html = response.text

            // Buscar JSON de configuración del video en el HTML
            // Rumble usa un objeto JSON con las URLs de video
            val jsonConfigRegex = Regex("""\"ua\":\s*\{[^}]*\"mp4\":\s*\[([^\]]+)\]""")
            val jsonMatch = jsonConfigRegex.find(html)
            if (jsonMatch != null) {
                val mp4Array = jsonMatch.destructured.component1()
                // Extraer URLs individuales del array
                val urlRegex = Regex("""\"(https?://[^\"]+\.mp4[^\"]*)\"""")
                urlRegex.findAll(mp4Array).forEach { match ->
                    val url = match.destructured.component1()
                    // Extraer calidad del URL si es posible
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
                            url = url
                        ) {
                            this.referer = referer
                            this.quality = quality
                        }
                    )
                }
                return
            }

            // Método alternativo: buscar URL m3u8 en el HTML
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

            // Método alternativo: buscar URL mp4 directamente
            val mp4Patterns = listOf(
                Regex("""["'](https?://[^"']+?rmbl\.ws[^"']*?\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),
            )

            for (pattern in mp4Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val url = match.destructured.component1()
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = serverName,
                            url = url
                        ) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            }

            // Último intento: buscar og:video meta tag
            val ogVideoPattern = Regex("""<meta\s+property=["']og:video["']\s+content=["']([^"']+)["']""")
            val ogMatch = ogVideoPattern.find(html)
            if (ogMatch != null) {
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = ogMatch.destructured.component1()
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }
}
