package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.Qualities
import kotlin.collections.ArrayList
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "DonghuaLife"
// v20: cache TTL de vkeys que fallaron - evita repetir el ciclo lento (~60 s) en cada intento
private val rumbleFailedVkeys = java.util.concurrent.ConcurrentHashMap<String, Long>()
private val RUMBLE_FAIL_TTL_MS = 10L * 60L * 1000L

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

        // ===== Metadatos (v22): puntuación, año, duración y título original =====
        val score = parseDonghuaLifeScore(doc)
        val year = doc.selectFirst(".fecha .field--name-field-fecha-de-emision time.datetime")?.attr("datetime")
            ?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
        val durationMinutes = doc.selectFirst(".duracion .field--name-field-duracion")?.text()
            ?.let { parseDurationToMinutes(it) }
        val originalTitle = doc.selectFirst(".titulo-original .field--name-field-titulo-original")?.text()?.trim() ?: ""

        // ===== Títulos alternativos (v22): primera línea de la descripción =====
        val plotPrefix = StringBuilder()
        if (originalTitle.isNotBlank() && !originalTitle.equals(title, ignoreCase = true)) {
            plotPrefix.append("Títulos alternativos: ").append(originalTitle).append("\n\n")
        }

        val isMovie = seriesUrl.contains("/movie/") || genres.any { it.equals("Película", ignoreCase = true) }
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        if (isMovie) {
            return newMovieLoadResponse(title, seriesUrl, TvType.AnimeMovie, seriesUrl) {
                posterUrl = poster
                plot = plotPrefix.toString() + description
                tags = genres
                this.score = Score.from10(score)
                year?.let { this.year = it }
                if (durationMinutes != null && durationMinutes > 0) this.duration = durationMinutes
            }
        }

        // ===== Recomendaciones (v22): "Más Populares" del sidebar, sin incluir la propia serie =====
        val recommendations = extractDonghuaLifeRecommendations(doc, seriesUrl, title)

        val episodes = ArrayList<Episode>()
        val seasonNames = ArrayList<SeasonData>()

        // FIX v22 TEMPORADAS: usar el orden EXACTO de la web. Las tarjetas de
        // .temporada .views-row están en orden DOM (Temporada 1, Especial, ...
        // como en donghualife.com). Cada tarjeta puede ser "Temporada N" o un
        // "Especial" (badge .especial). Antes se re-numeraba (idx+1) y se
        // perdía la numeración real mezclando especiales con temporadas.
        data class DlSeason(val url: String, val name: String, val isSpecial: Boolean)
        val seasonCards = doc.select(".temporada .views-row").mapNotNull { row ->
            val a = row.selectFirst(".temporada .serie .imagen a, .serie .imagen a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val cardTitle = row.selectFirst(".titulo")?.text()?.trim().orEmpty()
            val isSpecial = row.selectFirst(".especial") != null ||
                cardTitle.contains("especial", ignoreCase = true)
            DlSeason(resolveUrl(href), cardTitle, isSpecial)
        }

        if (seasonCards.isNotEmpty()) {
            seasonCards.forEachIndexed { idx, season ->
                // seasonNum interno único para agrupar episodios; displaySeason=null
                // hace que CloudStream muestre el nombre tal cual en el selector.
                val seasonNum = idx + 1
                val displayName = when {
                    season.isSpecial && Regex("^especial\\s*\\d*", RegexOption.IGNORE_CASE).containsMatchIn(season.name).not() ->
                        "ESPECIAL ${idx + 1}-${season.name}" // ej: ESPECIAL 2-THE ORIGIN
                    season.isSpecial -> "ESPECIAL ${season.name}"
                    else -> "TEMPORADA ${season.name.substringAfterLast("- ").trim().ifBlank { (idx + 1).toString() }}"
                }
                seasonNames.add(SeasonData(season = seasonNum, name = displayName, displaySeason = null))

                val seasonDoc = app.get(season.url, timeout = 120).document
                extractEpisodesFromSeasonPage(seasonDoc, seasonNum, episodes)

                val lastPageLink = seasonDoc.selectFirst("li.pager__item--last a")
                val maxPage = lastPageLink?.attr("href")?.let {
                    Regex("page=(\\d+)").find(it)?.destructured?.component1()?.toIntOrNull() ?: 0
                } ?: 0

                for (pageNum in 1..maxPage) {
                    try {
                        val pageUrl = if (season.url.contains("?")) {
                            "${season.url}&page=$pageNum"
                        } else {
                            "${season.url}?page=$pageNum"
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
            if (seasonNames.isNotEmpty()) addSeasonNames(seasonNames)
            showStatus = status
            plot = plotPrefix.toString() + description
            tags = genres
            this.score = Score.from10(score)
            year?.let { this.year = it }
            if (durationMinutes != null && durationMinutes > 0) this.duration = durationMinutes
            this.recommendations = recommendations
        }
    }

    /** v22: Puntuación del fivestar de Drupal (opción seleccionada, 0-100) → valor 0-10. */
    private fun parseDonghuaLifeScore(doc: org.jsoup.nodes.Document): Double? {
        val percent = doc.selectFirst(".calificacion select.vote option[selected=selected]")?.attr("value")
            ?.toIntOrNull() ?: return null
        if (percent <= 0) return null
        return percent / 10.0
    }

    /** v22: Convierte textos tipo "15 min", "20 min", "1 h 20 min" a minutos. */
    private fun parseDurationToMinutes(text: String): Int? {
        val t = text.trim().lowercase()
        var minutes = 0
        Regex("(\\d+)\\s*h").find(t)?.destructured?.component1()?.toIntOrNull()?.let { minutes += it * 60 }
        Regex("(\\d+)\\s*m").find(t)?.destructured?.component1()?.toIntOrNull()?.let { minutes += it }
        if (minutes > 0) return minutes
        return Regex("(\\d+)").find(t)?.destructured?.component1()?.toIntOrNull()
    }

    /**
     * v22.2: Extrae la lista "Más Populares" del sidebar como recomendaciones.
     * NOTA del usuario: mezclar en orden aleatorio y devolver máximo 10 para que
     * no salgan siempre las mismas.
     */
    /**
     * v22.4: recomendaciones con la NOTA del usuario:
     * 1) título base (sin "temp N" / "latino" etc.) -> resultados del buscador
     *    interno cuyo título comparte el mismo nombre parcial (otras temporadas);
     * 2) el resto: similares ALEATORIOS del catálogo paginado /donghuas (una página
     *    al azar de las ~22). La sección "Más Populares" solo tiene 5 series fijas
     *    y por eso las recomendaciones se repetían siempre.
     * Máximo 10 resultados.
     */
    private suspend fun extractDonghuaLifeRecommendations(
        doc: org.jsoup.nodes.Document,
        seriesUrl: String,
        seriesTitle: String
    ): List<SearchResponse> {
        return try {
            val results = ArrayList<SearchResponse>()
            val seen = HashSet<String>().apply { add(seriesUrl) }

            // 1) Otras temporadas con el mismo nombre parcial (buscador interno)
            val base = seriesTitle.lowercase()
                .replace(Regex("\\btemp(orada)?\\s*\\d+\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\b(latino|castellano|doblada|subtitulada)\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+-\\s+.*$"), "")
                .trim()
            if (base.isNotBlank()) {
                val q = java.net.URLEncoder.encode(base, "UTF-8")
                val searchDoc = app.get("$mainUrl/search?search_api_fulltext=$q", timeout = 120).document
                val searchContainer = searchDoc.selectFirst("div.region-content") ?: searchDoc
                searchContainer.select(".views-row .serie").forEach {
                    val title = it.selectFirst(".titulo")?.text()?.trim() ?: return@forEach
                    val href = it.selectFirst(".imagen a")?.attr("href") ?: return@forEach
                    val full = resolveUrl(href)
                    if (full in seen || !title.lowercase().contains(base)) return@forEach
                    seen.add(full)
                    results.add(newAnimeSearchResponse(title, full) {
                        this.posterUrl = resolveUrl(it.selectFirst(".imagen img")?.attr("src") ?: "")
                    })
                }
            }

            // 2) Similares aleatorios del catálogo paginado /donghuas (página al azar)
            //    La sección "Más Populares" de la ficha solo trae ~5 series fijas.
            try {
                val lastPage = doc.select("li.pager__item--last a")
                    .firstOrNull()?.attr("href")
                    ?.let { Regex("page=(\\d+)").find(it)?.destructured?.component1()?.toIntOrNull() }
                    ?: 21 // ~22 páginas de catálogo al momento de escribir esto
                val pool = ArrayList<SearchResponse>()
                var attempts = 0
                while (pool.size < 20 && attempts < 2) {
                    attempts++
                    val pg = (0..lastPage).random()
                    val catDoc = app.get("$mainUrl/donghuas?page=$pg", timeout = 120).document
                    catDoc.select(".views-row .serie").forEach { card ->
                        if (pool.size >= 20) return@forEach
                        val href = resolveUrl(card.selectFirst(".imagen a")?.attr("href") ?: return@forEach)
                        if (href in seen || !seen.add(href)) return@forEach
                        val recTitle = card.selectFirst(".titulo")?.text()?.trim()
                            ?: card.selectFirst(".titulo a")?.text()?.trim()
                            ?: return@forEach
                        pool.add(newAnimeSearchResponse(recTitle, href) {
                            this.posterUrl = resolveUrl(card.selectFirst(".imagen img")?.attr("src") ?: "")
                        })
                    }
                }
                pool.shuffle()
                results.addAll(pool)
            } catch (_: Exception) {
                // Fallback: usar las ~5 series fijas de "Más Populares" de la ficha
                doc.select(".view-mas-populares .views-row").forEach { row ->
                    val a = row.selectFirst(".serie .imagen a") ?: return@forEach
                    val href = resolveUrl(a.attr("href"))
                    if (href in seen || !seen.add(href)) return@forEach
                    val recTitle = row.selectFirst(".titulo a")?.text()?.trim()
                        ?: row.selectFirst(".titulo")?.text()?.trim()
                        ?: return@forEach
                    results.add(newAnimeSearchResponse(recTitle, href) {
                        this.posterUrl = resolveUrl(row.selectFirst("img")?.attr("src") ?: "")
                    })
                }
            }
            results.take(10)
        } catch (_: Exception) {
            emptyList()
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
            // v22: fecha de emisión del episodio (columna "Fecha de Emisión")
            val epDate = row.selectFirst("time.datetime")?.attr("datetime")

            if (epLink != null && !isVip) {
                episodes.add(
                    newEpisode(resolveUrl(epLink)) {
                        this.season = seasonNum
                        this.episode = epNum
                        addDate(epDate)
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
                        videoUrl.contains("vidhide") || videoUrl.contains("morencius.com") -> {
                            // v2: Vidhide (ads) — morencius.com/vidhide*/embed/xxx
                            if (!extractVidhide(videoUrl, data, serverName, callback)) {
                                try { loadExtractor(videoUrl, data, subtitleCallback, callback) } catch (_: Exception) {}
                            }
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
                        fullSrc.contains("vidhide") || fullSrc.contains("morencius.com") -> {
                            // v2: Vidhide (ads)
                            if (!extractVidhide(fullSrc, data, "Vidhide", callback)) {
                                try { loadExtractor(fullSrc, data, subtitleCallback, callback) } catch (_: Exception) {}
                            }
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
            val resp = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L)
            val html = resp.text
            Log.i(TAG, "extractOkRu v19: $videoUrl httpCode=${resp.code} len=${html.length}")
            if (resp.code != 200) {
                return extractOkRuViaWebView(videoUrl, serverName, callback)
            }
            val dataMatch = Regex("""data-options="([^"]+)"""").find(html)
            if (dataMatch != null) {
                val optionsJson = dataMatch.destructured.component1().replace("&quot;", "\"").replace("&amp;", "&")
                // v3 FIX CRÍTICO: el payload contiene UNA barra invertida antes de u0026,
                // pero "\\\\u0026" (doble barra) nunca matcheaba => URLs rotas (HTTP 400).
                val unescaped = optionsJson
                    .replace(Regex("\\\\+u0026"), "&")
                    .replace(Regex("\\\\+/"), "/")
                var emittedAny = false
                // HLS: emitir cada variante como pista m3u8 (quality individual)
                for (match in Regex("""(https?://[^"]+\.m3u8[^"]*)""").findAll(unescaped)) {
                    val u = match.groupValues[1]
                    try {
                        generateM3u8(serverName, u, videoUrl).forEach(callback)
                        emittedAny = true
                    } catch (_: Exception) {}
                }
                if (emittedAny) return true
                // MP4
                for (match in Regex("""(https?://[^"]+\.mp4[^"]*)""").findAll(unescaped)) {
                    val u = match.groupValues[1]
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = serverName,
                            url = u,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = videoUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    )
                    emittedAny = true
                }
                if (emittedAny) return true
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
            // v19 Fallback WebView: si okhttp recibió el reto de Cloudflare o nada emitió,
            // carga el embed en un WebView real y parsea su HTML final.
            return extractOkRuViaWebView(videoUrl, serverName, callback)
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
    ): Boolean {
        var emitted = false
        val trackingCb: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }

        // v3: embedJS/u3|u4 AHORA DEVUELVE DATA DECOY (otro vkey, verificado 2026-09).
        // Se elimina y se usa directamente el método del embed page.
        val vkey = Regex("""/embed/([A-Za-z0-9_]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: Regex("""vkey=([A-Za-z0-9_]+)""").find(embedUrl)?.groupValues?.get(1)
        // v20: este vkey ya fallo toda la cadena hace poco - no repetir el ciclo lento
        if (vkey != null) {
            val failUntil = rumbleFailedVkeys[vkey]
            if (failUntil != null && System.currentTimeMillis() < failUntil) {
                Log.i(TAG, "extractRumble v20: fast-skip $vkey (cached failure)")
                return false
            }
        }

        if (vkey != null && !emitted) {
            emitted = tryExtractRumbleFromEmbedHtml(embedUrl, vkey, serverName, trackingCb)
            if (emitted) return true
        }

        // Fallback: método HTML del embed page
        extractRumbleLegacy(embedUrl, referer, serverName, trackingCb)
        return emitted
    }

    /** v19: Extrae el bloque real m.f["<vkey>"]={...} del HTML de la página embed de Rumble.
     *  okhttp primero; si Cloudflare bloquea (403) o el bloque no aparece, reintenta con
     *  WebView real (pasa el reto de Cloudflare). El parsing vive en extractRumbleDataBlock. */
    private suspend fun tryExtractRumbleFromEmbedHtml(
        embedUrl: String,
        vkey: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val marker = """m.f["$vkey"]="""
        // 1) okhttp (via rapida; funciona desde IP no bloqueada)
        try {
            val resp = app.get(embedUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 20L)
            val html = resp.text
            Log.i(TAG, "extractRumble v20: embedPage(okhttp) vkey=$vkey httpCode=${resp.code} len=${html.length}")
            if (resp.code == 200 && html.contains(marker)) {
                if (extractRumbleDataBlock(html, vkey, serverName, callback)) {
                    Log.i(TAG, "extractRumble v20: SUCCESS via okhttp embed-page m.f[] for $vkey")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractRumble v20: okhttp embedPage failed: ${e.message}")
        }

        // 2) Fallback WebView: motor real del sistema, pasa el reto de Cloudflare
        try {
            Log.i(TAG, "extractRumble v20: trying WebView fallback for embed page (vkey=$vkey)")
            val webHtml = fetchHtmlViaWebView(embedUrl, referer = null, waitMarker = marker, maxWaitMs = 45000L)
            if (webHtml != null) {
                Log.i(TAG, "extractRumble v20: WebView HTML len=${webHtml.length} markerFound=${webHtml.contains(marker)}")
                if (extractRumbleDataBlock(webHtml, vkey, serverName, callback)) {
                    Log.i(TAG, "extractRumble v20: SUCCESS via WebView embed-page m.f[] for $vkey")
                    return true
                }
                Log.w(TAG, "extractRumble v20: WebView HTML present but m.f[] data did not parse (vkey=$vkey)")
            } else {
                Log.w(TAG, "extractRumble v20: WebView fallback returned no HTML")
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractRumble v20: WebView fallback failed: ${e.message}")
        }
        rumbleFailedVkeys[vkey] = System.currentTimeMillis() + RUMBLE_FAIL_TTL_MS
        Log.w(TAG, "extractRumble v20: all embed-page methods failed for $vkey (cached)")
        return false
    }

    private suspend fun fetchHtmlViaWebView(
        url: String,
        referer: String? = null,
        waitMarker: String? = null,
        maxWaitMs: Long = 45000L
    ): String? {
        val ctx: Context? = try {
            var c: Context? = null
            try {
                val m = Class.forName("com.lagradost.api.ContextHelper_jvmKt")
                    .declaredMethods.firstOrNull { it.name == "getContext" }
                if (m != null) {
                    @Suppress("UNCHECKED_CAST")
                    c = m.invoke(null) as? Context
                }
            } catch (_: Throwable) {}
            if (c == null) {
                try {
                    val cls = Class.forName("com.lagradost.cloudstream3.AcraApplication")
                    val field = cls.getDeclaredField("context")
                    field.isAccessible = true
                    c = field.get(null) as? Context
                } catch (_: Throwable) {}
            }
            if (c == null) {
                try {
                    val atCls = Class.forName("android.app.ActivityThread")
                    val m = atCls.getDeclaredMethod("currentApplication")
                    m.isAccessible = true
                    c = m.invoke(null) as? Context
                } catch (_: Throwable) {}
            }
            c
        } catch (_: Throwable) { null }
        if (ctx == null) {
            Log.w(TAG, "fetchHtmlViaWebView: no Context available")
            return null
        }

        var webView: WebView? = null
        val snapshot = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val html = withTimeoutOrNull(maxWaitMs) {
            suspendCoroutine<String?> { cont ->
                val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
                fun resumeOnce(value: String?) {
                    if (resumed.compareAndSet(false, true)) cont.resume(value)
                }
                Handler(Looper.getMainLooper()).post {
                    try {
                        val wv = WebView(ctx)
                        webView = wv
                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        wv.settings.mediaPlaybackRequiresUserGesture = false
                        wv.settings.blockNetworkImage = true
                        try {
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                        } catch (_: Throwable) {}
                        // v19: NO forzar userAgentString — el UA por defecto del WebView pasa
                        // la huella de Cloudflare; forzar UA desktop en un motor móvil la falla.

                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, u: String?) {
                                super.onPageFinished(view, u)
                                view?.evaluateJavascript(
                                    "document.documentElement.outerHTML.substring(0, 900000)"
                                ) { value ->
                                    val h = value?.trim()
                                        ?.removePrefix("\"")?.removeSuffix("\"")
                                        ?.replace("\\\"", "\"")
                                        ?.replace("\\\\n", "\n")
                                        ?.replace("\\\\\"", "\"")
                                    if (!h.isNullOrEmpty() && h.length > 500) {
                                        snapshot.set(h)
                                        val markerFound = waitMarker == null || h.contains(waitMarker)
                                        if (markerFound) {
                                            Log.i(TAG, "fetchHtmlViaWebView: HTML captured (len=${h.length}, marker=${waitMarker != null})")
                                            resumeOnce(h)
                                        }
                                    }
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                val u2 = request?.url?.toString() ?: ""
                                // Solo loguear errores del documento principal, no de subrecursos
                                if (request?.isForMainFrame == true) {
                                    Log.i(TAG, "fetchHtmlViaWebView: httpError code=${errorResponse?.statusCode} url=$u2")
                                    // v20: si el documento principal dio error HTTP real, abortar ya
                                    // en lugar de esperar el challenge/captura todo el timeout
                                    val sc = errorResponse?.statusCode ?: 0
                                    if (sc in 400..599 && !u2.contains("challenges.cloudflare.com")) {
                                        resumeOnce(null)
                                    }
                                }
                            }
                        }

                        val headers = if (referer != null) mapOf("Referer" to referer) else emptyMap()
                        wv.loadUrl(url, headers)
                    } catch (e: Exception) {
                        Log.w(TAG, "fetchHtmlViaWebView: WebView error: ${e.message}")
                        resumeOnce(null)
                    }
                }
            }
        }

        Handler(Looper.getMainLooper()).post {
            try {
                webView?.stopLoading()
                webView?.removeJavascriptInterface("Android")
                webView?.destroy()
            } catch (_: Exception) {}
        }
        webView = null

        val result = html ?: snapshot.get()
        if (result == null) Log.w(TAG, "fetchHtmlViaWebView: timeout/cancel for $url")
        if (result != null && waitMarker != null && !result.contains(waitMarker)) {
            Log.w(TAG, "fetchHtmlViaWebView: HTML captured but marker not found after ${maxWaitMs}ms")
        }
        return result
    }

    /** v19: Extrae el bloque m.f["<vkey>"] del HTML (okhttp o WebView) y emite los links. */
    private suspend fun extractRumbleDataBlock(
        html: String,
        vkey: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // v20: si este vkey fallo hace poco, no repetir el analisis completo
        val failUntil = rumbleFailedVkeys[vkey]
        if (failUntil != null && System.currentTimeMillis() < failUntil) {
            Log.i(TAG, "extractRumble v20: skip $vkey (failed recently)")
            return false
        }
        val qCh = 34.toChar()   // doble comilla
        val bsCh = 92.toChar() // barra invertida
        val block = Regex("m" + bsCh + ".f" + bsCh + "[" + bsCh + qCh + Regex.escape(vkey) + qCh + bsCh + "]=").find(html)
        if (block == null) {
            Log.w(TAG, "extractRumble v21: m.f[] block regex no matchio para $vkey (marker presente?)")
            return false
        }

        val start = block.range.first
        val nextMf = html.indexOf("m.f[\"", start + 10)
        // v20: </script> NO es un borde seguro (configs de ads lo contienen dentro de
        // strings y truncaban el bloque antes de las URLs); cap de 300 KB por seguridad
        val end = listOf(nextMf, start + 300000).filter { it > start }.minOrNull() ?: html.length
        val data = html.substring(start, minOf(end, html.length))
            .replace(Regex("\\\\+/"), "/")
            .replace(Regex("\\\\+u0026"), "&")

                Log.i(TAG, "extractRumble v20: block len=" + data.length + " head=" + data.take(140))

var emitted = false

        // 1) Master HLS (hls-vod o live-hls-dvr) — jugable directamente (verificado 200)
        val hlsMaster = Regex(""""hls"\s*:\s*\{\s*"url"\s*:\s*"(https://rumble\.com/(?:hls-vod|live-hls-dvr)/[^"]+\.m3u8)"""").find(data)
            ?: Regex("""(https://rumble\.com/(?:hls-vod|live-hls-dvr)/[^"]+\.m3u8)""").find(data)
        if (hlsMaster != null) {
            val u = hlsMaster.groupValues[1]
            try {
                generateM3u8(serverName, u, "https://rumble.com").forEach(callback)
                emitted = true
                Log.i(TAG, "extractRumble v20: master HLS emitted: ${u.take(80)}")
            } catch (e: Exception) {
                Log.w(TAG, "extractRumble v20: generateM3u8 failed (${e.message}) - emitiendo link HLS directo")
                try {
                    callback(
                        newExtractorLink(
                            source = serverName, name = serverName, url = u,
                            type = ExtractorLinkType.M3U8
                        ) { this.referer = "https://rumble.com" }
                    )
                    emitted = true
                } catch (_: Throwable) {}
            }
        }

        // 1b) v20: si la master no matcheo, cualquier m3u8 del bloque (CDNs alternativos)
        if (hlsMaster == null) {
            val anyM3u8 = Regex("""https?://[^\"\s]+?\.m3u8[^\"\s]*""").find(data)
            if (anyM3u8 != null) {
                try {
                    callback(
                        newExtractorLink(
                            source = serverName, name = serverName, url = anyM3u8.value,
                            type = ExtractorLinkType.M3U8
                        ) { this.referer = "https://rumble.com" }
                    )
                    emitted = true
                    Log.i(TAG, "extractRumble v20: generic m3u8 emitted: " + anyM3u8.value.take(80))
                } catch (_: Throwable) {}
            }
        }

        // 2) Pistas tar por calidad (ua.tar.360/480/720/1080) — chunklist virtual jugable
        val tarUrls = LinkedHashMap<String, String>()
        for (m in Regex(""""(\d{3,4})"\s*:\s*\{\s*"url"\s*:\s*"(https://hugh\.cdn\.rumble\.cloud/[^"]+?\.tar[^"]*?)"""").findAll(data)) {
            val q = m.groupValues[1]
            val u = m.groupValues[2]
            if (!tarUrls.containsKey(q)) tarUrls[q] = u
        }
        for ((q, u) in tarUrls) {
            val quality = when (q) {
                "2160" -> Qualities.P2160.value; "1440" -> Qualities.P1440.value
                "1080" -> Qualities.P1080.value; "720" -> Qualities.P720.value
                "480" -> Qualities.P480.value; "360" -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            try {
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = "$serverName ${q}p",
                        url = u,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://rumble.com"
                        this.quality = quality
                    }
                )
                emitted = true
            } catch (_: Throwable) {}
        }
        if (tarUrls.isNotEmpty()) Log.i(TAG, "extractRumble v19: ${tarUrls.size} tar tracks (${tarUrls.keys.joinToString(",")})")

        // 3) Fallback: cualquier mp4 directo
        if (!emitted) {
            for (m in Regex("""(https?://[^"]+?\.mp4[^"]*)""").findAll(data)) {
                val u = m.groupValues[1]
                try {
                    callback(
                        newExtractorLink(
                            source = serverName, name = serverName, url = u,
                            type = ExtractorLinkType.VIDEO
                        ) { this.referer = "https://rumble.com" }
                    )
                    emitted = true
                } catch (_: Throwable) {}
            }
        }
        if (!emitted) {
            rumbleFailedVkeys[vkey] = System.currentTimeMillis() + RUMBLE_FAIL_TTL_MS
            Log.w(TAG, "extractRumble v20: bloque presente sin links para el vkey (cacheado 10 min)")
        }
        return emitted
    }

    /** v19: Último recurso OK.ru — carga el embed en un WebView real (pasa Cloudflare)
     *  y parsea el HTML final con data-options / URLs directas. */
    private suspend fun extractOkRuViaWebView(
        videoUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.i(TAG, "extractOkRu v19: trying WebView fallback for $videoUrl")
            val webHtml = fetchHtmlViaWebView(videoUrl, referer = null, waitMarker = null, maxWaitMs = 45000L)
                ?: return false
            Log.i(TAG, "extractOkRu v19: WebView HTML len=${webHtml.length}")

            // data-options (HTML-entity escapado) — camino principal
            Regex("""data-options="([^"]+)"""").find(webHtml)?.let { dm ->
                val optionsJson = dm.groupValues[1].replace("&quot;", "\"").replace("&amp;", "&")
                val unescaped = optionsJson
                    .replace(Regex("\\\\+u0026"), "&")
                    .replace(Regex("\\+/"), "/")
                var emitted = false
                for (match in Regex("""(https?://[^"]+\.m3u8[^"]*)""").findAll(unescaped)) {
                    try {
                        generateM3u8(serverName, match.groupValues[1], videoUrl).forEach(callback)
                        emitted = true
                    } catch (_: Exception) {}
                }
                if (emitted) return true
                for (match in Regex("""(https?://[^"]+\.mp4[^"]*)""").findAll(unescaped)) {
                    callback(newExtractorLink(source = serverName, name = serverName, url = match.groupValues[1], type = ExtractorLinkType.VIDEO) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    })
                    emitted = true
                }
                if (emitted) return true
            }

            // MP4/m3u8 crudos en el HTML renderizado
            for (match in Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""").findAll(webHtml)) {
                val u = match.groupValues[1]
                if (u.contains(".m3u8")) {
                    try {
                        generateM3u8(serverName, u, videoUrl).forEach(callback)
                        return true
                    } catch (_: Exception) {}
                } else {
                    callback(newExtractorLink(source = serverName, name = serverName, url = u, type = ExtractorLinkType.VIDEO) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    })
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractOkRuViaWebView failed: ${e.message}")
        }
        return false
    }

    private suspend fun extractRumbleLegacy(
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

    // ========== EXTRACTOR VIDHIDE (v2) ==========

    /**
     * Vidhide (ads) — morencius.com / vidhidevip.com / vidhidepre.com / vidhide.com
     * La página embed trae un packer Dean Edwards (eval(function(p,a,c,k,e,d)...))
     * que contiene la URL master.m3u8. La m3u8 responde 200 sin Referer.
     */
    private suspend fun extractVidhide(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        val trackingCb: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }
        try {
            val html = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 20L).text
            val unpacked = getAndUnpack(html)
            var found = false
            for (m in Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").findAll(unpacked)) {
                val u = m.groupValues[1]
                    .replace("\\/", "/").replace(Regex("\\\\+u0026"), "&").replace("&amp;", "&")
                if (u.contains(".mp4")) continue
                try {
                    generateM3u8(serverName, u, embedUrl).forEach(trackingCb)
                    found = true
                } catch (_: Exception) {
                    try {
                        trackingCb(
                            newExtractorLink(
                                source = serverName,
                                name = serverName,
                                url = u,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }
            if (!emitted) {
                for (m in Regex("""(https?://[^"'\s\\]+\.mp4[^"'\s\\]*)""").findAll(unpacked)) {
                    val u = m.groupValues[1]
                        .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                    try {
                        trackingCb(
                            newExtractorLink(
                                source = serverName,
                                name = serverName,
                                url = u,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Exception) {}
        return emitted
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
