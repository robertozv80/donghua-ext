package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class DonghuaLifeProvider : MainAPI() {
    override var mainUrl = "https://donghualife.com"
    override var name = "DonghuaLife"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    private fun resolveUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            url.isNotBlank() -> "$mainUrl/$url"
            else -> ""
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episodios",
        "$mainUrl/donghuas" to "Donghuas",
        "$mainUrl/en-emision" to "En Emisión",
        "$mainUrl/finalizado" to "Finalizados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.name == "Últimos Episodios" -> request.data
            page == 1 -> request.data
            else -> "${request.data}?page=${page - 1}"
        }
        val doc = app.get(url).document

        val items = when (request.name) {
            "Últimos Episodios" -> {
                // Latest episodes on the homepage
                doc.select("div.views-row, div.episodio, div.serie").mapNotNull { el ->
                    val a = el.selectFirst("a") ?: return@mapNotNull null
                    val title = el.selectFirst("div.titulo")?.text()?.trim()
                        ?: a.text().trim()
                    val href = resolveUrl(a.attr("href"))
                    val poster = resolveUrl(el.selectFirst("img")?.attr("src") ?: "")
                    if (href.isNotBlank() && title.isNotBlank()) {
                        newAnimeSearchResponse(title, href) { this.posterUrl = poster }
                    } else null
                }
            }
            else -> {
                doc.select("div.serie").mapNotNull { el ->
                    val a = el.selectFirst("div.imagen > a") ?: el.selectFirst("a") ?: return@mapNotNull null
                    val title = el.selectFirst("div.titulo")?.text()?.trim() ?: return@mapNotNull null
                    val href = resolveUrl(a.attr("href"))
                    val poster = resolveUrl(el.selectFirst("img.image-style-poster")?.attr("src") ?: "")
                    if (href.isNotBlank()) {
                        newAnimeSearchResponse(title, href) { this.posterUrl = poster }
                    } else null
                }
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?search_api_fulltext=$query").document
        return doc.select("div.serie").mapNotNull { el ->
            val a = el.selectFirst("div.imagen > a") ?: el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("div.titulo")?.text()?.trim() ?: return@mapNotNull null
            val href = resolveUrl(a.attr("href"))
            val poster = resolveUrl(el.selectFirst("img.image-style-poster")?.attr("src") ?: "")
            if (href.isNotBlank()) {
                newAnimeSearchResponse(title, href) { this.posterUrl = poster }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.titulo")?.text()?.trim() ?: ""
        val poster = resolveUrl(doc.selectFirst("div.poster img.image-style-poster")?.attr("src") ?: "")
            .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content") ?: "" }
        val description = doc.selectFirst("div.field--name-field-synopsis")?.text()
            ?: doc.selectFirst("div.descripcion")?.text()

        val episodes = mutableListOf<Episode>()
        val seasonLinks = doc.select("div.temporada a")
        if (seasonLinks.isNotEmpty()) {
            for (seasonEl in seasonLinks) {
                val seasonUrl = resolveUrl(seasonEl.attr("href"))
                if (seasonUrl.isNotBlank()) {
                    try {
                        val seasonDoc = app.get(seasonUrl).document
                        seasonDoc.select("table.table-hover a").forEach { epEl ->
                            val epName = epEl.text()
                            val epUrl = resolveUrl(epEl.attr("href"))
                            if (epUrl.isNotBlank()) {
                                episodes.add(newEpisode(epUrl) { this.name = epName })
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } else {
            doc.select("table.table-hover a").forEach { epEl ->
                val epName = epEl.text()
                val epUrl = resolveUrl(epEl.attr("href"))
                if (epUrl.isNotBlank()) {
                    episodes.add(newEpisode(epUrl) { this.name = epName })
                }
            }
        }

        // Reverse so episode 1 is first (sites list newest first)
        episodes.reverse()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Method 1: Get video from data-video attributes (Rumble, Dailymotion, etc.)
        doc.select("a[data-video]").forEach { el ->
            val videoUrl = el.attr("data-video")
            val serverName = el.text().trim().ifBlank { el.attr("title").ifBlank { "Server" } }
            if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                callback(
                    newExtractorLink(source = name, name = serverName, url = videoUrl) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )

                // For Dailymotion embeds, try to get direct stream URL
                if (videoUrl.contains("dailymotion.com") || videoUrl.contains("dailymotion")) {
                    try {
                        val videoId = Regex("""video[=/]([A-Za-z0-9]+)""").find(videoUrl)?.groupValues?.get(1)
                        if (videoId != null) {
                            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
                            val apiRes = app.get(apiUrl).text
                            val m3u8Regex = Regex(""""(https?://[^"]+\.m3u8[^"]*)"""")
                            m3u8Regex.find(apiRes)?.groupValues?.get(1)?.let { m3u8Url ->
                                callback(
                                    newExtractorLink(source = name, name = "$serverName (HLS)", url = m3u8Url) {
                                        this.referer = "https://www.dailymotion.com/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }

                // For Rumble embeds, try to get direct stream URL
                if (videoUrl.contains("rumble.com")) {
                    try {
                        val rumbleDoc = app.get(videoUrl).document
                        rumbleDoc.select("script").forEach { script ->
                            val content = script.html()
                            // Rumble puts video URLs in JSON
                            val mp4Regex = Regex("""["']?(https?://[^"'\s]+\.mp4[^"'\s]*)["']?""")
                            mp4Regex.findAll(content).forEach { match ->
                                val mp4Url = match.groupValues[1]
                                if (mp4Url.startsWith("http") && mp4Url.contains(".mp4")) {
                                    callback(
                                        newExtractorLink(source = name, name = "$serverName (MP4)", url = mp4Url) {
                                            this.referer = videoUrl
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                }
                            }
                            val m3u8Regex = Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?""")
                            m3u8Regex.findAll(content).forEach { match ->
                                val m3u8Url = match.groupValues[1]
                                if (m3u8Url.startsWith("http") && m3u8Url.contains(".m3u8")) {
                                    callback(
                                        newExtractorLink(source = name, name = "$serverName (HLS)", url = m3u8Url) {
                                            this.referer = videoUrl
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Method 2: Get iframe src directly
        val mainIframe = doc.selectFirst("#iframe-episode")
        if (mainIframe != null) {
            val src = resolveUrl(mainIframe.attr("src"))
            if (src.isNotBlank()) {
                callback(
                    newExtractorLink(source = name, name = "Video", url = src) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Method 3: All iframes as fallback
        doc.select("iframe").forEach { el ->
            val src = resolveUrl(el.attr("src"))
            if (src.isNotBlank()) {
                callback(
                    newExtractorLink(source = name, name = "Embed", url = src) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
