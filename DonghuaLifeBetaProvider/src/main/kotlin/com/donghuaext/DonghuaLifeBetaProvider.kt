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
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.delay
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

        val seenSlugs = mutableSetOf<String>()

        fun extractCardsFromJsoup(doc: org.jsoup.nodes.Document, pageType: String) {
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
        }

        try {
            for (p in 1..5) {
                val url = if (p == 1) "$mainUrl/series?sort=latest" else "$mainUrl/series?page=$p&sort=latest"
                val response = app.get(url, timeout = 30)
                extractCardsFromJsoup(response.document, "series")
                if (results.size >= 30) break
            }
        } catch (_: Exception) {}

        try {
            for (p in 1..3) {
                val url = if (p == 1) "$mainUrl/peliculas?sort=newest" else "$mainUrl/peliculas?page=$p&sort=newest"
                val response = app.get(url, timeout = 30)
                extractCardsFromJsoup(response.document, "peliculas")
                if (results.size >= 50) break
            }
        } catch (_: Exception) {}

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val isMovie = url.contains("/peliculas/")
        val isWatch = url.contains("/watch/")
        val isSeries = url.contains("/series/")

        val seriesUrl = if (isWatch) {
            val path = url.substringAfter("/watch/")
            val match = Regex("""^(.+)-(\d+)-(\d+)$""").find(path)
            if (match != null) {
                val slug = match.groupValues[1]
                "$mainUrl/series/$slug"
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
            doc.select("a.aspect-video[href*='/watch/']").forEach { a ->
                val href = a.attr("href")
                val match = Regex("""/watch/(.+)-(\d+)-(\d+)$""").find(href)
                if (match != null) {
                    val seasonNum = match.groupValues[2].toIntOrNull() ?: 1
                    val epNum = match.groupValues[3].toIntOrNull() ?: return@forEach
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

    // ========== EPISODE LINKS (con correcciones) ==========
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

        if (webViewCaptured.isNotEmpty() && activeEpId.isBlank() && rscPayload.length < 50000) {
            Log.i(TAG, "$logKey v15 FAST PATH: bot detection + WebView data available, emitting directly")
            val emitted = emitFromWebViewCaptured(webViewCaptured, url, logKey, subtitleCallback, callback)
            if (emitted) {
                Log.i(TAG, "$logKey FINAL anyEmitted=true (v15 fast path via WebView)")
                return true
            }
            Log.i(TAG, "$logKey v15 FAST PATH: WebView emit failed, falling through to v9/v11 strategies")
        }

        if (activeEpId.isBlank() && rscPayload.length < 50000) {
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

        // ===== CORRECCIÓN: PRIORIZAR sources con token ANTES que servers directos =====
        if (activeEpId.isNotBlank()) {
            val epSources = extractSourcesNearEpisode(rscPayload, activeEpId)
            Log.i(TAG, "$logKey epSources with tokens: ${epSources.size} labels=[${epSources.joinToString(",") { it.label }}]")
            if (epSources.isNotEmpty()) {
                val emitted = loadSourcesViaApi(epSources, activeEpId, url, subtitleCallback, callback, logKey)
                Log.i(TAG, "$logKey loadSourcesViaApi emitted=$emitted")
                if (emitted) anyEmitted = true
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
                    val epServers = extractServersByNumber(rscPayload, seasonSlug, epNum)
                    if (epServers.isNotEmpty()) {
                        val emitted = emitEpisodeServers(epServers, url, subtitleCallback, callback)
                        if (emitted) anyEmitted = true
                    }
                }
            }
        }

        // Último recurso: primer servers[] del RSC (pero solo si no hay nada más)
        if (!anyEmitted) {
            val firstServers = extractFirstServersArray(rscPayload)
            if (firstServers.isNotEmpty()) {
                Log.w(TAG, "$logKey WARNING: using first servers array (may be wrong episode)")
                val emitted = emitEpisodeServers(firstServers, url, subtitleCallback, callback)
                if (emitted) anyEmitted = true
            }
        }

        // v11: estrategias alternativas (endpoints + decrypt) si todo falló
        if (!anyEmitted) {
            Log.i(TAG, "$logKey v11: trying alternative strategies (endpoints + JS + token decrypt)")
            val allSources = extractAllSourcesFromRsc(rscPayload)
            for ((src, contentId) in allSources) {
                Log.i(TAG, "$logKey v11 ALT_ENDPOINTS for contentId=$contentId label=${src.label}")
                val altResp = tryAlternativeEndpoints(contentId, src.token, false, url, logKey)
                if (altResp.isNotBlank()) {
                    if (emitFromApiResponse(altResp, url, subtitleCallback, callback, defaultLabel = src.label)) {
                        anyEmitted = true
                    }
                }
            }
            if (!anyEmitted) {
                for ((src, _) in allSources) {
                    Log.i(TAG, "$logKey v11 TOKEN_DECRYPT label=${src.label} provider=${src.provider}")
                    val decryptedUrl = decryptTokenAesCbc(src.token, logKey)
                    if (decryptedUrl.isNotBlank()) {
                        Log.i(TAG, "$logKey v11 TOKEN_DECRYPT SUCCESS: $decryptedUrl")
                        val linkType = when {
                            decryptedUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                            decryptedUrl.contains(".mp4") -> ExtractorLinkType.VIDEO
                            else -> ExtractorLinkType.DASH
                        }
                        callback(
                            newExtractorLink(
                                source = src.label,
                                name = src.label,
                                url = decryptedUrl,
                                type = linkType
                            ) {
                                this.referer = url
                                this.headers = mapOf("Origin" to mainUrl, "User-Agent" to browserUA)
                            }
                        )
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

    // ========== EXTRACTORES DE VIDEO ==========

    // EXTRACTOR MANUAL PARA OK.RU (nuevo)
    private suspend fun extractOkRu(
        videoUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to browserUA), timeout = 15L).text
            // data-options
            val dataMatch = Regex("""data-options="([^"]+)"""").find(html)
            if (dataMatch != null) {
                val optionsJson = dataMatch.destructured.component1().replace("&quot;", "\"").replace("&amp;", "&")
                for (match in Regex("""(https?://[^"]+\.(?:mp4|m3u8)[^"]*)""").findAll(optionsJson)) {
                    callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                        this.referer = videoUrl
                        this.quality = Qualities.Unknown.value
                    })
                    return true
                }
            }
            // og:video
            Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""").find(html)?.let { m ->
                callback(newExtractorLink(source = serverName, name = serverName, url = m.destructured.component1()) {
                    this.referer = videoUrl
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
            // cualquier mp4/m3u8
            for (match in Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = match.value) {
                    this.referer = videoUrl
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}
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
                val hlsPattern = Regex(""""url"\s*:\s*"(https?://[^"\s]+\.m3u8[^"\s]*)"""")
                val mp4Pattern = Regex(""""url"\s*:\s*"(https?://[^"\s]+\.mp4[^"\s]*)"""")

                for (m in hlsPattern.findAll(decoded)) {
                    val u = m.groupValues[1]
                    try {
                        generateM3u8(serverName, u, embedUrl).forEach(callback)
                        return
                    } catch (_: Exception) {}
                }
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
                        }
                    )
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractOkRuDirect failed: ${e.message}")
        }
    }

    // ========== EMIT EPISODE SERVERS (con corrección para OK.RU) ==========
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
                    // Rumble
                    serverUrlFixed.contains("rumble.com") -> {
                        val emittedHere = extractRumble(serverUrlFixed, referer, name, trackingCallback)
                        if (!emittedHere && !anyEmitted) {
                            Log.i(TAG, "v15 Rumble: custom extractRumble failed, trying loadExtractor fallback for $serverUrlFixed")
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (e: Exception) {
                                Log.w(TAG, "v15 Rumble: loadExtractor also failed: ${e.message}")
                            }
                        }
                    }
                    // Dailymotion
                    serverUrlFixed.contains("dailymotion.com") || serverUrlFixed.contains("geo.dailymotion.com") -> {
                        val emittedHere = extractDailymotion(serverUrlFixed, referer, name, trackingCallback)
                        if (!emittedHere && !anyEmitted) {
                            Log.i(TAG, "v15 Dailymotion: custom extractDailymotion failed, trying loadExtractor fallback for $serverUrlFixed")
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (e: Exception) {
                                Log.w(TAG, "v15 Dailymotion: loadExtractor also failed: ${e.message}")
                            }
                        }
                    }
                    // Stremeable
                    serverUrlFixed.contains("streamable.com") -> {
                        extractStreamable(serverUrlFixed, referer, name, subtitleCallback, trackingCallback)
                        if (!anyEmitted) {
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (_: Exception) {}
                        }
                    }
                    // ====== FIX: OK.RU con extractor manual ======
                    serverUrlFixed.contains("ok.ru") -> {
                        // Primero intentar extractor manual
                        if (!extractOkRu(serverUrlFixed, referer, name, trackingCallback)) {
                            // Fallback a loadExtractor
                            loadExtractor(normalizedUrl, referer, subtitleCallback, trackingCallback)
                            if (!anyEmitted) {
                                try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (_: Exception) {}
                            }
                            if (!anyEmitted) {
                                try { extractOkRuDirect(serverUrlFixed, referer, name, trackingCallback) } catch (_: Exception) {}
                            }
                        }
                    }
                    // Direct mp4/m3u8
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
                    // Voe.sx
                    serverUrlFixed.contains("voe.sx") -> {
                        loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback)
                    }
                    // Filemoon
                    serverUrlFixed.contains("filemoon") || serverUrlFixed.contains("moonplayer") -> {
                        loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback)
                    }
                    // Otros
                    else -> {
                        loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback)
                    }
                }
            } catch (_: Exception) {}
        }
        return anyEmitted
    }

    // ========== EL RESTO DE FUNCIONES (extraídas del original, no modificadas) ==========
    // Asegúrate de que todas las funciones existentes estén aquí, como:
    // - extractServersForEpisode
    // - extractServersByNumber
    // - extractFirstServersArray
    // - extractServersFromHtml
    // - emitFromWebViewCaptured
    // - loadMovieLinks
    // - loadSourcesViaApi
    // - extractSourcesNearEpisode
    // - tryWebViewResolver
    // - extractAllSourcesFromRsc
    // - tryAlternativeEndpoints
    // - decryptTokenAesCbc
    // - emitFromApiResponse
    // - extractRumble
    // - extractDailymotion
    // - extractStreamable
    // - y las clases de datos (JsonLdMeta, SeasonMeta, MovieSource, CapturedWebViewData, etc.)

    // ... (todas las funciones y clases que no se han modificado y ya estaban en tu archivo original)
}
