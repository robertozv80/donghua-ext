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

    override val mainPage = mainPageOf(
        "$mainUrl/todos-los-donghuas" to "Donghuas",
        "$mainUrl/donghuas-en-emision" to "En Emisión"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?pag=$page"
        val doc = app.get(url).document
        val items = doc.select("div.item").mapNotNull { el ->
            val a = el.selectFirst("a.angled-img") ?: return@mapNotNull null
            val title = el.selectFirst("h5")?.text() ?: return@mapNotNull null
            val href = a.attr("abs:href")
            val poster = el.selectFirst("div.img img")?.attr("abs:src")
            if (href.isNotBlank()) {
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            } else null
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.item").mapNotNull { el ->
            val a = el.selectFirst("a.angled-img") ?: return@mapNotNull null
            val title = el.selectFirst("h5")?.text() ?: return@mapNotNull null
            val href = a.attr("abs:href")
            val poster = el.selectFirst("div.img img")?.attr("abs:src")
            if (href.isNotBlank()) {
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.ls-title-serie")?.text() ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst("div.text-justify.fc-dark")?.text()
        val episodes = doc.select("ul.donghua-list > a").mapNotNull { el ->
            val epName = el.selectFirst("blockquote")?.text() ?: el.text()
            val epUrl = el.attr("abs:href")
            if (epUrl.isNotBlank()) {
                newEpisode(epUrl) {
                    this.name = epName
                }
            } else null
        }
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
        }

        doc.select("iframe").forEach { el ->
            val src = el.attr("abs:src")
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
