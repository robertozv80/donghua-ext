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
        "$mainUrl/lista-episodios" to "Nuevos Episodios",
        "$mainUrl/lista-donghuas" to "Donghuas",
        "$mainUrl/lista-donghuas-emision" to "En Emisión",
        "$mainUrl/lista-donghuas-finalizados" to "Finalizados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val items = when (request.name) {
            "Nuevos Episodios" -> {
                doc.select("div.md-ep-item, div.md-card, a.md-ep-link").mapNotNull { el ->
                    val a = el.selectFirst("a") ?: el.takeIf { it.tagName() == "a" } ?: return@mapNotNull null
                    val title = el.selectFirst("h3, h5, .md-card-title, .md-ep-title")?.text() ?: a.text()
                    val href = resolveUrl(a.attr("href"))
                    val poster = resolveUrl(el.selectFirst("img")?.attr("src") ?: "")
                    if (href.isNotBlank()) {
                        newAnimeSearchResponse(title, href) { this.posterUrl = poster }
                    } else null
                }
            }
            else -> {
                doc.select("div.md-card").mapNotNull { el ->
                    val a = el.selectFirst("a") ?: return@mapNotNull null
                    val title = el.selectFirst("h3.md-card-title")?.text() ?: return@mapNotNull null
                    val href = resolveUrl(a.attr("href"))
                    val poster = resolveUrl(el.selectFirst("div.md-card-img img")?.attr("src") ?: "")
                    if (href.isNotBlank()) {
                        newAnimeSearchResponse(title, href) { this.posterUrl = poster }
                    } else null
                }
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/busquedas/?donghua=$query").document
        return doc.select("div.md-card").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("h3.md-card-title, h5.md-card-title")?.text() ?: return@mapNotNull null
            val href = resolveUrl(a.attr("href"))
            val poster = resolveUrl(el.selectFirst("div.md-card-img img")?.attr("src") ?: "")
            if (href.isNotBlank()) {
                newAnimeSearchResponse(title, href) { this.posterUrl = poster }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.md-detail-title")?.text() ?: ""
        val poster = resolveUrl(doc.selectFirst("div.md-detail-poster img, div.md-detail-img img, img.md-poster")?.attr("src") ?: "")
            .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content") ?: "" }
        val description = doc.selectFirst("p.md-detail-synopsis")?.text()
        val episodes = doc.select("a.md-ep-link").mapNotNull { el ->
            val epName = el.selectFirst("h5")?.text() ?: el.text()
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

        // Extract server names from tabs
        val serverNames = doc.select("button.md-server-tab").mapNotNull {
            it.text().trim().ifBlank { null }
        }

        // Method 1: Look for iframes already loaded
        doc.select("iframe").forEach { el ->
            val src = resolveUrl(el.attr("src"))
            if (src.isNotBlank() && src != "about:blank") {
                callback(
                    newExtractorLink(source = name, name = "Tamamo", url = src) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Method 2: Parse obfuscated JS to find embed URLs for each server
        doc.select("script").forEach { script ->
            val content = script.html()

            // Find URLs in eval blocks - common patterns
            val urlPatterns = listOf(
                Regex("""https?://[^\s'"]+?/e/[^\s'"]+"""),  // embed patterns like /e/xxxx
                Regex("""https?://[^\s'"]+?\.xyz/[^\s'"]+"""),
                Regex("""https?://[^\s'"]+?\.com/[^\s'"]+?/e/[^\s'"]+"""),
                Regex("""https?://voe\.sx/[^\s'"]+"""),
                Regex("""https?://[^\s'"]+?\.sx/[^\s'"]+"""),
                Regex("""https?://vidhidepro\.com/[^\s'"]+"""),
                Regex("""https?://[^\s'"]+?bysekoze[^\s'"]*""")
            )
            for (pattern in urlPatterns) {
                pattern.findAll(content).forEach { match ->
                    val videoUrl = match.value.trimEnd(',', ';', ')')
                    if (videoUrl.startsWith("http")) {
                        callback(
                            newExtractorLink(source = name, name = "Server", url = videoUrl) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }

            // Look for src attributes being set in JS
            val srcRegex = Regex("""["']https?://[^"']+["']""")
            srcRegex.findAll(content).forEach { match ->
                val videoUrl = match.value.trim('"', '\'')
                if (videoUrl.startsWith("http") &&
                    (videoUrl.contains("/e/") || videoUrl.contains("embed") ||
                     videoUrl.contains("player") || videoUrl.contains("video"))) {
                    callback(
                        newExtractorLink(source = name, name = "Server", url = videoUrl) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }

        // Method 3: Try fetching the tamamo API endpoint
        val donghuaKey = doc.selectFirst("input#donghua_key")?.attr("value")
        if (!donghuaKey.isNullOrBlank()) {
            try {
                val apiDoc = app.post("$mainUrl/api_donghua.php", data = mapOf("slug" to donghuaKey)).text
                if (apiDoc.isNotBlank()) {
                    // API returns JSON with video URLs
                    val urlRegex = Regex("""https?://[^"'\s,]+""")
                    urlRegex.findAll(apiDoc).forEach { match ->
                        val videoUrl = match.value.trimEnd(',', ';', ')', ']', '}')
                        if (videoUrl.startsWith("http") &&
                            (videoUrl.contains("player") || videoUrl.contains("embed") ||
                             videoUrl.contains("/e/") || videoUrl.contains(".php"))) {
                            callback(
                                newExtractorLink(source = name, name = "Tamamo", url = videoUrl) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Method 4: Direct video/source tags
        doc.select("video source, video").forEach { el ->
            val src = resolveUrl(el.attr("src"))
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
