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
 * v3 (2026-08-12): Correcciones tras pruebas reales en app:
 *   - loadEpisodeLinks: usa activeEpisodeId para encontrar el episodio específico
 *     (antes extraía servers de TODOS los episodios de la temporada, ahora solo del activo)
 *   - loadMovieLinks: prueba múltiples formatos de API (movieId, token, sourceId)
 *     y parsea defensivamente cualquier URL en la respuesta
 *   - Multi-temporada: usa 1..episodeCount como rango (antes usaba firstEpNum..firstEpNum+N que era incorrecto)
 *   - Regex de temporadas: ahora matchea cualquier temporada (no requiere initialEpisodes[0])
 *   - search: scrapea /series y /peliculas (páginas 1-5) y filtra por query
 *     (el sitio no filtra server-side, hay que hacerlo client-side)
 *   - Metadata: usa campos nativos de CS3 (showStatus, duration, rating, year, tags)
 *     en lugar de incluirlos en el plot — aparecen junto a PROVEEDOR/TIPO/AÑO
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
     * Retorna el texto combinado con escapes JS resueltos.
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

            // ====== Sección 3: TENDENCIAS (home) ======
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

    /**
     * Búsqueda: el sitio NO filtra server-side con ?q=, así que scrapeamos
     * las primeras páginas de /series y /peliculas y filtramos client-side.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        val queryLower = query.lowercase().trim()
        if (queryLower.isBlank()) return results

        // Pool compartido para evitar duplicados por slug
        val seenSlugs = mutableSetOf<String>()

        // Helper: extraer y filtrar cards desde un org.jsoup.nodes.Document
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

                // Filtro client-side: el título debe contener el query
                if (!title.lowercase().contains(queryLower) &&
                    !slug.lowercase().contains(queryLower)) {
                    return@forEach
                }

                seenSlugs.add(slug)
                val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                val tvType = if (pageType == "peliculas") TvType.AnimeMovie else TvType.Anime

                // Datos extra para mostrar
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

        // Scrapear /series (páginas 1-5 = 125 series)
        try {
            for (p in 1..5) {
                val url = if (p == 1) "$mainUrl/series?sort=latest" else "$mainUrl/series?page=$p&sort=latest"
                val response = app.get(url, timeout = 30)
                extractCardsFromJsoup(response.document, "series")
                if (results.size >= 30) break  // Suficientes resultados
            }
        } catch (_: Exception) {}

        // Scrapear /peliculas (páginas 1-3 = 75 películas)
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

        // ====== Metadata visible desde HTML ======
        // Estructura: <span class="text-xs font-black uppercase tracking-widest">X</span>
        // dentro de divs con svg icons (lucide-calendar, lucide-clock, lucide-film, lucide-layers)
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

        // ====== Extraer pills de metadata (Estado, Fecha, Duración, Capítulos) ======
        // Cada pill es: <div class="flex items-center gap-2 ...">
        //   <svg class="lucide lucide-<ICON> ..." />
        //   <span class="text-xs font-black uppercase tracking-widest">VALUE</span>
        // </div>
        var showStatus: ShowStatus? = null
        var releaseDateStr = ""
        var durationMinutes = 0

        // Buscar todos los pills por su contenido de texto
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
                // Fecha: "7 Marzo, 2020" o "15 Enero 2025"
                Regex("""\d+\s+de?\s*[A-Za-záéíóú]+,?\s+\d{4}""").matches(text) ||
                Regex("""\d+\s+[A-Za-záéíóú]+,?\s+\d{4}""").matches(text) -> {
                    releaseDateStr = text
                }
                // Duración: "Duración: 7" o "Duración: 120"
                text.startsWith("Duración", ignoreCase = true) ||
                text.startsWith("Duracion", ignoreCase = true) -> {
                    val numMatch = Regex("""(\d+)""").find(text)
                    numMatch?.groupValues?.get(1)?.toIntOrNull()?.let { durationMinutes = it }
                }
            }
        }

        // Año desde releaseDateStr o JSON-LD
        val yearInt = releaseDateStr.substringAfterLast(",").trim().substringBefore(" ").toIntOrNull()
            ?: releaseDateStr.substringAfterLast(" ").trim().toIntOrNull()
            ?: jsonLdDate.takeIf { it.isNotBlank() }?.substring(0, 4)?.toIntOrNull()

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
                plot = description
                tags = genres
                year = yearInt
                if (durationMinutes > 0) this.duration = durationMinutes * 60
                showStatus = showStatus
            }
        }

        // ====== Si es serie, extraer temporadas y episodios desde JSON seasons[] ======
        val episodes = ArrayList<Episode>()
        val seasons = extractSeasonsFromPayload(rscPayload)

        for ((idx, season) in seasons.withIndex()) {
            val seasonNum = idx + 1
            val seasonSlug = season.slug
            val episodeCount = season.episodeCount

            if (episodeCount > 0) {
                // Construir URLs /watch/{seasonSlug}-{N} para cada episodio
                // Los episodios SIEMPRE empiezan en 1 dentro de cada temporada
                for (epNum in 1..episodeCount) {
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
            plot = description
            tags = genres
            year = yearInt
            if (durationMinutes > 0) this.duration = durationMinutes * 60
        }
    }

    /**
     * Extrae la lista de temporadas desde el RSC payload.
     * Cada temporada: {slug, label, episodeCount, initialEpisodes: [{number, ...}]}
     *
     * NOTA: initialEpisodes[0].number NO es el primer episodio de la temporada,
     * es uno de los episodios más recientes mostrados en la UI. Los episodios
     * siempre empiezan en 1 dentro de cada temporada.
     */
    private fun extractSeasonsFromPayload(payload: String): List<SeasonMeta> {
        val seasons = ArrayList<SeasonMeta>()
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

        // Patrón más flexible: captura slug, label, episodeCount sin requerir initialEpisodes
        // Pattern: {"id":"...","slug":"<slug>","label":"<label>","coverImage":"...","isSpecial":<bool>,"episodeCount":<N>
        val seasonPattern = Regex(
            """\{"id":"[^"]+","slug":"([^"]+)","label":"([^"]+)","coverImage":"[^"]*","isSpecial":(?:true|false),"episodeCount":(\d+)"""
        )
        for (m in seasonPattern.findAll(seasonsArrayStr)) {
            val slug = m.groupValues[1]
            val label = m.groupValues[2]
            val countStr = m.groupValues[3]
            seasons.add(
                SeasonMeta(
                    slug = slug,
                    label = label,
                    episodeCount = countStr.toIntOrNull() ?: 0,
                )
            )
        }
        return seasons
    }

    private fun String.find(needle: String): Int = this.indexOf(needle)
    private fun String.find(needle: String, startIndex: Int): Int = this.indexOf(needle, startIndex)

    /**
     * Extrae movieId o episodeId (UUID) desde el RSC payload decodificado.
     */
    private fun extractContentIdFromPayload(payload: String, key: String): String? {
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
     * Para episodios: el RSC payload contiene TODOS los episodios de la temporada,
     * cada uno con su propio servers[] array. Necesitamos encontrar el episodio
     * ACTIVO (activeEpisodeId) y extraer solo sus servers.
     *
     * Estructura: ...,"activeEpisodeId":"<uuid>",...
     * Y cada episodio: {"id":"<uuid>","url":"...","seasonSlug":"...","number":N,...,"servers":[{"name":"X","url":"Y"}]}
     */
    private suspend fun loadEpisodeLinks(
        url: String,
        rscPayload: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Encontrar activeEpisodeId
        val activeEpIdMatch = Regex(""""activeEpisodeId":"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""")
            .find(rscPayload)
        val activeEpId = activeEpIdMatch?.groupValues?.get(1) ?: ""

        // 2. Si tenemos activeEpisodeId, buscar el episodio específico y extraer sus servers
        if (activeEpId.isNotBlank()) {
            val epServers = extractServersForEpisode(rscPayload, activeEpId)
            if (epServers.isNotEmpty()) {
                val anyEmitted = emitEpisodeServers(epServers, url, subtitleCallback, callback)
                if (anyEmitted) return true
            }
        }

        // 3. Fallback: si no encontramos activeEpisodeId o no emitió links,
        // intentar parsear el URL para identificar seasonSlug + epNum y buscar el episodio por número
        val urlPath = url.substringAfter("/watch/", "")
        val urlMatch = Regex("""^(.+)-(\d+)-(\d+)$""").find(urlPath)
        if (urlMatch != null) {
            val seasonSlug = urlMatch.groupValues[1]
            val epNum = urlMatch.groupValues[3].toIntOrNull() ?: 0
            if (epNum > 0) {
                val epServers = extractServersByNumber(rscPayload, seasonSlug, epNum)
                if (epServers.isNotEmpty()) {
                    val anyEmitted = emitEpisodeServers(epServers, url, subtitleCallback, callback)
                    if (anyEmitted) return true
                }
            }
        }

        // 4. Último recurso: extraer el PRIMER servers[] array que aparezca
        // (puede no ser el episodio correcto, pero al menos da links)
        val firstServers = extractFirstServersArray(rscPayload)
        if (firstServers.isNotEmpty()) {
            val anyEmitted = emitEpisodeServers(firstServers, url, subtitleCallback, callback)
            if (anyEmitted) return true
        }

        return false
    }

    /**
     * Extrae los servers[] de un episodio específico por su UUID.
     * Busca el objeto episodio {"id":"<uuid>",...,"servers":[...]} y devuelve los servers.
     */
    private fun extractServersForEpisode(payload: String, episodeId: String): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        // Buscar el episodio por su ID, luego encontrar su servers[] array
        val idPos = payload.find("\"id\":\"$episodeId\"")
        if (idPos < 0) return servers

        // Buscar el siguiente "servers":[ después del episodio
        val serversStart = payload.find("\"servers\":[", idPos)
        if (serversStart < 0) return servers

        // Encontrar el cierre del array
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

        // Extraer entradas {"name":"X","url":"Y"}
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

    /**
     * Extrae servers por seasonSlug + número de episodio.
     * Busca el episodio con seasonSlug="<slug>" y number=<epNum>, luego su servers[].
     */
    private fun extractServersByNumber(payload: String, seasonSlug: String, epNum: Int): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        // Buscar el episodio por seasonSlug y number
        // Estructura: ...,"seasonSlug":"<slug>","title":"...","number":<N>,...,"servers":[...]
        // NOTA: agregamos "," después del número para evitar falsos positivos
        // (sin la coma, "number":1 también matchearía "number":15, "number":100, etc.)
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

    /**
     * Extrae el primer servers[] array encontrado en el payload (fallback).
     */
    private fun extractFirstServersArray(payload: String): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        val firstPos = payload.find("\"servers\":[")
        if (firstPos < 0) return servers
        val arrayStart = firstPos + "\"servers\":[".length
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

    /**
     * Emite ExtractorLinks para una lista de (name, url) de servers de episodio.
     * Retorna true si al menos un callback fue invocado.
     */
    private suspend fun emitEpisodeServers(
        servers: List<Pair<String, String>>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anyEmitted = false
        for ((serverName, serverUrl) in servers) {
            val name = serverName.trim().ifBlank { "Server" }
            try {
                when {
                    // Rumble
                    serverUrl.contains("rumble.com") -> {
                        extractRumble(serverUrl, referer, name, callback)
                    }
                    // Dailymotion
                    serverUrl.contains("dailymotion.com") -> {
                        extractDailymotion(serverUrl, referer, name, callback)
                    }
                    // Stremeable = streamable.com
                    serverUrl.contains("streamable.com") -> {
                        extractStreamable(serverUrl, referer, name, subtitleCallback, callback)
                    }
                    // Ok.ru (CS3 tiene extractor nativo)
                    serverUrl.contains("ok.ru") -> {
                        loadExtractor(serverUrl, referer, subtitleCallback, callback)
                    }
                    // Voe.sx
                    serverUrl.contains("voe.sx") -> {
                        loadExtractor(serverUrl, referer, subtitleCallback, callback)
                    }
                    // Filemoon
                    serverUrl.contains("filemoon") || serverUrl.contains("moonplayer") -> {
                        loadExtractor(serverUrl, referer, subtitleCallback, callback)
                    }
                    // Otros: loadExtractor genérico
                    else -> {
                        loadExtractor(serverUrl, referer, subtitleCallback, callback)
                    }
                }
            } catch (_: Exception) {}
            // Nota: no podemos saber con certeza si el callback fue invocado,
            // pero asumimos que si loadExtractor/extractX no lanza excepción, intentó emitir.
            anyEmitted = true
        }
        return anyEmitted
    }

    /**
     * Para películas: el RSC payload contiene el array sources[] con tokens encriptados.
     * Cada source tiene: {id, label, name, token, type, provider, ...}
     *
     * Estrategia: probar múltiples formatos de API call hasta encontrar uno que funcione:
     * 1. POST /api/sources con {movieId: "<uuid>"}
     * 2. POST /api/sources con {token: "<token>"} por cada source
     * 3. POST /api/sources con {movieId: "<uuid>", token: "<token>"}
     * 4. GET /api/sources?movieId=<uuid>
     * 5. GET /api/sources?token=<token>
     *
     * La respuesta se parsea defensivamente buscando URLs de video en cualquier campo.
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

        // Extraer la lista de sources con tokens desde el payload
        val sources = extractMovieSources(rscPayload)
        if (movieId.isBlank() && sources.isEmpty()) return false

        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to url,
            "Content-Type" to "application/json",
        )

        // ====== Método 1: POST /api/sources con {movieId: "<uuid>"} ======
        if (movieId.isNotBlank()) {
            try {
                val resp = app.post(
                    "$mainUrl/api/sources",
                    json = mapOf<String, Any>("movieId" to movieId),
                    headers = headers,
                    timeout = 30L
                ).text
                if (resp.isNotBlank() && resp != "{}") {
                    if (emitFromApiResponse(resp, url, subtitleCallback, callback)) return true
                }
            } catch (_: Exception) {}
        }

        // ====== Método 2: POST /api/sources con {token: "<token>"} por cada source ======
        for (source in sources) {
            val token = source.token
            if (token.isBlank()) continue
            try {
                val resp = app.post(
                    "$mainUrl/api/sources",
                    json = mapOf<String, Any>("token" to token),
                    headers = headers,
                    timeout = 30L
                ).text
                if (resp.isNotBlank() && resp != "{}") {
                    if (emitFromApiResponse(resp, url, subtitleCallback, callback, defaultLabel = source.label)) return true
                }
            } catch (_: Exception) {}
        }

        // ====== Método 3: POST /api/sources con {movieId, token} por cada source ======
        if (movieId.isNotBlank()) {
            for (source in sources) {
                val token = source.token
                if (token.isBlank()) continue
                try {
                    val resp = app.post(
                        "$mainUrl/api/sources",
                        json = mapOf<String, Any>(
                            "movieId" to movieId,
                            "token" to token
                        ),
                        headers = headers,
                        timeout = 30L
                    ).text
                    if (resp.isNotBlank() && resp != "{}") {
                        if (emitFromApiResponse(resp, url, subtitleCallback, callback, defaultLabel = source.label)) return true
                    }
                } catch (_: Exception) {}
            }
        }

        // ====== Método 4: GET /api/sources?movieId=<uuid> ======
        if (movieId.isNotBlank()) {
            try {
                val resp = app.get(
                    "$mainUrl/api/sources?movieId=$movieId",
                    headers = headers,
                    timeout = 30L
                ).text
                if (resp.isNotBlank() && resp != "{}") {
                    if (emitFromApiResponse(resp, url, subtitleCallback, callback)) return true
                }
            } catch (_: Exception) {}
        }

        // ====== Método 5: GET /api/sources?token=<token> por cada source ======
        for (source in sources) {
            val token = source.token
            if (token.isBlank()) continue
            try {
                val resp = app.get(
                    "$mainUrl/api/sources?token=$token",
                    headers = headers,
                    timeout = 30L
                ).text
                if (resp.isNotBlank() && resp != "{}") {
                    if (emitFromApiResponse(resp, url, subtitleCallback, callback, defaultLabel = source.label)) return true
                }
            } catch (_: Exception) {}
        }

        // ====== Método 6: Para fuentes Rumble/VK conocidas, intentar loadExtractor directo ======
        // Algunos tokens pueden decodificar a URLs directas en el cliente
        // (raro pero posible si el token es solo base64)
        for (source in sources) {
            val token = source.token
            if (token.isBlank()) continue
            // Intentar base64 decode del token por si contiene URL directa
            try {
                val decoded = java.util.Base64.getDecoder().decode(token)
                val decodedStr = String(decoded, Charsets.UTF_8)
                // Buscar URL en el contenido decodificado
                val urlMatch = Regex("""https?://[^\s"']+""").find(decodedStr)
                if (urlMatch != null) {
                    val directUrl = urlMatch.value
                    if (directUrl.contains("rumble.com") || directUrl.contains("dailymotion.com") ||
                        directUrl.contains("vk.com") || directUrl.contains("ok.ru")) {
                        try {
                            loadExtractor(directUrl, url, subtitleCallback, callback)
                            // Si loadExtractor llegó aquí, asumimos éxito
                            return true
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        return false
    }

    /**
     * Extrae la lista de sources con tokens desde el RSC payload de una película.
     * Cada source: {id, label, name, token, type, provider, ...}
     */
    private fun extractMovieSources(payload: String): List<MovieSource> {
        val sources = ArrayList<MovieSource>()
        // Buscar "sources":[ ... ] en el payload
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

        // Patrón: {"id":"...","label":"X","name":"Y","token":"Z","type":"video","provider":"P",...}
        val sourcePattern = Regex(
            """\{"id":"[^"]+","label":"([^"]+)","name":"([^"]+)","token":"([^"]+)","type":"([^"]+)","provider":"([^"]+)""""
        )
        for (m in sourcePattern.findAll(sourcesArrayStr)) {
            sources.add(
                MovieSource(
                    label = m.groupValues[1],
                    name = m.groupValues[2],
                    token = m.groupValues[3],
                    type = m.groupValues[4],
                    provider = m.groupValues[5],
                )
            )
        }
        return sources
    }

    /**
     * Parsea defensivamente la respuesta de /api/sources buscando URLs en cualquier campo.
     * Acepta múltiples formatos:
     *   - {"success":true,"sources":[{"url":"...","quality":"720p"}]}
     *   - {"sources":[{"url":"..."}]}
     *   - {"url":"..."}
     *   - Cualquier JSON con URLs http(s)://...
     */
    private suspend fun emitFromApiResponse(
        response: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        defaultLabel: String = "Server"
    ): Boolean {
        var anyEmitted = false

        // Intentar parsear como JSON estructurado
        val parsed: SourcesResponse? = try { parseJson<SourcesResponse>(response) } catch (_: Exception) { null }

        if (parsed != null && parsed.sources.isNotEmpty()) {
            for ((idx, source) in parsed.sources.withIndex()) {
                val srcUrl = source.url ?: source.src ?: source.embedUrl ?: source.iframeUrl ?: ""
                if (srcUrl.isBlank()) continue

                val serverLabel = source.label ?: source.name ?: defaultLabel
                val quality = when {
                    source.quality?.contains("1080", ignoreCase = true) == true -> Qualities.P1080.value
                    source.quality?.contains("720", ignoreCase = true) == true -> Qualities.P720.value
                    source.quality?.contains("480", ignoreCase = true) == true -> Qualities.P480.value
                    source.quality?.contains("360", ignoreCase = true) == true -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                val type = source.type?.lowercase() ?: ""

                try {
                    when {
                        // m3u8 directo
                        type.contains("m3u8") || srcUrl.endsWith(".m3u8") -> {
                            generateM3u8(serverLabel, srcUrl, referer).forEach(callback)
                            anyEmitted = true
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
                                    this.referer = referer
                                    this.quality = quality
                                }
                            )
                            anyEmitted = true
                        }
                        // Embeds conocidos
                        srcUrl.contains("rumble.com") || srcUrl.contains("streamable.com") ||
                        srcUrl.contains("dailymotion.com") || srcUrl.contains("ok.ru") ||
                        srcUrl.contains("vk.com") || srcUrl.contains("vk.ru") ||
                        srcUrl.contains("voe.sx") || srcUrl.contains("filemoon") -> {
                            loadExtractor(srcUrl, referer, subtitleCallback, callback)
                            anyEmitted = true
                        }
                        // Otros (intentar loadExtractor genérico)
                        else -> {
                            try {
                                loadExtractor(srcUrl, referer, subtitleCallback, callback)
                                anyEmitted = true
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Si el JSON estructurado no dio resultados, buscar URLs http(s):// en el texto plano
        if (!anyEmitted) {
            val urlPattern = Regex("""(https?://[^\s"\\\]]+)""")
            val urls = urlPattern.findAll(response).map { it.groupValues[1] }.distinct().toList()
            for ((idx, u) in urls.withIndex()) {
                val cleanUrl = u.removeSuffix(",").removeSuffix("}")
                if (cleanUrl.contains("/api/sources")) continue  // No usar la URL del API
                if (cleanUrl.length < 20) continue  // URLs muy cortas son ruido
                try {
                    when {
                        cleanUrl.contains("rumble.com") -> {
                            extractRumble(cleanUrl, referer, "$defaultLabel ${idx + 1}", callback)
                            anyEmitted = true
                        }
                        cleanUrl.contains("dailymotion.com") -> {
                            extractDailymotion(cleanUrl, referer, "$defaultLabel ${idx + 1}", callback)
                            anyEmitted = true
                        }
                        cleanUrl.endsWith(".m3u8") || cleanUrl.contains(".m3u8") -> {
                            try {
                                generateM3u8("$defaultLabel ${idx + 1}", cleanUrl, referer).forEach(callback)
                                anyEmitted = true
                            } catch (_: Exception) {}
                        }
                        cleanUrl.endsWith(".mp4") || cleanUrl.contains(".mp4") -> {
                            callback(
                                newExtractorLink(
                                    source = "$defaultLabel ${idx + 1}",
                                    name = "$defaultLabel ${idx + 1}",
                                    url = cleanUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = referer
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            anyEmitted = true
                        }
                        else -> {
                            try {
                                loadExtractor(cleanUrl, referer, subtitleCallback, callback)
                                anyEmitted = true
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
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
    )

    private data class MovieSource(
        val label: String,
        val name: String,
        val token: String,
        val type: String,
        val provider: String,
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
