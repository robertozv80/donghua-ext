package com.donghuaworld

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class DonghuaWorldProvider : MainAPI() {
    override var mainUrl = "https://donghuaworld.com"
    override var name = "DonghuaWorld"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val PLAYER_BASE = "https://player.donghuaplanet.com"

        /** Regex to match episode numbers or ranges like "Episode 263" or "Episode 254-255" */
        private val EPISODE_NUM_REGEX = Regex("""Episode\s+(\d+(?:-\d+)?)""", RegexOption.IGNORE_CASE)

        /** Languages to include as subtitles (Spanish primary, English fallback) */
        private val SUBTITLE_LANGUAGES = setOf("Spanish", "English")
    }

    // ==================== MAIN PAGE ====================
    // FIX 2026-08-11: Refactorizado para usar selectores CSS basados en clases en lugar
    // de matching por texto de heading. Mas robusto y menos propenso a romperse si el
    // sitio cambia el texto del heading (ej: "Series Update" → "Hot Series Update").
    //
    // Ademas, agrega la nueva seccion "Completed" que viene de la URL
    // /anime/?status=completed&sub=&order=latest (con paginacion via ?page=N).
    //
    // Estructura HTML del homepage (verificada con BS4):
    //   <div class="releases hothome"><h3>...Series Update...</h3></div>
    //   <div class="listupd popularslider">...<article>...</article>...</div>   ← Hot Series Update (5 articles)
    //   <div class="releases latesthome"><h3>Latest Release</h3></div>
    //   <div class="listupd normal">...<article>...</article>...</div>           ← Latest Release (~30 articles)
    //   <div class="releases"><h3>Recommendation</h3></div>
    //   <div class="series-gen">...<article>...</article>...</div>              ← Recommendation (25 articles)
    //
    // Para "Completed" (separada): GET /anime/?status=completed&sub=&order=latest
    //   <div class="listupd">...<article>...</article>...</div>                  ← 20 series articles
    //   Paginacion: ?page=N&status=completed&sub=&order=latest

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Hot Series Update",
        "$mainUrl/##latest" to "Latest Release",
        "$mainUrl/##recommendation" to "Recommendation",
        "$mainUrl/anime/?status=completed&sub=&order=latest" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionName = request.name
        val sectionUrl = request.data

        // === Completed section: separate page with its own pagination ===
        if (sectionName == "Completed") {
            // URL: /anime/?status=completed&sub=&order=latest
            // Page N: ?page=N&status=completed&sub=&order=latest
            val url = if (page > 1) {
                "$mainUrl/anime/?page=$page&status=completed&sub=&order=latest"
            } else {
                sectionUrl
            }
            val document = app.get(url).document
            val items = document.select(".listupd article").mapNotNull { art ->
                parseArticleCard(art)
            }
            // Paginacion: si la pagina actual tiene 20 articulos, hay mas paginas
            val hasNext = items.size >= 20
            return newHomePageResponse(
                listOf(HomePageList(sectionName, items)),
                hasNext = hasNext
            )
        }

        // === Homepage sections (Hot/Latest/Recommendation) ===
        // Solo la primera pagina trae el HTML del homepage; a partir de pagina 2
        // cargamos /page/N/ para la seccion Latest Release.
        val document = if (page == 1) {
            app.get(sectionUrl.substringBefore("##")).document
        } else {
            if (sectionUrl.contains("latest")) {
                app.get("$mainUrl/page/$page/").document
            } else {
                return newHomePageResponse(emptyList(), hasNext = false)
            }
        }

        val items = when {
            sectionName == "Hot Series Update" -> document.select(".listupd.popularslider article").mapNotNull { parseArticleCard(it) }
            sectionName == "Latest Release" -> document.select(".listupd.normal article").mapNotNull { parseArticleCard(it) }
            sectionName == "Recommendation" -> document.select(".series-gen article").mapNotNull { parseArticleCard(it) }
            else -> emptyList()
        }

        return newHomePageResponse(
            listOf(HomePageList(sectionName, items)),
            hasNext = sectionName == "Latest Release" && items.isNotEmpty()
        )
    }

    /**
     * Parse a single article card into a SearchResponse.
     * FIX 2026-07-29: La home de donghuaworld.com lista URLs de EPISODIOS
     * (https://donghuaworld.com/martial-master-episode-678-...), no URLs de series.
     * Pasamos la URL de episodio tal cual a load(); load() la resuelve via
     * resolveSeriesUrlFromEpisode() leyendo el breadcrumb de la pagina de episodio.
     * Esto es 1 HTTP request extra pero es 100% confiable: el slug del episodio
     * a veces NO coincide con el slug de la serie (ej: "swallowed-star-warrior-of-the-galaxy"
     * → serie real "/anime/swallowed-the-universe-warrior-of-galaxy/").
     * La heuristica anterior (convertEpisodeUrlToSeriesUrl) producia 404 en esos casos.
     */
    private fun parseArticleCard(article: org.jsoup.nodes.Element): SearchResponse? {
        val linkEl = article.selectFirst("a[href]") ?: return null
        val episodeUrl = linkEl.attr("abs:href")
        if (episodeUrl.isEmpty()) return null

        val title = linkEl.attr("title").takeIf { it.isNotEmpty() }
            ?: linkEl.selectFirst(".eggtitle")?.text()?.trim()
            ?: linkEl.selectFirst("h2")?.text()?.trim()
            ?: return null

        val img = linkEl.selectFirst("img")?.let { imgEl ->
            imgEl.attr("data-src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: imgEl.attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: imgEl.attr("src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
        } ?: ""

        // FIX: Extraer número de episodio del título o del badge
        val epNum = EPISODE_NUM_REGEX.find(title)?.groupValues?.get(1)?.let { numStr ->
            numStr.substringBefore("-").toIntOrNull()
        } ?: article.selectFirst(".epx")?.text()?.let { epText ->
            Regex("""Ep\s+(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.destructured?.component1()?.toIntOrNull()
        }

        // Extraer nombre de serie limpio (sin "Episode X" ni indicadores de calidad)
        val cleanTitle = title.replace(Regex("""\s*Episode\s+\d+(?:-\d+)?[^|]*$""", RegexOption.IGNORE_CASE), "").trim()

        // Pasamos la URL de episodio; load() la resuelve via breadcrumb (ver resolveSeriesUrlFromEpisode)
        return newAnimeSearchResponse(cleanTitle, episodeUrl) {
            this.posterUrl = img
            addDubStatus(DubStatus.Subbed, epNum)
        }
    }

    /**
     * FIX 2026-07-28: Convierte una URL de episodio a su URL de serie.
     * Ej: /martial-master-episode-678-4k-multi-subtitles/ → /anime/martial-master/
     * Si la URL no contiene "-episode-" se devuelve tal cual (ya es URL de serie).
     */
    private fun convertEpisodeUrlToSeriesUrl(episodeUrl: String): String {
        return try {
            val uri = java.net.URI(episodeUrl)
            val path = uri.path ?: return episodeUrl
            // Extraer el slug: tomar el último segmento del path
            val segments = path.split("/").filter { it.isNotEmpty() }
            val slug = segments.lastOrNull() ?: return episodeUrl
            // Si no parece URL de episodio, devolver tal cual
            val lowerSlug = slug.lowercase()
            val epIdx = lowerSlug.indexOf("-episode-")
            if (epIdx < 0) return episodeUrl
            // Tomar todo antes de "-episode-" (case-insensitive, preservando el case original)
            val seriesSlug = slug.substring(0, epIdx)
            if (seriesSlug.isEmpty()) return episodeUrl
            // Construir URL canónica /anime/<seriesSlug>/
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return episodeUrl
            "$scheme://$host/anime/$seriesSlug/"
        } catch (_: Exception) {
            episodeUrl
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}").document
        val items = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        document.select("article").forEach { article ->
            val linkEl = article.selectFirst("a[href]") ?: return@forEach
            val url = linkEl.attr("abs:href")

            if (!url.contains("/anime/")) return@forEach
            if (!seenUrls.add(url)) return@forEach

            val title = linkEl.attr("title").takeIf { it.isNotEmpty() }
                ?: article.selectFirst("h2")?.text()?.trim()
                ?: return@forEach

            val img = article.selectFirst("img")?.let { imgEl ->
                imgEl.attr("data-src").takeIf { it.isNotEmpty() && !it.startsWith("data:") } ?: imgEl.attr("src")
            } ?: ""

            // Extraer número de episodio si está en el título
            val epNum = EPISODE_NUM_REGEX.find(title)?.groupValues?.get(1)?.let { numStr ->
                numStr.substringBefore("-").toIntOrNull()
            }

            val cleanTitle = title.replace(Regex("""\s*Episode\s+\d+(?:-\d+)?[^|]*$""", RegexOption.IGNORE_CASE), "").trim()

            items.add(newAnimeSearchResponse(cleanTitle, url) {
                this.posterUrl = img
                addDubStatus(DubStatus.Subbed, epNum)
            })
        }
        return items
    }

    // ==================== DETAIL ====================

    override suspend fun load(url: String): LoadResponse {
        // If URL is an episode page (not /anime/), extract the series URL from breadcrumb
        val seriesUrl = if (url.contains("/anime/")) {
            url
        } else {
            resolveSeriesUrlFromEpisode(url)
        }

        val document = app.get(seriesUrl).document

        // Extract title
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        // Extract poster image
        val poster = document.selectFirst(".thumb img")?.let { getImgSrc(it) }
            ?: document.selectFirst(".bigcontent .ts-post-image")?.let { getImgSrc(it) }
            ?: document.selectFirst(".ts-post-image")?.let { getImgSrc(it) }
            ?: ""

        // FIX 2026-07-29: Extraer la SINOPSIS real desde .bixbox.synp .entry-content.
        // Antes se usaba ".mindes, .alldes, .entry-content, .desc" pero:
        //  - ".mindes" NO existe (la clase real es ".mindesc" con 'c' al final)
        //  - ".desc" contiene el boilerplate SEO ("Watch streaming <title> English Subbed...")
        //  - ".entry-content" dentro de .bixbox.synp contiene la SINOPSIS narrativa
        // Ademas, el usuario pidio ELIMINAR el primer parrafo (boilerplate) y AGREGAR la sinopsis.
        // Solucion: tomar todos los <p> del .bixbox.synp .entry-content y unirlos con \n.
        // Si no hay synopsis, caer a los selectores legacy (que devuelven el boilerplate).
        val description = run {
            val synopsisBox = document.selectFirst(".bixbox.synp .entry-content")
                ?: document.selectFirst(".synp .entry-content")
                ?: document.selectFirst("h2:contains(Synopsis)")?.parent()?.selectFirst(".entry-content")
            if (synopsisBox != null) {
                val paragraphs = synopsisBox.select("p")
                if (paragraphs.isNotEmpty()) {
                    paragraphs.joinToString("\n\n") { p ->
                        // Salto de <br> tambien como salto de linea
                        p.html().replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                            .replace(Regex("""<[^>]+>"""), "")
                            .replace("&amp;", "&").replace("&#8217;", "'")
                            .replace("&#8230;", "...").replace("&quot;", "\"")
                            .replace("&nbsp;", " ").trim()
                    }.trim()
                } else {
                    synopsisBox.text().trim()
                }
            } else {
                // Fallback: selectores legacy (incluye el boilerplate .desc)
                document.selectFirst(".mindesc, .alldes, .entry-content, .desc")?.text()?.trim() ?: ""
            }
        }

        // Extract genres
        val genres = document.select(".genxed a, .series-gen a").mapNotNull { it.text().trim() }

        // Extract status
        val showStatus = document.selectFirst(".spe span:contains(Status)")?.nextElementSibling()?.text()?.trim()
            ?.let { stat ->
                when {
                    stat.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
                    stat.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
                    else -> null
                }
            }

        // Extract year from "Released:" field
        val year = document.selectFirst(".spe span:contains(Released)")?.nextElementSibling()?.text()?.trim()
            ?.take(4)?.toIntOrNull()

        // Extract episodes from the episode list
        val episodes = mutableListOf<Episode>()
        val seenUrls = mutableSetOf<String>()

        // FIX 2026-07-28: Usar el contenedor .eplister (selector correcto del tema actual).
        // Antes se buscaba en .bxcl/.epl/.episodelist pero la clase real es .eplister.
        // Además, hay que evitar la zona .lastend (que contiene "First Episode"/"New Episode"
        // con texto confuso) y leer número/título desde .epl-num y .epl-title en lugar de
        // linkEl.text() que concatenaba todo y daba "New Episode Episode 678 (4K)".
        // FIX 2026-08-11: Admitir URLs que contengan "-movie-" ademas de "episode".
        // Series como "Perfect World Movie - Ninefold The Burning Sky" tienen episodios
        // cuyas URLs son /perfect-world-movie-ninefold-the-burning-sky-part-2-movie-4k-multi-subtitles/
        // (no contienen "episode"). Con el selector anterior, estos episodios no se
        // encontraban y la app mostraba "se ve la descripcion pero no hay video para reproducir".
        document.select(".eplister li a[href*=episode], .eplister li a[href*=-movie-]").forEach { linkEl ->
            val epUrl = linkEl.attr("abs:href")
            // Validar que la URL sea de episodio o de movie (no filtros)
            val isEpisodeUrl = epUrl.contains("episode", ignoreCase = true) || epUrl.contains("-movie-", ignoreCase = true)
            if (epUrl.isEmpty() || !isEpisodeUrl) return@forEach
            if (!seenUrls.add(epUrl)) return@forEach

            // Priorizar .epl-num y .epl-title (estructura actual del tema)
            val numText = linkEl.selectFirst(".epl-num")?.text()?.trim()
            val titleText = linkEl.selectFirst(".epl-title")?.text()?.trim()
            // FIX 2026-07-29: Si .epl-num no tiene numero (ej: "Series Haitus"),
            // caer a titleText que normalmente contiene "Episode X".
            // FIX 2026-08-11: extractEpisodeNumber ahora tambien soporta "Part X"
            // para movies (ej: "Perfect World Movie Part 2" → epNum=2).
            val epNum = numText?.let { extractEpisodeNumber(it) }
                ?: titleText?.let { extractEpisodeNumber(it) }
            val name = titleText ?: numText

            episodes.add(newEpisode(epUrl) {
                this.name = name?.takeIf { it.isNotEmpty() }
                this.episode = epNum
            })
        }

        // Method 2: Selectores legacy (.bxcl, .epl) por si el tema cambia de vuelta
        if (episodes.isEmpty()) {
            document.select(".bxcl a[href*=episode], .epl a[href*=episode], .episodelist a[href*=episode], .bxcl a[href*=-movie-], .epl a[href*=-movie-], .episodelist a[href*=-movie-]").forEach { linkEl ->
                val epUrl = linkEl.attr("abs:href")
                val isEpisodeUrl = epUrl.contains("episode", ignoreCase = true) || epUrl.contains("-movie-", ignoreCase = true)
                if (epUrl.isEmpty() || !isEpisodeUrl) return@forEach
                if (!seenUrls.add(epUrl)) return@forEach

                val epText = linkEl.text().trim()
                val epNum = extractEpisodeNumber(epText)

                episodes.add(newEpisode(epUrl) {
                    this.name = epText.takeIf { it.isNotEmpty() }
                    this.episode = epNum
                })
            }
        }

        // Method 3: Fallback - cualquier link de episodio o movie, EXCLUYENDO los de .lastend
        // (que tienen "New Episode"/"First Episode" y rompen la secuencia)
        if (episodes.isEmpty()) {
            document.select("a[href*=episode], a[href*=-movie-]").forEach { linkEl ->
                // Saltar si está dentro de .lastend (botón "New Episode"/"First Episode")
                if (linkEl.parents().any { it.hasClass("lastend") }) return@forEach

                val epUrl = linkEl.attr("abs:href")
                if (epUrl.isEmpty()) return@forEach
                if (!seenUrls.add(epUrl)) return@forEach

                val epText = linkEl.text().trim()
                // Limpiar prefijos "New Episode"/"First Episode" si aun llegaran
                val cleanText = epText
                    .replace(Regex("""^New\s+Episode\s*""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""^First\s+Episode\s*""", RegexOption.IGNORE_CASE), "")
                    .trim()
                val epNum = extractEpisodeNumber(cleanText)

                episodes.add(newEpisode(epUrl) {
                    this.name = cleanText.takeIf { it.isNotEmpty() }
                    this.episode = epNum
                })
            }
        }

        // Sort episodes by number (ascending)
        val sortedEpisodes = episodes.sortedBy { it.episode ?: 0 }

        return newAnimeLoadResponse(title, seriesUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.showStatus = showStatus
            this.year = year
            this.episodes = mutableMapOf(DubStatus.Subbed to sortedEpisodes)
        }
    }

    /**
     * Given an episode page URL, load it and extract the series URL from the breadcrumb.
     * FIX 2026-07-28: Excluir URLs de filtro como /anime/?status=ongoing que aparecen
     * antes que la URL de la serie en el HTML. Solo aceptar URLs /anime/<slug>/.
     */
    private suspend fun resolveSeriesUrlFromEpisode(episodeUrl: String): String {
        return try {
            val epDoc = app.get(episodeUrl).document
            // Buscar specifically /anime/<slug>/ (con path, no query string)
            epDoc.select("a[href*=/anime/]").firstOrNull { el ->
                val href = el.attr("abs:href")
                // Debe ser /anime/<slug>/, NO /anime/?status=... ni /anime/#...
                href.contains(Regex("""/anime/[a-z0-9][a-z0-9-]*/""", RegexOption.IGNORE_CASE))
            }?.attr("abs:href")?.takeIf { it.isNotEmpty() }
                ?: epDoc.select(".ts-breadcrumb a, .breadcrumb a, .breadcrumbs a").firstOrNull {
                    it.attr("abs:href").contains(Regex("""/anime/[a-z0-9][a-z0-9-]*/""", RegexOption.IGNORE_CASE))
                }?.attr("abs:href")
                ?: episodeUrl
        } catch (_: Exception) {
            episodeUrl
        }
    }

    /**
     * Extract episode number from text like "Episode 263" or "Episode 254-255".
     * FIX 2026-07-29: Para textos como "678 (4K)" o "2 (4K)" (sin "Episode"),
     * el fallback de digitos ahora toma el PRIMER match (no el ultimo).
     * Antes: "2 (4K)" → lastOrNull = "4" → epNum=4 (todos los episodios quedaban
     * con numero 4, CloudStream los deduplicaba y solo mostraba 1).
     * Ahora: "2 (4K)" → firstOrNull = "2" → epNum=2.
     *
     * FIX 2026-08-11: Soporte para "Part X" en movies.
     * Series como "Perfect World Movie - Ninefold The Burning Sky" tienen episodios
     * con .epl-num="Movie (4K)" y .epl-title="Perfect World Movie - ... Part 2 Movie (4K)".
     * El regex de "Episode" no hace match, y el fallback de digitos tomaría "4" (de "4K").
     * Ahora: priorizamos "Part X" antes del fallback de digitos, y eliminamos "4K"/"8K"
     * del texto antes de buscar digitos para evitar falsos positivos.
     */
    private fun extractEpisodeNumber(text: String): Int? {
        // 1. "Episode X" o "Episode X-Y"
        EPISODE_NUM_REGEX.find(text)?.groupValues?.get(1)?.let { numStr ->
            return numStr.substringBefore("-").toIntOrNull()
        }
        // 2. "Part X" (para movies divididos en partes)
        Regex("""Part\s+(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.let { partStr ->
            return partStr.toIntOrNull()
        }
        // 3. Fallback: primer digito, PERO primero eliminamos "4K"/"8K"/"2K" para evitar
        //    falsos positivos en textos como "Movie (4K)" → sin este paso, devolvería 4.
        val cleanedText = text.replace(Regex("""\d+[Kk]"""), "")
        return Regex("""(\d+)""").findAll(cleanedText).firstOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Get image src, handling lazy loading (data-src attribute).
     * FIX: Priorizar data-src sobre src (que puede ser placeholder base64)
     */
    private fun getImgSrc(imgEl: org.jsoup.nodes.Element): String {
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
        val document = app.get(data).document

        val serverButtons = document.select("a[data-hash], .btn[data-hash], .server-item[data-hash]")
        if (serverButtons.isEmpty()) return false

        var darkServerUrl: String? = null
        var engSubUrl: String? = null

        for (btn in serverButtons) {
            val hash = btn.attr("data-hash") ?: continue
            val serverName = btn.text().trim()

            try {
                val decoded = String(Base64.decode(hash, Base64.DEFAULT), Charsets.UTF_8)
                val srcMatch = Regex("""src=["']([^"']+)["']""").find(decoded)
                val iframeSrc = srcMatch?.groupValues?.get(1) ?: continue

                when {
                    serverName.contains("Dark", ignoreCase = true) -> darkServerUrl = iframeSrc
                    serverName.contains("Eng-Sub", ignoreCase = true) ||
                        serverName.contains("Dailymotion", ignoreCase = true) -> engSubUrl = iframeSrc
                }
            } catch (_: Exception) {
                // Skip invalid base64
            }
        }

        // Priority 1: Dark Server (Rumble CDN with multi-language subtitles including Spanish)
        if (darkServerUrl != null) {
            extractDarkServer(darkServerUrl, subtitleCallback, callback)
        }

        // Priority 2: Eng-Sub Player (Dailymotion fallback)
        if (engSubUrl != null) {
            extractDailymotion(engSubUrl, callback)
        }

        return darkServerUrl != null || engSubUrl != null
    }

    /**
     * Extract video and subtitles from the Dark Server (player.donghuaplanet.com).
     */
    private suspend fun extractDarkServer(
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val playerHtml = app.get(playerUrl, referer = "$mainUrl/").text

            // === Extract Subtitle Tracks ===
            extractAndParseTracks(playerHtml, subtitleCallback)

            // === Extract Video Sources ===
            extractAndParseSources(playerHtml, callback)

        } catch (_: Exception) {
            // Dark Server extraction failed
        }
    }

    /**
     * Extract and parse subtitle tracks from the player HTML.
     */
    private fun extractAndParseTracks(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val tracksMatch = Regex("""(?:const|var|let)\s+tracks\s*=\s*(\[[\s\S]*?\])\s*;""").find(html)
            ?: return

        val tracksStr = tracksMatch.groupValues[1]

        val trackPattern = Regex("""\{\s*"file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"\s*\}""")
        val tracks = trackPattern.findAll(tracksStr).mapNotNull { match ->
            val file = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
            val label = match.groupValues[2]
            Pair(file, label)
        }.toList()

        for ((file, label) in tracks) {
            if (label in SUBTITLE_LANGUAGES) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = label,
                        url = file
                    )
                )
            }
        }
    }

    /**
     * Extract and parse video sources from the player HTML.
     * FIX 2026-07-28: Marcar como M3U8/HLS los archivos .tar?r_file=chunklist.m3u8.
     * Antes se etiquetaban como MP4 (isM3u8=false en el lambda anterior ya fue removido),
     * pero el reproductor intentaba parsearlos como contenedor MP4 y mostraba
     * "Error_code_parsing_container_unsupported(3003)". Aunque la app ya no usa
     * isM3u8 explícitamente, debemos pasar estos como type=M3U8 usando ExtractorLinkType
     * para que el reproductor sepa que son HLS envueltos en .tar.
     */
    private suspend fun extractAndParseSources(html: String, callback: (ExtractorLink) -> Unit) {
        // Buscar el bloque "sources":[...] (termina antes de "tracks")
        val sourcesMatch = Regex("""sources\s*:\s*(\[[\s\S]*?\])\s*,\s*tracks""").find(html)
            ?: return

        val sourcesStr = sourcesMatch.groupValues[1]

        val sourcePattern = Regex(
            """\{\s*"file"\s*:\s*"([^"]+)"\s*,\s*"type"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"\s*\}"""
        )

        var hasHlsMaster = false

        sourcePattern.findAll(sourcesStr).forEach { match ->
            val file = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
            val type = match.groupValues[2]
                .replace("\\/", "/")
            val label = match.groupValues[3]

            when {
                // HLS master playlist (type=application/x-mpegURL)
                type.contains("mpegURL", ignoreCase = true) -> {
                    hasHlsMaster = true
                    callback.invoke(
                        newExtractorLink(
                            source = "Dark Server",
                            name = "Dark Server (Auto)",
                            url = file,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$PLAYER_BASE/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                // FIX: Rumble CDN quality-specific HLS streams (.tar?r_file=chunklist.m3u8)
                // NO son MP4 reales: son contenedores TAR con un chunklist HLS dentro.
                // Marcarlos como M3U8 evita el error "parsing_container_unsupported(3003)".
                file.contains("r_file=") || file.contains("chunklist.m3u8") -> {
                    val quality = parseQualityFromLabel(label)
                    callback.invoke(
                        newExtractorLink(
                            source = "Dark Server",
                            name = "Dark Server ($label)",
                            url = file,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$PLAYER_BASE/"
                            this.quality = quality
                        }
                    )
                }
                // Regular MP4 direct links
                type.contains("mp4", ignoreCase = true) -> {
                    val quality = parseQualityFromLabel(label)
                    callback.invoke(
                        newExtractorLink(
                            source = "Dark Server",
                            name = "Dark Server ($label)",
                            url = file,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$PLAYER_BASE/"
                            this.quality = quality
                        }
                    )
                }
            }
        }

        // Fallback: if no sources parsed, try to find HLS URL directly
        if (!hasHlsMaster) {
            val hlsMatch = Regex("""["'](https://rumble\.com/hls-vod/[^"']+\.m3u8[^"']*)["']""").find(html)
                ?: Regex("""["'](https://[^"']*\.m3u8[^"']*)["']""").find(html)

            if (hlsMatch != null) {
                val hlsUrl = hlsMatch.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")

                callback.invoke(
                    newExtractorLink(
                        source = "Dark Server",
                        name = "Dark Server",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$PLAYER_BASE/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }

    /**
     * Parse quality label string to quality integer value.
     */
    private fun parseQualityFromLabel(label: String): Int {
        return when {
            label.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            label.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            label.contains("720", ignoreCase = true) -> Qualities.P720.value
            label.contains("480", ignoreCase = true) -> Qualities.P480.value
            label.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /**
     * Extract video from Dailymotion embed URL (Eng-Sub Player).
     */
    private suspend fun extractDailymotion(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val videoId = embedUrl.substringAfter("video=", "").substringBefore("&").substringBefore("#")
            if (videoId.isEmpty()) return

            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val json = app.get(apiUrl, referer = embedUrl).text

            val hlsMatch = Regex("""["'](?:url|stream_url|hls)["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(json)
                ?: Regex("""["'](https://[^"']*dmcdn\.net/[^"']*m3u8[^"']*)["']""").find(json)

            if (hlsMatch != null) {
                val hlsUrl = hlsMatch.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")

                callback.invoke(
                    newExtractorLink(
                        source = "Eng-Sub Player",
                        name = "Eng-Sub Player",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://www.dailymotion.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {
            // Dailymotion extraction failed
        }
    }
}
