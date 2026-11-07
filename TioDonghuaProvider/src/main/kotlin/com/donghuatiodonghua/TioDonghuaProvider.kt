package com.donghuatiodonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.donghuaext.common.CommonExtractors as CE

/** UA de navegador (necesario para varios hosts). */
const val TD_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

/**
 * API del sitio nuevo. tiodonghua.lat abandonó WordPress/Dooplay y ahora es
 * Next.js (App Router) sobre un backend Laravel expuesto en esta ruta. No pide
 * auth y responde JSON envuelto en {"data": ...}.
 */
private const val TD_API = "https://tioplayer.cloudlatino.com/api/v1"

/** Catalogo cacheado (titulo normalizado -> rating) para puntuar las cards de episodio. */
private const val TD_RATINGS_TTL_MS = 60L * 60L * 1000L
private var tdRatingsCache: Pair<Long, Map<String, Double>> = 0L to emptyMap()

/**
 * Lectores de JSON sin librería: el módulo solo depende de cloudstream3,
 * NiceHttp y jsoup. `tdJsonVal` devuelve el valor crudo de la PRIMERA clave que
 * coincide; en las respuestas del API los campos propios de un objeto siempre
 * van antes de los objetos anidados, así que el primer match es el del nivel
 * que se está leyendo.
 */
private fun tdJsonVal(json: String, key: String): String? {
    val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*").find(json) ?: return null
    var i = m.range.last + 1
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length) return null
    return when (val c = json[i]) {
        '"', '{', '[' -> {
            val close = if (c == '"') '"' else if (c == '{') '}' else ']'
            var depth = 0
            var j = i
            var inStr = false
            var esc = false
            while (j < json.length) {
                val ch = json[j]
                if (inStr) {
                    when {
                        esc -> esc = false
                        ch == '\\' -> esc = true
                        ch == '"' -> inStr = false
                    }
                } else when (ch) {
                    '"' -> inStr = true
                    c -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return json.substring(i, j + 1)
                    }
                }
                j++
            }
            null
        }
        else -> {
            var j = i
            while (j < json.length && json[j] != ',' && json[j] != '}' && json[j] != ']') j++
            json.substring(i, j).trim()
        }
    }
}

/** Cadena JSON des-escapada (\\uXXXX, \\/, \\n) o null si la clave no existe. */
private fun tdJsonStr(json: String, key: String): String? =
    tdJsonVal(json, key)?.let { CE.unescJson(it.removePrefix("\"").removeSuffix("\"")).trim() }

private fun tdJsonInt(json: String, key: String): Int? = tdJsonVal(json, key)?.toDoubleOrNull()?.toInt()

private fun tdJsonDbl(json: String, key: String): Double? = tdJsonVal(json, key)?.toDoubleOrNull()

/**
 * Objetos JSON de un valor `"key":[{...},{...}]` (o de un solo objeto
 * `"key":{...}`): se balancean llaves respetando las cadenas para no romperse
 * con títulos que contengan llaves.
 */
private fun tdJsonObjs(json: String, key: String): List<String> {
    val body = tdJsonVal(json, key) ?: return emptyList()
    val out = ArrayList<String>()
    var depth = 0
    var start = -1
    var inStr = false
    var esc = false
    for (i in body.indices) {
        val ch = body[i]
        if (inStr) {
            when {
                esc -> esc = false
                ch == '\\' -> esc = true
                ch == '"' -> inStr = false
            }
        } else when (ch) {
            '"' -> inStr = true
            '{' -> if (depth++ == 0) start = i
            '}' -> if (--depth == 0 && start >= 0) { out.add(body.substring(start, i + 1)); start = -1 }
        }
    }
    return out
}

private fun tdGenres(obj: String): List<String> =
    tdJsonObjs(obj, "genres").mapNotNull { tdJsonStr(it, "name") }.filter { it.isNotBlank() }

/**
 * Extractor de Ok.Ru (odnoklassniki). La página del embed trae el
 * hlsManifestUrl y las urls progresivas en el atributo data-options.
 */
