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

                // Extraer número de episodio del subtitulo ("Episodio 157")
                val epNum = subtitleEl?.text()?.let {
                    Regex("Episodio\\s*(\\d+)", RegexOption.IGNORE_CASE).find(it)?.destructured?.component1()?.toIntOrNull()
                }

                // Convertir URL de episodio a URL de serie
                val seriesUrl = episodeUrlToSeriesUrl(epHref)

                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, seriesUrl) {
                    this.posterUrl = resolveUrl(poster ?: "")
                    addDubStatus(dubstat, epNum)
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

    // FIX: Scoping selector to .region-content to avoid sidebar "Más Populares" contamination
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?search_api_fulltext=$query", timeout = 120).document
        // Solo buscar dentro del área de contenido principal (.region-content),
        // NO en el sidebar (<aside>) que contiene "Más Populares"
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
        // Poster: el sitio usa URLs relativas (/sites/default/files/styles/poster/...)
        // og:image NO existe en las páginas de serie de donghualife
        // Selector principal: .field--name-field-poster img.image-style-poster
        val posterRaw = doc.selectFirst(".field--name-field-poster img.image-style-poster")?.attr("src")
            ?: doc.selectFirst(".poster img")?.attr("src")
            ?: doc.selectFirst(".poster img")?.attr("data-src")
            ?: doc.selectFirst("article.node img.image-style-poster")?.attr("src")
            ?: doc.selectFirst(".imagen-node img.image-style-poster")?.attr("src")
            ?: doc.selectFirst("article.node img.image-style-node-series")?.attr("src")
            ?: doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("head meta[name=image]")?.attr("content")
            ?: ""
        // Resolver URL relativa a absoluta
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
        doc.select("a.toggle-enlace[data-video]").forEach { link ->
            val videoUrl = link.attr("data-video")
            val serverName = link.attr("title")?.trim() ?: "Server"
            if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                try {
                    when {
                        // Rumble: extracción manual (no hay extractor nativo en CS3)
                        videoUrl.contains("rumble.com") -> {
                            extractRumble(videoUrl, data, serverName, callback)
                        }
                        // Stremeable = streamable.com (FIX 2026-07-30: scraping directo del embed)
                        videoUrl.contains("streamable.com") -> {
                            extractStreamable(videoUrl, data, serverName, subtitleCallback, callback)
                        }
                        // Dailymotion: extracción directa via API
                        videoUrl.contains("dailymotion.com") || videoUrl.contains("geo.dailymotion.com") -> {
                            val videoId = Regex("video=([A-Za-z0-9]+)").find(videoUrl)?.destructured?.component1()
                                ?: Regex("/video/([A-Za-z0-9]+)").find(videoUrl)?.destructured?.component1()
                            if (!videoId.isNullOrEmpty()) {
                                extractDailymotionApi(videoId, data, serverName, callback)
                            } else {
                                loadExtractor(videoUrl, data, subtitleCallback, callback)
                            }
                        }
                        // Ok.ru: necesita http:// (no https://) para loadExtractor
                        videoUrl.contains("ok.ru") -> {
                            val okUrl = videoUrl.replace("https://ok.ru", "http://ok.ru")
                            try { loadExtractor(okUrl, data, subtitleCallback, callback) } catch (_: Exception) {}
                        }
                        // Vidhide (vidhide.pro, vidhide.com, etc.)
                        videoUrl.contains("vidhide") || videoUrl.contains("videovard") -> {
                            try { loadExtractor(videoUrl, data, subtitleCallback, callback) } catch (_: Exception) {}
                        }
                        // Otros servidores: intentar con loadExtractor
                        else -> {
                            try { loadExtractor(videoUrl, data, subtitleCallback, callback) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Método alternativo: buscar iframe directamente
        doc.select("iframe#iframe-episode, div.embed iframe, div#video-container iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                try {
                    val fullSrc = resolveUrl(src)
                    when {
                        fullSrc.contains("rumble.com") -> {
                            extractRumble(fullSrc, data, "Rumble", callback)
                        }
                        // Stremeable en iframe (FIX 2026-07-30: scraping directo del embed)
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
                            try { loadExtractor(fullSrc.replace("https://ok.ru", "http://ok.ru"), data, subtitleCallback, callback) } catch (_: Exception) {}
                        }
                        fullSrc.contains("vidhide") || fullSrc.contains("videovard") -> {
                            try { loadExtractor(fullSrc, data, subtitleCallback, callback) } catch (_: Exception) {}
                        }
                        else -> {
                            try { loadExtractor(fullSrc, data, subtitleCallback, callback) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Método adicional: buscar cualquier enlace que contenga OK.ru aunque no esté en data-video o iframe
        // Algunos sitios usan selectores diferentes como data-ok, href, o atributos personalizados
        doc.select("[href*='ok.ru'], [data-src*='ok.ru'], [data-url*='ok.ru']").forEach { element ->
            val okUrl = element.attr("href").ifEmpty { element.attr("data-src") }.ifEmpty { element.attr("data-url") }
            if (okUrl.isNotEmpty() && okUrl.contains("ok.ru")) {
                try {
                    loadExtractor(okUrl.replace("https://ok.ru", "http://ok.ru"), data, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        return true
    }

    /**
     * Extracción de Dailymotion via API metadata (más confiable que loadExtractor)
     */
    private suspend fun extractDailymotionApi(
        videoId: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Método 1: API metadata
        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val jsonText = app.get(apiUrl,
                referer = "https://www.dailymotion.com/embed/video/$videoId",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                timeout = 15L).text

            // Buscar URLs m3u8
            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(jsonText)) {
                try {
                    generateM3u8(serverName, match.value, "https://www.dailymotion.com").forEach(callback)
                    return true
                } catch (_: Exception) {}
            }
            // Buscar URLs mp4
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

        // Método 2: loadExtractor
        try {
            loadExtractor("https://www.dailymotion.com/embed/video/$videoId", referer, subtitleCallback = {}, callback)
            return true
        } catch (_: Exception) {}

        return false
    }

    /**
     * Extracción manual de video Rumble (no tiene extractor nativo en CS3)
     * Rumble embed URLs: https://rumble.com/embed/v{ID}/?pub=...
     *
     * REESCRITO con 5 métodos de fallback progresivos:
     * 1. JSON block completo con "ua" y "mp4"
     * 2. URLs CDN rmbl.ws (mp4)
     * 3. URLs m3u8 (HLS)
     * 4. URLs mp4 genéricas
     * 5. og:video meta tag
     */
    /**
     * FIX 2026-07-28: Rumble cambió el formato JSON de su embed.
     * Antes: "ua":{"mp4":[...]}
     * Ahora: "hls":{"auto":{"url":"https://rumble.com/hls-vod/<id>/playlist.m3u8"}}
     *        "ua":{"tar":{"360":{"url":"..."},"480":{"url":"..."},"720":{"url":"..."}}}
     * Los archivos .tar?r_file=chunklist.m3u8 son HLS envueltos en TAR,
     * hay que marcarlos como M3U8 (no MP4) o el reproductor falla con
     * "Error_code_parsing_container_unsupported(3003)".
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

            // ===== Método 1 (NUEVO): HLS master playlist "hls":{"auto":{"url":"..."}} =====
            // Es lo más confiable: una playlist m3u8 que el reproductor CS3 maneja nativamente.
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

            // ===== Método 2 (NUEVO): "ua":{"tar":{"<quality>":{"url":"..."}}} =====
            // Cada calidad es un .tar?r_file=chunklist.m3u8 (HLS envuelto en TAR).
            val tarQualityPattern = Regex(
                """"(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)""""
            )
            val tarBlockMatch = Regex(""""ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})""").find(html)
            if (tarBlockMatch != null) {
                val tarBlock = tarBlockMatch.groupValues[1]
                var foundAny = false
                tarQualityPattern.findAll(tarBlock).forEach { match ->
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
                    foundAny = true
                }
                if (foundAny) return
            }

            // ===== Método 3 (LEGACY): "ua":{"mp4":[...]} =====
            // Para compatibilidad con videos antiguos que aún usan el formato mp4 array.
            val jsonPatterns = listOf(
                Regex(""""ua"\s*:\s*\{[^}]*"mp4"\s*:\s*\[([^\]]+)\]"""),
                Regex(""""mp4"\s*:\s*\[([^\]]+)\]"""),
            )
            for (pattern in jsonPatterns) {
                val jsonMatch = pattern.find(html)
                if (jsonMatch != null) {
                    val mp4Array = jsonMatch.destructured.component1()
                    val urlRegex = Regex(""""(https?://[^"]+\.mp4[^"]*)"""")
                    var foundAny = false
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
                        foundAny = true
                    }
                    if (foundAny) return
                }
            }

            // ===== Método 4: URLs CDN de rmbl.ws =====
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

            // ===== Método 5: Cualquier URL m3u8 genérica =====
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

            // ===== Método 6: URLs mp4 genéricas =====
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

            // ===== Método 7: og:video meta tag =====
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

    /**
     * FIX 2026-07-30: Extractor para Stremeable (= streamable.com).
     *
     * BUG ANTERIOR (2026-07-28): El código llamaba a `loadExtractor` primero con un
     * `return` incondicional después. Como `loadExtractor` en CS3 NO lanza excepción
     * cuando no encuentra enlaces (simplemente no invoca el callback), el `return`
     * se ejecutaba siempre y el código de scraping NUNCA corría. Resultado: para
     * episodios que solo tienen servidor Stremeable (ej: Supreme God Emperor 2
     * ep 479), la app mostraba "Enlaces no Encontrados" aunque el embed sí
     * contenía el MP4 firmado.
     *
     * NUEVO ENFOQUE: scraping directo del HTML del embed, SIN pasar por loadExtractor.
     *
     * Streamable expone el MP4 en el embed /e/<id> en dos lugares:
     *   1. Atributo <video src="//cdn-cf-east.streamable.com/video/mp4/<id>.mp4?Expires=...&amp;Signature=...&amp;Key-Pair-Id=...">
     *      → URL con &amp; (HTML escape)
     *   2. JSON embebido: var videoObject = {"files":{"mp4":{"url":"//cdn-cf-...mp4?Expires=...\u0026Signature=..."},
     *                                            "mp4-mobile":{"url":"//cdn-cf-...mp4-mobile/...?Expires=...\u0026Signature=..."}}
     *      → URL con \u0026 (JSON escape)
     *
     * Dos variantes con firmas distintas:
     *   - "mp4"        → 1280x720 (720p)
     *   - "mp4-mobile" →  640x360 (360p)
     *
     * Ambas variantes se extraen y se unescapen &amp; → & y \u0026 → &. Se deduplican
     * URLs idénticas (la URL del <video> y la del JSON "mp4" son la misma tras unescapar).
     *
     * Verificado con curl: las URLs MP4 firmadas del CDN devuelven HTTP 200,
     * content-type video/mp4, content-length ~198MB. El reproductor CS3 las maneja
     * directamente como ExtractorLinkType.VIDEO.
     */
    private suspend fun extractStreamable(
        embedUrl: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // FIX 2026-07-30: scraping directo del HTML del embed (NO loadExtractor primero).
            // Causa del bug anterior: loadExtractor no lanza excepción cuando no encuentra
            // enlaces, solo no invoca el callback. Como había un `return` incondicional
            // después, el código de scraping NUNCA se ejecutaba → "Enlaces no Encontrados".
            val html = app.get(embedUrl, referer = referer, timeout = 15L).text

            // Set para deduplicar URLs ya procesadas
            val seen = mutableSetOf<String>()

            // Método 1: Capturar TODAS las URLs MP4 del CDN de streamable.
            // Patrón único catch-all que atrapa <video src="...">, JSON "url":"...", y
            // cualquier otra variante. Maneja &amp; (HTML) y \u0026 (JSON) — ambos se
            // unescapen después del match.
            val mp4Pattern = Regex("""["'](//cdn-cf-[^"']*streamable\.com/video/[^"']+\.mp4[^"']*)["']""")
            val mp4Matches = mp4Pattern.findAll(html).toList()

            for (match in mp4Matches) {
                var url = match.groupValues[1]
                // Si es protocol-relative (//), agregar https:
                if (url.startsWith("//")) url = "https:$url"
                // Unescapar HTML &amp; → &
                url = url.replace("&amp;", "&")
                // Unescapar JSON \u0026 → & (en string regular "\\u0026" = literal \u0026)
                url = url.replace("\\u0026", "&")
                // Unescapar JSON \/ → / (por si acaso)
                url = url.replace("\\/", "/")

                if (url in seen) continue
                seen.add(url)

                // Detectar calidad desde el path:
                //   /video/mp4/<id>.mp4        → 720p (1280x720 según metadata)
                //   /video/mp4-mobile/<id>.mp4 → 360p (640x360 según metadata)
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

            // Método 2 (fallback): Si scraping no encontró nada (HTML cambió),
            // intentar con loadExtractor nativo de CS3.
            try {
                loadExtractor(embedUrl, referer, subtitleCallback, callback)
                return
            } catch (_: Exception) {}

            // Método 3 (último recurso): Construir URL del CDN sin auth.
            // Casi seguramente fallará (CloudFront requiere firma), pero lo intentamos.
            val idMatch = Regex("""streamable\.com/(?:e/)?([A-Za-z0-9]+)""").find(embedUrl)
            if (idMatch != null) {
                val videoId = idMatch.destructured.component1()
                val cdnZones = listOf("cdn-cf-east", "cdn-cf-west")
                for (zone in cdnZones) {
                    val cdnUrl = "https://$zone.streamable.com/video/mp4/$videoId.mp4"
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = serverName,
                            url = cdnUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://streamable.com/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (_: Exception) {}
    }
}
