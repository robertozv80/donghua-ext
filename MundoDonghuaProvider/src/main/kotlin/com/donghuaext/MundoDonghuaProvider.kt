package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class MundoDonghuaProvider : MainAPI() {
    override var mainUrl = "https://mundodonghua.com"
    override var name = "MundoDonghua"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/lista-donghuas" to "Donghuas",
        "$mainUrl/lista-donghuas-emision" to "En Emisión"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/$page"
        val doc = app.get(url).document
        val items = doc.select("div.md-card").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("h3.md-card-title")?.text() ?: return@mapNotNull null
            val href = a.attr("abs:href")
            val poster = el.selectFirst("div.md-card-img img")?.attr("abs:src")
            if (href.isNotBlank()) {
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            } else null
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/busquedas/?donghua=$query").document
        return doc.select("div.md-card").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("h3.md-card-title")?.text() ?: return@mapNotNull null
            val href = a.attr("abs:href")
            val poster = el.selectFirst("div.md-card-img img")?.attr("abs:src")
            if (href.isNotBlank()) {
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.md-detail-title")?.text() ?: ""
        val poster = doc.selectFirst("div.md-detail-poster img")?.attr("abs:src")
        val description = doc.selectFirst("p.md-detail-synopsis")?.text()
        val episodes = doc.select("a.md-ep-link").mapNotNull { el ->
            val epName = el.selectFirst("h5")?.text() ?: el.text()
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

        val iframes = doc.select("iframe").mapNotNull { it.attr("abs:src").ifBlank { null } }
        for (iframeSrc in iframes) {
            try {
                val iframeDoc = app.get(iframeSrc, referer = data).document
                iframeDoc.select("source, video source").forEach { el ->
                    val src = el.attr("abs:src")
                    if (src.isNotBlank()) {
                        callback(
                            newExtractorLink(source = name, name = "Video", url = src) {
                                this.referer = iframeSrc
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        doc.select("script").forEach { script ->
            val content = script.html()
            val regex = Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+)["']""")
            regex.findAll(content).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("http")) {
                    callback(
                        newExtractorLink(source = name, name = "Video", url = videoUrl) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }

        doc.select("video source, video").forEach { el ->
            val src = el.attr("abs:src")
            if (src.isNotBlank()) {
                callback(
                    newExtractorLink(source = name, name = "Direct", url = src) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
