package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class SeriesDonghuaProvider : MainAPI() {
    override var mainUrl = "https://seriesdonghua.com"
    override var name = "SeriesDonghua"
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
        "$mainUrl/episodios" to "Nuevos Episodios",
        "$mainUrl/todos-los-donghuas" to "Donghuas",
        "$mainUrl/donghuas-en-emision" to "En Emisión",
        "$mainUrl/donghuas-finalizados" to "Finalizados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && request.name != "Nuevos Episodios") "${request.data}?pag=$page" else request.data
        val doc = app.get(url).document

        val items = when (request.name) {
            "Nuevos Episodios" -> {
                // Episode list items on main page
                doc.select("div.item").mapNotNull { el ->
                    val a = el.selectFirst("a.angled-img") ?: return@mapNotNull null
                    val title = el.selectFirst("h5")?.text() ?: return@mapNotNull null
                    val href = resolveUrl(a.attr("href"))
                    val poster = resolveUrl(el.selectFirst("div.img img")?.attr("src") ?: "")
                    if (href.isNotBlank()) {
                        newAnimeSearchResponse(title, href) { this.posterUrl = poster }
                    } else null
                }
            }
            else -> {
                doc.select("div.item").mapNotNull { el ->
                    val a = el.selectFirst("a.angled-img") ?: return@mapNotNull null
                    val title = el.selectFirst("h5")?.text() ?: return@mapNotNull null
                    val href = resolveUrl(a.attr("href"))
                    val poster = resolveUrl(el.selectFirst("div.img img")?.attr("src") ?: "")
                    if (href.isNotBlank()) {
                        newAnimeSearchResponse(title, href) { this.posterUrl = poster }
                    } else null
                }
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // The site uses JS search, but the /todos-los-donghuas page lists all series
        // We fetch all and filter by query client-side
        val results = mutableListOf<SearchResponse>()
        var page = 1
        while (page <= 5) { // limit to 5 pages to avoid too many requests
            val doc = app.get("$mainUrl/todos-los-donghuas?pag=$page").document
            val items = doc.select("div.item").mapNotNull { el ->
                val a = el.selectFirst("a.angled-img") ?: return@mapNotNull null
                val title = el.selectFirst("h5")?.text() ?: return@mapNotNull null
                val href = resolveUrl(a.attr("href"))
                val poster = resolveUrl(el.selectFirst("div.img img")?.attr("src") ?: "")
                if (href.isNotBlank() && title.contains(query, ignoreCase = true)) {
                    newAnimeSearchResponse(title, href) { this.posterUrl = poster }
                } else null
            }
            results.addAll(items)
            if (doc.select("div.item").isEmpty() || items.isEmpty()) break
            page++
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.ls-title-serie")?.text() ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val description = doc.selectFirst("div.text-justify.fc-dark")?.text()
        val episodes = doc.select("ul.donghua-list > a").mapNotNull { el ->
            val epName = el.selectFirst("blockquote")?.text() ?: el.text()
            val epUrl = resolveUrl(el.attr("href"))
            if (epUrl.isNotBlank()) {
                newEpisode(epUrl) { this.name = epName }
            } else null
        }.reversed()
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

        // Get server names from tabs
        val serverMap = mutableMapOf<String, String>()
        doc.select("ul.nav-tabs li a").forEach { tab ->
            val serverName = tab.text().trim().replace("Ads", "").trim()
            val tabId = tab.attr("href").removePrefix("#")
            if (serverName.isNotBlank() && tabId.isNotBlank()) {
                serverMap[tabId] = serverName
            }
        }

        // Method 1: Parse VIDEO_MAP_JSON or similar JS variables
        doc.select("script").forEach { script ->
            val content = script.html()
            if (content.contains("VIDEO_MAP_JSON")) {
                val jsonRegex = Regex("""VIDEO_MAP_JSON\s*=\s*\{([^}]+)\}""")
                val match = jsonRegex.find(content)
                if (match != null) {
                    val jsonStr = match.groupValues[1]
                    val pairRegex = Regex(""""(\w+)"\s*:\s*"([^"]+)"""")
                    pairRegex.findAll(jsonStr).forEach { pairMatch ->
                        val serverName = pairMatch.groupValues[1]
                        var videoUrl = pairMatch.groupValues[2]
                        if (serverName == "asura" && !videoUrl.startsWith("http")) {
                            videoUrl = "https://www.dailymotion.com/embed/video/$videoUrl"
                        }
                        if (videoUrl.startsWith("http")) {
                            callback(
                                newExtractorLink(source = name, name = serverName.replaceFirstChar { it.uppercase() }, url = videoUrl) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }
            }

            // Find URLs in obfuscated/eval scripts
            val urlRegex = Regex("""https?://[^"'\s,\);]+""")
            urlRegex.findAll(content).forEach { match ->
                val videoUrl = match.value.trimEnd(',', ';', ')', ']', '}')
                if (videoUrl.startsWith("http") &&
                    (videoUrl.contains("/e/") || videoUrl.contains("embed") ||
                     videoUrl.contains("player") || videoUrl.contains("dailymotion") ||
                     videoUrl.contains("rumble") || videoUrl.contains("odysee") ||
                     videoUrl.contains("voe") || videoUrl.contains("ok.ru"))) {
                    callback(
                        newExtractorLink(source = name, name = "Server", url = videoUrl) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }

        // Method 2: Extract from iframes
        doc.select("iframe").forEach { el ->
            val src = resolveUrl(el.attr("src"))
            if (src.isNotBlank() && src != "about:blank") {
                val serverName = serverMap[el.attr("id").removeSuffix("_player")] ?: "Embed"
                callback(
                    newExtractorLink(source = name, name = serverName, url = src) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Method 3: Try fetching iframe pages for video sources
        val iframeSrcs = doc.select("iframe").mapNotNull { el ->
            resolveUrl(el.attr("src")).ifBlank { null }
        }
        for (iframeSrc in iframeSrcs) {
            try {
                val iframeDoc = app.get(iframeSrc, referer = data).document
                iframeDoc.select("video source, video").forEach { el ->
                    val src = resolveUrl(el.attr("src"))
                    if (src.isNotBlank()) {
                        callback(
                            newExtractorLink(source = name, name = "Video", url = src) {
                                this.referer = iframeSrc
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
                iframeDoc.select("script").forEach { script ->
                    val content = script.html()
                    val fileRegex = Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+)["']""")
                    fileRegex.findAll(content).forEach { match ->
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.startsWith("http")) {
                            callback(
                                newExtractorLink(source = name, name = "Video", url = videoUrl) {
                                    this.referer = iframeSrc
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return true
    }
}