private suspend fun extractTdOkRu(embedUrl: String, referer: String, name: String, callback: (ExtractorLink) -> Unit): Boolean {
    // v27: primero el extractor COMÚN (hls validado #EXTM3U + progresivos).
    if (CE.extractOkRu(embedUrl, referer, name, TD_USER_AGENT, callback)) return true
    return try {
        // v24.2: el embed puede llegar protocol-relative (//ok.ru/...) dentro del
        // HTML dtshcode; sin esquema la petición falla.
        val url = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
        val html = app.get(url, referer = referer,
            headers = mapOf("User-Agent" to TD_USER_AGENT), timeout = 20L).text
        // v24.2: decodificar TODOS los escapes del data-options: &quot;, / y
        // \uXXXX (\u0026 = '&'). Sin esto la URL del manifiesto llega con
        // '\u0026' literal, el player la descarta y el episodio queda sin
        // enlaces (verificado en logcat 16:49:45, "M3u8 Playlist is not a
        // Master Playlist").
        val decodeUnicode = { s: String ->
            Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { m ->
                m.groupValues[1].toInt(16).toChar().toString()
            }
        }
        val options = Regex("data-options=\"([^\"]+)\"").find(html)
            ?.groupValues?.get(1)
            ?.let { it.replace("&quot;", "\"").replace("\\/", "/").let(decodeUnicode) }
            ?: return false
        var found = false
        // hlsManifestUrl (mejor opción: adaptable). El campo puede vivir en el
        // JSON top-level O dentro de flashvars.metadata (embeds nuevos del
        // agregador). VALIDAR el manifiesto antes de entregarlo: generateM3u8 no
        // lanza excepción con contenido inválido y M3u8Helper filtra el link en
        // reproducción => "Enlaces no encontrados". La clave varía por versión
        // del player: "hlsManifestUrl" (clásico) u "ondemandHls". El primer
        // match que valide como #EXTM3U gana.
        val hlsUrl = Regex("\"(?:[a-zA-Z]*ManifestUrl|ondemandHls)\":\"([^\"]+)\"")
            .findAll(options).map { it.groupValues[1] }.firstOrNull { candidate ->
                try {
                    app.get(candidate, referer = url,
                        headers = mapOf("User-Agent" to TD_USER_AGENT), timeout = 20L)
                        .text.trimStart().startsWith("#EXTM3U")
                } catch (_: Exception) { false }
            }
        if (hlsUrl != null) {
            try {
                generateM3u8(name, hlsUrl, url).forEach(callback)
                found = true
            } catch (_: Exception) {}
        }
        // Fallback: videos[] con urls progresivas por calidad. Puede estar en el
        // top-level del JSON o anidado en flashvars.metadata (los embeds nuevos
        // anidan TODO el metadata del player dentro de flashvars).
        Regex("\"name\":\"(mobile|lowest|low|sd|hd|full|super)\",\"url\":\"([^\"]+)\"")
            .findAll(options).forEach { m ->
                val progressive = decodeUnicode(m.groupValues[2])
                if (progressive.startsWith("http")) {
                    val full = if (progressive.startsWith("//")) "https:$progressive" else progressive
                    callback(newExtractorLink(source = name, name = name, url = full) {
                        this.referer = url
                        this.quality = when (m.groupValues[1]) {
                            "full", "super" -> Qualities.P1080.value
                            "hd" -> Qualities.P720.value
                            "sd" -> Qualities.P480.value
                            else -> Qualities.P360.value
                        }
                    })
                    found = true
                }
            }
        found
    } catch (_: Exception) {
        false
    }
}

/**
 * v24: extractor de Rumble.
 * v29: primero el extractor COMÚN (API embedJS + visita previa al embed para el
 * reto de Cloudflare). Si no hay suerte se lee el HTML del embed: los videos
 * antiguos aún traen el data JSON con "hls-vod" o el mapa de calidades mp4 en
 * hugh.cdn.rumble.cloud (verificado en vivo: 206 video/mp4, Range OK).
 */
