package com.donghuaworld

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Hot Series Update",
        "$mainUrl/##latest" to "Latest Release",
        "$mainUrl/##recommendation" to "Recommendation"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionName = request.name
        val sectionUrl = request.data

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
            sectionName == "Hot Series Update" -> parseSectionByHeading(document, "Series Update")
            sectionName == "Latest Release" -> parseSectionByHeading(document, "Latest Release")
            sectionName == "Recommendation" -> parseSectionByHeading(document, "Recommendation")
            else -> emptyList()
        }

        return newHomePageResponse(
            listOf(HomePageList(sectionName, items)),
            hasNext = sectionName == "Latest Release" && items.isNotEmpty()
        )
    }

    /**
     * Generic section parser: finds the heading matching [headingText],
     * then scans sibling elements for article cards.
     */
    private fun parseSectionByHeading(
        document: org.jsoup.nodes.Document,
        headingText: String
    ): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()

        val heading = document.select("h3, h2").firstOrNull { h ->
            h.text().trim().equals(headingText, ignoreCase = true)
        } ?: return emptyList()

        var sibling: org.jsoup.nodes.Element? = heading.parent()
        var attempts = 0

        while (sibling != null && attempts < 10) {
            val articles = sibling.select("article")
            if (articles.isNotEmpty()) {
                articles.forEach { article ->
                    parseArticleCard(article)?.let { items.add(it) }
                }
                return items
            }
            sibling = sibling.nextElementSibling()
            attempts++
        }

        return items
    }

    /**
     * Parse a single article card into a SearchResponse.
     * FIX: Extraer número de episodio del título y pasarlo a addDubStatus
     */
    private fun parseArticleCard(article: org.jsoup.nodes.Element): SearchResponse? {
        val linkEl = article.selectFirst("a[href]") ?: return null
        val url = linkEl.attr("abs:href")
        if (url.isEmpty()) return null

        val title = linkEl.attr("title").takeIf { it.isNotEmpty() }
            ?: linkEl.selectFirst(".eggtitle")?.text()?.trim()
            ?: linkEl.selectFirst("h2")?.text()?.trim()
            ?: return null

        val img = linkEl.selectFirst("img")?.let { imgEl ->
            // FIX: Priorizar data-src (lazy loading) sobre src (placeholder base64)
            imgEl.attr("data-src").takeIf { it.isNotEmpty() && !it.startsWith("data:") } ?: imgEl.attr("src")
        } ?: ""

        // FIX: Extraer número de episodio del título o del badge
        // Los títulos tienen formato: "Soul Land 2 ... Episode 157 (4K) Multi-Subtitles"
        // También hay un badge: <span class="epx">Ep 157 (4K)</span>
        val epNum = EPISODE_NUM_REGEX.find(title)?.groupValues?.get(1)?.let { numStr ->
            numStr.substringBefore("-").toIntOrNull()
        } ?: article.selectFirst(".epx")?.text()?.let { epText ->
            Regex("""Ep\s+(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.destructured?.component1()?.toIntOrNull()
        }

        // Extraer nombre de serie limpio (sin "Episode X" ni indicadores de calidad)
        val cleanTitle = title.replace(Regex("""\s*Episode\s+\d+(?:-\d+)?[^|]*$""", RegexOption.IGNORE_CASE), "").trim()

        return newAnimeSearchResponse(cleanTitle, url) {
            this.posterUrl = img
            addDubStatus(DubStatus.Subbed, epNum)
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

        // Extract description
        val description = document.selectFirst(".mindes, .alldes, .entry-content, .desc")?.text()?.trim() ?: ""

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

        // Method 1: Look for episode links in the bxcl/episode list container
        document.select(".bxcl a[href*=episode], .epl a[href*=episode], .episodelist a[href*=episode]").forEach { linkEl ->
            val epUrl = linkEl.attr("abs:href")
            if (epUrl.isEmpty() || !epUrl.contains("episode", ignoreCase = true)) return@forEach
            if (!seenUrls.add(epUrl)) return@forEach

            val epText = linkEl.text().trim()
            val epNum = extractEpisodeNumber(epText)

            episodes.add(newEpisode(epUrl) {
                this.name = epText.takeIf { it.isNotEmpty() }
                this.episode = epNum
            })
        }

        // Method 2: Fallback - search for all episode links
        if (episodes.isEmpty()) {
            document.select("a[href*=episode]").forEach { linkEl ->
                val epUrl = linkEl.attr("abs:href")
                if (epUrl.isEmpty()) return@forEach
                if (!seenUrls.add(epUrl)) return@forEach

                val epText = linkEl.text().trim()
                val epNum = extractEpisodeNumber(epText)

                episodes.add(newEpisode(epUrl) {
                    this.name = epText.takeIf { it.isNotEmpty() }
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
            this.episodes = mapOf(DubStatus.Subbed to sortedEpisodes)
        }
    }

    /**
     * Given an episode page URL, load it and extract the series URL from the breadcrumb.
     */
    private suspend fun resolveSeriesUrlFromEpisode(episodeUrl: String): String {
        return try {
            val epDoc = app.get(episodeUrl).document
            epDoc.select("a[href*=/anime/]").firstOrNull()?.attr("abs:href")?.takeIf { it.isNotEmpty() }
                ?: epDoc.select(".breadcrumb a, .breadcrumbs a").firstOrNull {
                    it.attr("abs:href").contains("/anime/")
                }?.attr("abs:href")
                ?: episodeUrl
        } catch (_: Exception) {
            episodeUrl
        }
    }

    /**
     * Extract episode number from text like "Episode 263" or "Episode 254-255"
     */
    private fun extractEpisodeNumber(text: String): Int? {
        return EPISODE_NUM_REGEX.find(text)?.groupValues?.get(1)?.let { numStr ->
            numStr.substringBefore("-").toIntOrNull()
        } ?: run {
            Regex("""(\d+)""").findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        }
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
     */
    private fun extractAndParseSources(html: String, callback: (ExtractorLink) -> Unit) {
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
                // HLS master playlist
                type.contains("mpegURL", ignoreCase = true) -> {
                    hasHlsMaster = true
                    callback.invoke(
                        newExtractorLink(
                            source = "Dark Server",
                            name = "Dark Server (Auto)",
                            url = file,
                            referer = "$PLAYER_BASE/"
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.isM3u8 = true
                        }
                    )
                }
                // Rumble CDN quality-specific HLS streams
                type.contains("mp4", ignoreCase = true) && file.contains("r_file=") -> {
                    val quality = parseQualityFromLabel(label)
                    callback.invoke(
                        newExtractorLink(
                            source = "Dark Server",
                            name = "Dark Server ($label)",
                            url = file,
                            referer = "$PLAYER_BASE/"
                        ) {
                            this.quality = quality
                            this.isM3u8 = true
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
                            referer = "$PLAYER_BASE/"
                        ) {
                            this.quality = quality
                            this.isM3u8 = false
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
                        referer = "$PLAYER_BASE/"
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.isM3u8 = true
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
                        referer = "https://www.dailymotion.com/"
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.isM3u8 = true
                    }
                )
            }
        } catch (_: Exception) {
            // Dailymotion extraction failed
        }
    }
}
