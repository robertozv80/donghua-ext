package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import kotlin.collections.ArrayList

/** UA de navegador (varios hosts lo exigen). */
const val SD_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

/**
 * v24.2: REESCRITO para el nuevo seriesdonghua.com (sitio propio, no WordPress).
 *
 * Estructura verificada en vivo (2026-09):
 * - Home: 2 secciones de cards article.donghua-card -> "Nuevos Episodios"
 *   (links /<slug>-episodio-N/ con badge EP N) y "Series Donghua en Emision".
 * - Catalogos: /todos-los-donghuas, /donghuas-en-emision (badge
 *   badge-status-emision), /donghuas-finalizados (badge-status-finalizado);
 *   paginacion ?page=N (24 cards por pagina).
 * - Generos: /accion/, /artes-marciales/, /cultivacion/, ... con ?page=N.
 * - Buscador real: GET /buscar.php?s=<query> (filtra de verdad; ?s= NO filtra).
 * - Ficha /<slug>/: JSON-LD TVSeries (sinopsis, generos, numberOfEpisodes),
 *   sinopsis en panel, generos como a.genre-pill, grid de episodios
 *   div#episodes-grid article.episode-card-item (data-ep).
 * - Episodio /<slug>-episodio-N/: SIN iframes en el HTML; los servidores son
 *   botones button.server-tab-btn (data-video-id + data-server-index) y el
 *   embed se resuelve con POST /api/player/get-server
 *   {video_id, server_index} + header X-CSRF-TOKEN (meta[name=csrf-token])
 *   -> {"success":true,"embed_url":"..."}. Acepta JSON y form-encoded.
 *
 * Visual (pedido del usuario, igual que TioDonghua v24):
 * - "Nuevos Episodios" del home como fila horizontal (isHorizontalImages).
 * - Cards del grid SIN el viejo "Subtitulado": el sitio no publica puntuacion
 *   (no hay div.rating/score en ninguna pagina), asi que se muestra el estado.
 * - Ficha completa: sinopsis, generos, estado, poster HD de portada.
 */
class SeriesDonghuaProvider : MainAPI() {

    override var mainUrl = "https://seriesdonghua.com"
    override var name = "SeriesDonghua"
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
        "$mainUrl/donghuas-en-emision" to "En Emisión",
        "$mainUrl/donghuas-finalizados" to "Finalizados",
        "$mainUrl/todos-los-donghuas" to "Todos los Donghuas",
        "$mainUrl/##accion" to "Género: Acción",
        "$mainUrl/##artes-marciales" to "Género: Artes Marciales",
        "$mainUrl/##cultivacion" to "Género: Cultivación",
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

    /**
     * v24.3: las imagenes del sitio no cargaban en la app (el loader de
     * imagenes de CloudStream recibe 403/validaciones de Cloudflare con su UA;
     * el usuario las reporto sin cargar). Envolver con wsrv.nl (proxy de
     * imagenes de Discord, mismo dominio que ya usa el repo para el icono):
     * verificado en vivo que responde 200 image/webp para thumbs y portadas.
     * El origen va SIN esquema (wsrv.nl exige protocol-relative). Si la url es
     * relativa se resuelve antes contra mainUrl.
     */
    private fun sdProxyImg(url: String): String {
        val abs = resolveUrl(url)
        if (abs.isBlank() || !abs.startsWith("http")) return abs
        return "https://wsrv.nl/?url=" + java.net.URLEncoder.encode(abs.removePrefix("https://"), "UTF-8") + "&w=400"
    }

    private suspend fun pageGet(url: String, timeout: Long = 30L) =
        app.get(url, headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = timeout)

    // ========== getMainPage ==========
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data == "$mainUrl/"
        val isGenre = request.data.startsWith("$mainUrl/##")

        // Home: la seccion "Nuevos Episodios" solo existe en la pagina 1; la
        // paginacion continua en el archivo /episodios (24 cards, mismo markup).
        val url = when {
            isHome && page > 1 -> "$mainUrl/episodios?page=$page"
            isGenre -> {
                val slug = request.data.removePrefix("$mainUrl/##")
                "$mainUrl/$slug/" + if (page > 1) "?page=$page" else ""
            }
            page > 1 -> "$request.data?page=$page"
            else -> request.data
        }
        val doc = pageGet(url).document

        val items: List<SearchResponse> = if (isHome) {
            // Solo cards de episodio del home (los catalogos estan en otras pestañas)
            doc.select("article.donghua-card").mapNotNull { parseSdEpisodeCard(it) }
        } else {
            doc.select("article.donghua-card").mapNotNull { parseSdCard(it) }
        }

