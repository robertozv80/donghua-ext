package com.luciferdonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

/**
 * LuciferDonghua (https://luciferdonghua.org/) — WordPress con tema AnimeStream
 * (familia Tsukuyomi, misma base de DonghuaWorld). Verificado en vivo (2026-10):
 *
 * - Home: cards `article` en .listupd (Latest Release / Popular Today); las cards
 *   apuntan a EPISODIOS (/<slug>-episode-N-engsub/) y load() resuelve la ficha
 *   por breadcrumb.
 * - Completed: /anime/?status=completed&sub=&order=latest con paginado
 *   /anime/page/N/?status=completed&sub=&order=latest (15 fichas/pagina).
 * - Action/Adventure: /anime/?genre[]=action&genre[]=adventure&genre[]=martial-arts
 *   &status=&type=&order=latest (20 fichas/pagina, mismo paginado).
 * - Fichas: /anime/<slug>/ con episodios en .eplister.
 * - Episodios: select.mirror con opciones base64 (iframe completo codificado):
 *     * "ENG SUB" (Dailymotion)  -> metadata con pistas de subtitulos
 *       MULTI-IDOMA que en muchos episodios incluye "es" (ESPANOL REAL).
 *     * "Rumble Multi Sub"       -> API embedJS (patron TioDonghua).
 *     * "Ok.ru"                  -> data-options (patron TioDonghua).
 *     * "Multi Sub" (Streamplay) -> player jwplayer solo-JS (se omite).
 *
 * PRIORIDAD DE IDIOMAS: se emiten subtitulos es* (Spanish) primero y en* (English)
 * despues; el servidor primario es el Dailymotion "ENG SUB" que es el unico con
 * pistas de subtitulos reales.
 */
class LuciferDonghuaProvider : MainAPI() {
    override var mainUrl = "https://luciferdonghua.org"
    override var name = "LuciferDonghua"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        private val EPISODE_NUM_REGEX = Regex("""Episode\s+(\d+(?:-\d+)?)""", RegexOption.IGNORE_CASE)

