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

    override val mainPage = mainPageOf(
        "$mainUrl/donghuas" to "Donghuas",
        "$mainUrl/en-emision" to "En Emisión"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?page=${page - 1}"
        val doc = app.get(url).document
        val items = doc.select("div.serie").mapNotNull { el ->
            val a = el.selectFirst("div.imagen > a") ?: el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("div.titulo")?.text() ?: return@mapNotNull null
            val href = a.attr("abs:href")
            val poster = el.selectFirst("img.image-style-poster")?.attr("abs:src")
            if (href.isNotBlank()) {
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            } else null
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?search_api_fulltext=$query").document
        return doc.select("div.serie").mapNotNull { el ->
            val a = el.selectFirst("div.imagen > a") ?: el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("div.titulo")?.text() ?: return@mapNotNull null
            val href = a.attr("abs:href")
            val poster = el.selectFirst("img.image-style-poster")?.attr("abs:src")
            if (href.isNotBlank()) {
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.titulo h2 span")?.text()
            ?: doc.selectFirst("div.titulo")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img.image-style-poster")?.attr("abs:src")
        val description = doc.selectFirst("div.field--name-field-synopsis")?.text()
            ?: doc.selectFirst("div.descripcion")?.text()

        val episodes = mutableListOf<Episode>()
        val seasonLinks = doc.select("div.temporada div.serie a")
        if (seasonLinks.isNotEmpty()) {
            for (seasonEl in seasonLinks) {
                val seasonUrl = seasonEl.attr("abs:href")
                if (seasonUrl.isNotBlank()) {
                    try {
                        val seasonDoc = app.get(seasonUrl).document
                        seasonDoc.select("table.table-hover a").forEach { epEl ->
                            val epName = epEl.text()
                            val epUrl = epEl.attr("abs:href")
                            if (epUrl.isNotBlank()) {
                                episodes.add(newEpisode(epUrl) {
                                    this.name = epName
                                })
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } else {
            doc.select("div.episodios a, table.table-hover a").forEach { epEl ->
                val epName = epEl.text()
                val epUrl = epEl.attr("abs:href")
                if (epUrl.isNotBlank()) {
                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                    })
                }
            }
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

        val mainIframe = doc.selectFirst("#iframe-episode")
        if (mainIframe != null) {
            val src = mainIframe.attr("abs:src")
            if (src.isNotBlank()) {
                callback(
                    newExtractorLink(source = name, name = "Video", url = src) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        doc.select("a[data-video]").forEach { el ->
            val videoUrl = el.attr("data-video")
            if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                val serverName = el.text().trim()
                callback(
                    newExtractorLink(source = name, name = serverName, url = videoUrl) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
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
