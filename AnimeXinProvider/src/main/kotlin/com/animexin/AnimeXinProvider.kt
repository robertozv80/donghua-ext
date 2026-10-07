package com.animexin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.jsoup.nodes.Element

/**
 * AnimeXin (https://animexin.dev/) — WordPress con tema AnimeStream (familia
 * Tsukuyomi, misma base de DonghuaWorld). Verificado en vivo (2026-10):
 *
 * - Home: cards `article` en .listupd.normal (Latest Release); las cards apuntan
 *   a EPISODIOS y load() resuelve la ficha por breadcrumb.
 * - Generos: /genres/action/, /genres/adventure/, /genres/martial-arts/ con
 *   paginado /genres/<g>/page/N/ (10 fichas/pagina).
 * - Fichas: /anime/<slug>/ o /<slug>/ con episodios en .eplister.
 * - Episodios: select.mirror con iframes base64. Servidores reales:
 *     * "All Sub Player Dailymotion AX" -> metadata DM con pistas MULTI-IDOMA
 *       que incluyen "es" (ESPANOL REAL) y "en-auto"; HLS "auto".
 *     * "Hardsub English Dailymotion AX" -> hardsub EN quemado en el video.
 *     * "Hardsub Indonesia ..." / "Dtube" / "Mega" -> otros idiomas o no
 *       extraibles (se omiten).
 *
 * PRIORIDAD DE IDIOMAS (pedido del usuario): el servidor "All Sub" primero
 * (sus pistas es* se emiten como Spanish y en* como English); si falla,
 * fallback al hardsub English.
 */
class AnimeXinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        private val EPISODE_NUM_REGEX = Regex("""Episode\s+(\d+(?:-\d+)?)""", RegexOption.IGNORE_CASE)
    }

    // ==================== MAIN PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/##latest" to "Latest Release",
        "$mainUrl/genres/action/" to "Action",
        "$mainUrl/genres/adventure/" to "Adventure",
        "$mainUrl/genres/martial-arts/" to "Martial Arts"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionName = request.name
        val url = when {
            sectionName == "Latest Release" ->
                if (page == 1) request.data.substringBefore("##") else "$mainUrl/page/$page/"
            else -> if (page == 1) request.data else "${request.data}page/$page/"
        }

        val document = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val selector = if (sectionName == "Latest Release") ".listupd.normal article" else "article"
        val items = document.select(selector).mapNotNull { parseArticleCard(it) }
            .distinctBy { it.url }

        val hasNext = if (sectionName == "Latest Release") items.isNotEmpty() else items.size >= 10
        return newHomePageResponse(listOf(HomePageList(sectionName, items)), hasNext)
    }

    private fun parseArticleCard(article: Element): SearchResponse? {
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
            // FIX v2: animexin.dev exige Referer para servir sus imagenes (403 sin el)
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            addDubStatus(DubStatus.Subbed, epNum)
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

        // Rating: <div class="numscore">8.5</div> o meta[itemprop=ratingValue]
        val scoreRaw = document.selectFirst(".numscore")?.text()?.trim()
            ?: document.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.trim()
        val scoreVal = scoreRaw?.toDoubleOrNull()?.takeIf { it > 0.0 }

        val episodes = mutableListOf<Episode>()
        val seenUrls = mutableSetOf<String>()

        document.select(".eplister li a[href]").forEach { linkEl ->
            val epUrl = linkEl.attr("abs:href")
            if (epUrl.isEmpty() || !epUrl.contains(Regex("""-episode-\d+""", RegexOption.IGNORE_CASE))) return@forEach
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
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
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
        val lazy = imgEl.attr("data-lazy-src").trim()
        if (lazy.isNotEmpty() && !lazy.startsWith("data:")) return lazy
        val src = imgEl.attr("src").trim()
        if (src.startsWith("data:")) return ""
        return src
    }

    // ==================== VIDEO EXTRACTION ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to UA)).document

        val mirrors = mutableListOf<Pair<String, String>>() // label -> iframe html
        document.select("select.mirror option").forEach { opt ->
            val b64 = opt.attr("value").trim()
            if (b64.length < 20) return@forEach
            try {
                val html = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                mirrors.add(opt.text().trim() to html)
            } catch (_: Exception) {}
        }
        if (mirrors.isEmpty()) return false

        val emittedSubs = mutableSetOf<String>()
        var found = false

        // Prioridad: "All Sub" (subs ES/EN reales) -> resto de Dailymotion ->
        // Odysee/Rumble/Ok.ru/otros. sortedBy es estable.
        val prio = { label: String ->
            val l = label.lowercase()
            when {
                l.contains("all sub") -> 0
                l.contains("dailymotion") && l.contains("english") -> 1
                l.contains("dailymotion") -> 2
                l.contains("odysee") && l.contains("english") -> 3
                l.contains("ok.ru") && l.contains("english") -> 4
                else -> 5
            }
        }

        for ((label, iframeHtml) in mirrors.sortedBy { prio(it.first) }) {
            val iframeSrc = Regex("""src=["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                ?: continue
            val fixed = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            val isPrimary = prio(label) <= 2 // All Sub + todos los Dailymotion
            if (!isPrimary && found) break   // fallbacks solo si nada primario funcionó

            val ok = when {
                fixed.contains("dailymotion", ignoreCase = true) ->
                    extractDailymotion(fixed, "$mainUrl/", label, emittedSubs, subtitleCallback, callback)
                fixed.contains("odysee.com", ignoreCase = true) ->
                    extractOdysee(fixed, data, label, callback)
                else -> false // Dtube/Mega/Streamwish/Dood: no extraibles o muertos
            }
            if (ok) found = true
        }
        return found
    }

    // ==================== EXTRACTORES ====================

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

    /** Dailymotion via metadata: subs es* (Spanish) primero, en* (English) despues. */
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

    /**
     * Odysee: hardsub EN/ID embebido en el stream HLS del embed (iframe con
     * firma; la URL de reproduccion vive dentro del JS del embed).
     */
    private suspend fun extractOdysee(
        embedUrl: String,
        referer: String,
        linkName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 20L).text
            var found = false
            Regex(""""hls":"(https:[^"]+?\.m3u8[^"]*)"""").findAll(html).forEach { m ->
                try {
                    generateM3u8(linkName, unescJson(m.groupValues[1]), "https://odysee.com/").forEach(callback)
                    found = true
                } catch (_: Exception) {}
            }
            if (!found) {
                Regex(""""videoUrl":"(https:[^"]+?\.mp4[^"]*)"""").findAll(html).forEach { m ->
                    callback(
                        newExtractorLink(source = linkName, name = linkName, url = unescJson(m.groupValues[1])) {
                            this.referer = "https://odysee.com/"
                            this.quality = Qualities.Unknown.value
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
