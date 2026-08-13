package com.donghuaext

import android.util.Log
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

private const val TAG = "DonghuaLifeBeta"

/**
 * DonghuaLifeBetaProvider — Provider para https://beta.donghualife.com
 *
 * v11 (2026-08-13): FIX basado en logcat v10 del emulador.
 *   Hallazgo v10: SET_COOKIE count=0 → NO es Cloudflare. Header Server no
 *   dice cloudflare. El server NO setea cookies de sesión en ningún momento.
 *   v10 produjo EXACTAMENTE el mismo resultado que v9: mismo mock de 719
 *   bytes con cdn.example.com/video.mp4. La bot detection NO se bypassa
 *   con headers.
 *
 *   Conclusión definitiva: el endpoint /api/sources tiene bot detection a
 *   nivel de APLICACIÓN (Next.js middleware o el handler mismo) que siempre
 *   devuelve el mismo mock a clientes no-navegador, sin importar headers.
 *
 *   Estrategia v11 — tres enfoques combinados:
 *   1. PROBAR ENDPOINTS ALTERNATIVOS: /api/embed, /api/source, /api/v1/sources,
 *      /api/play, /api/stream, /api/video — por si /api/sources es honeypot.
 *   2. DESCARGAR JS CHUNK PRINCIPAL del watch page y dumpear strings relevantes
 *      para encontrar la key AES o la lógica de decodificación del token.
 *   3. DESCIFRAR TOKEN AES-CBC CLIENT-SIDE: el token base64 decodificado es
 *      {"v":1,"iv":"<32hex>","data":"<192hex>","sig":"<base64>"}.
 *      Si encontramos la key AES de 32 bytes en el JS, podemos descifrar
 *      la URL real del video sin llamar al API.
 *
 * v10: Quitado X-Requested-With, agregados Sec-Ch-Ua client hints. No funcionó.
 * v9: extractAllSourcesFromRsc + deriveContentId (workaround sin activeEpisodeId).
 * v8: Quitado Accept-Encoding de browserHeaders (NiceHttp lo maneja solo).
 * v7: browserUA + browserHeaders para intentar bypass (no funcionó solo).
 * v6: M0/EE/EF/M0b métodos GET con episodeId/movieId + token.
 * v5: No retornar temprano, headers en ExtractorLinks, URL resolution, anti-chatty.
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

    /**
     * User-Agent de navegador real (Chrome desktop).
     * El server /api/sources y el RSC de episodios requieren un UA de navegador
     * completo para devolver tokens y URLs reales. Con UA por defecto de CS3,
     * el server devuelve RSC reducido (sin activeEpisodeId) y respuestas mock
     * con URLs "example".
     */
    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    /**
     * Headers completos de navegador para todas las peticiones a beta.donghualife.com.
     * NOTA: NO incluimos Accept-Encoding porque NiceHttp/OkHttp ya maneja gzip
     * automáticamente y agregarlo manualmente puede causar problemas de descompresión.
     *
     * v10: agregados Sec-Ch-Ua client hints (Chrome los envía siempre) y Priority.
     */
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

    /**
     * v10: Client hints compartidos para peticiones AJAX (fetch() desde el navegador).
     * Aplicar a todos los headers de /api/sources.
     */
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

        // ====== Construir bloque de metadata para el plot ======
        // CS3 no tiene campos nativos para Estado/Fecha/Puntuación como texto;
        // los mostramos al inicio del plot (arriba de Sinopsis) para máxima visibilidad.
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
        // Puntuación: intentaremos extraerla si está disponible en el HTML/RSC
        val ratingScore = extractRatingScore(html, rscPayload)
        if (ratingScore.isNotBlank()) metaLines.add("Puntuación: $ratingScore")
        val metaBlock = if (metaLines.isNotEmpty()) {
            metaLines.joinToString("\n") + "\n\n"
        } else ""
        val fullPlot = metaBlock + description

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
                plot = fullPlot
                tags = genres
                year = yearInt
                if (durationMinutes > 0) this.duration = durationMinutes
                showStatus = showStatus
                // score omitido: la API de CS3 cambió y Score() tiene constructor privado.
                // Para reactivarlo, usar: score = Score.from10PointScale(ratingScore.toFloat())
                // (o el factory method que use tu versión de CS3)
            }
        }

        // ====== Si es serie, extraer temporadas y episodios desde JSON seasons[] ======
        val episodes = ArrayList<Episode>()
        val seasons = extractSeasonsFromPayload(rscPayload)

        // Separar temporadas regulares de especiales
        // Las especiales van como season 0 (Especiales), las regulares como 1..N
        val regularSeasons = seasons.filter { !it.isSpecial }
        val specialSeasons = seasons.filter { it.isSpecial }

        for ((idx, season) in regularSeasons.withIndex()) {
            val seasonNum = idx + 1
            val seasonSlug = season.slug
            val episodeCount = season.episodeCount
            // initialEpisodes está ordenado ASCENDENTE por número
            // El primer elemento nos dice cuál es el primer episodio disponible
            val startEpNum = season.firstEpNumber.takeIf { it > 0 } ?: 1

            if (episodeCount > 0) {
                // Construir URLs /watch/{seasonSlug}-{N} para cada episodio
                // Los episodios se numeran desde startEpNum hasta startEpNum+episodeCount-1
                for (i in 0 until episodeCount) {
                    val epNum = startEpNum + i
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

        // Agregar temporadas especiales como season 0
        for (season in specialSeasons) {
            val seasonSlug = season.slug
            val episodeCount = season.episodeCount
            val startEpNum = season.firstEpNumber.takeIf { it > 0 } ?: 1
            if (episodeCount > 0) {
                for (i in 0 until episodeCount) {
                    val epNum = startEpNum + i
                    val epUrl = "$mainUrl/watch/$seasonSlug-$epNum"
                    episodes.add(
                        newEpisode(epUrl) {
                            this.season = 0  // 0 = Especiales en CS3
                            this.episode = epNum
                            this.name = "Especial $epNum"
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
            plot = fullPlot
            tags = genres
            year = yearInt
            if (durationMinutes > 0) this.duration = durationMinutes
            // score omitido: la API de CS3 cambió y Score() tiene constructor privado.
            // Para reactivarlo, usar: score = Score.from10PointScale(ratingScore.toFloat())
            // (o el factory method que use tu versión de CS3)
        }
    }

    /**
     * Intenta extraer la puntuación numérica (ej: "4.9") del HTML o RSC payload.
     * El sitio carga la puntuación dinámicamente vía JS, pero a veces está en el RSC
     * como "rating":4.9 o "score":4.9 o "averageRating":4.9.
     */
    private fun extractRatingScore(html: String, rscPayload: String): String {
        // Buscar patrones de puntuación en el RSC payload
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
                // Filtrar valores fuera de rango válido (0-10)
                val asDouble = value.toDoubleOrNull() ?: continue
                if (asDouble in 0.0..10.0) {
                    return "$value/10 votos"
                }
            }
        }
        return ""
    }

    /**
     * Convierte una puntuación como "4.9/10 votos" a un Int 0-10000 (escala CS3).
     * 1000 = 1 estrella, 10000 = 10 estrellas.
     */
    private fun String.extractRatingToInt(): Int? {
        if (this.isBlank()) return null
        val numStr = this.substringBefore("/").trim()
        val num = numStr.toDoubleOrNull() ?: return null
        if (num <= 0 || num > 10) return null
        return (num * 1000).toInt()
    }

    /**
     * Extrae la lista de temporadas desde el RSC payload.
     * Cada temporada: {slug, label, episodeCount, isSpecial, initialEpisodes: [{number, ...}]}
     *
     * initialEpisodes[] está ordenado ASCENDENTE por número.
     * El primer elemento (initialEpisodes[0].number) nos dice cuál es el PRIMER episodio
     * disponible en la temporada (no necesariamente 1 — ej: Martial Master empieza en 152).
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

        // Patrón: captura slug, label, isSpecial, episodeCount
        // Pattern: {"id":"...","slug":"<slug>","label":"<label>","coverImage":"...","isSpecial":<bool>,"episodeCount":<N>
        val seasonPattern = Regex(
            """\{"id":"[^"]+","slug":"([^"]+)","label":"([^"]+)","coverImage":"[^"]*","isSpecial":(true|false),"episodeCount":(\d+)"""
        )
        for (m in seasonPattern.findAll(seasonsArrayStr)) {
            val slug = m.groupValues[1]
            val label = m.groupValues[2]
            val isSpecial = m.groupValues[3] == "true"
            val countStr = m.groupValues[4]
            val episodeCount = countStr.toIntOrNull() ?: 0

            // Buscar el primer número de episodio en initialEpisodes[]
            // Estructura: ..."initialEpisodes":[{"id":"...","title":"...","number":N,...}]
            // Buscamos el primer "number":N después de "initialEpisodes":[ dentro de esta temporada
            val initialEpStart = seasonsArrayStr.find("\"initialEpisodes\":[", m.range.first)
            val firstEpNumber = if (initialEpStart >= 0 && initialEpStart > m.range.first) {
                // Tomar el primer "number":N después de "initialEpisodes":[
                val searchStart = initialEpStart + "\"initialEpisodes\":[".length
                val numMatch = Regex(""""number":(\d+)""").find(seasonsArrayStr.substring(searchStart))
                numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } else 0

            seasons.add(
                SeasonMeta(
                    slug = slug,
                    label = label,
                    episodeCount = episodeCount,
                    isSpecial = isSpecial,
                    firstEpNumber = firstEpNumber,
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

        val response = app.get(cleanUrl, headers = browserHeaders, timeout = 60)
        val html = response.text
        val rscPayload = extractRscPayload(html)
        // Log diagnóstico: tamaño, presencia de marcadores clave, y preview del HTML
        Log.i(TAG, "loadLinks url=$cleanUrl htmlLen=${html.length} rscLen=${rscPayload.length} " +
            "hasActiveEpId=${rscPayload.contains("\"activeEpisodeId\":")} " +
            "hasSources=${rscPayload.contains("\"sources\":[")} " +
            "hasTokens=${rscPayload.contains("\"token\":")} " +
            "hasNextF=${html.contains("self.__next_f")} " +
            "hasCloudflare=${html.contains("cloudflare") || html.contains("cf-")} " +
            "httpCode=${response.code}")
        // v10: Log de cookies Set-Cookie de la respuesta inicial.
        // Si Cloudflare setea cf_clearance u otra cookie de sesión, la veremos aquí.
        // OkHttp Headers: names() retorna Set<String>, values(name) retorna List<String>.
        try {
            val setCookieHeaders = response.headers.names()
                .filter { it.equals("set-cookie", ignoreCase = true) }
                .flatMap { name -> response.headers.values(name) }
            if (setCookieHeaders.isNotEmpty()) {
                Log.i(TAG, "loadLinks SET_COOKIE count=${setCookieHeaders.size} " +
                    "cookies=${setCookieHeaders.joinToString(" | ") { c -> c.take(120) }}")
            } else {
                Log.i(TAG, "loadLinks SET_COOKIE count=0 (no cookies returned) " +
                    "headerNames=${response.headers.names().joinToString(",")}")
            }
        } catch (e: Exception) {
            Log.i(TAG, "loadLinks SET_COOKIE error reading: ${e.message}")
        }
        // Preview del inicio y final del HTML para diagnóstico
        if (rscPayload.isEmpty()) {
            Log.i(TAG, "loadLinks HTML head=${html.take(300).replace("\n", " ")}")
            Log.i(TAG, "loadLinks HTML tail=${html.takeLast(300).replace("\n", " ")}")
        }
        // v9: Si el RSC es sospechosamente pequeño (bot detection), dumpear primeros 5000 chars
        // para ver qué data logró llegar. Esto nos permite ver la estructura real del RSC reducido.
        if (rscPayload.isNotEmpty() && rscPayload.length < 50000) {
            Log.i(TAG, "loadLinks RSC_DUMP (reduced, ${rscPayload.length} chars): " +
                rscPayload.take(5000).replace("\n", " "))
        }
        // v11: Si el RSC está reducido (bot detection confirmado), descargar y analizar JS chunks
        // para buscar la key AES o la lógica de decodificación del token client-side.
        // Esto se ejecuta UNA sola vez por loadLinks (no en cada retry).
        if (rscPayload.isNotEmpty() && rscPayload.length < 50000) {
            downloadAndAnalyzeJs(html, "loadLinks")
        }

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
        val logKey = "[ep#${rscPayload.hashCode().and(0xFFFF)}]"  // marker anti-chatty
        // 1. Encontrar activeEpisodeId
        val activeEpIdMatch = Regex(""""activeEpisodeId":"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""")
            .find(rscPayload)
        val activeEpId = activeEpIdMatch?.groupValues?.get(1) ?: ""
        Log.i(TAG, "$logKey loadEpisodeLinks url=$url activeEpId=$activeEpId rscSize=${rscPayload.length}")

        var anyEmitted = false

        // 1.5 NUEVO: extraer tokens del array "sources":[...] que está junto al activeEpisodeId
        // El RSC incluye un component $L1e o $L1b con {"sources":[{"id","label","name","token","type","provider"}]}
        // Esto es lo MISMO que usan las películas, así que reutilizamos loadSourcesViaApi.
        if (activeEpId.isNotBlank()) {
            val epSources = extractSourcesNearEpisode(rscPayload, activeEpId)
            Log.i(TAG, "$logKey epSources with tokens: ${epSources.size} labels=[${epSources.joinToString(",") { it.label }}]")
            if (epSources.isNotEmpty()) {
                // Llamar al API con los tokens (igual que las películas) — NO retornar temprano
                val emitted = loadSourcesViaApi(epSources, activeEpId, url, subtitleCallback, callback, logKey)
                Log.i(TAG, "$logKey loadSourcesViaApi emitted=$emitted")
                if (emitted) anyEmitted = true
            }
        }

        // 1.6 NUEVO v9: Si NO tenemos activeEpId (RSC reducido por bot detection),
        // escanear TODO el RSC en busca de sources con token. Cada source.id tiene
        // formato "<episodeId>-<index>", de donde derivamos el episodeId.
        // Esto permite recuperar los links incluso cuando el server omitió activeEpisodeId.
        if (activeEpId.isBlank()) {
            val allSources = extractAllSourcesFromRsc(rscPayload)
            Log.i(TAG, "$logKey v9 fallback: no activeEpId, found ${allSources.size} sources with tokens " +
                "labels=[${allSources.joinToString(",") { it.first.label }}] " +
                "contentIds=[${allSources.joinToString(",") { it.second.take(8) }}]")
            // Agrupar por contentId derivado (puede haber múltiples sources por episodio)
            val byContentId = allSources.groupBy({ it.second }, { it.first })
            for ((contentId, srcs) in byContentId) {
                Log.i(TAG, "$logKey v9 trying contentId=$contentId sources=${srcs.size}")
                val emitted = loadSourcesViaApi(srcs, contentId, url, subtitleCallback, callback, logKey)
                Log.i(TAG, "$logKey v9 contentId=$contentId emitted=$emitted")
                if (emitted) anyEmitted = true
            }
        }

        // 2. Si tenemos activeEpisodeId, buscar el episodio específico y extraer sus servers
        if (activeEpId.isNotBlank()) {
            val epServers = extractServersForEpisode(rscPayload, activeEpId)
            Log.i(TAG, "$logKey direct servers: ${epServers.size} [${epServers.joinToString(",") { "${it.first}:${it.second.take(40)}" }}]")
            if (epServers.isNotEmpty()) {
                val emitted = emitEpisodeServers(epServers, url, subtitleCallback, callback)
                Log.i(TAG, "$logKey emitEpisodeServers emitted=$emitted")
                if (emitted) anyEmitted = true
            }
        }

        // 3. Fallback: si no encontramos activeEpisodeId o no emitió links,
        // intentar parsear el URL para identificar seasonSlug + epNum y buscar el episodio por número
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

        // 4. Último recurso: extraer el PRIMER servers[] array que aparezca
        // (puede no ser el episodio correcto, pero al menos da links)
        if (!anyEmitted) {
            val firstServers = extractFirstServersArray(rscPayload)
            if (firstServers.isNotEmpty()) {
                val emitted = emitEpisodeServers(firstServers, url, subtitleCallback, callback)
                if (emitted) anyEmitted = true
            }
        }

        // 5. v11: Si nada funcionó, probar endpoints alternativos + análisis JS + descifrado token.
        // Solo ejecutar una vez (evitar duplicar trabajo si loadEpisodeLinks se llama múltiples veces).
        if (!anyEmitted) {
            Log.i(TAG, "$logKey v11: trying alternative strategies (endpoints + JS + token decrypt)")
            val allSources = extractAllSourcesFromRsc(rscPayload)
            // 5a. Endpoints alternativos
            for ((src, contentId) in allSources) {
                Log.i(TAG, "$logKey v11 ALT_ENDPOINTS for contentId=$contentId label=${src.label}")
                val altResp = tryAlternativeEndpoints(contentId, src.token, false, url, logKey)
                if (altResp.isNotBlank()) {
                    if (emitFromApiResponse(altResp, url, subtitleCallback, callback, defaultLabel = src.label)) {
                        anyEmitted = true
                    }
                }
            }
            // 5b. Descifrar token AES-CBC client-side (probablemente falle sin la key correcta,
            // pero el log nos dirá la estructura del token decodificado para análisis).
            if (!anyEmitted) {
                for ((src, _) in allSources) {
                    Log.i(TAG, "$logKey v11 TOKEN_DECRYPT label=${src.label} provider=${src.provider}")
                    val decryptedUrl = decryptTokenAesCbc(src.token, logKey)
                    if (decryptedUrl.isNotBlank()) {
                        Log.i(TAG, "$logKey v11 TOKEN_DECRYPT SUCCESS: $decryptedUrl")
                        // Si encontramos una URL, emitirla como ExtractorLink
                        val linkType = when {
                            decryptedUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                            decryptedUrl.contains(".mp4") -> ExtractorLinkType.VIDEO
                            else -> ExtractorLinkType.DASH
                        }
                        callback(
                            newExtractorLink(src.label, decryptedUrl, linkType) {
                                this.referer = url
                                this.headers = mapOf("Origin" to mainUrl, "User-Agent" to browserUA)
                            }
                        )
                        anyEmitted = true
                    }
                }
            }
        }

        Log.i(TAG, "$logKey FINAL anyEmitted=$anyEmitted")
        return anyEmitted
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
     *
     * IMPORTANTE: usamos un wrapper del callback para detectar si fue invocado.
     * loadExtractor no retorna éxito/fracaso ni lanza excepción si no encuentra links;
     * solo invoca el callback cuando tiene un link. Por eso envolvemos el callback.
     */
    private suspend fun emitEpisodeServers(
        servers: List<Pair<String, String>>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anyEmitted = false
        // Wrapper del callback que registra invocaciones
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            anyEmitted = true
            callback(link)
        }
        // Headers para CDNs (R2, hcdn, etc.) que requieren Origin + UA
        val cdnHeaders = mapOf(
            "Origin" to mainUrl,
            "User-Agent" to browserUA,
        )
        for ((serverName, serverUrl) in servers) {
            val name = serverName.trim().ifBlank { "Server" }
            // Normalizar URL:
            // - Para Ok.ru, el sitio usa /videoembed/<id> pero CS3 solo reconoce /video/<id>.
            //   Convertimos al formato esperado por el extractor nativo.
            // - Algunos extractores además requieren www.
            val normalizedUrl = when {
                serverUrl.contains("ok.ru/videoembed/") ->
                    serverUrl.replace("ok.ru/videoembed/", "www.ok.ru/video/")
                serverUrl.contains("ok.ru") && !serverUrl.contains("www.ok.ru") ->
                    serverUrl.replace("ok.ru", "www.ok.ru")
                else -> serverUrl
            }
            try {
                when {
                    // Rumble
                    serverUrl.contains("rumble.com") -> {
                        extractRumble(serverUrl, referer, name, trackingCallback)
                    }
                    // Dailymotion (incluye geo.dailymotion.com)
                    serverUrl.contains("dailymotion.com") || serverUrl.contains("geo.dailymotion.com") -> {
                        extractDailymotion(serverUrl, referer, name, trackingCallback)
                    }
                    // Stremeable = streamable.com
                    serverUrl.contains("streamable.com") -> {
                        extractStreamable(serverUrl, referer, name, subtitleCallback, trackingCallback)
                    }
                    // Ok.ru (CS3 tiene extractor nativo; usar URL normalizada con www.)
                    serverUrl.contains("ok.ru") -> {
                        loadExtractor(normalizedUrl, referer, subtitleCallback, trackingCallback)
                        // Si loadExtractor no emitió nada, intentar con la URL original también
                        if (!anyEmitted) {
                            try { loadExtractor(serverUrl, referer, subtitleCallback, trackingCallback) } catch (_: Exception) {}
                        }
                        // Si aún no emitió, intentar scrapear ok.ru directamente
                        // (algunos videos tienen metadata embebida en el HTML)
                        if (!anyEmitted) {
                            try { extractOkruDirect(serverUrl, referer, name, trackingCallback) } catch (_: Exception) {}
                        }
                    }
                    // Direct mp4/m3u8 URLs (raro pero posible)
                    serverUrl.endsWith(".mp4") || serverUrl.endsWith(".m3u8") ||
                    serverUrl.contains("r2.cloudflarestorage") || serverUrl.contains("hcdn.dev") -> {
                        val linkType = if (serverUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = serverUrl,
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
                    serverUrl.contains("voe.sx") -> {
                        loadExtractor(serverUrl, referer, subtitleCallback, trackingCallback)
                    }
                    // Filemoon
                    serverUrl.contains("filemoon") || serverUrl.contains("moonplayer") -> {
                        loadExtractor(serverUrl, referer, subtitleCallback, trackingCallback)
                    }
                    // Otros: loadExtractor genérico
                    else -> {
                        loadExtractor(serverUrl, referer, subtitleCallback, trackingCallback)
                    }
                }
            } catch (_: Exception) {}
            // No seteamos anyEmitted aquí: el trackingCallback lo hace al ser invocado
        }
        return anyEmitted
    }

    /**
     * Fallback para ok.ru: scrapea el HTML del embed y busca URLs directas de video.
     *
     * Ok.ru embeds incluyen data-options="{...}" con URLs HLS y MP4.
     * También buscanos patrones comunes en el HTML del embed.
     */
    private suspend fun extractOkruDirect(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(embedUrl, referer = referer, timeout = 30L).text
            Log.i(TAG, "extractOkruDirect htmlLen=${html.length} url=$embedUrl")

            // Método 1: data-options attribute (JSON-encoded con URLs HLS/MP4)
            // En HTML, el attribute se delimita con " y dentro usa &quot; para comillas
            val dataOptionsPattern = Regex("data-options=\"([^\"]+)\"")
            val dataOptionsMatch = dataOptionsPattern.find(html)
            if (dataOptionsMatch != null) {
                val decoded = dataOptionsMatch.groupValues[1]
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                // Buscar URLs m3u8 y mp4 dentro del JSON decodificado
                val hlsPattern = Regex(""""url"\s*:\s*"(https?://[^"\s]+\.m3u8[^"\s]*)"""")
                val mp4Pattern = Regex(""""url"\s*:\s*"(https?://[^"\s]+\.mp4[^"\s]*)"""")

                for (m in hlsPattern.findAll(decoded)) {
                    val u = m.groupValues[1]
                    Log.i(TAG, "extractOkruDirect: found HLS $u")
                    try {
                        generateM3u8(serverName, u, embedUrl).forEach(callback)
                        return
                    } catch (_: Exception) {}
                }
                for (m in mp4Pattern.findAll(decoded)) {
                    val u = m.groupValues[1]
                    Log.i(TAG, "extractOkruDirect: found MP4 $u")
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

            // Método 2: buscar cualquier URL .m3u8 o .mp4 en el HTML
            val urlPattern = Regex("""(https?://[^\s"'<>]+(?:\.m3u8|\.mp4)[^\s"'<>]*)""")
            for (m in urlPattern.findAll(html)) {
                val u = m.groupValues[1]
                Log.i(TAG, "extractOkruDirect: found raw URL $u")
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
            Log.w(TAG, "extractOkruDirect: no URLs found in HTML")
        } catch (e: Exception) {
            Log.w(TAG, "extractOkruDirect failed: ${e.message}")
        }
    }

    /**
     * Para películas: el RSC payload contiene el array sources[] con tokens encriptados.
     * Cada source tiene: {id, label, name, token, type, provider, ...}
     *
     * Estrategia v5: probar TODOS los métodos y TODOS los sources, acumulando links.
     * NO retornar temprano — el usuario puede elegir si un source falla.
     *
     * Métodos:
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
        val logKey = "[mv#${rscPayload.hashCode().and(0xFFFF)}]"  // marker anti-chatty
        val movieId = if (preloadedContentId.isNotBlank()) preloadedContentId
            else extractContentIdFromPayload(rscPayload, "movieId") ?: ""
        Log.i(TAG, "$logKey loadMovieLinks url=$url movieId=$movieId rscSize=${rscPayload.length}")

        // Extraer la lista de sources con tokens desde el payload
        val sources = extractMovieSources(rscPayload)
        Log.i(TAG, "$logKey sources=[${sources.joinToString(",") { "${it.label}/${it.type}/${it.provider}" }}]")
        if (movieId.isBlank() && sources.isEmpty()) return false

        // Headers tipo AJAX de navegador real (Next.js / API routes los valida).
        // Sin Sec-Fetch-* y Accept correcto, el server puede devolver respuestas mock.
        // v10: quitado X-Requested-With (jQuery-era, Chrome fetch() no lo envía —
        // era una red flag para bot detection). Agregados Sec-Ch-Ua client hints.
        val headers = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
            "Content-Type" to "application/json",
            "Origin" to mainUrl,
            "Referer" to url,
            "User-Agent" to browserUA,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
        ) + ajaxClientHints

        var anyEmitted = false

        // ====== Método M0 (NUEVO v6 — PRIMERO): GET /api/sources?movieId=X&token=Y ======
        // Mismo fix que para episodios: el server exige movieId en URL params.
        // Combinamos movieId + token en un solo GET.
        if (movieId.isNotBlank()) {
            for (source in sources) {
                val token = source.token
                if (token.isBlank()) continue
                try {
                    val encToken = URLEncoder.encode(token, "UTF-8")
                    val resp = app.get(
                        "$mainUrl/api/sources?movieId=$movieId&token=$encToken",
                        headers = headers,
                        timeout = 30L
                    ).text
                    Log.i(TAG, "$logKey M0 GET ?mv&token ${source.label} respLen=${resp.length} head=${resp.take(200)}")
                    if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                        if (emitFromApiResponse(resp, url, subtitleCallback, callback, defaultLabel = source.label)) anyEmitted = true
                    }
                } catch (e: Exception) { Log.w(TAG, "$logKey M0 failed (${source.label}): ${e.message}") }
            }
        }

        // ====== Método M0b (v6): GET /api/sources?movieId=X (sin token) ======
        if (movieId.isNotBlank() && !anyEmitted) {
            try {
                val resp = app.get(
                    "$mainUrl/api/sources?movieId=$movieId",
                    headers = headers,
                    timeout = 30L
                ).text
                Log.i(TAG, "$logKey M0b GET ?mv respLen=${resp.length} head=${resp.take(200)}")
                if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                    if (emitFromApiResponse(resp, url, subtitleCallback, callback)) anyEmitted = true
                }
            } catch (e: Exception) { Log.w(TAG, "$logKey M0b failed: ${e.message}") }
        }

        // ====== Método 1 (fallback): POST /api/sources con {movieId: "<uuid>"} ======
        if (movieId.isNotBlank()) {
            try {
                val resp = app.post(
                    "$mainUrl/api/sources",
                    json = mapOf<String, Any>("movieId" to movieId),
                    headers = headers,
                    timeout = 30L
                ).text
                Log.i(TAG, "$logKey M1 POST {movieId} respLen=${resp.length} head=${resp.take(150)}")
                if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                    if (emitFromApiResponse(resp, url, subtitleCallback, callback)) anyEmitted = true
                }
            } catch (e: Exception) { Log.w(TAG, "$logKey M1 failed: ${e.message}") }
        }

        // ====== Método 2: POST /api/sources con {token: "<token>"} por cada source ======
        // IMPORTANTE: NO retornar temprano — acumular links de TODOS los sources
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
                Log.i(TAG, "$logKey M2 POST {token} ${source.label} respLen=${resp.length} head=${resp.take(150)}")
                if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                    if (emitFromApiResponse(resp, url, subtitleCallback, callback, defaultLabel = source.label)) anyEmitted = true
                }
            } catch (e: Exception) { Log.w(TAG, "$logKey M2 failed (${source.label}): ${e.message}") }
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
                    if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                        if (emitFromApiResponse(resp, url, subtitleCallback, callback, defaultLabel = source.label)) anyEmitted = true
                    }
                } catch (_: Exception) {}
            }
        }

        // ====== Método 4: GET /api/sources?movieId=<uuid> ======
        if (movieId.isNotBlank() && !anyEmitted) {
            try {
                val resp = app.get(
                    "$mainUrl/api/sources?movieId=$movieId",
                    headers = headers,
                    timeout = 30L
                ).text
                if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                    if (emitFromApiResponse(resp, url, subtitleCallback, callback)) anyEmitted = true
                }
            } catch (_: Exception) {}
        }

        // ====== Método 5: GET /api/sources?token=<token> por cada source ======
        if (!anyEmitted) {
            for (source in sources) {
                val token = source.token
                if (token.isBlank()) continue
                try {
                    val resp = app.get(
                        "$mainUrl/api/sources?token=$token",
                        headers = headers,
                        timeout = 30L
                    ).text
                    if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                        if (emitFromApiResponse(resp, url, subtitleCallback, callback, defaultLabel = source.label)) anyEmitted = true
                    }
                } catch (_: Exception) {}
            }
        }

        // ====== Método 6: Para fuentes Rumble/VK conocidas, intentar loadExtractor directo ======
        // Algunos tokens pueden decodificar a URLs directas en el cliente
        // (raro pero posible si el token es solo base64)
        if (!anyEmitted) {
            for (source in sources) {
                val token = source.token
                if (token.isBlank()) continue
                try {
                    val decoded = java.util.Base64.getDecoder().decode(token)
                    val decodedStr = String(decoded, Charsets.UTF_8)
                    val urlMatch = Regex("""https?://[^\s"']+""").find(decodedStr)
                    if (urlMatch != null) {
                        val directUrl = urlMatch.value
                        if (directUrl.contains("rumble.com") || directUrl.contains("dailymotion.com") ||
                            directUrl.contains("vk.com") || directUrl.contains("ok.ru")) {
                            try {
                                loadExtractor(directUrl, url, subtitleCallback, callback)
                                anyEmitted = true
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // ====== Método 7 v11: Endpoints alternativos + descifrado AES-CBC del token ======
        if (!anyEmitted) {
            Log.i(TAG, "$logKey v11: trying alternative strategies (endpoints + token decrypt)")
            // 7a. Endpoints alternativos
            for (source in sources) {
                if (source.token.isBlank()) continue
                Log.i(TAG, "$logKey v11 ALT_ENDPOINTS for movieId=$movieId label=${source.label}")
                val altResp = tryAlternativeEndpoints(movieId, source.token, true, url, logKey)
                if (altResp.isNotBlank()) {
                    if (emitFromApiResponse(altResp, url, subtitleCallback, callback, defaultLabel = source.label)) {
                        anyEmitted = true
                    }
                }
            }
            // 7b. Descifrar token AES-CBC client-side
            if (!anyEmitted) {
                for (source in sources) {
                    if (source.token.isBlank()) continue
                    Log.i(TAG, "$logKey v11 TOKEN_DECRYPT label=${source.label} provider=${source.provider}")
                    val decryptedUrl = decryptTokenAesCbc(source.token, logKey)
                    if (decryptedUrl.isNotBlank()) {
                        Log.i(TAG, "$logKey v11 TOKEN_DECRYPT SUCCESS: $decryptedUrl")
                        val linkType = when {
                            decryptedUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                            decryptedUrl.contains(".mp4") -> ExtractorLinkType.VIDEO
                            else -> ExtractorLinkType.DASH
                        }
                        callback(
                            newExtractorLink(source.label, decryptedUrl, linkType) {
                                this.referer = url
                                this.headers = mapOf("Origin" to mainUrl, "User-Agent" to browserUA)
                            }
                        )
                        anyEmitted = true
                    }
                }
            }
        }

        Log.i(TAG, "$logKey FINAL anyEmitted=$anyEmitted")
        return anyEmitted
    }

    /**
     * Extrae los sources con tokens que están junto al activeEpisodeId en el RSC.
     *
     * Estructura real del RSC:
     *   ...,["$","$L1e","<activeEpisodeId>",{"sources":[{"id":"...","label":"ok.ru","name":"ok.ru","token":"...","type":"embed","provider":"ok.ru",...}]}],...
     *
     * Busca "<activeEpisodeId>",{"sources":[ y extrae el array que sigue.
     */
    private fun extractSourcesNearEpisode(payload: String, episodeId: String): List<MovieSource> {
        val sources = ArrayList<MovieSource>()

        // Buscar el patrón: "<episodeId>",{"sources":[
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

        // Mismo patrón que extractMovieSources
        // v9: capturamos también el "id" del source
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

    /**
     * Llama al API /api/sources con los tokens extraídos del RSC (igual que loadMovieLinks
     * pero para episodios). Reutiliza la misma lógica de múltiples métodos.
     *
     * v5: NO retorna temprano. Prueba TODOS los métodos y sources, acumulando links.
     *
     * @param contentId ID del episodio (activeEpisodeId)
     * @param referer URL del episodio (para headers)
     * @param logKey Marker anti-chatty para logs
     */
    private suspend fun loadSourcesViaApi(
        sources: List<MovieSource>,
        contentId: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        logKey: String = "[ep]"
    ): Boolean {
        if (sources.isEmpty()) return false

        // Headers tipo AJAX de navegador real (mismo que loadMovieLinks).
        // v10: sin X-Requested-With, con Sec-Ch-Ua client hints.
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

        var anyEmitted = false

        // ====== Método EE (NUEVO v6 — PRIMERO): GET /api/sources?episodeId=X&token=Y ======
        // El server respondió "episodeId or movieId required" cuando solo mandábamos ?token=.
        // Esto indica que el server exige episodeId en URL params. Combinamos ambos.
        // URL-encodeamos el token por si contiene caracteres especiales (aunque JWT suele ser safe).
        for (source in sources) {
            val token = source.token
            if (token.isBlank()) continue
            try {
                val encToken = URLEncoder.encode(token, "UTF-8")
                val resp = app.get(
                    "$mainUrl/api/sources?episodeId=$contentId&token=$encToken",
                    headers = headers,
                    timeout = 30L
                ).text
                Log.i(TAG, "$logKey EE GET ?ep&token ${source.label} respLen=${resp.length} head=${resp.take(200)}")
                if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                    if (emitFromApiResponse(resp, referer, subtitleCallback, callback, defaultLabel = source.label)) anyEmitted = true
                }
            } catch (e: Exception) { Log.w(TAG, "$logKey EE failed (${source.label}): ${e.message}") }
        }

        // ====== Método EF (v6): GET /api/sources?episodeId=X (sin token) ======
        // Por si el server puede derivar el token del episodeId (server-side lookup).
        // El server ya tiene el episodeId, así que tal vez no necesite el token.
        if (!anyEmitted) {
            try {
                val resp = app.get(
                    "$mainUrl/api/sources?episodeId=$contentId",
                    headers = headers,
                    timeout = 30L
                ).text
                Log.i(TAG, "$logKey EF GET ?ep respLen=${resp.length} head=${resp.take(200)}")
                if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                    if (emitFromApiResponse(resp, referer, subtitleCallback, callback)) anyEmitted = true
                }
            } catch (e: Exception) { Log.w(TAG, "$logKey EF failed: ${e.message}") }
        }

        // ====== Método A (fallback): POST /api/sources con {token} por cada source ======
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
                Log.i(TAG, "$logKey EA POST {token} ${source.label} respLen=${resp.length} head=${resp.take(150)}")
                if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                    if (emitFromApiResponse(resp, referer, subtitleCallback, callback, defaultLabel = source.label)) anyEmitted = true
                }
            } catch (e: Exception) { Log.w(TAG, "$logKey EA failed (${source.label}): ${e.message}") }
        }

        // Método B: POST /api/sources con {episodeId, token}
        if (!anyEmitted) {
            for (source in sources) {
                val token = source.token
                if (token.isBlank()) continue
                try {
                    val resp = app.post(
                        "$mainUrl/api/sources",
                        json = mapOf<String, Any>(
                            "episodeId" to contentId,
                            "token" to token
                        ),
                        headers = headers,
                        timeout = 30L
                    ).text
                    Log.i(TAG, "$logKey EB POST {ep,token} ${source.label} respLen=${resp.length} head=${resp.take(150)}")
                    if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                        if (emitFromApiResponse(resp, referer, subtitleCallback, callback, defaultLabel = source.label)) anyEmitted = true
                    }
                } catch (e: Exception) { Log.w(TAG, "$logKey EB failed (${source.label}): ${e.message}") }
            }
        }

        // Método C: POST /api/sources con {episodeId}
        if (!anyEmitted) {
            try {
                val resp = app.post(
                    "$mainUrl/api/sources",
                    json = mapOf<String, Any>("episodeId" to contentId),
                    headers = headers,
                    timeout = 30L
                ).text
                Log.i(TAG, "$logKey EC POST {episodeId} respLen=${resp.length} head=${resp.take(150)}")
                if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                    if (emitFromApiResponse(resp, referer, subtitleCallback, callback)) anyEmitted = true
                }
            } catch (e: Exception) { Log.w(TAG, "$logKey EC failed: ${e.message}") }
        }

        // Método D: GET /api/sources?token=<token>
        if (!anyEmitted) {
            for (source in sources) {
                val token = source.token
                if (token.isBlank()) continue
                try {
                    val resp = app.get(
                        "$mainUrl/api/sources?token=$token",
                        headers = headers,
                        timeout = 30L
                    ).text
                    Log.i(TAG, "$logKey ED GET ?token ${source.label} respLen=${resp.length} head=${resp.take(150)}")
                    if (resp.isNotBlank() && resp != "{}" && !resp.contains("\"error\"")) {
                        if (emitFromApiResponse(resp, referer, subtitleCallback, callback, defaultLabel = source.label)) anyEmitted = true
                    }
                } catch (e: Exception) { Log.w(TAG, "$logKey ED failed (${source.label}): ${e.message}") }
            }
        }

        return anyEmitted
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
        // v9: capturamos también el "id" para derivar el contentId cuando no hay activeEpisodeId.
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

    /**
     * v9: Extrae TODOS los sources con token de TODOS los arrays "sources":[...] del RSC.
     * No depende de activeEpisodeId ni de movieId.
     *
     * Uso principal: cuando el server devuelve un RSC reducido (bot detection) sin
     * activeEpisodeId, todavía puede contener sources[] con tokens. Cada source.id
     * tiene formato "<contentId>-<index>", de donde derivamos el episodeId/movieId.
     *
     * @return Lista de (MovieSource, derivedContentId) para todos los sources encontrados.
     */
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

    /**
     * v11: Prueba endpoints alternativos al /api/sources (que está mockeado).
     * Quizás alguno no tiene bot detection y devuelve URLs reales.
     *
     * @param contentId episodeId o movieId
     * @param token token del source
     * @param isMovie true si es película, false si es episodio
     * @param referer URL de la página (para headers)
     * @return respuesta del primer endpoint que devuelva algo no-mock, o "" si ninguno
     */
    private suspend fun tryAlternativeEndpoints(
        contentId: String,
        token: String,
        isMovie: Boolean,
        referer: String,
        logKey: String
    ): String {
        val idParam = if (isMovie) "movieId" else "episodeId"
        val encToken = URLEncoder.encode(token, "UTF-8")
        val altEndpoints = listOf(
            "/api/embed",
            "/api/source",
            "/api/v1/sources",
            "/api/play",
            "/api/stream",
            "/api/video",
            "/api/links",
            "/api/extract",
            "/api/resolve",
            "/api/getSources",
            "/api/get-sources",
        )
        val headers = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
            "Origin" to mainUrl,
            "Referer" to referer,
            "User-Agent" to browserUA,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
        ) + ajaxClientHints

        for (endpoint in altEndpoints) {
            val url = "$mainUrl$endpoint?$idParam=$contentId&token=$encToken"
            try {
                val resp = app.get(url, headers = headers, timeout = 15L).text
                val isMock = resp.contains("cdn.example.com") ||
                             resp.contains("/video/example") ||
                             resp.contains("/embed/video/example")
                Log.i(TAG, "$logKey ALT $endpoint respLen=${resp.length} isMock=$isMock " +
                    "head=${resp.take(150).replace("\n", " ")}")
                if (resp.isNotBlank() && resp.length > 5 && !isMock &&
                    !resp.contains("\"error\"") && resp != "{}") {
                    Log.i(TAG, "$logKey ALT $endpoint NON-MOCK RESPONSE FOUND!")
                    return resp
                }
            } catch (e: Exception) {
                Log.i(TAG, "$logKey ALT $endpoint error: ${e.message}")
            }
        }
        return ""
    }

    /**
     * v11: Descarga el JS chunk principal del watch/peliculas page y busca strings
     * relevantes (api/sources, AES, decrypt, key, secret, signature) para intentar
     * encontrar la lógica de decodificación del token client-side.
     *
     * @param html HTML de la página (para extraer URLs de chunks JS)
     */
    private suspend fun downloadAndAnalyzeJs(html: String, logKey: String) {
        try {
            // Extraer URLs de chunks JS del HTML
            val jsUrls = Regex("""(/_next/static/chunks/[^"'\s]+\.js)""")
                .findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            Log.i(TAG, "$logKey JS_ANALYZE found ${jsUrls.size} JS chunks in HTML")
            // Solo loguear URLs que puedan ser relevantes (watch, peliculas, page, layout, sources)
            val relevant = jsUrls.filter { url ->
                url.contains("page") || url.contains("layout") ||
                url.contains("watch") || url.contains("peliculas") ||
                url.contains("source") || url.contains("video") ||
                url.contains("api") || url.contains("embed")
            }
            Log.i(TAG, "$logKey JS_ANALYZE relevant chunks: ${relevant.size} " +
                "urls=${relevant.joinToString(",") { it.takeLast(60) }}")

            // Descargar los primeros 3 chunks relevantes y buscar strings
            val headers = mapOf(
                "User-Agent" to browserUA,
                "Accept" to "*/*",
                "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
                "Referer" to mainUrl,
                "Origin" to mainUrl,
                "Sec-Fetch-Dest" to "script",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "same-origin",
            ) + ajaxClientHints

            // Palabras clave a buscar en el JS
            val searchTerms = listOf(
                "api/sources", "/api/embed", "/api/source", "/api/play",
                "AES", "decrypt", "CryptoJS", "crypto.subtle",
                "SECRET_KEY", "secretKey", "ENCRYPTION_KEY", "encryptionKey",
                "signature", "verify", "hmac", "HMAC",
                "token", "resolveToken", "decodeToken",
            )

            for (chunkUrl in relevant.take(5)) {
                try {
                    val fullUrl = if (chunkUrl.startsWith("http")) chunkUrl else "$mainUrl$chunkUrl"
                    val js = app.get(fullUrl, headers = headers, timeout = 20L).text
                    Log.i(TAG, "$logKey JS chunk ${chunkUrl.takeLast(50)} len=${js.length}")
                    // Buscar strings relevantes
                    val found = mutableListOf<String>()
                    for (term in searchTerms) {
                        val idx = js.indexOf(term, ignoreCase = true)
                        if (idx >= 0) {
                            // Extraer 100 chars alrededor del match
                            val start = maxOf(0, idx - 50)
                            val end = minOf(js.length, idx + term.length + 100)
                            found.add("'$term' @ $idx: ...${js.substring(start, end)}...")
                        }
                    }
                    if (found.isNotEmpty()) {
                        Log.i(TAG, "$logKey JS chunk ${chunkUrl.takeLast(50)} MATCHES:")
                        for (m in found) Log.i(TAG, "$logKey   $m")
                    }
                    // Buscar también strings hex de 64 chars (posible AES-256 key)
                    val hexKeyPattern = Regex("""["']([0-9a-fA-F]{64})["']""")
                    val hexMatches = hexKeyPattern.findAll(js).take(3).toList()
                    if (hexMatches.isNotEmpty()) {
                        Log.i(TAG, "$logKey JS chunk ${chunkUrl.takeLast(50)} HEX64 (possible AES key):")
                        for (m in hexMatches) Log.i(TAG, "$logKey   ${m.groupValues[1]}")
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "$logKey JS chunk ${chunkUrl.takeLast(50)} error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "$logKey JS_ANALYZE error: ${e.message}")
        }
    }

    /**
     * v11: Intenta descifrar un token AES-CBC client-side.
     * El token base64 decodificado es JSON: {"v":1,"iv":"<32hex>","data":"<192hex>","sig":"<base64>"}
     * - iv: 16 bytes (AES block size)
     * - data: N bytes (múltiplo de 16, cifrado AES-256-CBC)
     * - sig: HMAC-SHA256 para verificación (no necesitamos verificar para descifrar)
     *
     * Probamos varias keys hardcoded comunes (encontradas en otros sitios Next.js
     * que usan el mismo patrón). Si ninguna funciona, no podemos descifrar y
     * tendremos que obtener la key del JS del sitio.
     *
     * @param token Token base64 del source
     * @return URL descifrada o "" si no se pudo descifrar
     */
    private fun decryptTokenAesCbc(token: String, logKey: String): String {
        if (token.isBlank()) return ""
        try {
            // Decodificar base64 → JSON
            val jsonStr = String(Base64.getDecoder().decode(token), Charsets.UTF_8)
            Log.i(TAG, "$logKey TOKEN_DEC b64decoded=$jsonStr")
            // Parsear JSON manualmente
            val ivMatch = Regex(""""iv":"([0-9a-fA-F]+)"""").find(jsonStr)
            val dataMatch = Regex(""""data":"([0-9a-fA-F]+)"""").find(jsonStr)
            if (ivMatch == null || dataMatch == null) {
                Log.i(TAG, "$logKey TOKEN_DEC no iv/data found in JSON")
                return ""
            }
            val ivHex = ivMatch.groupValues[1]
            val dataHex = dataMatch.groupValues[1]
            val ivBytes = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val dataBytes = dataHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Log.i(TAG, "$logKey TOKEN_DEC iv=${ivBytes.size}B data=${dataBytes.size}B")

            // Keys comunes a probar (placeholders — necesitamos la key real del JS).
            // Estas son solo para diagnóstico; es muy improbable que alguna funcione.
            val candidateKeys = listOf(
                "donghualife-secret-key-2024-v1!!!",  // 32 chars
                "donghualife2024secretkey1234567890ab",  // 32 chars
                "beta.donghualife.com-secret-key-2024",  // 36 chars (truncaremos a 32)
                "0123456789abcdef0123456789abcdef",  // 32 chars hex demo
                "donghualife-beta-secret-key-32bytes!",  // 34 chars
            )
            for (keyStr in candidateKeys) {
                // Pad/truncar a 32 bytes (AES-256 key size)
                val keyBytes = if (keyStr.length >= 32) {
                    keyStr.toByteArray(Charsets.UTF_8).copyOfRange(0, 32)
                } else {
                    keyStr.toByteArray(Charsets.UTF_8).copyOf(32)
                }
                try {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                    val decrypted = cipher.doFinal(dataBytes)
                    val decStr = String(decrypted, Charsets.UTF_8)
                    Log.i(TAG, "$logKey TOKEN_DEC key='$keyStr' → $decStr")
                    // Si el resultado contiene una URL, retornarla
                    val urlMatch = Regex("""https?://[^\s"']+""").find(decStr)
                    if (urlMatch != null) {
                        Log.i(TAG, "$logKey TOKEN_DEC URL FOUND: ${urlMatch.value}")
                        return urlMatch.value
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "$logKey TOKEN_DEC key='$keyStr' error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "$logKey TOKEN_DEC outer error: ${e.message}")
        }
        return ""
    }

    /**
     * Parsea defensivamente la respuesta de /api/sources buscando URLs en cualquier campo.
     * Acepta múltiples formatos:
     *   - {"success":true,"sources":[{"url":"...","quality":"720p"}]}
     *   - {"sources":[{"url":"..."}]}
     *   - {"url":"..."}
     *   - Cualquier JSON con URLs http(s)://...
     *
     * v5: agrega Origin + User-Agent headers a todos los ExtractorLinks directos
     *     para que ExoPlayer pueda acceder a CDNs (R2, hcdn) que requieren estos headers.
     *     También resuelve URLs relativas (/video/...) contra mainUrl.
     */
    private suspend fun emitFromApiResponse(
        response: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        defaultLabel: String = "Server"
    ): Boolean {
        var anyEmitted = false
        Log.i(TAG, "emitFromApiResponse label=$defaultLabel len=${response.length}")

        // v9: Detectar URLs mock (bot detection). El server devuelve respuestas con
        // success:true pero URLs falsas como cdn.example.com/video.mp4 o /video/example
        // cuando detecta que el cliente no es un navegador real.
        val isMockResponse = response.contains("cdn.example.com") ||
                             response.contains("\"/video/example\"") ||
                             response.contains("/video/example\"")
        if (isMockResponse) {
            Log.w(TAG, "emitFromApiResponse MOCK_URL_DETECTED label=$defaultLabel " +
                "resp=${response.take(500)}")
            // No emitir nada — las URLs mock solo causan errores en ExoPlayer.
            return false
        }

        // Helper: resolver URL relativa contra mainUrl
        fun resolveUrl(url: String): String = when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }

        // Helper: headers para ExtractorLink (Origin + UA para CDNs)
        val cdnHeaders = mapOf(
            "Origin" to mainUrl,
            "User-Agent" to browserUA,
        )

        // Intentar parsear como JSON estructurado
        val parsed: SourcesResponse? = try { parseJson<SourcesResponse>(response) } catch (_: Exception) { null }

        if (parsed != null && parsed.sources.isNotEmpty()) {
            Log.i(TAG, "  Parsed sources: ${parsed.sources.size}")
            for ((idx, source) in parsed.sources.withIndex()) {
                val rawUrl = source.url ?: source.src ?: source.embedUrl ?: source.iframeUrl ?: ""
                if (rawUrl.isBlank()) {
                    Log.i(TAG, "  src[$idx]: NO URL (label=${source.label} name=${source.name} type=${source.type})")
                    continue
                }
                val srcUrl = resolveUrl(rawUrl)
                Log.i(TAG, "  src[$idx]: url=$srcUrl label=${source.label} type=${source.type} quality=${source.quality}")

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
                        type.contains("m3u8") || srcUrl.endsWith(".m3u8") || srcUrl.contains(".m3u8") -> {
                            Log.i(TAG, "  -> m3u8 path: $srcUrl")
                            try {
                                generateM3u8(serverLabel, srcUrl, referer).forEach(callback)
                                anyEmitted = true
                            } catch (e: Exception) {
                                Log.w(TAG, "  m3u8 failed: ${e.message}")
                            }
                        }
                        // mp4 directo (incluye R2 URLs que suelen ser .mp4 o sin extensión pero type=video)
                        type.contains("mp4") || type.contains("video") || srcUrl.endsWith(".mp4") ||
                        (srcUrl.contains("r2.cloudflarestorage") || srcUrl.contains("hcdn.dev") ||
                         srcUrl.contains("cloudflarestorage")) -> {
                            Log.i(TAG, "  -> mp4/video path: $srcUrl")
                            callback(
                                newExtractorLink(
                                    source = serverLabel,
                                    name = "$serverLabel ${quality / 1000}p",
                                    url = srcUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = referer
                                    this.quality = quality
                                    this.headers = cdnHeaders
                                }
                            )
                            anyEmitted = true
                        }
                        // Embeds conocidos
                        srcUrl.contains("rumble.com") || srcUrl.contains("streamable.com") ||
                        srcUrl.contains("dailymotion.com") || srcUrl.contains("ok.ru") ||
                        srcUrl.contains("vk.com") || srcUrl.contains("vk.ru") ||
                        srcUrl.contains("voe.sx") || srcUrl.contains("filemoon") -> {
                            Log.i(TAG, "  -> loadExtractor path (embed): $srcUrl")
                            // Normalizar Ok.ru videoembed -> video (CS3 OkruExtractor solo reconoce /video/)
                            val normalizedEmbed = if (srcUrl.contains("ok.ru/videoembed/")) {
                                srcUrl.replace("ok.ru/videoembed/", "www.ok.ru/video/")
                            } else if (srcUrl.contains("ok.ru") && !srcUrl.contains("www.ok.ru")) {
                                srcUrl.replace("ok.ru", "www.ok.ru")
                            } else srcUrl
                            try {
                                loadExtractor(normalizedEmbed, referer, subtitleCallback, callback)
                                anyEmitted = true
                            } catch (e: Exception) {
                                Log.w(TAG, "  loadExtractor failed: ${e.message}")
                            }
                        }
                        // Otros (intentar loadExtractor genérico)
                        else -> {
                            Log.i(TAG, "  -> loadExtractor path (generic): $srcUrl")
                            try {
                                loadExtractor(srcUrl, referer, subtitleCallback, callback)
                                anyEmitted = true
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  src[$idx] exception: ${e.message}")
                }
            }
        }

        // Si el JSON estructurado no dio resultados, buscar URLs http(s):// en el texto plano
        if (!anyEmitted) {
            Log.i(TAG, "  Structured parse failed/skipped, trying raw URL extraction...")
            val urlPattern = Regex("""(https?://[^\s"\\\]]+)""")
            val urls = urlPattern.findAll(response).map { it.groupValues[1] }.distinct().toList()
            Log.i(TAG, "  Raw URLs found: ${urls.size}")
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
                        cleanUrl.endsWith(".mp4") || cleanUrl.contains(".mp4") ||
                        cleanUrl.contains("r2.cloudflarestorage") || cleanUrl.contains("hcdn.dev") -> {
                            callback(
                                newExtractorLink(
                                    source = "$defaultLabel ${idx + 1}",
                                    name = "$defaultLabel ${idx + 1}",
                                    url = cleanUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = referer
                                    this.quality = Qualities.Unknown.value
                                    this.headers = cdnHeaders
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
                headers = mapOf("User-Agent" to browserUA, "Accept" to "application/json"),
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
        val isSpecial: Boolean = false,
        val firstEpNumber: Int = 0,  // Primer número de episodio (de initialEpisodes[0])
    )

    private data class MovieSource(
        val label: String,
        val name: String,
        val token: String,
        val type: String,
        val provider: String,
        /** ID completo del source, formato: "<episodeId>-<index>" o "<movieId>-<index>". */
        val id: String = "",
    ) {
        /**
         * Deriva el episodeId/movieId (UUID) a partir del id del source.
         * Si id="cad7248e-08f6-4258-9f99-83e178a3e943-0", retorna "cad7248e-08f6-4258-9f99-83e178a3e943".
         * Si id no tiene el formato esperado, retorna "".
         */
        fun deriveContentId(): String {
            // UUID = 8-4-4-4-12 hex chars. Remover el sufijo "-<index>" final.
            val uuidPattern = Regex("""([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""")
            return uuidPattern.find(id)?.groupValues?.get(1) ?: ""
        }
    }

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
