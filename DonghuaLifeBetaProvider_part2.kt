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
            // Si episodeNumbers está disponible (extraído de initialEpisodes[]), usar esos
            // números reales — algunas series tienen saltos (ej: 10, 106, 566 en Supreme God Emperor).
            // Si está vacío, asumir secuenciales: firstEpNumber..firstEpNumber+episodeCount-1.
            val epNumbersToUse = if (season.episodeNumbers.isNotEmpty()) {
                season.episodeNumbers
            } else {
                val start = season.firstEpNumber.takeIf { it > 0 } ?: 1
                (0 until episodeCount).map { start + it }
            }

            for (epNum in epNumbersToUse) {
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

        // Agregar temporadas especiales como season 0
        for (season in specialSeasons) {
            val seasonSlug = season.slug
            val episodeCount = season.episodeCount
            val epNumbersToUse = if (season.episodeNumbers.isNotEmpty()) {
                season.episodeNumbers
            } else {
                val start = season.firstEpNumber.takeIf { it > 0 } ?: 1
                (0 until episodeCount).map { start + it }
            }
            for (epNum in epNumbersToUse) {
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

            // Buscar TODOS los números de episodio en initialEpisodes[] de esta temporada.
            // Estructura: ..."initialEpisodes":[{"id":"...","title":"...","number":N,...},{"id":"...","number":M,...}]
            // initialEpisodes[] puede estar truncado (los primeros N episodios), pero los
            // números pueden NO ser secuenciales (ej: Supreme God Emperor tiene 10, 106, 566).
            // Para estas series con saltos, es crítico usar los números reales en vez de
            // asumir startEpNum..startEpNum+N-1.
            val nextSeasonStart = seasonsArrayStr.indexOf(
                "\"slug\":\"", m.range.last
            ).let { if (it < 0 || it <= m.range.first) seasonsArrayStr.length else it }
            val seasonBlock = seasonsArrayStr.substring(m.range.first, nextSeasonStart)
            val initialEpStart = seasonBlock.find("\"initialEpisodes\":[")
            val episodeNumbers = if (initialEpStart >= 0) {
                val searchStart = initialEpStart + "\"initialEpisodes\":[".length
                // Buscar el cierre del array initialEpisodes
                var depth = 0
                var endIdx = searchStart
                var j = searchStart
                while (j < seasonBlock.length) {
                    when (seasonBlock[j]) {
                        '[' -> depth++
                        ']' -> { if (depth == 0) { endIdx = j; break } else depth-- }
                    }
                    j++
                }
                val initialEpArrayStr = seasonBlock.substring(searchStart, endIdx)
                // Extraer TODOS los "number":N en orden
                Regex(""""number":(\d+)""")
                    .findAll(initialEpArrayStr)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .toList()
            } else emptyList()
            val firstEpNumber = episodeNumbers.firstOrNull() ?: 0

            seasons.add(
                SeasonMeta(
                    slug = slug,
                    label = label,
                    episodeCount = episodeCount,
                    isSpecial = isSpecial,
                    firstEpNumber = firstEpNumber,
                    episodeNumbers = episodeNumbers,
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

        var response = app.get(cleanUrl, headers = browserHeaders, timeout = 60)
        var html = response.text
        var rscPayload = extractRscPayload(html)
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
        // v15 OPTIMIZATION: Si el RSC está reducido (bot detection), NO hacer retries ni
        // JS_ANALYZE automático — el log histórico muestra que:
        //   - Los 2 retries de 3s NUNCA recuperan el RSC completo (siempre vuelve reducido).
        //     Esto es porque la bot detection es determinística basada en TLS fingerprint,
        //     no rate-limiting. Esperar no cambia nada. → Perdíamos 6s por episodio.
        //   - JS_ANALYZE nunca ha encontrado la key AES — todas las keys probadas fallan.
        //     Sirve solo para diagnóstico, no para producción. → Perdíamos 7s por episodio.
        // En su lugar, ir DIRECTO al WebView, que es la única estrategia que funciona.
        if (rscPayload.isNotEmpty() && rscPayload.length < 50000) {
            Log.i(TAG, "loadLinks BOT_DETECTED: RSC reduced (${rscPayload.length} chars), " +
                "skipping retries and JS_ANALYZE, going directly to WebView fallback")
            // Solo loguear el RSC_DUMP si está en modo debug verbose (no siempre, para no spamear)
        }

        // v14: Última estrategia — usar WebViewResolver para renderizar la página en
        // un motor Chrome real que ejecuta JS como un navegador verdadero.
        // Esto bypassa la bot detection que reduce el RSC y devuelve MOCK URLs.
        // El WebView ejecuta el JS de Next.js, que hace fetch() autenticado al
        // backend y recibe URLs reales (Dailymotion, Rumble, etc.).
        var webViewCaptured: String = ""
        if (rscPayload.isNotEmpty() && rscPayload.length < 50000) {
            val webViewResult = tryWebViewResolver(cleanUrl, "loadLinks")
            if (webViewResult != null) {
                val (renderedHtml, capturedJson) = webViewResult
                webViewCaptured = capturedJson
                // Intentar extraer RSC del HTML renderizado por WebView
                val webViewRsc = extractRscPayload(renderedHtml)
                Log.i(TAG, "loadLinks v14 WEBVIEW RSC: rscLen=${webViewRsc.length} " +
                    "hasActiveEpId=${webViewRsc.contains("\"activeEpisodeId\":")} " +
                    "hasSources=${webViewRsc.contains("\"sources\":[")}")
                // Si el RSC del WebView es más completo, usarlo
                if (webViewRsc.length > rscPayload.length) {
                    val oldRscLen = rscPayload.length
                    html = renderedHtml
                    rscPayload = webViewRsc
                    Log.i(TAG, "loadLinks v14 WEBVIEW: using WebView RSC " +
                        "(was $oldRscLen, now ${webViewRsc.length})")
                }
                // Si capturedJson tiene next_f, concatenarlo al RSC (puede tener servers[] reales)
                if (capturedJson.isNotEmpty()) {
                    try {
                        val captured = parseJson<CapturedWebViewData>(capturedJson)
                        val nextF = captured.next_f ?: ""
                        if (nextF.length > 1000) {
                            Log.i(TAG, "loadLinks v14 WEBVIEW: appending captured next_f " +
                                "(len=${nextF.length}) to rscPayload")
                            // El next_f del WebView puede contener servers[] reales que
                            // no estaban en el RSC original. Lo concatenamos.
                            rscPayload = rscPayload + "\n" + nextF
                            Log.i(TAG, "loadLinks v14 WEBVIEW: rscPayload now len=${rscPayload.length} " +
                                "hasSources=${rscPayload.contains("\"sources\":[")} " +
                                "hasActiveEpId=${rscPayload.contains("\"activeEpisodeId\":")}")
                        }
                    } catch (e: Exception) {
                        Log.i(TAG, "loadLinks v14 WEBVIEW: parse captured for next_f error: ${e.message}")
                    }
                }
            }
        }

        val isMovie = cleanUrl.contains("/peliculas/")

        if (isMovie) {
            return loadMovieLinks(cleanUrl, preloadedContentId, rscPayload, html, webViewCaptured, subtitleCallback, callback)
        } else {
            return loadEpisodeLinks(cleanUrl, rscPayload, html, webViewCaptured, subtitleCallback, callback)
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
        html: String,
        webViewCaptured: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logKey = "[ep#${rscPayload.hashCode().and(0xFFFF)}]"  // marker anti-chatty
        // 1. Encontrar activeEpisodeId
        val activeEpIdMatch = Regex(""""activeEpisodeId":"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""")
            .find(rscPayload)
        val activeEpId = activeEpIdMatch?.groupValues?.get(1) ?: ""
        Log.i(TAG, "$logKey loadEpisodeLinks url=$url activeEpId=$activeEpId rscSize=${rscPayload.length} htmlLen=${html.length} webViewCapturedLen=${webViewCaptured.length}")

        // v15 OPTIMIZATION: Si tenemos datos del WebView Y el RSC está reducido (bot detection),
        // emitir directamente desde el WebView en vez de pasar por todas las estrategias v9/v11
        // que sabemos van a fallar (todas reciben mock 719 bytes del API).
        // Esto ahorra ~6-8 segundos por episodio.
        if (webViewCaptured.isNotEmpty() && activeEpId.isBlank() && rscPayload.length < 50000) {
            Log.i(TAG, "$logKey v15 FAST PATH: bot detection + WebView data available, emitting directly")
            val emitted = emitFromWebViewCaptured(webViewCaptured, url, logKey, subtitleCallback, callback)
            if (emitted) {
                Log.i(TAG, "$logKey FINAL anyEmitted=true (v15 fast path via WebView)")
                return true
            }
            Log.i(TAG, "$logKey v15 FAST PATH: WebView emit failed, falling through to v9/v11 strategies")
        }

        // v12: Si el RSC está reducido (bot detection) y no tiene activeEpId,
        // buscar servers[] directamente en el HTML crudo. El HTML tiene mucha más
        // data que el RSC decodificado y puede contener los servers del episodio.
        if (activeEpId.isBlank() && rscPayload.length < 50000) {
            Log.i(TAG, "$logKey v12 HTML_SCAN: searching servers[] in raw HTML (${html.length} chars)")
            val htmlServers = extractServersFromHtml(html, logKey)
            if (htmlServers.isNotEmpty()) {
                Log.i(TAG, "$logKey v12 HTML_SCAN found ${htmlServers.size} servers in HTML, emitting")
                val emitted = emitEpisodeServers(htmlServers, url, subtitleCallback, callback)
                if (emitted) {
                    Log.i(TAG, "$logKey v12 HTML_SCAN emitted=true, skipping API calls")
                    Log.i(TAG, "$logKey FINAL anyEmitted=true")
                    return true
                }
            } else {
                Log.i(TAG, "$logKey v12 HTML_SCAN: no servers found in HTML")
            }
        }

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
                            newExtractorLink(
                                source = src.label,
                                name = src.label,
                                url = decryptedUrl,
                                type = linkType
                            ) {
                                this.referer = url
                                this.headers = mapOf("Origin" to mainUrl, "User-Agent" to browserUA)
                            }
                        )
                        anyEmitted = true
                    }
                }
            }
        }

        // v14: Si después de todas las estrategias no se emitió nada, intentar
        // emitir desde los datos capturados por WebViewResolver (videos, fetchResponses)
        if (!anyEmitted && webViewCaptured.isNotEmpty()) {
            Log.i(TAG, "$logKey v14 WEBVIEW FALLBACK: trying to emit from captured WebView data")
            val emitted = emitFromWebViewCaptured(webViewCaptured, url, logKey, subtitleCallback, callback)
            if (emitted) {
                anyEmitted = true
                Log.i(TAG, "$logKey FINAL anyEmitted=true (via WebView captured)")
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
     * v12: Extrae servers [{"name":"X","url":"Y"}] directamente del HTML crudo.
     *
     * Cuando el RSC está reducido por bot detection, el HTML completo (724653 bytes
     * para Martial Master 1-154) puede contener los servers del episodio en:
     * 1. Otros fragments de self.__next_f.push([1,"..."]) que extractRscPayload no decodificó
     * 2. JSON embebido en <script> tags
     * 3. Atributos data-* en el HTML
     *
     * Busca TODAS las ocurrencias de "servers":[{"name":"X","url":"Y"}] en el HTML crudo
     * y filtra solo las URLs que parecen ser de video (dailymotion, rumble, ok.ru, etc.).
     */
    private fun extractServersFromHtml(html: String, logKey: String): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        // Buscar TODAS las ocurrencias de {"name":"X","url":"Y"} en el HTML crudo.
        // El HTML puede tener escapes \\\" \\/ \\u0026, así que buscamos con un patrón
        // que tolere ambos formatos (escaped y unescaped).
        val serverEntryPattern = Regex("""\{\\?"name\\?":"([^"\\]+)\\?",\\?"url\\?":"([^"\\]+)\\?"\}""")
        val seen = mutableSetOf<String>()  // dedupe por URL
        for (m in serverEntryPattern.findAll(html)) {
            val rawName = m.groupValues[1]
            val rawUrl = m.groupValues[2]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            // Filtrar solo URLs que parecen ser de video real.
            // Esto excluye CSS, JS chunks, imágenes, fonts, etc.
            val isVideoUrl = rawUrl.contains("dailymotion.com") ||
                    rawUrl.contains("rumble.com") ||
                    rawUrl.contains("ok.ru") ||
                    rawUrl.contains("vk.com") || rawUrl.contains("vk.ru") ||
                    rawUrl.contains("streamable.com") ||
                    rawUrl.contains("voe.sx") ||
                    rawUrl.contains("filemoon") || rawUrl.contains("moonplayer") ||
                    rawUrl.contains("r2.cloudflarestorage") ||
                    rawUrl.contains("hcdn.dev") ||
                    rawUrl.contains("cloudflarestorage") ||
                    rawUrl.endsWith(".mp4") || rawUrl.contains(".mp4") ||
                    rawUrl.endsWith(".m3u8") || rawUrl.contains(".m3u8")
            if (!isVideoUrl) continue
            if (rawUrl.contains("/video/example") || rawUrl.contains("cdn.example.com")) continue
            if (rawUrl.length < 20) continue
            if (seen.contains(rawUrl)) continue
            seen.add(rawUrl)
            servers.add(rawName to rawUrl)
            Log.i(TAG, "$logKey v12 HTML_SCAN found: name=$rawName url=${rawUrl.take(80)}")
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
            // Reparar URLs m3u8 truncadas (algunas respuestas del server cortan en .m3)
            val serverUrlFixed = when {
                serverUrl.endsWith(".m3") -> serverUrl + "u8"
                serverUrl.endsWith(".m3u") -> serverUrl + "8"
                serverUrl.contains("chunklist.m3") && !serverUrl.contains("chunklist.m3u8") ->
                    serverUrl.replace("chunklist.m3", "chunklist.m3u8")
                else -> serverUrl
            }
            // Normalizar URL:
            // - Para Ok.ru, el sitio usa /videoembed/<id> pero CS3 solo reconoce /video/<id>.
            //   Convertimos al formato esperado por el extractor nativo.
            // - Algunos extractores además requieren www.
            val normalizedUrl = when {
                serverUrlFixed.contains("ok.ru/videoembed/") ->
                    serverUrlFixed.replace("ok.ru/videoembed/", "www.ok.ru/video/")
                serverUrlFixed.contains("ok.ru") && !serverUrlFixed.contains("www.ok.ru") ->
                    serverUrlFixed.replace("ok.ru", "www.ok.ru")
                else -> serverUrlFixed
            }
            try {
                when {
                    // Rumble
                    serverUrlFixed.contains("rumble.com") -> {
                        val emittedHere = extractRumble(serverUrlFixed, referer, name, trackingCallback)
                        // v15 FIX: si extractRumble no emitió nada, probar loadExtractor
                        // (CS3 tiene un RumbleExtractor built-in que maneja /embed/<id> URLs)
                        if (!emittedHere && !anyEmitted) {
                            Log.i(TAG, "v15 Rumble: custom extractRumble failed, trying loadExtractor fallback for $serverUrlFixed")
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (e: Exception) {
                                Log.w(TAG, "v15 Rumble: loadExtractor also failed: ${e.message}")
                            }
                        }
                    }
                    // Dailymotion (incluye geo.dailymotion.com)
                    serverUrlFixed.contains("dailymotion.com") || serverUrlFixed.contains("geo.dailymotion.com") -> {
                        val emittedHere = extractDailymotion(serverUrlFixed, referer, name, trackingCallback)
                        if (!emittedHere && !anyEmitted) {
                            Log.i(TAG, "v15 Dailymotion: custom extractDailymotion failed, trying loadExtractor fallback for $serverUrlFixed")
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (e: Exception) {
                                Log.w(TAG, "v15 Dailymotion: loadExtractor also failed: ${e.message}")
                            }
                        }
                    }
                    // Stremeable = streamable.com
                    serverUrlFixed.contains("streamable.com") -> {
                        extractStreamable(serverUrlFixed, referer, name, subtitleCallback, trackingCallback)
                        if (!anyEmitted) {
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (_: Exception) {}
                        }
                    }
                    // Ok.ru (CS3 tiene extractor nativo; usar URL normalizada con www.)
                    serverUrlFixed.contains("ok.ru") -> {
                        loadExtractor(normalizedUrl, referer, subtitleCallback, trackingCallback)
                        // Si loadExtractor no emitió nada, intentar con la URL original también
                        if (!anyEmitted) {
                            try { loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback) } catch (_: Exception) {}
                        }
                        // Si aún no emitió, intentar scrapear ok.ru directamente
                        // (algunos videos tienen metadata embebida en el HTML)
                        if (!anyEmitted) {
                            try { extractOkruDirect(serverUrlFixed, referer, name, trackingCallback) } catch (_: Exception) {}
                        }
                    }
                    // Direct mp4/m3u8 URLs (incluye URLs reparadas de chunklist.m3u8)
                    serverUrlFixed.endsWith(".mp4") || serverUrlFixed.endsWith(".m3u8") ||
                    serverUrlFixed.contains("chunklist") || serverUrlFixed.contains("index.m3u8") ||
                    serverUrlFixed.contains("r2.cloudflarestorage") || serverUrlFixed.contains("hcdn.dev") ||
                    serverUrlFixed.contains("donghualife.com/video") ||
                    serverUrlFixed.contains("donghualife.com/episodes") ||
                    serverUrlFixed.contains("donghualife.com/movie") -> {
                        val linkType = if (serverUrlFixed.contains(".m3u8") ||
                                           serverUrlFixed.contains("chunklist") ||
                                           serverUrlFixed.contains("index.m3u8")) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = serverUrlFixed,
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
                    serverUrlFixed.contains("voe.sx") -> {
                        loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback)
                    }
                    // Filemoon
                    serverUrlFixed.contains("filemoon") || serverUrlFixed.contains("moonplayer") -> {
                        loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback)
                    }
                    // Otros: loadExtractor genérico
                    else -> {
                        loadExtractor(serverUrlFixed, referer, subtitleCallback, trackingCallback)
                    }
                }
            } catch (_: Exception) {}
            // No seteamos anyEmitted aquí: el trackingCallback lo hace al ser invocado
        }
        return anyEmitted
    }

    /**
     * v14: Emite ExtractorLinks desde los datos capturados por WebViewResolver.
     *
     * Busca URLs de video reales en:
     * 1. captured.next_f — RSC data fetched client-side por el WebView
     * 2. captured.nextData — __NEXT_DATA__ legacy hydration
     * 3. captured.fetchResponses — respuestas interceptadas de /api/sources
     * 4. captured.videos — <video>, <iframe>, <source> del DOM renderizado
     * 5. captured.dataUrls — atributos data-src, data-url, data-video
     *
     * Filtra URLs mock ("/video/example", "cdn.example.com") y URLs que no
     * parecen ser de video (CSS, JS, imágenes, fonts).
     *
     * Retorna true si al menos un ExtractorLink fue emitido.
     */
    private suspend fun emitFromWebViewCaptured(
        capturedJson: String,
        referer: String,
        logKey: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val servers = ArrayList<Pair<String, String>>()

        // Helper: verifica si una URL parece ser de video real
        fun isVideoUrl(url: String): Boolean {
            return url.contains("dailymotion.com") ||
                url.contains("rumble.com") ||
                url.contains("ok.ru") ||
                url.contains("vk.com") || url.contains("vk.ru") ||
                url.contains("streamable.com") ||
                url.contains("voe.sx") ||
                url.contains("filemoon") || url.contains("moonplayer") ||
                url.contains("r2.cloudflarestorage") ||
                url.contains("hcdn.dev") ||
                url.contains("cloudflarestorage") ||
                url.contains(".mp4") || url.contains(".m3u8") ||
                // Algunas CDN sirven m3u8 con URLs tipo .../chunklist.m3u8 o .../index.m3u8
                // Si la URL está truncada (termina en .m3), aceptar también.
                url.contains("chunklist") || url.contains("index.m3") ||
                // CDN propia del sitio (videos.donghualife.com) y CDNs tipo 1a-XXXX.com
                url.contains("donghualife.com/video") ||
                url.contains("donghualife.com/episodes") ||
                url.contains("donghualife.com/movie") ||
                Regex("""https?://[a-z0-9-]+\.[a-z0-9-]+\.\w+/video/""").containsMatchIn(url)
        }

        // Helper: verifica si una URL es mock/placeholder del sitio
        fun isMockUrl(url: String): Boolean {
            return url.contains("/video/example") ||
                url.contains("cdn.example.com") ||
                url.contains("rumble.com/embed/example") ||
                url.contains("dailymotion.com/embed/video/example")
        }

        // Helper: reparar URLs m3u8 truncadas.
        // El server a veces devuelve URLs cortadas en .m3 (debería ser .m3u8) o en chunklist.m3
        fun repairM3u8Url(url: String): String {
            return when {
                url.endsWith(".m3") -> url + "u8"
                url.endsWith(".m3u") -> url + "8"
                url.contains("chunklist.m3") && !url.contains("chunklist.m3u8") ->
                    url.replace("chunklist.m3", "chunklist.m3u8")
                else -> url
            }
        }

        // Helper: extrae URLs de video de un texto (RSC o API response)
        fun extractVideoUrls(text: String, sourceLabel: String) {
            if (text.isEmpty()) return
            // Pattern 1: {"name":"X","url":"Y"} (formato RSC, name y url adyacentes)
            val serverPattern = Regex("""\{\\?"name\\?":"([^"\\]+)\\?",\\?"url\\?":"([^"\\]+)\\?"\}""")
            for (m in serverPattern.findAll(text)) {
                val name = m.groupValues[1]
                val rawUrl = m.groupValues[2]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\\"", "\"")
                val url = repairM3u8Url(rawUrl)
                if (isVideoUrl(url) && !isMockUrl(url) && url.length >= 20) {
                    servers.add(name to url)
                    Log.i(TAG, "$logKey v14 EMIT found (RSC format): name=$name url=${url.take(80)}")
                }
            }
            // Pattern 2: "url":"<video_url>" en cualquier posición (formato API response)
            // Busca nombres cercanos para asociarlos
            val urlPattern = Regex(""""url"\s*:\s*"([^"]+)"""")
            for (m in urlPattern.findAll(text)) {
                val rawUrl = m.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                val url = repairM3u8Url(rawUrl)
                if (!isVideoUrl(url) || isMockUrl(url) || url.length < 20) continue
                // Buscar "name":"X" cercano (hasta 200 chars antes)
                val urlPos = m.range.first
                val searchStart = maxOf(0, urlPos - 200)
                val searchEnd = minOf(text.length, urlPos)
                val nearbyText = text.substring(searchStart, searchEnd)
                val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(nearbyText)
                val name = nameMatch?.groupValues?.get(1) ?: sourceLabel
                // Verificar que no esté ya en servers (dedupe básico)
                if (servers.none { it.second == url }) {
                    servers.add(name to url)
                    Log.i(TAG, "$logKey v14 EMIT found (API format): name=$name url=${url.take(80)}")
                }
            }
            // Pattern 3: "label":"X",...,"url":"Y" (otra variante del API)
            val labelUrlPattern = Regex(""""label"\s*:\s*"([^"]+)"[^}]*?"url"\s*:\s*"([^"]+)"""")
            for (m in labelUrlPattern.findAll(text)) {
                val name = m.groupValues[1]
                val rawUrl = m.groupValues[2]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                val url = repairM3u8Url(rawUrl)
                if (!isVideoUrl(url) || isMockUrl(url) || url.length < 20) continue
                if (servers.none { it.second == url }) {
                    servers.add(name to url)
                    Log.i(TAG, "$logKey v14 EMIT found (label format): name=$name url=${url.take(80)}")
                }
            }
        }

        try {
            val captured = parseJson<CapturedWebViewData>(capturedJson)

            // 1. Escanear next_f (RSC data del WebView, puede tener servers[] reales)
            val nextF = captured.next_f ?: ""
            if (nextF.isNotEmpty()) {
                Log.i(TAG, "$logKey v14 EMIT: scanning next_f (${nextF.length} chars)")
                extractVideoUrls(nextF, "WebView RSC")
            }

            // 2. Escanear nextData (legacy __NEXT_DATA__)
            val nextData = captured.nextData ?: ""
            if (nextData.isNotEmpty()) {
                Log.i(TAG, "$logKey v14 EMIT: scanning nextData (${nextData.length} chars)")
                extractVideoUrls(nextData, "WebView NextData")
            }

            // 3. Escanear fetchResponses (respuestas interceptadas de /api/sources)
            val fetchResponses = captured.fetchResponses ?: emptyList()
            for ((idx, fr) in fetchResponses.withIndex()) {
                val frUrl = fr.url ?: ""
                val frBody = fr.body ?: ""
                if (frBody.isEmpty()) continue
                Log.i(TAG, "$logKey v14 EMIT: scanning fetchResponse[$idx] url=$frUrl bodyLen=${frBody.length}")
                // Log si la respuesta es mock
                if (frBody.contains("/video/example") || frBody.contains("cdn.example.com")) {
                    Log.i(TAG, "$logKey v14 EMIT: fetchResponse[$idx] is MOCK, but still scanning for real URLs")
                }
                extractVideoUrls(frBody, "WebView API $idx")
            }

            // 4. Agregar videos directos del DOM (ya son URLs completas)
            val videos = captured.videos ?: emptyList()
            for ((vIdx, v) in videos.withIndex()) {
                if (v.length < 20 || !isVideoUrl(v) || isMockUrl(v)) continue
                if (servers.none { it.second == v }) {
                    Log.i(TAG, "$logKey v14 EMIT: DOM video[$vIdx]=$v")
                    servers.add("WebView Video $vIdx" to v)
                }
            }

            // 5. Agregar dataUrls del DOM
            val dataUrls = captured.dataUrls ?: emptyList()
            for ((dIdx, d) in dataUrls.withIndex()) {
                if (d.length < 20 || !isVideoUrl(d) || isMockUrl(d)) continue
                if (servers.none { it.second == d }) {
                    Log.i(TAG, "$logKey v14 EMIT: DOM dataUrl[$dIdx]=$d")
                    servers.add("WebView Data $dIdx" to d)
                }
            }

            // Dedupe final por URL
            val seen = mutableSetOf<String>()
            val uniqueServers = servers.filter { (_, u) ->
                if (seen.contains(u)) false else { seen.add(u); true }
            }

            Log.i(TAG, "$logKey v14 EMIT: total ${servers.size} servers found, ${uniqueServers.size} unique")
            if (uniqueServers.isEmpty()) {
                Log.i(TAG, "$logKey v14 EMIT: no servers found in captured WebView data")
                return false
            }

            // Emitir usando emitEpisodeServers (que maneja cada tipo de URL)
            Log.i(TAG, "$logKey v14 EMIT: emitting ${uniqueServers.size} servers via emitEpisodeServers")
            return emitEpisodeServers(uniqueServers, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.i(TAG, "$logKey v14 EMIT error: ${e.message}")
            return false
        }
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
        html: String,
        webViewCaptured: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logKey = "[mv#${rscPayload.hashCode().and(0xFFFF)}]"  // marker anti-chatty
        val movieId = if (preloadedContentId.isNotBlank()) preloadedContentId
            else extractContentIdFromPayload(rscPayload, "movieId") ?: ""
        Log.i(TAG, "$logKey loadMovieLinks url=$url movieId=$movieId rscSize=${rscPayload.length} htmlLen=${html.length} webViewCapturedLen=${webViewCaptured.length}")

        // v15 OPTIMIZATION: Si tenemos datos del WebView Y el RSC está reducido (bot detection),
        // emitir directamente desde el WebView en vez de pasar por todas las estrategias que
        // sabemos van a fallar (reciben mock 719 bytes del API).
        // Esto ahorra ~6-8 segundos por película.
        if (webViewCaptured.isNotEmpty() && rscPayload.length < 50000) {
            Log.i(TAG, "$logKey v15 FAST PATH: bot detection + WebView data available, emitting directly")
            val emitted = emitFromWebViewCaptured(webViewCaptured, url, logKey, subtitleCallback, callback)
            if (emitted) {
                Log.i(TAG, "$logKey FINAL anyEmitted=true (v15 fast path via WebView)")
                return true
            }
            Log.i(TAG, "$logKey v15 FAST PATH: WebView emit failed, falling through to v9/v11 strategies")
        }

        // v12: Si el RSC está reducido (bot detection), buscar servers[] en el HTML crudo.
        // Para películas, el HTML puede contener los servers del movieId.
        if (rscPayload.length < 50000) {
            Log.i(TAG, "$logKey v12 HTML_SCAN: searching servers[] in raw HTML (${html.length} chars)")
            val htmlServers = extractServersFromHtml(html, logKey)
            if (htmlServers.isNotEmpty()) {
                Log.i(TAG, "$logKey v12 HTML_SCAN found ${htmlServers.size} servers in HTML, emitting")
                val emitted = emitEpisodeServers(htmlServers, url, subtitleCallback, callback)
                if (emitted) {
                    Log.i(TAG, "$logKey v12 HTML_SCAN emitted=true, skipping API calls")
                    Log.i(TAG, "$logKey FINAL anyEmitted=true")
                    return true
                }
            } else {
                Log.i(TAG, "$logKey v12 HTML_SCAN: no servers found in HTML")
            }
        }

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
                            newExtractorLink(
                                source = source.label,
                                name = source.label,
                                url = decryptedUrl,
                                type = linkType
                            ) {
                                this.referer = url
                                this.headers = mapOf("Origin" to mainUrl, "User-Agent" to browserUA)
                            }
                        )
                        anyEmitted = true
                    }
                }
            }
        }

        // v14: Si después de todas las estrategias no se emitió nada, intentar
        // emitir desde los datos capturados por WebViewResolver (videos, fetchResponses)
        if (!anyEmitted && webViewCaptured.isNotEmpty()) {
            Log.i(TAG, "$logKey v14 WEBVIEW FALLBACK: trying to emit from captured WebView data")
            val emitted = emitFromWebViewCaptured(webViewCaptured, url, logKey, subtitleCallback, callback)
            if (emitted) {
                anyEmitted = true
                Log.i(TAG, "$logKey FINAL anyEmitted=true (via WebView captured)")
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
                // v12: Rechazar respuestas HTML (Next.js devuelve el HTML 404 cuando
                // el endpoint no existe). Solo aceptar JSON válido.
                val isHtml = resp.trimStart().startsWith("<!DOCTYPE") ||
                             resp.trimStart().startsWith("<html")
                val isJson = resp.trimStart().startsWith("{") || resp.trimStart().startsWith("[")
                Log.i(TAG, "$logKey ALT $endpoint respLen=${resp.length} isMock=$isMock isHtml=$isHtml isJson=$isJson " +
                    "head=${resp.take(150).replace("\n", " ")}")
                if (resp.isNotBlank() && resp.length > 5 && !isMock && !isHtml && isJson &&
                    !resp.contains("\"error\"") && resp != "{}") {
                    Log.i(TAG, "$logKey ALT $endpoint NON-MOCK JSON RESPONSE FOUND!")
                    return resp
                }
            } catch (e: Exception) {
                Log.i(TAG, "$logKey ALT $endpoint error: ${e.message}")
            }
        }
        return ""
    }

    /**
     * v14: Usa WebViewResolver para renderizar la página en un motor Chrome real
     * (WebView de Android) que ejecuta JavaScript como un navegador verdadero.
     *
     * Esto bypassa la bot detection de beta.donghualife.com que:
     * - Reduce el RSC payload a ~30KB (skeleton de loading) para clientes no-navegador
     * - Devuelve MOCK URLs ("example") en /api/sources cuando se llama directo
     * - Bloquea POST endpoints (respLen=0)
     *
     * El WebView ejecuta el JS de Next.js, que hace fetch() autenticado al backend
     * y recibe URLs reales (Dailymotion, Rumble, etc.) en lugar de mocks.
     *
     * Estrategia del script JS:
     * 1. Interceptar window.fetch para capturar respuestas de /api/sources
     * 2. Esperar 10s a que React hidrate y haga fetch client-side
     * 3. Capturar window.__next_f (RSC data fetched client-side)
     * 4. Capturar <video>, <iframe>, <source> del DOM renderizado
     * 5. Inyectar todo en un <div id="cs3-captured"> oculto
     *
     * @param url URL completa del episodio/película
     * @param logKey Prefijo para logs
     * @return Pair(renderedHtml, capturedJson) o null si falla
     */
    private suspend fun tryWebViewResolver(
        url: String,
        logKey: String
    ): Pair<String, String>? {
        return try {
            Log.i(TAG, "$logKey v14 WEBVIEW: launching manual WebView for $url")

            // Obtener Context para crear el WebView.
            // Estrategia multi-vía porque las APIs varían entre versiones de CS3:
            //   1. com.lagradost.api.getContext() — library API oficial (falla en algunas versiones)
            //   2. Reflection sobre android.app.ActivityThread.currentApplication() — hidden API
            //      de Android, estable, devuelve la Application.
            //   3. com.lagradost.cloudstream3.AcraApplication.context — si existe en esta versión.
            val ctx: Context? = try {
                var c: Context? = null
                // Intento 1: com.lagradost.api.getContext()
                try {
                    val m = Class.forName("com.lagradost.api.ContextHelper_jvmKt")
                        .declaredMethods.firstOrNull { it.name == "getContext" }
                    if (m != null) {
                        @Suppress("UNCHECKED_CAST")
                        c = m.invoke(null) as? Context
                    }
                } catch (_: Throwable) {}
                // Intento 2: AcraApplication.context (campo estático)
                if (c == null) {
                    try {
                        val cls = Class.forName("com.lagradost.cloudstream3.AcraApplication")
                        val field = cls.getDeclaredField("context")
                        field.isAccessible = true
                        c = field.get(null) as? Context
                    } catch (_: Throwable) {}
                }
                // Intento 3: ActivityThread.currentApplication() vía reflection
                if (c == null) {
                    try {
                        val atCls = Class.forName("android.app.ActivityThread")
                        val m = atCls.getDeclaredMethod("currentApplication")
                        m.isAccessible = true
                        c = m.invoke(null) as? Context
                    } catch (_: Throwable) {}
                }
                c
            } catch (_: Throwable) { null }
            if (ctx == null) {
                Log.i(TAG, "$logKey v14 WEBVIEW: no Context available (all 3 strategies failed), cannot use WebView")
                return null
            }
            Log.i(TAG, "$logKey v14 WEBVIEW: Context acquired class=${ctx.javaClass.simpleName}")

            // Script JS que se inyecta DESPUÉS de que la página carga.
            // Usa setTimeout para esperar a que React hidrate y haga fetches client-side.
            // Al terminar, llama a Android.onCaptured(json) para enviar los datos al Kotlin.
            val script = """
                (function() {
                    try {
                        // 1. Interceptar fetch para capturar respuestas de API
                        if (!window.__cs3FetchIntercepted) {
                            window.__cs3FetchIntercepted = true;
                            window.__cs3FetchResponses = [];
                            var origFetch = window.fetch;
                            window.fetch = function() {
                                var args = arguments;
                                var fetchUrl = (typeof args[0] === 'string') ? args[0] :
                                               (args[0] && args[0].url) ? args[0].url : '';
                                return origFetch.apply(this, args).then(function(resp) {
                                    try {
                                        if (fetchUrl && (fetchUrl.indexOf('/api/') >= 0 ||
                                            fetchUrl.indexOf('sources') >= 0 ||
                                            fetchUrl.indexOf('embed') >= 0 ||
                                            fetchUrl.indexOf('stream') >= 0)) {
                                            var clone = resp.clone();
                                            clone.text().then(function(txt) {
                                                if (txt && txt.length < 50000) {
                                                    window.__cs3FetchResponses.push({
                                                        url: fetchUrl,
                                                        status: resp.status,
                                                        body: txt
                                                    });
                                                }
                                            }).catch(function(){});

                                            // v15 EARLY CAPTURE: si vemos que llegó /api/player/source
                                            // (que es el endpoint crítico con la URL real del video),
                                            // disparar la captura inmediatamente sin esperar al setTimeout.
                                            if (fetchUrl && fetchUrl.indexOf('/api/player/source') >= 0) {
                                                try {
                                                    if (!window.__cs3EarlyCaptureFired) {
                                                        window.__cs3EarlyCaptureFired = true;
                                                        setTimeout(function() {
                                                            // Dar 500ms extra para que lleguen más fetches
                                                            // (subtitles, banners, etc.) antes de capturar.
                                                            fireCapture();
                                                        }, 500);
                                                    }
                                                } catch(ee) {}
                                            }
                                        }
                                    } catch(e) {}
                                    return resp;
                                });
                            };
                        }
                    } catch(e) {}

                    // Función para recolectar datos capturados (reutilizable)
                    function collectCaptured() {
                        var captured = {};

                        // 3. Capturar window.__next_f (RSC data client-side)
                        var nextF = '';
                        try {
                            if (window.__next_f && window.__next_f.length) {
                                for (var i = 0; i < window.__next_f.length; i++) {
                                    try {
                                        var part = window.__next_f[i];
                                        if (part && part.length >= 2) {
                                            nextF += part[1] + '\n';
                                        }
                                    } catch(e) {}
                                }
                            }
                        } catch(e) {}
                        captured.next_f = nextF.substring(0, 300000);

                        // 4. Capturar __NEXT_DATA__ (legacy hydration)
                        try {
                            if (window.__NEXT_DATA__) {
                                captured.nextData = JSON.stringify(window.__NEXT_DATA__).substring(0, 100000);
                            }
                        } catch(e) {}

                        // 5. Capturar video/iframe/source URLs del DOM
                        var videos = [];
                        try {
                            document.querySelectorAll('video').forEach(function(v) {
                                if (v.src) videos.push(v.src);
                                if (v.currentSrc) videos.push(v.currentSrc);
                            });
                            document.querySelectorAll('iframe').forEach(function(i) {
                                if (i.src) videos.push(i.src);
                            });
                            document.querySelectorAll('source').forEach(function(s) {
                                if (s.src) videos.push(s.src);
                            });
                        } catch(e) {}
                        captured.videos = videos;

                        // 6. Capturar data-src, data-url, data-video attributes
                        var dataUrls = [];
                        try {
                            document.querySelectorAll('[data-src],[data-url],[data-video],[data-source]').forEach(function(el) {
                                ['data-src','data-url','data-video','data-source'].forEach(function(attr) {
                                    var val = el.getAttribute(attr);
                                    if (val && val.indexOf('http') === 0) dataUrls.push(val);
                                });
                            });
                        } catch(e) {}
                        captured.dataUrls = dataUrls;

                        // 7. Capturar fetch responses interceptadas
                        try {
                            captured.fetchResponses = window.__cs3FetchResponses || [];
                        } catch(e) {
                            captured.fetchResponses = [];
                        }

                        // 8. Capturar HTML del body renderizado (para RSC extraction)
                        try {
                            captured.html = document.documentElement.outerHTML.substring(0, 500000);
                        } catch(e) {}

                        // 9. Capturar HTML length para diagnóstico
                        try {
                            captured.htmlLength = document.documentElement.outerHTML.length;
                        } catch(e) {}

                        return captured;
                    }

                    // Helper: envía captured a Kotlin respetando el flag de "ya capturado"
                    function fireCapture() {
                        if (window.__cs3Captured) return;
                        window.__cs3Captured = true;
                        try {
                            Android.onCaptured(JSON.stringify(collectCaptured()));
                        } catch(e) {
                            // Si Android interface no está disponible, intentar inyectar en DOM
                            try {
                                var div = document.createElement('div');
                                div.id = 'cs3-captured';
                                div.style.display = 'none';
                                div.textContent = JSON.stringify(collectCaptured());
                                document.body.appendChild(div);
                            } catch(ee) {}
                        }
                    }

                    // 2. Esperar 8s a que React hidrate y haga fetches client-side.
                    // Si el early-capture ya disparó (porque llegó /api/player/source),
                    // este setTimeout será no-op gracias al flag __cs3Captured.
                    setTimeout(function() {
                        try {
                            fireCapture();
                        } catch(e) {
                            try {
                                Android.onCaptured('{"error":"' + e.toString().replace(/"/g, '\\"') + '"}');
                            } catch(ee) {}
                        }
                    }, 8000);  // 8s: dar tiempo a React hidrate + fetches (reducido de 12s)
                })();
            """.trimIndent()

            // Usar WebView manual con suspendCoroutine
            var webView: WebView? = null
            val capturedJson = withTimeoutOrNull(20000L) {
                suspendCoroutine<String?> { cont ->
                    // WebView DEBE crearse en el main thread (UI thread)
                    Handler(Looper.getMainLooper()).post {
                        try {
                            val wv = WebView(ctx)
                            webView = wv
                            wv.settings.javaScriptEnabled = true
                            wv.settings.domStorageEnabled = true
                            wv.settings.mediaPlaybackRequiresUserGesture = false
                            wv.settings.blockNetworkImage = true  // No cargar imágenes (más rápido)
                            // NO setear userAgentString custom — dejar el default de Chrome

                            var finished = false

                            // JavascriptInterface para recibir datos del JS
                            wv.addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onCaptured(json: String) {
                                    Log.i(TAG, "$logKey v14 WEBVIEW: onCaptured len=${json.length}")
                                    if (!finished) {
                                        finished = true
                                        cont.resume(json)
                                    }
                                }
                            }, "Android")

                            wv.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    Log.i(TAG, "$logKey v14 WEBVIEW: onPageFinished, injecting script")
                                    // Inyectar el script que intercepta fetch y captura datos
                                    view?.evaluateJavascript(script) { _ ->
                                        Log.i(TAG, "$logKey v14 WEBVIEW: script injected, waiting 8s for capture...")
                                        // El script tiene setTimeout(8000) que llamará Android.onCaptured
                                        // Si no llama en 20s, el withTimeoutOrNull cancelará
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    Log.i(TAG, "$logKey v14 WEBVIEW error: ${error?.description} url=${request?.url}")
                                }
                            }

                            // Set headers browser-like
                            val headers = mapOf(
                                "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8"
                            )
                            wv.loadUrl(url, headers)
                        } catch (e: Exception) {
                            Log.i(TAG, "$logKey v14 WEBVIEW: WebView creation error: ${e.message}")
                            cont.resume(null)
                        }
                    }
                }
            }

            // Limpiar WebView SIEMPRE (incluso si timeout)
            Handler(Looper.getMainLooper()).post {
                try {
                    webView?.stopLoading()
                    webView?.removeJavascriptInterface("Android")
                    webView?.destroy()
                } catch (_: Exception) {}
            }
            webView = null

            if (capturedJson.isNullOrEmpty()) {
                Log.i(TAG, "$logKey v14 WEBVIEW: no data captured (timeout or error)")
                return null
            }

            Log.i(TAG, "$logKey v14 WEBVIEW CAPTURED len=${capturedJson.length}")

            // Extraer HTML del JSON capturado (si está disponible)
            var renderedHtml = ""
            try {
                val captured = parseJson<CapturedWebViewData>(capturedJson)
                renderedHtml = captured.html ?: ""
                Log.i(TAG, "$logKey v14 WEBVIEW: next_f len=${captured.next_f?.length ?: 0} " +
                    "nextData len=${captured.nextData?.length ?: 0} " +
                    "videos=${captured.videos?.size ?: 0} " +
                    "dataUrls=${captured.dataUrls?.size ?: 0} " +
                    "fetchResponses=${captured.fetchResponses?.size ?: 0} " +
                    "htmlLength=${captured.htmlLength ?: 0} " +
                    "html len=${renderedHtml.length}")
                // Log videos encontrados
                captured.videos?.take(5)?.forEachIndexed { idx, v ->
                    Log.i(TAG, "$logKey v14 WEBVIEW video[$idx]=$v")
                }
                captured.dataUrls?.take(5)?.forEachIndexed { idx, u ->
                    Log.i(TAG, "$logKey v14 WEBVIEW dataUrl[$idx]=$u")
                }
                captured.fetchResponses?.take(3)?.forEachIndexed { idx, fr ->
                    Log.i(TAG, "$logKey v14 WEBVIEW fetchResp[$idx] url=${fr.url} status=${fr.status} bodyLen=${fr.body?.length ?: 0} bodyHead=${fr.body?.take(150)}")
                }
            } catch (e: Exception) {
                Log.i(TAG, "$logKey v14 WEBVIEW: parse captured error: ${e.message}")
                Log.i(TAG, "$logKey v14 WEBVIEW raw head: ${capturedJson.take(500)}")
            }

            Pair(renderedHtml, capturedJson)
        } catch (e: Throwable) {
            Log.i(TAG, "$logKey v14 WEBVIEW exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * v14: Data class para parsear el JSON capturado por WebView.
     * Usa Gson (vía AppUtils.parseJson) — los nombres de campo Kotlin coinciden
     * con las keys del JSON, no se necesitan anotaciones @SerializedName.
     */
    private data class CapturedWebViewData(
        val next_f: String? = null,
        val nextData: String? = null,
        val videos: List<String>? = null,
        val dataUrls: List<String>? = null,
        val fetchResponses: List<FetchResponseData>? = null,
        val html: String? = null,
        val htmlLength: Int? = null
    )

    private data class FetchResponseData(
        val url: String? = null,
        val status: Int? = null,
        val body: String? = null
    )

    /**
     * v23: Data class para parsear el JSON capturado por extractRumbleViaWebView.
     * Mismo mecanismo que CapturedWebViewData (parseJson<T>, sin Gson).
     */
    private data class CapturedRumbleData(
        val videoSrcs: List<String>? = null,
        val sourceSrcs: List<String>? = null,
        val iframeSrcs: List<String>? = null,
        val htmlSnapshot: String? = null,
        val fetchUrls: List<RumbleFetchData>? = null,
        // v24 diagnostic fields
        val htmlLength: Int? = null,
        val htmlHead: String? = null,
        val docTitle: String? = null,
        val cookieLen: Int? = null,
        val cookieNames: String? = null,
        val error: String? = null
    )

    private data class RumbleFetchData(
        val type: String? = null,
        val url: String? = null,
        val status: Int? = null,
        val body: String? = null
    )

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
            // Two-tier scan:
            //   - "Relevant" = chunks with page/layout/watch/api/source/video/embed in name
            //   - "Framework" = ALL other chunks (big ones like 8500-*.js, 3701-*.js where
            //     the API client + encryption logic usually lives)
            // Scan relevant first, then framework up to a total of 8 chunks.
            val relevant = jsUrls.filter { url ->
                url.contains("page") || url.contains("layout") ||
                url.contains("watch") || url.contains("peliculas") ||
                url.contains("source") || url.contains("video") ||
                url.contains("api") || url.contains("embed")
            }
            val framework = jsUrls.filter { it !in relevant }
            Log.i(TAG, "$logKey JS_ANALYZE relevant=${relevant.size} framework=${framework.size} " +
                "relevant=${relevant.joinToString(",") { it.takeLast(40) }}")
            val toScan = (relevant + framework).take(8)

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
                "api/stream", "/api/video", "/api/v1/sources",
                "AES", "decrypt", "CryptoJS", "crypto.subtle",
                "SECRET_KEY", "secretKey", "ENCRYPTION_KEY", "encryptionKey",
                "signature", "verify", "hmac", "HMAC",
                "token", "resolveToken", "decodeToken",
                "isBot", "isCrawler", "botDetected", "userAgent",
                "mockData", "mockUrl", "example.com", "cdn.example",
                "verifyBrowser", "fingerprint", "tlsFingerprint",
            )

            for (chunkUrl in toScan) {
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
            // v12: Filtrar solo URLs que parecen ser de video (no CSS, JS, imágenes, fonts).
            val videoUrls = urls.filter { u ->
                u.contains("dailymotion.com") || u.contains("rumble.com") ||
                u.contains("ok.ru") || u.contains("vk.com") || u.contains("vk.ru") ||
                u.contains("streamable.com") || u.contains("voe.sx") ||
                u.contains("filemoon") || u.contains("moonplayer") ||
                u.contains("r2.cloudflarestorage") || u.contains("hcdn.dev") ||
                u.contains("cloudflarestorage") ||
                u.endsWith(".mp4") || u.contains(".mp4") ||
                u.endsWith(".m3u8") || u.contains(".m3u8")
            }.filterNot { it.contains("/video/example") || it.contains("cdn.example.com") }
            Log.i(TAG, "  Raw URLs found: ${urls.size}, video URLs: ${videoUrls.size}")
            for ((idx, u) in videoUrls.withIndex()) {
                val cleanUrl = u.removeSuffix(",").removeSuffix("}")
                if (cleanUrl.contains("/api/sources")) continue  // No usar la URL del API
                if (cleanUrl.length < 20) continue  // URLs muy cortas son ruido
                try {
                    when {
                        cleanUrl.contains("rumble.com") -> {
                            val emitted = extractRumble(cleanUrl, referer, "$defaultLabel ${idx + 1}", callback)
                            if (emitted) anyEmitted = true
                            else {
                                // Fallback a loadExtractor (CS3 built-in RumbleExtractor)
                                try { loadExtractor(cleanUrl, referer, subtitleCallback = {}, callback); anyEmitted = true } catch (_: Exception) {}
                            }
                        }
                        cleanUrl.contains("dailymotion.com") -> {
                            val emitted = extractDailymotion(cleanUrl, referer, "$defaultLabel ${idx + 1}", callback)
                            if (emitted) anyEmitted = true
                            else {
                                try { loadExtractor(cleanUrl, referer, subtitleCallback = {}, callback); anyEmitted = true } catch (_: Exception) {}
                            }
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
    ): Boolean {
        var emitted = false
        val trackingCb: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }

        // v15 FIX: Rumble ahora devuelve HTTP 410 (Gone) para /embed/<vkey> con bot detection.
        // Solución: usar la API pública JSON de Rumble: /api/Media?vkey=<vkey>
        // Esta API devuelve JSON estructurado con URLs directas m3u8/mp4 y NO tiene el 410 block.
        val vkey = Regex("""/embed/([A-Za-z0-9_]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: Regex("""vkey=([A-Za-z0-9_]+)""").find(embedUrl)?.groupValues?.get(1)

        val rumbleHeaders = mapOf(
            "User-Agent" to browserUA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "https://rumble.com/",
            "Origin" to "https://rumble.com",
        )

        // ===================== MÉTODO 1: API pública /api/Media?vkey=<vkey> =====================
        if (vkey != null) {
            try {
                val apiUrl = "https://rumble.com/api/Media?vkey=$vkey"
                val resp = app.get(apiUrl, headers = rumbleHeaders, timeout = 20L)
                val jsonText = resp.text
                Log.i(TAG, "extractRumble: API vkey=$vkey httpCode=${resp.code} jsonLen=${jsonText.length} head=${jsonText.take(200)}")

                // v27 DIAGNOSTIC: detectar si Rumble devolvió HTML (homepage o video page) en vez de JSON.
                // Si es HTML, MÉTODO 1 no podrá parsear JSON. Probamos extraer embedded_video_data script
                // y luego caemos a MÉTODO 1.5 (embedJS/oembed/publicPage).
                val isHtmlResponse = jsonText.length > 200 &&
                    (jsonText.startsWith("<!doctype", ignoreCase = true) ||
                     jsonText.startsWith("<html", ignoreCase = true) ||
                     jsonText.contains("<head", ignoreCase = true))
                if (isHtmlResponse) {
                    Log.i(TAG, "extractRumble: API returned HTML (len=${jsonText.length}), not JSON — trying embedded_video_data parser")

                    // v27: Rumble video pages contienen un <script class="embedded_video_data" type="application/json">
                    // con el JSON completo del video (hls, mp4, etc). Buscarlo y parsearlo.
                    val embeddedDataRegex = Regex(
                        """<script[^>]*class\s*=\s*"[^">]*embedded_video_data[^">]*"[^>]*>([\s\S]*?)</script>""",
                        RegexOption.IGNORE_CASE
                    )
                    val embeddedMatch = embeddedDataRegex.find(jsonText)
                    if (embeddedMatch != null) {
                        val embeddedJson = embeddedMatch.groupValues[1].trim()
                        Log.i(TAG, "extractRumble: API embedded_video_data FOUND len=${embeddedJson.length} head=${embeddedJson.take(300)}")

                        // Parsear el JSON del embedded_video_data — mismo formato que la API normal
                        // {"vkey":"...","hls":{"auto":{"url":"https://...m3u8","ld":{"url":"..."},"sd":{"url":"..."},"hd":{"url":"..."}}},
                        //  "ua":{"tar":{"1080":{"url":"https://...mp4"},"720":{"url":"..."},"480":{"url":"..."}}}}

                        // hls.auto.url
                        val hlsAutoMatch = Regex(
                            """"hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)""""
                        ).find(embeddedJson)
                        if (hlsAutoMatch != null) {
                            val u = hlsAutoMatch.groupValues[1]
                                .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                            try {
                                generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                Log.i(TAG, "extractRumble: API embedded hls.auto emitted: ${u.take(80)}")
                                if (emitted) return true
                            } catch (e: Exception) {
                                Log.w(TAG, "extractRumble: API embedded hls.auto generateM3u8 failed: ${e.message}")
                            }
                        }

                        // hls.auto.{ld,sd,hd}.url (quality variants)
                        if (!emitted) {
                            var qualityEmitted = 0
                            for (qMatch in Regex(""""(ld|sd|hd)"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").findAll(embeddedJson)) {
                                val qLabel = qMatch.groupValues[1]
                                val u = qMatch.groupValues[2]
                                    .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                                try {
                                    generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                    Log.i(TAG, "extractRumble: API embedded hls.$qLabel emitted: ${u.take(80)}")
                                    qualityEmitted++
                                } catch (_: Throwable) {}
                            }
                            if (qualityEmitted > 0 && emitted) return true
                        }

                        // ua.tar.{quality}.url (mp4 direct URLs)
                        if (!emitted) {
                            val tarBlockMatch = Regex(
                                """"ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})"""
                            ).find(embeddedJson)
                            if (tarBlockMatch != null) {
                                val tarBlock = tarBlockMatch.groupValues[1]
                                var tarCount = 0
                                Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
                                    val qLabel = match.groupValues[1]
                                    val u = match.groupValues[2]
                                        .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                                    if (u.isBlank()) return@forEach
                                    val quality = when (qLabel) {
                                        "2160", "1440" -> Qualities.P2160.value
                                        "1080" -> Qualities.P1080.value
                                        "720" -> Qualities.P720.value
                                        "480" -> Qualities.P480.value
                                        "360" -> Qualities.P360.value
                                        else -> Qualities.Unknown.value
                                    }
                                    try {
                                        trackingCb(
                                            newExtractorLink(
                                                source = serverName,
                                                name = "$serverName ${qLabel}p",
                                                url = u,
                                                type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = "https://rumble.com"
                                                this.quality = quality
                                            }
                                        )
                                        tarCount++
                                    } catch (_: Throwable) {}
                                }
                                if (tarCount > 0) {
                                    Log.i(TAG, "extractRumble: API embedded ua.tar emitted $tarCount qualities")
                                    if (emitted) return true
                                }
                            }
                        }

                        // Fallback: scan any .m3u8 / .mp4 URL en el embedded JSON
                        if (!emitted) {
                            var fbCount = 0
                            for (m in Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(embeddedJson)) {
                                val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                                try {
                                    trackingCb(
                                        newExtractorLink(
                                            source = serverName, name = serverName, url = u,
                                            type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "https://rumble.com"
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    fbCount++
                                } catch (_: Throwable) {}
                            }
                            if (fbCount > 0) {
                                Log.i(TAG, "extractRumble: API embedded regex fallback emitted $fbCount URLs")
                                if (emitted) return true
                            }
                        }
                    } else {
                        // v27 DIAGNOSTIC: no embedded_video_data found. Log si hay og:video meta tag.
                        val ogVideoMatch = Regex("""<meta\s+property\s*=\s*"og:video[^"]*"\s+content\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(jsonText)
                        if (ogVideoMatch != null) {
                            Log.i(TAG, "extractRumble: API HTML has og:video=${ogVideoMatch.groupValues[1].take(100)} (no embedded_video_data)")
                        } else {
                            Log.i(TAG, "extractRumble: API HTML has NO embedded_video_data AND no og:video — likely Rumble homepage (bot detection redirect)")
                        }
                    }
                    // No emitimos nada del HTML; caemos al MÉTODO 1.5
                } else {
                    // JSON response — usar parser original de la API
                // La API devuelve: {"data":{"<vkey>":{"hls":{"auto":{"url":"...m3u8"}},"ua":{"tar":{"1080":{"url":"...mp4"}}}}}}
                // v22 FIX: com.google.gson NO está en el classpath de CS3 (usan Jackson vía AppUtils.parseJson<T>).
                // Por simplicidad y consistencia con MÉTODO 2, parseamos el JSON con regex (igual que extractDailymotion).

                // 1) hls.auto.url (m3u8 principal)
                if (!emitted) {
                    val hlsAutoMatch = Regex(
                        """"hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)""""
                    ).find(jsonText)
                    if (hlsAutoMatch != null) {
                        val u = hlsAutoMatch.groupValues[1]
                            .replace("\\/", "/")
                            .replace("\\u0026", "&")
                        if (u.contains(".m3u8")) {
                            try {
                                generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                Log.i(TAG, "extractRumble: API hls.auto emitted: ${u.take(80)}")
                                if (emitted) return true
                            } catch (e: Exception) {
                                Log.w(TAG, "extractRumble: API hls.auto generateM3u8 failed: ${e.message}")
                            }
                        }
                    }
                }

                // 2) hls.url (fallback si no hay hls.auto)
                if (!emitted) {
                    val hlsUrlMatch = Regex(
                        """"hls"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)""""
                    ).find(jsonText)
                    if (hlsUrlMatch != null) {
                        val u = hlsUrlMatch.groupValues[1]
                            .replace("\\/", "/")
                            .replace("\\u0026", "&")
                        try {
                            generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                            Log.i(TAG, "extractRumble: API hls.url emitted: ${u.take(80)}")
                            if (emitted) return true
                        } catch (e: Exception) {
                            Log.w(TAG, "extractRumble: API hls.url generateM3u8 failed: ${e.message}")
                        }
                    }
                }

                // 3) ua.tar.<quality>.url (multiple qualities mp4)
                if (!emitted) {
                    val tarBlockMatch = Regex(
                        """"ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})"""
                    ).find(jsonText)
                    if (tarBlockMatch != null) {
                        val tarBlock = tarBlockMatch.groupValues[1]
                        var tarCount = 0
                        Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
                            val qLabel = match.groupValues[1]
                            val u = match.groupValues[2]
                                .replace("\\/", "/")
                                .replace("\\u0026", "&")
                            if (u.isBlank()) return@forEach
                            val quality = when (qLabel) {
                                "2160", "1440" -> Qualities.P2160.value
                                "1080" -> Qualities.P1080.value
                                "720" -> Qualities.P720.value
                                "480" -> Qualities.P480.value
                                "360" -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
                            val isM3u8 = u.contains(".m3u8")
                            try {
                                trackingCb(
                                    newExtractorLink(
                                        source = serverName,
                                        name = "$serverName ${qLabel}p",
                                        url = u,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://rumble.com"
                                        this.quality = quality
                                    }
                                )
                                tarCount++
                            } catch (_: Throwable) {}
                        }
                        if (tarCount > 0) {
                            Log.i(TAG, "extractRumble: API ua.tar emitted $tarCount qualities")
                            if (emitted) return true
                        }
                    }
                }

                // 4) Fallback: scan any .m3u8 or .mp4 URL en el JSON completo
                if (!emitted) {
                    var fallbackCount = 0
                    for (m in Regex("""(https?://[^"]+\.(?:m3u8|mp4)[^"]*)""").findAll(jsonText)) {
                        val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                        val isM3u8 = u.contains(".m3u8")
                        try {
                            trackingCb(
                                newExtractorLink(
                                    source = serverName,
                                    name = serverName,
                                    url = u,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://rumble.com"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            fallbackCount++
                        } catch (_: Throwable) {}
                    }
                    if (fallbackCount > 0) {
                        Log.i(TAG, "extractRumble: API regex fallback emitted $fallbackCount URLs")
                        if (emitted) return true
                    }
                }
                } // end else (JSON response branch)
            } catch (e: Exception) {
                Log.w(TAG, "extractRumble: API call failed for vkey=$vkey: ${e.message}")
            }
        } else {
            Log.w(TAG, "extractRumble: could not extract vkey from $embedUrl")
        }

        // ===================== MÉTODO 1.5: v26+v27 ALTERNATIVE ENDPOINTS (get.json / embedJS / oEmbed / public page) =====================
        // v26: MÉTODO 1 (API) devuelve homepage HTML (301 redirect). MÉTODO 2 (embed HTML) devuelve 410.
        // v27: MÉTODO 1 ahora detecta HTML y cae aquí directamente. Agregado /api/Media/get.json como endpoint alternativo.
        // Probamos endpoints alternativos que pueden NO tener el mismo bot detection:
        //   a) /api/Media/get.json?vkey=<vkey>  — v27 NEW: API JSON alternativa (formato get.json)
        //   b) /embedjs/<vkey>                  — JS file con URLs de video en JSON (lo carga el iframe normalmente)
        //   c) /api/Media/oembed?url=<embed>    — API pública para metadata de embeds
        //   d) /<vkey> (sin /embed/)            — página pública del video (canonical URL)
        if (vkey != null && !emitted) {
            Log.i(TAG, "extractRumble: MÉTODO 1.5 entered (vkey=$vkey) — trying 4 alternative endpoints")
            val altHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9,es;q=0.8",
                "Referer" to "https://beta.donghualife.com/",
                "Origin" to "https://beta.donghualife.com",
                "Sec-Fetch-Dest" to "script",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "cross-site",
            )

            // a) v27 NEW: /api/Media/get.json?vkey=<vkey> — alternativa a /api/Media?vkey=
            // Algunos endpoints de Rumble usan /get.json para respuestas JSON limpias
            if (!emitted) {
                try {
                    val getJsonUrl = "https://rumble.com/api/Media/get.json?vkey=$vkey"
                    val getJsonHeaders = altHeaders.toMutableMap().apply {
                        put("Accept", "application/json, text/plain, */*")
                        put("Sec-Fetch-Dest", "empty")
                        put("Sec-Fetch-Mode", "cors")
                    }
                    val resp = app.get(getJsonUrl, headers = getJsonHeaders, timeout = 15L)
                    val jsonText = resp.text
                    Log.i(TAG, "extractRumble: get.json vkey=$vkey httpCode=${resp.code} jsonLen=${jsonText.length} head=${jsonText.take(200)}")

                    if (resp.code == 200 && (jsonText.startsWith("{") || jsonText.startsWith("["))) {
                        // Real JSON response — parse like MÉTODO 1
                        val hlsAutoMatch = Regex(""""hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(jsonText)
                        if (hlsAutoMatch != null) {
                            val u = hlsAutoMatch.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                            try {
                                generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                Log.i(TAG, "extractRumble: get.json hls.auto emitted: ${u.take(80)}")
                                if (emitted) return true
                            } catch (_: Throwable) {}
                        }

                        // ua.tar
                        if (!emitted) {
                            val tarBlockMatch = Regex(""""ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})""").find(jsonText)
                            if (tarBlockMatch != null) {
                                val tarBlock = tarBlockMatch.groupValues[1]
                                Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
                                    val qLabel = match.groupValues[1]
                                    val u = match.groupValues[2].replace("\\/", "/").replace("\\u0026", "&")
                                    if (u.isBlank()) return@forEach
                                    val quality = when (qLabel) {
                                        "2160", "1440" -> Qualities.P2160.value
                                        "1080" -> Qualities.P1080.value
                                        "720" -> Qualities.P720.value
                                        "480" -> Qualities.P480.value
                                        "360" -> Qualities.P360.value
                                        else -> Qualities.Unknown.value
                                    }
                                    try {
                                        trackingCb(
                                            newExtractorLink(
                                                source = serverName, name = "$serverName ${qLabel}p", url = u,
                                                type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) { this.referer = "https://rumble.com"; this.quality = quality }
                                        )
                                    } catch (_: Throwable) {}
                                }
                                if (emitted) {
                                    Log.i(TAG, "extractRumble: get.json ua.tar emitted")
                                    return true
                                }
                            }
                        }

                        // Fallback regex
                        if (!emitted) {
                            var fbCount = 0
                            for (m in Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(jsonText)) {
                                val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                                try {
                                    trackingCb(
                                        newExtractorLink(
                                            source = serverName, name = serverName, url = u,
                                            type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) { this.referer = "https://rumble.com" }
                                    )
                                    fbCount++
                                } catch (_: Throwable) {}
                            }
                            if (fbCount > 0) {
                                Log.i(TAG, "extractRumble: get.json regex fallback emitted $fbCount URLs")
                                if (emitted) return true
                            }
                        }
                    } else {
                        Log.i(TAG, "extractRumble: get.json returned HTML or non-JSON (httpCode=${resp.code}, len=${jsonText.length}), skipping")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "extractRumble: get.json fetch failed: ${e.message}")
                }
            }

            // b) /embedjs/<vkey> — script JS que contiene URLs de video en formato JSON
            try {
                val embedJsUrl = "https://rumble.com/embedjs/$vkey"
                val resp = app.get(embedJsUrl, headers = altHeaders, timeout = 20L)
                val jsText = resp.text
                Log.i(TAG, "extractRumble: embedJS vkey=$vkey httpCode=${resp.code} jsLen=${jsText.length} head=${jsText.take(200)}")

                if (resp.code == 200 && jsText.length > 1000) {
                    // El JS contiene un objeto JSON con las URLs. Buscar patrones:
                    // "hls":{"auto":{"url":"https://...m3u8"}}
                    // "ua":{"tar":{"1080":{"url":"https://...mp4"}}}
                    var jsEmitted = 0

                    // hls.auto.url
                    val hlsAutoMatch = Regex(""""hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(jsText)
                    if (hlsAutoMatch != null) {
                        val u = hlsAutoMatch.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                        try {
                            generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                            Log.i(TAG, "extractRumble: embedJS hls.auto emitted: ${u.take(80)}")
                            jsEmitted++
                        } catch (_: Throwable) {}
                    }

                    // ua.tar.{quality}.url
                    val tarBlockMatch = Regex(""""ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})""").find(jsText)
                    if (tarBlockMatch != null) {
                        val tarBlock = tarBlockMatch.groupValues[1]
                        Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
                            val qLabel = match.groupValues[1]
                            val u = match.groupValues[2].replace("\\/", "/").replace("\\u0026", "&")
                            if (u.isBlank()) return@forEach
                            val quality = when (qLabel) {
                                "2160", "1440" -> Qualities.P2160.value
                                "1080" -> Qualities.P1080.value
                                "720" -> Qualities.P720.value
                                "480" -> Qualities.P480.value
                                "360" -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
                            try {
                                trackingCb(
                                    newExtractorLink(
                                        source = serverName,
                                        name = "$serverName ${qLabel}p",
                                        url = u,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://rumble.com"
                                        this.quality = quality
                                    }
                                )
                                jsEmitted++
                            } catch (_: Throwable) {}
                        }
                    }

                    // Fallback: scan any .mp4 / .m3u8 URL en el JS
                    if (jsEmitted == 0) {
                        for (m in Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(jsText)) {
                            val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                            val isM3u8 = u.contains(".m3u8")
                            try {
                                trackingCb(
                                    newExtractorLink(
                                        source = serverName,
                                        name = serverName,
                                        url = u,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://rumble.com"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                jsEmitted++
                            } catch (_: Throwable) {}
                        }
                    }

                    if (jsEmitted > 0) {
                        Log.i(TAG, "extractRumble: embedJS emitted $jsEmitted URLs")
                        if (emitted) return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "extractRumble: embedJS fetch failed: ${e.message}")
            }

            // b) /api/Media/oembed?url=<embed>
            if (!emitted) {
                try {
                    val oembedUrl = "https://rumble.com/api/Media/oembed?url=" +
                        java.net.URLEncoder.encode(embedUrl, "UTF-8")
                    val resp = app.get(oembedUrl, headers = altHeaders, timeout = 15L)
                    val jsonText = resp.text
                    Log.i(TAG, "extractRumble: oembed vkey=$vkey httpCode=${resp.code} jsonLen=${jsonText.length} head=${jsonText.take(200)}")

                    if (resp.code == 200 && jsonText.startsWith("{")) {
                        // oEmbed devuelve {html: '<iframe src="..."></iframe>', ...}
                        // No contiene URLs directas, pero a veces tiene thumbnail_url con el vkey
                        // Lo intentamos solo como diagnóstico
                        for (m in Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(jsonText)) {
                            val u = m.groupValues[1]
                            try {
                                trackingCb(
                                    newExtractorLink(source = serverName, name = serverName, url = u,
                                        type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                        this.referer = "https://rumble.com"
                                    }
                                )
                                if (emitted) return true
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "extractRumble: oembed fetch failed: ${e.message}")
                }
            }

            // c) /<vkey> (public video page, no /embed/)
            if (!emitted) {
                try {
                    val publicUrl = "https://rumble.com/$vkey"
                    val publicHeaders = altHeaders.toMutableMap().apply {
                        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        put("Sec-Fetch-Dest", "document")
                        put("Sec-Fetch-Mode", "navigate")
                        put("Sec-Fetch-Site", "cross-site")
                        put("Upgrade-Insecure-Requests", "1")
                    }
                    val resp = app.get(publicUrl, headers = publicHeaders, timeout = 20L)
                    val html = resp.text
                    Log.i(TAG, "extractRumble: publicPage vkey=$vkey httpCode=${resp.code} htmlLen=${html.length} head=${html.take(200)}")

                    if (resp.code == 200 && html.length > 5000) {
                        // Buscar patrones de video URLs en el HTML de la página pública
                        // Rumble incluye <meta property="og:video" content="...mp4"> o un JSON con URLs
                        val hlsAutoMatch = Regex(""""hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(html)
                        if (hlsAutoMatch != null) {
                            val u = hlsAutoMatch.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                            try {
                                generateM3u8(serverName, u, "https://rumble.com").forEach(trackingCb)
                                Log.i(TAG, "extractRumble: publicPage hls.auto emitted: ${u.take(80)}")
                                if (emitted) return true
                            } catch (_: Throwable) {}
                        }

                        // mp4 direct URLs
                        for (m in Regex("""(https?://[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*)""").findAll(html)) {
                            val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                            if (u.contains("rmbl.ws") || u.contains(".m3u8") || u.contains(".mp4")) {
                                try {
                                    trackingCb(
                                        newExtractorLink(source = serverName, name = serverName, url = u,
                                            type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                            this.referer = "https://rumble.com"
                                        }
                                    )
                                    if (emitted) return true
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "extractRumble: publicPage fetch failed: ${e.message}")
                }
            }
        }

        // ===================== MÉTODO 2: fallback al scrape del embed page (caso 200) =====================
        // v25 FIX CRÍTICO: Referer debe ser donghualife (el sitio que embebe), NO rumble.com.
        // Rumble valida Referer para detectar hotlinking/bots. Sin Referer=cross-site → fake 410.
        try {
            val embedHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,es;q=0.8",
                "Referer" to "https://beta.donghualife.com/",
                "Origin" to "https://beta.donghualife.com",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1",
            )
            val resp = app.get(embedUrl, referer = "https://beta.donghualife.com/", headers = embedHeaders, timeout = 30L)
            val html = resp.text
            Log.i(TAG, "extractRumble: embedPage httpCode=${resp.code} htmlLen=${html.length} (with donghualife Referer)")

            if (resp.code == 200 && html.length > 5000) {
                // HLS auto
                val hlsAutoPattern = Regex(""""hls"\s*:\s*\{[^{}]*"auto"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""")
                hlsAutoPattern.find(html)?.let { m ->
                    val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                    try {
                        generateM3u8(serverName, u, referer).forEach(trackingCb)
                        if (emitted) return true
                    } catch (_: Throwable) {}
                }
                // tar qualities
                val tarBlockMatch = Regex(""""ua"\s*:\s*\{[^{}]*"tar"\s*:\s*(\{[^}]+\})""").find(html)
                if (tarBlockMatch != null) {
                    val tarBlock = tarBlockMatch.groupValues[1]
                    Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { match ->
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
                        trackingCb(
                            newExtractorLink(source = serverName, name = "$serverName ${qLabel}p", url = u,
                                type = ExtractorLinkType.M3U8) {
                                this.referer = referer
                                this.quality = quality
                            }
                        )
                    }
                    if (emitted) return true
                }
                // rmbl.ws CDN
                for (pattern in listOf(
                    Regex("""["'](https?://[^"']*rmbl\.ws[^"']*\.mp4[^"']*)["']"""),
                    Regex("""["'](https?://[^"']*rmbl\.ws[^"']*)["']"""),
                )) {
                    val matches = pattern.findAll(html).toList()
                    if (matches.isNotEmpty()) {
                        for (match in matches) {
                            val u = match.groupValues[1]
                            val quality = when {
                                u.contains("1080") -> Qualities.P1080.value
                                u.contains("720") -> Qualities.P720.value
                                u.contains("480") -> Qualities.P480.value
                                else -> Qualities.Unknown.value
                            }
                            trackingCb(
                                newExtractorLink(source = serverName, name = "$serverName ${quality / 1000}p",
                                    url = u, type = ExtractorLinkType.VIDEO) {
                                    this.referer = referer
                                    this.quality = quality
                                }
                            )
                        }
                        if (emitted) return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractRumble: embedPage fetch failed: ${e.message}")
        }

        // ===================== MÉTODO 3: WebView-based extraction (bypassa Cloudflare) =====================
        // v23 FIX: Rumble agregó Cloudflare bot detection al /embed/<vkey>:
        //   - HTTP directo → 410 con HTML "Video not found" (1390 bytes)
        //   - /api/Media?vkey= → 301 redirect a homepage (407KB HTML)
        //   - El video SÍ existe (la página donghualife lo embebe exitosamente)
        // Solución: cargar el embed URL en WebView real. El WebView ejecuta JS,
        // resuelve el challenge de Cloudflare (setea cookie __cf_bm), y carga el
        // player real de Rumble. Después inyectamos JS para extraer las URLs.
        if (!emitted) {
            try {
                val wvResult = extractRumbleViaWebView(embedUrl, vkey, moviePageUrl = referer)
                if (wvResult != null) {
                    val (hlsUrl, mp4Urls) = wvResult
                    Log.i(TAG, "extractRumble: WEBVIEW captured hls=${hlsUrl?.take(80)} mp4Count=${mp4Urls.size}")

                    // 1) HLS m3u8 principal
                    if (!hlsUrl.isNullOrEmpty() && hlsUrl.contains(".m3u8")) {
                        try {
                            generateM3u8(serverName, hlsUrl, "https://rumble.com").forEach(trackingCb)
                            Log.i(TAG, "extractRumble: WEBVIEW hls emitted: ${hlsUrl.take(80)}")
                            if (emitted) return true
                        } catch (e: Exception) {
                            Log.w(TAG, "extractRumble: WEBVIEW hls generateM3u8 failed: ${e.message}")
                        }
                    }

                    // 2) MP4 qualities (ua.tar.* — vienen como lista de Pair<url, qualityLabel>)
                    for ((u, qLabel) in mp4Urls) {
                        val quality = when (qLabel) {
                            "2160", "1440" -> Qualities.P2160.value
                            "1080" -> Qualities.P1080.value
                            "720" -> Qualities.P720.value
                            "480" -> Qualities.P480.value
                            "360" -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        val isM3u8 = u.contains(".m3u8")
                        try {
                            trackingCb(
                                newExtractorLink(
                                    source = serverName,
                                    name = "$serverName ${qLabel}p",
                                    url = u,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://rumble.com"
                                    this.quality = quality
                                }
                            )
                        } catch (_: Throwable) {}
                    }
                    if (emitted) {
                        Log.i(TAG, "extractRumble: WEBVIEW mp4 emitted ${mp4Urls.size} qualities")
                        return true
                    }
                } else {
                    Log.w(TAG, "extractRumble: WEBVIEW returned null for $embedUrl")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "extractRumble: WEBVIEW method failed: ${e.message}")
            }
        }

        Log.w(TAG, "extractRumble: all methods failed for $embedUrl (vkey=$vkey)")
        return emitted
    }

    /**
     * v23: Carga la página embed de Rumble en un WebView real para bypassar
     * Cloudflare bot detection. Después de que la página carga, inyecta JS que:
     *   1. Intercepta fetch/XHR para capturar playlist .m3u8 y segmentos .mp4
     *   2. Lee el <video> tag (currentSrc) que el player setea al cargar
     *   3. Scrapea el HTML del player buscando URLs .m3u8 y .mp4
     *
     * @param embedUrl URL completa del embed (https://rumble.com/embed/<vkey>/?pub=...)
     * @param vkey vkey extraído del embedUrl (para logging)
     * @return Pair(hlsUrl, mp4Urls) donde mp4Urls es List<Pair<url, qualityLabel>>
     *         o null si falla
     */
    private suspend fun extractRumbleViaWebView(
        embedUrl: String,
        vkey: String?,
        moviePageUrl: String? = null
    ): Pair<String?, List<Pair<String, String>>>? {
        // Adquirir Context (mismo strategy que tryWebViewResolver)
        val ctx: Context? = try {
            var c: Context? = null
            try {
                val m = Class.forName("com.lagradost.api.ContextHelper_jvmKt")
                    .declaredMethods.firstOrNull { it.name == "getContext" }
                if (m != null) {
                    @Suppress("UNCHECKED_CAST")
                    c = m.invoke(null) as? Context
                }
            } catch (_: Throwable) {}
            if (c == null) {
                try {
                    val cls = Class.forName("com.lagradost.cloudstream3.AcraApplication")
                    val field = cls.getDeclaredField("context")
                    field.isAccessible = true
                    c = field.get(null) as? Context
                } catch (_: Throwable) {}
            }
            if (c == null) {
                try {
                    val atCls = Class.forName("android.app.ActivityThread")
                    val m = atCls.getDeclaredMethod("currentApplication")
                    m.isAccessible = true
                    c = m.invoke(null) as? Context
                } catch (_: Throwable) {}
            }
            c
        } catch (_: Throwable) { null }
        if (ctx == null) {
            Log.w(TAG, "extractRumble WEBVIEW: no Context available")
            return null
        }

        // Script JS que captura URLs del player de Rumble
        val script = """
            (function() {
                try {
                    // 1. Intercept fetch para capturar playlists m3u8 y mp4
                    if (!window.__cs3RumbleIntercepted) {
                        window.__cs3RumbleIntercepted = true;
                        window.__cs3RumbleUrls = [];
                        var origFetch = window.fetch;
                        window.fetch = function() {
                            var args = arguments;
                            var fetchUrl = (typeof args[0] === 'string') ? args[0] :
                                           (args[0] && args[0].url) ? args[0].url : '';
                            return origFetch.apply(this, args).then(function(resp) {
                                try {
                                    if (fetchUrl && (fetchUrl.indexOf('.m3u8') >= 0 ||
                                        fetchUrl.indexOf('.mp4') >= 0 ||
                                        fetchUrl.indexOf('rmbl.ws') >= 0 ||
                                        fetchUrl.indexOf('playlist') >= 0 ||
                                        fetchUrl.indexOf('/embedjs/') >= 0)) {
                                        window.__cs3RumbleUrls.push({
                                            type: 'fetch',
                                            url: fetchUrl,
                                            status: resp.status
                                        });
                                        // Si es embedjs, capturar el body (tiene URLs de video)
                                        if (fetchUrl.indexOf('/embedjs/') >= 0 || fetchUrl.indexOf('.m3u8') >= 0) {
                                            try {
                                                var clone = resp.clone();
                                                clone.text().then(function(txt) {
                                                    window.__cs3RumbleUrls.push({
                                                        type: 'body',
                                                        url: fetchUrl,
                                                        body: txt.substring(0, 200000)
                                                    });
                                                }).catch(function(){});
                                            } catch(e) {}
                                        }
                                    }
                                } catch(e) {}
                                return resp;
                            });
                        };
                        // También interceptar XMLHttpRequest (algunos players lo usan)
                        var origOpen = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, url) {
                            try {
                                if (url && (String(url).indexOf('.m3u8') >= 0 ||
                                    String(url).indexOf('.mp4') >= 0 ||
                                    String(url).indexOf('rmbl.ws') >= 0 ||
                                    String(url).indexOf('/embedjs/') >= 0)) {
                                    window.__cs3RumbleUrls.push({
                                        type: 'xhr',
                                        url: String(url),
                                        status: 0
                                    });
                                }
                            } catch(e) {}
                            return origOpen.apply(this, arguments);
                        };
                    }

                    // 2. Disparar captura tras 10s (dar tiempo a Cloudflare + player load)
                    setTimeout(function() {
                        if (window.__cs3RumbleCaptured) return;
                        window.__cs3RumbleCaptured = true;
                        try {
                            var captured = {
                                videoSrcs: [],
                                sourceSrcs: [],
                                iframeSrcs: [],
                                htmlSnapshot: '',
                                fetchUrls: window.__cs3RumbleUrls || []
                            };

                            // Capturar <video> src / currentSrc
                            try {
                                document.querySelectorAll('video').forEach(function(v) {
                                    if (v.src) captured.videoSrcs.push(v.src);
                                    if (v.currentSrc && v.currentSrc !== v.src) captured.videoSrcs.push(v.currentSrc);
                                });
                            } catch(e) {}

                            // Capturar <source> src
                            try {
                                document.querySelectorAll('source').forEach(function(s) {
                                    if (s.src) captured.sourceSrcs.push(s.src);
                                });
                            } catch(e) {}

                            // Capturar <iframe> src (por si hay player anidado)
                            try {
                                document.querySelectorAll('iframe').forEach(function(i) {
                                    if (i.src) captured.iframeSrcs.push(i.src);
                                });
                            } catch(e) {}

                            // Snapshot del HTML del player (para buscar URLs m3u8/mp4 embebidas)
                            try {
                                captured.htmlSnapshot = document.documentElement.outerHTML.substring(0, 800000);
                            } catch(e) {}

                            // v24 DIAGNOSTIC: capturar metadata adicional para logs
                            try {
                                captured.htmlLength = document.documentElement.outerHTML.length;
                                captured.htmlHead = document.documentElement.outerHTML.substring(0, 500);
                                captured.docTitle = document.title || '';
                                captured.cookieLen = (document.cookie || '').length;
                                captured.cookieNames = (document.cookie || '').split(';').map(function(c){return c.split('=')[0].trim();}).filter(Boolean).join(',');
                            } catch(e) {}

                            Android.onRumbleCaptured(JSON.stringify(captured));
                        } catch(e) {
                            try { Android.onRumbleCaptured('{"error":"' + e.toString() + '"}'); } catch(ee) {}
                        }
                    }, 18000);
                } catch(e) {
                    try { Android.onRumbleCaptured('{"error":"' + e.toString() + '"}'); } catch(ee) {}
                }
            })();
        """.trimIndent()

        var webView: WebView? = null
        // v25: declarar FUERA del withTimeoutOrNull para que el parser pueda acceder.
        val capturedNetworkUrls = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val capturedJson = withTimeoutOrNull(45000L) {
            suspendCoroutine<String?> { cont ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        val wv = WebView(ctx)
                        webView = wv
                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        wv.settings.mediaPlaybackRequiresUserGesture = false
                        wv.settings.blockNetworkImage = true
                        // v25: habilitar cookies por si Rumble setea alguna tras resolver Referer
                        try {
                            wv.settings.javaScriptCanOpenWindowsAutomatically = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                        } catch (_: Throwable) {}
                        // v25 FIX CRÍTICO: Override UA a desktop Chrome.
                        // El UA default de WebView incluye "; wv)" que Rumble detecta como bot.
                        try {
                            wv.settings.userAgentString =
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        } catch (_: Throwable) {}

                        var finished = false
                        var http410Count = false

                        wv.addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onRumbleCaptured(json: String) {
                                Log.i(TAG, "extractRumble WEBVIEW: onRumbleCaptured len=${json.length}")
                                if (!finished) {
                                    finished = true
                                    cont.resume(json)
                                }
                            }
                        }, "Android")

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                // v25: capturar URLs de video de TODOS los requests (incluye iframe cross-origin)
                                try {
                                    val u = request?.url?.toString() ?: ""
                                    if (u.isNotEmpty() && (
                                        u.contains(".m3u8") || u.contains(".mp4") ||
                                        u.contains("rmbl.ws") || u.contains("/embedjs/") ||
                                        u.contains("playlist") || u.contains("master"))) {
                                        capturedNetworkUrls.add(u)
                                        Log.i(TAG, "extractRumble WEBVIEW intercepted: $u")
                                    }
                                } catch (_: Throwable) {}
                                return null  // no interceptar, solo observar
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.i(TAG, "extractRumble WEBVIEW: onPageFinished url=$url")

                                // v25: inyectar script de captura en cada page load.
                                // (Se re-inyecta tras navegar a donghualife en el fallback.)
                                Log.i(TAG, "extractRumble WEBVIEW: injecting script (waiting 18s)")
                                view?.evaluateJavascript(script) { _ ->
                                    Log.i(TAG, "extractRumble WEBVIEW: script injected, waiting 18s for capture...")
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                Log.i(TAG, "extractRumble WEBVIEW error: ${error?.description} url=${request?.url}")
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                val code = errorResponse?.statusCode
                                val url = request?.url?.toString() ?: ""
                                Log.i(TAG, "extractRumble WEBVIEW httpError: code=$code reason=${errorResponse?.reasonPhrase} url=$url")

                                // v25 FALLBACK: si embed URL recibe 410 a pesar del Referer header,
                                // navegar el WebView a la página de la película en donghualife, y luego
                                // inyectar un <iframe> apuntando al embed URL. El iframe heredará
                                // Referer=moviePageUrl automáticamente.
                                // v26: usar moviePageUrl (la URL específica de la película) en vez
                                // de la homepage — da un Referer más auténtico.
                                if (code == 410 && url.contains("/embed/") && !http410Count) {
                                    http410Count = true
                                    val fallbackUrl = moviePageUrl ?: "https://beta.donghualife.com/"
                                    Log.i(TAG, "extractRumble WEBVIEW: 410 recibido, fallback: navegar a $fallbackUrl + iframe injection")
                                    val dhHeaders = mapOf(
                                        "Accept-Language" to "en-US,en;q=0.9,es;q=0.8",
                                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                        "Sec-Fetch-Dest" to "document",
                                        "Sec-Fetch-Mode" to "navigate",
                                        "Sec-Fetch-Site" to "none",
                                        "Upgrade-Insecure-Requests" to "1"
                                    )
                                    view?.loadUrl(fallbackUrl, dhHeaders)
                                    // Tras 3s, inyectar iframe apuntando al embed URL (Referer=donghualife)
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (!finished) {
                                            Log.i(TAG, "extractRumble WEBVIEW: inyectando iframe tras donghualife load")
                                            val iframeScript = """
                                                (function() {
                                                    try {
                                                        var ifr = document.createElement('iframe');
                                                        ifr.style.width = '640px';
                                                        ifr.style.height = '360px';
                                                        ifr.style.position = 'fixed';
                                                        ifr.style.top = '0';
                                                        ifr.style.left = '0';
                                                        ifr.style.zIndex = '9999';
                                                        ifr.src = '$embedUrl';
                                                        document.body.appendChild(ifr);
                                                    } catch(e) {}
                                                })();
                                            """.trimIndent()
                                            view?.evaluateJavascript(iframeScript) {}
                                        }
                                    }, 3000L)
                                }
                            }
                        }

                        // v25 FIX CRÍTICO: cargar embed URL DIRECTO con Referer = donghualife.
                        // Rumble verifica Referer — sin él devuelve fake 410 "Video not found".
                        val embedHeaders = mapOf(
                            "Referer" to "https://beta.donghualife.com/",
                            "Origin" to "https://beta.donghualife.com",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                            "Accept-Language" to "en-US,en;q=0.9,es;q=0.8",
                            "Sec-Fetch-Dest" to "iframe",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "cross-site",
                            "Sec-Fetch-User" to "?1",
                            "Upgrade-Insecure-Requests" to "1"
                        )
                        Log.i(TAG, "extractRumble WEBVIEW: loading embed URL with Referer=beta.donghualife.com")
                        wv.loadUrl(embedUrl, embedHeaders)
                    } catch (e: Exception) {
                        Log.i(TAG, "extractRumble WEBVIEW: WebView creation error: ${e.message}")
                        cont.resume(null)
                    }
                }
            }
        }

        // Limpiar WebView SIEMPRE
        Handler(Looper.getMainLooper()).post {
            try {
                webView?.stopLoading()
                webView?.removeJavascriptInterface("Android")
                webView?.destroy()
            } catch (_: Exception) {}
        }
        webView = null

        if (capturedJson.isNullOrEmpty()) {
            // v25: aún si el JS no respondió, podemos tener URLs capturadas via shouldInterceptRequest
            if (capturedNetworkUrls.isNotEmpty()) {
                Log.i(TAG, "extractRumble WEBVIEW: JS timeout pero ${capturedNetworkUrls.size} URLs interceptadas — usando esas")
                var hlsUrl: String? = null
                val allUrls = mutableListOf<Pair<String, String>>()
                capturedNetworkUrls.forEach { u ->
                    val clean = u.replace("\\/", "/").replace("\\u0026", "&")
                    if (clean.contains(".m3u8") && hlsUrl == null) {
                        hlsUrl = clean
                    } else if (clean.contains(".mp4") || clean.contains("rmbl.ws")) {
                        allUrls.add(clean to guessQuality(clean))
                    }
                }
                if (hlsUrl != null || allUrls.isNotEmpty()) {
                    Log.i(TAG, "extractRumble WEBVIEW: returning from intercept-only hls=${hlsUrl?.take(60)} mp4=${allUrls.size}")
                    return hlsUrl to allUrls.distinctBy { it.first }
                }
            }
            Log.w(TAG, "extractRumble WEBVIEW: no data captured (timeout) and no intercepted URLs")
            return null
        }

        // Parsear el JSON capturado (sin Gson — regex + parseJson<CapturedRumbleData>)
        try {
            val parsed = parseJson<CapturedRumbleData>(capturedJson)
            val allUrls = mutableListOf<Pair<String, String>>()
            var hlsUrl: String? = null

            // v24 DIAGNOSTIC: log diagnostic fields para entender qué vio el WebView
            Log.i(TAG, "extractRumble WEBVIEW DIAG: docTitle='${parsed.docTitle}' " +
                "htmlLength=${parsed.htmlLength ?: "null"} " +
                "htmlSnapshotLen=${parsed.htmlSnapshot?.length ?: 0} " +
                "cookieLen=${parsed.cookieLen ?: 0} " +
                "cookieNames='${parsed.cookieNames ?: ""}' " +
                "videoSrcs=${parsed.videoSrcs?.size ?: 0} " +
                "sourceSrcs=${parsed.sourceSrcs?.size ?: 0} " +
                "iframeSrcs=${parsed.iframeSrcs?.size ?: 0} " +
                "fetchUrls=${parsed.fetchUrls?.size ?: 0}")
            if (!parsed.htmlHead.isNullOrEmpty()) {
                Log.i(TAG, "extractRumble WEBVIEW DIAG: htmlHead=${parsed.htmlHead.take(300)}")
            }
            if (!parsed.error.isNullOrEmpty()) {
                Log.w(TAG, "extractRumble WEBVIEW DIAG: JS error: ${parsed.error}")
            }

            // 1) De <video> / <source> / <iframe>
            parsed.videoSrcs?.forEach { src ->
                if (src.contains(".m3u8") && hlsUrl == null) hlsUrl = src
                else if (src.contains(".mp4")) allUrls.add(src to guessQuality(src))
            }
            parsed.sourceSrcs?.forEach { src ->
                if (src.contains(".m3u8") && hlsUrl == null) hlsUrl = src
                else if (src.contains(".mp4")) allUrls.add(src to guessQuality(src))
            }
            parsed.iframeSrcs?.forEach { src ->
                if (src.contains(".m3u8") && hlsUrl == null) hlsUrl = src
                else if (src.contains(".mp4")) allUrls.add(src to guessQuality(src))
            }

            // 2) De fetch responses (buscar URLs .m3u8 y .mp4)
            parsed.fetchUrls?.forEach { fu ->
                val u = fu.url ?: return@forEach
                if (u.contains(".m3u8") && hlsUrl == null) {
                    hlsUrl = u.replace("\\/", "/").replace("\\u0026", "&")
                } else if (u.contains(".mp4")) {
                    allUrls.add(u.replace("\\/", "/").replace("\\u0026", "&") to guessQuality(u))
                }
                // Si es un body de embedjs, escanearlo por URLs
                val body = fu.body
                if (!body.isNullOrEmpty()) {
                    Regex("""(https?://[^"\\]+\.(?:m3u8|mp4)[^"\\]*)""").findAll(body).forEach { m ->
                        val url = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                        if (url.contains(".m3u8") && hlsUrl == null) {
                            hlsUrl = url
                        } else if (url.contains(".mp4")) {
                            allUrls.add(url to guessQuality(url))
                        }
                    }
                    // Buscar bloque "ua":{"tar":{...}} con calidades
                    val tarBlock = Regex(""""tar"\s*:\s*(\{[^}]+\})""").find(body)?.groupValues?.get(1)
                    if (tarBlock != null) {
                        Regex(""""(\d{3,4})"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""").findAll(tarBlock).forEach { mm ->
                            val q = mm.groupValues[1]
                            val u2 = mm.groupValues[2].replace("\\/", "/").replace("\\u0026", "&")
                            allUrls.add(u2 to q)
                        }
                    }
                }
            }

            // 3) Del HTML snapshot (si todo lo demás falló)
            if (hlsUrl == null && allUrls.isEmpty()) {
                val html = parsed.htmlSnapshot ?: ""
                Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(html).forEach { m ->
                    val u = m.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                    if (u.contains(".m3u8") && hlsUrl == null) hlsUrl = u
                    else if (u.contains(".mp4")) allUrls.add(u to guessQuality(u))
                }
            }

            // 4) v25 FALLBACK CRÍTICO: URLs capturadas via shouldInterceptRequest.
            // Estas son URLs de requests de red hechos por el WebView (incluye iframe cross-origin).
            // Es la ÚLTIMA fuente confiable cuando el iframe carga el player de Rumble y el JS top
            // no puede ver dentro del iframe (mismo-origin policy).
            if (capturedNetworkUrls.isNotEmpty()) {
                Log.i(TAG, "extractRumble WEBVIEW: checking ${capturedNetworkUrls.size} intercepted URLs")
                capturedNetworkUrls.forEach { u ->
                    val clean = u.replace("\\/", "/").replace("\\u0026", "&")
                    if (clean.contains(".m3u8") && hlsUrl == null) {
                        hlsUrl = clean
                        Log.i(TAG, "extractRumble WEBVIEW: hls from intercept: ${clean.take(80)}")
                    } else if (clean.contains(".mp4") || clean.contains("rmbl.ws")) {
                        allUrls.add(clean to guessQuality(clean))
                        Log.i(TAG, "extractRumble WEBVIEW: mp4 from intercept: ${clean.take(80)}")
                    }
                }
            }

            Log.i(TAG, "extractRumble WEBVIEW: parsed hls=${hlsUrl?.take(60)} mp4=${allUrls.size} videoSrcs=${parsed.videoSrcs?.size ?: 0} fetchUrls=${parsed.fetchUrls?.size ?: 0} intercepted=${capturedNetworkUrls.size}")
            return hlsUrl to allUrls.distinctBy { it.first }
        } catch (e: Exception) {
            Log.w(TAG, "extractRumble WEBVIEW: parse error: ${e.message}")
            return null
        }
    }

    /** Heurística para inferir calidad de una URL mp4 basándose en patrones comunes */
    private fun guessQuality(url: String): String {
        return when {
            url.contains("2160") || url.contains("1440") -> "2160"
            url.contains("1080") -> "1080"
            url.contains("720") -> "720"
            url.contains("480") -> "480"
            url.contains("360") -> "360"
            else -> "720"  // default razonable para Rumble
        }
    }

    private suspend fun extractDailymotion(
        embedUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        val trackingCb: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }
        val videoId = Regex("video=([A-Za-z0-9]+)").find(embedUrl)?.destructured?.component1()
            ?: Regex("/video/([A-Za-z0-9]+)").find(embedUrl)?.destructured?.component1()
            ?: Regex("/embed/video/([A-Za-z0-9]+)").find(embedUrl)?.destructured?.component1()
            ?: return false

        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val jsonText = app.get(apiUrl,
                referer = "https://www.dailymotion.com/embed/video/$videoId",
                headers = mapOf(
                    "User-Agent" to browserUA,
                    "Accept" to "application/json",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Origin" to "https://www.dailymotion.com",
                ),
                timeout = 15L).text
            Log.i(TAG, "extractDailymotion: videoId=$videoId jsonLen=${jsonText.length}")

            for (match in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(jsonText)) {
                try {
                    generateM3u8(serverName, match.value, "https://www.dailymotion.com").forEach(trackingCb)
                    if (emitted) return true
                } catch (e: Exception) {
                    Log.w(TAG, "extractDailymotion: m3u8 method failed: ${e.message}")
                }
            }
            val mp4Urls = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(jsonText).map { it.value }.distinct().toList()
            for (u in mp4Urls) {
                val q = when {
                    u.contains("1080") -> Qualities.P1080.value
                    u.contains("720") -> Qualities.P720.value
                    u.contains("480") -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                trackingCb(newExtractorLink(source = serverName, name = "$serverName ${q/1000}p", url = u) {
                    this.referer = "https://www.dailymotion.com"
                    this.quality = q
                })
            }
            if (emitted) return true
        } catch (e: Exception) {
            Log.w(TAG, "extractDailymotion: metadata fetch failed for videoId=$videoId: ${e.message}")
        }

        // Fallback: probar loadExtractor con la URL de embed
        try {
            loadExtractor("https://www.dailymotion.com/embed/video/$videoId", referer, subtitleCallback = {}, trackingCb)
        } catch (e: Exception) {
            Log.w(TAG, "extractDailymotion: loadExtractor fallback failed: ${e.message}")
        }
        return emitted
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
        /** Lista COMPLETA de números de episodio disponibles (de initialEpisodes[]).
         *  Vacía si solo conocemos episodeCount + firstEpNumber. */
        val episodeNumbers: List<Int> = emptyList(),
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
