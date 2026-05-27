package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SeriesDonghuaProvider : MainAPI() {
    override var mainUrl = "https://seriesdonghua.com"
    override var name = "SeriesDonghua"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    // ==================== Homepage ====================
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Inicio",
        "$mainUrl/donghua/" to "Donghuas",
        "$mainUrl/anime/" to "Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val doc = app.get(url).document
        val items = doc.select("div.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, page + 1)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("a") ?: return null
        val title = titleEl.attr("title").ifBlank { titleEl.text() }.trim()
        val href = titleEl.attr("abs:href")
        if (href.isBlank()) return null
        val posterUrl = selectFirst("img")?.attr("abs:src")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ==================== Search ====================
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query}").document
        return doc.select("div.bs").mapNotNull { it.toSearchResult() }
    }

    // ==================== Detail ====================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("div.bigcontent img")?.attr("abs:src")
            ?: doc.selectFirst("div.thumb img")?.attr("abs:src")
        val synopsis = doc.selectFirst("div.entry-content, div.sinopsis, div.desc")?.text()?.trim()
        val genres = doc.select("div.genxed a, div.sigen a, a[rel=tag]").map { it.text().trim() }

        val episodes = doc.select("div.eplister ul li a, div.lsch a, div.episodelist a").mapNotNull { ep ->
            val epTitle = ep.selectFirst("span.epl-title, span")?.text()?.trim()
                ?: ep.text().trim().ifBlank { return@mapNotNull null }
            val epHref = ep.attr("abs:href").ifBlank { return@mapNotNull null }
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

        // Look for iframes (common video players)
        val iframes = doc.select("iframe").mapNotNull { it.attr("abs:src") }

        for (iframe in iframes) {
            loadExtractor(iframe, data, subtitleCallback, callback)
        }

        // Also check for direct video/m3u8 in script tags
        doc.select("script").forEach { script ->
            val content = script.data()
            val m3u8Regex = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            m3u8Regex.findAll(content).forEach { match ->
                callback(
                    ExtractorLink(
                        source = name,
                        name = "SeriesDonghua HLS",
                        url = match.value,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
        }

        // Check for video source tags
        doc.select("video source, video src").forEach { source ->
            val videoUrl = source.attr("abs:src").ifBlank { return@forEach }
            callback(
                ExtractorLink(
                    source = name,
                    name = "SeriesDonghua",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        }

        return true
    }
}