package com.donghuaext

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLDecoder
import kotlin.collections.ArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "DonghuaLifeBeta"

class DonghuaLifeBetaProvider : MainAPI() {

    override var mainUrl = "https://beta.donghualife.com"
    override var name = "DonghuaLife Beta"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.OVA,
        TvType.AnimeMovie,
    )

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Sec-Ch-Ua" to "\"Not?A_Brand\";v=\"99\", \"Chromium\";v=\"149\", \"Google Chrome\";v=\"149\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Priority" to "u=0, i",
    )

    private val ajaxClientHints = mapOf(
        "Sec-Ch-Ua" to "\"Not?A_Brand\";v=\"99\", \"Chromium\";v=\"149\", \"Google Chrome\";v=\"149\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Priority" to "u=1, i",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos Episodios",
        "$mainUrl/#recomendaciones" to "Recomendaciones",
        "$mainUrl/#tendencias" to "Tendencias",
        "$mainUrl/rankings" to "Ranking",
        "$mainUrl/series?sort=latest" to "Series",
        "$mainUrl/peliculas?sort=newest" to "Películas",
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

    private fun extractNextImagePath(imgSrc: String): String {
        val decoded = URLDecoder.decode(imgSrc, "UTF-8")
        val paramMatch = Regex("""url=([^&]+)""").find(decoded)
        return paramMatch?.groupValues?.get(1) ?: decoded
    }

    private fun extractRscPayload(html: String): String {
        val payloadPattern = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
        val payloads = payloadPattern.findAll(html).map { it.groupValues[1] }.toList()
        val sb = StringBuilder()
        for (p in payloads) {
            val decoded = try {
                p.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
            } catch (_: Exception) { p }
            sb.append(decoded)
        }
        return sb.toString()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val home = ArrayList<SearchResponse>()
        var hasNext = false

        when {
            url.endsWith("/") && !url.contains("#") -> {
                val doc = app.get(url, timeout = 60).document
                doc.select("#latest-episodes-scroll a[href*='/watch/']").forEach { a ->
                    val href = a.attr("href")
                    val title = a.selectFirst("p.line-clamp-2")?.text()?.trim()
                        ?: a.selectFirst("img")?.attr("alt")?.trim()
                        ?: href.substringAfterLast("/").replace("-", " ")
                    val epBadge = a.selectFirst("span")?.text()?.trim() ?: ""
                    val epNum = Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE).find(epBadge)
                        ?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""/(?:watch/)?(?:[^/]+-)*(\d+)$""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                    home.add(
                        newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                            this.posterUrl = poster
                            if (epNum != null) addDubStatus(DubStatus.Subbed, epNum)
                        }
                    )
                }
                hasNext = false
            }

            url.endsWith("#recomendaciones") -> {
                val doc = app.get("$mainUrl/", timeout = 60).document
                doc.select("#recommended-scroll a[href^='/series/']").forEach { a ->
                    val href = a.attr("href")
                    val title = a.selectFirst("img")?.attr("alt")?.trim()
                        ?: a.selectFirst("p")?.text()?.trim()
                        ?: href.substringAfterLast("/")
                    val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                    if (title.isNotBlank()) {
                        home.add(
                            newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                                this.posterUrl = poster
                                addDubStatus(DubStatus.Subbed)
                            }
                        )
                    }
                }
                hasNext = false
            }

            url.endsWith("#tendencias") -> {
                val doc = app.get("$mainUrl/", timeout = 60).document
                doc.select("#trending-scroll a[href^='/series/']").forEach { a ->
                    val href = a.attr("href")
                    val title = a.selectFirst("img")?.attr("alt")?.trim()
                        ?: a.selectFirst("p")?.text()?.trim()
                        ?: href.substringAfterLast("/")
                    val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                    home.add(
                        newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                            this.posterUrl = poster
                            addDubStatus(DubStatus.Subbed)
                        }
                    )
                }
                hasNext = false
            }

            url.contains("/rankings") -> {
                val doc = app.get(url, timeout = 60).document
                doc.select("a[href^='/series/']").forEach { a ->
                    val href = a.attr("href")
                    if (!href.startsWith("/series/")) return@forEach
                    val title = a.selectFirst("h2")?.text()?.trim() ?: return@forEach
                    val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                    var score: String? = null
                    a.select("span").forEach { s ->
                        val txt = s.text().trim()
                        if (txt.matches(Regex("""\d+\.\d+"""))) score = txt
                    }
                    val votes = a.selectFirst("span.text-muted")?.text()?.trim()
                    val displayTitle = when {
                        score != null && votes != null -> "$title • ★$score ($votes)"
                        score != null -> "$title • ★$score"
                        else -> title
                    }
                    home.add(
                        newAnimeSearchResponse(displayTitle, resolveUrl(href), TvType.Anime) {
                            this.posterUrl = poster
                            addDubStatus(DubStatus.Subbed)
                        }
                    )
                }
                hasNext = false
            }

            url.contains("/series?") -> {
                val pageUrl = if (page > 1) "$mainUrl/series?page=$page&sort=latest" else "$mainUrl/series?sort=latest"
                val doc = app.get(pageUrl, timeout = 60).document
                doc.select("a.poster-card[href^='/series/']").forEach { a ->
                    val href = a.attr("href")
                    val title = a.selectFirst("img")?.attr("alt")?.trim()
                        ?: a.selectFirst("p.font-black")?.text()?.trim()
                        ?: href.substringAfterLast("/")
                    val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                    val epsText = a.select("p.tracking-widest").lastOrNull()?.text()?.trim() ?: ""
                    val lastEpMatch = Regex("""Ep\s*(\d+)""", RegexOption.IGNORE_CASE).find(epsText)
                    val lastEp = lastEpMatch?.groupValues?.get(1)?.toIntOrNull()
                    home.add(
                        newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                            this.posterUrl = poster
                            if (lastEp != null) addDubStatus(DubStatus.Subbed, lastEp)
                            else addDubStatus(DubStatus.Subbed)
                        }
                    )
                }
                hasNext = doc.select("a[href*='/series?page=']:last-child").isNotEmpty() ||
                           doc.select("a:contains(Sig)").isNotEmpty()
            }

            url.contains("/peliculas?") -> {
                val pageUrl = if (page > 1) "$mainUrl/peliculas?page=$page&sort=newest" else "$mainUrl/peliculas?sort=newest"
                val doc = app.get(pageUrl, timeout = 60).document
                doc.select("a.poster-card[href^='/peliculas/']").forEach { a ->
                    val href = a.attr("href")
                    val title = a.selectFirst("img")?.attr("alt")?.trim()
                        ?: a.selectFirst("p.font-black")?.text()?.trim()
                        ?: href.substringAfterLast("/")
                    val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                    home.add(
                        newAnimeSearchResponse(title, resolveUrl(href), TvType.AnimeMovie) {
                            this.posterUrl = poster
                            addDubStatus(DubStatus.Subbed)
                        }
                    )
                }
                hasNext = doc.select("a[href*='/peliculas?page=']:last-child").isNotEmpty() ||
                           doc.select("a:contains(Sig)").isNotEmpty()
            }
        }

        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
    val results = ArrayList<SearchResponse>()
    val queryLower = query.lowercase().trim()
    if (queryLower.isBlank()) return results

    // Pool para evitar duplicados
    val seenSlugs = mutableSetOf<String>()

    // Helper: extraer y filtrar cards desde un documento
    suspend fun extractCardsFromPage(pageUrl: String, pageType: String) {
        try {
            val doc = app.get(pageUrl, timeout = 30).document
            val selector = if (pageType == "series") {
                "a.poster-card[href^='/series/']"
            } else {
                "a.poster-card[href^='/peliculas/']"
            }
            doc.select(selector).forEach { a ->
                val href = a.attr("href")
                if (href.isBlank()) return@forEach
                val slug = href.substringAfterLast("/")
                if (slug in seenSlugs) return@forEach

                val title = a.selectFirst("img")?.attr("alt")?.trim()
                    ?: a.selectFirst("p.font-black")?.text()?.trim()
                    ?: return@forEach

                // FILTRO CLIENT-SIDE: solo agregar si el título o slug contiene el query
                if (!title.lowercase().contains(queryLower) &&
                    !slug.lowercase().contains(queryLower)) {
                    return@forEach
                }

                seenSlugs.add(slug)
                val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                val tvType = if (pageType == "peliculas") TvType.AnimeMovie else TvType.Anime

                val epsText = a.select("p.tracking-widest").lastOrNull()?.text()?.trim() ?: ""
                val lastEpMatch = Regex("""Ep\s*(\d+)""", RegexOption.IGNORE_CASE).find(epsText)
                val lastEp = lastEpMatch?.groupValues?.get(1)?.toIntOrNull()

                results.add(
                    newAnimeSearchResponse(title, resolveUrl(href), tvType) {
                        this.posterUrl = poster
                        if (lastEp != null) addDubStatus(DubStatus.Subbed, lastEp)
                        else addDubStatus(DubStatus.Subbed)
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // v18 FIX BÚSQUEDA: el sitio soporta búsqueda server-side con ?q= (verificado:
    // /series?q=eternal+god devuelve exactamente "Eternal God Emperor"). Antes se
    // escaneaban solo 5 páginas (125 series de ~1000+) y EGE quedaba fuera.
    val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")

    // Búsqueda server-side en series y películas
    extractCardsFromPage("$mainUrl/series?q=$encodedQuery", "series")
    extractCardsFromPage("$mainUrl/peliculas?q=$encodedQuery", "peliculas")

    // Si la búsqueda server-side no dio nada (p. ej. el server ignoró q=),
    // hacer fallback a un escaneo amplio de las primeras páginas con filtro client-side
    if (results.isEmpty()) {
        for (p in 1..8) {
            val url = if (p == 1) "$mainUrl/series?sort=latest" else "$mainUrl/series?page=$p&sort=latest"
            extractCardsFromPage(url, "series")
            if (results.size >= 30) break
        }
        for (p in 1..3) {
            val url = if (p == 1) "$mainUrl/peliculas?sort=newest" else "$mainUrl/peliculas?page=$p&sort=newest"
            extractCardsFromPage(url, "peliculas")
            if (results.size >= 50) break
        }
    }

    return results
}

    override suspend fun load(url: String): LoadResponse {
        val isMovie = url.contains("/peliculas/")
        val isWatch = url.contains("/watch/")
        val isSeries = url.contains("/series/")

        val seriesUrl = if (isWatch) {
            val path = url.substringAfter("/watch/")
            // v17 FIX: el sufijo de temporada es opcional — Eternal God Emperor usa URLs
            // como "...-temporada-1-5" (sin guión entre "temporada-1" y el episodio).
            // Antes este regex exigía dos guiones y devolvía un slug corrupto ("...-1").
            val match = Regex("""^(.+?)(?:-(\d+))??-(\d+)$""").find(path)
            if (match != null) {
                val slug = match.groupValues[1]
                val candidate = "$mainUrl/series/$slug"
                // v16 FIX: cuando la temporada usa slug UUID (p.ej. Eternal God Emperor:
                // "9796b713-...-temporada-1"), /series/<slug> responde "Serie no encontrada".
                // En ese caso usar la propia página watch, que contiene la lista completa.
                val probe = try { app.get(candidate, timeout = 30) } catch (_: Exception) { null }
                if (probe != null && probe.isSuccessful &&
                    !probe.text.contains("no encontrada", ignoreCase = true) &&
                    probe.text.contains("\"seasons\":")) {
                    candidate
                } else {
                    url
                }
            } else {
                url
            }
        } else {
            url
        }

        val doc = app.get(seriesUrl, timeout = 60).document
        val html = doc.html()
        val rscPayload = extractRscPayload(html)

        val jsonLdPattern = Regex(
            """<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val jsonLdMatch = jsonLdPattern.find(html)
        var jsonLdName = ""
        var jsonLdDescription = ""
        var jsonLdImage = ""
        var jsonLdDate = ""
        var jsonLdStudio = ""
        var jsonLdGenres = listOf<String>()
        var jsonLdNumEps = 0
        var jsonLdNumSeasons = 0
        if (jsonLdMatch != null) {
            try {
                val json = parseJson<JsonLdMeta>(jsonLdMatch.groupValues[1].trim())
                jsonLdName = json.name ?: ""
                jsonLdDescription = json.description ?: ""
                jsonLdImage = json.image ?: ""
                jsonLdDate = json.datePublished?.substringBefore("T") ?: ""
                jsonLdStudio = json.productionCompany?.name?.trim() ?: ""
                jsonLdGenres = json.genre ?: emptyList()
                jsonLdNumEps = json.numberOfEpisodes ?: 0
                jsonLdNumSeasons = json.numberOfSeasons ?: 0
            } catch (_: Exception) {}
        }

        val title = jsonLdName.ifBlank {
            doc.selectFirst("h1")?.text()?.trim() ?: ""
        }
        val description = jsonLdDescription.ifBlank {
            doc.selectFirst("[class*=description i], [class*=synopsis i]")?.text()?.trim() ?: ""
        }
        val poster = if (jsonLdImage.isNotBlank()) {
            resolveUrl(jsonLdImage)
        } else {
            doc.selectFirst("img[alt]")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) } ?: ""
        }
        val genres = ArrayList<String>(jsonLdGenres)
        if (jsonLdStudio.isNotBlank() && jsonLdStudio !in genres) genres.add(jsonLdStudio)

        var showStatus: ShowStatus? = null
        var releaseDateStr = ""
        var durationMinutes = 0

        val pillSpans = doc.select("span.text-xs.font-black.uppercase.tracking-widest")
        for (span in pillSpans) {
            val text = span.text().trim()
            if (text.isBlank()) continue
            when {
                text.contains("En Emisión", ignoreCase = true) ||
                text.contains("En Emision", ignoreCase = true) ||
                text.contains("Pausa", ignoreCase = true) ||
                text.contains("Ongoing", ignoreCase = true) -> {
                    showStatus = ShowStatus.Ongoing
                }
                text.contains("Finalizado", ignoreCase = true) ||
                text.contains("Completed", ignoreCase = true) -> {
                    showStatus = ShowStatus.Completed
                }
                Regex("""\d+\s+de?\s*[A-Za-záéíóú]+,?\s+\d{4}""").matches(text) ||
                Regex("""\d+\s+[A-Za-záéíóú]+,?\s+\d{4}""").matches(text) -> {
                    releaseDateStr = text
                }
                text.startsWith("Duración", ignoreCase = true) ||
                text.startsWith("Duracion", ignoreCase = true) -> {
                    val numMatch = Regex("""(\d+)""").find(text)
                    numMatch?.groupValues?.get(1)?.toIntOrNull()?.let { durationMinutes = it }
                }
            }
        }

        val yearInt = releaseDateStr.substringAfterLast(",").trim().substringBefore(" ").toIntOrNull()
            ?: releaseDateStr.substringAfterLast(" ").trim().toIntOrNull()
            ?: jsonLdDate.takeIf { it.isNotBlank() }?.substring(0, 4)?.toIntOrNull()

        val metaLines = ArrayList<String>()
        if (showStatus != null) {
            val statusText = when (showStatus) {
                ShowStatus.Ongoing -> "En Emisión"
                ShowStatus.Completed -> "Finalizado"
            }
            metaLines.add("Estado: $statusText")
        }
        if (releaseDateStr.isNotBlank()) metaLines.add("Fecha: $releaseDateStr")
        if (durationMinutes > 0) metaLines.add("Duración: ${durationMinutes}m")
        val ratingScore = extractRatingScore(html, rscPayload)
        if (ratingScore.isNotBlank()) metaLines.add("Puntuación: $ratingScore")
        val metaBlock = if (metaLines.isNotEmpty()) {
            metaLines.joinToString("\n") + "\n\n"
        } else ""
        val fullPlot = metaBlock + description

        if (isMovie) {
            val movieId = extractContentIdFromPayload(rscPayload, "movieId") ?: ""
            val dataUrl = if (movieId.isNotBlank()) {
                "$seriesUrl##movieId=$movieId"
            } else {
                seriesUrl
            }
            return newMovieLoadResponse(title, dataUrl, TvType.AnimeMovie, dataUrl) {
                posterUrl = poster
                plot = fullPlot
                tags = genres
                year = yearInt
                if (durationMinutes > 0) this.duration = durationMinutes
                showStatus = showStatus
            }
        }

        val episodes = ArrayList<Episode>()
        val seasons = extractSeasonsFromPayload(rscPayload)

        val regularSeasons = seasons.filter { !it.isSpecial }
        val specialSeasons = seasons.filter { it.isSpecial }

        for ((idx, season) in regularSeasons.withIndex()) {
            val seasonNum = idx + 1
            val seasonSlug = season.slug
            val episodeCount = season.episodeCount
            val epNumbersToUse = if (season.episodeNumbers.isNotEmpty()) {
                season.episodeNumbers
            } else {
                val start = season.firstEpNumber.takeIf { it > 0 } ?: 1
                (0 until episodeCount).map { start + it }
            }

            for (epNum in epNumbersToUse) {
                val epUrl = "$mainUrl/watch/$seasonSlug-$epNum"
                episodes.add(
                    newEpisode(epUrl) {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = "Episodio $epNum"
                    }
                )
            }
        }

        for (season in specialSeasons) {
            val seasonSlug = season.slug
            val episodeCount = season.episodeCount
            val epNumbersToUse = if (season.episodeNumbers.isNotEmpty()) {
                season.episodeNumbers
            } else {
                val start = season.firstEpNumber.takeIf { it > 0 } ?: 1
                (0 until episodeCount).map { start + it }
            }
            for (epNum in epNumbersToUse) {
                val epUrl = "$mainUrl/watch/$seasonSlug-$epNum"
                episodes.add(
                    newEpisode(epUrl) {
                        this.season = 0
                        this.episode = epNum
                        this.name = "Especial $epNum"
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            // v16: el DOM del watch page lista TODOS los episodios de la temporada
            doc.select("a[href*='/watch/']").forEach { a ->
                val href = a.attr("href")
                val match = Regex("""/watch/(.+)-(\d+)-(\d+)$""").find(href)
                if (match != null) {
                    val seasonNum = match.groupValues[2].toIntOrNull() ?: 1
                    val epNum = match.groupValues[3].toIntOrNull() ?: return@forEach
                    if (episodes.any { it.episode == epNum && it.season == seasonNum }) return@forEach
                    val epTitle = a.selectFirst("img")?.attr("alt")?.trim()
                    episodes.add(
                        newEpisode(resolveUrl(href)) {
                            this.season = seasonNum
                            this.episode = epNum
                            if (epTitle != null) this.name = epTitle
                        }
                    )
                }
            }
        }

        return newAnimeLoadResponse(title, seriesUrl, TvType.Anime) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes.sortedWith(compareBy({ it.season }, { it.episode })))
            showStatus = showStatus
            plot = fullPlot
            tags = genres
            year = yearInt
            if (durationMinutes > 0) this.duration = durationMinutes
        }
    }

    private fun extractRatingScore(html: String, rscPayload: String): String {
        val patterns = listOf(
            Regex(""""rating"\s*:\s*(\d+\.?\d*)"""),
            Regex(""""score"\s*:\s*(\d+\.?\d*)"""),
            Regex(""""averageRating"\s*:\s*(\d+\.?\d*)"""),
            Regex(""""ratingValue"\s*:\s*(\d+\.?\d*)"""),
            Regex(""""puntuacion"\s*:\s*(\d+\.?\d*)"""),
        )
        for (p in patterns) {
            val m = p.find(rscPayload)
            if (m != null) {
                val value = m.groupValues[1]
                val asDouble = value.toDoubleOrNull() ?: continue
                if (asDouble in 0.0..10.0) {
                    return "$value/10 votos"
                }
            }
        }
        return ""
    }

    private fun extractSeasonsFromPayload(payload: String): List<SeasonMeta> {
        val seasons = ArrayList<SeasonMeta>()
        val seasonsStart = payload.find("\"seasons\":[")
        if (seasonsStart < 0) return seasons

        var depth = 0
        var i = seasonsStart + "\"seasons\":[".length
        val start = i
        while (i < payload.length) {
            when (payload[i]) {
                '[' -> depth++
                ']' -> { if (depth == 0) break else depth-- }
            }
            i++
        }
        val seasonsArrayStr = payload.substring(start, i)

        val seasonPattern = Regex(
            """\{"id":"[^"]+","slug":"([^"]+)","label":"([^"]+)","coverImage":"[^"]*","isSpecial":(true|false),"episodeCount":(\d+)"""
        )
        for (m in seasonPattern.findAll(seasonsArrayStr)) {
            val slug = m.groupValues[1]
            val label = m.groupValues[2]
            val isSpecial = m.groupValues[3] == "true"
            val countStr = m.groupValues[4]
            val episodeCount = countStr.toIntOrNull() ?: 0

            val nextSeasonStart = seasonsArrayStr.indexOf(
                "\"slug\":\"", m.range.last
            ).let { if (it < 0 || it <= m.range.first) seasonsArrayStr.length else it }
            val seasonBlock = seasonsArrayStr.substring(m.range.first, nextSeasonStart)
            val initialEpStart = seasonBlock.find("\"initialEpisodes\":[")
            val episodeNumbers = if (initialEpStart >= 0) {
                val searchStart = initialEpStart + "\"initialEpisodes\":[".length
                var depth = 0
                var endIdx = searchStart
                var j = searchStart
                while (j < seasonBlock.length) {
                    when (seasonBlock[j]) {
                        '[' -> depth++
                        ']' -> { if (depth == 0) { endIdx = j; break } else depth-- }
                    }
                    j++
                }
                val initialEpArrayStr = seasonBlock.substring(searchStart, endIdx)
                Regex(""""number":(\d+)""")
                    .findAll(initialEpArrayStr)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .toList()
            } else emptyList()
            val firstEpNumber = episodeNumbers.firstOrNull() ?: 0

            seasons.add(
                SeasonMeta(
                    slug = slug,
                    label = label,
                    episodeCount = episodeCount,
                    isSpecial = isSpecial,
                    firstEpNumber = firstEpNumber,
                    episodeNumbers = episodeNumbers,
                )
            )
        }
        return seasons
    }

    private fun String.find(needle: String): Int = this.indexOf(needle)
    private fun String.find(needle: String, startIndex: Int): Int = this.indexOf(needle, startIndex)

    private fun extractContentIdFromPayload(payload: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""", RegexOption.IGNORE_CASE)
        return pattern.find(payload)?.groupValues?.get(1)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (cleanUrl, preloadedContentId) = if (data.contains("##")) {
            val parts = data.split("##")
            val cid = parts.getOrNull(1)?.substringAfter("=") ?: ""
            parts[0] to cid
        } else {
            data to ""
        }

        var response = app.get(cleanUrl, headers = browserHeaders, timeout = 60)
        var html = response.text
        var rscPayload = extractRscPayload(html)
        Log.i(TAG, "loadLinks url=$cleanUrl htmlLen=${html.length} rscLen=${rscPayload.length} " +
            "hasActiveEpId=${rscPayload.contains("\"activeEpisodeId\":")} " +
            "hasSources=${rscPayload.contains("\"sources\":[")} " +
            "hasTokens=${rscPayload.contains("\"token\":")} " +
            "hasNextF=${html.contains("self.__next_f")} " +
            "hasCloudflare=${html.contains("cloudflare") || html.contains("cf-")} " +
            "httpCode=${response.code}")

        if (rscPayload.isNotEmpty() && rscPayload.length < 50000) {
            Log.i(TAG, "loadLinks BOT_DETECTED: RSC reduced (${rscPayload.length} chars), " +
                "skipping retries and JS_ANALYZE, going directly to WebView fallback")
        }

        var webViewCaptured: String = ""
        if (rscPayload.isNotEmpty() && rscPayload.length < 50000) {
            val webViewResult = tryWebViewResolver(cleanUrl, "loadLinks")
            if (webViewResult != null) {
                val (renderedHtml, capturedJson) = webViewResult
                webViewCaptured = capturedJson
                val webViewRsc = extractRscPayload(renderedHtml)
                Log.i(TAG, "loadLinks v14 WEBVIEW RSC: rscLen=${webViewRsc.length} " +
                    "hasActiveEpId=${webViewRsc.contains("\"activeEpisodeId\":")} " +
                    "hasSources=${webViewRsc.contains("\"sources\":[")}")
                if (webViewRsc.length > rscPayload.length) {
                    val oldRscLen = rscPayload.length
                    html = renderedHtml
                    rscPayload = webViewRsc
                    Log.i(TAG, "loadLinks v14 WEBVIEW: using WebView RSC " +
                        "(was $oldRscLen, now ${webViewRsc.length})")
                }
                if (capturedJson.isNotEmpty()) {
                    try {
                        val captured = parseJson<CapturedWebViewData>(capturedJson)
                        val nextF = captured.next_f ?: ""
                        if (nextF.length > 1000) {
                            Log.i(TAG, "loadLinks v14 WEBVIEW: appending captured next_f " +
                                "(len=${nextF.length}) to rscPayload")
                            rscPayload = rscPayload + "\n" + nextF
                            Log.i(TAG, "loadLinks v14 WEBVIEW: rscPayload now len=${rscPayload.length} " +
                                "hasSources=${rscPayload.contains("\"sources\":[")} " +
                                "hasActiveEpId=${rscPayload.contains("\"activeEpisodeId\":")}")
                        }
                    } catch (e: Exception) {
                        Log.i(TAG, "loadLinks v14 WEBVIEW: parse captured for next_f error: ${e.message}")
                    }
                }
            }
        }

        val isMovie = cleanUrl.contains("/peliculas/")

        if (isMovie) {
            return loadMovieLinks(cleanUrl, preloadedContentId, rscPayload, html, webViewCaptured, subtitleCallback, callback)
        } else {
            return loadEpisodeLinks(cleanUrl, rscPayload, html, webViewCaptured, subtitleCallback, callback)
        }
    }
    // ========== EPISODE / MOVIE LINK LOADING ==========

    private suspend fun loadEpisodeLinks(
        url: String,
        rscPayload: String,
        html: String,
        webViewCaptured: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logKey = "[ep#${rscPayload.hashCode().and(0xFFFF)}]"
        val activeEpIdMatch = Regex(""""activeEpisodeId":"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""")
            .find(rscPayload)
        val activeEpId = activeEpIdMatch?.groupValues?.get(1) ?: ""
        Log.i(TAG, "$logKey loadEpisodeLinks url=$url activeEpId=$activeEpId rscSize=${rscPayload.length} htmlLen=${html.length} webViewCapturedLen=${webViewCaptured.length}")

        if (webViewCaptured.isNotEmpty() && rscPayload.length < 50000) {
            Log.i(TAG, "$logKey v16 FAST PATH: bot detection + WebView data available, emitting directly (episode-filtered)")
            val emitted = emitFromWebViewCaptured(webViewCaptured, url, logKey, subtitleCallback, callback)
            if (emitted) {
                Log.i(TAG, "$logKey FINAL anyEmitted=true (v15 fast path via WebView)")
                return true
            }
            Log.i(TAG, "$logKey v15 FAST PATH: WebView emit failed, falling through to v9/v11 strategies")
        }

        if (rscPayload.length < 50000) {
            Log.i(TAG, "$logKey v12 HTML_SCAN: searching servers[] in raw HTML (${html.length} chars)")
            val htmlServers = extractServersFromHtml(html, logKey)
            if (htmlServers.isNotEmpty()) {
                Log.i(TAG, "$logKey v12 HTML_SCAN found ${htmlServers.size} servers in HTML, emitting")
                val emitted = emitEpisodeServers(htmlServers, url, subtitleCallback, callback)
                if (emitted) {
                    Log.i(TAG, "$logKey v12 HTML_SCAN emitted=true, skipping API calls")
                    Log.i(TAG, "$logKey FINAL anyEmitted=true")
                    return true
                }
            } else {
                Log.i(TAG, "$logKey v12 HTML_SCAN: no servers found in HTML")
            }
        }

        var anyEmitted = false

        // PRIORIZAR sources con token ANTES que servers directos
        // v17: resolver cada token con la API real del sitio (POST /api/player/source)
        if (activeEpId.isNotBlank()) {
            val epSources = extractSourcesNearEpisode(rscPayload, activeEpId)
            Log.i(TAG, "$logKey epSources with tokens: ${epSources.size} labels=[${epSources.joinToString(",") { it.label }}]")
            for (src in epSources) {
                if (src.token.isBlank()) continue
                val resolvedUrl = resolveTokenViaPlayerApi(src.token, url, logKey)
                if (resolvedUrl.isBlank()) continue
                if (emitEpisodeServers(listOf(src.label to resolvedUrl), url, subtitleCallback, callback)) {
                    anyEmitted = true
                }
            }
        }

        // Ahora intentar servers directos (si lo anterior no emitió)
        if (!anyEmitted && activeEpId.isNotBlank()) {
            val epServers = extractServersForEpisode(rscPayload, activeEpId)
            Log.i(TAG, "$logKey direct servers: ${epServers.size} [${epServers.joinToString(",") { "${it.first}:${it.second.take(40)}" }}]")
            if (epServers.isNotEmpty()) {
                val emitted = emitEpisodeServers(epServers, url, subtitleCallback, callback)
                Log.i(TAG, "$logKey emitEpisodeServers emitted=$emitted")
                if (emitted) anyEmitted = true
            }
        }

        // Fallback por número (si activeEpId no existe)
        if (!anyEmitted) {
            val urlPath = url.substringAfter("/watch/", "")
            val urlMatch = Regex("""^(.+)-(\d+)-(\d+)$""").find(urlPath)
            if (urlMatch != null) {
                val seasonSlug = urlMatch.groupValues[1]
                val epNum = urlMatch.groupValues[3].toIntOrNull() ?: 0
                if (epNum > 0) {
                    var epServers = extractServersByNumber(rscPayload, seasonSlug, epNum)
                    if (epServers.isEmpty()) {
                        // v16: match por título "Capítulo N" / "episodio N" / "Especial N"
                        epServers = extractServersByEpisodeTitle(rscPayload, epNum)
                    }
                    if (epServers.isNotEmpty()) {
                        val emitted = emitEpisodeServers(epServers, url, subtitleCallback, callback)
                        if (emitted) anyEmitted = true
                    }
                }
            } else {
                // v16: URL sin sufijo numérico → usar el episodio activo de la página
                val activeServers = extractActiveEpisodeServers(rscPayload)
                if (activeServers.isNotEmpty()) {
                    val emitted = emitEpisodeServers(activeServers, url, subtitleCallback, callback)
                    if (emitted) anyEmitted = true
                }
            }
        }

        // v17: API REAL del sitio — POST /api/player/source {"token"} → {"url": ...}
        // (/api/sources y sus variantes devuelven siempre datos mock de example.com)
        if (!anyEmitted) {
            Log.i(TAG, "$logKey v17: trying real site API /api/player/source")
            val allSources = extractAllSourcesFromRsc(rscPayload)
            for ((src, _) in allSources) {
                if (src.token.isBlank()) continue
                val resolvedUrl = resolveTokenViaPlayerApi(src.token, url, logKey)
                if (resolvedUrl.isNotBlank()) {
                    if (emitEpisodeServers(listOf(src.label to resolvedUrl), url, subtitleCallback, callback)) {
                        anyEmitted = true
                    }
                }
            }
        }

        if (!anyEmitted && webViewCaptured.isNotEmpty()) {
            Log.i(TAG, "$logKey v14 WEBVIEW FALLBACK: trying to emit from captured WebView data")
            val emitted = emitFromWebViewCaptured(webViewCaptured, url, logKey, subtitleCallback, callback)
            if (emitted) {
                anyEmitted = true
                Log.i(TAG, "$logKey FINAL anyEmitted=true (via WebView captured)")
            }
        }

        Log.i(TAG, "$logKey FINAL anyEmitted=$anyEmitted")
        return anyEmitted
    }

    private suspend fun loadMovieLinks(
        url: String,
        preloadedContentId: String,
        rscPayload: String,
        html: String,
        webViewCaptured: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logKey = "[mv#${rscPayload.hashCode().and(0xFFFF)}]"
        val movieId = if (preloadedContentId.isNotBlank()) preloadedContentId
            else extractContentIdFromPayload(rscPayload, "movieId") ?: ""
        Log.i(TAG, "$logKey loadMovieLinks url=$url movieId=$movieId rscSize=${rscPayload.length} htmlLen=${html.length} webViewCapturedLen=${webViewCaptured.length}")

        if (webViewCaptured.isNotEmpty() && rscPayload.length < 50000) {
            Log.i(TAG, "$logKey v15 FAST PATH: bot detection + WebView data available, emitting directly")
            val emitted = emitFromWebViewCaptured(webViewCaptured, url, logKey, subtitleCallback, callback)
            if (emitted) {
                Log.i(TAG, "$logKey FINAL anyEmitted=true (v15 fast path via WebView)")
                return true
            }
            Log.i(TAG, "$logKey v15 FAST PATH: WebView emit failed, falling through to v9/v11 strategies")
        }

        if (rscPayload.length < 50000) {
            Log.i(TAG, "$logKey v12 HTML_SCAN: searching servers[] in raw HTML (${html.length} chars)")
            val htmlServers = extractServersFromHtml(html, logKey)
            if (htmlServers.isNotEmpty()) {
                Log.i(TAG, "$logKey v12 HTML_SCAN found ${htmlServers.size} servers in HTML, emitting")
                val emitted = emitEpisodeServers(htmlServers, url, subtitleCallback, callback)
                if (emitted) {
                    Log.i(TAG, "$logKey v12 HTML_SCAN emitted=true, skipping API calls")
                    Log.i(TAG, "$logKey FINAL anyEmitted=true")
                    return true
                }
            } else {
                Log.i(TAG, "$logKey v12 HTML_SCAN: no servers found in HTML")
            }
        }

        val sources = extractMovieSources(rscPayload)
        Log.i(TAG, "$logKey sources=[${sources.joinToString(",") { "${it.label}/${it.type}/${it.provider}" }}]")
        if (movieId.isBlank() && sources.isEmpty()) return false

        var anyEmitted = false

        // v17: API REAL del sitio — POST /api/player/source {"token"} → {"url": ...}
        if (!anyEmitted) {
            Log.i(TAG, "$logKey v17: trying real site API /api/player/source (movie)")
            for (source in sources) {
                if (source.token.isBlank()) continue
                val resolvedUrl = resolveTokenViaPlayerApi(source.token, url, logKey)
                if (resolvedUrl.isNotBlank()) {
                    if (emitEpisodeServers(listOf(source.label to resolvedUrl), url, subtitleCallback, callback)) {
                        anyEmitted = true
                    }
                }
            }
        }

        if (!anyEmitted && webViewCaptured.isNotEmpty()) {
            Log.i(TAG, "$logKey v14 WEBVIEW FALLBACK: trying to emit from captured WebView data")
            val emitted = emitFromWebViewCaptured(webViewCaptured, url, logKey, subtitleCallback, callback)
            if (emitted) {
                anyEmitted = true
                Log.i(TAG, "$logKey FINAL anyEmitted=true (via WebView captured)")
            }
        }

        Log.i(TAG, "$logKey FINAL anyEmitted=$anyEmitted")
        return anyEmitted
    }

    // ========== SOURCE EXTRACTION HELPERS ==========

    private fun extractSourcesNearEpisode(payload: String, episodeId: String): List<MovieSource> {
        val sources = ArrayList<MovieSource>()
        val marker = ""","$episodeId",{"sources":["""
        val markerPos = payload.find(marker)
        if (markerPos < 0) return sources

        val arrayStart = markerPos + marker.length
        var depth = 0
        var i = arrayStart
        while (i < payload.length) {
            when (payload[i]) {
                '[' -> depth++
                ']' -> { if (depth == 0) break else depth-- }
            }
            i++
        }
        val sourcesArrayStr = payload.substring(arrayStart, i)

        val sourcePattern = Regex(
            """\{"id":"([^"]+)","label":"([^"]+)","name":"([^"]+)","token":"([^"]+)","type":"([^"]+)","provider":"([^"]+)""""
        )
        for (m in sourcePattern.findAll(sourcesArrayStr)) {
            sources.add(
                MovieSource(
                    id = m.groupValues[1],
                    label = m.groupValues[2],
                    name = m.groupValues[3],
                    token = m.groupValues[4],
                    type = m.groupValues[5],
                    provider = m.groupValues[6],
                )
            )
        }
        return sources
    }

    private fun extractMovieSources(payload: String): List<MovieSource> {
        val sources = ArrayList<MovieSource>()
        val sourcesStart = payload.find("\"sources\":[")
        if (sourcesStart < 0) return sources

        val arrayStart = sourcesStart + "\"sources\":[".length
        var depth = 0
        var i = arrayStart
        while (i < payload.length) {
            when (payload[i]) {
                '[' -> depth++
                ']' -> { if (depth == 0) break else depth-- }
            }
            i++
        }
        val sourcesArrayStr = payload.substring(arrayStart, i)

        val sourcePattern = Regex(
            """\{"id":"([^"]+)","label":"([^"]+)","name":"([^"]+)","token":"([^"]+)","type":"([^"]+)","provider":"([^"]+)""""
        )
        for (m in sourcePattern.findAll(sourcesArrayStr)) {
            sources.add(
                MovieSource(
                    id = m.groupValues[1],
                    label = m.groupValues[2],
                    name = m.groupValues[3],
                    token = m.groupValues[4],
                    type = m.groupValues[5],
                    provider = m.groupValues[6],
                )
            )
        }
        return sources
    }

    private fun extractAllSourcesFromRsc(payload: String): List<Pair<MovieSource, String>> {
        val result = ArrayList<Pair<MovieSource, String>>()
        val sourcePattern = Regex(
            """\{"id":"([^"]+)","label":"([^"]+)","name":"([^"]+)","token":"([^"]+)","type":"([^"]+)","provider":"([^"]+)""""
        )
        for (m in sourcePattern.findAll(payload)) {
            val src = MovieSource(
                id = m.groupValues[1],
                label = m.groupValues[2],
                name = m.groupValues[3],
                token = m.groupValues[4],
                type = m.groupValues[5],
                provider = m.groupValues[6],
            )
            val contentId = src.deriveContentId()
            if (contentId.isNotBlank() && src.token.isNotBlank()) {
                result.add(src to contentId)
            }
        }
        return result
    }

    private fun extractServersForEpisode(payload: String, episodeId: String): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        val idPos = payload.find("\"id\":\"$episodeId\"")
        if (idPos < 0) return servers

        val serversStart = payload.find("\"servers\":[", idPos)
        if (serversStart < 0) return servers

        val arrayStart = serversStart + "\"servers\":[".length
        var depth = 0
        var i = arrayStart
        while (i < payload.length) {
            when (payload[i]) {
                '[' -> depth++
                ']' -> { if (depth == 0) break else depth-- }
            }
            i++
        }
        val serversArrayStr = payload.substring(arrayStart, i)

        val serverEntryPattern = Regex("""\{"name":"([^"]+)","url":"([^"]+)"\}""")
        for (m in serverEntryPattern.findAll(serversArrayStr)) {
            val rawName = m.groupValues[1]
            val rawUrl = m.groupValues[2]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
            servers.add(rawName to rawUrl)
        }
        return servers
    }

    private fun extractServersByNumber(payload: String, seasonSlug: String, epNum: Int): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        val epPattern = Regex(
            """"seasonSlug":"\Q$seasonSlug\E"[^}]*?"number":$epNum,[^}]*?"servers":\[([^\]]+)\]"""
        )
        val m = epPattern.find(payload) ?: return servers
        val serversArrayStr = m.groupValues[1]
        val serverEntryPattern = Regex("""\{"name":"([^"]+)","url":"([^"]+)"\}""")
        for (sm in serverEntryPattern.findAll(serversArrayStr)) {
            val rawName = sm.groupValues[1]
            val rawUrl = sm.groupValues[2]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
            servers.add(rawName to rawUrl)
        }
        return servers
    }

    // v16: busca servers por título del episodio ("Capítulo N", "episodio N", "Especial N")
    private fun extractServersByEpisodeTitle(payload: String, epNum: Int): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        val titlePattern = Regex(
            """\"title\":\"(?:Capítulo|capítulo|Episodio|episodio|Especial|especial)\s*0*$epNum\"[^}]*?\"servers\":\[([^\]]+)\]"""
        )
        val m = titlePattern.find(payload) ?: return servers
        val serverEntryPattern = Regex("""\{"name":"([^"]+)","url":"([^"]+)"\}""")
        for (sm in serverEntryPattern.findAll(m.groupValues[1])) {
            val rawUrl = sm.groupValues[2]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
            servers.add(sm.groupValues[1] to rawUrl)
        }
        if (servers.isNotEmpty()) Log.i(TAG, "v16: extractServersByEpisodeTitle ep=$epNum encontró ${servers.size} servers")
        return servers
    }

    // v16: servers del episodio activo (usar cuando la URL no tiene sufijo numérico)
    private fun extractActiveEpisodeServers(payload: String): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        val activeId = Regex("""\"activeEpisodeId\":\"([0-9a-fA-F-]{36})\"""").find(payload)?.groupValues?.get(1)
            ?: return servers
        val idPos = payload.find("\"id\":\"$activeId\"")
        if (idPos < 0) return servers
        val serversStart = payload.find("\"servers\":[", idPos)
        if (serversStart < 0) return servers
        val arrayStart = serversStart + "\"servers\":[".length
        var depth = 0
        var i = arrayStart
        while (i < payload.length) {
            when (payload[i]) {
                '[' -> depth++
                ']' -> { if (depth == 0) break else depth-- }
            }
            i++
        }
        val serversArrayStr = payload.substring(arrayStart, i)
        val serverEntryPattern = Regex("""\{"name":"([^"]+)","url":"([^"]+)"\}""")
        for (m in serverEntryPattern.findAll(serversArrayStr)) {
            val rawUrl = m.groupValues[2]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
            servers.add(m.groupValues[1] to rawUrl)
        }
        return servers
    }

    // v16: deduce el número de episodio de una URL de página watch (no de embeds)
    private fun episodeNumberFromUrl(url: String): Int? {
        return Regex("""/watch/[^?]*?-(\d+)-(\d+)(?:[?#].*)?$""").find(url)?.groupValues?.get(2)?.toIntOrNull()
            ?: Regex("""-(\d+)-(\d+)$""").find(url)?.groupValues?.get(2)?.toIntOrNull()
    }

    // v16: extrae el servers[] del episodio con número dado desde texto RSC/JSON crudo
    private fun extractNumberedServersFromText(text: String, epNum: Int, logKey: String): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        if (text.isBlank()) return servers
        val numberPattern = Regex("""\\?"number\\?":(\d+)""")
        val matches = numberPattern.findAll(text).toList()
        for ((idx, nm) in matches.withIndex()) {
            if (nm.groupValues[1].toIntOrNull() != epNum) continue
            val segStart = nm.range.last + 1
            val segEnd = matches.getOrNull(idx + 1)?.range?.first ?: text.length
            val segment = text.substring(segStart, segEnd)
            val svPos = segment.indexOf("servers")
            if (svPos < 0) continue
            val arrStart = segment.indexOf('[', svPos)
            if (arrStart < 0) continue
            var depth = 0
            var j = arrStart
            var endIdx = -1
            while (j < segment.length) {
                when (segment[j]) {
                    '[' -> depth++
                    ']' -> { if (depth == 0) { endIdx = j; break } else depth-- }
                }
                j++
            }
            if (endIdx < 0) continue
            val block = segment.substring(arrStart + 1, endIdx)
            val entryPattern = Regex("""\{\\?"name\\?":"([^"\\]+)\\?",\\?"url\\?":"([^"\\]+)\\?"\}""")
            for (em in entryPattern.findAll(block)) {
                val u = em.groupValues[2]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\\"", "\"")
                val ok = (u.contains("dailymotion.com") || u.contains("rumble.com") || u.contains("ok.ru") ||
                        u.contains("vk.com") || u.contains("vkvideo") || u.contains("vk.ru") ||
                        u.contains("streamable.com") || u.contains("vidhide") || u.contains("morencius.com") ||
                        u.contains("voe.sx") || u.contains("filemoon") || u.contains(".mp4") || u.contains(".m3u8")) &&
                        !u.contains("/video/example") && !u.contains("cdn.example.com") && u.length >= 20
                if (ok && servers.none { it.second == u }) {
                    servers.add(em.groupValues[1].trim() to u)
                    Log.i(TAG, "$logKey v16 NUM_SERVERS ep=$epNum: ${em.groupValues[1]} → ${u.take(70)}")
                }
            }
        }
        return servers
    }


    private fun extractServersFromHtml(html: String, logKey: String): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        val serverEntryPattern = Regex("""\{\\?"name\\?":"([^"\\]+)\\?",\\?"url\\?":"([^"\\]+)\\?"\}""")
        val seen = mutableSetOf<String>()
        for (m in serverEntryPattern.findAll(html)) {
            val rawName = m.groupValues[1]
            val rawUrl = m.groupValues[2]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            val isVideoUrl = rawUrl.contains("dailymotion.com") ||
                    rawUrl.contains("rumble.com") ||
                    rawUrl.contains("ok.ru") ||
                    rawUrl.contains("vk.com") || rawUrl.contains("vk.ru") ||
                    rawUrl.contains("streamable.com") ||
                    rawUrl.contains("voe.sx") ||
                    rawUrl.contains("filemoon") || rawUrl.contains("moonplayer") ||
                    rawUrl.contains("r2.cloudflarestorage") ||
                    rawUrl.contains("hcdn.dev") ||
                    rawUrl.contains("cloudflarestorage") ||
                    rawUrl.endsWith(".mp4") || rawUrl.contains(".mp4") ||
                    rawUrl.endsWith(".m3u8") || rawUrl.contains(".m3u8")
            if (!isVideoUrl) continue
            if (rawUrl.contains("/video/example") || rawUrl.contains("cdn.example.com")) continue
            if (rawUrl.length < 20) continue
            if (seen.contains(rawUrl)) continue
            seen.add(rawUrl)
            servers.add(rawName to rawUrl)
            Log.i(TAG, "$logKey v12 HTML_SCAN found: name=$rawName url=${rawUrl.take(80)}")
        }
        return servers
    }

    // ========== API REAL DEL SITIO (v17) ==========
    // POST /api/player/source con {"token": ...} → {"url": "...", "exp": ...}
    // Verificado en vivo: funciona con tokens de episodios (OK/Rumble/etc.) y de películas.
    // (/api/sources y todas sus variantes siempre devuelven datos mock de example.com)

    private suspend fun resolveTokenViaPlayerApi(
        token: String,
        referer: String,
        logKey: String
    ): String {
        if (token.isBlank()) return ""
        val headers = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
            "Content-Type" to "application/json",
            "Origin" to mainUrl,
            "Referer" to referer,
            "User-Agent" to browserUA,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
        ) + ajaxClientHints
        return try {
            val resp = app.post(
                "$mainUrl/api/player/source",
                json = mapOf<String, Any>("token" to token),
                headers = headers,
                timeout = 30L
            ).text
            Log.i(TAG, "$logKey v17 PLAYER_API respLen=${resp.length} head=${resp.take(200)}")
            if (resp.isBlank() || resp == "{}" || resp.contains("\"error\"") ||
                resp.contains("cdn.example.com") || resp.contains("/video/example")) {
                Log.w(TAG, "$logKey v17 PLAYER_API respuesta mock/error: ${resp.take(200)}")
                ""
            } else {
                val resolvedUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1) ?: ""
                if (resolvedUrl.isNotBlank()) {
                    Log.i(TAG, "$logKey v17 PLAYER_API resuelto: $resolvedUrl")
                }
                resolvedUrl
            }
        } catch (e: Exception) {
            Log.w(TAG, "$logKey v17 PLAYER_API falló: ${e.message}")
            ""
        }
    }


    // ========== EMIT EPISODE SERVERS ==========

    private suspend fun emitEpisodeServers(
        servers: List<Pair<String, String>>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anyEmitted = false
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            anyEmitted = true
            callback(link)
        }
        val cdnHeaders = mapOf(
            "Origin" to mainUrl,
            "User-Agent" to browserUA,
        )
        for ((serverName, serverUrl) in servers) {
            val name = serverName.trim().ifBlank { "Server" }
            val serverUrlFixed = when {
                serverUrl.endsWith(".m3") -> serverUrl + "u8"
                serverUrl.endsWith(".m3u") -> serverUrl + "8"
                serverUrl.contains("chunklist.m3") && !serverUrl.contains("chunklist.m3u8") ->
                    serverUrl.replace("chunklist.m3", "chunklist.m3u8")
                else -> serverUrl
            }
            val normalizedUrl = when {
                serverUrlFixed.contains("ok.ru/videoembed/") ->
                    serverUrlFixed.replace("ok.ru/videoembed/", "www.ok.ru/video/")
                serverUrlFixed.contains("ok.ru") && !serverUrlFixed.contains("www.ok.ru") ->
                    serverUrlFixed.replace("ok.ru", "www.ok.ru")
                else -> serverUrlFixed
            }
            try {
                when {
                    serverUrlFixed.contains("vidhide") || serverUrlFixed.contains("morencius.com") -> {
                        // v16: Vidhide (ads) — morencius.com/vidhide*/embed/xxx
                        val emittedHere = extractVidhide(serverUrlFixed, referer, name, subtitleCallback, trackingCallback)
                        if (!emittedHere) {
                            Log.i(TAG, "v16 Vidhide: extractVidhide failed, trying loadExtractor for $serverUrlFixed")
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (_: Exception) {}
                        }
                    }
                    serverUrlFixed.contains("rumble.com") -> {
                        val emittedHere = extractRumble(serverUrlFixed, referer, name, trackingCallback)
                        if (!emittedHere && !anyEmitted) {
                            Log.i(TAG, "v15 Rumble: custom extractRumble failed, trying loadExtractor fallback for $serverUrlFixed")
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (e: Exception) {
                                Log.w(TAG, "v15 Rumble: loadExtractor also failed: ${e.message}")
                            }
                        }
                    }
                    serverUrlFixed.contains("dailymotion.com") || serverUrlFixed.contains("geo.dailymotion.com") -> {
                        val emittedHere = extractDailymotion(serverUrlFixed, referer, name, trackingCallback)
                        if (!emittedHere && !anyEmitted) {
                            Log.i(TAG, "v15 Dailymotion: custom extractDailymotion failed, trying loadExtractor fallback for $serverUrlFixed")
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (e: Exception) {
                                Log.w(TAG, "v15 Dailymotion: loadExtractor also failed: ${e.message}")
                            }
                        }
                    }
                    serverUrlFixed.contains("streamable.com") -> {
                        extractStreamable(serverUrlFixed, referer, name, subtitleCallback, trackingCallback)
                        if (!anyEmitted) {
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (_: Exception) {}
                        }
                    }
                    // ====== FIX: OK.RU con extractor manual + User-Agent ======
                    serverUrlFixed.contains("ok.ru") -> {
                        if (!extractOkRu(serverUrlFixed, referer, name, trackingCallback)) {
                            loadExtractor(normalizedUrl, referer, subtitleCallback, trackingCallback)
                            if (!anyEmitted) {
                                try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (_: Exception) {}
                            }
                            if (!anyEmitted) {
                                try { extractOkRuDirect(serverUrlFixed, referer, name, trackingCallback) } catch (_: Exception) {}
                            }
                        }
                    }
                    serverUrlFixed.endsWith(".mp4") || serverUrlFixed.endsWith(".m3u8") ||
                    serverUrlFixed.contains("chunklist") || serverUrlFixed.contains("index.m3u8") ||
                    serverUrlFixed.contains("r2.cloudflarestorage") || serverUrlFixed.contains("hcdn.dev") ||
                    serverUrlFixed.contains("donghualife.com/video") ||
                    serverUrlFixed.contains("donghualife.com/episodes") ||
                    serverUrlFixed.contains("donghualife.com/movie") -> {
                        val linkType = if (serverUrlFixed.contains(".m3u8") ||
                                           serverUrlFixed.contains("chunklist") ||
                                           serverUrlFixed.contains("index.m3u8")) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = serverUrlFixed,
                                type = linkType
                            ) {
                                this.referer = referer
                                this.quality = Qualities.Unknown.value
                                this.headers = cdnHeaders
                            }
                        )
                        anyEmitted = true
                    }
                    serverUrlFixed.contains("voe.sx") -> {
                        loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback)
                    }
                    serverUrlFixed.contains("filemoon") || serverUrlFixed.contains("moonplayer") -> {
                        loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback)
                    }
                    else -> {
                        loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback)
                    }
                }
            } catch (_: Exception) {}
        }
        return anyEmitted
    }

    // ========== WEBVIEW RESOLVER ==========

    private suspend fun tryWebViewResolver(
        url: String,
        logKey: String
    ): Pair<String, String>? {
        return try {
            Log.i(TAG, "$logKey v14 WEBVIEW: launching manual WebView for $url")

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
                Log.i(TAG, "$logKey v14 WEBVIEW: no Context available (all 3 strategies failed), cannot use WebView")
                return null
            }
            Log.i(TAG, "$logKey v14 WEBVIEW: Context acquired class=${ctx.javaClass.simpleName}")

            val script = """
                (function() {
                    try {
                        if (!window.__cs3FetchIntercepted) {
                            window.__cs3FetchIntercepted = true;
                            window.__cs3FetchResponses = [];
                            var origFetch = window.fetch;
                            window.fetch = function() {
                                var args = arguments;
                                var fetchUrl = (typeof args[0] === 'string') ? args[0] :
                                               (args[0] && args[0].url) ? args[0].url : '';
                                return origFetch.apply(this, args).then(function(resp) {
                                    try {
                                        if (fetchUrl && (fetchUrl.indexOf('/api/') >= 0 ||
                                            fetchUrl.indexOf('sources') >= 0 ||
                                            fetchUrl.indexOf('embed') >= 0 ||
                                            fetchUrl.indexOf('stream') >= 0)) {
                                            var clone = resp.clone();
                                            clone.text().then(function(txt) {
                                                if (txt && txt.length < 50000) {
                                                    window.__cs3FetchResponses.push({
                                                        url: fetchUrl,
                                                        status: resp.status,
                                                        body: txt
                                                    });
                                                }
                                            }).catch(function(){});
                                            if (fetchUrl && fetchUrl.indexOf('/api/player/source') >= 0) {
                                                try {
                                                    if (!window.__cs3EarlyCaptureFired) {
                                                        window.__cs3EarlyCaptureFired = true;
                                                        setTimeout(function() {
                                                            fireCapture();
                                                        }, 500);
                                                    }
                                                } catch(ee) {}
                                            }
                                        }
                                    } catch(e) {}
                                    return resp;
                                });
                            };
                        }
                    } catch(e) {}

                    function collectCaptured() {
                        var captured = {};
                        var nextF = '';
                        try {
                            if (window.__next_f && window.__next_f.length) {
                                for (var i = 0; i < window.__next_f.length; i++) {
                                    try {
                                        var part = window.__next_f[i];
                                        if (part && part.length >= 2) {
                                            nextF += part[1] + '\n';
                                        }
                                    } catch(e) {}
                                }
                            }
                        } catch(e) {}
                        captured.next_f = nextF.substring(0, 300000);
                        try {
                            if (window.__NEXT_DATA__) {
                                captured.nextData = JSON.stringify(window.__NEXT_DATA__).substring(0, 100000);
                            }
                        } catch(e) {}
                        var videos = [];
                        try {
                            document.querySelectorAll('video').forEach(function(v) {
                                if (v.src) videos.push(v.src);
                                if (v.currentSrc) videos.push(v.currentSrc);
                            });
                            document.querySelectorAll('iframe').forEach(function(i) {
                                if (i.src) videos.push(i.src);
                            });
                            document.querySelectorAll('source').forEach(function(s) {
                                if (s.src) videos.push(s.src);
                            });
                        } catch(e) {}
                        captured.videos = videos;
                        var dataUrls = [];
                        try {
                            document.querySelectorAll('[data-src],[data-url],[data-video],[data-source]').forEach(function(el) {
                                ['data-src','data-url','data-video','data-source'].forEach(function(attr) {
                                    var val = el.getAttribute(attr);
                                    if (val && val.indexOf('http') === 0) dataUrls.push(val);
                                });
                            });
                        } catch(e) {}
                        captured.dataUrls = dataUrls;
                        try {
                            captured.fetchResponses = window.__cs3FetchResponses || [];
                        } catch(e) {
                            captured.fetchResponses = [];
                        }
                        try {
                            captured.html = document.documentElement.outerHTML.substring(0, 500000);
                        } catch(e) {}
                        try {
                            captured.htmlLength = document.documentElement.outerHTML.length;
                        } catch(e) {}
                        return captured;
                    }

                    function fireCapture() {
                        if (window.__cs3Captured) return;
                        window.__cs3Captured = true;
                        try {
                            Android.onCaptured(JSON.stringify(collectCaptured()));
                        } catch(e) {
                            try {
                                var div = document.createElement('div');
                                div.id = 'cs3-captured';
                                div.style.display = 'none';
                                div.textContent = JSON.stringify(collectCaptured());
                                document.body.appendChild(div);
                            } catch(ee) {}
                        }
                    }

                    setTimeout(function() {
                        try {
                            fireCapture();
                        } catch(e) {
                            try {
                                Android.onCaptured('{"error":"' + e.toString().replace(/"/g, '\\"') + '"}');
                            } catch(ee) {}
                        }
                    }, 8000);
                })();
            """.trimIndent()

            var webView: WebView? = null
            val capturedJson = withTimeoutOrNull(20000L) {
                suspendCoroutine<String?> { cont ->
                    Handler(Looper.getMainLooper()).post {
                        try {
                            val wv = WebView(ctx)
                            webView = wv
                            wv.settings.javaScriptEnabled = true
                            wv.settings.domStorageEnabled = true
                            wv.settings.mediaPlaybackRequiresUserGesture = false
                            wv.settings.blockNetworkImage = true

                            var finished = false

                            wv.addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onCaptured(json: String) {
                                    Log.i(TAG, "$logKey v14 WEBVIEW: onCaptured len=${json.length}")
                                    if (!finished) {
                                        finished = true
                                        cont.resume(json)
                                    }
                                }
                            }, "Android")

                            wv.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    Log.i(TAG, "$logKey v14 WEBVIEW: onPageFinished, injecting script")
                                    view?.evaluateJavascript(script) { _ ->
                                        Log.i(TAG, "$logKey v14 WEBVIEW: script injected, waiting 8s for capture...")
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    Log.i(TAG, "$logKey v14 WEBVIEW error: ${error?.description} url=${request?.url}")
                                }
                            }

                            val headers = mapOf(
                                "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8"
                            )
                            wv.loadUrl(url, headers)
                        } catch (e: Exception) {
                            Log.i(TAG, "$logKey v14 WEBVIEW: WebView creation error: ${e.message}")
                            cont.resume(null)
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

            if (capturedJson.isNullOrEmpty()) {
                Log.i(TAG, "$logKey v14 WEBVIEW: no data captured (timeout or error)")
                return null
            }

            Log.i(TAG, "$logKey v14 WEBVIEW CAPTURED len=${capturedJson.length}")

            var renderedHtml = ""
            try {
                val captured = parseJson<CapturedWebViewData>(capturedJson)
                renderedHtml = captured.html ?: ""
                Log.i(TAG, "$logKey v14 WEBVIEW: next_f len=${captured.next_f?.length ?: 0} " +
                    "nextData len=${captured.nextData?.length ?: 0} " +
                    "videos=${captured.videos?.size ?: 0} " +
                    "dataUrls=${captured.dataUrls?.size ?: 0} " +
                    "fetchResponses=${captured.fetchResponses?.size ?: 0} " +
                    "htmlLength=${captured.htmlLength ?: 0} " +
                    "html len=${renderedHtml.length}")
                captured.videos?.take(5)?.forEachIndexed { idx, v ->
                    Log.i(TAG, "$logKey v14 WEBVIEW video[$idx]=$v")
                }
                captured.dataUrls?.take(5)?.forEachIndexed { idx, u ->
                    Log.i(TAG, "$logKey v14 WEBVIEW dataUrl[$idx]=$u")
                }
                captured.fetchResponses?.take(3)?.forEachIndexed { idx, fr ->
                    Log.i(TAG, "$logKey v14 WEBVIEW fetchResp[$idx] url=${fr.url} status=${fr.status} bodyLen=${fr.body?.length ?: 0} bodyHead=${fr.body?.take(150)}")
                }
            } catch (e: Exception) {
                Log.i(TAG, "$logKey v14 WEBVIEW: parse captured error: ${e.message}")
                Log.i(TAG, "$logKey v14 WEBVIEW raw head: ${capturedJson.take(500)}")
            }

            Pair(renderedHtml, capturedJson)
        } catch (e: Throwable) {
            Log.i(TAG, "$logKey v14 WEBVIEW exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun emitFromWebViewCaptured(
        capturedJson: String,
        referer: String,
        logKey: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val servers = ArrayList<Pair<String, String>>()

        fun isVideoUrl(url: String): Boolean {
            return url.contains("dailymotion.com") ||
                url.contains("rumble.com") ||
                url.contains("ok.ru") ||
                url.contains("vk.com") || url.contains("vk.ru") ||
                url.contains("streamable.com") ||
                url.contains("voe.sx") ||
                url.contains("filemoon") || url.contains("moonplayer") ||
                url.contains("r2.cloudflarestorage") ||
                url.contains("hcdn.dev") ||
                url.contains("cloudflarestorage") ||
                url.contains(".mp4") || url.contains(".m3u8") ||
                url.contains("chunklist") || url.contains("index.m3") ||
                url.contains("donghualife.com/video") ||
                url.contains("donghualife.com/episodes") ||
                url.contains("donghualife.com/movie") ||
                Regex("""https?://[a-z0-9-]+\.[a-z0-9-]+\.\w+/video/""").containsMatchIn(url)
        }

        fun isMockUrl(url: String): Boolean {
            return url.contains("/video/example") ||
                url.contains("cdn.example.com") ||
                url.contains("rumble.com/embed/example") ||
                url.contains("dailymotion.com/embed/video/example")
        }

        fun repairM3u8Url(url: String): String {
            return when {
                url.endsWith(".m3") -> url + "u8"
                url.endsWith(".m3u") -> url + "8"
                url.contains("chunklist.m3") && !url.contains("chunklist.m3u8") ->
                    url.replace("chunklist.m3", "chunklist.m3u8")
                else -> url
            }
        }

        fun extractVideoUrls(text: String, sourceLabel: String) {
            if (text.isEmpty()) return
            val serverPattern = Regex("""\{\\?"name\\?":"([^"\\]+)\\?",\\?"url\\?":"([^"\\]+)\\?"\}""")
            for (m in serverPattern.findAll(text)) {
                val name = m.groupValues[1]
                val rawUrl = m.groupValues[2]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\\"", "\"")
                val url = repairM3u8Url(rawUrl)
                if (isVideoUrl(url) && !isMockUrl(url) && url.length >= 20) {
                    servers.add(name to url)
                    Log.i(TAG, "$logKey v14 EMIT found (RSC format): name=$name url=${url.take(80)}")
                }
            }
            val urlPattern = Regex(""""url"\s*:\s*"([^"]+)"""")
            for (m in urlPattern.findAll(text)) {
                val rawUrl = m.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                val url = repairM3u8Url(rawUrl)
                if (!isVideoUrl(url) || isMockUrl(url) || url.length < 20) continue
                val urlPos = m.range.first
                val searchStart = maxOf(0, urlPos - 200)
                val searchEnd = minOf(text.length, urlPos)
                val nearbyText = text.substring(searchStart, searchEnd)
                val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(nearbyText)
                val name = nameMatch?.groupValues?.get(1) ?: sourceLabel
                if (servers.none { it.second == url }) {
                    servers.add(name to url)
                    Log.i(TAG, "$logKey v14 EMIT found (API format): name=$name url=${url.take(80)}")
                }
            }
            val labelUrlPattern = Regex(""""label"\s*:\s*"([^"]+)"[^}]*?"url"\s*:\s*"([^"]+)"""")
            for (m in labelUrlPattern.findAll(text)) {
                val name = m.groupValues[1]
                val rawUrl = m.groupValues[2]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                val url = repairM3u8Url(rawUrl)
                if (!isVideoUrl(url) || isMockUrl(url) || url.length < 20) continue
                if (servers.none { it.second == url }) {
                    servers.add(name to url)
                    Log.i(TAG, "$logKey v14 EMIT found (label format): name=$name url=${url.take(80)}")
                }
            }
        }

        try {
            val captured = parseJson<CapturedWebViewData>(capturedJson)

            val nextF = captured.next_f ?: ""
            if (nextF.isNotEmpty()) {
                Log.i(TAG, "$logKey v14 EMIT: scanning next_f (${nextF.length} chars)")
                extractVideoUrls(nextF, "WebView RSC")
            }

            val nextData = captured.nextData ?: ""
            if (nextData.isNotEmpty()) {
                Log.i(TAG, "$logKey v14 EMIT: scanning nextData (${nextData.length} chars)")
                extractVideoUrls(nextData, "WebView NextData")
            }

            val fetchResponses = captured.fetchResponses ?: emptyList()
            for ((idx, fr) in fetchResponses.withIndex()) {
                val frUrl = fr.url ?: ""
                val frBody = fr.body ?: ""
                if (frBody.isEmpty()) continue
                Log.i(TAG, "$logKey v14 EMIT: scanning fetchResponse[$idx] url=$frUrl bodyLen=${frBody.length}")
                if (frBody.contains("/video/example") || frBody.contains("cdn.example.com")) {
                    Log.i(TAG, "$logKey v14 EMIT: fetchResponse[$idx] is MOCK, but still scanning for real URLs")
                }
                extractVideoUrls(frBody, "WebView API $idx")
            }

            val videos = captured.videos ?: emptyList()
            for ((vIdx, v) in videos.withIndex()) {
                if (v.length < 20 || !isVideoUrl(v) || isMockUrl(v)) continue
                if (servers.none { it.second == v }) {
                    Log.i(TAG, "$logKey v14 EMIT: DOM video[$vIdx]=$v")
                    servers.add("WebView Video $vIdx" to v)
                }
            }

            val dataUrls = captured.dataUrls ?: emptyList()
            for ((dIdx, d) in dataUrls.withIndex()) {
                if (d.length < 20 || !isVideoUrl(d) || isMockUrl(d)) continue
                if (servers.none { it.second == d }) {
                    Log.i(TAG, "$logKey v14 EMIT: DOM dataUrl[$dIdx]=$d")
                    servers.add("WebView Data $dIdx" to d)
                }
            }

            val seen = mutableSetOf<String>()
            val uniqueServers = servers.filter { (_, u) ->
                if (seen.contains(u)) false else { seen.add(u); true }
            }

            Log.i(TAG, "$logKey v14 EMIT: total ${servers.size} servers found, ${uniqueServers.size} unique")
            if (uniqueServers.isEmpty()) {
                Log.i(TAG, "$logKey v14 EMIT: no servers found in captured WebView data")
                return false
            }

            // v16: filtrar por el episodio pedido — la página contiene TODOS los episodios
            // y sin este filtro se emitían servers del episodio 1 (bug reportado)
            val watchPath = referer.substringAfter("/watch/", "")
            val urlMatch = Regex("""^(.+)-(\d+)-(\d+)$""").find(watchPath)
            val epHint = urlMatch?.groupValues?.get(3)?.toIntOrNull()

            var finalServers = uniqueServers
            if (epHint != null) {
                // 1) buscar el bloque servers del episodio pedido en el texto capturado
                val numbered = ArrayList<Pair<String, String>>()
                for (text in listOf(nextF, nextData) + fetchResponses.mapNotNull { it.body }) {
                    numbered.addAll(extractNumberedServersFromText(text, epHint, logKey))
                }
                if (numbered.isNotEmpty()) {
                    Log.i(TAG, "$logKey v16 EMIT: epHint=$epHint → ${numbered.size} servers del episodio pedido")
                    finalServers = numbered.distinctBy { it.second }
                } else {
                    // 2) sin match: filtrar los genéricos por número en su URL (si la tienen)
                    val filtered = uniqueServers.filter { (_, u) -> episodeNumberFromUrl(u) == epHint }
                    if (filtered.isNotEmpty()) {
                        Log.i(TAG, "$logKey v16 EMIT: epHint=$epHint → ${filtered.size} servers por URL")
                        finalServers = filtered
                    } else {
                        Log.i(TAG, "$logKey v16 EMIT: epHint=$epHint sin match; se emiten ${uniqueServers.size} tal cual")
                    }
                }
            }

            Log.i(TAG, "$logKey v14 EMIT: emitting ${finalServers.size} servers via emitEpisodeServers")
            return emitEpisodeServers(finalServers, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.i(TAG, "$logKey v14 EMIT error: ${e.message}")
            return false
        }
    }


    // ========== EXTRACTORS ==========

    /** v19: Extrae el bloque real m.f["<vkey>"]={...} del HTML de la página embed de Rumble.
     *  okhttp primero; si Cloudflare bloquea (403) o el bloque no aparece, reintenta con
     *  WebView real (pasa el reto de Cloudflare). El parsing vive en extractRumbleDataBlock. */
    private suspend fun tryExtractRumbleFromEmbedHtml(
        embedUrl: String,
        vkey: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val marker = "m.f[\"$vkey\"]="
        // 1) okhttp (rápido, funciona desde IP no bloqueada)
        try {
            val resp = app.get(embedUrl, headers = mapOf("User-Agent" to browserUA), timeout = 20L)
            val html = resp.text
            Log.i(TAG, "extractRumble v19: embedPage(okhttp) vkey=$vkey httpCode=${resp.code} len=${html.length}")
            if (resp.code == 200 && html.contains(marker)) {
                if (extractRumbleDataBlock(html, vkey, serverName, callback)) {
                    Log.i(TAG, "extractRumble v19: SUCCESS via okhttp embed-page m.f[] for $vkey")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractRumble v19: okhttp embedPage failed: ${e.message}")
        }

        // 2) Fallback WebView: motor real del sistema, pasa el reto de Cloudflare
        try {
            Log.i(TAG, "extractRumble v19: trying WebView fallback for embed page (vkey=$vkey)")
            val webHtml = fetchHtmlViaWebView(embedUrl, referer = null, waitMarker = marker, maxWaitMs = 45000L)
            if (webHtml != null) {
                Log.i(TAG, "extractRumble v19: WebView HTML len=${webHtml.length} markerFound=${webHtml.contains(marker)}")
                if (extractRumbleDataBlock(webHtml, vkey, serverName, callback)) {
                    Log.i(TAG, "extractRumble v19: SUCCESS via WebView embed-page m.f[] for $vkey")
                    return true
                }
            } else {
                Log.w(TAG, "extractRumble v19: WebView fallback returned no HTML")
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractRumble v19: WebView fallback failed: ${e.message}")
        }
        return false
    }

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

        val vkey = Regex("""/embed/([A-Za-z0-9_]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: Regex("""vkey=([A-Za-z0-9_]+)""").find(embedUrl)?.groupValues?.get(1)

        val rumbleHeaders = mapOf(
            "User-Agent" to browserUA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "https://rumble.com/",
            "Origin" to "https://rumble.com",
        )

        // v18 MÉTODO 0 (VERIFICADO): el HTML de la página embed contiene el bloque real de datos
        // m.f["<vkey>"]={...} con el master hls (hls-vod/... o live-hls-dvr/...) y las pistas ua.tar
        // por calidad (360/480/720/1080). Las tar son "chunklist virtual": la master playlist completa
        // (sin r_range) sirve los segmentos TS reales (verificado: bytes 0x47 MPEG-TS).
        // embedJS/u3|u4 AHORA DEVUELVE DATA DECOY (otro vkey) — se eliminó.
        if (vkey != null && !emitted) {
            emitted = tryExtractRumbleFromEmbedHtml(embedUrl, vkey, serverName, trackingCb)
            if (emitted) {
                Log.i(TAG, "extractRumble v18: SUCCESS via embed-page m.f[] for $vkey")
                return true
            }
        }

        if (vkey != null) {
            try {
                val apiUrl = "https://rumble.com/api/Media?vkey=$vkey"
                val resp = app.get(apiUrl, headers = rumbleHeaders, timeout = 20L)
                val jsonText = resp.text
                Log.i(TAG, "extractRumble: API vkey=$vkey httpCode=${resp.code} jsonLen=${jsonText.length} head=${jsonText.take(200)}")

                val isHtmlResponse = jsonText.length > 200 &&
                    (jsonText.startsWith("<!doctype", ignoreCase = true) ||
                     jsonText.startsWith("<html", ignoreCase = true) ||
                     jsonText.contains("<head", ignoreCase = true))
                if (isHtmlResponse) {
                    Log.i(TAG, "extractRumble: API returned HTML (len=${jsonText.length}), not JSON — trying embedded_video_data parser")

                    val embeddedDataRegex = Regex(
                        """<script[^>]*class\s*=\s*"[^">]*embedded_video_data[^">]*"[^>]*>([\s\S]*?)</script>""",
                        RegexOption.IGNORE_CASE
                    )
                    val embeddedMatch = embeddedDataRegex.find(jsonText)
                    if (embeddedMatch != null) {
                        val embeddedJson = embeddedMatch.groupValues[1].trim()
                        Log.i(TAG, "extractRumble: API embedded_video_data FOUND len=${embeddedJson.length} head=${embeddedJson.take(300)}")

                        val hlsAutoMatch = Regex(
                            """"hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)""""
                        ).find(embeddedJson)
                        if (hlsAutoMatch != null) {
                            val u = hlsAutoMatch.groupValues[1]
                                .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                            try {
                                generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                Log.i(TAG, "extractRumble: API embedded hls.auto emitted: ${u.take(80)}")
                                if (emitted) return true
                            } catch (e: Exception) {
                                Log.w(TAG, "extractRumble: API embedded hls.auto generateM3u8 failed: ${e.message}")
                            }
                        }

                        if (!emitted) {
                            var qualityEmitted = 0
                            for (qMatch in Regex(""""(ld|sd|hd)"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").findAll(embeddedJson)) {
                                val qLabel = qMatch.groupValues[1]
                                val u = qMatch.groupValues[2]
                                    .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                                try {
                                    generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                    Log.i(TAG, "extractRumble: API embedded hls.$qLabel emitted: ${u.take(80)}")
                                    qualityEmitted++
                                } catch (_: Throwable) {}
                            }
                            if (qualityEmitted > 0 && emitted) return true
                        }

                        if (!emitted) {
                            val tarBlockMatch = Regex(
                                """"ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})"""
                            ).find(embeddedJson)
                            if (tarBlockMatch != null) {
                                val tarBlock = tarBlockMatch.groupValues[1]
                                var tarCount = 0
                                Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
                                    val qLabel = match.groupValues[1]
                                    val u = match.groupValues[2]
                                        .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                                    if (u.isBlank()) return@forEach
                                    val quality = when (qLabel) {
                                        "2160", "1440" -> Qualities.P2160.value
                                        "1080" -> Qualities.P1080.value
                                        "720" -> Qualities.P720.value
                                        "480" -> Qualities.P480.value
                                        "360" -> Qualities.P360.value
                                        else -> Qualities.Unknown.value
                                    }
                                    try {
                                        trackingCb(
                                            newExtractorLink(
                                                source = serverName,
                                                name = "$serverName ${qLabel}p",
                                                url = u,
                                                type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = "https://rumble.com"
                                                this.quality = quality
                                            }
                                        )
                                        tarCount++
                                    } catch (_: Throwable) {}
                                }
                                if (tarCount > 0) {
                                    Log.i(TAG, "extractRumble: API embedded ua.tar emitted $tarCount qualities")
                                    if (emitted) return true
                                }
                            }
                        }

                        if (!emitted) {
                            var fbCount = 0
                            for (m in Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(embeddedJson)) {
                                val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                                try {
                                    trackingCb(
                                        newExtractorLink(
                                            source = serverName, name = serverName, url = u,
                                            type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "https://rumble.com"
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    fbCount++
                                } catch (_: Throwable) {}
                            }
                            if (fbCount > 0) {
                                Log.i(TAG, "extractRumble: API embedded regex fallback emitted $fbCount URLs")
                                if (emitted) return true
                            }
                        }
                    } else {
                        val ogVideoMatch = Regex("""<meta\s+property\s*=\s*"og:video[^"]*"\s+content\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(jsonText)
                        if (ogVideoMatch != null) {
                            Log.i(TAG, "extractRumble: API HTML has og:video=${ogVideoMatch.groupValues[1].take(100)} (no embedded_video_data)")
                        } else {
                            Log.i(TAG, "extractRumble: API HTML has NO embedded_video_data AND no og:video — likely Rumble homepage (bot detection redirect)")
                        }
                    }
                } else {
                    if (!emitted) {
                        val hlsAutoMatch = Regex(
                            """"hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)""""
                        ).find(jsonText)
                        if (hlsAutoMatch != null) {
                            val u = hlsAutoMatch.groupValues[1]
                                .replace("\\/", "/")
                                .replace("\\u0026", "&")
                            if (u.contains(".m3u8")) {
                                try {
                                    generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                    Log.i(TAG, "extractRumble: API hls.auto emitted: ${u.take(80)}")
                                    if (emitted) return true
                                } catch (e: Exception) {
                                    Log.w(TAG, "extractRumble: API hls.auto generateM3u8 failed: ${e.message}")
                                }
                            }
                        }
                    }

                    if (!emitted) {
                        val hlsUrlMatch = Regex(
                            """"hls"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)""""
                        ).find(jsonText)
                        if (hlsUrlMatch != null) {
                            val u = hlsUrlMatch.groupValues[1]
                                .replace("\\/", "/")
                                .replace("\\u0026", "&")
                            try {
                                generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                Log.i(TAG, "extractRumble: API hls.url emitted: ${u.take(80)}")
                                if (emitted) return true
                            } catch (e: Exception) {
                                Log.w(TAG, "extractRumble: API hls.url generateM3u8 failed: ${e.message}")
                            }
                        }
                    }

                    if (!emitted) {
                        val tarBlockMatch = Regex(
                            """"ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})"""
                        ).find(jsonText)
                        if (tarBlockMatch != null) {
                            val tarBlock = tarBlockMatch.groupValues[1]
                            var tarCount = 0
                            Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
                                val qLabel = match.groupValues[1]
                                val u = match.groupValues[2]
                                    .replace("\\/", "/")
                                    .replace("\\u0026", "&")
                                if (u.isBlank()) return@forEach
                                val quality = when (qLabel) {
                                    "2160", "1440" -> Qualities.P2160.value
                                    "1080" -> Qualities.P1080.value
                                    "720" -> Qualities.P720.value
                                    "480" -> Qualities.P480.value
                                    "360" -> Qualities.P360.value
                                    else -> Qualities.Unknown.value
                                }
                                val isM3u8 = u.contains(".m3u8")
                                try {
                                    trackingCb(
                                        newExtractorLink(
                                            source = serverName,
                                            name = "$serverName ${qLabel}p",
                                            url = u,
                                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "https://rumble.com"
                                            this.quality = quality
                                        }
                                    )
                                    tarCount++
                                } catch (_: Throwable) {}
                            }
                            if (tarCount > 0) {
                                Log.i(TAG, "extractRumble: API ua.tar emitted $tarCount qualities")
                                if (emitted) return true
                            }
                        }
                    }

                    if (!emitted) {
                        var fallbackCount = 0
                        for (m in Regex("""(https?://[^"]+\.(?:m3u8|mp4)[^"]*)""").findAll(jsonText)) {
                            val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                            val isM3u8 = u.contains(".m3u8")
                            try {
                                trackingCb(
                                    newExtractorLink(
                                        source = serverName,
                                        name = serverName,
                                        url = u,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://rumble.com"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                fallbackCount++
                            } catch (_: Throwable) {}
                        }
                        if (fallbackCount > 0) {
                            Log.i(TAG, "extractRumble: API regex fallback emitted $fallbackCount URLs")
                            if (emitted) return true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "extractRumble: API call failed for vkey=$vkey: ${e.message}")
            }
        } else {
            Log.w(TAG, "extractRumble: could not extract vkey from $embedUrl")
        }

        if (vkey != null && !emitted) {
            Log.i(TAG, "extractRumble: MÉTODO 1.5 entered (vkey=$vkey) — trying 4 alternative endpoints")
            val altHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9,es;q=0.8",
                "Referer" to "https://beta.donghualife.com/",
                "Origin" to "https://beta.donghualife.com",
                "Sec-Fetch-Dest" to "script",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "cross-site",
            )

            if (!emitted) {
                try {
                    val getJsonUrl = "https://rumble.com/api/Media/get.json?vkey=$vkey"
                    val getJsonHeaders = altHeaders.toMutableMap().apply {
                        put("Accept", "application/json, text/plain, */*")
                        put("Sec-Fetch-Dest", "empty")
                        put("Sec-Fetch-Mode", "cors")
                    }
                    val resp = app.get(getJsonUrl, headers = getJsonHeaders, timeout = 15L)
                    val jsonText = resp.text
                    Log.i(TAG, "extractRumble: get.json vkey=$vkey httpCode=${resp.code} jsonLen=${jsonText.length} head=${jsonText.take(200)}")

                    if (resp.code == 200 && (jsonText.startsWith("{") || jsonText.startsWith("["))) {
                        val hlsAutoMatch = Regex(""""hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(jsonText)
                        if (hlsAutoMatch != null) {
                            val u = hlsAutoMatch.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                            try {
                                generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                Log.i(TAG, "extractRumble: get.json hls.auto emitted: ${u.take(80)}")
                                if (emitted) return true
                            } catch (_: Throwable) {}
                        }

                        if (!emitted) {
                            val tarBlockMatch = Regex(""""ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})""").find(jsonText)
                            if (tarBlockMatch != null) {
                                val tarBlock = tarBlockMatch.groupValues[1]
                                Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
                                    val qLabel = match.groupValues[1]
                                    val u = match.groupValues[2].replace("\\/", "/").replace("\\u0026", "&")
                                    if (u.isBlank()) return@forEach
                                    val quality = when (qLabel) {
                                        "2160", "1440" -> Qualities.P2160.value
                                        "1080" -> Qualities.P1080.value
                                        "720" -> Qualities.P720.value
                                        "480" -> Qualities.P480.value
                                        "360" -> Qualities.P360.value
                                        else -> Qualities.Unknown.value
                                    }
                                    try {
                                        trackingCb(
                                            newExtractorLink(
                                                source = serverName, name = "$serverName ${qLabel}p", url = u,
                                                type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) { this.referer = "https://rumble.com"; this.quality = quality }
                                        )
                                    } catch (_: Throwable) {}
                                }
                                if (emitted) {
                                    Log.i(TAG, "extractRumble: get.json ua.tar emitted")
                                    return true
                                }
                            }
                        }

                        if (!emitted) {
                            var fbCount = 0
                            for (m in Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(jsonText)) {
                                val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                                try {
                                    trackingCb(
                                        newExtractorLink(
                                            source = serverName, name = serverName, url = u,
                                            type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) { this.referer = "https://rumble.com" }
                                    )
                                    fbCount++
                                } catch (_: Throwable) {}
                            }
                            if (fbCount > 0) {
                                Log.i(TAG, "extractRumble: get.json regex fallback emitted $fbCount URLs")
                                if (emitted) return true
                            }
                        }
                    } else {
                        Log.i(TAG, "extractRumble: get.json returned HTML or non-JSON (httpCode=${resp.code}, len=${jsonText.length}), skipping")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "extractRumble: get.json fetch failed: ${e.message}")
                }
            }

            try {
                val embedJsUrl = "https://rumble.com/embedjs/$vkey"
                val resp = app.get(embedJsUrl, headers = altHeaders, timeout = 20L)
                val jsText = resp.text
                Log.i(TAG, "extractRumble: embedJS vkey=$vkey httpCode=${resp.code} jsLen=${jsText.length} head=${jsText.take(200)}")

                if (resp.code == 200 && jsText.length > 1000) {
                    var jsEmitted = 0
                    val hlsAutoMatch = Regex(""""hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(jsText)
                    if (hlsAutoMatch != null) {
                        val u = hlsAutoMatch.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                        try {
                            generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                            Log.i(TAG, "extractRumble: embedJS hls.auto emitted: ${u.take(80)}")
                            jsEmitted++
                        } catch (_: Throwable) {}
                    }

                    val tarBlockMatch = Regex(""""ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})""").find(jsText)
                    if (tarBlockMatch != null) {
                        val tarBlock = tarBlockMatch.groupValues[1]
                        Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
                            val qLabel = match.groupValues[1]
                            val u = match.groupValues[2].replace("\\/", "/").replace("\\u0026", "&")
                            if (u.isBlank()) return@forEach
                            val quality = when (qLabel) {
                                "2160", "1440" -> Qualities.P2160.value
                                "1080" -> Qualities.P1080.value
                                "720" -> Qualities.P720.value
                                "480" -> Qualities.P480.value
                                "360" -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
                            try {
                                trackingCb(
                                    newExtractorLink(
                                        source = serverName,
                                        name = "$serverName ${qLabel}p",
                                        url = u,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://rumble.com"
                                        this.quality = quality
                                    }
                                )
                                jsEmitted++
                            } catch (_: Throwable) {}
                        }
                    }

                    if (jsEmitted == 0) {
                        for (m in Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(jsText)) {
                            val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                            val isM3u8 = u.contains(".m3u8")
                            try {
                                trackingCb(
                                    newExtractorLink(
                                        source = serverName,
                                        name = serverName,
                                        url = u,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://rumble.com"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                jsEmitted++
                            } catch (_: Throwable) {}
                        }
                    }

                    if (jsEmitted > 0) {
                        Log.i(TAG, "extractRumble: embedJS emitted $jsEmitted URLs")
                        if (emitted) return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "extractRumble: embedJS fetch failed: ${e.message}")
            }

            if (!emitted) {
                try {
                    val oembedUrl = "https://rumble.com/api/Media/oembed?url=" +
                        java.net.URLEncoder.encode(embedUrl, "UTF-8")
                    val resp = app.get(oembedUrl, headers = altHeaders, timeout = 15L)
                    val jsonText = resp.text
                    Log.i(TAG, "extractRumble: oembed vkey=$vkey httpCode=${resp.code} jsonLen=${jsonText.length} head=${jsonText.take(200)}")

                    if (resp.code == 200 && jsonText.startsWith("{")) {
                        for (m in Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(jsonText)) {
                            val u = m.groupValues[1]
                            try {
                                trackingCb(
                                    newExtractorLink(source = serverName, name = serverName, url = u,
                                        type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                        this.referer = "https://rumble.com"
                                    }
                                )
                                if (emitted) return true
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "extractRumble: oembed fetch failed: ${e.message}")
                }
            }

            if (!emitted) {
                try {
                    val publicUrl = "https://rumble.com/$vkey"
                    val publicHeaders = altHeaders.toMutableMap().apply {
                        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        put("Sec-Fetch-Dest", "document")
                        put("Sec-Fetch-Mode", "navigate")
                        put("Sec-Fetch-Site", "cross-site")
                        put("Upgrade-Insecure-Requests", "1")
                    }
                    val resp = app.get(publicUrl, headers = publicHeaders, timeout = 20L)
                    val html = resp.text
                    Log.i(TAG, "extractRumble: publicPage vkey=$vkey httpCode=${resp.code} htmlLen=${html.length} head=${html.take(200)}")

                    if (resp.code == 200 && html.length > 5000) {
                        val hlsAutoMatch = Regex(""""hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(html)
                        if (hlsAutoMatch != null) {
                            val u = hlsAutoMatch.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                            try {
                                generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                Log.i(TAG, "extractRumble: publicPage hls.auto emitted: ${u.take(80)}")
                                if (emitted) return true
                            } catch (_: Throwable) {}
                        }

                        for (m in Regex("""(https?://[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*)""").findAll(html)) {
                            val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                            if (u.contains("rmbl.ws") || u.contains(".m3u8") || u.contains(".mp4")) {
                                try {
                                    trackingCb(
                                        newExtractorLink(source = serverName, name = serverName, url = u,
                                            type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                            this.referer = "https://rumble.com"
                                        }
                                    )
                                    if (emitted) return true
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "extractRumble: publicPage fetch failed: ${e.message}")
                }
            }
        }

        try {
            val embedHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,es;q=0.8",
                "Referer" to "https://beta.donghualife.com/",
                "Origin" to "https://beta.donghualife.com",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1",
            )
            val resp = app.get(embedUrl, referer = "https://beta.donghualife.com/", headers = embedHeaders, timeout = 30L)
            val html = resp.text
            Log.i(TAG, "extractRumble: embedPage httpCode=${resp.code} htmlLen=${html.length} (with donghualife Referer)")

            if (resp.code == 200 && html.length > 5000) {
                val hlsAutoPattern = Regex(""""hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""")
                hlsAutoPattern.find(html)?.let { m ->
                    val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                    try {
                        generateM3u8(serverName, u, referer).forEach(trackingCb)
                        if (emitted) return true
                    } catch (_: Throwable) {}
                }
                val tarBlockMatch = Regex(""""ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})""").find(html)
                if (tarBlockMatch != null) {
                    val tarBlock = tarBlockMatch.groupValues[1]
                    Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
                        val qLabel = match.groupValues[1]
                        val u = match.groupValues[2].replace("\\/", "/").replace("\\u0026", "&")
                        val quality = when (qLabel) {
                            "2160", "1440" -> Qualities.P2160.value
                            "1080" -> Qualities.P1080.value
                            "720" -> Qualities.P720.value
                            "480" -> Qualities.P480.value
                            "360" -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        trackingCb(
                            newExtractorLink(source = serverName, name = "$serverName ${qLabel}p", url = u,
                                type = ExtractorLinkType.M3U8) {
                                this.referer = referer
                                this.quality = quality
                            }
                        )
                    }
                    if (emitted) return true
                }
                for (pattern in listOf(
                    Regex("""["'](https?://[^"']*rmbl\.ws[^"']*\.mp4[^"']*)["']"""),
                    Regex("""["'](https?://[^"']*rmbl\.ws[^"']*)["']"""),
                )) {
                    val matches = pattern.findAll(html).toList()
                    if (matches.isNotEmpty()) {
                        for (match in matches) {
                            val u = match.groupValues[1]
                            val quality = when {
                                u.contains("1080") -> Qualities.P1080.value
                                u.contains("720") -> Qualities.P720.value
                                u.contains("480") -> Qualities.P480.value
                                else -> Qualities.Unknown.value
                            }
                            trackingCb(
                                newExtractorLink(source = serverName, name = "$serverName ${quality / 1000}p",
                                    url = u, type = ExtractorLinkType.VIDEO) {
                                    this.referer = referer
                                    this.quality = quality
                                }
                            )
                        }
                        if (emitted) return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractRumble: embedPage fetch failed: ${e.message}")
        }

        Log.w(TAG, "extractRumble: all methods failed for $embedUrl (vkey=$vkey)")
        return emitted
    }

    /** v19: WebView genérico que devuelve el HTML final de una URL.
     *  UA por defecto del WebView (no forzado) + cookies persistentes + espera del reto de
     *  Cloudflare (hasta 30s) + polling del marker (p.ej. m.f["vkey"]) en el DOM.
     *  Devuelve null si no hay Context, timeout, o el HTML nunca contiene el marker. */
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
        val block = Regex("""m\.f\[""" + Regex.escape(vkey) + """"\]=""").find(html)
            ?: return false

        val start = block.range.first
        val nextMf = html.indexOf("m.f[\"", start + 10)
        val nextScript = html.indexOf("</script>", start)
        val end = listOf(nextMf, nextScript).filter { it > start }.minOrNull() ?: html.length
        val data = html.substring(start, minOf(end, html.length))
            .replace(Regex("\\\\+/"), "/")
            .replace(Regex("\\\\+u0026"), "&")

        var emitted = false

        // 1) Master HLS (hls-vod o live-hls-dvr) — jugable directamente (verificado 200)
        val hlsMaster = Regex(""""hls"\s*:\s*\{\s*"url"\s*:\s*"(https://rumble\.com/(?:hls-vod|live-hls-dvr)/[^"]+\.m3u8)"""").find(data)
            ?: Regex("""(https://rumble\.com/(?:hls-vod|live-hls-dvr)/[^"]+\.m3u8)""").find(data)
        if (hlsMaster != null) {
            val u = hlsMaster.groupValues[1]
            try {
                generateM3u8(serverName, u, "https://rumble.com").forEach(callback)
                emitted = true
                Log.i(TAG, "extractRumble v19: master HLS emitted: ${u.take(80)}")
            } catch (e: Exception) {
                Log.w(TAG, "extractRumble v19: master HLS generateM3u8 failed: ${e.message}")
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
        return emitted
    }

    private fun guessQuality(url: String): String {
        return when {
            url.contains("2160") || url.contains("1440") -> "2160"
            url.contains("1080") -> "1080"
            url.contains("720") -> "720"
            url.contains("480") -> "480"
            url.contains("360") -> "360"
            else -> "720"
        }
    }

    private suspend fun extractDailymotion(
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
        val videoId = Regex("video=([A-Za-z0-9]+)").find(embedUrl)?.destructured?.component1()
            ?: Regex("/video/([A-Za-z0-9]+)").find(embedUrl)?.destructured?.component1()
            ?: Regex("/embed/video/([A-Za-z0-9]+)").find(embedUrl)?.destructured?.component1()
            ?: return false

        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val jsonText = app.get(apiUrl,
                referer = "https://www.dailymotion.com/embed/video/$videoId",
                headers = mapOf(
                    "User-Agent" to browserUA,
                    "Accept" to "application/json",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Origin" to "https://www.dailymotion.com",
                ),
                timeout = 15L).text
            Log.i(TAG, "extractDailymotion: videoId=$videoId jsonLen=${jsonText.length}")

            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(jsonText)) {
                try {
                    generateM3u8(serverName, match.value, "https://www.dailymotion.com").forEach(trackingCb)
                    if (emitted) return true
                } catch (e: Exception) {
                    Log.w(TAG, "extractDailymotion: m3u8 method failed: ${e.message}")
                }
            }
            val mp4Urls = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(jsonText).map { it.value }.distinct().toList()
            for (u in mp4Urls) {
                val q = when {
                    u.contains("1080") -> Qualities.P1080.value
                    u.contains("720") -> Qualities.P720.value
                    u.contains("480") -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                trackingCb(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = u) {
                    this.referer = "https://www.dailymotion.com"
                    this.quality = q
                })
            }
            if (emitted) return true
        } catch (e: Exception) {
            Log.w(TAG, "extractDailymotion: metadata fetch failed for videoId=$videoId: ${e.message}")
        }

        try {
            loadExtractor("https://www.dailymotion.com/embed/video/$videoId", referer, subtitleCallback = {}, trackingCb)
        } catch (e: Exception) {
            Log.w(TAG, "extractDailymotion: loadExtractor fallback failed: ${e.message}")
        }
        return emitted
    }

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
                var u = match.groupValues[1]
                if (u.startsWith("//")) u = "https:$u"
                u = u.replace("&amp;", "&").replace("\\u0026", "&").replace("\\/", "/")
                if (u in seen) continue
                seen.add(u)
                val quality = when {
                    u.contains("/video/mp4-mobile/") -> Qualities.P360.value
                    u.contains("/video/mp4/") -> Qualities.P720.value
                    else -> Qualities.Unknown.value
                }
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = "$serverName ${quality / 1000}p",
                        url = u,
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

    // ========== OK.RU EXTRACTOR (con User-Agent) ==========

    private suspend fun extractOkRu(
        videoUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val resp = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to browserUA), timeout = 15L)
            val html = resp.text
            Log.i(TAG, "extractOkRu v19: $videoUrl httpCode=${resp.code} len=${html.length}")
            if (resp.code != 200) {
                return extractOkRuViaWebView(videoUrl, serverName, callback)
            }
            val dataMatch = Regex("""data-options="([^"]+)"""").find(html)
            if (dataMatch != null) {
                val optionsJson = dataMatch.destructured.component1().replace("&quot;", "\"").replace("&amp;", "&")
                // v18 FIX CRÍTICO: el payload contiene UNA barra invertida antes de u0026 (\u0026),
                // pero el replace anterior usaba "\\\\u0026" (doble barra) y nunca matcheaba =>
                // el player recibía URLs con \u0026 y devolvía 400. Se usa Regex para des-escapar
                // tanto \\u0026 como \\/ y también las formas con doble barra invertida.
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
                            this.headers = mapOf("User-Agent" to browserUA)
                        }
                    )
                    emittedAny = true
                }
                if (emittedAny) return true
            }
            // v18 FIX: fallback vía la API de metadatos de OK.ru (POST /dk?cmd=videoPlayerMetadata)
            // que devuelve el array "videos" con MP4 progresivos por calidad (mobile..full).
            if (!extractOkRuViaMetadata(videoUrl, serverName, callback)) {
                Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""").find(html)?.let { m ->
                    callback(newExtractorLink(source = serverName, name = serverName, url = m.destructured.component1()) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to browserUA)
                    })
                    return true
                }
                for (match in Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""").findAll(html)) {
                    callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to browserUA)
                    })
                    return true
                }
                // v19 Fallback WebView: si okhttp recibió el reto de Cloudflare (sin data-options)
                // o nada emitió, carga el embed en un WebView real y parsea su HTML final.
                return extractOkRuViaWebView(videoUrl, serverName, callback)
            }
        } catch (_: Exception) {}
        return false
    }

    /** v18: Fallback OK.ru vía API de metadatos (POST /dk?cmd=videoPlayerMetadata&mid=<id>).
     *  Devuelve "videos" con MP4 progresivos (mobile/lowest/low/sd/hd/full) — verificado 200. */
    private suspend fun extractOkRuViaMetadata(
        videoUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val mid = Regex("video(?:embed)?/(\\d+)").find(videoUrl)?.groupValues?.get(1)
                ?: return false
            val resp = app.post(
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$mid",
                headers = mapOf(
                    "User-Agent" to browserUA,
                    "Referer" to videoUrl,
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
                timeout = 20L
            )
            if (!resp.isSuccessful) return false
            val body = resp.text
            if (!body.trimStart().startsWith("{")) return false
            var emitted = false
            // videos[]: name=mobile|lowest|low|sd|hd|full con url tipo https://vdNNN.okcdn.ru/?...
            val qualityMap = listOf("full" to Qualities.P1080.value, "hd" to Qualities.P720.value,
                "sd" to Qualities.P480.value, "low" to Qualities.P360.value,
                "lowest" to Qualities.P240.value, "mobile" to Qualities.P240.value)
            val videosBlock = Regex(""""videos"\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            val urlRegex = Regex(""""name"\s*:\s*"([a-z]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""")
            val text = videosBlock ?: body
            for (m in urlRegex.findAll(text)) {
                val qName = m.groupValues[1]
                val u = m.groupValues[2].replace(Regex("\\\\+/"), "/").replace(Regex("\\\\+u0026"), "&")
                if (!u.startsWith("http")) continue
                val quality = qualityMap.firstOrNull { it.first == qName }?.second ?: Qualities.Unknown.value
                try {
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName ${qName}",
                            url = u,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = videoUrl
                            this.quality = quality
                            this.headers = mapOf("User-Agent" to browserUA)
                        }
                    )
                    emitted = true
                } catch (_: Throwable) {}
            }
            if (emitted) Log.i(TAG, "extractOkRuViaMetadata: emitidos URLs desde API metadata para $mid")
            emitted
        } catch (e: Exception) {
            Log.w(TAG, "extractOkRuViaMetadata failed: ${e.message}")
            false
        }
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
                        this.headers = mapOf("User-Agent" to browserUA)
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
                        this.headers = mapOf("User-Agent" to browserUA)
                    })
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractOkRuViaWebView failed: ${e.message}")
        }
        return false
    }

    private suspend fun extractOkRuDirect(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(embedUrl, referer = referer, timeout = 30L).text
            val dataOptionsPattern = Regex("data-options=\"([^\"]+)\"")
            val dataOptionsMatch = dataOptionsPattern.find(html)
            if (dataOptionsMatch != null) {
                val decoded = dataOptionsMatch.groupValues[1]
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    // v18 FIX: mismo bug de doble barra invertida — usar Regex
                    .replace(Regex("\\\\+u0026"), "&")
                    .replace(Regex("\\\\+/"), "/")
                val hlsPattern = Regex(""""url"\s*:\s*"(https?://[^"\s]+\.m3u8[^"\s]*)"""")
                val mp4Pattern = Regex(""""url"\s*:\s*"(https?://[^"\s]+\.mp4[^"\s]*)"""")

                var emittedAny = false
                for (m in hlsPattern.findAll(decoded)) {
                    val u = m.groupValues[1]
                    try {
                        generateM3u8(serverName, u, embedUrl).forEach(callback)
                        emittedAny = true
                    } catch (_: Exception) {}
                }
                if (emittedAny) return
                for (m in mp4Pattern.findAll(decoded)) {
                    val u = m.groupValues[1]
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName (direct)",
                            url = u,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = embedUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to browserUA)
                        }
                    )
                    return
                }
            }
            val urlPattern = Regex("""(https?://[^\s"'<>]+(?:\.m3u8|\.mp4)[^\s"'<>]*)""")
            for (m in urlPattern.findAll(html)) {
                val u = m.groupValues[1]
                if (u.endsWith(".m3u8") || u.contains(".m3u8")) {
                    try {
                        generateM3u8(serverName, u, embedUrl).forEach(callback)
                        return
                    } catch (_: Exception) {}
                } else {
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName (direct)",
                            url = u,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = embedUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to browserUA)
                        }
                    )
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractOkRuDirect failed: ${e.message}")
        }
    }

    // ========== VIDHIDE EXTRACTOR (v16) ==========

    /**
     * Vidhide (ads) — morencius.com / vidhidevip.com / vidhidepre.com / vidhide.com
     * La página embed trae un packer Dean Edwards (eval(function(p,a,c,k,e,d)...))
     * que contiene la URL master.m3u8. La m3u8 responde 200 sin Referer.
     */
    private suspend fun extractVidhide(
        embedUrl: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        val trackingCb: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }
        try {
            val html = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to browserUA), timeout = 20L).text
            val packedMatch = Regex(
                """\}\('(.*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)
            if (packedMatch == null) {
                Log.i(TAG, "extractVidhide: packer not found in $embedUrl (len=${html.length})")
                return false
            }
            val p = packedMatch.groupValues[1]
            val base = packedMatch.groupValues[2].toIntOrNull() ?: 0
            val words = packedMatch.groupValues[4].split('|')

            // v18 FIX: base custom (36..62+) — Integer.parseInt solo llega a base 36 y
            // el regex anterior usaba "\b" en string normal (= carácter backspace), nunca matcheaba.
            val baseChars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            fun decodeWord(word: String): Int? {
                if (base !in 2..baseChars.length) {
                    return try { Integer.parseInt(word, base) } catch (_: Exception) { null }
                }
                var n = 0
                for (c in word) {
                    val d = baseChars.indexOf(c)
                    if (d < 0 || d >= base) return null
                    n = n * base + d
                }
                return n
            }

            val unpacked = Regex("[0-9a-zA-Z]+").replace(p) { m ->
                val idx = decodeWord(m.value)
                if (idx != null && idx in words.indices) words[idx].ifEmpty { m.value } else m.value
            }
            Log.i(TAG, "extractVidhide v18: unpacked len=${unpacked.length} (base=$base, words=${words.size})")

            val hlsUrls = LinkedHashMap<String, Int>()
            for (m in Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").findAll(unpacked)) {
                val u = m.groupValues[1]
                    .replace("\\/", "/").replace(Regex("\\\\+u0026"), "&").replace("&amp;", "&")
                if (u.contains(".mp4")) continue
                val q = when {
                    u.contains("1080") -> Qualities.P1080.value
                    u.contains("720") -> Qualities.P720.value
                    u.contains("480") -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                if (!hlsUrls.containsKey(u)) hlsUrls[u] = q
            }
            Log.i(TAG, "extractVidhide v18: ${hlsUrls.size} m3u8 únicos encontrados")
            for ((u, q) in hlsUrls) {
                try {
                    generateM3u8(serverName, u, embedUrl).forEach(trackingCb)
                } catch (e: Exception) {
                    // si generateM3u8 falla (p.ej. no es jugable), emitir la URL cruda como m3u8
                    try {
                        trackingCb(
                            newExtractorLink(
                                source = serverName,
                                name = if (q == Qualities.Unknown.value) serverName else "$serverName ${q / 1000}p",
                                url = u,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = embedUrl
                                this.quality = q
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }

            if (!emitted) {
                for (m in Regex("""(https?://[^"'\s\\]+\.mp4[^"'\s\\]*)""").findAll(unpacked)) {
                    val u = m.groupValues[1]
                        .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                    val q = when {
                        u.contains("1080") -> Qualities.P1080.value
                        u.contains("720") -> Qualities.P720.value
                        u.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                    trackingCb(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName ${q / 1000}p",
                            url = u,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = embedUrl
                            this.quality = q
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractVidhide failed for $embedUrl: ${e.message}")
        }
        return emitted
    }

    // ========== DATA CLASSES ==========

    private data class JsonLdMeta(
        val name: String? = null,
        val description: String? = null,
        val image: String? = null,
        val datePublished: String? = null,
        val productionCompany: ProductionCompany? = null,
        val genre: List<String>? = null,
        val numberOfEpisodes: Int? = null,
        val numberOfSeasons: Int? = null,
    )

    private data class ProductionCompany(
        val name: String? = null
    )

    private data class SeasonMeta(
        val slug: String,
        val label: String,
        val episodeCount: Int,
        val isSpecial: Boolean = false,
        val firstEpNumber: Int = 0,
        val episodeNumbers: List<Int> = emptyList(),
    )

    private data class MovieSource(
        val label: String,
        val name: String,
        val token: String,
        val type: String,
        val provider: String,
        val id: String = "",
    ) {
        fun deriveContentId(): String {
            val uuidPattern = Regex("""([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""")
            return uuidPattern.find(id)?.groupValues?.get(1) ?: ""
        }
    }


    private data class CapturedWebViewData(
        val next_f: String? = null,
        val nextData: String? = null,
        val videos: List<String>? = null,
        val dataUrls: List<String>? = null,
        val fetchResponses: List<FetchResponseData>? = null,
        val html: String? = null,
        val htmlLength: Int? = null
    )

    private data class FetchResponseData(
        val url: String? = null,
        val status: Int? = null,
        val body: String? = null
    )

    private data class CapturedRumbleData(
        val videoSrcs: List<String>? = null,
        val sourceSrcs: List<String>? = null,
        val iframeSrcs: List<String>? = null,
        val htmlSnapshot: String? = null,
        val fetchUrls: List<RumbleFetchData>? = null,
        val htmlLength: Int? = null,
        val htmlHead: String? = null,
        val docTitle: String? = null,
        val cookieLen: Int? = null,
        val cookieNames: String? = null,
        val error: String? = null
    )

    private data class RumbleFetchData(
        val type: String? = null,
        val url: String? = null,
        val status: Int? = null,
        val body: String? = null
    )
}
