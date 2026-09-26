package com.donghuatiodonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.collections.ArrayList

/** UA de navegador (necesario para varios hosts). */
const val TD_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

/**
 * v24: extractor de Ok.Ru (odnoklassniki). La página del embed trae el
 * hlsManifestUrl y las urls progresivas en el atributo data-options.
 */
private suspend fun extractTdOkRu(embedUrl: String, referer: String, name: String, callback: (ExtractorLink) -> Unit): Boolean {
    return try {
        val html = app.get(embedUrl, referer = referer,
            headers = mapOf("User-Agent" to TD_USER_AGENT), timeout = 20L).text
        val options = Regex("data-options=\"([^\"]+)\"").find(html)
            ?.groupValues?.get(1)
            ?.replace("&quot;", "\"")
            ?.replace("\\/", "/")
            ?: return false
        var found = false
        // hlsManifestUrl (mejor opción: adaptable)
        Regex("\"hlsManifestUrl\":\"([^\"]+)\"").find(options)?.let { m ->
            try { generateM3u8(name, m.groupValues[1], embedUrl).forEach(callback); found = true } catch (_: Exception) {}
        }
        // Fallback: videos[] con urls progresivas por calidad
        if (!found) {
            val videoPairs = Regex("\"name\":\"(mobile|lowest|low|sd|hd|full|super)\",\"url\":\"([^\"]+)\"")
                .findAll(options).toList()
            videoPairs.lastOrNull()?.let { m ->
                callback(newExtractorLink(source = name, name = name, url = m.groupValues[2]) {
                    this.referer = embedUrl
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
 * v24: extractor de Rumble. La página del embed incluye el embed JS con
 * los mp4 directos por calidad (hugh.cdn.rumble.cloud).
 */
private suspend fun extractTdRumble(embedUrl: String, referer: String, name: String, callback: (ExtractorLink) -> Unit): Boolean {
    return try {
        val html = app.get(embedUrl, referer = referer,
            headers = mapOf("User-Agent" to TD_USER_AGENT), timeout = 20L).text
        // Las URLs vienen con \/ escapado dentro del JSON del embed
        val fixed = html.replace("\\/", "/")
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

/**
 * v24: extractor de StreamTape. El HTML del player trae el enlace directo
 * `//<host>.streamtape.<tld>/get_video?id=...&expires=...&ip=...&token=...`
 * (revelado solo tras el click; nos basta con extraerlo del HTML).
 */
private suspend fun extractTdStreamTape(embedUrl: String, referer: String, name: String, callback: (ExtractorLink) -> Unit): Boolean {
    return try {
        val html = app.get(embedUrl, referer = referer,
            headers = mapOf("User-Agent" to TD_USER_AGENT), timeout = 20L).text
        val direct = Regex("(?:https?:)?//[a-z0-9-]+\\.streamtape[a-z.]*/get_video\\?[^\"'\\s<>]+", RegexOption.IGNORE_CASE)
            .find(html)?.value ?: return false
        val fixedUrl = if (direct.startsWith("//")) "https:$direct" else direct
        callback(newExtractorLink(source = name, name = name, url = fixedUrl) {
            this.referer = embedUrl
            this.quality = Qualities.Unknown.value
        })
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * TioDonghua (https://tiodonghua.lat/) — WordPress con tema Dooplay.
 *
 * Estructura verificada en vivo (2026-09):
 * - Home: secciones "⚡TOP MAS VISTAS" (div#featured-titles),
 *   "🍿 ÚLTIMOS EPISODIOS" (article.item.episodes, span.serie),
 *   "DONGHUAS AGREGADOS" (div#dt-tvshows) y "🎬 PELICULAS RECIENTES".
 * - Catálogo: /donghua/ ("Recently added", 40 fichas/página, paginado /donghua/page/N/).
 * - Fichas: /donghua/slug/ y /peliculas/slug/ con episodios en ul.episodios
 *   (div.numerando = "temporada - episodio") y géneros en div.sgeneros.
 * - Episodios: /episodios/slug-episodio-N-sub-espanol/ con jugador Dooplay AJAX
 *   (li.dooplay_player_option data-type/data-post/data-nume ->
 *    POST /wp-admin/admin-ajax.php action=doo_player_ajax ->
 *    {"embed_url": "...", "type": "iframe"}).
 * - Servidores del episodio de prueba (The Demon Hunter ep1): DM (Dailymotion),
 *   TP (player.modagamers.com, challenge JS), TP2 (playercuyplay.cuyplay.com),
 *   VG (vgembed.com), SSB (sblona.com), SH (ahvsh.com), FL (filelions.to).
 *   Los servidores "is-vip-server" (Drive Vip / Rumble VIP) responden
 *   {"embed_url":"","type":false} sin cuenta VIP: se excluyen.
 */
class TioDonghuaProvider : MainAPI() {

    override var mainUrl = "https://tiodonghua.lat"
    override var name = "TioDonghua"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.OVA,
        TvType.AnimeMovie,
    )

    override val    mainPage = mainPageOf(
        "$mainUrl/" to "Últimos Episodios",
        "$mainUrl/##top" to "Top Más Vistas",
        "$mainUrl/donghua/" to "Últimos Agregados",
        "$mainUrl/##peliculas" to "Películas Recientes",
        // v23.1: no hay páginas /generos/ ni /animes/ (404 verificado), pero las
        // páginas de género de Dooplay sí existen: /genero/accion/ etc.
        // (30 fichas por página, paginadas /genero/X/page/N/).
        "$mainUrl/genero/accion/" to "Género: Acción",
        "$mainUrl/genero/artes-marciales/" to "Género: Artes Marciales",
        "$mainUrl/genero/aventura/" to "Género: Aventura",
        "$mainUrl/genero/fantasia/" to "Género: Fantasía",
    )

    private val pageHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "es-ES,es;q=0.8,en;q=0.5",
    )

    private fun resolveUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }

    /** Quita sufijo "Sub Español" y emojis iniciales de los títulos. */
    private fun tdCleanTitle(t: String): String = t
        .replace(Regex("\\s*Sub\\s*Espa[nñ]ol\\s*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^[^\\p{L}\\p{N}]+"), "")
        .trim()

    /** v23.0 normaliza un título para comparar bases (minúsculas, sin acentos). */
    private fun tdNormalize(t: String): String = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    // ========== getMainPage ==========
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data == "$mainUrl/"
        val isTop = request.data == "$mainUrl/##top"
        val isCatalog = request.data == "$mainUrl/donghua/"
        // v24: /peliculas/ es un archivo propio (sin header "PELICULAS"), así que
        // se pide directo en vez de la sección del home (que carga por AJAX).
        val isMovies = request.data == "$mainUrl/##peliculas"
        val isGenre = request.data.contains("$mainUrl/genero/")

        // "Top Más Vistas" no tiene paginación real
        if (isTop && page > 1) {
            return newHomePageResponse(list = HomePageList(request.name, emptyList()), hasNext = false)
        }

        val url = when {
            isHome && page > 1 -> "$mainUrl/episodios/page/$page/" // archivo /episodios/ (30/pág, mismo markup)
            isCatalog && page > 1 -> "$mainUrl/donghua/page/$page/"
            isMovies && page > 1 -> "$mainUrl/peliculas/page/$page/" // 404 si no existe: hasNext=false
            isGenre && page > 1 -> "${request.data.trimEnd('/')}/page/$page/"
            else -> request.data.removePrefix("$mainUrl/##").let { if (it == request.data) request.data else "$mainUrl/${it.removePrefix("$mainUrl/")}" }
        }
        val doc = app.get(
            if (isMovies) "$mainUrl/peliculas/" else url,
            headers = pageHeaders, timeout = 30L
        ).document

        val items: List<SearchResponse> = when {
            // Últimos Episodios: cards article.item.episodes con span.serie
            isHome -> {
                val articles = if (page == 1) {
                    sectionArticles(doc, "EPISODIOS")
                } else {
                    doc.select("article.item.episodes")
                }
                articles.mapNotNull { parseTdEpisodeCard(it) }
            }
            // Top Más Vistas: div#featured-titles article.item (fichas)
            isTop -> doc.select("div#featured-titles article.item").mapNotNull { parseTdCard(it) }
            // Últimos Agregados: catálogo /donghua/ ("Recently added")
            isCatalog -> doc.select("div#archive-content article.item").mapNotNull { parseTdCard(it) }
            // Géneros: /genero/X/ usa div.items.full con los mismos cards
            isGenre -> doc.select("div.items article.item").mapNotNull { parseTdCard(it) }
            // Películas: catálogo /peliculas/ con div#archive-content
            isMovies -> doc.select("div#archive-content article.item").mapNotNull { parseTdCard(it) }
            // Resto: sección del home por h2
            else -> sectionArticles(doc, "PELICULAS").mapNotNull { parseTdCard(it) }
        }

        // El home y /episodios/ tienen miles de páginas (16,594 episodios):
        // permitir paginación infinita; Top no tiene paginación real.
        val hasNext = when {
            isHome -> true
            isTop -> false
            isMovies -> page == 1 && doc.select("div#archive-content article.item").isNotEmpty() &&
                doc.html().contains("peliculas/page/2/")
            else -> doc.select("a[href*=\"page/${page + 1}/\"]").isNotEmpty()
        }
        // v24: "Últimos Episodios" como fila de imágenes horizontales (las cards
        // ya son panorámicas 300x170: carrusel visual tipo "Captura home
        // donghualife.png"; CloudStream no expone hero/spotlight a los plugins,
        // esta es la fila más grande que permite la API).
        return newHomePageResponse(
            list = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = isHome),
            hasNext = hasNext
        )
    }

    /** Articles de una sección del home identificada por el texto del h2 del header. */
    private fun sectionArticles(doc: org.jsoup.nodes.Document, titleContains: String): List<org.jsoup.nodes.Element> {
        val headerEl = doc.select("header h2").firstOrNull {
            it.text().uppercase().contains(titleContains)
        } ?: return emptyList()
        var node: org.jsoup.nodes.Element? = headerEl.parent()?.nextElementSibling()
        while (node != null) {
            val articles = node.select("article")
            if (articles.isNotEmpty()) return articles
            node = node.nextElementSibling()
        }
        return emptyList()
    }

    /**
     * Card de ficha (donghua/película) del home o catálogo.
     * v24: soporta cards de /peliculas/ (div.image con h3 sin <a>) y muestra
     * el PUNTAJE (div.rating, ej. "9.8") en la etiqueta de la card en vez de
     * "Subtitulado": se setea score y no se marca dubStatus.
     */
    private fun parseTdCard(article: org.jsoup.nodes.Element): SearchResponse? {
        val href = article.selectFirst("div.poster a[href], div.image a[href], a[href]")?.attr("href") ?: return null
        if (!href.contains("/donghua/") && !href.contains("/peliculas/")) return null
        val title = article.select("h3 a").firstOrNull { it.text().isNotBlank() }?.text()?.trim()
            ?: article.selectFirst("h3.title")?.text()?.trim()
            ?: article.selectFirst("h3")?.text()?.trim()
            ?: article.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val poster = article.selectFirst("img")?.attr("src") ?: ""
        val rating = article.selectFirst("div.rating")?.text()?.trim()
        return newAnimeSearchResponse(tdCleanTitle(title), href) {
            this.posterUrl = poster
            if (!rating.isNullOrBlank()) {
                try { Score.from10(rating)?.let { this.score = it } } catch (_: Exception) {}
            }
        }
    }

    /**
     * Card de episodio ("Últimos Episodios"): el nombre de la serie está en
     * span.serie; el h3 trae "Episodio N" y el span "S1 EN / fecha".
     * v24: mostrar el número de episodio en la etiqueta de la card
     * ("Subtitulado • N") y el score de la serie si viene en el RSC.
     */
    private fun parseTdEpisodeCard(article: org.jsoup.nodes.Element): SearchResponse? {
        val href = article.selectFirst("a[href*=\"/episodios/\"]")?.attr("href") ?: return null
        val serieName = article.selectFirst("span.serie")?.text()?.trim()
        val title = if (!serieName.isNullOrBlank()) serieName
        else tdCleanTitle(article.selectFirst("img")?.attr("alt") ?: "")
        if (title.isBlank()) return null
        val poster = article.selectFirst("img")?.attr("src") ?: ""
        val epText = article.selectFirst("h3")?.text()?.trim() ?: ""
        val spanText = article.select("div.data span").firstOrNull()?.text()?.trim() ?: ""
        val epNum = Regex("(?:Episodio|Capítulo)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(epText)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("S\\d+\\s*E\\s*(\\d+)", RegexOption.IGNORE_CASE).find(spanText)
                ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("-episodio-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = poster
            addDubStatus(DubStatus.Subbed, epNum)
        }
    }

    // ========== search ==========
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get(
                "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}",
                headers = pageHeaders, timeout = 30L
            ).document
            doc.select("div.result-item").mapNotNull { item ->
                val a = item.selectFirst("div.title a") ?: item.selectFirst("div.image a[href]") ?: return@mapNotNull null
                val href = a.attr("href")
                if (href.isBlank()) return@mapNotNull null
                val title = a.text().trim().ifBlank { item.selectFirst("img")?.attr("alt") ?: return@mapNotNull null }
                val poster = item.selectFirst("img")?.attr("src") ?: ""
                newAnimeSearchResponse(tdCleanTitle(title), href) {
                    this.posterUrl = poster
                    addDubStatus(DubStatus.Subbed)
                }
            }.distinctBy { it.url }.take(30)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ========== load ==========
    override suspend fun load(url: String): LoadResponse {
        // Episodio suelto -> convertir a ficha de serie
        if (url.contains("/episodios/")) {
            return loadFromEpisodeUrl(url)
        }

        val isMovie = url.contains("/peliculas/")
        val doc = app.get(url, headers = pageHeaders, timeout = 30L).document

        val title = tdCleanTitle(doc.selectFirst("h1")?.text() ?: "")
            .ifBlank {
                tdCleanTitle(
                    doc.selectFirst("meta[property=\"og:title\"]")?.attr("content") ?: ""
                )
            }
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?: ""

        // v24: pestaña "info" de Dooplay (div#info) — sinopsis completa +
        // custom_fields (Original title, TMDb Rating, First air date, ...)
        val infoDiv = doc.selectFirst("div#info")
        val description = infoDiv?.selectFirst("div.wp-content")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=\"og:description\"]")?.attr("content") ?: ""

        // custom_fields: pares <b class="variante">clave</b><span class="valor">valor</span>
        val infoFields = HashMap<String, String>()
        infoDiv?.select("div.custom_fields")?.forEach { cf ->
            val k = cf.selectFirst("b.variante")?.text()?.trim() ?: return@forEach
            val v = cf.selectFirst("span.valor")?.text()?.trim() ?: return@forEach
            if (k.isNotBlank() && v.isNotBlank()) infoFields[k] = v
        }
        val originalTitle = infoFields["Original title"]?.trim()

        // Año desde "First air date" ("Jan. 01, 2024")
        val year = Regex("(19|20)\\d{2}").find(
            infoFields["First air date"] ?: ""
        )?.value?.toIntOrNull()

        // Rating del sitio (span.dt_rating_vgs, escala 10)
        val ratingText = doc.selectFirst("span.dt_rating_vgs[itemprop=ratingValue]")?.text()?.trim()
        val rating = if (!ratingText.isNullOrBlank()) {
            try { Score.from10(ratingText) } catch (_: Exception) { null }
        } else null

        val genres = doc.select("div.sgeneros a").map { it.text().trim() }.filter { it.isNotBlank() }
        // Estado de emisión según géneros del sitio (Completado / En emisión)
        val showStatus = when {
            genres.any { it.equals("Completado", ignoreCase = true) } -> ShowStatus.Completed
            genres.any {
                it.contains("emision", ignoreCase = true) ||
                    it.contains("emisión", ignoreCase = true) ||
                    it.contains("curso", ignoreCase = true)
            } -> ShowStatus.Ongoing
            else -> null
        }

        // Recomendaciones: primero otras temporadas, luego similares aleatorios (tope 16)
        val recommendations = fetchTdRecommendations(url, title)

        // Episodios: ul.episodios con div.numerando "temporada - episodio"
        // v24: el numerando es la fuente de verdad (algunos slugs traen sufijos
        // raros tipo "-episodio-10-1sub-" o URLs de la temporada anterior).
        val episodes = ArrayList<Episode>()
        doc.select("ul.episodios li").forEach { li ->
            val a = li.selectFirst("div.episodiotitle a[href]") ?: return@forEach
            val href = a.attr("href")
            if (href.isBlank()) return@forEach
            val num = li.selectFirst("div.numerando")?.text()?.trim() ?: ""
            val parts = num.split("-")
            val season = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val epNum = parts.getOrNull(1)?.trim()?.toIntOrNull()
                ?: Regex("-episodio-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
            episodes.add(newEpisode(resolveUrl(href)) {
                this.name = a.text().trim().ifBlank { null }
                this.episode = epNum
                season?.let { this.season = it }
            })
        }

        // Película con lista de episodios (película por episodio) -> serie
        if (episodes.isEmpty()) {
            if (isMovie) {
                // MovieLoadResponse no tiene synonyms/showStatus: se añaden al plot
                val extraInfo = buildString {
                    append(description)
                    originalTitle?.let { append("\n\nTítulo original: ").append(it) }
                    if (showStatus == ShowStatus.Completed) append("\n\nEstado: Completado")
                    if (showStatus == ShowStatus.Ongoing) append("\n\nEstado: En emisión")
                }.trim()
                return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    posterUrl = poster; this.plot = extraInfo; this.tags = genres
                    this.year = year; this.score = rating
                    if (recommendations.isNotEmpty()) this.recommendations = recommendations
                }
            }
            // Ficha de serie sin ul.episodios: sintetizar con links a /episodios/
            doc.select("a[href*=\"/episodios/\"]").forEach { a ->
                val href = a.attr("href")
                val epNum = Regex("-episodio-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                if (href.isNotBlank() && episodes.none { it.data == href }) {
                    episodes.add(newEpisode(resolveUrl(href)) { this.episode = epNum })
                }
            }
            if (episodes.isEmpty()) {
                // Último recurso: la ficha misma tiene jugador (especiales)
                return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    posterUrl = poster; this.plot = description; this.tags = genres
                    this.year = year; this.score = rating
                    if (recommendations.isNotEmpty()) this.recommendations = recommendations
                }
            }
            episodes.sortBy { it.episode }
        } else {
            episodes.sortBy { (it.season ?: 1) * 100000 + (it.episode ?: 0) }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            this.plot = description; this.tags = genres
            this.year = year; this.score = rating
            showStatus?.let { this.showStatus = it }
            originalTitle?.let { this.synonyms = listOf(it) }
            if (recommendations.isNotEmpty()) this.recommendations = recommendations
        }
    }

    /**
     * Carga desde una URL de episodio: usa el enlace "ALL" del paginador
     * (div.pag_episodes) que apunta a la ficha con el título limpio.
     */
    private suspend fun loadFromEpisodeUrl(epUrl: String): LoadResponse {
        val doc = try {
            app.get(epUrl, headers = pageHeaders, timeout = 30L).document
        } catch (_: Exception) {
            return synthesizedEpisodeResponse(epUrl)
        }
        val seriesHref = doc.select("div.pag_episodes a[href]").firstOrNull {
            val h = it.attr("href")
            (h.contains("/donghua/") || h.contains("/peliculas/")) && !h.contains("/donghua/page/")
        }?.attr("href")
        if (!seriesHref.isNullOrBlank()) {
            return load(seriesHref)
        }
        return synthesizedEpisodeResponse(epUrl)
    }

    /** Fallback: episodio sin ficha localizable -> serie sintetizada de un episodio. */
    private suspend fun synthesizedEpisodeResponse(epUrl: String): LoadResponse {
        val epNum = Regex("-episodio-(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val slugBase = epUrl.substringAfter("/episodios/").trimEnd('/').substringBefore("-episodio-")
        val guessTitle = slugBase.replace("-", " ").trim().replaceFirstChar { it.uppercase() }
        var poster = ""
        var plot = ""
        try {
            val doc = app.get(epUrl, headers = pageHeaders, timeout = 30L).document
            poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?: ""
            plot = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content") ?: ""
        } catch (_: Exception) {}
        return newAnimeLoadResponse(guessTitle, epUrl, TvType.Anime) {
            posterUrl = poster; plot = plot
            addEpisodes(DubStatus.Subbed, listOf(newEpisode(epUrl) { this.episode = epNum }))
        }
    }

    /**
     * v23.0 recomendaciones (NOTA del usuario):
     * 1) Otras temporadas del mismo nombre (misma base de slug/título) vía el
     *    buscador del sitio;
     * 2) Similares aleatorios desde una página al azar de /donghua/ (catálogo,
     *    40 fichas por página). Tope 16.
     */
    private suspend fun fetchTdRecommendations(
        seriesUrl: String,
        seriesTitle: String
    ): List<SearchResponse> {
        return try {
            val all = ArrayList<SearchResponse>()
            val seen = mutableSetOf(seriesUrl)

            val selfSlug = seriesUrl.trimEnd('/').substringAfterLast('/')
            val baseSlug = Regex("-\\d+$").replace(selfSlug, "")
            val norm = tdNormalize(seriesTitle)
            val baseNorm = Regex("\\s+\\d+$").replace(norm, "").trim()

            // El título puede traer sufijo de temporada ("... Temporada 3")
            val titleNoSeason = Regex("\\s*(temporada|season)\\s*\\d+$", RegexOption.IGNORE_CASE)
                .replace(seriesTitle, "").trim()
                .ifBlank { Regex("\\s+\\d+$").replace(seriesTitle, "").trim() }
            val normNoSeason = if (titleNoSeason != seriesTitle) tdNormalize(titleNoSeason) else ""

            // ===== 1) Otras temporadas vía buscador =====
            val q = normNoSeason.ifBlank { baseNorm.ifBlank { baseSlug.replace("-", " ") } }
            if (q.isNotBlank()) {
                try {
                    val doc = app.get(
                        "$mainUrl/?s=${java.net.URLEncoder.encode(q, "UTF-8")}",
                        headers = pageHeaders, timeout = 30L
                    ).document
                    val seasons = ArrayList<Triple<String, String, String>>() // url, title, poster
                    doc.select("div.result-item").forEach { item ->
                        val a = item.selectFirst("div.title a") ?: item.selectFirst("a[href]") ?: return@forEach
                        val href = a.attr("href")
                        if (href.isBlank() || href in seen) return@forEach
                        if (!href.contains("/donghua/") && !href.contains("/peliculas/")) return@forEach
                        val slug = href.trimEnd('/').substringAfterLast('/')
                        val t = tdCleanTitle(a.text().trim().ifBlank { item.selectFirst("img")?.attr("alt") ?: "" })
                        val tNorm = tdNormalize(t)
                        val tBase = Regex("\\s+\\d+$").replace(tNorm, "").trim()
                        val slugBase = Regex("-\\d+$").replace(slug, "")
                        val sameBase = (baseSlug.isNotBlank() && slugBase.startsWith(baseSlug)) ||
                            (baseNorm.isNotBlank() && tNorm.startsWith(baseNorm)) ||
                            (normNoSeason.isNotBlank() && tNorm.startsWith(normNoSeason)) ||
                            (baseNorm.isNotBlank() && baseNorm.startsWith(tBase) && tBase.isNotBlank())
                        if (!sameBase) return@forEach
                        seen.add(href)
                        seasons.add(Triple(href, t, item.selectFirst("img")?.attr("src") ?: ""))
                    }
                    seasons.sortBy {
                        Regex("(\\d+)$").find(it.first.trimEnd('/').substringAfterLast('/'))
                            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }
                    seasons.forEach { (u, t, p) ->
                        all.add(newAnimeSearchResponse(t, u) { this.posterUrl = p })
                    }
                } catch (_: Exception) {}
            }

            // ===== 2) Similares aleatorios del catálogo =====
            if (all.size < 16) try {
                val firstDoc = app.get("$mainUrl/donghua/", headers = pageHeaders, timeout = 30L).document
                val lastPage = Regex("/donghua/page/(\\d+)/").findAll(firstDoc.html())
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1
                val page = if (lastPage > 1) (1..lastPage).random() else 1
                val poolDoc = if (page == 1) firstDoc else
                    app.get("$mainUrl/donghua/page/$page/", headers = pageHeaders, timeout = 30L).document
                val pool = ArrayList<SearchResponse>()
                poolDoc.select("div#archive-content article.item").forEach { article ->
                    if (pool.size >= 40) return@forEach
                    val href = article.selectFirst("a[href]")?.attr("href") ?: return@forEach
                    if (href in seen || !href.contains("/donghua/") && !href.contains("/peliculas/")) return@forEach
                    val t = article.select("h3 a").firstOrNull { it.text().isNotBlank() }?.text()?.trim()
                        ?: article.selectFirst("img")?.attr("alt") ?: return@forEach
                    seen.add(href)
                    pool.add(newAnimeSearchResponse(tdCleanTitle(t), href) {
                        this.posterUrl = article.selectFirst("img")?.attr("src") ?: ""
                    })
                }
                pool.shuffle()
                all.addAll(pool)
            } catch (_: Exception) {}
            all.take(16)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ========== loadLinks ==========
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = pageHeaders, timeout = 30L).document
        var foundLinks = false
        var links = 0
        val cb: (ExtractorLink) -> Unit = { links++; callback(it) }

        // Opciones de servidor Dooplay (AJAX), excluyendo VIP
        val options = doc.select("li.dooplay_player_option").filter { li ->
            !li.hasClass("is-vip-server") && li.attr("data-post").isNotBlank() && li.attr("data-nume").isNotBlank()
        }
        if (options.isNotEmpty()) {
            val results = coroutineScope {
                options.map { li ->
                    val post = li.attr("data-post")
                    val nume = li.attr("data-nume")
                    val type = li.attr("data-type").ifBlank { "tv" }
                    val label = li.selectFirst("span.title")?.text()?.trim()?.ifBlank { null }
                        ?: "Server $nume"
                    async {
                        try { fetchTdPlayerEmbed(post, nume, type) to label } catch (_: Exception) { "" to label }
                    }
                }.map { it.await() }
            }
            results.forEach { (rawEmbed, label) ->
                val embedUrl = tdNormalizeEmbed(rawEmbed)
                if (embedUrl != null) {
                    processTdEmbed(embedUrl, data, label, subtitleCallback, cb)
                }
            }
        }

        // Fallback 1: iframes directos
        if (links == 0) {
            doc.select("iframe").forEach { iframe ->
                val src = listOf("src", "data-src").map { iframe.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() } ?: return@forEach
                if (src.startsWith("http")) {
                    processTdEmbed(src, data, serverLabelFromUrl(src), subtitleCallback, cb)
                }
            }
        }

        // Fallback 2: m3u8/mp4 en el HTML
        if (links == 0) {
            val html = doc.html()
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").findAll(html)) {
                try { generateM3u8("Server", m.value, data).forEach(cb); foundLinks = true } catch (_: Exception) {}
            }
            if (!foundLinks) {
                for (m in Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""").findAll(html)) {
                    callback(newExtractorLink(source = "Server", name = "Server", url = m.value) {
                        this.referer = data; this.quality = Qualities.Unknown.value
                    })
                    foundLinks = true
                }
            }
        }

        return links > 0 || foundLinks
    }

    /**
     * v24: normaliza el embed_url del AJAX. El tipo "dtshcode" devuelve HTML
     * (un <iframe ...> o <div><iframe ...></div>); extraer el src. Si es una
     * URL directa ("iframe") se devuelve tal cual. Devuelve null si no hay URL.
     */
    private fun tdNormalizeEmbed(raw: String): String? {
        // des-escapar JSON (\" -> " y \/ -> /) antes de analizar
        val t = raw.replace("\\\"", "\"").replace("\\/", "/").trim()
        if (t.startsWith("http")) {
            val end = t.indexOfFirst { it == '"' || it == '\\' }
            return (if (end == -1) t else t.substring(0, end)).trim().ifBlank { null }
        }
        if (t.contains("<iframe", ignoreCase = true) || t.contains("<div", ignoreCase = true)) {
            val src = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(t)
                ?.groupValues?.get(1)
            if (src != null) return src.trim()
        }
        return null
    }

    /** Llama al endpoint AJAX de Dooplay y devuelve el embed_url de la opción. */
    private suspend fun fetchTdPlayerEmbed(post: String, nume: String, type: String): String {
        return try {
            val resp = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to type,
                ),
                headers = pageHeaders,
                timeout = 20L
            ).text
            // {"embed_url":"https:\/\/...","type":"iframe"}
            Regex("\"embed_url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(resp)
                ?.groupValues?.get(1) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** Nombre de servidor legible a partir del host. */
    private fun serverLabelFromUrl(url: String): String {
        return try {
            val host = java.net.URI(url).host ?: return "Server"
            host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            "Server"
        }
    }

    /**
     * Enruta cada embed al extractor apropiado. Cuenta ENLACES EMITIDOS, no
     * excepciones (los hosts muertos devuelven 200 con página de error).
     */
    private suspend fun processTdEmbed(
        embedUrl: String, referer: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        var links = 0
        val cb: (ExtractorLink) -> Unit = { links++; callback(it) }
        val u = embedUrl.trim()
        when {
            u.contains("dailymotion.com") ->
                extractTdDailymotion(u, referer, serverName, cb)
            // v24: Ok.Ru (servidor "OK" muy común en tiodonghua)
            u.contains("ok.ru") || u.contains("odnoklassniki") ->
                extractTdOkRu(u, referer, serverName, cb)
            // v24: Rumble (servidor "DM" en fichas nuevas)
            u.contains("rumble.com") ->
                extractTdRumble(u, referer, serverName, cb)
            // v24: StreamTape (servidor "ST")
            u.contains("streamtape") || u.contains("strcloud") ->
                extractTdStreamTape(u, referer, serverName, cb)
            // modagamers: 302 a publicidad que planta cookie sid, luego challenge con
            // JS redirect (verificado en vivo); seguir la cadena con reintentos.
            u.contains("modagamers") ->
                extractTdPackedFromChallenge(u, referer, serverName, cb)
            // Familia streamwish/filelions/vidguard: players con JS empaquetado
            u.contains("wish") || u.contains("sblona") || u.contains("ahvsh") ||
                u.contains("filelions") || u.contains("luluvdo") ||
                u.contains("vgembed") || u.contains("vgfplay") || u.contains("vidguard") ||
                u.contains("byse") || u.contains("asnwish") ->
                extractTdPacked(u, referer, serverName, cb)
            else -> {
                try { loadExtractor(u, referer, subtitleCallback, cb) } catch (_: Exception) {}
                if (links == 0) extractTdFromPage(u, referer, serverName, cb)
            }
        }
        return links > 0
    }

    /**
     * v23.1: GET con manejo del challenge tipo modagamers. Patrón observado en
     * vivo: la primera visita a una URL devuelve 302 hacia un click de publicidad
     * y planta la cookie sid; repitiendo la MISMA URL con esa cookie el servidor
     * responde 200 (o una página con window.location.replace hacia el siguiente
     * paso). HTTP 429 = rate limit: esperar y reintentar.
     */
    private suspend fun tdGetWithChallenge(startUrl: String, referer: String?, maxSteps: Int = 5): String? {
        var current = startUrl
        repeat(maxSteps) {
            val resp = try {
                app.get(
                    current, referer = referer,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    timeout = 20L, allowRedirects = false
                )
            } catch (_: Exception) {
                return null
            }
            when {
                resp.code in 300..399 -> {
                    // Redirect de publicidad: la cookie sid ya quedó en la sesión
                    // de NiceHttp; reintentar la MISMA URL.
                }
                resp.code == 429 -> kotlinx.coroutines.delay(2500)
                else -> {
                    val text = resp.text
                    val jsRedirect = Regex("window\\.location\\.replace\\('([^']+)'")
                        .find(text)?.groupValues?.get(1)
                    if (jsRedirect != null) {
                        current = jsRedirect
                    } else if (text.length > 1500) {
                        return text
                    }
                }
            }
        }
        return null
    }

    /** modagamers: resolver challenge y buscar m3u8/mp4 en el player final. */
    private suspend fun extractTdPackedFromChallenge(
        embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = tdGetWithChallenge(embedUrl, referer) ?: return false
        return extractTdPackedFromHtml(html, referer, serverName, callback)
    }

    /** Búsqueda de fuentes de video sobre un HTML de player ya resuelto. */
    private suspend fun extractTdPackedFromHtml(
        html: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val unpacked = try { getAndUnpack(html) } catch (_: Exception) { html }
        var found = false
        Regex("""["']?hls["']?\s*:\s*\{[^}]*?["']?(?:url|file)["']?\s*:\s*["']([^"']+)["']""")
            .find(unpacked)?.let { m ->
                try { generateM3u8(serverName, m.groupValues[1], referer).forEach(callback); found = true } catch (_: Exception) {}
            }
        if (!found) {
            Regex("""["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(unpacked).forEach { m ->
                try { generateM3u8(serverName, m.groupValues[1], referer).forEach(callback); found = true } catch (_: Exception) {}
            }
        }
        if (!found) {
            Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").findAll(unpacked).forEach { m ->
                try { generateM3u8(serverName, m.value, referer).forEach(callback); found = true } catch (_: Exception) {}
            }
        }
        if (!found) {
            Regex("""["']?file["']?\s*:\s*["']([^"']+\.mp4[^"']*)["']""").findAll(unpacked).forEach { m ->
                callback(newExtractorLink(source = serverName, name = serverName, url = m.groupValues[1]) {
                    this.referer = referer; this.quality = Qualities.Unknown.value
                })
                found = true
            }
        }
        return found
    }

    /**
     * Dailymotion: pre-warm de cookies + API de metadata
     * (mismo approach probado en SeriesDonghuaProvider).
     */
    private suspend fun extractTdDailymotion(
        embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = Regex("dailymotion\\.com/(?:embed/)?video/([a-zA-Z0-9]+)")
            .find(embedUrl)?.groupValues?.get(1) ?: return false
        try { app.get(embedUrl, referer = referer, timeout = 15L) } catch (_: Exception) {}
        try {
            val json = app.get(
                "https://www.dailymotion.com/player/metadata/video/$videoId",
                referer = embedUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                timeout = 15L
            ).text.replace("\\/", "/")
            Regex("\"qualities\"\\s*:\\s*\\{\\s*\"auto\"\\s*:\\s*\\[\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\"")
                .find(json)?.let { m ->
                    try { generateM3u8(serverName, m.groupValues[1], embedUrl).forEach(callback); return true } catch (_: Exception) {}
                }
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").findAll(json)) {
                val u = m.value
                if (u.contains("dmxleo.dailymotion.com")) continue
                if (u.contains("[APIFRAMEWORKS]") || u.contains("[VASTVERSIONS]")) continue
                try { generateM3u8(serverName, u, embedUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""").findAll(json)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) {
                    this.referer = embedUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Extractor para players con JS ofuscado tipo packer
     * (streamwish/sblona/ahvsh/filelions/vgembed): getAndUnpack y buscar
     * hls/file/m3u8/mp4.
     */
    private suspend fun extractTdPacked(
        embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(embedUrl, referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT), timeout = 20L).text
            val unpacked = try { getAndUnpack(html) } catch (_: Exception) { html }
            var found = false
            // 1) "hls": {"url": "...m3u8..."}
            Regex("""["']?hls["']?\s*:\s*\{[^}]*?["']?(?:url|file)["']?\s*:\s*["']([^"']+)["']""")
                .find(unpacked)?.let { m ->
                    try { generateM3u8(serverName, m.groupValues[1], referer).forEach(callback); found = true } catch (_: Exception) {}
                }
            // 2) "file": "...m3u8..."
            if (!found) {
                Regex("""["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(unpacked).forEach { m ->
                    try { generateM3u8(serverName, m.groupValues[1], referer).forEach(callback); found = true } catch (_: Exception) {}
                }
            }
            // 3) m3u8 directos
            if (!found) {
                Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").findAll(unpacked).forEach { m ->
                    try { generateM3u8(serverName, m.value, referer).forEach(callback); found = true } catch (_: Exception) {}
                }
            }
            // 4) mp4
            if (!found) {
                Regex("""["']?file["']?\s*:\s*["']([^"']+\.mp4[^"']*)["']""").findAll(unpacked).forEach { m ->
                    callback(newExtractorLink(source = serverName, name = serverName, url = m.groupValues[1]) {
                        this.referer = referer; this.quality = Qualities.Unknown.value
                    })
                    found = true
                }
            }
            found
        } catch (_: Exception) {
            false
        }
    }

    /** Último recurso: buscar m3u8/mp4 en la página del embed. */
    private suspend fun extractTdFromPage(
        playerUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val text = app.get(playerUrl, referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").findAll(text)) {
                try { generateM3u8(serverName, m.value, playerUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""").findAll(text)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) {
                    this.referer = playerUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
