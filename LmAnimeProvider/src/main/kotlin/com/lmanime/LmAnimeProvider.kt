package com.lmanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

/**
 * LmAnime (https://lmanime.com/) — WordPress con tema AnimeStream (familia Tsukuyomi,
 * la misma base de DonghuaWorld). Estructura verificada en vivo (2026-10):
 *
 * - Home: secciones con cards `article` dentro de `.listupd.normal` (Latest Release);
 *   los cards apuntan a EPISODIOS (/<slug>-episode-N/) y load() resuelve la ficha
 *   por breadcrumb (misma mecanica de DonghuaWorldProvider).
 * - Fichas: /<slug>/ con episodios en .eplister (div.epl-num / div.epl-title).
 * - Completed Series: /anime/?status=completed&order=latest con paginado
 *   /anime/page/N/?status=completed&order=latest (20 fichas/pagina).
 * - Generos: /genres/action/, /genres/adventure/, /genres/martial-arts/ con
 *   paginado /genres/<g>/page/N/.
 * - Episodios: select.mirror con UNA opcion por servidor/idioma, cada una carga
 *   la pagina /v/N/ cuyo #pembed trae UN iframe:
 *     * Dailymotion  -> HLS por API metadata + pistas de subtitulos del metadata
 *     * Ok.ru        -> data-options (hlsManifestUrl + progresivos)
 *     * MP4Upload    -> player videojs solo-JS (no extraible server-side; se omite)
 *
 * PRIORIDAD DE IDIOMAS (pedido del usuario): servidores con subtitulos en ESPANOL
 * primero (mirrors etiquetados "Espanol"), luego ENGLISH, y solo si no hubo links
 * de ES/EN se prueban el resto (Portugues/Tai/Arabe).
 */
class LmAnimeProvider : MainAPI() {
    override var mainUrl = "https://lmanime.com"
    override var name = "LmAnime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        /** Regex para numeros de episodio tipo "Episode 263" o "Episode 254-255". */
        private val EPISODE_NUM_REGEX = Regex("""Episode\s+(\d+(?:-\d+)?)""", RegexOption.IGNORE_CASE)

