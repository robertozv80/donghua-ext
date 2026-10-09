package com.donghuazone

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

/**
 * DonghuaZone (https://www.donghuazone.com/) — Blogger (Blogspot) con plantilla
 * tipo AnimeStream. Contrato verificado en vivo (2026-10):
 *
 * - Home (Latest Episode): posts de Blogger; hay JSON feed publico:
 *     /feeds/posts/default?alt=json&max-results=N&start-index=M
 *   Cada entry: title.$t, link[rel=alternate].href, media$thumbnail.url (s72c,
 *   reescribible a /s0/ para tamaño original), content.$t (incluye serverBtn +
 *   changeServer con URLs de los players), category[].term = labels.
 * - Generos: feeds por label /feeds/posts/default/-/<Label>?alt=json
 *   ("Action" y "Adventure" funcionan; "Martial Arts" NO existe como label:
 *   totalResults=0 — los eps de martial arts usan otros labels de genero).
 * - Serie: label no-genero del post (ej: "Swallowed star", "Martial Master");
 *   su feed /feeds/posts/default/-/<serie>?alt=json&max-results=50 lista TODOS
 *   los episodios publicados (ordenados por fecha desc).
 * - Episodio: el post embebe un player con <button class="serverBtn"
 *   onclick="changeServer(this,'https://geo.dailymotion.com/player/x110cm.html?video=ID')">
 *   (servidores Indonesia Sub / English Sub sin URL = botones muertos).
 * - Reproduccion: Dailymotion via metadata (subs en-auto = English; en muchos
 *   episodios hay pistas es* reales; title del metadata da el nombre limpio).
 *
 * Los generos pedidos que existen como label: Action, Adventure. Para
 * "Martial Arts" se usa la busqueda del feed (?q=martial) como alternativa.
 */
class DonghuaZoneProvider : MainAPI() {
    override var mainUrl = "https://www.donghuazone.com"
    override var name = "DonghuaZone"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        private const val FEED = "/feeds/posts/default"
        private const val PAGE_SIZE = 25

        private val GENRE_SET = setOf(
            "Action", "Adventure", "Cars", "Comedy", "Dementia", "Demons", "Drama",
            "Ecchi", "Fantasy", "Game", "Harem", "Historical", "Horror", "Josei",
            "Kids", "Magic", "Martial Arts", "Mecha", "Military", "Music",
            "Mysteri", "Parody", "Police", "Psychological", "Romance", "Samurai",
            "School", "Sci-Fi", "Seinen", "Shoujo", "Shoujo Ai", "Shounen",
            "Shounen Ai", "Slice of Life", "Space", "Sport", "Sports",
            "Super Power", "Supernatural", "Thriller", "Vampire", "Isekai",
            "Erotica", "Hentai", "Danger", "Hot", "Sub", "Episode", "Eps. Donghua"
        )