private suspend fun extractTdRumble(embedUrl: String, referer: String, name: String, callback: (ExtractorLink) -> Unit): Boolean {
    if (CE.extractRumble(embedUrl, referer, name, TD_USER_AGENT, callback)) return true
    return try {
        val html = app.get(embedUrl, referer = referer,
            headers = mapOf("User-Agent" to TD_USER_AGENT), timeout = 20L).text
        // Las URLs vienen con / escapado dentro del JSON del embed
        val fixed = html.replace("\\/", "/")
        // El embed nuevo (vip) trae el bloque m.f["VID"]={...} con rendiciones +
        // "hls":{"url":"https://rumble.com/hls-vod/.../playlist.m3u8"}. El HLS es
        // VOD válido: master con variantes 1080/720/480/360 y segmentos TS.
        Regex("\"hls\":\\{\"url\":\"(https://rumble\\.com/hls-vod/[^\"]+)\"")
            .find(fixed)?.groupValues?.get(1)?.let { hlsUrl ->
                try {
                    generateM3u8(name, hlsUrl, embedUrl).forEach(callback)
                    return true
                } catch (_: Exception) {}
            }
        // El JSON del embed trae un mapa de calidades con dimensiones:
        // "360":{"url":"...mp4","meta":{...,"w":638,"h":360}}. Emitir cada una.
        val qualities = Regex("\"(\\d{3,4})\":\\{\"url\":\"(https://[a-z0-9.-]*rumble\\.cloud/video/[A-Za-z0-9/._-]+\\.mp4)\"")
            .findAll(fixed).toList()
        if (qualities.isNotEmpty()) {
            qualities.distinctBy { it.groupValues[2] }.forEach { m ->
                val h = m.groupValues[1].toIntOrNull()
                callback(newExtractorLink(source = name, name = name, url = m.groupValues[2]) {
                    this.referer = embedUrl
                    this.quality = when {
                        h == null -> Qualities.Unknown.value
                        h >= 1080 -> Qualities.P1080.value
                        h >= 720 -> Qualities.P720.value
                        h >= 480 -> Qualities.P480.value
                        else -> Qualities.P360.value
                    }
                })
            }
            return true
        }
        // Fallback: primer mp4 directo (embeds sin mapa de calidades)
        val url = Regex("https://[a-z0-9.-]*rumble\\.cloud/video/[A-Za-z0-9/._-]+\\.mp4")
            .find(fixed)?.value ?: return false
        callback(newExtractorLink(source = name, name = name, url = url) {
            this.referer = embedUrl
            this.quality = Qualities.Unknown.value
        })
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * v24: extractor de StreamTape. El HTML del player trae el enlace directo
 * `//<host>.streamtape.<tld>/get_video?id=...&expires=...&ip=...&token=...`
 * (revelado solo tras el click; nos basta con extraerlo del HTML).
 */
private suspend fun extractTdStreamTape(embedUrl: String, referer: String, name: String, callback: (ExtractorLink) -> Unit): Boolean {
    return try {
        val html = app.get(embedUrl, referer = referer,
            headers = mapOf("User-Agent" to TD_USER_AGENT), timeout = 20L).text
        val direct = Regex("(?:https?:)?//[a-z0-9-]+\\.streamtape[a-z.]*/get_video\\?[^\"'\\s<>]+", RegexOption.IGNORE_CASE)
            .find(html)?.value ?: return false
        val fixedUrl = if (direct.startsWith("//")) "https:$direct" else direct
        callback(newExtractorLink(source = name, name = name, url = fixedUrl) {
            this.referer = embedUrl
            this.quality = Qualities.Unknown.value
        })
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * TioDonghua (https://tiodonghua.lat/) — v29: reescrito sobre el sitio nuevo.
 *
 * El sitio dejó WordPress/Dooplay: ahora es Next.js App Router con Tailwind y
 * un backend Laravel en [TD_API]. Los selectores viejos (article.item,
 * ul.episodios, admin-ajax doo_player_ajax) ya no matcheaban NADA, por eso la
 * provider quedaba vacía en la app.
 *
 * Rutas verificadas en vivo (2026-11):
 * - /donghua/{slug}  ficha        -> TD_API/donghuas/{slug}
 * - /ver/{episodeId} reproductor  -> TD_API/episodes/{episodeId}
 * - /explorar?status=&sort=&q=    -> TD_API/donghuas?status=&sort=&q=
 * El API de catálogo NO expone los últimos episodios, así que esa sección se
 * sigue leyendo del HTML del home (renderizado en servidor).
 *
 * LIMITACIÓN del sitio (no del plugin): la ficha solo publica los episodios más
 * recientes de cada temporada (p. ej. Swallowed Star entrega 241-248). Se
 * probaron per_page/limit/page/all=1 y las rutas alternativas
 * /donghuas/{slug}/episodes y /episodes?serie_slug=; el tope está del lado del
 * servidor y el HTML de la ficha expone exactamente los mismos episodios.
 */
class TioDonghuaProvider : MainAPI() {

    override var mainUrl = "https://tiodonghua.lat"
    override var name = "TioDonghua"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.OVA,
        TvType.AnimeMovie,
    )

    // Secciones pedidas (2026-11). Las tres últimas usan los mismos filtros que
    // /explorar en el navegador, resueltos directo contra el API.
    override val mainPage = mainPageOf(
        "$mainUrl/##episodios" to "Últimos Episodios",
        "$TD_API/donghuas?sort=popular" to "Top Más Vistos",
        "$TD_API/donghuas?status=ongoing&sort=rating" to "En Emisión",
        "$TD_API/donghuas?sort=rating" to "Catálogo de Donghuas",
    )

    private val pageHeaders = mapOf(
        "User-Agent" to TD_USER_AGENT,
        "Accept-Language" to "es-ES,es;q=0.8,en;q=0.5",
    )

    private fun resolveUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }

    /** Quita sufijo "Sub Español" y emojis iniciales de los títulos. */
    private fun tdCleanTitle(t: String): String = t
        .replace(Regex("\\s*Sub\\s*Espa[nñ]ol\\s*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^[^\\p{L}\\p{N}]+"), "")
        .trim()

    /** v23.0 normaliza un título para comparar bases (minúsculas, sin acentos). */
    private fun tdNormalize(t: String): String = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private suspend fun apiGet(url: String): String? = try {
        app.get(url, headers = pageHeaders, timeout = 30L).text
    } catch (_: Exception) {
        null
    }

    /** Objetos del array `"data":[...]` de una respuesta del API. */
    private fun apiItems(json: String): List<String> = tdJsonObjs(json, "data")

    /**
     * Ficha del API -> SearchResponse. El campo `rating` (escala 10) se traduce
     * a `score` para que la card del home muestre la etiqueta de puntuación.
     */
    private fun apiCard(item: String): SearchResponse? {
        val slug = tdJsonStr(item, "slug") ?: return null
        val rawTitle = tdJsonStr(item, "title") ?: return null
        val title = tdCleanTitle(rawTitle)
        if (title.isBlank()) return null
        return newAnimeSearchResponse(title, "$mainUrl/donghua/$slug") {
            this.posterUrl = tdJsonStr(item, "poster_url") ?: ""
            tdJsonInt(item, "release_year")?.let { this.year = it }
            val r = tdJsonDbl(item, "rating")
            if (r != null && r > 0.0) {
                try { Score.from10(r.toString())?.let { this.score = it } } catch (_: Exception) {}
            }
        }
    }

    // ========== getMainPage ==========
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // "Últimos Episodios" sale del HTML del home (el API no expone la lista).
        if (request.data.endsWith("##episodios")) {
            val items = if (page > 1) emptyList() else lastEpisodesFromHome()
            return newHomePageResponse(HomePageList(request.name, items), hasNext = false)
        }
        if (page > 1) {
            // El catálogo completo cabe en una página: last_page=1 verificado en
            // vivo para sort=rating|popular y el filtro status=ongoing.
            return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        }
        val items = apiItems(apiGet(request.data) ?: "").mapNotNull { apiCard(it) }
        return newHomePageResponse(HomePageList(request.name, items.distinctBy { it.url }), hasNext = false)
    }

    /**
     * Cards de "⚡ÚLTIMOS EPISODIOS" del home nuevo:
     * `<a href="/ver/{id}">` con `<img>`, el badge "Capítulo N" y un `<h4>` con
     * el nombre de la serie (el span de al lado trae el título largo con sufijos
     * tipo "X 10 Episodio 45 Sub Español", por eso se prefiere el h4).
     */
    private suspend fun lastEpisodesFromHome(): List<SearchResponse> {
        return try {
            val ratings = apiRatingsByTitle()
            val doc = app.get("$mainUrl/", headers = pageHeaders, timeout = 30L).document
            doc.select("a[href^=/ver/]").mapNotNull { a ->
                val epId = Regex("/ver/(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val img = a.selectFirst("img")
                val alt = img?.attr("alt")?.trim() ?: ""
                val serie = a.selectFirst("h4")?.text()?.trim().orEmpty()
                val title = tdCleanTitle(serie.ifBlank { alt.substringBeforeLast(" - Ep") })
                if (title.isBlank()) return@mapNotNull null
                val badge = a.selectFirst("div.absolute")?.text().orEmpty()
                val epNum = Regex("(?i)Cap[íi]tulo\\s*(\\d+)").find(badge)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?i)[- ]Ep(?:isodio)?\\s*(\\d+)").find(alt)?.groupValues?.get(1)?.toIntOrNull()
                newAnimeSearchResponse(title, "$mainUrl/ver/$epId") {
                    this.posterUrl = img?.attr("src") ?: ""
                    // La card del home es un EPISODIO y su HTML no trae nota; se toma
                    // del catálogo del API, que llega completo en una sola petición.
                    val r = ratings[tdNormalize(title)]
                    if (r != null) {
                        try { Score.from10(r.toString())?.let { this.score = it } } catch (_: Exception) {}
                    }
                    addDubStatus(DubStatus.Subbed, epNum)
                }
            }.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** título normalizado -> rating, según el catálogo del API (cacheado 1 h). */
    private suspend fun apiRatingsByTitle(): Map<String, Double> {
        val now = System.currentTimeMillis()
        tdRatingsCache.second.takeIf { now - tdRatingsCache.first < TD_RATINGS_TTL_MS }?.let { return it }
        val json = apiGet("$TD_API/donghuas?sort=rating") ?: return emptyMap()
        val map = apiItems(json).mapNotNull { item ->
            val t = tdJsonStr(item, "title") ?: return@mapNotNull null
            val r = tdJsonDbl(item, "rating") ?: return@mapNotNull null
            tdNormalize(tdCleanTitle(t)) to r
        }.toMap()
        if (map.isNotEmpty()) tdRatingsCache = now to map
        return map
    }

    // ========== search ==========
    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return apiItems(apiGet("$TD_API/donghuas?q=$q&sort=rating") ?: "")
            .mapNotNull { apiCard(it) }.distinctBy { it.url }.take(30)
    }

    // ========== load ==========
    override suspend fun load(url: String): LoadResponse {
        val epId = Regex("/ver/(\\d+)").find(url)?.groupValues?.get(1)
        if (epId != null) {
            val json = apiGet("$TD_API/episodes/$epId") ?: ""
            // "slug" aparece primero dentro de serie{} (el episodio no tiene
            // slug propio; embed_slug usa otra clave).
            val serieSlug = tdJsonStr(json, "slug")
            if (!serieSlug.isNullOrBlank()) return loadSerie(serieSlug)
            // Sin ficha localizable: serie sintetizada de un episodio para que
            // el usuario pueda reproducir el enlace que abrió.
            return singleEpisodeResponse(url, epId, json)
        }
        val slug = url.trimEnd('/').substringAfter("/donghua/").substringBefore('/').trim()
        return loadSerie(slug.ifBlank { url.trimEnd('/').substringAfterLast('/') })
    }

    private suspend fun singleEpisodeResponse(url: String, epId: String, json: String): LoadResponse {
        val serieTitle = tdCleanTitle(tdJsonStr(json, "title") ?: "Episodio $epId")
        val poster = tdJsonStr(json, "thumbnail_url") ?: ""
        return newAnimeLoadResponse(serieTitle, url, TvType.Anime) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, listOf(newEpisode(url) {
                this.episode = tdJsonInt(json, "episode_number")
            }))
        }
    }

    /** Ficha completa desde el API: episodios por temporada, rating, géneros. */
    private suspend fun loadSerie(slug: String): LoadResponse {
        val json = apiGet("$TD_API/donghuas/$slug") ?: ""
        val data = tdJsonVal(json, "data") ?: json
        val title = tdCleanTitle(tdJsonStr(data, "title") ?: slug.replace("-", " ").trim())
        val seriesUrl = "$mainUrl/donghua/$slug"
        val poster = tdJsonStr(data, "poster_url") ?: ""
        val synopsis = tdJsonStr(data, "synopsis") ?: ""
        val releaseYear = tdJsonInt(data, "release_year")
        val genres = tdGenres(data)
        val originalTitle = tdJsonStr(data, "original_title")?.takeIf { it.isNotBlank() }
        val showStatus = when (tdJsonStr(data, "broadcast_status")?.lowercase()) {
            "completed" -> ShowStatus.Completed
            "ongoing" -> ShowStatus.Ongoing
            else -> null
        }
        val score = tdJsonDbl(data, "rating")?.takeIf { it > 0.0 }?.let {
            try { Score.from10(it.toString()) } catch (_: Exception) { null }
        }

        val episodes = ArrayList<Episode>()
        val seen = HashSet<String>()
        for (season in tdJsonObjs(data, "seasons")) {
            val seasonNum = tdJsonInt(season, "season_number")
            for (ep in tdJsonObjs(season, "episodes")) {
                val epId = tdJsonStr(ep, "id") ?: continue
                if (!seen.add(epId)) continue
                // name se deja null: el título del API ("X 10 Episodio 45 Sub
                // Español") repite la serie y el sufijo; con season+episode
                // CloudStream renderiza "Episodio N" y agrupa por temporada.
                episodes.add(newEpisode("$mainUrl/ver/$epId") {
                    this.episode = tdJsonInt(ep, "episode_number")
                    this.season = seasonNum
                })
            }
        }
        episodes.sortWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

        val recs = apiItems(apiGet("$TD_API/donghuas?sort=popular") ?: "")
            .filter { tdJsonStr(it, "slug") != slug }
            .mapNotNull { apiCard(it) }
            .take(12)

        return newAnimeLoadResponse(title, seriesUrl, TvType.Anime) {
            posterUrl = poster
            if (synopsis.isNotBlank()) plot = synopsis
            if (genres.isNotEmpty()) tags = genres
            releaseYear?.let { this.year = it }
            score?.let { this.score = it }
            showStatus?.let { this.showStatus = it }
            originalTitle?.let { this.synonyms = listOf(it) }
            if (episodes.isEmpty()) addEpisodes(DubStatus.Subbed, listOf(newEpisode(seriesUrl)))
            else addEpisodes(DubStatus.Subbed, episodes)
            if (recs.isNotEmpty()) this.recommendations = recs
        }
    }

    // ========== loadLinks ==========
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        var links = 0
        val cb: (ExtractorLink) -> Unit = { links++; callback(it) }
        // v8: dedupe DM por episodio (los sets son de instancia).
        tdEmittedDmVids.clear()
        tdEmittedDmSubs.clear()

        val epId = Regex("/ver/(\\d+)").find(data)?.groupValues?.get(1)
        val json = if (epId != null) apiGet("$TD_API/episodes/$epId") ?: "" else ""

        val servers = ArrayList<Pair<String, String>>()
        for (src in tdJsonObjs(json, "sources")) {
            val embed = tdNormalizeEmbed(tdJsonStr(src, "embed_url") ?: "") ?: continue
            val label = tdJsonStr(tdJsonVal(src, "server") ?: "", "name")?.takeIf { it.isNotBlank() }
                ?: serverLabelFromUrl(embed)
            servers.add(embed to label)
        }
        // v24.13: los servidores vivos primero para que la reproducción por
        // defecto caiga en uno que responde (auditoría en tdServerPriority).
        for ((embed, label) in servers.sortedByDescending { tdServerPriority(it.first) }.distinctBy { it.first }) {
            processTdEmbed(embed, data, label, subtitleCallback, cb)
        }

        // Fallback 1: la lista "downloads" del mismo episodio (urls cortas).
        if (links == 0) {
            for (dl in tdJsonObjs(json, "downloads")) {
                val url = tdNormalizeEmbed(tdJsonStr(dl, "url") ?: "") ?: continue
                if (url.contains("/drive/folders/") || url.contains("playerp2p")) continue
                val label = tdJsonStr(dl, "name")?.takeIf { it.isNotBlank() } ?: serverLabelFromUrl(url)
                processTdEmbed(url, data, label, subtitleCallback, cb)
            }
        }

        // Fallback 2: iframes / m3u8 servidos en la propia página del episodio.
        if (links == 0) {
            val doc = try { app.get(data, headers = pageHeaders, timeout = 30L).document } catch (_: Exception) { null }
            doc?.select("iframe")?.forEach { iframe ->
                val raw = listOf("src", "data-src").map { iframe.attr(it).trim() }.firstOrNull { it.isNotBlank() }
                    ?: return@forEach
                val src = resolveUrl(tdNormalizeEmbed(raw) ?: raw)
                if (src.startsWith("http")) processTdEmbed(src, data, serverLabelFromUrl(src), subtitleCallback, cb)
            }
            if (links == 0) {
                for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").findAll(doc?.html() ?: "")) {
                    try { generateM3u8("Server", m.value, data).forEach(cb) } catch (_: Exception) {}
                    if (links > 0) break
                }
            }
        }
        return links > 0
    }

    /**
     * v29: normaliza las URLs de servidor. El API mezcla dos formatos por host:
     * el canónico del player (`dailymotion.com/embed/video/ID`,
     * `rumble.com/embed/vID/?pub=...`) y el corto de las descargas
     * (`dailymotion.com/d/video/ID`, `rumble.com/d/vID`). Los extractores solo
     * entienden el canónico, así que se reescribe aquí.
     */
    private fun tdNormalizeEmbed(raw: String): String? {
        val t = raw.replace("\\\"", "\"").replace("\\/", "/").trim()
        if (!t.startsWith("http")) return null
        val end = t.indexOfFirst { it == '"' || it == ' ' }
        val url = (if (end == -1) t else t.substring(0, end)).trim()
        if (url.isBlank()) return null
        return when {
            url.contains("dailymotion.com/d/video/") -> url.replace("/d/video/", "/embed/video/")
            Regex("rumble\\.com/d/v[a-zA-Z0-9]+").containsMatchIn(url) -> url.replaceFirst("/d/", "/embed/")
            else -> url
        }
    }

    /** Nombre de servidor legible a partir del host. */
    private fun serverLabelFromUrl(url: String): String {
        return try {
            val host = java.net.URI(url).host ?: return "Server"
            host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            "Server"
        }
    }

    /**
     * Prioridad del servidor por host para ordenar la emisión de enlaces.
     * v29 auditoría (18 episodios del API nuevo): Byse 15/18, VidHide 15,
     * Streamp2p 14, Rumble 13, Dailymotion 13, OkRu 10, VIDARA 5, VOE 5.
     * Byse (byselapuix.com) es una SPA Vite de 1.6 KB sin fuente estática y sin
     * API pública; Streamp2p es WebRTC con tokens (ExoPlayer no lo reproduce);
     * VOE redirige a un host aleatorio y sirve las fuentes en un blob cifrado
     * por su bundle JS. Esos tres se saltan sin gastar peticiones.
     */
    private fun tdServerPriority(embedUrl: String): Int {
        val u = embedUrl.lowercase()
        return when {
            u.contains("rumble.com") -> 9
            u.contains("dailymotion.com") || u.contains("dai.ly") -> 8
            u.contains("ok.ru") || u.contains("odnoklassniki") -> 7
            u.contains("morencius") || u.contains("vidhide") -> 6
            u.contains("vidara") -> 5
            u.contains("streamtape") || u.contains("strcloud") -> 4
            u.contains("drive.google.com") -> 3
            else -> 0
        }
    }

    /**
     * v24.3: Google Drive (servidor "Drive Vip"): drive.google.com/file/d/ID/
     * preview -> drive.usercontent.google.com/download?id=ID&export=download
     * &confirm=t, que sirve el MP4 DIRECTO (verificado en vivo con 3 archivos:
     * 200 video/mp4, 325-586 MB, sin cookies ni JS). Transformación pura de URL.
     */
    private suspend fun extractTdGoogleDrive(
        embedUrl: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("drive\\.google\\.com/(?:file/d/|open\\?id=)([a-zA-Z0-9_-]{10,})")
            .find(embedUrl)?.groupValues?.get(1) ?: return false
        callback(
            newExtractorLink(source = "Drive", name = "Drive", url =
                "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t") {
                this.referer = "https://drive.google.com/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /**
     * v29: Vidara (servidor "VIDARA"). El embed es un reproductor JWPlayer cuya
     * fuente resuelve una API POST sin auth en el MISMO host del embed:
     *   POST <host>/api/stream {"filecode":"<id de /e/ID>","device":"web"}
     *   -> {"streaming_url":"https://.../master.m3u8?token=...","subtitles":...}
     * Validado en vivo: master #EXTM3U con variante
     * CODECS="avc1.640028,mp4a.40.2" (h264 con audio muxed, sin VMAP). El host
     * real puede ser vidara.to o un espejo (as9u7bgh9.com), así que se usa el
     * host de la propia URL del embed.
     */
    private suspend fun extractTdVidara(
        embedUrl: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val code = embedUrl.trimEnd('/').substringAfterLast('/')
            if (code.isBlank() || code.contains('.')) return false
            val host = Regex("(https?://[^/]+)").find(embedUrl)?.groupValues?.get(1) ?: return false
            val resp = app.post(
                "$host/api/stream",
                referer = embedUrl,
                headers = mapOf("User-Agent" to TD_USER_AGENT),
                json = mapOf("filecode" to code, "device" to "web"),
                timeout = 20L
            ).text
            val stream = tdJsonStr(resp, "streaming_url") ?: return false
            if (!stream.startsWith("http")) return false
            var found = false
            if (stream.contains(".m3u8")) {
                try { generateM3u8(serverName, stream, embedUrl).forEach(callback); found = true } catch (_: Exception) {}
            }
            if (!found) {
                callback(newExtractorLink(source = serverName, name = serverName, url = stream) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                })
                found = true
            }
            for (sub in tdJsonObjs(resp, "subtitles")) {
                val file = tdJsonStr(sub, "file") ?: continue
                if (!file.startsWith("http")) continue
                val label = tdJsonStr(sub, "label")?.takeIf { it.isNotBlank() } ?: "Subtítulos"
                try { subtitleCallback(SubtitleFile(label, file)) } catch (_: Exception) {}
            }
            found
        } catch (_: Exception) {
            false
        }
    }

    /**
     * v24.3: la página "MultiPlayer" (player.tiodonghua.com/embed/XX+NN) era un
     * agregador: lista <li onclick="go_to_player('URL')"> con varios servidores y
     * la etiqueta visible junto a cada botón. Extraer cada embed y procesarlo con
     * su nombre real de servidor.
     */
    private suspend fun extractTdMultiPlayer(
        embedUrl: String, referer: String, name: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(embedUrl, referer = referer,
                headers = mapOf("User-Agent" to TD_USER_AGENT), timeout = 20L).text
            // v24.3 FIX: el raw string con \\( tenia backslash literal + '(' ->
            // 0 matches en Android (la doble barra no es escape en raw strings).
            // Un solo backslash en raw string = escape regex real.
            val embeds = (Regex("""go_to_player\('([^']+)'\)""").findAll(html).map { it.groupValues[1].trim() }
                    + html.split("go_to_player").drop(1).mapNotNull { seg ->
                        // v24.3 fallback tolerante: host + ruta hasta la comilla,
                        // para onclick con formato distinto (comillas dobles, etc.)
                        Regex("""[\(]\s*['\"]?([a-z0-9.-]+\.[a-z]{2,}[^'\"]\s*)['\"]?[\)]""", RegexOption.IGNORE_CASE)
                            .find(seg)?.groupValues?.get(1)?.trim()
                    })
                .filter { it.startsWith("http") }.distinct().toList()
            var any = false
            for (child in embeds) {
                val processed = try {
                    processTdEmbed(child, embedUrl, serverLabelFromUrl(child), subtitleCallback, callback)
                } catch (_: Exception) { false }
                if (processed) any = true
            }
            // v24.3: el agregador VIP usa botones is-vip-server SIN
            // go_to_player: hosts absolutos en atributos data-*.
            if (!any) {
                for (m in Regex("""(https?://[a-zA-Z0-9.-]+/[a-zA-Z0-9/._+?=&-]+)""").findAll(html)) {
                    val cand = m.groupValues[1]
                    if (cand.contains("tiodonghua") || cand.contains("googletagmanager") ||
                        cand.contains("google-analytics") || cand.contains("gstatic") ||
                        cand.contains("fontawesome") || cand.contains("cloudflare") ||
                        cand.contains("yandex") || cand.contains("jquery")) continue
                    val processed = try {
                        processTdEmbed(cand, embedUrl, serverLabelFromUrl(cand), subtitleCallback, callback)
                    } catch (_: Exception) { false }
                    if (processed) any = true
                }
            }
            any
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Enruta cada embed al extractor apropiado. Cuenta ENLACES EMITIDOS, no
     * excepciones (los hosts muertos devuelven 200 con página de error).
     */
    private suspend fun processTdEmbed(
        embedUrl: String, referer: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        var links = 0
        val cb: (ExtractorLink) -> Unit = { links++; callback(it) }
        val u = embedUrl.trim()
        when {
            // v24.3: streaming P2P/WebRTC (API JSON con token, peers y MSE);
            // ExoPlayer no puede reproducirlo: no gastar peticiones.
            u.contains("playerp2p") -> {}
            // v29: Byse (byselapuix.com): SPA Vite sin fuente estática ni API
            // pública anónima (verificado en vivo: 1.6 KB de HTML y el bundle no
            // expone ruta de sources). Saltar ahorra 1 request muerta.
            u.contains("byse") -> {}
            // v24.13: Terabox (servidor "TB"): SPA sin dlink anónimo. No
            // extraible: saltar (ahorra 2 requests muertas por episodio).
            u.contains("terabox") -> {}
            // v29: VOE sirve las fuentes en un blob cifrado por su bundle JS y
            // el host real cambia en cada visita (window.location.href hacia un
            // dominio aleatorio). No extraible sin ejecutar JS: saltar.
            u.contains("voe.sx") || u.contains("voe.sh") -> {}
            u.contains("dailymotion.com") ->
                extractTdDailymotion(u, referer, serverName, subtitleCallback, cb)
            // v24: Ok.Ru (servidor "OK" muy común en tiodonghua)
            u.contains("ok.ru") || u.contains("odnoklassniki") ->
                extractTdOkRu(u, referer, serverName, cb)
            // v24: Rumble (servidor "DM" en fichas nuevas)
            u.contains("rumble.com") ->
                extractTdRumble(u, referer, serverName, cb)
            // v29: Vidara y sus espejos: API POST /api/stream.
            u.contains("vidara") -> extractTdVidara(u, serverName, subtitleCallback, cb)
            // v24.2: agregador "MultiPlayer" (player.tiodonghua.com/embed/XX+NN).
            u.contains("player.tiodonghua.com") ->
                extractTdMultiPlayer(u, referer, serverName, subtitleCallback, cb)
            // v24: StreamTape (servidor "ST")
            u.contains("streamtape") || u.contains("strcloud") ->
                extractTdStreamTape(u, referer, serverName, cb)
            // modagamers: 302 a publicidad que planta cookie sid, luego challenge
            // con JS redirect; seguir la cadena con reintentos.
            u.contains("modagamers") ->
                extractTdPackedFromChallenge(u, referer, serverName, cb)
            // Familia streamwish/filelions/vidguard + VidHide (morencius.com):
            // players con JS empaquetado Dean-Edwards cuya fuente m3u8 aparece al
            // des-empaquetar. Validado en vivo con morencius: el unpack revela
            // links={"hls2":"https://...acek-cdn.com/hls2/.../master.m3u8?..."}.
            u.contains("wish") || u.contains("sblona") || u.contains("ahvsh") ||
                u.contains("filelions") || u.contains("luluvdo") ||
                u.contains("vgembed") || u.contains("vgfplay") || u.contains("vidguard") ||
                u.contains("asnwish") || u.contains("morencius") || u.contains("vidhide") ||
                u.contains("pixibay") || u.contains("earnvids") ->
                extractTdPacked(u, referer, serverName, cb)
            // v24.3: Google Drive (servidor "Drive Vip"): transform pura de URL.
            u.contains("drive.google.com") ->
                extractTdGoogleDrive(u, serverName, cb)
            else -> {
                try { loadExtractor(u, referer, subtitleCallback, cb) } catch (_: Exception) {}
                // v24.2: hosts del agregador sin rama propia (iplayerhls.com,
                // tioplayer.com, filemoon.sx, dhtpre.com) son players con JS
                // empaquetado: mismo tratamiento que la familia streamwish.
                if (links == 0) extractTdPacked(u, referer, serverName, cb)
                if (links == 0) extractTdFromPage(u, referer, serverName, cb)
            }
        }
        return links > 0
    }

    /**
     * v23.1: GET con manejo del challenge tipo modagamers. Patrón observado en
     * vivo: la primera visita a una URL devuelve 302 hacia un click de publicidad
     * y planta la cookie sid; repitiendo la MISMA URL con esa cookie el servidor
     * responde 200 (o una página con window.location.replace hacia el siguiente
     * paso). HTTP 429 = rate limit: esperar y reintentar.
     */
    private suspend fun tdGetWithChallenge(startUrl: String, referer: String?, maxSteps: Int = 5): String? {
        var current = startUrl
        repeat(maxSteps) {
            val resp = try {
                app.get(
                    current, referer = referer,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    timeout = 20L, allowRedirects = false
                )
            } catch (_: Exception) {
                return null
            }
            when {
                resp.code in 300..399 -> {
                    // Redirect de publicidad: la cookie sid ya quedó en la sesión
                    // de NiceHttp; reintentar la MISMA URL.
                }
                resp.code == 429 -> kotlinx.coroutines.delay(2500)
                else -> {
                    val text = resp.text
                    val jsRedirect = Regex("window\\.location\\.replace\\('([^']+)'")
                        .find(text)?.groupValues?.get(1)
                    if (jsRedirect != null) {
                        current = jsRedirect
                    } else if (text.length > 1500) {
                        return text
                    }
                }
            }
        }
        return null
    }

    /** modagamers: resolver challenge y buscar m3u8/mp4 en el player final. */
    private suspend fun extractTdPackedFromChallenge(
        embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = tdGetWithChallenge(embedUrl, referer) ?: return false
        return tdEmitFromPlayerScript(
            try { getAndUnpack(html) } catch (_: Exception) { html }, referer, serverName, callback
        )
    }

    /**
     * Fuentes de un script de player ya des-empaquetado. En VidHide el orden es
     * `links={"hls2":"https://.../master.m3u8"}` seguido de
     * `sources:[{file:links.hls2}]`: el `file:` NO es una cadena, así que la
     * fuente se toma del m3u8 directo.
     */
    private suspend fun tdEmitFromPlayerScript(
        unpacked: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        Regex("""["']?hls["']?\s*:\s*\{[^}]*?["']?(?:url|file)["']?\s*:\s*["']([^"']+)["']""")
            .find(unpacked)?.let { m ->
                try { generateM3u8(serverName, m.groupValues[1], referer).forEach(callback); found = true } catch (_: Exception) {}
            }
        if (!found) {
            Regex("""["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(unpacked).forEach { m ->
                try { generateM3u8(serverName, m.groupValues[1], referer).forEach(callback); found = true } catch (_: Exception) {}
            }
        }
        if (!found) {
            Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").findAll(unpacked).map { it.value }.distinct().forEach { cand ->
                try { generateM3u8(serverName, cand, referer).forEach(callback); found = true } catch (_: Exception) {}
            }
        }
        if (!found) {
            Regex("""["']?file["']?\s*:\s*["']([^"']+\.mp4[^"']*)["']""").findAll(unpacked).forEach { m ->
                if (CE.isJunkVideoUrl(m.groupValues[1])) return@forEach
                callback(newExtractorLink(source = serverName, name = serverName, url = m.groupValues[1]) {
                    this.referer = referer; this.quality = Qualities.Unknown.value
                })
                found = true
            }
        }
        return found
    }

    /** Dedupe de subs/videos de DM entre servers del episodio. */
    private val tdEmittedDmSubs = mutableSetOf<String>()
    private val tdEmittedDmVids = mutableSetOf<String>()

    private suspend fun extractTdDailymotion(
        embedUrl: String, referer: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // v24.3: tambien geo.dailymotion.com/player.html?video=ID (variante
        // servida en algunos embeds del agregador, ya cubierta en SeriesDonghua)
        val videoId = Regex("dailymotion\\.com/(?:embed/)?video/([a-zA-Z0-9]+)|geo\\.dailymotion\\.com/player\\.html\\?video=([a-zA-Z0-9]+)")
            .find(embedUrl)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } } ?: return false
        try { app.get(embedUrl, referer = referer, timeout = 15L) } catch (_: Exception) {}
        // v27: primero el extractor COMÚN (master completo con audio + dedupe).
        if (CE.extractDailymotion(
                "https://www.dailymotion.com/embed/video/$videoId",
                referer, serverName, TD_USER_AGENT, tdEmittedDmSubs, tdEmittedDmVids,
                subtitleCallback, callback
            )
        ) return true
        // v28: el unico respaldo que queda es el master HLS del metadata. Las
        // ramas de generateM3u8 (partia el master y perdía el audio) y de mp4
        // (solo casaban decoys) se eliminaron.
        return CE.extractDailymotionMaster(videoId, embedUrl, serverName, TD_USER_AGENT, callback)
    }

    /**
     * Extractor para players con JS ofuscado tipo packer
     * (streamwish/sblona/ahvsh/filelions/vgembed/vidhide): getAndUnpack y buscar
     * hls/file/m3u8/mp4.
     */
    private suspend fun extractTdPacked(
        embedUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(embedUrl, referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT), timeout = 20L).text
            val unpacked = try { getAndUnpack(html) } catch (_: Exception) { html }
            tdEmitFromPlayerScript(unpacked, referer, serverName, callback)
        } catch (_: Exception) {
            false
        }
    }

    /** Último recurso: buscar m3u8/mp4 en la página del embed. */
    private suspend fun extractTdFromPage(
        playerUrl: String, referer: String, serverName: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val text = app.get(playerUrl, referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT), timeout = 15L).text
            for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").findAll(text)) {
                try { generateM3u8(serverName, m.value, playerUrl).forEach(callback); return true } catch (_: Exception) {}
            }
            for (m in Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""").findAll(text)) {
                if (CE.isJunkVideoUrl(m.value)) continue
                callback(newExtractorLink(source = serverName, name = serverName, url = m.value) {
                    this.referer = playerUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
