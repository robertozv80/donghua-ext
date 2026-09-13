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

    // FIX: Excluir items de "Donghua más Vistos" de los resultados de búsqueda
    // Los items de "Más Vistos" están dentro del mismo contenedor que los resultados
    // pero aparecen DESPUÉS del texto "Tenemos un problema" o del encabezado "Donghua más Vistos"
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/busquedas/${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(searchUrl, timeout = 120L).document

        // Buscar el contenedor principal de resultados (col-md-9)
        val mainContent = doc.selectFirst("div.col-md-9") ?: doc

        // Si la página dice "Tenemos un problema", NO hay resultados reales
        // Los items después de ese mensaje son "Más Vistos" (no resultados de búsqueda)
        val hasNoResults = mainContent.select("h1, h2").any {
            it.text().contains("Tenemos un problema") || it.text().contains("Aún no Contamos")
        }

        if (hasNoResults) {
            // No hay resultados reales, no incluir los "Más Vistos"
            return emptyList()
        }

        // Hay resultados reales: parsear solo los items ANTES del encabezado "Más Vistos"
        val results = ArrayList<SearchResponse>()
        val allItems = mainContent.select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a")

        for (link in allItems) {
            // Verificar si llegamos al encabezado "Donghua más Vistos"
            // Los items después de ese encabezado no son resultados de búsqueda
            val parentItem = link.parent()
            val prevHeading = parentItem?.previousElementSiblings()?.select("h4, h3")?.firstOrNull {
                it.text().contains("Vistos", ignoreCase = true) || it.text().contains("Populares", ignoreCase = true)
            }
            if (prevHeading != null) break

            val title = link.selectFirst("h5")?.text() ?: continue
            val href = link.attr("href") ?: continue
            if (href.contains("episodio")) continue
            val image = link.selectFirst("div.img img")?.attr("src")
            val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
            results.add(newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                this.posterUrl = resolveUrl(image ?: "")
                addDubStatus(dubstat)
            })
        }

        return results
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

        // ===== v22.2 Recomendaciones =====
        // NOTA del usuario: primero otras temporadas del mismo nombre, luego
        // similares aleatorios (máx 10).
        val genreHrefs = doc.select("a.generos").map { it.attr("href") }
        val recommendations = fetchSeriesDonghuaRecommendations(seriesUrl, title, genreHrefs)

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
                if (recommendations.isNotEmpty()) this.recommendations = recommendations
            }
        }

        return newAnimeLoadResponse(title, seriesUrl, tvType) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
            showStatus = status; plot = description; tags = genres
            if (recommendations.isNotEmpty()) this.recommendations = recommendations
        }
    }

    /** v22.2: normaliza un título para comparar bases (minúsculas, sin acentos). */
    private fun sdNormalize(t: String): String = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * v22.2: recomendaciones con la NOTA del usuario:
     * 1) Otras temporadas del mismo nombre (misma base de slug, p.ej.
     *    "jade-dynasty-4" -> base "jade-dynasty") usando el buscador del sitio;
     * 2) El resto: títulos del home (aleatorio). Máximo 10 resultados.
     * Si no hay temporadas del mismo nombre, solo similares aleatorios.
     */
    private suspend fun fetchSeriesDonghuaRecommendations(
        seriesUrl: String,
        seriesTitle: String,
        genreHrefs: List<String> = emptyList()
    ): List<SearchResponse> {
        return try {
            val all = ArrayList<SearchResponse>()
            val seen = mutableSetOf(seriesUrl)

            // Base del slug: /donghua/jade-dynasty-4 -> "jade-dynasty"
            val selfSlug = seriesUrl.substringAfterLast("/")
            val baseSlug = Regex("-\\d+$").replace(selfSlug, "")
            val norm = sdNormalize(seriesTitle)
            val baseNorm = Regex("\\s+\\d+$").replace(norm, "").trim()

            // v22.5 FIX: el título puede traer sufijo de temporada ("Tales Of Demons and
            // Gods Season10", "Doupo Cangqiong Temporada 3"). Buscar con ese sufijo
            // adjunto devuelve resultados NO relacionados (verificado: "...gods season"
            // devuelve Combat Continent, Martial Master, etc.). Quitarlo antes.
            val titleNoSeason = Regex("\\s*(temporada|season|s)\\s*\\d+$", RegexOption.IGNORE_CASE)
                .replace(seriesTitle, "").trim()
                .ifBlank { Regex("\\s+\\d+$").replace(seriesTitle, "").trim() }
            val normNoSeason = if (titleNoSeason != seriesTitle) sdNormalize(titleNoSeason) else ""

            if (baseSlug.isNotBlank() && baseSlug != selfSlug || baseNorm.isNotBlank() && baseNorm != norm || normNoSeason.isNotBlank() && normNoSeason != norm) {
                try {
                    // v22.5: probar varias variantes de consulta hasta que una devuelva
                    // temporadas del mismo nombre (antes solo "tales+of+demons+and+gods
                    // +season10" que no matcheaba nada).
                    val queries = LinkedHashSet<String>()
                    if (normNoSeason.isNotBlank() && normNoSeason != norm) queries.add(normNoSeason)
                    if (baseNorm.isNotBlank()) queries.add(baseNorm)
                    if (queries.isEmpty()) queries.add(baseSlug.replace("-", " "))
                    var searchDoc: org.jsoup.nodes.Document? = null
                    var usedQ = ""
                    for (q in queries) {
                        try {
                            val d = app.get(
                                "$mainUrl/busquedas/${java.net.URLEncoder.encode(q, "UTF-8")}",
                                timeout = 120L
                            ).document
                            val hasMatch = d.select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a").any { link ->
                                val t = link.selectFirst("h5")?.text()?.trim() ?: ""
                                val href = link.attr("href")
                                !href.contains("episodio") &&
                                    (sdNormalize(t).startsWith(normNoSeason.ifBlank { baseNorm }) ||
                                     href.substringAfterLast("/").startsWith(baseSlug))
                            }
                            if (hasMatch || searchDoc == null) {
                                searchDoc = d
                                usedQ = q
                            }
                            if (hasMatch) break
                        } catch (_: Exception) {}
                    }
                    if (searchDoc != null) {
                        val mainDoc = searchDoc
                        val mainContent = mainDoc.selectFirst("div.col-md-9") ?: mainDoc
                        val seasons = ArrayList<Triple<String, String, String>>() // url, title, poster
                        mainContent.select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a").forEach { link ->
                            val href = link.attr("href")
                            if (href.contains("episodio")) return@forEach
                            val fullHref = resolveUrl(href)
                            if (fullHref in seen) return@forEach
                            val slug = fullHref.substringAfterLast("/")
                            val t = link.selectFirst("h5")?.text()?.trim() ?: return@forEach
                            val isSameBase = slug.startsWith(baseSlug) ||
                                (baseNorm.isNotBlank() && sdNormalize(t).startsWith(baseNorm)) ||
                                (normNoSeason.isNotBlank() && normNoSeason != norm && sdNormalize(t).startsWith(normNoSeason))
                            if (!isSameBase) return@forEach
                            seen.add(fullHref)
                            seasons.add(Triple(fullHref, t, link.selectFirst("div.img img")?.attr("src") ?: ""))
                        }
                        seasons.sortBy { Regex("(\\d+)$").find(it.first.substringAfterLast("/"))?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
                        seasons.forEach { (u, t, p) ->
                            all.add(newAnimeSearchResponse(t, u) { this.posterUrl = resolveUrl(p) })
                        }
                    }
                } catch (_: Exception) {}
            }

            // v22.3: similares por CATEGORÍA — elegir un género aleatorio de la ficha
            // (/lucha/, /artes-marciales/, ...) y tomar títulos de esa página.
            if (all.size < 10 && genreHrefs.isNotEmpty()) {
                try {
                    val genrePath = genreHrefs.random().let {
                        if (it.startsWith("http")) it.substringAfter(mainUrl, it) else it
                    }
                    if (genrePath.isNotBlank() && genrePath != "/") {
                        val genreDoc = app.get(resolveUrl(genrePath), timeout = 120L).document
                        val poolG = ArrayList<SearchResponse>()
                        genreDoc.select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a").forEach { link ->
                            if (poolG.size >= 40) return@forEach
                            val href = link.attr("href")
                            if (href.contains("episodio")) return@forEach
                            val fullHref = resolveUrl(href)
                            if (fullHref in seen) return@forEach
                            val t = link.selectFirst("h5")?.text()?.trim() ?: return@forEach
                            seen.add(fullHref)
                            poolG.add(newAnimeSearchResponse(t, fullHref) {
                                this.posterUrl = resolveUrl(link.selectFirst("div.img img")?.attr("src") ?: "")
                            })
                        }
                        poolG.shuffle()
                        all.addAll(poolG)
                    }
                } catch (_: Exception) {}
            }

            // Similares aleatorios desde el home (relleno si la categoría no alcanzó)
            if (all.size < 10) try {
                val homeDoc = app.get("$mainUrl/", timeout = 120L).document
                val pool = ArrayList<SearchResponse>()
                homeDoc.select("div.item a.angled-img, div.item.col-lg-3 a, div.item.col-lg-2 a").forEach { link ->
                    if (pool.size >= 60) return@forEach
                    val href = link.attr("href")
                    if (href.contains("episodio")) return@forEach
                    val fullHref = resolveUrl(href)
                    if (fullHref in seen) return@forEach
                    val recTitle = link.selectFirst("h5")?.text()?.trim() ?: return@forEach
                    seen.add(fullHref)
                    val poster = link.selectFirst("div.img img")?.attr("src")
                    pool.add(newAnimeSearchResponse(recTitle, fullHref) {
                        this.posterUrl = resolveUrl(poster ?: "")
                    })
                }
                pool.shuffle()
                all.addAll(pool)
            } catch (_: Exception) {}
            all.take(10)
        } catch (_: Exception) {
            emptyList()
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
    //  DECODIFICADOR DEL JAVASCRIPT OFUSCADO (Smart Packer)
    // ================================================================
    private fun decodeSmartPacker(
        encodedStr: String, eParam: Int, charset: String, offset: Int
    ): String? {
        if (charset.isEmpty() || eParam < 2 || eParam > 62) return null
        if (eParam >= charset.length) return null

        val delimiter = charset[eParam]
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val hDigits = digits.substring(0, eParam)

        val tokens = encodedStr.split(delimiter)
        val bytes = ArrayList<Byte>()

        for (token in tokens) {
            if (token.isEmpty()) continue

            var digitStr = ""
            for (c in token) {
                val idx = charset.indexOf(c)
                if (idx < 0) return null
                digitStr += idx.toString()
            }

            val reversed = digitStr.reversed()
            var num = 0L
            for ((pos, ch) in reversed.withIndex()) {
                val idx = hDigits.indexOf(ch)
                if (idx >= 0) {
                    var power = 1L
                    repeat(pos) { power *= eParam }
                    num += idx * power
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

    private fun extractVideoMapJson(decodedScript: String): String? {
        val patterns = listOf(
            Regex("""const\s+VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
            Regex("""VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
            Regex("""VIDEO_MAP_JSON\s*=\s*(\{.*?\})\s*;?"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(decodedScript)
            if (match != null) {
                val rawJson = match.destructured.component1()
                // FIX: El script decodificado está envuelto en document.write('<script>...</script>')
                // Por eso el JSON contiene secuencias de escape adicionales (\\\" → \", \\/ → \/).
                // Hay que desescapar antes de que parseJson pueda procesarlo.
                val unescaped = rawJson
                    .replace("\\\\", "\u0000")  // proteger \\ temporalmente
                    .replace("\\\"", "\"")       // \" → "
                    .replace("\\/", "/")         // \/ → /
                    .replace("\u0000", "\\")     // restaurar \\ → \
                return unescaped
            }
        }
        return null
    }

    // ========== loadLinks ==========
    override suspend fun loadLinks(
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
            // Fallback: buscar VIDEO_MAP_JSON directamente (script no ofuscado)
            var found: String? = null
            for (script in doc.select("script")) {
                val scriptData = script.data()
                if (scriptData.contains("VIDEO_MAP_JSON")) {
                    for (pattern in listOf(
                        Regex("""const\s+VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                        Regex("""VIDEO_MAP_JSON\s*=\s*(\{[^;]+\})\s*;"""),
                    )) {
                        val match = pattern.find(scriptData)
                        if (match != null) {
                            val rawJson = match.destructured.component1()
                            // Aplicar el mismo desescape por si el script también está envuelto
                            found = rawJson
                                .replace("\\\\", "\u0000")
                                .replace("\\\"", "\"")
                                .replace("\\/", "/")
                                .replace("\u0000", "\\")
                            break
                        }
                    }
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
                // Asura → Dailymotion (el valor es un ID de video)
                videoMap.asura?.let { rawValue ->
                    val videoId = decodeDoubleEncoded(rawValue)
                    if (videoId.isNotEmpty()) {
                        foundLinks = extractDailymotion(videoId, data, "Dailymotion", subtitleCallback, callback) || foundLinks
                    }
                }

                // Skadi → ok.ru — v22.5: contar enlaces emitidos, no solo "no-excepción".
                // loadExtractor(ok.ru) puede retornar sin enlaces (HTTP 302 del endpoint de
                // metadata) y al marcar foundLinks=true bloqueaba el fallback propio.
                videoMap.skadi?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        var links = 0
                        try { loadExtractor(url, data, subtitleCallback, callback) } catch (_: Exception) {}
                        try {
                            extractOkRu(url, data, serverName = "Ok", callback = { links++; callback(it) })
                        } catch (_: Exception) {}
                        if (links > 0) foundLinks = true
                    }
                }

                // Fembed → Strsb (likessb.com, detrás de parklogic) / genérico — v22.5:
                // contar enlaces emitidos; likessb devuelve una página de redirección
                // publicitaria y loadExtractor "tiene éxito" sin emitir nada.
                videoMap.fembed?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        var links = 0
                        try { loadExtractor(url, data, subtitleCallback, callback) } catch (_: Exception) {}
                        if (url.contains("rumble.com")) {
                            try { extractRumble(url, data, "Rumble") { links++; callback(it) } } catch (_: Exception) {}
                        } else {
                            try { extractGenericVideo(url, data, "Strsb") { links++; callback(it) } } catch (_: Exception) {}
                        }
                        if (links > 0) foundLinks = true
                    }
                }

                // Tape → Odysee — v22.5: contar enlaces emitidos
                videoMap.tape?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        var links = 0
                        try { loadExtractor(url, data, subtitleCallback, callback) } catch (_: Exception) {}
                        try { extractOdysee(url, data, "Odysee") { links++; callback(it) } } catch (_: Exception) {}
                        if (links > 0) foundLinks = true
                    }
                }

                // Amagi → Voe.sx — v22.5: contar enlaces emitidos
                videoMap.amagi?.let { rawValue ->
                    val url = decodeDoubleEncoded(rawValue)
                    if (url.startsWith("http")) {
                        var links = 0
                        try { loadExtractor(url, data, subtitleCallback, callback) } catch (_: Exception) {}
                        try { extractVoe(url, data, "Voe") { links++; callback(it) } } catch (_: Exception) {}
                        if (links > 0) foundLinks = true
                    }
                }
            }
        }

        // Fallback 1: iframes
        if (!foundLinks) {
            doc.select("iframe").forEach { iframe ->
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

    // ========== Decodificador de valores doble-encoded ==========
    private fun decodeDoubleEncoded(value: String): String {
        var result = value.trim()
        if (result.startsWith("\"") && result.endsWith("\"")) result = result.substring(1, result.length - 1)
        result = result.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
        if (result.startsWith("\"") && result.endsWith("\"")) result = result.substring(1, result.length - 1)
        return result.trim()
    }

    // ========== Extractores ==========

    private suspend fun extractDailymotion(
        videoId: String, referer: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
        val metaUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"

        // 0) Pre-warm session cookies — Dailymotion requires the v1st cookie to be set
        //    before the metadata API is called. Otherwise the returned cdndirector m3u8 URL
        //    contains a `dmV1st` token that doesn't match any session cookie, and the
        //    cdndirector server returns HTTP 403 (player shows ERROR_CODE_IO_BAD_HTTP_STATUS 2004).
        //    Visiting the embed page first sets the v1st cookie in CloudStream's session,
        //    so subsequent metadata + m3u8 requests carry the matching cookie.
        try {
            app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L)
        } catch (_: Exception) {}

        // 1) loadExtractor — try CloudStream's native Dailymotion extractor first
        try { loadExtractor(embedUrl, referer, subtitleCallback, callback); return true } catch (_: Exception) {}

        // 2) API metadata — parse JSON properly to extract qualities.auto[*].url
        //    Skip advertising.ad_url (returns VMAP XML / empty / JS, not real m3u8).
        //    Skip URLs with placeholder tokens ([APIFRAMEWORKS], [VASTVERSIONS], etc.) —
        //    those are template strings the player JS replaces; sending them as-is returns 4xx.
        try {
            val jsonText = app.get(metaUrl,
                referer = embedUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                timeout = 15L).text

            // When called with cookies, Dailymotion returns JSON with `\/` escape sequences
            // (e.g., `https:\/\/cdndirector...`). Normalize them so the regex matches.
            val normalizedJson = jsonText.replace("\\/", "/")

            // Strategy A: regex-extract the qualities.auto[*].url directly (most reliable).
            // Matches: "qualities":{"auto":[{"type":"...","url":"...m3u8..."}]}
            val qualitiesUrlPattern = Regex(
                """"qualities"\s*:\s*\{\s*"auto"\s*:\s*\[\s*\{[^}]*?"url"\s*:\s*"([^"]+\.m3u8[^"]*)""""
            )
            qualitiesUrlPattern.find(normalizedJson)?.let { match ->
                val url = match.destructured.component1()
                try {
                    generateM3u8(serverName, url, embedUrl).forEach(callback)
                    return true
                } catch (_: Exception) {}
            }

            // Strategy B: regex-search all m3u8 URLs, skip advertising / placeholder ones.
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(normalizedJson)) {
                val u = m.value
                // Skip advertising CDN (returns VMAP XML / empty body / JS, not m3u8).
                if (u.contains("dmxleo.dailymotion.com")) continue
                // Skip URLs with unresolved template placeholders.
                if (u.contains("[APIFRAMEWORKS]") || u.contains("[VASTVERSIONS]") ||
                    u.contains("[MEDIAMIME]") || u.contains("[PLAYBACKMETHODS]") ||
                    u.contains("[ERRORCODE]")) continue
                try { generateM3u8(serverName, u, embedUrl).forEach(callback); return true } catch (_: Exception) {}
            }

            // Strategy C: mp4 fallback (rare for current Dailymotion, but kept for safety).
            val mp4s = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(normalizedJson)
                .map { it.value }.distinct().toList()
            if (mp4s.isNotEmpty()) {
                for (url in mp4s) {
                    val q = when {
                        url.contains("1080") -> Qualities.P1080.value
                        url.contains("720") -> Qualities.P720.value
                        url.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                    callback(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = url) {
                        this.referer = embedUrl; this.quality = q
                    })
                }
                return true
            }
        } catch (_: Exception) {}

        // 3) Scrape embed page (last resort — embed HTML rarely contains direct URLs these days).
        try {
            val embedHtml = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(embedHtml)) {
                val u = m.value
                if (u.contains("dmxleo.dailymotion.com")) continue
                try { generateM3u8(serverName, u, embedUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(embedHtml)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) {
                    this.referer = embedUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * v22.5 FIX: extractor propio de ok.ru reescrito.
     * - La página embed trae TODO en data-options: hlsManifestUrl (m3u8) y videos[] (mp4 por calidad).
     * - El JSON interno usa escapes \u0026 para '&' → desescapar o las URLs quedan rotas.
     * - hlsManifestUrl se entrega via generateM3u8 (multi-calidad), igual que el player web.
     */
    private suspend fun extractOkRu(videoUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val html = app.get(videoUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            val dataMatch = Regex("""data-options="([^"]+)"""").find(html)
            if (dataMatch != null) {
                val optionsJson = dataMatch.destructured.component1()
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("\\u0026", "&")
                    .replace("\\u002F", "/")
                    .replace("\\/", "/")
                var found = false
                // 1) HLS manifest (multi-calidad)
                Regex("""hlsManifestUrl\"?\s*:\s*\"?([^\",]+)""").find(optionsJson)?.let { hm ->
                    val m3u8 = hm.destructured.component1().trim()
                    if (m3u8.startsWith("http")) {
                        try {
                            generateM3u8(serverName, m3u8, "https://ok.ru").forEach(callback)
                            found = true
                        } catch (_: Exception) {}
                    }
                }
                // 2) videos[] con mp4 por calidad ("name":"1080"|"720"|"mobile"...)
                Regex("""\{[^{}]*?\"name\"\s*:\s*\"([^\"]+)\"[^{}]*?\"url\"\s*:\s*\"([^\"]+)\"[^{}]*?\}""").findAll(optionsJson).forEach { vm ->
                    val qName = vm.destructured.component1()
                    val vUrl = vm.destructured.component2()
                    if (vUrl.startsWith("http")) {
                        val q = when {
                            qName.contains("1080") -> Qualities.P1080.value
                            qName.contains("720") -> Qualities.P720.value
                            qName.contains("480") -> Qualities.P480.value
                            qName.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        callback(newExtractorLink(source = serverName, name = "$serverName ${q / 1000}p", url = vUrl) {
                            this.referer = "https://ok.ru"
                            this.quality = q
                        })
                        found = true
                    }
                }
                if (found) return true
                // 3) Fallback: cualquier mp4/m3u8 dentro de data-options
                for (m in Regex("""(https?://[^\"]+\.(?:mp4|m3u8)[^\"]*)""").findAll(optionsJson)) {
                    callback(newExtractorLink(source = serverName, name = serverName, url = m.value) { this.referer = "https://ok.ru"; this.quality = Qualities.Unknown.value })
                    return true
                }
            }
            Regex("""<meta\s+property=[\"']og:video(?::url)?[\"']\s+content=[\"']([^\"']+)[\"']""").find(html)?.let { m ->
                callback(newExtractorLink(source = serverName, name = serverName, url = m.destructured.component1()) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                return true
            }
            for (m in Regex("""(https?://[^\"'\s<>]+\.(?:mp4|m3u8)[^\"'\s<>]*)""").findAll(html)) {
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) { this.referer = videoUrl; this.quality = Qualities.Unknown.value })
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * FIX: Extractor Rumble mejorado con múltiples fallbacks progresivos
     * 1. JSON block con "ua"/"mp4"
     * 2. URLs CDN rmbl.ws
     * 3. URLs m3u8 (HLS)
     * 4. URLs mp4 genéricas
     * 5. og:video meta tag
     */
    private suspend fun extractRumble(embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val html = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 30L).text

            // ===== Método 1: Buscar JSON de configuración =====
            val jsonPatterns = listOf(
                Regex("""\"ua\"\s*:\s*\{[^}]*\"mp4\"\s*:\s*\[([^\]]+)\]"""),
                Regex("""\"mp4\"\s*:\s*\[([^\]]+)\]"""),
                Regex("""\\"ua\\"\s*:\s*\\\{[^\\}]*\\"mp4\\"\s*:\s*\\\[([^\\\]]+)\\\]"""),
            )
            for (pattern in jsonPatterns) {
                val jsonMatch = pattern.find(html)
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
            }

            // ===== Método 2: Buscar URLs CDN rmbl.ws =====
            val rmblPatterns = listOf(
                Regex("""["'](https?://[^"']*rmbl\.ws[^"']*\.mp4[^"']*)["']"""),
                Regex("""(https?://[^\s"'<>]*rmbl\.ws[^\s"'<>]*\.mp4[^\s"'<>]*)"""),
                Regex("""["'](https?://[^"']*rmbl\.ws[^"']*)["']"""),
            )
            for (pattern in rmblPatterns) {
                val matches = pattern.findAll(html).toList()
                if (matches.isNotEmpty()) {
                    for (match in matches) {
                        val url = match.destructured.component1()
                        val q = when { url.contains("1080") -> Qualities.P1080.value; url.contains("720") -> Qualities.P720.value; url.contains("480") -> Qualities.P480.value; else -> Qualities.Unknown.value }
                        callback(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = url) { this.referer = referer; this.quality = q })
                    }
                    return true
                }
            }

            // ===== Método 3: URLs m3u8 =====
            for (pattern in listOf(Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""), Regex("""(https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?)"""))) {
                pattern.find(html)?.let { match ->
                    try { generateM3u8(serverName, match.destructured.component1(), referer).forEach(callback); return true } catch (_: Exception) {}
                }
            }

            // ===== Método 4: URLs mp4 genéricas =====
            for (pattern in listOf(Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""), Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)"""))) {
                pattern.find(html)?.let { match ->
                    callback(newExtractorLink(source = serverName, name = serverName, url = match.destructured.component1()) { this.referer = referer; this.quality = Qualities.Unknown.value })
                    return true
                }
            }

            // ===== Método 5: og:video meta tag =====
            Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""").find(html)?.let { match ->
                callback(newExtractorLink(source = serverName, name = serverName, url = match.destructured.component1()) { this.referer = referer; this.quality = Qualities.Unknown.value })
                return true
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
