package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class AnimeGratisProvider : MainAPI() {
    override var mainUrl = "https://animegratis.net"
    override var name = "AnimeGratis"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Inicio",
        "$mainUrl/genre/anime" to "Anime",
        "$mainUrl/genre/donghua" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val doc = app.get(url).document
        val items = doc.select("article, .post-item, .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text() }
            val href = a.attr("abs:href")
            val poster = el.selectFirst("img")?.attr("abs:src")
            if (title.isNotBlank() && href.isNotBlank()) {
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            } else null
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article, .post-item, .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text() }
            val href = a.attr("abs:href")
            val poster = el.selectFirst("img")?.attr("abs:src")
            if (title.isNotBlank() && href.isNotBlank()) {
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1, .entry-title")?.text() ?: ""
        val poster = doc.selectFirst("img.wp-post-image, .poster img")?.attr("abs:src")
        val description = doc.selectFirst(".entry-content, .synopsis, .descripcion")?.text()

        val episodes = doc.select(".episode-list a, .episodios a, .capitulos a, .eplister a").mapNotNull { el ->
            val epName = el.text()
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

        val iframes = doc.select("iframe").mapNotNull { it.attr("abs:src") }
        for (iframeSrc in iframes) {
            try {
                val iframeDoc = app.get(iframeSrc, referer = data).document
                val videoUrls = mutableListOf<String>()

                iframeDoc.select("source, video source").forEach { el ->
                    val src = el.attr("abs:src")
                    if (src.isNotBlank()) videoUrls.add(src)
                }
                iframeDoc.select("video").forEach { el ->
                    val src = el.attr("abs:src")
                    if (src.isNotBlank()) videoUrls.add(src)
                }

                iframeDoc.select("script").forEach { script ->
                    val content = script.html()
                    val regex = Regex("""file\s*[:=]\s*["']([^"']+)["']""")
                    regex.findAll(content).forEach { match ->
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.startsWith("http")) videoUrls.add(videoUrl)
                    }
                }

                for (videoUrl in videoUrls) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Video",
                            url = videoUrl
                        ) {
                            this.referer = iframeSrc
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Referer" to iframeSrc)
                        }
                    )
                }
            } catch (_: Exception) {}
        }

        doc.select("video source, video").forEach { el ->
            val src = el.attr("abs:src")
            if (src.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direct",
                        url = src
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}