        /** Cache slug -> rating (el listado no trae nota; hay que pedir la ficha). */
        private val ratingCache = java.util.concurrent.ConcurrentHashMap<String, Double>()
    }

    // ==================== MAIN PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/##latest" to "Latest Release",
        "$mainUrl/anime/?status=completed&sub=&order=latest" to "Completed",
        "$mainUrl/anime/?genre%5B%5D=action&genre%5B%5D=adventure&genre%5B%5D=martial-arts&status=&type=&order=latest" to "Action / Adventure"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionName = request.name
        val base = request.data.substringBefore("##")
        val url = when {
            sectionName == "Latest Release" ->
                if (page == 1) base else "$mainUrl/page/$page/"
            else -> // Completed / Action-Adventure: query params + /page/N/ despues del path
                if (page == 1) base
                else base.replace("?", "/page/$page/?")
        }

        val document = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val isLatest = sectionName == "Latest Release"
        val items = document.select("article").mapNotNull { parseArticleCard(it, withDubStatus = isLatest) }
            .distinctBy { it.url }

        // v4: en las secciones que NO son "Latest Release" se muestra la
        // CALIFICACION sobre el poster en lugar de la etiqueta "Sub".
        val finalItems = if (isLatest) items else withRatings(items)

        val hasNext = if (isLatest) items.isNotEmpty() else items.size >= 15
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

        // "Released: 2020" / "Jul 17, 2024" -> primer año de 4 cifras
        val year = speValue(document, "Released")?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        // "Duration: 7 minute" -> minutos
        val durationMin = speValue(document, "Duration")?.let { parseDurationMinutes(it) }

        // Rating: <strong>Rating 7</strong> o <meta itemprop="ratingValue" content="7">
        val scoreRaw = document.selectFirst(".numscore")?.text()?.trim()
            ?: document.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.trim()
            ?: document.selectFirst(".rt strong")?.text()?.trim()?.substringAfter("Rating")?.trim()
        val scoreVal = scoreRaw?.toDoubleOrNull()?.takeIf { it > 0.0 }

        val episodes = mutableListOf<Episode>()
        val seenUrls = mutableSetOf<String>()

        document.select(".eplister li a[href]").forEach { linkEl ->
            val epUrl = linkEl.attr("abs:href")
            if (epUrl.isEmpty() || !epUrl.contains(Regex("""-episode-\d+""", RegexOption.IGNORE_CASE))) return@forEach
            if (!seenUrls.add(epUrl)) return@forEach

            val numText = linkEl.selectFirst(".epl-num")?.text()?.trim()
            val titleText = linkEl.selectFirst(".epl-title")?.text()?.trim()

            // FIX v4 (2026-10): el sitio tiene erratas en .epl-num. El post del
            // episodio 96 lleva num="56" -> colisionaba con el 56 real y la app
            // se saltaba el 96 (del 95 pasaba al 97). La URL es fiable, asi que
            // el numero se toma de "-episode-<N>" y .epl-num queda de reserva.
            val urlNum = Regex("""-episode-(\d+(?:-\d+)?)""", RegexOption.IGNORE_CASE)
                .find(epUrl)?.groupValues?.get(1)
            val epNum = urlNum?.substringBefore("-")?.toIntOrNull()
                ?: numText?.toIntOrNull()
                ?: EPISODE_NUM_REGEX.find(titleText ?: "")?.groupValues?.get(1)
                    ?.substringBefore("-")?.toIntOrNull()

            // Nombre: si el titulo discrepa del rango real de la URL (el sitio
            // titula "Episode 46-50" un post que es episode-46-49), se corrige.
            val name = titleText?.let { t ->
                val tNum = EPISODE_NUM_REGEX.find(t)?.groupValues?.get(1)
                if (urlNum != null && tNum != null && tNum != urlNum) t.replace(tNum, urlNum) else t
            } ?: numText

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

        // select.mirror con iframes completos en base64
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

        // Prioridad: servidor Dailymotion (subs es/en reales), luego Rumble y Ok.ru.
        // sortedBy es estable: conserva el orden del sitio dentro de cada grupo.
        val prio = { label: String ->
            when {
                label.contains("ENG SUB", ignoreCase = true) -> 0
                label.contains("dailymotion", ignoreCase = true) -> 1
                label.contains("rumble", ignoreCase = true) -> 2
                label.contains("ok", ignoreCase = true) -> 3
                else -> 4
            }
        }
        for ((label, iframeHtml) in mirrors.sortedBy { prio(it.first) }) {
            val iframeSrc = Regex("""src=["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                ?: continue
            val fixed = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            val langTag = if (label.contains("ENG", ignoreCase = true)) "English" else label
            val ok = when {
                fixed.contains("dailymotion", ignoreCase = true) ->
                    extractDailymotion(fixed, "$mainUrl/", "Dailymotion ($langTag)", emittedSubs, subtitleCallback, callback)
                fixed.contains("rumble.com", ignoreCase = true) ->
                    extractRumble(fixed, data, "Rumble ($langTag)", callback)
                fixed.contains("ok.ru", ignoreCase = true) ->
                    extractOkRu(fixed, data, "Ok.ru ($langTag)", callback)
                else -> false
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

    /** Dailymotion via metadata: subs es* (Spanish) primero, en* despues; HLS + MP4. */
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

            // Subtitulos: pistas "es*" = ESPANOL (prioridad 0), "en*" = ingles (1)
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

    /** Rumble via API embedJS (puerto de TioDonghuaProvider v24.12). */
    private suspend fun extractRumble(
        embedUrl: String,
        referer: String,
        linkName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val vid = Regex("""rumble\.com/(?:embed/)?(v[a-zA-Z0-9]+)""").find(embedUrl)?.groupValues?.get(1)
                ?: return false
            val api = "https://rumble.com/embedJS/u3/?request=video&v=$vid" +
                "&embed=" + java.net.URLEncoder.encode(embedUrl, "UTF-8")
            val uaMap = Regex("\"(\\d{3,4})\":\\[\"(https://[a-z0-9.]*rumble\\.cloud/video/[A-Za-z0-9/._-]+\\.mp4)\"")
            suspend fun emit(json: String): Int {
                val fixed = json.replace("\\/", "/")
                var n = 0
                uaMap.findAll(fixed).forEach { m ->
                    val h = m.groupValues[1].toIntOrNull()
                    callback(
                        newExtractorLink(source = linkName, name = linkName, url = m.groupValues[2]) {
                            this.referer = "https://rumble.com/"
                            this.quality = when {
                                h == null -> Qualities.Unknown.value
                                h >= 1080 -> Qualities.P1080.value
                                h >= 720 -> Qualities.P720.value
                                h >= 480 -> Qualities.P480.value
                                else -> Qualities.P360.value
                            }
                        }
                    )
                    n++
                }
                return n
            }
            var json = try {
                app.get(api, referer = "https://rumble.com/", headers = mapOf("User-Agent" to UA), timeout = 20L).text
            } catch (_: Exception) { "" }
            var count = if (json.isNotBlank()) emit(json) else 0
            if (count == 0) {
                // WAF Cloudflare: visitar el embed primero y reintentar con la cookie
                try {
                    app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 20L)
                } catch (_: Exception) {}
                json = try {
                    app.get(api, referer = embedUrl, headers = mapOf("User-Agent" to UA), timeout = 20L).text
                } catch (_: Exception) { "" }
                if (json.isNotBlank()) count = emit(json)
            }
            count > 0
        } catch (_: Exception) {
            false
        }
    }

    /** Ok.Ru via data-options (puerto de TioDonghuaProvider). */
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