        /** Labels meta que NO son generos reales (se excluyen de los tags de la ficha). */
        private val META_LABELS = setOf("Danger", "Hot", "Sub", "Episode", "Eps. Donghua")
    }

    // ==================== MAIN PAGE ====================

    // v3: se eliminan las secciones Action / Adventure / Martial Arts y se
    // anade "Movies" (label Movie del feed de Blogger, 8 entradas en 2026-10).
    override val mainPage = mainPageOf(
        "$mainUrl$FEED?alt=json" to "Latest Episode",
        "label:Movie" to "Movies"
    )

    /** Resultado de una peticion al feed: lista de entradas + flag hasNext. */
    private data class FeedPage(val entries: List<org.json.JSONObject>, val fullPage: Boolean)

    private suspend fun fetchFeed(url: String): FeedPage {
        return try {
            val text = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 25L).text
            val json = org.json.JSONTokener(text).nextValue() as? org.json.JSONObject
                ?: return FeedPage(emptyList(), false)
            val feed = json.optJSONObject("feed") ?: return FeedPage(emptyList(), false)
            val arr = feed.optJSONArray("entry")
            val entries = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
            val total = feed.optJSONObject("openSearch\$totalResults")?.optString("\$t")?.toIntOrNull() ?: 0
            FeedPage(entries, total > entries.size)
        } catch (_: Exception) {
            FeedPage(emptyList(), false)
        }
    }

    private fun entryToSearchResponse(entry: org.json.JSONObject): SearchResponse? {
        val title = entry.optJSONObject("title")?.optString("\$t") ?: return null
        val links = entry.optJSONArray("link") ?: return null
        var url = ""
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i) ?: continue
            if (l.optString("rel") == "alternate") { url = l.optString("href"); break }
        }
        if (url.isEmpty()) return null

        val thumb = entry.optJSONObject("media\$thumbnail")?.optString("url") ?: ""
        val img = thumb.replace(Regex("/s\\d+(-c)?/"), "/s0/")

        val epNum = Regex("""Episode\s+(\d+(?:-\d+)?)""", RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)?.substringBefore("-")?.toIntOrNull()
        val cleanTitle = title
            .replace(Regex("""\s*Episode\s+\d+(?:-\d+)?[^|]*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[\[(].*?[\])]\s*$"""), "")
            .trim()

        return newAnimeSearchResponse(cleanTitle, url) {
            this.posterUrl = img
            addDubStatus(DubStatus.Subbed, epNum)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val url = when {
            data.startsWith("label:") -> {
                val lab = data.removePrefix("label:")
                if (page == 1) "$mainUrl$FEED/-/$lab?alt=json&max-results=$PAGE_SIZE"
                else "$mainUrl$FEED/-/$lab?alt=json&max-results=$PAGE_SIZE&start-index=${(page - 1) * PAGE_SIZE + 1}"
            }
            data.startsWith("q:") -> {
                val q = java.net.URLEncoder.encode(data.removePrefix("q:"), "UTF-8")
                "$mainUrl$FEED?alt=json&q=$q&max-results=$PAGE_SIZE" +
                    if (page > 1) "&start-index=${(page - 1) * PAGE_SIZE + 1}" else ""
            }
            else -> "$mainUrl$FEED?alt=json&max-results=$PAGE_SIZE" +
                if (page > 1) "&start-index=${(page - 1) * PAGE_SIZE + 1}" else ""
        }

        val feed = fetchFeed(url)
        val items = feed.entries.mapNotNull { entryToSearchResponse(it) }
            .distinctBy { it.url }
        val hasNext = feed.fullPage && items.size >= PAGE_SIZE
        return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext)
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val feed = fetchFeed("$mainUrl$FEED?alt=json&q=$q&max-results=30")
        return feed.entries.mapNotNull { entryToSearchResponse(it) }
            .distinctBy { it.url }
    }

    // ==================== DETAIL ====================

    /** true si el label es de genero/meta y no de serie. */
    private fun isGenreLabel(l: String): Boolean = l in GENRE_SET

    private fun String.jsonUnescape(): String {
        val sb = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c == '\\' && i + 1 < length) {
                when (this[i + 1]) {
                    'u' -> {
                        val hex = substring(i + 2, (i + 6).coerceAtMost(length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6 }
                        else { sb.append(this[i + 1]); i += 2 }
                    }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(this[i + 1]); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    override suspend fun load(url: String): LoadResponse {
        // Pagina de episodio -> resolver el label de serie via feed q= con el slug
        val isEpisode = url.contains(Regex("""/\d{4}/\d{2}/.+\.html$"""))
        var seriesLabel = ""
        var firstPostTitle = ""
        if (isEpisode) {
            val slug = url.substringAfterLast("/").removeSuffix(".html")
            // El feed q= del slug trae el post; sus labels incluyen la serie
            val feed = fetchFeed("$mainUrl$FEED?alt=json&q=${java.net.URLEncoder.encode(slug, "UTF-8")}&max-results=5")
            for (entry in feed.entries) {
                val link = entryLink(entry)
                if (link != null && (link == url || link.contains(slug))) {
                    val cats = entry.optJSONArray("category")
                    val labels = (0 until (cats?.length() ?: 0)).mapNotNull { cats?.optJSONObject(it)?.optString("term") }
                    seriesLabel = labels.lastOrNull { !isGenreLabel(it) && it != "Eps. Donghua" } ?: ""
                    firstPostTitle = entry.optJSONObject("title")?.optString("\$t") ?: ""
                    break
                }
            }
        }
        if (seriesLabel.isBlank()) {
            // Fallback: usar el propio post como "serie"
            seriesLabel = url.substringAfterLast("/").removeSuffix(".html").replace("-", " ")
        }

        // Feed de la serie = episodios (por fecha desc; ordenar por numero)
        val labelEnc = java.net.URLEncoder.encode(seriesLabel, "UTF-8")
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        var startIndex = 1
        // Datos de la ficha: poster/plot/año del post de serie + generos de los labels
        var seriesThumb = ""
        var seriesPlot = ""
        var seriesYear: Int? = null
        val genreLabels = LinkedHashSet<String>()
        val stripHtml = { html: String ->
            html.replace(Regex("(?is)<(script|style)[\\s\\S]*?</\\1>"), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace(Regex("\\s+"), " ").trim()
        }
        // Hasta 4 paginas de 25 entradas (100 episodios) — suficiente para la mayoria
        for (p in 1..4) {
            val feed = fetchFeed(
                "$mainUrl$FEED/-/$labelEnc?alt=json&max-results=$PAGE_SIZE&start-index=$startIndex"
            )
            if (feed.entries.isEmpty()) break
            for (entry in feed.entries) {
                val title = entry.optJSONObject("title")?.optString("\$t") ?: continue
                val link = entryLink(entry) ?: continue
                if (!seen.add(link)) continue

                val isEpisodeEntry = title.contains(Regex("""Episode\s+\d+""", RegexOption.IGNORE_CASE))
                val thumb = entry.optJSONObject("media\$thumbnail")?.optString("url")
                    ?.replace(Regex("/s\\d+(-c)?/"), "/s0/") ?: ""
                val cats = entry.optJSONArray("category")
                (0 until (cats?.length() ?: 0)).forEach { i ->
                    cats?.optJSONObject(i)?.optString("term")?.let { t ->
                        if (isGenreLabel(t) && t !in META_LABELS) genreLabels.add(t)
                    }
                }

                if (!isEpisodeEntry && seriesThumb.isEmpty()) {
                    // Post de la serie: poster, sinopsis y año de publicacion
                    seriesThumb = thumb
                    val content = entry.optJSONObject("content")?.optString("\$t") ?: ""
                    val plain = stripHtml(content)
                    if (plain.length > 40) seriesPlot = plain.take(1200)
                    seriesYear = entry.optString("published")?.take(4)?.toIntOrNull()
                }
                if (seriesThumb.isEmpty() && thumb.isNotEmpty()) seriesThumb = thumb

                if (!isEpisodeEntry) continue
                val num = Regex("""Episode\s+(\d+(?:-\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(title)?.groupValues?.get(1)?.substringBefore("-")?.toIntOrNull()
                episodes.add(newEpisode(link) {
                    this.name = title
                    this.episode = num
                })
            }
            if (!feed.fullPage) break
            startIndex += PAGE_SIZE
        }

        val sorted = episodes.sortedBy { it.episode ?: 0 }
        val displayName = seriesLabel.replace(Regex("""\b[a-z]""")) { it.value.uppercase() }

        // v3: recomendaciones (similitud de nombre, hasta 16).
        val recommendations = fetchRecommendations(seriesLabel, url)

        return newAnimeLoadResponse(displayName, if (isEpisode) url else "$mainUrl/search/label/$labelEnc", TvType.Anime) {
            this.plot = if (seriesPlot.isNotBlank()) seriesPlot
            else "Donghua 4K con subtitulos (Indonesia/English). Servidor: Dailymotion (Multi Sub)."
            if (seriesThumb.isNotBlank()) this.posterUrl = seriesThumb
            if (genreLabels.isNotEmpty()) this.tags = genreLabels.toList()
            if (seriesYear != null && seriesYear in 1900..2100) this.year = seriesYear
            this.episodes = mutableMapOf(DubStatus.Subbed to sorted)
            if (recommendations.isNotEmpty()) this.recommendations = recommendations
        }
    }

    /**
     * v3: recomendaciones "como en los primeros providers" — similitud de nombre
     * (hasta 16 resultados), sobre el feed publico de Blogger:
     *  1) Entradas cuya SERIE (label no-genero) empieza por el nombre base, una
     *     por serie;
     *  2) Relleno aleatorio de una pagina del feed general.
     */
    private suspend fun fetchRecommendations(
        seriesLabel: String,
        currentUrl: String
    ): List<SearchResponse> {
        val all = ArrayList<SearchResponse>()
        val seenUrls = HashSet<String>()
        seenUrls.add(currentUrl)
        val seenSeries = HashSet<String>()

        val norm = dzRecNormalize(seriesLabel)
        val baseNorm = Regex("""\s+\d+$""").replace(norm, "").trim().ifBlank { norm }

        // 1) Mismo nombre base (misma serie / temporadas)
        if (baseNorm.isNotBlank()) {
            try {
                val q = java.net.URLEncoder.encode(baseNorm, "UTF-8")
                fetchFeed("$mainUrl$FEED?alt=json&q=$q&max-results=25").entries.forEach { entry ->
                    val link = entryLink(entry) ?: return@forEach
                    if (link in seenUrls) return@forEach
                    val label = seriesLabelOf(entry) ?: return@forEach
                    val lNorm = dzRecNormalize(label)
                    if (lNorm != baseNorm && !lNorm.startsWith(baseNorm)) return@forEach
                    if (!seenSeries.add(lNorm)) return@forEach
                    seenUrls.add(link)
                    entryToSearchResponse(entry)?.let { all.add(it) }
                }
            } catch (_: Exception) {}
        }

        // 2) Relleno aleatorio del feed general
        val pool = ArrayList<SearchResponse>()
        if (all.size < 16) {
            try {
                val start = 1 + (0 until 8).random() * PAGE_SIZE
                fetchFeed("$mainUrl$FEED?alt=json&max-results=$PAGE_SIZE&start-index=$start").entries
                    .forEach { entry ->
                        if (pool.size >= 40) return@forEach
                        val link = entryLink(entry) ?: return@forEach
                        if (link in seenUrls) return@forEach
                        seenUrls.add(link)
                        entryToSearchResponse(entry)?.let { pool.add(it) }
                    }
            } catch (_: Exception) {}
        }

        pool.shuffle()
        all.addAll(pool)
        return all.take(16)
    }

    /** Label de SERIE de una entrada del feed (ultimo label que no es genero/meta). */
    private fun seriesLabelOf(entry: org.json.JSONObject): String? {
        val cats = entry.optJSONArray("category") ?: return null
        val labels = (0 until cats.length()).mapNotNull { cats.optJSONObject(it)?.optString("term") }
        return labels.lastOrNull { !isGenreLabel(it) && it != "Eps. Donghua" }
    }

    /** Normaliza un titulo para comparar bases (minusculas, sin acentos ni simbolos). */
    private fun dzRecNormalize(t: String): String =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ").trim()

    private fun entryLink(entry: org.json.JSONObject): String? {
        val links = entry.optJSONArray("link") ?: return null
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i) ?: continue
            if (l.optString("rel") == "alternate") return l.optString("href")
        }
        return null
    }

    // ==================== VIDEO EXTRACTION ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = mapOf("User-Agent" to UA), timeout = 25L).text

        // Servers del post: onclick="changeServer(this, 'URL')" con texto del boton
        val servers = Regex(
            """<button[^>]*onclick="changeServer\(this,\s*'([^']*)'\)"[^>]*>\s*([^<]+?)\s*</button>"""
        ).findAll(html)
            .mapNotNull { m ->
                val u = m.groupValues[1].trim()
                if (u.isEmpty()) null else u to m.groupValues[2].trim()
            }
            .toList()
        if (servers.isEmpty()) return false

        // Prioridad: Multi Sub primero (es el que tiene las pistas de subtitulos)
        val sorted = servers.sortedBy { (u, label) ->
            when {
                label.contains("multi", ignoreCase = true) -> 0
                else -> 1
            }
        }

        val emittedSubs = mutableSetOf<String>()
        var found = false
        for ((u, label) in sorted) {
            val dmVid = Regex("""(?:geo\.dailymotion\.com/player(?:/[a-z0-9]+)?\.html\?video=|dailymotion\.com/embed/video/)([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(u)?.groupValues?.get(1)
            if (dmVid != null) {
                val ok = extractDailymotion(dmVid, "$mainUrl/", "Dailymotion ($label)", emittedSubs, subtitleCallback, callback)
                if (ok) found = true
            }
        }
        return found
    }

    // ==================== EXTRACTORES ====================

    private fun dmSubtitlesBlock(json: String): String? {
        val start = json.indexOf("\"subtitles\"")
        if (start < 0) return null
        val dataIdx = json.indexOf("\"data\"", start)
        if (dataIdx < 0) return null
        val open = json.indexOf('{', dataIdx)
        if (open < 0) return null
        var depth = 0
        for (i in open until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(open, i + 1)
                }
            }
        }
        return null
    }

    private fun unescJson(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'u' -> {
                        val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                        } else {
                            sb.append(s[i + 1])
                            i += 2
                        }
                    }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(s[i + 1]); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private suspend fun extractDailymotion(
        vid: String,
        referer: String,
        linkName: String,
        emittedSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val metaUrl = "https://www.dailymotion.com/player/metadata/video/$vid" +
                "?embedder=" + java.net.URLEncoder.encode(referer, "UTF-8") + "&integration=inline"
            val json = app.get(metaUrl, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 20L)
                .text.replace("\\/", "/").replace("\\u0026", "&")

            dmSubtitlesBlock(json)?.let { block ->
                Regex(""""(es[a-z-]*|en[a-z-]*)":\s*\{[^{}]*?"urls":\["([^"]+)"""")
                    .findAll(block)
                    .map { it.groupValues[1] to unescJson(it.groupValues[2]) }
                    .distinctBy { it.second }
                    .sortedBy { (code, _) -> if (code.startsWith("es")) 0 else 1 }
                    .forEach { (code, url) ->
                        if (emittedSubs.add(url)) {
                            subtitleCallback.invoke(
                                SubtitleFile(if (code.startsWith("es")) "Spanish" else "English", url)
                            )
                        }
                    }
            }

            var found = false
            Regex(""""url":"(https://[^"]+?\.m3u8[^"]*)"""").findAll(json)
                .map { unescJson(it.groupValues[1]) }
                .firstOrNull()
                ?.let { hls ->
                    try {
                        generateM3u8(linkName, hls, "https://www.dailymotion.com/").forEach(callback)
                        found = true
                    } catch (_: Exception) {}
                }
            Regex(""""(\d{3,4})":\s*\[\s*\{\s*"type":\s*"video/mp4"\s*,\s*"url":\s*"([^"]+)"""")
                .findAll(json)
                .forEach { m ->
                    val h = m.groupValues[1].toIntOrNull()
                    callback(
                        newExtractorLink(source = linkName, name = linkName, url = unescJson(m.groupValues[2])) {
                            this.referer = "https://www.dailymotion.com/"
                            this.quality = when {
                                h == null -> Qualities.Unknown.value
                                h >= 1080 -> Qualities.P1080.value
                                h >= 720 -> Qualities.P720.value
                                h >= 480 -> Qualities.P480.value
                                else -> Qualities.P360.value
                            }
                        }
                    )
                    found = true
                }
            found
        } catch (_: Exception) {
            false
        }
    }
}
