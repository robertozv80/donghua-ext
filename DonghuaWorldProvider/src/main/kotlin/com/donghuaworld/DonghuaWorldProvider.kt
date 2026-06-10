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

        /** Subtitle label keywords to match (case-insensitive check) */
        private val SPANISH_KEYWORDS = listOf("spanish", "español", "espanol", "castilian", "castellano")
        private val ENGLISH_KEYWORDS = listOf("english", "inglés", "ingles")
    }

    // ==================== MAIN PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?order=update" to "Hot Series Update",
        "$mainUrl/anime/?order=latest" to "Latest Release",
        "$mainUrl/anime/?order=popular" to "Popular Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "$mainUrl/anime/page/$page/?order=${request.data.substringAfter("order=")}"
        } else {
            request.data
        }

        val document = app.get(url).document
        val items = document.select("article.bs").mapNotNull { article ->
            parseAnimeCard(article)
        }

        val hasNext = document.select("a.next.page-numbers, .pagination a.next").isNotEmpty()
                || document.select("a.page-numbers").any { it.attr("href").contains("page/${page + 1}") }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    /**
     * Parse an anime card from the /anime/ listing page.
     */
    private fun parseAnimeCard(article: org.jsoup.nodes.Element): SearchResponse? {
        val linkEl = article.selectFirst(".bsx a[itemprop=url]") ?: article.selectFirst("a[href]") ?: return null
        val url = linkEl.attr("abs:href")
        if (url.isEmpty() || !url.contains("/anime/")) return null

        val title = linkEl.selectFirst("h2[itemprop=headline]")?.text()?.trim()
            ?: article.selectFirst(".tt")?.text()?.trim()
            ?: linkEl.attr("title").takeIf { it.isNotEmpty() }
            ?: return null

        val img = linkEl.selectFirst("img.ts-post-image")?.let { imgEl ->
            imgEl.attr("data-src").takeIf { it.isNotEmpty() } ?: imgEl.attr("src")
        } ?: article.selectFirst("img")?.let { imgEl ->
            imgEl.attr("data-src").takeIf { it.isNotEmpty() } ?: imgEl.attr("src")
        } ?: ""

        return newAnimeSearchResponse(title, url) {
            this.posterUrl = img
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}").document
        val items = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        document.select("article.bs").forEach { article ->
            val linkEl = article.selectFirst(".bsx a[itemprop=url]") ?: article.selectFirst("a[href]") ?: return@forEach
            val url = linkEl.attr("abs:href")

            if (!url.contains("/anime/")) return@forEach
            if (!seenUrls.add(url)) return@forEach

            val title = linkEl.selectFirst("h2[itemprop=headline]")?.text()?.trim()
                ?: article.selectFirst(".tt")?.text()?.trim()
                ?: linkEl.attr("title").takeIf { it.isNotEmpty() }
                ?: return@forEach

            val img = linkEl.selectFirst("img.ts-post-image")?.let { imgEl ->
                imgEl.attr("data-src").takeIf { it.isNotEmpty() } ?: imgEl.attr("src")
            } ?: article.selectFirst("img")?.let { imgEl ->
                imgEl.attr("data-src").takeIf { it.isNotEmpty() } ?: imgEl.attr("src")
            } ?: ""

            items.add(newAnimeSearchResponse(title, url) {
                this.posterUrl = img
            })
        }
        return items
    }

    // ==================== DETAIL ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Extract title
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        // Extract poster image
        val poster = document.selectFirst(".bigcontent .thumbook .thumb img.ts-post-image")?.let { getImgSrc(it) }
            ?: document.selectFirst(".thumb img")?.let { getImgSrc(it) }
            ?: document.selectFirst("img.ts-post-image")?.let { getImgSrc(it) }
            ?: ""

        // Extract description
        val description = document.selectFirst(".synp .entry-content, .entry-content, .mindes, .alldes, .desc")?.text()?.trim() ?: ""

        // Extract genres
        val genres = document.select(".genxed a, .infox .genxed a").mapNotNull { it.text().trim() }

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

        // Primary: .eplister episode list (standard on /anime/ pages)
        document.select(".eplister ul li a[href]").forEach { linkEl ->
            val epUrl = linkEl.attr("abs:href")
            if (epUrl.isEmpty()) return@forEach
            if (!seenUrls.add(epUrl)) return@forEach

            val epNum = linkEl.selectFirst(".epl-num")?.text()?.trim()?.toIntOrNull()
                ?: extractEpisodeNumber(linkEl.text())
            val epTitle = linkEl.selectFirst(".epl-title")?.text()?.trim()

            episodes.add(newEpisode(epUrl) {
                this.name = epTitle?.takeIf { it.isNotEmpty() }
                this.episode = epNum
            })
        }

        // Fallback: .bxcl episode links
        if (episodes.isEmpty()) {
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
        }

        // Fallback 2: any episode links
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

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.showStatus = showStatus
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode ?: 0 })
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
     */
    private fun getImgSrc(imgEl: org.jsoup.nodes.Element): String {
        return imgEl.attr("data-src").takeIf { it.isNotEmpty() } ?: imgEl.attr("src")
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
            val labelLower = label.lowercase()
            val isSpanish = SPANISH_KEYWORDS.any { labelLower.contains(it) }
            val isEnglish = ENGLISH_KEYWORDS.any { labelLower.contains(it) }

            if (isSpanish || isEnglish) {
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
    private suspend fun extractAndParseSources(html: String, callback: (ExtractorLink) -> Unit) {
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
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$PLAYER_BASE/"
                            this.quality = Qualities.Unknown.value
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
