package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MundoDonghuaProvider : MainAPI() {
    override var mainUrl = "https://mundodonghua.com"
    override var name = "MundoDonghua"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    // ==================== Homepage ====================
    override val mainPage = mainPageOf(
        "$mainUrl/donghuas/" to "Donghuas",
        "$mainUrl/animes/" to "Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val doc = app.get(url).document
        val items = doc.select("div.md-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, page + 1)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3.md-card-title")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("abs:href") ?: return null
        val posterUrl = selectFirst("img")?.attr("abs:src")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ==================== Search ====================
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/busquedas/?donghua=${query}").document
        return doc.select("div.md-card").mapNotNull { it.toSearchResult() }
    }

    // ==================== Detail ====================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.md-detail-title")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("div.md-detail-poster img")?.attr("abs:src")
        val synopsis = doc.selectFirst("p.md-detail-synopsis")?.text()?.trim()
        val genres = doc.select("a.md-genre-tag").map { it.text().trim() }

        val episodes = doc.select("ul.md-episode-list > li.md-episode-item").mapNotNull { ep ->
            val epTitle = ep.selectFirst("span")?.text()?.trim() ?: return@mapNotNull null
            val epHref = ep.selectFirst("a")?.attr("abs:href") ?: return@mapNotNull null
            Episode(epHref, epTitle)
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = genres
        }
    }

    // ==================== Links ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Try iframes from the episode page
        val iframes = doc.select("iframe").mapNotNull { it.attr("abs:src") }

        for (iframe in iframes) {
            if (iframe.contains("mdplayer.xyz") || iframe.contains("player")) {
                val playerDoc = app.get(iframe).document
                // Extract HLS source from player
                playerDoc.select("script").forEach { script ->
                    val content = script.data()
                    val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                    m3u8Regex.findAll(content).forEach { match ->
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "MundoDonghua",
                                url = match.value,
                                referer = iframe,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true
                            )
                        )
                    }
                }
            }
            // Also try generic extractors
            loadExtractor(iframe, iframe, subtitleCallback, callback)
        }

        // Also check for direct video sources in script tags
        doc.select("script").forEach { script ->
            val content = script.data()
            val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
            m3u8Regex.findAll(content).forEach { match ->
                callback(
                    ExtractorLink(
                        source = name,
                        name = "MundoDonghua HLS",
                        url = match.value,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
        }

        return true
    }
}