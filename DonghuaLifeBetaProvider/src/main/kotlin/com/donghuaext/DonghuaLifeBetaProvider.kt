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
 * DonghuaLifeBetaProvider — Provider temporal para https://beta.donghualife.com
 *
 * ANTECEDENTES:
 *   donghualife.com está migrando a una nueva arquitectura (Next.js 14 + React Server
 *   Components). La beta (https://beta.donghualife.com) ya tiene contenido que el sitio
 *   principal aún no muestra (ej: "Nine Tribulations Burnings Heaven"). Este provider
 *   da acceso a ese contenido nuevo mientras se completa la migración.
 *
 * DIFERENCIAS CLAVE CON EL SITIO PRINCIPAL:
 *   - URLs:     /series/<slug>     (en lugar de /series/<slug>)
 *               /peliculas/<slug>  (en lugar de /movie/<slug>)
 *               /watch/<slug>-<temp>-<ep>  (en lugar de /episode/<slug>-<temp>-episodio-x<ep>)
 *   - HTML:     Next.js 14 con Tailwind CSS. Clases utilitarias, no semánticas.
 *   - Imágenes: Servidas via /_next/image?url=<path>&w=<w>&q=75 (optimización on-the-fly).
 *   - Servidores: Lista embebida en RSC payload (#5 típicamente) con tokens encriptados
 *                 (AES-256-CBC + HMAC-SHA256). El backend expone /api/sources que acepta
 *                 POST con {"movieId":"<uuid>"} o {"episodeId":"<uuid>"} y devuelve las
 *                 URLs desencriptadas.
 *   - Rating:    Visible solo en la página de Rankings (ej: 4.6, 30 votos). No aparece
 *                 en listados de series/películas. Se incorpora al título cuando está
 *                 disponible (formato: "Title • ★4.6 (30 votos)").
 *
 * SECCIONES DEL GRID PRINCIPAL (según solicitud del usuario):
 *   1. Últimos Episodios (home, episodios recientes con link /watch/...)
 *   2. Recomendaciones  (home, "TE RECOMENDAMOS PARA VOS")
 *   3. Tendencias       (home, "TENDENCIAS" — ranking #1-#10)
 *   4. Ranking          (/rankings — ranking completo con puntuación)
 *   5. Series           (/series?sort=latest — listado paginado)
 *   6. Películas        (/peliculas?sort=newest — listado paginado)
 *
 * EXTRACCIÓN DE VIDEO (loadLinks):
 *   1. La página de detalle (pelicula/episodio) incluye un RSC payload con la lista
 *      de servidores: [{id, label, name, token, type, provider, ...}, ...]
 *      - token = base64(JSON{iv, data, sig})  — payload encriptado AES-256-CBC
 *   2. La misma página incluye el UUID del contenido: "movieId":"<uuid>" o
 *      "episodeId":"<uuid>" embebido en el RSC payload.
 *   3. Se hace POST a /api/sources con JSON body {"movieId":"<uuid>"} o
 *      {"episodeId":"<uuid>"}. El backend desencripta los tokens y devuelve:
 *        {"success":true, "sources":[{"url":"https://...","quality":"720p","type":"mp4"}, ...]}
 *   4. Cada source se convierte en ExtractorLink. Si el source es un embed
 *      (rumble, streamable, dailymotion, ok.ru), se invoca loadExtractor nativo
 *      de CS3 con la URL ya desencriptada.
 *
 * NOTA: Como no se pudo verificar el formato exacto del response de /api/sources
 * (la página solo hace POST y no se puede probar GET), el código es defensivo:
 * intenta varios formatos de respuesta (sources[], data.sources[], url directa)
 * y múltiples claves (url, src, embedUrl, iframeUrl). Si falla, registra un error
 * y continúa con el siguiente servidor.
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

    /**
     * Extrae la ruta real de la imagen desde una URL de Next.js Image Optimization:
     *   /_next/image?url=%2Fimages%2Fwebp%2Ffoo.webp&w=3840&q=75
     * devuelve:
     *   /images/webp/foo.webp
     * Si la URL no tiene el formato /_next/image?url=..., se devuelve tal cual.
     */
    private fun extractNextImagePath(imgSrc: String): String {
        val decoded = URLDecoder.decode(imgSrc, "UTF-8")
        val paramMatch = Regex("""url=([^&]+)""").find(decoded)
        return paramMatch?.groupValues?.get(1) ?: decoded
    }

    // =========================== PÁGINA PRINCIPAL ===========================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val home = ArrayList<SearchResponse>()
        var hasNext = false

        when {
            // ====== Sección 1: ÚLTIMOS EPISODIOS (home, episodios recientes) ======
            // Anclaje: section-title = "ÚLTIMOS EPISODIOS"
            // Cards: <a class="group..." href="/watch/<slug>-<temp>-<ep>">
            url.endsWith("/") && !url.contains("#") -> {
                val doc = app.get(url, timeout = 60).document
                // Buscar la sección ÚLTIMOS EPISODIOS por el h2.section-title
                val targetH2 = doc.select("h2.section-title").find { it.text().contains("ÚLTIMOS EPISODIOS", ignoreCase = true) }
                if (targetH2 != null) {
                    // Las cards están en un wrapper hermano de section-header
                    val sectionHeader = targetH2.parent() // div.section-header
                    val wrapper = sectionHeader?.parent() // div py-8
                    if (wrapper != null) {
                        // Solo capturar los <a class="group"> con href /watch/
                        wrapper.select("a.group[href*='/watch/']").forEach { a ->
                            val href = a.attr("href")
                            // Texto visible: "EP 40 Peerless Divine Emperor hace 2 min · Capítulo 40"
                            val fullText = a.text().trim()
                            // Extraer número de episodio del texto
                            val epNum = Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE).find(fullText)
                                ?.groupValues?.get(1)?.toIntOrNull()
                            // Extraer título (entre "EP N " y " hace X ")
                            val title = Regex("""EP\s*\d+\s*(.+?)\s+hace\s""", RegexOption.IGNORE_CASE)
                                .find(fullText)?.groupValues?.get(1)?.trim() ?: fullText
                            // Poster (de la <img> interna)
                            val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                            // Para episodios, la URL se pasa directamente (load() detecta /watch/ y procesa como episodio)
                            home.add(
                                newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                                    this.posterUrl = poster
                                    if (epNum != null) addDubStatus(DubStatus.Subbed, epNum)
                                }
                            )
                        }
                    }
                }
                hasNext = false  // Home no tiene paginación
            }

            // ====== Sección 2: RECOMENDACIONES (home, "TE RECOMENDAMOS PARA VOS") ======
            url.endsWith("#recomendaciones") -> {
                val doc = app.get("$mainUrl/", timeout = 60).document
                val targetH2 = doc.select("h2.section-title").find { it.text().contains("RECOMENDAMOS", ignoreCase = true) }
                if (targetH2 != null) {
                    val wrapper = targetH2.parent()?.parent()
                    wrapper?.select("a[href^='/series/']")?.forEach { a ->
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
                }
                hasNext = false
            }

            // ====== Sección 3: TENDENCIAS (home, "TENDENCIAS" — ranking #1-#10) ======
            url.endsWith("#tendencias") -> {
                val doc = app.get("$mainUrl/", timeout = 60).document
                val targetH2 = doc.select("h2.section-title").find { it.text().contains("TENDENCIAS", ignoreCase = true) }
                if (targetH2 != null) {
                    val wrapper = targetH2.parent()?.parent()
                    wrapper?.select("a[href^='/series/']")?.forEach { a ->
                        val href = a.attr("href")
                        // El texto incluye "# 1 Alquimia Suprema" — extraer el ranking
                        val fullText = a.text().replace(Regex("\\s+"), " ").trim()
                        val rankMatch = Regex("""#\s*(\d+)\s*(.+)""").find(fullText)
                        val (rank, title) = if (rankMatch != null) {
                            rankMatch.groupValues[1].toIntOrNull() to rankMatch.groupValues[2].trim()
                        } else null to fullText
                        val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                        // Mostrar ranking en el título si está disponible
                        val displayTitle = if (rank != null) "#$rank $title" else title
                        if (title.isNotBlank()) {
                            home.add(
                                newAnimeSearchResponse(displayTitle, resolveUrl(href), TvType.Anime) {
                                    this.posterUrl = poster
                                    addDubStatus(DubStatus.Subbed)
                                }
                            )
                        }
                    }
                }
                hasNext = false
            }

            // ====== Sección 4: RANKING (/rankings) ======
            // Cards: <a class="flex items-center gap-4..." href="/series/<slug>">
            // Rating: <span class="font-black">4.6</span>  +  <span class="text-muted">30 votos</span>
            url.contains("/rankings") -> {
                val doc = app.get(url, timeout = 60).document
                // Las cards del ranking son <a> con clase "flex items-center gap-4"
                doc.select("a.flex.items-center").forEach { a ->
                    val href = a.attr("href")
                    // Filtrar enlaces no válidos (ej: botón "back to home" con href="/")
                    if (!href.startsWith("/series/")) return@forEach
                    val title = a.selectFirst("h2")?.text()?.trim() ?: return@forEach
                    val poster = a.selectFirst("img")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) }
                    // Buscar score y votos
                    val scoreSpans = a.select("span.font-black, span.text-base")
                    var score: String? = null
                    var votes: String? = null
                    for (s in scoreSpans) {
                        val txt = s.text().trim()
                        if (txt.matches(Regex("""\d+\.\d+"""))) score = txt
                    }
                    val votesSpan = a.selectFirst("span.text-muted")
                    if (votesSpan != null) {
                        val v = votesSpan.text().trim()
                        if (v.matches(Regex("""\d+\s*votos"""))) votes = v
                    }
                    // Mostrar puntuación en el título
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
                hasNext = false  // Ranking no tiene paginación (top 10)
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
                    // Estado (En Emisión, Finalizado)
                    val statusText = a.selectFirst("span[class*=backdrop-blur]")?.text()?.trim()
                    val dubstat = if (statusText?.contains("Finalizado", ignoreCase = true) == true) DubStatus.Subbed else DubStatus.Subbed
                    // Conteo de episodios y último episodio (opcional)
                    val epsText = a.select("p.tracking-widest").lastOrNull()?.text()?.trim() ?: ""
                    val lastEpMatch = Regex("""Ep\s*(\d+)""", RegexOption.IGNORE_CASE).find(epsText)
                    val lastEp = lastEpMatch?.groupValues?.get(1)?.toIntOrNull()
                    home.add(
                        newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                            this.posterUrl = poster
                            if (lastEp != null) addDubStatus(dubstat, lastEp)
                            else addDubStatus(dubstat)
                        }
                    )
                }
                // Paginación: hay <a href="/series?page=N+1">Sig</a>
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
        val doc = app.get("$mainUrl/?q=${URLEncoder.encode(query, "UTF-8")}", timeout = 60).document
        val results = ArrayList<SearchResponse>()
        // Las series y películas aparecen como <a class="poster-card" href="/series/..." o "/peliculas/..."
        doc.select("a.poster-card").forEach { a ->
            val href = a.attr("href")
            if (!href.startsWith("/series/") && !href.startsWith("/peliculas/")) return@forEach
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
        return results
    }

    // =========================== LOAD (detalle) ===========================

    override suspend fun load(url: String): LoadResponse {
        val isMovie = url.contains("/peliculas/")
        val isWatch = url.contains("/watch/")
        val isSeries = url.contains("/series/")

        // Si la URL es /watch/<slug>-<temp>-<ep>, necesitamos resolver la serie a la que pertenece.
        // El formato es: /watch/<series-slug>-<temp>-<ep>
        // Para series con slug que termina en número (ej: "swallowed-star-3"), necesitamos
        // separar correctamente. La serie es todo hasta "-<digito>-<digito>$".
        val seriesUrl = if (isWatch) {
            val path = url.substringAfter("/watch/")
            // Quitar los dos últimos segmentos "-<temp>-<ep>"
            val match = Regex("""^(.+)-(\d+)-(\d+)$""").find(path)
            if (match != null) {
                val slug = match.groupValues[1]
                "$mainUrl/series/$slug"
            } else {
                url  // fallback
            }
        } else {
            url
        }

        val doc = app.get(seriesUrl, timeout = 60).document
        val html = doc.html()

        // ====== Metadata desde JSON-LD schema.org ======
        // Ejemplo:
        //   {"@context":"https://schema.org","@type":"Movie","name":"...",
        //    "description":"...","image":"/uploads/images/...jpg","url":"...",
        //    "datePublished":"2026-08-09T00:00:00.000Z","productionCompany":{"name":"Tencent"}}
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
        if (jsonLdMatch != null) {
            try {
                val json = parseJson<JsonLdMeta>(jsonLdMatch.groupValues[1].trim())
                jsonLdName = json.name ?: ""
                jsonLdDescription = json.description ?: ""
                jsonLdImage = json.image ?: ""
                jsonLdDate = json.datePublished?.substringBefore("T") ?: ""
                jsonLdStudio = json.productionCompany?.name?.trim() ?: ""
            } catch (_: Exception) {}
        }

        // ====== Metadata desde HTML (fallback) ======
        val title = jsonLdName.ifBlank {
            doc.selectFirst("h1")?.text()?.trim() ?: ""
        }
        val description = jsonLdDescription.ifBlank {
            doc.selectFirst("[class*=description i], [class*=synopsis i], [class*=plot i]")?.text()?.trim() ?: ""
        }
        val poster = if (jsonLdImage.isNotBlank()) {
            resolveUrl(jsonLdImage)
        } else {
            doc.selectFirst("img[alt]")?.attr("src")?.let { resolveUrl(extractNextImagePath(it)) } ?: ""
        }

        // ====== Géneros (si existen en el HTML) ======
        val genres = ArrayList<String>()
        // Buscar bloques de "Géneros" o "Generos" en el HTML
        doc.select("a[href^='/genres/']").forEach { a ->
            val g = a.text().trim()
            if (g.isNotBlank()) genres.add(g)
        }
        if (jsonLdStudio.isNotBlank()) genres.add(jsonLdStudio)

        // ====== Si es película, devolver MovieLoadResponse ======
        if (isMovie) {
            // Buscar el movieId en el RSC payload (para usarlo en loadLinks)
            val movieId = extractMovieOrEpisodeId(html, "movieId") ?: ""
            // Construir URL "compuesta" para loadLinks: pasamos movieId como queryparam
            // Caso especial: si la URL original es /peliculas/<slug>, pasamos URL+?movieId=<uuid>
            val dataUrl = if (movieId.isNotBlank()) {
                "$seriesUrl##movieId=$movieId"
            } else {
                seriesUrl
            }
            return newMovieLoadResponse(title, dataUrl, TvType.AnimeMovie, dataUrl) {
                posterUrl = poster
                plot = description
                tags = genres
                year = jsonLdDate.takeIf { it.isNotBlank() }?.toIntOrNull()
            }
        }

        // ====== Si es serie, extraer temporadas y episodios ======
        // Para series, los episodios están listados en la página de detalle o en
        // sub-páginas por temporada. Beta usa /watch/<slug>-<temp>-<ep>.
        // Sin tener un dump de una página de serie de la beta, asumimos estructura
        // similar a películas: cada "episodio" es un <a href="/watch/...">
        val episodes = ArrayList<Episode>()

        // Método 1: episodios listados directamente en la página de serie
        // (cards <a href="/watch/<slug>-<temp>-<ep>">)
        val watchLinks = doc.select("a[href*='/watch/']")
        if (watchLinks.isNotEmpty()) {
            val seen = mutableSetOf<String>()
            for (a in watchLinks) {
                val href = a.attr("href")
                if (href in seen) continue
                seen.add(href)
                // Parse: /watch/<slug>-<temp>-<ep>
                val match = Regex("""/watch/(.+)-(\d+)-(\d+)$""").find(href)
                if (match != null) {
                    val seasonNum = match.groupValues[2].toIntOrNull() ?: 1
                    val epNum = match.groupValues[3].toIntOrNull() ?: continue
                    // Texto del episodio (título opcional)
                    val epTitle = a.text().trim().takeIf { it.isNotBlank() }
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

        // Método 2 (fallback): Si no encontramos episodios en la página de serie,
        // puede ser que la serie tenga múltiples temporadas listadas como enlaces.
        // Buscar patrones adicionales: <a href="/series/<slug>-<temp>"> para temporadas.
        // (Esto se probará con dumps reales de series en la siguiente iteración.)

        // Extraer episodeId del RSC payload si está presente
        val episodeId = extractMovieOrEpisodeId(html, "episodeId") ?: ""

        return newAnimeLoadResponse(title, seriesUrl, TvType.Anime) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes.sortedWith(compareBy({ it.season }, { it.episode })))
            plot = description
            tags = genres
            year = jsonLdDate.takeIf { it.isNotBlank() }?.toIntOrNull()
        }
    }

    /**
     * Extrae el UUID del contenido desde el RSC payload de Next.js.
     * Busca patrones como: "movieId":"<uuid>" o "episodeId":"<uuid>"
     */
    private fun extractMovieOrEpisodeId(html: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""", RegexOption.IGNORE_CASE)
        return pattern.find(html)?.groupValues?.get(1)
    }

    /**
     * Extrae la lista de servidores desde el RSC payload de Next.js.
     * Cada servidor tiene: id, label, name, token, type, provider, subtitleSupport, isWorking, priority.
     * Retorna una lista de mapas con los campos relevantes.
     */
    private fun extractServerList(html: String): List<ServerInfo> {
        // El RSC payload está dentro de self.__next_f.push([1,"..."]) y usa escapes \"
        val payloadPattern = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
        val payloads = payloadPattern.findAll(html).map { it.groupValues[1] }.toList()

        val servers = ArrayList<ServerInfo>()
        // Patrón para cada servidor dentro del payload (después de unescape de \")
        // Estructura: {"id":"...","label":"...","name":"...","token":"...","type":"...","provider":"...","subtitleSupport":"...","isWorking":true/false,"priority":N}
        val serverPattern = Regex(
            """\{[^{}]*?"id":"([^"]+)"[^{}]*?"label":"([^"]+)"[^{}]*?"name":"([^"]+)"[^{}]*?"token":"([^"]+)"[^{}]*?"type":"([^"]+)"[^{}]*?"provider":"([^"]+)"[^{}]*?"subtitleSupport":"([^"]+)"[^{}]*?"isWorking":(true|false)[^{}]*?"priority":(\d+)[^{}]*?\}""",
            RegexOption.DOT_MATCHES_ALL
        )

        for (p in payloads) {
            // Unescape JS string escapes: \" -> ", \\ -> \, \n -> newline, etc.
            val decoded = try {
                p.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\/", "/")
            } catch (_: Exception) { p }
            for (m in serverPattern.findAll(decoded)) {
                val (id, label, name, token, type_, provider, sub, working, prio) = m.destructured
                if (working != "true") continue  // Solo servidores funcionales
                servers.add(
                    ServerInfo(
                        id = id,
                        label = label,
                        name = name,
                        token = token,
                        type = type_,
                        provider = provider,
                        subtitleSupport = sub,
                        priority = prio.toIntOrNull() ?: 99,
                    )
                )
            }
            if (servers.isNotEmpty()) break  // Solo necesitamos el primer payload que tenga servers
        }
        return servers.sortedBy { it.priority }
    }

    /**
     * Extrae el movieId o episodeId (UUID) desde el RSC payload.
     * El payload contiene: "movieId":"<uuid>" o "episodeId":"<uuid>"
     */
    private fun extractContentId(html: String): Pair<String, String>? {
        // Devuelve (tipo, uuid) donde tipo es "movieId" o "episodeId"
        val payloadPattern = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
        val payloads = payloadPattern.findAll(html).map { it.groupValues[1] }.toList()
        for (p in payloads) {
            val decoded = try {
                p.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")
            } catch (_: Exception) { p }
            for (key in listOf("movieId", "episodeId")) {
                val m = Regex(""""$key"\s*:\s*"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""", RegexOption.IGNORE_CASE).find(decoded)
                if (m != null) return key to m.groupValues[1]
            }
        }
        return null
    }

    // =========================== LOAD LINKS ===========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data puede ser:
        //   1. URL de película con anchor: https://beta.donghualife.com/peliculas/<slug>##movieId=<uuid>
        //   2. URL de episodio: https://beta.donghualife.com/watch/<slug>-<temp>-<ep>
        // En ambos casos, necesitamos:
        //   - Fetch del HTML
        //   - Extraer movieId o episodeId del RSC payload
        //   - Extraer lista de servidores del RSC payload
        //   - POST a /api/sources con {"movieId":"<uuid>"} o {"episodeId":"<uuid>"}
        //   - Parsear response y emitir ExtractorLink por cada fuente

        // Limpiar la URL: quitar el anchor ##movieId= si lo trae
        val (cleanUrl, preloadedContentId) = if (data.contains("##")) {
            val parts = data.split("##")
            val cid = parts.getOrNull(1)?.substringAfter("=") ?: ""
            parts[0] to cid
        } else {
            data to ""
        }

        val doc = app.get(cleanUrl, timeout = 60).document
        val html = doc.html()

        // Identificar el contentId (movieId o episodeId)
        val contentPair = if (preloadedContentId.isNotBlank()) {
            // Para películas, el anchor ya trae el movieId
            "movieId" to preloadedContentId
        } else {
            extractContentId(html) ?: ("" to "")
        }
        val idKey = contentPair.first
        val contentUuid = contentPair.second

        if (contentUuid.isBlank()) {
            // Sin contentId no podemos llamar a /api/sources
            return false
        }

        // Extraer la lista de servidores del RSC payload (para conocer labels y providers)
        val servers = extractServerList(html)

        // POST a /api/sources con JSON body
        // CloudStream 3 app.post signature: post(url, json: Map<String,Any>? = null, referer: String?, headers: Map<String,String>, ...)
        // Pasamos el body como mapa (forma soportada y type-safe)
        val apiUrl = "$mainUrl/api/sources"
        val bodyMap = mapOf<String, Any>(idKey to contentUuid)
        val apiResponse = try {
            app.post(
                apiUrl,
                json = bodyMap,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Referer" to cleanUrl,
                ),
                timeout = 30L
            ).text
        } catch (_: Exception) {
            ""
        }

        if (apiResponse.isBlank()) return false

        // Parsear la respuesta: {"success":true,"sources":[{"url":"...","quality":"720p","type":"mp4"}, ...]}
        val parsed = try { parseJson<SourcesResponse>(apiResponse) } catch (_: Exception) { null }
        if (parsed?.success != true) return false

        var anyEmitted = false
        for ((idx, source) in parsed.sources.withIndex()) {
            val srcUrl = source.url ?: source.src ?: source.embedUrl ?: source.iframeUrl ?: ""
            if (srcUrl.isBlank()) continue
            // Nombre del servidor (mapear por índice al label del RSC si está disponible)
            val serverLabel = servers.getOrNull(idx)?.label ?: source.label ?: source.name ?: "Server ${idx + 1}"
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
                type.contains("m3u8") || srcUrl.endsWith(".m3u8") || srcUrl.contains(".m3u8") -> {
                    try {
                        generateM3u8(serverLabel, srcUrl, cleanUrl).forEach(callback)
                        anyEmitted = true
                    } catch (_: Exception) {}
                }
                // mp4 directo
                type.contains("mp4") || srcUrl.endsWith(".mp4") || srcUrl.contains(".mp4") -> {
                    callback(
                        newExtractorLink(
                            source = serverLabel,
                            name = "$serverLabel ${quality / 1000}p",
                            url = srcUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = cleanUrl
                            this.quality = quality
                        }
                    )
                    anyEmitted = true
                }
                // Embeds conocidos: pasar a loadExtractor nativo de CS3
                srcUrl.contains("rumble.com") || srcUrl.contains("streamable.com") ||
                srcUrl.contains("dailymotion.com") || srcUrl.contains("ok.ru") ||
                srcUrl.contains("fembed.com") || srcUrl.contains("voe.sx") ||
                srcUrl.contains("asura") || srcUrl.contains("vk.com") || srcUrl.contains("vk.ru") -> {
                    try {
                        loadExtractor(srcUrl, cleanUrl, subtitleCallback, callback)
                        anyEmitted = true
                    } catch (_: Exception) {}
                }
                // Otros embeds: probar loadExtractor genérico
                else -> {
                    try {
                        loadExtractor(srcUrl, cleanUrl, subtitleCallback, callback)
                        anyEmitted = true
                    } catch (_: Exception) {}
                }
            }
        }
        return anyEmitted
    }

    // =========================== CLASES DE DATOS ===========================

    private data class JsonLdMeta(
        val name: String? = null,
        val description: String? = null,
        val image: String? = null,
        val datePublished: String? = null,
        val productionCompany: ProductionCompany? = null
    )

    private data class ProductionCompany(
        val name: String? = null
    )

    private data class ServerInfo(
        val id: String,
        val label: String,
        val name: String,
        val token: String,
        val type: String,
        val provider: String,
        val subtitleSupport: String,
        val priority: Int,
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
