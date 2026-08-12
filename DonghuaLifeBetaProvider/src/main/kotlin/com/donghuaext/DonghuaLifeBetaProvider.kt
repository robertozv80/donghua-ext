package com.donghuaext

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
import kotlin.collections.ArrayList

/**
 * DonghuaLifeBetaProvider — Provider para https://beta.donghualife.com
 *
 * v2 (2026-08-12): Correcciones tras pruebas en app real:
 *   - Homepage: selectores por #id (más confiables que h2.section-title)
 *   - Series load(): usa JSON seasons[] del RSC payload (no scrapea /watch/ links,
 *     evitando picks erróneos de botones "Capítulo 1" y "Ver Último")
 *   - Episode loadLinks(): parsea JSON del episodio page y obtiene servers[] directos
 *     (NO usa /api/sources para episodios — las URLs están en plano en el JSON)
 *   - Movie loadLinks(): prueba GET /api/sources?movieId=<uuid> primero, POST como fallback
 *   - Detalle: agrega status (En Emisión/Finalizado) y fecha desde HTML visible
 *   - Search: prueba múltiples URL patterns (/search?q=, /?q=, /api/search?q=)
 */
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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos Episodios",
        "$mainUrl/#recomendaciones" to "Recomendaciones",
        "$mainUrl/#tendencias" to "Tendencias",
        "$mainUrl/rankings" to "Ranking",
        "$mainUrl/series?sort=latest" to "Series",
        "$mainUrl/peliculas?sort=newest" to "Películas",
    )

    // =========================== UTILIDADES ===========================

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

    /**
     * Extrae y decodifica todos los payloads self.__next_f.push([1,"..."]) del HTML.
     * Retorna el texto combinado con escapes JS resueltos (\" -> ", \\ -> \, etc.).
     */
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

    // =========================== PÁGINA PRINCIPAL ===========================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val home = ArrayList<SearchResponse>()
        var hasNext = false

        when {
            // ====== Sección 1: ÚLTIMOS EPISODIOS (home) ======
            // Selectores por #id (más confiable que h2.section-title que no existe)
            // Estructura: <div id="latest-episodes-scroll"><a class="group block..." href="/watch/...">
            url.endsWith("/") && !url.contains("#") -> {
                val doc = app.get(url, timeout = 60).document
                doc.select("#latest-episodes-scroll a[href*='/watch/']").forEach { a ->
                    val href = a.attr("href")
                    // El título está en <p class="line-clamp-2..."> dentro del <a>
                    val title = a.selectFirst("p.line-clamp-2")?.text()?.trim()
                        ?: a.selectFirst("img")?.attr("alt")?.trim()
                        ?: href.substringAfterLast("/").replace("-", " ")
                    // Número de episodio: badge <span>EP 152</span> o del href
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

            // ====== Sección 2: RECOMENDACIONES (home) ======
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

            // ====== Sección 3: TENDENCIAS (home, ranking #1-#10) ======
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

            // ====== Sección 4: RANKING (/rankings) ======
            url.contains("/rankings") -> {
                val doc = app.get(url, timeout = 60).document
                doc.select("a[href^='/series/']").forEach { a ->
                    val href = a.attr("href")
                    if (!href.startsWith("/series/")) return@forEach
                    val title = a.selectFirst("h2")?.text()?.trim() ?: return@forEach
                    val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                    // Rating: <span class="font-black">4.6</span> + <span class="text-muted">30 votos</span>
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

            // ====== Sección 5: SERIES (/series?sort=latest) ======
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

            // ====== Sección 6: PELÍCULAS (/peliculas?sort=newest) ======
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

    // =========================== BÚSQUEDA ===========================

    override suspend fun search(query: String): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // Probar múltiples URL patterns (no sabemos cuál usa el sitio)
        val searchUrls = listOf(
            "$mainUrl/search?q=$encodedQuery",
            "$mainUrl/?q=$encodedQuery",
            "$mainUrl/api/search?q=$encodedQuery",
        )

        for (searchUrl in searchUrls) {
            try {
                val response = app.get(searchUrl, timeout = 30)
                val doc = response.document
                val html = doc.html()

                // Si es JSON (API), parsearlo
                if (searchUrl.contains("/api/") || response.text.trimStart().startsWith("[")) {
                    val text = response.text
                    val jsonResults = Regex(""""url":"(/(?:series|peliculas)/[^"]+)"""").findAll(text).toList()
                    if (jsonResults.isNotEmpty()) {
                        for (m in jsonResults) {
                            val href = m.groupValues[1]
                            val title = Regex(""""title":"([^"]+)"""").find(text)?.groupValues?.get(1)
                                ?: href.substringAfterLast("/").replace("-", " ")
                            val tvType = if (href.startsWith("/peliculas/")) TvType.AnimeMovie else TvType.Anime
                            results.add(
                                newAnimeSearchResponse(title, resolveUrl(href), tvType) {
                                    addDubStatus(DubStatus.Subbed)
                                }
                            )
                        }
                        if (results.isNotEmpty()) return results
                    }
                }

                // HTML: buscar cards de series/películas
                doc.select("a.poster-card[href^='/series/'], a.poster-card[href^='/peliculas/']").forEach { a ->
                    val href = a.attr("href")
                    val title = a.selectFirst("img")?.attr("alt")?.trim()
                        ?: a.selectFirst("p.font-black")?.text()?.trim()
                        ?: return@forEach
                    val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                    val tvType = if (href.startsWith("/peliculas/")) TvType.AnimeMovie else TvType.Anime
                    results.add(
                        newAnimeSearchResponse(title, resolveUrl(href), tvType) {
                            this.posterUrl = poster
                            addDubStatus(DubStatus.Subbed)
                        }
                    )
                }
                if (results.isNotEmpty()) return results
            } catch (_: Exception) {
                continue
            }
        }
        return results
    }

    // =========================== LOAD (detalle) ===========================

    override suspend fun load(url: String): LoadResponse {
        val isMovie = url.contains("/peliculas/")
        val isWatch = url.contains("/watch/")
        val isSeries = url.contains("/series/")

        // Si la URL es /watch/<slug>-<temp>-<ep>, resolver a la serie
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

        // ====== Metadata desde JSON-LD schema.org ======
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

        // ====== Metadata desde HTML ======
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

        // ====== Status (En Emisión/Finalizado) desde HTML visible ======
        // Estructura: <span class="text-xs font-black uppercase tracking-widest">En Emisión</span>
        var showStatus: ShowStatus? = null
        val statusSpans = doc.select("span.text-xs.font-black.uppercase.tracking-widest")
        for (s in statusSpans) {
            val txt = s.text().trim()
            when {
                txt.contains("Emisión", ignoreCase = true) -> { showStatus = ShowStatus.Ongoing; break }
                txt.contains("Pausa", ignoreCase = true) -> { showStatus = ShowStatus.Ongoing; break }
                txt.contains("Finalizado", ignoreCase = true) -> { showStatus = ShowStatus.Completed; break }
            }
        }

        // ====== Fecha de estreno desde HTML (formato "7 Marzo, 2020") ======
        var releaseDate = ""
        for (s in statusSpans) {
            val txt = s.text().trim()
            // Patrón: día + mes + año (acepta "7 Marzo, 2020", "15 Enero 2025", etc.)
            if (Regex("""\d+\s+[A-Za-záéíóú]+,?\s+\d{4}""").matches(txt)) {
                releaseDate = txt
                break
            }
        }

        // ====== Si es película, devolver MovieLoadResponse ======
        if (isMovie) {
            val movieId = extractContentIdFromPayload(rscPayload, "movieId") ?: ""
            val dataUrl = if (movieId.isNotBlank()) {
                "$seriesUrl##movieId=$movieId"
            } else {
                seriesUrl
            }
            return newMovieLoadResponse(title, dataUrl, TvType.AnimeMovie, dataUrl) {
                posterUrl = poster
                plot = buildPlot(description, releaseDate, jsonLdNumEps, jsonLdNumSeasons, showStatus)
                tags = genres
                year = releaseDate.substringAfterLast(",").trim().toIntOrNull()
                    ?: jsonLdDate.takeIf { it.isNotBlank() }?.substring(0, 4)?.toIntOrNull()
            }
        }

        // ====== Si es serie, extraer temporadas y episodios desde JSON seasons[] ======
        val episodes = ArrayList<Episode>()
        val seasons = extractSeasonsFromPayload(rscPayload)

        for ((idx, season) in seasons.withIndex()) {
            val seasonNum = idx + 1
            val seasonSlug = season.slug
            val episodeCount = season.episodeCount
            val firstEpNum = season.firstEpisodeNumber

            if (firstEpNum > 0 && episodeCount > 0) {
                // Construir URLs /watch/{seasonSlug}-{N} para cada episodio
                for (epOffset in 0 until episodeCount) {
                    val epNum = firstEpNum + epOffset
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
        }

        // Fallback: si no encontramos seasons en JSON, scrapear /watch/ links del HTML
        // (excluyendo botones de navegación "Capítulo 1" y "Ver Último")
        if (episodes.isEmpty()) {
            // Seleccionar SOLO cards de episodios (clase aspect-video), no botones
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
            plot = buildPlot(description, releaseDate, jsonLdNumEps, jsonLdNumSeasons, showStatus)
            tags = genres
            year = releaseDate.substringAfterLast(",").trim().toIntOrNull()
                ?: jsonLdDate.takeIf { it.isNotBlank() }?.substring(0, 4)?.toIntOrNull()
        }
    }

    private fun buildPlot(
        description: String,
        releaseDate: String,
        numEps: Int,
        numSeasons: Int,
        status: ShowStatus?
    ): String {
        val sb = StringBuilder()
        if (description.isNotBlank()) {
            sb.append(description)
            sb.append("\n\n")
        }
        val meta = mutableListOf<String>()
        if (releaseDate.isNotBlank()) meta.add("Estreno: $releaseDate")
        if (status != null) {
            val statusStr = when (status) {
                ShowStatus.Ongoing -> "En Emisión"
                ShowStatus.Completed -> "Finalizado"
            }
            meta.add("Estado: $statusStr")
        }
        if (numSeasons > 0) meta.add("Temporadas: $numSeasons")
        if (numEps > 0) meta.add("Episodios: $numEps")
        if (meta.isNotEmpty()) {
            sb.append(meta.joinToString(" • "))
        }
        return sb.toString().trim()
    }

    /**
     * Extrae la lista de temporadas desde el RSC payload.
     * Cada temporada: {slug, label, episodeCount, initialEpisodes: [{number, ...}]}
     */
    private fun extractSeasonsFromPayload(payload: String): List<SeasonMeta> {
        val seasons = ArrayList<SeasonMeta>()
        // Buscar "seasons":[ ... ]
        val seasonsStart = payload.find("\"seasons\":[")
        if (seasonsStart < 0) return seasons

        // Encontrar el cierre del array
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

        // Extraer metadata de cada temporada: {slug, label, episodeCount, firstEpNumber}
        // Patrón: {"id":"...","slug":"<slug>","label":"<label>","coverImage":"...","isSpecial":false,"episodeCount":<N>,"initialEpisodes":[{"id":"...","title":"...","number":<N>,...
        val seasonPattern = Regex(
            """\{"id":"[^"]+","slug":"([^"]+)","label":"([^"]+)","coverImage":"[^"]+","isSpecial":(?:true|false),"episodeCount":(\d+),"initialEpisodes":\[\{"id":"[^"]+","title":"[^"]+","number":(\d+)"""
        )
        for (m in seasonPattern.findAll(seasonsArrayStr)) {
            val (slug, label, countStr, firstEpStr) = m.destructured
            seasons.add(
                SeasonMeta(
                    slug = slug,
                    label = label,
                    episodeCount = countStr.toIntOrNull() ?: 0,
                    firstEpisodeNumber = firstEpStr.toIntOrNull() ?: 1,
                )
            )
        }
        return seasons
    }

    private fun String.find(needle: String): Int = this.indexOf(needle)

    /**
     * Extrae movieId o episodeId (UUID) desde el RSC payload decodificado.
     */
    private fun extractContentIdFromPayload(payload: String, key: String): String? {
        // Construir regex: "$key":"<uuid>" — usar escapeRegex para el key (que es literal)
        val pattern = Regex(""""$key"\s*:\s*"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""", RegexOption.IGNORE_CASE)
        return pattern.find(payload)?.groupValues?.get(1)
    }

    // =========================== LOAD LINKS ===========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Limpiar la URL: quitar el anchor ##movieId= si lo trae
        val (cleanUrl, preloadedContentId) = if (data.contains("##")) {
            val parts = data.split("##")
            val cid = parts.getOrNull(1)?.substringAfter("=") ?: ""
            parts[0] to cid
        } else {
            data to ""
        }

        val response = app.get(cleanUrl, timeout = 60)
        val html = response.text
        val rscPayload = extractRscPayload(html)

        val isMovie = cleanUrl.contains("/peliculas/")

        if (isMovie) {
            return loadMovieLinks(cleanUrl, preloadedContentId, rscPayload, subtitleCallback, callback)
        } else {
            return loadEpisodeLinks(cleanUrl, rscPayload, subtitleCallback, callback)
        }
    }

    /**
     * Para episodios: parsear JSON del /watch/ page y obtener servers[] directos.
     * Estructura: cada episode object tiene servers: [{name, url}, ...]
     * Las URLs son directas (rumble.com/embed/..., geo.dailymotion.com/..., etc.)
     */
    private suspend fun loadEpisodeLinks(
        url: String,
        rscPayload: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Buscar todos los servidores de episodios en el payload
        // Patrón: {"name":"<name>","url":"<url>"}
        // dentro del contexto de un episodio (servers:[...])
        val serversPattern = Regex(
            """"servers":\[([^\]]+)\]"""
        )
        val serverEntryPattern = Regex(
            """\{"name":"([^"]+)","url":"([^"]+)"\}"""
        )

        val seenUrls = mutableSetOf<String>()
        var anyEmitted = false

        for (m in serversPattern.findAll(rscPayload)) {
            val serversArrayStr = m.groupValues[1]
            for (sm in serverEntryPattern.findAll(serversArrayStr)) {
                val (rawName, rawUrl) = sm.destructured
                val serverName = rawName.trim()
                // Unescape URL
                val serverUrl = rawUrl
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\\"", "\"")

                if (serverUrl in seenUrls) continue
                seenUrls.add(serverUrl)

                try {
                    when {
                        // Rumble: extractor nativo de CS3 funciona con embed URLs
                        serverUrl.contains("rumble.com") -> {
                            extractRumble(serverUrl, url, serverName.ifBlank { "Rumble" }, callback)
                            anyEmitted = true
                        }
                        // Dailymotion: usar API metadata
                        serverUrl.contains("dailymotion.com") -> {
                            extractDailymotion(serverUrl, url, serverName.ifBlank { "Dailymotion" }, callback)
                            anyEmitted = true
                        }
                        // Stremeable = streamable.com
                        serverUrl.contains("streamable.com") -> {
                            extractStreamable(serverUrl, url, serverName.ifBlank { "Stremeable" }, subtitleCallback, callback)
                            anyEmitted = true
                        }
                        // Ok.ru
                        serverUrl.contains("ok.ru") -> {
                            val okUrl = serverUrl.replace("https://ok.ru", "http://ok.ru")
                            try { loadExtractor(okUrl, url, subtitleCallback, callback); anyEmitted = true } catch (_: Exception) {}
                        }
                        // Otros: loadExtractor genérico
                        else -> {
                            try { loadExtractor(serverUrl, url, subtitleCallback, callback); anyEmitted = true } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
            if (anyEmitted) return true
        }
        return anyEmitted
    }

    /**
     * Para películas: los servidores usan tokens encriptados.
     * Probar GET /api/sources?movieId=<uuid> (más probable según el mensaje de error 400)
     * Si falla, probar POST /api/sources con JSON body como fallback.
     */
    private suspend fun loadMovieLinks(
        url: String,
        preloadedContentId: String,
        rscPayload: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val movieId = if (preloadedContentId.isNotBlank()) preloadedContentId
            else extractContentIdFromPayload(rscPayload, "movieId") ?: ""

        if (movieId.isBlank()) return false

        // Método 1: GET /api/sources?movieId=<uuid>
        var apiResponse: String? = null
        try {
            apiResponse = app.get(
                "$mainUrl/api/sources?movieId=$movieId",
                headers = mapOf(
                    "Accept" to "application/json",
                    "Referer" to url,
                ),
                timeout = 30L
            ).text
        } catch (_: Exception) {}

        // Método 2: POST /api/sources con JSON body (fallback)
        if (apiResponse.isNullOrBlank()) {
            try {
                apiResponse = app.post(
                    "$mainUrl/api/sources",
                    json = mapOf<String, Any>("movieId" to movieId),
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Referer" to url,
                    ),
                    timeout = 30L
                ).text
            } catch (_: Exception) {}
        }

        if (apiResponse.isNullOrBlank()) return false

        // Parsear respuesta: {"success":true,"sources":[{"url":"...","quality":"720p","type":"mp4"}, ...]}
        val parsed = try { parseJson<SourcesResponse>(apiResponse) } catch (_: Exception) { null }
        if (parsed?.success != true) return false

        var anyEmitted = false
        for ((idx, source) in parsed.sources.withIndex()) {
            val srcUrl = source.url ?: source.src ?: source.embedUrl ?: source.iframeUrl ?: ""
            if (srcUrl.isBlank()) continue

            val serverLabel = source.label ?: source.name ?: "Server ${idx + 1}"
            val quality = when {
                source.quality?.contains("1080", ignoreCase = true) == true -> Qualities.P1080.value
                source.quality?.contains("720", ignoreCase = true) == true -> Qualities.P720.value
                source.quality?.contains("480", ignoreCase = true) == true -> Qualities.P480.value
                source.quality?.contains("360", ignoreCase = true) == true -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            val type = source.type?.lowercase() ?: ""

            when {
                // m3u8 directo
                type.contains("m3u8") || srcUrl.endsWith(".m3u8") -> {
                    try {
                        generateM3u8(serverLabel, srcUrl, url).forEach(callback)
                        anyEmitted = true
                    } catch (_: Exception) {}
                }
                // mp4 directo
                type.contains("mp4") || srcUrl.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(
                            source = serverLabel,
                            name = "$serverLabel ${quality / 1000}p",
                            url = srcUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = quality
                        }
                    )
                    anyEmitted = true
                }
                // Embeds conocidos
                srcUrl.contains("rumble.com") || srcUrl.contains("streamable.com") ||
                srcUrl.contains("dailymotion.com") || srcUrl.contains("ok.ru") ||
                srcUrl.contains("vk.com") || srcUrl.contains("vk.ru") -> {
                    try {
                        loadExtractor(srcUrl, url, subtitleCallback, callback)
                        anyEmitted = true
                    } catch (_: Exception) {}
                }
                // Otros
                else -> {
                    try {
                        loadExtractor(srcUrl, url, subtitleCallback, callback)
                        anyEmitted = true
                    } catch (_: Exception) {}
                }
            }
        }
        return anyEmitted
    }

    // =========================== EXTRACTORES DE VIDEO ===========================
    // (Reutilizados del DonghuaLifeProvider confirmado funcional)

    private suspend fun extractRumble(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(embedUrl, referer = referer, timeout = 30L).text

            // Método 1: HLS master playlist "hls":{"auto":{"url":"..."}}
            val hlsAutoPatterns = listOf(
                Regex(""""hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)""""),
                Regex(""""hls"\s*:\s*\{\s*"url"\s*:\s*"([^"]+\.m3u8[^"]*)""""),
                Regex(""""url"\s*:\s*"(https?://rumble\.com/hls-vod/[^"]+\.m3u8[^"]*)""""),
            )
            for (pattern in hlsAutoPatterns) {
                val m = pattern.find(html)
                if (m != null) {
                    val u = m.destructured.component1().replace("\\/", "/").replace("\\u0026", "&")
                    try {
                        generateM3u8(serverName, u, referer).forEach(callback)
                        return
                    } catch (_: Exception) {}
                }
            }

            // Método 2: "ua":{"tar":{"<quality>":{"url":"..."}}}
            val tarBlockMatch = Regex(""""ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})""").find(html)
            if (tarBlockMatch != null) {
                val tarBlock = tarBlockMatch.groupValues[1]
                var foundAny = false
                val tarQualityPattern = Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""")
                tarQualityPattern.findAll(tarBlock).forEach { match ->
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
                    callback(
                        newExtractorLink(source = serverName, name = "$serverName ${qLabel}p", url = u, type = ExtractorLinkType.M3U8) {
                            this.referer = referer
                            this.quality = quality
                        }
                    )
                    foundAny = true
                }
                if (foundAny) return
            }

            // Método 3: URLs CDN rmbl.ws
            val rmblPatterns = listOf(
                Regex("""["'](https?://[^"']*rmbl\.ws[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*rmbl\.ws[^"']*)["']"""),
            )
            for (pattern in rmblPatterns) {
                val matches = pattern.findAll(html).toList()
                if (matches.isNotEmpty()) {
                    for (match in matches) {
                        val u = match.destructured.component1()
                        val quality = when {
                            u.contains("1080") -> Qualities.P1080.value
                            u.contains("720") -> Qualities.P720.value
                            u.contains("480") -> Qualities.P480.value
                            u.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        callback(
                            newExtractorLink(source = serverName, name = "$serverName ${quality / 1000}p", url = u, type = ExtractorLinkType.VIDEO) {
                                this.referer = referer
                                this.quality = quality
                            }
                        )
                    }
                    return
                }
            }

            // Método 4: m3u8 genérico
            val m3u8Patterns = listOf(
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            )
            for (pattern in m3u8Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val u = match.destructured.component1()
                    try {
                        generateM3u8(serverName, u, referer).forEach(callback)
                        return
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractDailymotion(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Regex("video=([A-Za-z0-9]+)").find(embedUrl)?.destructured?.component1()
            ?: Regex("/video/([A-Za-z0-9]+)").find(embedUrl)?.destructured?.component1()
            ?: return

        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val jsonText = app.get(apiUrl,
                referer = "https://www.dailymotion.com/embed/video/$videoId",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                timeout = 15L).text

            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(jsonText)) {
                try {
                    generateM3u8(serverName, match.value, "https://www.dailymotion.com").forEach(callback)
                    return
                } catch (_: Exception) {}
            }
            val mp4Urls = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(jsonText).map { it.value }.distinct().toList()
            for (u in mp4Urls) {
                val q = when {
                    u.contains("1080") -> Qualities.P1080.value
                    u.contains("720") -> Qualities.P720.value
                    u.contains("480") -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                callback(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = u) {
                    this.referer = "https://www.dailymotion.com"
                    this.quality = q
                })
            }
        } catch (_: Exception) {}

        try {
            loadExtractor("https://www.dailymotion.com/embed/video/$videoId", referer, subtitleCallback = {}, callback)
        } catch (_: Exception) {}
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
                    newExtractorLink(source = serverName, name = "$serverName ${quality / 1000}p", url = u, type = ExtractorLinkType.VIDEO) {
                        this.referer = "https://streamable.com/"
                        this.quality = quality
                    }
                )
            }
            if (seen.isNotEmpty()) return
        } catch (_: Exception) {}
        try { loadExtractor(embedUrl, referer, subtitleCallback, callback) } catch (_: Exception) {}
    }

    // =========================== CLASES DE DATOS ===========================

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
        val firstEpisodeNumber: Int,
    )

    private data class SourcesResponse(
        val success: Boolean? = false,
        val sources: List<SourceInfo> = emptyList(),
        val error: String? = null,
    )

    private data class SourceInfo(
        val url: String? = null,
        val src: String? = null,
        val embedUrl: String? = null,
        val iframeUrl: String? = null,
        val label: String? = null,
        val name: String? = null,
        val quality: String? = null,
        val type: String? = null,
        val provider: String? = null,
    )
}