        return newHomePageResponse(
            // v24.2: fila horizontal de posters para "Nuevos Episodios"
            list = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = isHome),
            hasNext = doc.select("ul.pagination a.page-link[href]")
                .any { it.attr("href").contains("page=${page + 1}") }
        )
    }

    /**
     * Card de ficha (catalogos, generos, emision, finalizados, resultados).
     * NOTA: se intento mapear la miniatura de card a la portada HD
     * /imagenes-portada/ pero el sitio no la tiene para todas las series
     * (solo para fichas nuevas): quedan las miniaturas de card.
     */
    private fun parseSdCard(article: org.jsoup.nodes.Element): SearchResponse? {
        val href = article.selectFirst("a[href]")?.attr("href") ?: return null
        if (href.contains("-episodio-")) return null
        val title = article.selectFirst("h3.card-title")?.text()?.trim()?.ifBlank { null }
            ?: article.selectFirst("img")?.attr("alt")
                ?.removePrefix("Donghua ")?.removeSuffix(" Sub Español")?.trim()
            ?: return null

        // v24.2: sin "Subtitulado" en la etiqueta (el sitio no expone puntuacion)
        return newAnimeSearchResponse(title, resolveUrl(href)) {
            // v24.3: poster via wsrv.nl
            this.posterUrl = sdProxyImg(article.selectFirst("img")?.attr("src") ?: "")
        }
    }

    /** Card de episodio ("Nuevos Episodios" del home): titulo de la serie + EP N. */
    private fun parseSdEpisodeCard(article: org.jsoup.nodes.Element): SearchResponse? {
        val href = article.selectFirst("a[href]")?.attr("href") ?: return null
        if (!href.contains("-episodio-")) return null
        val title = article.selectFirst("h3.card-title")?.text()?.trim()?.ifBlank { null }
            ?: article.selectFirst("img")?.attr("alt")
                ?.replace(Regex("\\s*Episodio\\s*\\d+.*$"), "")?.trim()
            ?: return null
        val epNum = Regex("-episodio-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
        return newAnimeSearchResponse(title, resolveUrl(href)) {
            // v24.3: poster via wsrv.nl
            this.posterUrl = sdProxyImg(article.selectFirst("img")?.attr("src") ?: "")
            // etiqueta de la card: "Subtitulado - N" (numero de episodio)
            addDubStatus(DubStatus.Subbed, epNum)
        }
    }

    // ========== search ==========
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            // v24.2: el buscador REAL es /buscar.php?s= (el ?s= clasico devuelve
            // el home sin filtrar)
            val doc = pageGet("$mainUrl/buscar.php?s=${java.net.URLEncoder.encode(query, "UTF-8")}").document
            doc.select("article.donghua-card").mapNotNull { parseSdCard(it) }
                .distinctBy { it.url }.take(30)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ========== load ==========
    override suspend fun load(url: String): LoadResponse {
        // Episodio suelto (clic en la fila del home) -> ficha de serie
        val seriesUrl = if (url.contains("-episodio-")) episodeToSeriesUrl(url) else url
        val doc = pageGet(seriesUrl).document
        val html = doc.html()

        val title = doc.selectFirst("h1.hero-title")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                // "Renegade Immortal ✔️ DONGHUA Sub Español | SeriesDonghua" ->
                // quitar la parte del sitio y la decoracion "✔️ DONGHUA Sub Español"
                ?.let { t -> (if (t.contains("|")) t.substringBefore("|") else t)
                    .replace(Regex("\\s*(?:[✔✅★⚡]|DONGHUA|Donghua)?\\s*Sub\\s*Espa[ñn]ol\\s*$",
                        RegexOption.IGNORE_CASE), "").trim()
                }
            ?: seriesUrl.trimEnd('/').substringAfterLast('/')

        // v24.3: portada via wsrv.nl (proxy) + fallback a og:image
        val poster = (
            doc.selectFirst("img.hero-poster")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
                ?: ""
            ).let { sdProxyImg(resolveUrl(it)) }

        // JSON-LD TVSeries: description, genre[], numberOfEpisodes
        // v24.3 FIX: antes se extraia con Regex("...\\{.*?}...") y el motor regex
        // de Android (ICU) rechaza el '}' literal sin escapar (la JVM de escritorio
        // lo tolera: PatternSyntaxException en produccion, load() crasheaba y el
        // tapping en una card no abria la ficha). JSoup no necesita regex.
        val jsonLd = doc.select("script[type=\"application/ld+json\"]")
            .map { it.data() }
            .firstOrNull { it.contains("\"TVSeries\"") }
        val ldDescription = jsonLd
            ?.let { Regex("\"description\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(it)?.groupValues?.get(1) }
            ?.let { sdUnescapeJson(it) }
        val ldEpisodes = jsonLd
            ?.let { Regex("\"numberOfEpisodes\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        // Sinopsis: JSON-LD o panel HTML de la ficha
        val description = ldDescription?.takeIf { it.isNotBlank() }
            ?: doc.select("section.glass-panel p").firstOrNull { it.text().length > 60 }?.text()?.trim()
            ?: ""

        // Generos: pills de la ficha
        val genres = doc.select("a.genre-pill").map { it.text().trim() }.filter { it.isNotBlank() }
            .ifEmpty {
                jsonLd?.let { ld ->
                    Regex("\"genre\"\\s*:\\s*\\[(.*?)\\]").find(ld)?.groupValues?.get(1)
                        ?.let { arr -> Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(arr)
                            .map { m -> sdUnescapeJson(m.groupValues[1]) }.toList() }
                } ?: emptyList()
            }

        // Estado: badges de la ficha
        val showStatus = when {
            doc.selectFirst(".badge-status-finalizado") != null -> ShowStatus.Completed
            doc.selectFirst(".badge-status-emision") != null -> ShowStatus.Ongoing
            else -> null
        }

        // Episodios: grid article.episode-card-item con data-ep
        val episodes = ArrayList<Episode>()
        doc.select("article.episode-card-item").forEach { card ->
            val a = card.selectFirst("a[href*=\"-episodio-\"]") ?: return@forEach
            val href = resolveUrl(a.attr("href"))
            val epNum = card.attr("data-ep").toIntOrNull()
                ?: Regex("-episodio-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
            episodes.add(newEpisode(href) {
                this.name = if (epNum != null) "Episodio $epNum" else a.attr("title").ifBlank { null }
                this.episode = epNum
            })
        }
        // Respaldo: cualquier link de episodio en la ficha (por si el grid cambia)
        if (episodes.isEmpty()) {
            Regex("href=\"(/[a-z0-9-]+-episodio-\\d+/)\"").findAll(html).forEach { m ->
                val href = resolveUrl(m.groupValues[1])
                val epNum = Regex("-episodio-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(href) {
                    this.name = if (epNum != null) "Episodio $epNum" else null
                    this.episode = epNum
                })
            }
        }

        // Recomendaciones: otras temporadas + similares por genero (tope 16)
        val recommendations = fetchSdRecommendations(
            seriesUrl,
            doc.select("a.genre-pill").map { it.attr("href") }
        )

        // Ficha sin grid de episodios -> pelicula (loadLinks resuelve el embed)
        if (episodes.isEmpty()) {
            val moviePlot = buildString {
                append(description)
                if (ldEpisodes != null && ldEpisodes > 0) append("\n\nEpisodios: ").append(ldEpisodes)
                when (showStatus) {
                    ShowStatus.Ongoing -> append("\n\nEstado: En emisión")
                    ShowStatus.Completed -> append("\n\nEstado: Finalizada")
                    else -> {}
                }
            }.trim()
            return newMovieLoadResponse(title, seriesUrl, TvType.AnimeMovie, seriesUrl) {
                posterUrl = poster
                this.plot = moviePlot
                this.tags = genres
                if (recommendations.isNotEmpty()) this.recommendations = recommendations
            }
        }

        val plot = buildString {
            append(description)
            // v24.2: numero de episodios del sitio (JSON-LD) como dato extra
            if (ldEpisodes != null && ldEpisodes > 0 && ldEpisodes != episodes.size) {
                append("\n\nEpisodios (según el sitio): ").append(ldEpisodes)
            }
        }.trim()

        return newAnimeLoadResponse(title, seriesUrl, TvType.Anime) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE })
            this.showStatus = showStatus
            this.plot = plot
            this.tags = genres
            if (recommendations.isNotEmpty()) this.recommendations = recommendations
        }
    }

    /** "/slug-episodio-N/" -> "/slug/" (ficha de la serie). */
    private fun episodeToSeriesUrl(url: String): String {
        val path = url.substringAfter(mainUrl).trim('/').substringBefore('?')
        val m = Regex("^(.+)-episodio-\\d+$").find(path) ?: return url
        return "$mainUrl/${m.groupValues[1]}/"
    }

    /** Desescapa un string JSON basico (\\n, \", \\/). */
    private fun sdUnescapeJson(s: String): String = s
        .replace("\\\\", "\u0000")
        .replace("\\n", "\n")
        .replace("\\r", "")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace("\u0000", "\\")

    /**
     * Recomendaciones (tope 16):
     * 1) Otras temporadas via /buscar.php con la base del slug ("jade dynasty 4"
     *    -> "jade dynasty"); matchea por prefijo de slug.
     * 2) Similares: pagina aleatoria de un genero de la ficha.
     * 3) Fallback: pagina aleatoria del catalogo general.
     */
    private suspend fun fetchSdRecommendations(
        seriesUrl: String,
        genrePaths: List<String>
    ): List<SearchResponse> {
        return try {
            val all = ArrayList<SearchResponse>()
            val seen = mutableSetOf(seriesUrl.trimEnd('/'))

            fun cardSlug(href: String) = href.trimEnd('/').substringAfterLast('/')

            // ===== 1) Otras temporadas =====
            val selfSlug = seriesUrl.trimEnd('/').substringAfterLast('/')
            val baseSlug = Regex("-\\d+$").replace(selfSlug, "")
            if (baseSlug.isNotBlank() && baseSlug != selfSlug) {
                try {
                    val d = pageGet("$mainUrl/buscar.php?s=${java.net.URLEncoder.encode(baseSlug.replace('-', ' '), "UTF-8")}").document
                    val seasons = d.select("article.donghua-card").mapNotNull { card ->
                        val href = card.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                        if (href.contains("-episodio-")) return@mapNotNull null
                        val cSlug = cardSlug(href)
                        if (cSlug == selfSlug || !cSlug.startsWith(baseSlug)) return@mapNotNull null
                        Triple(resolveUrl(href), card.selectFirst("h3.card-title")?.text()?.trim() ?: cSlug,
                            sdProxyImg(card.selectFirst("img")?.attr("src") ?: ""))
                    }.distinctBy { it.first }
                    seasons.sortedBy {
                        Regex("(\\d+)$").find(it.first.trimEnd('/').substringAfterLast('/'))
                            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }.forEach { (u, t, p) ->
                        if (u.trimEnd('/') !in seen && all.size < 16) {
                            seen.add(u.trimEnd('/'))
                            all.add(newAnimeSearchResponse(t, u) { this.posterUrl = p })
                        }
                    }
                } catch (_: Exception) {}
            }

            // ===== 2) Similares por genero (pagina aleatoria) =====
            for (g in genrePaths.map { resolveUrl(it) }.shuffled()) {
                if (all.size >= 12) break
                try {
                    val first = pageGet(g).document
                    val last = Regex("[?&]page=(\\d+)").findAll(first.html())
                        .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1
                    val page = if (last > 1) (1..last).random() else 1
                    val pool = (if (page == 1) first else pageGet("$g?page=$page").document)
                        .select("article.donghua-card").mapNotNull { card ->
                            val href = card.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                            if (href.contains("-episodio-")) return@mapNotNull null
                            val full = resolveUrl(href)
                            if (full.trimEnd('/') in seen) return@mapNotNull null
                            Triple(full, card.selectFirst("h3.card-title")?.text()?.trim() ?: return@mapNotNull null,
                                sdProxyImg(card.selectFirst("img")?.attr("src") ?: ""))
                        }
                    pool.shuffled().forEach { (u, t, p) ->
                        if (u.trimEnd('/') !in seen && all.size < 16) {
                            seen.add(u.trimEnd('/'))
                            all.add(newAnimeSearchResponse(t, u) { this.posterUrl = p })
                        }
                    }
                } catch (_: Exception) {}
            }

            // ===== 3) Fallback: catalogo general =====
            if (all.size < 8) try {
                val first = pageGet("$mainUrl/todos-los-donghuas").document
                val last = Regex("[?&]page=(\\d+)").findAll(first.html())
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1
                val page = if (last > 1) (1..last).random() else 1
                (if (page == 1) first else pageGet("$mainUrl/todos-los-donghuas?page=$page").document)
                    .select("article.donghua-card").forEach { card ->
                        if (all.size >= 16) return@forEach
                        val href = card.selectFirst("a[href]")?.attr("href") ?: return@forEach
                        if (href.contains("-episodio-")) return@forEach
                        val full = resolveUrl(href)
                        if (full.trimEnd('/') in seen) return@forEach
                        val t = card.selectFirst("h3.card-title")?.text()?.trim() ?: return@forEach
                        seen.add(full.trimEnd('/'))
                        all.add(newAnimeSearchResponse(t, full) {
                            this.posterUrl = sdProxyImg(card.selectFirst("img")?.attr("src") ?: "")
                        })
                    }
            } catch (_: Exception) {}

            all.take(16)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ================================================================
    //  loadLinks: servidores via POST /api/player/get-server
    // ================================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        var links = 0
        val cb: (ExtractorLink) -> Unit = { links++; callback(it) }

        // data = URL del episodio (serie) o de la ficha (pelicula)
        val pageResp = try { pageGet(data) } catch (_: Exception) { null }
        var html = pageResp?.text ?: ""
        // v24.6 FIX (logcat 23:05): el cliente HTTP del app NO acompana las
        // cookies de sesion al POST (Laravel rechaza todo POST sin sesion aunque
        // el X-CSRF-TOKEN sea correcto): capturar las Set-Cookie del GET y
        // reenviarlas EXPLICITAS en el header Cookie del POST, como un navegador.
        val pageCookies = pageResp?.cookies?.toMutableMap() ?: mutableMapOf()
        var episodePage = data

        // Ficha (pelicula): resolver el primer episodio del grid
        if (!data.contains("-episodio-")) {
            val firstEp = Regex("href=\\\"(/[a-z0-9-]+-episodio-\\d+/)\\\"").find(html)
                ?.groupValues?.get(1)
            if (firstEp != null) {
                episodePage = mainUrl + firstEp
                val r2 = pageGet(episodePage)
                pageCookies.putAll(r2.cookies)
                html = r2.text
            } else {
                // v24.7 FIX: el boton "Ver" de la ficha de pelicula apunta a la
                // pagina de video real (el propio sitio enlazo <slug>-<slug>-
                // pelicula/ que el mismo devuelve 404, y el <slug>-1/ de v24.4
                // puede dejar de existir): NO adivinar el patron — usar el href
                // del a.btn-stream de la ficha y como ultimo recurso <slug>-1/.
                val btnHref = Regex("<a[^>]*href=\\\"(/[^\\\"]+)\\\"[^>]*class=\\\"btn-stream[^\\\"]*\\\"").find(html)
                    ?.groupValues?.get(1)
                    ?: Regex("<a[^>]*class=\\\"btn-stream[^\\\"]*\\\"[^>]*href=\\\"(/[^\\\"]+)\\\"").find(html)
                        ?.groupValues?.get(1)
                val candidates = listOfNotNull(
                    btnHref,
                    "/" + data.trimEnd('/').substringAfterLast('/') + "-1/"
                )
                for (cand in candidates) {
                    try {
                        val probe = pageGet(mainUrl + cand)
                        if (probe.text.contains("server-tab-btn")) {
                            episodePage = mainUrl + cand
                            pageCookies.putAll(probe.cookies)
                            html = probe.text
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        // v24.4: continuar si hay botones de servidor (pagina numerada de pelicula);
        // early-return solo si no hay episodios NI servidores resolubles.
        if (!episodePage.contains("-episodio-") && !html.contains("server-tab-btn")) {
            // Sin episodios resolubles; ultimo recurso: embeds sueltos en el HTML
            return extractSdEmbedsFromHtml(html, data, cb) > 0
        }

        val csrf = Regex("<meta name=\\\"csrf-token\\\" content=\\\"([^\\\"]+)\\\"").find(html)
            ?.groupValues?.get(1) ?: ""

        // Botones de servidor: data-video-id + data-server-index + etiqueta
        val btnPattern = Regex(
            "<button[^>]*class=\\\"server-tab-btn[^\\\"]*\\\"[^>]*>",
            RegexOption.DOT_MATCHES_ALL
        )
        var processed = false
        for (btnMatch in btnPattern.findAll(html)) {
            val tag = btnMatch.value
            val videoId = Regex("data-video-id=\\\"(\\d+)\\\"").find(tag)?.groupValues?.get(1) ?: continue
            val serverIndex = Regex("data-server-index=\\\"(\\d+)\\\"").find(tag)?.groupValues?.get(1) ?: continue
            val serverName = serverLabelFromBtn(html, btnMatch.range.first)

            val embedUrl = try {
                var resp = ""
                // v24.5: repetir mientras NO llegue embed_url (el 419 de Laravel
                // trae cuerpo NO vacio y el retry por cuerpo en blanco de v24.4
                // nunca se disparaba). v24.6: en el reintento RE-PLANTAR sesion con
                // un GET a la pagina y enviar SIEMPRE las cookies explicitas.
                repeat(2) {
                    if (!resp.contains("embed_url")) {
                        if (resp.isNotEmpty()) {
                            kotlinx.coroutines.delay(150L)
                            try { pageCookies.putAll(pageGet(episodePage, 20L).cookies) } catch (_: Exception) {}
                        }
                        val ck = pageCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        resp = try {
                            app.post(
                                "$mainUrl/api/player/get-server",
                                data = mapOf("video_id" to videoId, "server_index" to serverIndex),
                                headers = buildMap {
                                    put("User-Agent", SD_USER_AGENT)
                                    put("X-CSRF-TOKEN", csrf)
                                    put("X-Requested-With", "XMLHttpRequest")
                                    put("Accept", "application/json")
                                    if (ck.isNotBlank()) put("Cookie", ck)
                                },
                                referer = episodePage,
                                timeout = 20L
                            ).text
                        } catch (_: Exception) { "" }
                    }
                }
                // {"success":true,"embed_url":"https:\/\/..."} (tolerante a espacios)
                // v24.8 FIX: la unescape de \/ tenia 4 backslashes (backslash
                // LITERAL) y el embed llegaba con las barras escapadas al
                // extractor: el regex del videoId de Dailymotion no matcheaba y
                // devolvia links=0 en la app pese a que la metadata funciona
                // (verificado en sim). Dos backslashes = \/ real del JSON.
                if (resp.contains("embed_url")) {
                    Regex("\"embed_url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(resp)
                        ?.groupValues?.get(1)?.replace("\\/", "/")
                } else null
            } catch (_: Exception) { null }

            val embed = embedUrl ?: continue
            try {
                if (processSdEmbed(embed, episodePage, serverName, subtitleCallback, cb)) processed = true
            } catch (_: Exception) {}
        }

        // Respaldo: embeds sueltos en el HTML del episodio
        if (!processed) {
            if (extractSdEmbedsFromHtml(html, episodePage, cb) > 0) processed = true
        }
        // v24.4: processed ya implica enlaces emitidos; no exigir la doble
        // condicion (un servidor que emite via callback directo cuenta igual).
        return processed || links > 0
    }

    /** Etiqueta visible del boton (span sin clase server-badge). */
    private fun serverLabelFromBtn(html: String, fromIndex: Int): String {
        val window = html.substring(fromIndex, minOf(html.length, fromIndex + 600))
        val spans = Regex("<span>([^<]+)</span>").findAll(window).toList()
        return (spans.firstOrNull()?.groupValues?.get(1)?.trim() ?: "Servidor")
            .replaceFirstChar { it.uppercase() }
    }

    /** Enruta cada embed al extractor apropiado. */
    private suspend fun processSdEmbed(
        embedUrl: String, referer: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val u = embedUrl.trim()
        return when {
            u.contains("dailymotion.com") || u.contains("dai.ly") ->
                extractSdDailymotion(u, referer, serverName, callback)
            u.contains("ok.ru") || u.contains("odnoklassniki") ->
                extractSdOkRu(u, referer, serverName, callback)
            u.contains("rumble.com") ->
                extractSdRumble(u, referer, serverName, callback)
            u.contains("voe.") || u.contains("voeunblk") || u.contains("audaciousdefaulthouse") ->
                extractSdVoe(u, referer, serverName, callback)
            else -> {
                try { loadExtractor(u, referer, subtitleCallback, callback) } catch (_: Exception) {}
                extractSdGeneric(u, referer, serverName, callback)
            }
        }
    }

    /** Respaldo: embeds de video sueltos dentro del HTML de una pagina. */
    private suspend fun extractSdEmbedsFromHtml(
        html: String, referer: String, callback: (ExtractorLink) -> Unit
    ): Int {
        var found = 0
        val embeds = Regex("(https?://[^\"'\\s<>]*(?:dailymotion\\.com/(?:embed/)?video|dailymotion\\.com/player(?:/[a-z0-9]+)?\\.html\\?video=|ok\\.ru/videoembed|rumble\\.com/embed|voe\\.[a-z]+/e/)[^\"'\\s<>]*)")
            .findAll(html).map { it.value.replace("\\/", "/") }.distinct().toList()
        for (embed in embeds) {
            val label = when {
                embed.contains("dailymotion") -> "Dailymotion"
                embed.contains("ok.ru") -> "Ok"
                embed.contains("rumble") -> "Rumble"
                else -> "Voe"
            }
            try {
                if (processSdEmbed(embed, referer, label, { }, callback)) found++
            } catch (_: Exception) {}
        }
        return found
    }

    // ========== Extractores ==========

    /**
     * Dailymotion: pre-warm de cookies + API de metadata.
     * Visitar el embed primero planta la cookie v1st; sin ella la URL m3u8 de
     * cdndirector devuelve 403 en reproduccion.
     */
    private suspend fun extractSdDailymotion(
        embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // El endpoint get-server puede devolver geo.dailymotion.com/player.html?video=ID
        // o player/x19frc.html?video=ID; el clasico es /embed/video/ID (y dai.ly/video/ID)
        val videoId = Regex("(?:dailymotion\\.com|dai\\.ly)/(?:embed/)?(?:video/|player(?:/[a-z0-9]+)?\\.html\\?video=)([a-zA-Z0-9]+)")
            .find(embedUrl)?.groupValues?.get(1) ?: return false
        val canonical = "https://www.dailymotion.com/embed/video/$videoId"
        try { app.get(canonical, referer = referer, headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 15L) } catch (_: Exception) {}
        try {
            val json = app.get(
                "https://www.dailymotion.com/player/metadata/video/$videoId",
                referer = canonical,
                headers = mapOf("User-Agent" to SD_USER_AGENT, "Accept" to "application/json"),
                timeout = 15L
            ).text.replace("\\/", "/")
            // qualities.auto[*].url (m3u8 adaptable)
            Regex("\"qualities\"\\s*:\\s*\\{\\s*\"auto\"\\s*:\\s*\\[\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\"")
                .find(json)?.let { m ->
                    try { generateM3u8(serverName, m.groupValues[1], canonical).forEach(callback); return true } catch (_: Exception) {}
                }
            // Resto de m3u8 (saltar publicidad y placeholders de plantillas)
            for (m in Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)").findAll(json)) {
                val u = m.value
                if (u.contains("dmxleo.dailymotion.com")) continue
                if (u.contains("[APIFRAMEWORKS]") || u.contains("[VASTVERSIONS]")) continue
                try { generateM3u8(serverName, u, canonical).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("(https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*)").findAll(json)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) {
                    this.referer = canonical; this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Ok.Ru (v24.2, mismo approach que TioDonghua): data-options con escapes
     * \uXXXX/&quot;/\/; manifiesto bajo hlsManifestUrl u ondemandHls (embeds
     * nuevos) VALIDADO como #EXTM3U antes de generateM3u8, con fallback a las
     * urls progresivas de videos[].
     */
    private suspend fun extractSdOkRu(embedUrl: String, referer: String, name: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
            val html = app.get(url, referer = referer,
                headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L).text
            val decodeUnicode = { s: String ->
                Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { m ->
                    m.groupValues[1].toInt(16).toChar().toString()
                }
            }
            val options = Regex("data-options=\"([^\"]+)\"").find(html)
                ?.groupValues?.get(1)
                ?.let { it.replace("&quot;", "\"").replace("\\/", "/").let(decodeUnicode) }
                ?: return false
            var found = false
            // hlsManifestUrl | ondemandHls: el primer candidato que valide gana
            val hlsUrl = Regex("\"(?:[a-zA-Z]*ManifestUrl|ondemandHls)\":\"([^\"]+)\"")
                .findAll(options).map { it.groupValues[1] }.firstOrNull { candidate ->
                    try {
                        app.get(candidate, referer = url,
                            headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L)
                            .text.trimStart().startsWith("#EXTM3U")
                    } catch (_: Exception) { false }
                }
            if (hlsUrl != null) {
                try {
                    generateM3u8(name, hlsUrl, url).forEach(callback)
                    found = true
                } catch (_: Exception) {}
            }
            // Fallback: videos[] progresivos por calidad
            Regex("\"name\":\"(mobile|lowest|low|sd|hd|full|super)\",\"url\":\"([^\"]+)\"")
                .findAll(options).forEach { m ->
                    val progressive = decodeUnicode(m.groupValues[2])
                    if (progressive.startsWith("http")) {
                        val full = if (progressive.startsWith("//")) "https:$progressive" else progressive
                        callback(newExtractorLink(source = name, name = name, url = full) {
                            this.referer = url
                            this.quality = when (m.groupValues[1]) {
                                "full", "super" -> Qualities.P1080.value
                                "hd" -> Qualities.P720.value
                                "sd" -> Qualities.P480.value
                                else -> Qualities.P360.value
                            }
                        })
                        found = true
                    }
                }
            found
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Rumble (v24.2): el embed JS trae un mapa de calidades
     * "360":{"url":"...mp4","meta":{...,"h":360}}; emitir cada una.
     *
     * v24.13 (port del fix de TioDonghua v24.12): el embed estandar
     * (rumble.com/embed/VID) puede NO traer el video en el HTML; la API
     * publica embedJS exige el parametro embed= o responde 403. Estrategia:
     * 1) API embedJS con MP4 directos de hugh.cdn.rumble.cloud; 2) HTML del
     * embed como fallback. Las subidas nuevas sirven rendiciones ".tar" (TAR
     * de segmentos TS, NO reproducibles como MP4 directo): no se emiten; el
     * HTML de esas trae hls-vod valido y el fallback HLS las cubre.
     */
    private suspend fun extractSdRumble(embedUrl: String, referer: String, name: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            // 1) API embedJS: embed= es OBLIGATORIO (verificado: sin el
            // parametro la API responde 403 Forbidden).
            val vid = Regex("rumble\\.com/(?:embed/)?(v[a-zA-Z0-9]+)").find(embedUrl)?.groupValues?.get(1)
            if (vid != null) {
                val api = "https://rumble.com/embedJS/u3/?request=video&v=$vid" +
                    "&embed=" + java.net.URLEncoder.encode(embedUrl, "UTF-8")
                val uaMap = Regex("\"(\\d{3,4})\":\\[\"(https://[a-z0-9.]*rumble\\.cloud/video/[A-Za-z0-9/._-]+\\.mp4)\"")
                suspend fun emit(json: String): Int {
                    val fixed = json.replace("\\/", "/")
                    var n = 0
                    uaMap.findAll(fixed).forEach { m ->
                        val h = m.groupValues[1].toIntOrNull()
                        callback(newExtractorLink(source = name, name = name, url = m.groupValues[2]) {
                            this.referer = "https://rumble.com/"
                            this.quality = when {
                                h == null -> Qualities.Unknown.value
                                h >= 1080 -> Qualities.P1080.value
                                h >= 720 -> Qualities.P720.value
                                h >= 480 -> Qualities.P480.value
                                else -> Qualities.P360.value
                            }
                        })
                        n++
                    }
                    return n
                }
                var json = try {
                    app.get(api, referer = "https://rumble.com/",
                        headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L).text
                } catch (_: Exception) { "" }
                var count = if (json.isNotBlank()) emit(json) else 0
                if (count == 0) {
                    // WAF de Cloudflare: la API responde 403 sin cookie __cf_bm.
                    // Visitar el embed primero (la sesion conserva la cookie) y reintentar.
                    try {
                        app.get(embedUrl, referer = referer,
                            headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L)
                    } catch (_: Exception) {}
                    json = try {
                        app.get(api, referer = embedUrl,
                            headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L).text
                    } catch (_: Exception) { "" }
                    if (json.isNotBlank()) count = emit(json)
                }
                if (count > 0) return true
            }
            // 2) Fallback: HTML del embed (hls-vod de las subidas nuevas .tar,
            // mapa de calidades y primer mp4 de videos antiguos).
            val html = app.get(embedUrl, referer = referer,
                headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L).text
            val fixed = html.replace("\\/", "/")
            // v24.4: los embeds nuevos traen "hls":{"url":"https://rumble.com/
            // hls-vod/..."} en el bloque m.f["VID"] (el mapa "360":{...} ya no
            // aparece; el patron viejo solo matcheaba la timeline de 180p).
            // HLS VOD valido con variantes 1080/720/480/360 (verificado en vivo).
            Regex("\"hls\":[{]\"url\":\"(https://rumble[.]com/hls-vod/[^\"]+)\")")
                .find(fixed)?.groupValues?.get(1)?.let { hlsUrl ->
                    try {
                        generateM3u8(name, hlsUrl, embedUrl).forEach(callback)
                        return true
                    } catch (_: Exception) {}
                }
            val qualities = Regex("\"(\\d{3,4})\":\\{\"url\":\"(https://[a-z0-9.]*rumble\\.cloud/video/[A-Za-z0-9/._-]+\\.mp4)\"")
                .findAll(fixed).toList()
            if (qualities.isNotEmpty()) {
                qualities.distinctBy { it.groupValues[2] }.forEach { m ->
                    val h = m.groupValues[1].toIntOrNull()
                    callback(newExtractorLink(source = name, name = name, url = m.groupValues[2]) {
                        this.referer = embedUrl
                        this.quality = when {
                            h == null -> Qualities.Unknown.value
                            h >= 1080 -> Qualities.P1080.value
                            h >= 720 -> Qualities.P720.value
                            h >= 480 -> Qualities.P480.value
                            else -> Qualities.P360.value
                        }
                    })
                }
                return true
            }
            // Fallback: primer mp4 directo
            val url = Regex("https://[a-z0-9.]*rumble\\.cloud/video/[A-Za-z0-9/._-]+\\.mp4")
                .find(fixed)?.value ?: return false
            callback(newExtractorLink(source = name, name = name, url = url) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Voe.sx: m3u8/mp4 en el HTML del player. */
    private suspend fun extractSdVoe(videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            var html = app.get(videoUrl, referer = referer,
                headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L).text
            // v24.4 FIX: voe.sx ya NO sirve el player: devuelve una pagina de 769
            // bytes que redirige por JS a otro dominio (verificado en vivo:
            // window.location.href='https://jeremyparticipantanything.com/e/ID').
            // app.get no ejecuta JS: seguir el redirect manualmente.
            if (html.length < 1200 && html.contains("window.location.href")) {
                Regex("""window.location.href\s*=\s*'(https://[^']+)'""").find(html)
                    ?.groupValues?.get(1)?.let { dest ->
                        try {
                            html = app.get(dest, referer = videoUrl,
                                headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 20L).text
                        } catch (_: Exception) {}
                    }
            }
            for (m in Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\\s\"'<>]*)").findAll(html)) {
                try { generateM3u8(serverName, m.value, videoUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("(https?://[^\"'\\s<>]+\\.mp4[^\\s\"'<>]*)").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) {
                    this.referer = videoUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Último recurso: buscar m3u8/mp4 en la página del embed. */
    /** Último recurso: buscar m3u8/mp4 en la página del embed. */
    private suspend fun extractSdGeneric(
        playerUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val text = app.get(playerUrl, referer = referer,
                headers = mapOf("User-Agent" to SD_USER_AGENT), timeout = 15L).text
            for (m in Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)").findAll(text)) {
                try { generateM3u8(serverName, m.value, playerUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("(https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*)").findAll(text)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) {
                    this.referer = playerUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
            // v24.7: filemoon/vid-guard ofuscan la url del video con packing JS
            // (eval(function(p,a,c,k,...)): desempaquetar y buscar de nuevo.
            try {
                val unpacked = getAndUnpack(text)
                for (m in Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)").findAll(unpacked)) {
                    try { generateM3u8(serverName, m.value, playerUrl).forEach(callback); return true } catch (_: Exception) {}
                }
                for (m in Regex("(https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*)").findAll(unpacked)) {
                    callback(newExtractorLink(source = serverName, name = serverName, url = m.value) {
                        this.referer = playerUrl; this.quality = Qualities.Unknown.value
                    })
                    return true
                }
            } catch (_: Exception) {}
            false
        } catch (_: Exception) {
            false
        }
    }
}
