package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlin.collections.ArrayList

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
        "$mainUrl/todos-los-donghuas" to "Todos los Donghuas",
        "$mainUrl/donghuas-en-emision" to "En Emisión",
        "$mainUrl/donghuas-finalizados" to "Finalizados",
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

    // ========== getMainPage ==========
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == "$mainUrl/"
        val url = if (page > 1) {
            if (isHomePage) request.data else "${request.data}?pag=$page"
        } else {
            request.data
        }
        val doc = app.get(url, timeout = 120L).document

        val home = if (isHomePage) {
            doc.select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a").mapNotNull { link ->
                val titleEl = link.selectFirst("h5") ?: return@mapNotNull null
                val title = titleEl.text().trim()
                val poster = link.selectFirst("div.img img")?.attr("src")
                val href = link.attr("href") ?: return@mapNotNull null
                if (!href.contains("episodio")) return@mapNotNull null
                val seriesUrl = convertEpisodeToSeriesUrl(href)
                val epNum = Regex("episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\s*Episodio\\s*\\d+"), "").trim()
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(cleanTitle, seriesUrl) {
                    this.posterUrl = resolveUrl(poster ?: "")
                    addDubStatus(dubstat, epNum)
                }
            }
        } else {
            doc.select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a").mapNotNull { link ->
                val title = link.selectFirst("h5")?.text() ?: return@mapNotNull null
                val poster = link.selectFirst("div.img img")?.attr("src")
                val href = link.attr("href") ?: return@mapNotNull null
                if (href.contains("episodio")) return@mapNotNull null
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, resolveUrl(href)) {
                    this.posterUrl = resolveUrl(poster ?: "")
                    addDubStatus(dubstat)
                }
            }
        }

        val hasNext = doc.select("ul.pagination li a").any { it.attr("href").contains("pag=${page + 1}") }
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun convertEpisodeToSeriesUrl(href: String): String {
        val fullUrl = resolveUrl(href)
        val path = fullUrl.substringAfter(mainUrl).trim('/')
        val regex = Regex("^(.+)-episodio-\\d+$")
        val match = regex.find(path)
        return if (match != null) {
            "$mainUrl/${match.destructured.component1()}/"
        } else {
            fullUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/busquedas/${java.net.URLEncoder.encode(query, "UTF-8")}"
        return app.get(searchUrl, timeout = 120L).document
            .select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a").mapNotNull { link ->
                val title = link.selectFirst("h5")?.text() ?: return@mapNotNull null
                val href = link.attr("href") ?: return@mapNotNull null
                if (href.contains("episodio")) return@mapNotNull null
                val image = link.selectFirst("div.img img")?.attr("src")
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                    this.posterUrl = resolveUrl(image ?: "")
                    addDubStatus(dubstat)
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val seriesUrl = if (url.contains("-episodio-")) {
            convertEpisodeToSeriesUrl(url)
        } else {
            url
        }

        val doc = app.get(seriesUrl, timeout = 120L).document
        val poster = doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.banner-side-serie, div.side-banner div.image")?.attr("style")?.let {
                Regex("background-image:\\s*url\\(['\"]?([^'\")\\s]+)").find(it)?.destructured?.component1()
            }
            ?: ""
        val title = doc.selectFirst("div.ls-title-serie")?.text()
            ?: doc.selectFirst("head meta[property=og:title]")?.attr("content")?.replace(Regex("\\s*[|\\-–].*$"), "")
            ?: ""
        val description = doc.selectFirst("div.text-justify.fc-dark p, div.text-justify.fc-dark")?.text() ?: ""
        val genres = doc.select("a.generos span.label.label-primary.f-bold").map { it.text() }
        val status = when (doc.selectFirst("span.badge.bg-default")?.text()?.trim()) {
            "En emisión", "En Emisión" -> ShowStatus.Ongoing
            "Finalizada" -> ShowStatus.Completed
            else -> null
        }

        val typeInfo = doc.select("div.row div.col-md-6 p.fc-dark, p.fc-dark").map { it.text() }.joinToString(" ")
        val tvType = when {
            typeInfo.contains(Regex("Tipo.*Pel.cula", RegexOption.IGNORE_CASE)) -> TvType.AnimeMovie
            typeInfo.contains(Regex("Tipo.*OVA|Tipo.*Especial", RegexOption.IGNORE_CASE)) -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = ArrayList<Episode>()
        doc.select("div.donghua-list-scroll ul.donghua-list a, ul.donghua-list a").map { epLink ->
            val href = epLink.attr("href")
            val epTitle = epLink.selectFirst("blockquote.message")?.text() ?: ""
            val epNum = Regex("-episodio-(\\d+)").find(href)?.destructured?.component1()?.toIntOrNull()
                ?: Regex("(\\d+)\\s*$").find(epTitle)?.value?.toIntOrNull()
                ?: Regex("-\\s*(\\d+)\\s*$").find(epTitle)?.destructured?.component1()?.toIntOrNull()
            episodes.add(
                newEpisode(resolveUrl(href)) {
                    this.episode = epNum
                    this.name = epTitle
                }
            )
        }

        if (episodes.isEmpty() && tvType == TvType.AnimeMovie) {
            return newMovieLoadResponse(title, seriesUrl, TvType.AnimeMovie, seriesUrl) {
                posterUrl = poster; plot = description; tags = genres
            }
        }

        return newAnimeLoadResponse(title, seriesUrl, tvType) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
            showStatus = status; plot = description; tags = genres
        }
    }

    data class VideoMapJson(
        val asura: String? = null,
        val skadi: String? = null,
        val fembed: String? = null,
        val tape: String? = null,
        val amagi: String? = null,
    )

    // ================================================================
    //  SMART PACKER DECODER
    // ================================================================
    // El sitio usa eval(function(h,u,n,t,e,r){...}(args)) para
    // ocultar VIDEO_MAP_JSON dentro de un document.write().
    //
    // Parámetros (en orden de aparición en la llamada):
    //   h = string codificado (largo)
    //   u = número (NO se usa dentro de la función)
    //   n = charset (string corto, ej "uRfZNtQnw")
    //   t = offset a restar del charCode
    //   e = base para la conversión Y índice del delimitador n[e]
    //   r = número (se sobreescribe con "", NO se usa)
    //
    // El resultado decodificado contiene:
    //   if(timestamp < T){if(hostname === 'seriesdonghua.com'){
    //     document.write('<script>const VIDEO_MAP_JSON={...};...</script>');
    //   }}
    //
    // Los valores del VIDEO_MAP_JSON están doble-codificados:
    //   "asura":"\"k7kfQJoj2LJJEqGt63g\""  → JSON.parse → "k7kfQJoj2LJJEqGt63g"
    //   "skadi":"\"https:\/\/ok.ru\/...\""   → JSON.parse → "https://ok.ru/..."
    //
    // IMPORTANTE: El contenido de document.write('...') contiene comillas
    // simples escapadas (\') que ROMPEN la extracción por regex simple.
    // Se debe usar parsing carácter por carácter.
    // ================================================================

    private fun decodeSmartPacker(
        encodedStr: String, eParam: Int, charset: String, offset: Int
    ): String? {
        if (charset.isEmpty() || eParam < 2 || eParam > 62) return null
        if (eParam >= charset.length) return null

        val delimiter = charset[eParam]
        val standardDigits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val validDigits = standardDigits.substring(0, eParam)

        val tokens = encodedStr.split(delimiter)
        val bytes = ArrayList<Byte>()

        for (token in tokens) {
            if (token.isEmpty()) continue

            // Reemplazar cada char por su índice en el charset
            // (replica exactamente el JS: s=s.replace(new RegExp(n[j],"g"),j))
            var replacedStr = token
            for (j in charset.indices) {
                replacedStr = replacedStr.replace(charset[j].toString(), j.toString())
            }

            // Convertir de base eParam a decimal (mismo método que JS _0xe56c)
            // Solo los caracteres en validDigits contribuyen al valor
            val reversed = replacedStr.reversed()
            var num = 0L
            for ((pos, ch) in reversed.withIndex()) {
                val digitValue = validDigits.indexOf(ch)
                if (digitValue >= 0) {
                    var power = 1L
                    repeat(pos) { power *= eParam }
                    num += digitValue * power
                }
            }

            val charCode = num - offset
            if (charCode < 0 || charCode > 255) return null
            bytes.add(charCode.toByte())
        }

        return try {
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            String(bytes.toByteArray(), Charsets.ISO_8859_1)
        }
    }

    /**
     * Busca y decodifica el eval() ofuscado en el HTML.
     * Mapeo de parámetros del regex:
     *   Grupo 1 = h (string codificado)
     *   Grupo 2 = u (NO usado)
     *   Grupo 3 = n (charset)
     *   Grupo 4 = t (offset)
     *   Grupo 5 = e (base Y delimiter index)
     *   Grupo 6 = r (NO usado)
     */
    private fun decodeObfuscatedScript(html: String): String? {
        val argPatterns = listOf(
            Regex("""\(\s*"([^"]{20,})"\s*,\s*(\d+)\s*,\s*"([^"]{2,20})"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)"""),
            Regex("""\(\s*'([^']{20,})'\s*,\s*(\d+)\s*,\s*'([^']{2,20})'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)"""),
        )

        for (pattern in argPatterns) {
            for (match in pattern.findAll(html)) {
                try {
                    val encodedStr = match.destructured.component1()
                    val charset = match.destructured.component3()
                    val offset = match.destructured.component4().toInt()
                    val eParam = match.destructured.component5().toInt()

                    val decoded = decodeSmartPacker(encodedStr, eParam, charset, offset)
                    if (decoded != null && decoded.contains("VIDEO_MAP_JSON")) {
                        return decoded
                    }
                } catch (_: Exception) { continue }
            }
        }

        return null
    }

    /**
     * Extrae y desescapa el contenido de document.write('...') del string
     * decodificado del Smart Packer.
     *
     * CRÍTICO: Se usa parsing carácter por carácter porque el contenido
     * contiene comillas simples escapadas (\' ) que ROMPEN la extracción
     * por regex (el patrón [^']+ se detiene en la comilla del \').
     *
     * Secuencias de escape procesadas:
     *   \' → '    \" → "    \\ → \    \/ → /    \n → salto de línea
     */
    private fun extractDocumentWriteContent(decodedScript: String): String? {
        val startMarker = "document.write('"
        val startIdx = decodedScript.indexOf(startMarker)
        if (startIdx < 0) return null

        var pos = startIdx + startMarker.length
        val content = StringBuilder()

        while (pos < decodedScript.length) {
            val ch = decodedScript[pos]
            if (ch == '\\' && pos + 1 < decodedScript.length) {
                val nextCh = decodedScript[pos + 1]
                when (nextCh) {
                    '\'' -> content.append('\'')
                    '"'  -> content.append('"')
                    '\\' -> content.append('\\')
                    '/'  -> content.append('/')
                    'n'  -> content.append('\n')
                    'r'  -> content.append('\r')
                    't'  -> content.append('\t')
                    else -> content.append(nextCh)
                }
                pos += 2
            } else if (ch == '\'') {
                // Fin del string de document.write
                break
            } else {
                content.append(ch)
                pos++
            }
        }

        return content.toString()
    }

    /**
     * Extrae un objeto JSON completo usando conteo de llaves.
     * Más robusto que regex porque maneja llaves anidadas y caracteres
     * especiales dentro de strings.
     */
    private fun extractJsonObject(text: String, varName: String = "VIDEO_MAP_JSON"): String? {
        val patterns = listOf(
            Regex("""VIDEO_MAP_JSON\s*=\s*\{"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val braceStart = text.indexOf('{', match.range.first)
            if (braceStart < 0) continue

            var depth = 0
            var inString = false
            var escape = false

            for (i in braceStart until text.length) {
                val c = text[i]
                if (escape) { escape = false; continue }
                if (c == '\\' && inString) { escape = true; continue }
                if (c == '"') { inString = !inString; continue }
                if (inString) continue
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) return text.substring(braceStart, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Extrae el JSON del VIDEO_MAP_JSON desde el script decodificado.
     *
     * Flujo:
     * 1. Extraer contenido de document.write('...') con parsing carácter por
     *    carácter (maneja secuencias de escape como \', \", \\, \/)
     * 2. Extraer JSON con conteo de llaves desde el contenido desescapado
     * 3. Fallback: buscar directamente en el texto decodificado crudo
     */
    private fun extractVideoMapJson(decodedScript: String): String? {
        // PASO 1: Extraer y desescapar contenido de document.write
        val dwContent = extractDocumentWriteContent(decodedScript)

        // PASO 2: Intentar extraer JSON desde el contenido desescapado
        if (dwContent != null) {
            val jsonFromDw = extractJsonObject(dwContent)
            if (jsonFromDw != null) return jsonFromDw
        }

        // PASO 3: Fallback - buscar directamente en el texto decodificado
        // (menos confiable porque las secuencias de escape no están procesadas)
        val jsonFromRaw = extractJsonObject(decodedScript)
        if (jsonFromRaw != null) return jsonFromRaw

        // PASO 4: Último fallback con regex simples
        val searchTargets = mutableListOf<String>()
        if (dwContent != null) searchTargets.add(dwContent)
        searchTargets.add(decodedScript)

        for (target in searchTargets) {
            for (pattern in listOf(
                Regex("""const\s+VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                Regex("""VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                Regex("""VIDEO_MAP_JSON\s*=\s*(\{.*?\})\s*;?"""),
            )) {
                val match = pattern.find(target)
                if (match != null) return match.destructured.component1()
            }
        }

        return null
    }

    /**
     * Decodifica un valor doble-codificado del VIDEO_MAP_JSON.
     *
     * Los valores están envueltos en comillas extras (JSON string dentro
     * de JSON string). El sitio usa JSON.parse(j) para obtener el valor real.
     * Ejemplo:
     *   "asura": "\"k7kfQJoj2LJJEqGt63g\"" → JSON.parse → k7kfQJoj2LJJEqGt63g
     *   "skadi": "\"https:\/\/ok.ru\/...\""  → JSON.parse → https://ok.ru/...
     *
     * Se usa parseJson<String> como método principal (equivale a JSON.parse),
     * con desescapado manual como fallback.
     */
    private fun decodeDoubleEncoded(value: String): String {
        val trimmed = value.trim()
        // Si el valor empieza con comilla, es un JSON string que necesita parseo adicional
        if (trimmed.startsWith("\"")) {
            try {
                return parseJson<String>(trimmed)
            } catch (_: Exception) {}
        }
        // Fallback: desescapado manual
        var result = trimmed
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        result = result.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        return result.trim()
    }

    // ========== loadLinks ==========
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            loadLinksInternal(data, isCasting, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun loadLinksInternal(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, timeout = 120L)
        val doc = response.document
        val html = response.text

        var foundLinks = false

        // ====== PASO 1: Decodificar JavaScript ofuscado ======
        val decodedScript = decodeObfuscatedScript(html)
        val videoMapJsonStr = if (decodedScript != null) {
            extractVideoMapJson(decodedScript)
        } else {
            // Fallback: buscar VIDEO_MAP_JSON directamente (por si no está ofuscado)
            var found: String? = null
            for (script in doc.select("script")) {
                val scriptData = script.data()
                if (scriptData.contains("VIDEO_MAP_JSON")) {
                    found = extractJsonObject(scriptData)
                        ?: try {
                            var result: String? = null
                            for (pattern in listOf(
                                Regex("""const\s+VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                                Regex("""VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                            )) {
                                val match = pattern.find(scriptData)
                                if (match != null) { result = match.destructured.component1(); break }
                            }
                            result
                        } catch (_: Exception) { null }
                    if (found != null) break
                }
            }
            found
        }

        // ====== PASO 2: Parsear y extraer enlaces ======
        if (videoMapJsonStr != null) {
            val videoMap: VideoMapJson? = try {
                parseJson<VideoMapJson>(videoMapJsonStr)
            } catch (_: Exception) { null }

            if (videoMap != null) {
                // Asura → Dailymotion (puede ser ID de video o URL completa)
                videoMap.asura?.let { rawValue ->
                    val decoded = decodeDoubleEncoded(rawValue)
                    val videoId = when {
                        decoded.contains("dailymotion.com") ->
                            Regex("dailymotion\\.com/(?:embed/)?video/([a-zA-Z0-9]+)")
                                .find(decoded)?.destructured?.component1() ?: decoded
                        else -> decoded
                    }
                    if (videoId.isNotEmpty()) {
                        try {
                            foundLinks = extractDailymotion(videoId, data, "Dailymotion", subtitleCallback, callback) || foundLinks
                        } catch (_: Exception) {}
                    }
                }

                // Skadi → ok.ru
                videoMap.skadi?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try { loadExtractor(url, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {
                            try { foundLinks = extractOkRu(url, data, "ok.ru", callback) || foundLinks } catch (_: Exception) {}
                        }
                    }
                }

                // Fembed → Rumble / vid-guard / genérico
                videoMap.fembed?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try { loadExtractor(url, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {
                            try {
                                when {
                                    url.contains("rumble.com") -> foundLinks = extractRumble(url, data, "Rumble", callback) || foundLinks
                                    else -> foundLinks = extractGenericVideo(url, data, "Server", callback) || foundLinks
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                // Tape → Odysee / FileMoon
                videoMap.tape?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try { loadExtractor(url, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {
                            try {
                                when {
                                    url.contains("odysee.com") -> foundLinks = extractOdysee(url, data, "Odysee", callback) || foundLinks
                                    else -> foundLinks = extractGenericVideo(url, data, "Server", callback) || foundLinks
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                // Amagi → Voe.sx
                videoMap.amagi?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        try { loadExtractor(url, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {
                            try { foundLinks = extractVoe(url, data, "Voe", callback) || foundLinks } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        // Fallback 1: iframes
        if (!foundLinks) {
            doc.select("iframe").amap { iframe ->
                val src = listOf("src", "data-src").map { iframe.attr(it).trim() }.firstOrNull { it.isNotBlank() }
                if (src != null) {
                    val fullSrc = resolveUrl(src)
                    if (fullSrc.startsWith("http")) {
                        try { loadExtractor(fullSrc, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {}
                    }
                }
            }
        }

        // Fallback 2: URLs de video en el HTML
        if (!foundLinks) {
            for (pattern in listOf(
                Regex("""(https?://[^"'\s<>]*dailymotion\.com/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*ok\.ru/videoembed/[^"'\s<>]+)"""),
                Regex("""(https?://[^"'\s<>]*voe\.sx/e/[^"'\s<>]+)"""),
            )) {
                for (match in pattern.findAll(html)) {
                    try { loadExtractor(match.value, data, subtitleCallback, callback); foundLinks = true } catch (_: Exception) {}
                }
                if (foundLinks) break
            }
        }

        return foundLinks
    }

    // ========== Extractores ==========

    private suspend fun extractDailymotion(
        videoId: String, referer: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1) loadExtractor con URL completa
        try { loadExtractor("https://www.dailymotion.com/embed/video/$videoId", referer, subtitleCallback, callback); return true } catch (_: Exception) {}
        // 2) API metadata
        try {
            val jsonText = app.get("https://www.dailymotion.com/player/metadata/video/$videoId",
                referer = "https://www.dailymotion.com/embed/video/$videoId",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(jsonText)) {
                try { generateM3u8(serverName, m.value, "https://www.dailymotion.com").forEach(callback); return true } catch (_: Exception) {}
            }
            val mp4s = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(jsonText).map { it.value }.distinct().toList()
            if (mp4s.isNotEmpty()) {
                for (url in mp4s) {
                    val q = when { url.contains("1080") -> Qualities.P1080.value; url.contains("720") -> Qualities.P720.value; url.contains("480") -> Qualities.P480.value; else -> Qualities.Unknown.value }
                    callback(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = url) { this.referer = "https://www.dailymotion.com"; this.quality = q })
                }
                return true
            }
        } catch (_: Exception) {}
        // 3) Scrape embed page
        try {
            val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
            val embedHtml = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(embedHtml)) {
                try { generateM3u8(serverName, m.value, embedUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(embedHtml)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) { this.referer = embedUrl; this.quality = Qualities.Unknown.value })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractOkRu(videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val html = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            val dataMatch = Regex("""data-options="([^"]+)"""").find(html)
            if (dataMatch != null) {
                val optionsJson = dataMatch.destructured.component1().replace("&quot;", "\"").replace("&amp;", "&")
                for (m in Regex("""(https?://[^"]+\.(?:mp4|m3u8)[^"]*)""").findAll(optionsJson)) {
                    callback(newExtractorLink(source = serverName, name = serverName, url = m.value) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                    return true
                }
            }
            Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""").find(html)?.let { m ->
                callback(newExtractorLink(source = serverName, name = serverName, url = m.destructured.component1()) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                return true
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractRumble(embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val html = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 30L).text
            val jsonMatch = Regex(""""ua":\s*\{[^}]*"mp4":\s*\[([^\]]+)\]""").find(html)
            if (jsonMatch != null) {
                val mp4Array = jsonMatch.destructured.component1()
                var found = false
                Regex(""""(https?://[^"]+\.mp4[^"]*)"""").findAll(mp4Array).forEach { match ->
                    val url = match.destructured.component1()
                    val q = when { url.contains("1080") -> Qualities.P1080.value; url.contains("720") -> Qualities.P720.value; url.contains("480") -> Qualities.P480.value; else -> Qualities.Unknown.value }
                    callback(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = url) { this.referer = referer; this.quality = q })
                    found = true
                }
                if (found) return true
            }
            for (pattern in listOf(Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""), Regex("""(https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?)"""))) {
                pattern.find(html)?.let { match ->
                    try { generateM3u8(serverName, match.destructured.component1(), referer).forEach(callback); return true } catch (_: Exception) {}
                }
            }
            for (pattern in listOf(Regex("""["'](https?://[^"']+?rmbl\.ws[^"']*?\.mp4[^"']*)["']"""), Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""))) {
                pattern.find(html)?.let { match ->
                    callback(newExtractorLink(source = serverName, name = serverName, url = match.destructured.component1()) { this.referer = referer; this.quality = Qualities.Unknown.value })
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractOdysee(embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val streamUrl = embedUrl.replace("/$/embed/", "/$/stream/").replace("/embed/", "/stream/")
            callback(newExtractorLink(source = serverName, name = serverName, url = streamUrl) { this.referer = referer; this.quality = Qualities.Unknown.value })
            return true
        } catch (_: Exception) {}
        try {
            val html = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) { this.referer = embedUrl; this.quality = Qualities.Unknown.value })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractVoe(videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val html = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(html)) {
                try { generateM3u8(serverName, m.value, videoUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                return true
            }
            for (m in Regex("""(?:source|src|url|hls)["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
                val url = m.destructured.component1()
                if (url.startsWith("http") && (url.contains(".m3u8") || url.contains(".mp4"))) {
                    if (url.contains(".m3u8")) { try { generateM3u8(serverName, url, videoUrl).forEach(callback); return true } catch (_: Exception) {} }
                    else { callback(newExtractorLink(source = serverName, name = serverName, url = url) { this.referer = videoUrl; this.quality = Qualities.Unknown.value }); return true }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractGenericVideo(videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val text = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(text)) {
                try { generateM3u8(serverName, m.value, videoUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(text)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                return true
            }
        } catch (_: Exception) {}
        return false
    }
}