        /** Cache slug -> rating (el listado no trae nota; hay que pedir la ficha). */
        private val ratingCache = java.util.concurrent.ConcurrentHashMap<String, Double>()
    }

    // ==================== MAIN PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/##latest" to "Latest Release",
        "$mainUrl/anime/?status=completed&order=latest" to "Completed Series",
        "$mainUrl/genres/action/" to "Action",
        "$mainUrl/genres/adventure/" to "Adventure",
        "$mainUrl/genres/martial-arts/" to "Martial Arts"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionName = request.name
        val url = when {
            sectionName == "Latest Release" ->
                if (page == 1) request.data.substringBefore("##") else "$mainUrl/page/$page/"
            sectionName == "Completed Series" ->
                if (page == 1) request.data else "$mainUrl/anime/page/$page/?status=completed&order=latest"
            else -> // generos: la URL ya termina en "/"
                if (page == 1) request.data else "${request.data}page/$page/"
        }

        val document = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val isLatest = sectionName == "Latest Release"
        val selector = if (isLatest) ".listupd.normal article" else "article"
        val items = document.select(selector).mapNotNull { parseArticleCard(it, withDubStatus = isLatest) }
            .distinctBy { it.url }

        // v4: en las secciones que NO son "Latest Release" se muestra la
        // CALIFICACION sobre el poster en lugar de la etiqueta "Sub".
        val finalItems = if (isLatest) items else withRatings(items)

        val hasNext = when {
            isLatest -> items.isNotEmpty()
            sectionName == "Completed Series" -> items.size >= 20
            else -> items.size >= 10
        }
        return newHomePageResponse(listOf(HomePageList(sectionName, finalItems)), hasNext)
    }

    /** Anade la nota (Score) a cada card consultando su ficha; cachea por slug. */
    private suspend fun withRatings(items: List<SearchResponse>): List<SearchResponse> = coroutineScope {
        items.map { sr ->
            async {
                try {
                    val slug = sr.url.trimEnd('/').substringAfterLast('/')
                    val rating = ratingCache[slug] ?: fetchRating(sr.url)?.also { ratingCache[slug] = it }
                    if (rating != null) sr.score = Score.from10(rating)
                } catch (_: Exception) {}
                sr
            }
        }.awaitAll()
    }

    /** Nota de la ficha: .numscore o meta[itemprop=ratingValue]. */
    private suspend fun fetchRating(seriesUrl: String): Double? = try {
        val doc = app.get(seriesUrl, headers = mapOf("User-Agent" to UA), timeout = 15L).document
        (doc.selectFirst(".numscore")?.text()?.trim()
            ?: doc.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.trim())
            ?.toDoubleOrNull()?.takeIf { it > 0.0 }
    } catch (_: Exception) {
        null
    }

    /** Parsea un card de listado (puede ser ficha de serie o pagina de episodio). */
    private fun parseArticleCard(article: Element, withDubStatus: Boolean = true): SearchResponse? {
        val linkEl = article.selectFirst("a[href]") ?: return null
        val url = linkEl.attr("abs:href")
        if (url.isEmpty()) return null

        val title = linkEl.attr("title").takeIf { it.isNotEmpty() }
            ?: linkEl.selectFirst("h2, h3, .eggtitle, .tt")?.text()?.trim()
            ?: linkEl.selectFirst("img")?.attr("alt")?.takeIf { it.isNotEmpty() }
            ?: return null

        val img = article.selectFirst("img")?.let { getImgSrc(it) } ?: ""
        val epNum = EPISODE_NUM_REGEX.find(title)?.groupValues?.get(1)
            ?.substringBefore("-")?.toIntOrNull()
        val cleanTitle = title.replace(
            Regex("""\s*Episode\s+\d+(?:-\d+)?[^|]*$""", RegexOption.IGNORE_CASE), ""
        ).trim()

        return newAnimeSearchResponse(cleanTitle, url) {
            this.posterUrl = img
            // v4: en las secciones de genero/Completed no se marca "Subbed"; ahi manda la nota.
            if (withDubStatus) addDubStatus(DubStatus.Subbed, epNum)
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}",
            headers = mapOf("User-Agent" to UA)
        ).document
        return document.select("article").mapNotNull { parseArticleCard(it) }
            .distinctBy { it.url }
    }

    // ==================== DETAIL ====================

    override suspend fun load(url: String): LoadResponse {
        // Si la URL es de episodio (<slug>-episode-N/), resolvemos la ficha via breadcrumb.
        val isEpisodeUrl = url.substringBefore("?").trimEnd('/').substringAfterLast('/')
            .contains(Regex("""-episode-\d+""", RegexOption.IGNORE_CASE))
        val seriesUrl = if (isEpisodeUrl) resolveSeriesUrlFromEpisode(url) else url

        val document = app.get(seriesUrl, headers = mapOf("User-Agent" to UA)).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        val poster = document.selectFirst(".thumb img")?.let { getImgSrc(it) }
            ?: document.selectFirst(".bigcontent .ts-post-image")?.let { getImgSrc(it) }
            ?: document.selectFirst(".ts-post-image")?.let { getImgSrc(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""

        val description = run {
            val synopsisBox = document.selectFirst(".bixbox.synp .entry-content")
                ?: document.selectFirst(".synp .entry-content")
                ?: document.selectFirst(".mindesc")
            synopsisBox?.text()?.trim() ?: ""
        }

        val genres = document.select(".genxed a, .series-gen a").mapNotNull { it.text().trim() }

        val showStatus = speValue(document, "Status")?.let { stat ->
            when {
                stat.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
                stat.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
                else -> null
            }
        }

        // "Released: 2020" / "Apr 04, 2026" -> primer año de 4 cifras
        val year = speValue(document, "Released")?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        // "Duration: 20 min. per ep." -> minutos
        val durationMin = speValue(document, "Duration")?.let { parseDurationMinutes(it) }

        // Rating: <strong>Rating 8</strong> o <meta itemprop="ratingValue" content="8">
        val scoreRaw = document.selectFirst(".numscore")?.text()?.trim()
            ?: document.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.trim()
            ?: document.selectFirst(".rt strong")?.text()?.trim()?.substringAfter("Rating")?.trim()
        val scoreVal = scoreRaw?.toDoubleOrNull()?.takeIf { it > 0.0 }

        val episodes = mutableListOf<Episode>()
        val seenUrls = mutableSetOf<String>()

        document.select(".eplister li a[href]").forEach { linkEl ->
            val epUrl = linkEl.attr("abs:href")
            if (epUrl.isEmpty() || !epUrl.contains(Regex("""-episode-""", RegexOption.IGNORE_CASE))) return@forEach
            if (!seenUrls.add(epUrl)) return@forEach

            val numText = linkEl.selectFirst(".epl-num")?.text()?.trim()
            val titleText = linkEl.selectFirst(".epl-title")?.text()?.trim()
            val epNum = numText?.toIntOrNull()
                ?: EPISODE_NUM_REGEX.find(titleText ?: "")?.groupValues?.get(1)
                    ?.substringBefore("-")?.toIntOrNull()
            val name = titleText ?: numText

            episodes.add(newEpisode(epUrl) {
                this.name = name?.takeIf { it.isNotEmpty() }
                this.episode = epNum
            })
        }

        val sortedEpisodes = episodes.sortedBy { it.episode ?: 0 }

        return newAnimeLoadResponse(title, seriesUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.showStatus = showStatus
            this.year = year
            if (scoreVal != null) this.score = Score.from10(scoreVal)
            if (durationMin != null && durationMin > 0) this.duration = durationMin
            this.episodes = mutableMapOf(DubStatus.Subbed to sortedEpisodes)
        }
    }

    /** Valor de un campo del bloque .spe del tema: "Status: Ongoing" -> "Ongoing". */
    private fun speValue(document: org.jsoup.nodes.Document, key: String): String? {
        return document.select(".spe span").firstOrNull { it.text().startsWith("$key:", ignoreCase = true) }
            ?.text()?.substringAfter(":")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** "20 min. per ep." / "7 minute" / "1 h 20 min" -> minutos. */
    private fun parseDurationMinutes(text: String): Int? {
        Regex("(\\d+)\\s*(?:h|hr|hour)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            ?.toIntOrNull()?.let { hours ->
                val mins = Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                return hours * 60 + mins
            }
        return Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Dada una pagina de episodio, saca la URL de la ficha desde el breadcrumb. */
    private suspend fun resolveSeriesUrlFromEpisode(episodeUrl: String): String {
        return try {
            val epDoc = app.get(episodeUrl, headers = mapOf("User-Agent" to UA)).document
            val root = "$mainUrl/"
            epDoc.select(".ts-breadcrumb a, .breadcrumb a, .breadcrumbs a")
                .map { it.attr("abs:href") }
                .filter { it.isNotEmpty() && it != root && it != episodeUrl && !it.contains("?") }
                .lastOrNull()
                ?: episodeUrl
        } catch (_: Exception) {
            episodeUrl
        }
    }

    private fun getImgSrc(imgEl: Element): String {
        val dataSrc = imgEl.attr("data-src").trim()
        if (dataSrc.isNotEmpty() && !dataSrc.startsWith("data:")) return dataSrc
        return imgEl.attr("src").trim()
    }

    // ==================== VIDEO EXTRACTION ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to UA)).document

        // Select.mirror con etiqueta de idioma por servidor ("English", "Espanol", ...)
        val mirrors = document.select("select.mirror option").mapNotNull { opt ->
            val u = opt.attr("value").trim()
            if (u.isEmpty()) null else MirrorOption(opt.text().trim(), u)
        }
        if (mirrors.isEmpty()) return false

        // sortedBy es estable: mantiene el orden original dentro de cada idioma
        val sorted = mirrors.sortedBy { langPrio(it.label) }

        val emittedSubs = mutableSetOf<String>()
        var foundAny = false
        // 1) ES y EN primero
        for (m in sorted.filter { langPrio(it.label) <= 1 }) {
            if (processMirror(m, data, emittedSubs, subtitleCallback, callback)) foundAny = true
        }
        // 2) Solo si no hubo nada de ES/EN, probar el resto de idiomas
        if (!foundAny) {
            for (m in sorted.filter { langPrio(it.label) == 2 }) {
                if (processMirror(m, data, emittedSubs, subtitleCallback, callback)) foundAny = true
            }
        }
        return foundAny
    }

    private data class MirrorOption(val label: String, val url: String)

    /**
     * 0 = espanol / multisub, 1 = ingles, 2 = resto de idiomas.
     *
     * FIX v4: "Multisub" traia pistas de subtitulos en varios idiomas
     * (incluido espanol) pero caia en el grupo 2 y se descartaba en cuanto
     * "English" funcionaba -> el servidor no aparecia. Ahora va primero.
     */
    private fun langPrio(label: String): Int {
        val l = label.lowercase()
        return when {
            l.contains("multi") -> 0
            l.contains("espa") || l.contains("spanish") || l.contains("latino") -> 0
            l.contains("english") || l.contains("ingles") || l.contains("ingl") -> 1
            else -> 2
        }
    }

    /** Descarga la pagina /v/N/ del mirror y extrae el video (y subs si hay). */
    private suspend fun processMirror(
        mirror: MirrorOption,
        referer: String,
        emittedSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val page = app.get(mirror.url, referer = referer, headers = mapOf("User-Agent" to UA)).document
            val iframes = page.select("#pembed iframe[src], .player-embed iframe[src], iframe[src]")
                .map { it.attr("abs:src") }
                .filter { it.contains("dailymotion", ignoreCase = true) || it.contains("ok.ru", ignoreCase = true) }
            if (iframes.isEmpty()) return false

            // v4: "Multisub" se etiqueta como tal (antes salia como "Español").
            val langTag = when {
                mirror.label.contains("multi", ignoreCase = true) -> "Multisub"
                langPrio(mirror.label) == 0 -> "Español"
                langPrio(mirror.label) == 1 -> "English"
                else -> mirror.label
            }
            var found = false
            for (iframe in iframes.distinct()) {
                found = when {
                    iframe.contains("dailymotion", ignoreCase = true) ->
                        extractDailymotion(iframe, "$mainUrl/", "$langTag • Dailymotion", emittedSubs, subtitleCallback, callback)
                    iframe.contains("ok.ru", ignoreCase = true) ->
                        extractOkRu(iframe, referer, "$langTag • Ok.ru", callback)
                    else -> false
                } || found
            }
            found
        } catch (_: Exception) {
            false
        }
    }

    // ==================== EXTRACTORES ====================

    /** Desescapa secuencias JSON (\uXXXX, \/, \n, ...). */
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

    /** Extrae el bloque JSON subtitles.data del metadata de Dailymotion. */
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

    /**
     * Extractor de Dailymotion via API metadata (misma via que usa el reproductor
     * geo.dailymotion.com). Emite:
     *  - SubtitleFile de las pistas "es*" (ESPANOL, prioridad) y "en*" (ingles);
     *  - HLS master ("auto") via generateM3u8;
     *  - MP4 progresivos por calidad si existen.
     * Devuelve true si emitio al menos un enlace de video.
     */
    private suspend fun extractDailymotion(
        iframeSrc: String,
        referer: String,
        linkName: String,
        emittedSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = Regex(
            """(?:geo\.dailymotion\.com/player(?:/[a-z0-9]+)?\.html\?video=|dailymotion\.com/embed/video/)([A-Za-z0-9]+)""",
            RegexOption.IGNORE_CASE
        ).find(iframeSrc)?.groupValues?.get(1) ?: return false
        return try {
            val metaUrl = "https://www.dailymotion.com/player/metadata/video/$vid" +
                "?embedder=" + java.net.URLEncoder.encode(referer, "UTF-8") + "&integration=inline"
            // FIX v2: DM ahora escapa las barras en el JSON (https:\/\/...) -> desescapar
            // antes de aplicar regex, si no ni el HLS ni los subs salen.
            val json = app.get(metaUrl, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 20L)
                .text.replace("\\/", "/").replace("\\u0026", "&")

            // --- Subtitulos: es* primero (espanol), luego en* (ingles) ---
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

            // --- Video HLS (calidad auto) ---
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

            // --- MP4 progresivos por calidad ---
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

    /**
     * Extractor de Ok.Ru: el embed trae data-options con el hlsManifestUrl y las
     * urls progresivas (patron portado de TioDonghuaProvider).
     */
    private suspend fun extractOkRu(
        embedUrl0: String,
        referer: String,
        linkName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val url = if (embedUrl0.startsWith("//")) "https:$embedUrl0" else embedUrl0
            val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 20L).text
            val decodeUnicode = { s: String ->
                Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { m ->
                    m.groupValues[1].toInt(16).toChar().toString()
                }
            }
            val options = Regex("data-options=\"([^\"]+)\"").find(html)
                ?.groupValues?.get(1)
                ?.let { it.replace("&quot;", "\"").replace("\\/", "/").let(decodeUnicode) }
                ?: return false
            var found = false
            val hlsUrl = Regex("\"(?:[a-zA-Z]*ManifestUrl|ondemandHls)\":\"([^\"]+)\"")
                .findAll(options).map { it.groupValues[1] }.firstOrNull { candidate ->
                    try {
                        app.get(candidate, referer = url, headers = mapOf("User-Agent" to UA), timeout = 20L)
                            .text.trimStart().startsWith("#EXTM3U")
                    } catch (_: Exception) { false }
                }
            if (hlsUrl != null) {
                try {
                    generateM3u8(linkName, hlsUrl, url).forEach(callback)
                    found = true
                } catch (_: Exception) {}
            }
            Regex("\"name\":\"(mobile|lowest|low|sd|hd|full|super)\",\"url\":\"([^\"]+)\"")
                .findAll(options).forEach { m ->
                    val progressive = decodeUnicode(m.groupValues[2])
                    if (progressive.startsWith("http")) {
                        val full = if (progressive.startsWith("//")) "https:$progressive" else progressive
                        callback(
                            newExtractorLink(source = linkName, name = linkName, url = full) {
                                this.referer = url
                                this.quality = when (m.groupValues[1]) {
                                    "full", "super" -> Qualities.P1080.value
                                    "hd" -> Qualities.P720.value
                                    "sd" -> Qualities.P480.value
                                    else -> Qualities.P360.value
                                }
                            }
                        )
                        found = true
                    }
                }
            found
        } catch (_: Exception) {
            false
        }
    }
}
