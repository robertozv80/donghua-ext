package com.animecube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.jsoup.Jsoup

/**
 * AnimeCube (https://animecube.live/) — SPA Next.js con API JSON propia.
 * Contrato verificado en vivo (2026-10):
 *
 * - Catalogo SSR: el HTML del home trae cards <article><a aria-label=... href="/anime/<slug>">
 *   (59 series, tambien listadas en /sitemap.xml).
 * - Ficha: GET /anime/<slug>?_rsc=1 con header RSC:1 devuelve el flight payload
 *   con JSON embebido: "episodes":[{"id":"<slug>-<seasonId>-ep-<N>",...}],
 *   "primaryTabs":[{"id":"primary-1","seasons":[{"id":"<seasonId>",...}]}].
 * - Registro de versiones: GET /api/anime-sources-versions (header X-Obf: hex16
 *   opcional) -> {"bySeason":{"<slug>":{"<primaryTabId>":{"<seasonId>":"<version>"}}}}.
 *   Si la respuesta viene cifrada ({"d": base64}) se descifra AES-GCM con clave
 *   SHA-256("X-Obf|X-Registry-ETag") e IV = primeros 12 bytes (igual que el JS).
 * - Fuentes por episodio: GET /api/anime/<slug>/episode/<epId>/sources
 *   ?v=<version>&primaryTabId=<ptId>&seasonId=<seId>  ->  {"sources":[{"platform":
 *   "dailymotion","videoId":"x9r2zum","quality":"4K","goodSub":false,...}]}
 *   (con X-Obf puede venir cifrado como {"d":...} con la version como clave).
 * - Reproduccion: Dailymotion via metadata (subs + HLS), como el resto.
 *
 * NOTA 2026-10: varios videos DM del sitio fueron eliminados (DM005/DM010);
 * loadLinks emite los servidores disponibles y falla en silencio si el video
 * ya no existe.
 */

/** Extrae el primer array JSON balanceado a partir de [from] (indice del '[' o cercano). */
private fun acBalancedArray(json: String, from: Int): String? {
    val open = json.indexOf('[', from)
    if (open < 0) return null
    var depth = 0
    var inStr = false
    var esc = false
    for (i in open until json.length) {
        val c = json[i]
        if (esc) { esc = false; continue }
        when {
            c == '\\' && inStr -> esc = true
            c == '"' -> inStr = !inStr
            !inStr && c == '[' -> depth++
            !inStr && c == ']' -> {
                depth--
                if (depth == 0) return json.substring(open, i + 1)
            }
        }
    }
    return null
}

/** Extrae el primer objeto JSON balanceado a partir de [from]. */
private fun acBalancedObject(json: String, from: Int): String? {
    val open = json.indexOf('{', from)
    if (open < 0) return null
    var depth = 0
    var inStr = false
    var esc = false
    for (i in open until json.length) {
        val c = json[i]
        if (esc) { esc = false; continue }
        when {
            c == '\\' && inStr -> esc = true
            c == '"' -> inStr = !inStr
            !inStr && c == '{' -> depth++
            !inStr && c == '}' -> {
                depth--
                if (depth == 0) return json.substring(open, i + 1)
            }
        }
    }
    return null
}

/** Desescapa un string JSON (incluye \uXXXX). */
private fun acUnescJson(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'u' -> {
                    val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 6
                    } else {
                        sb.append(s[i + 1])
                        i += 2
                    }
                }
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                else -> { sb.append(s[i + 1]); i += 2 }
            }
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private fun acRandomHex(): String {
    val bytes = ByteArray(16)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Descifra el payload GCM del sitio: IV = 12 primeros bytes del base64. */
private fun acDecrypt(b64: String, keyMaterial: String): String? {
    return try {
        val data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val key = md.digest(keyMaterial.toByteArray(Charsets.UTF_8))
        val spec = javax.crypto.spec.SecretKeySpec(key, "AES")
        val iv = data.copyOfRange(0, 12)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, spec, javax.crypto.spec.GCMParameterSpec(128, iv))
        String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}

private data class AcSourceEntry(val platform: String, val videoId: String, val quality: String)
/** Item del catalogo del home (payload RSC): slug, titulo, poster y generos. */
private data class AcCatalogItem(
    val slug: String,
    val title: String,
    val poster: String,
    val genres: List<String>
)

/** Convierte un slug en titulo legible (equivalente top-level del miembro de la clase). */
private fun acTitleFromSlug(slug: String): String =
    slug.replace("-", " ").replace(Regex("\\b[a-z]")) { it.value.uppercase() }

/**
 * Extrae el catalogo del home desde el flight payload embebido en el HTML.
 * Las cadenas vienen escapadas (\" y \u0026); se desescapan primero.
 * Regex validada en vivo (2026-10): 56 items, con generos y metadatos completos
 * (Action 47, Adventure 29, Fantasy 43, Martial Arts 7).
 */
private fun acCatalog(html: String): List<AcCatalogItem> {
    val un = html.replace("\\\"", "\"").replace("\\u0026", "&")
    val merged = LinkedHashMap<String, AcCatalogItem>()

    // Metadatos: poster + slug + titulo (las claves del payload vienen alfabeticas).
    val metaRe = Regex(
        "\"coverImage\":\"([^\"]+)\"[^}]*?\"genres\":\\[[^\\]]*\\][^}]*?\"slug\":\"([a-z0-9-]+)\"[^}]*?\"title\":\"((?:[^\"\\\\]|\\\\.)*)\""
    )
    metaRe.findAll(un).forEach { m ->
        val poster = m.groupValues[1]
        val slug = m.groupValues[2]
        val title = acUnescJson(m.groupValues[3])
        merged[slug] = AcCatalogItem(slug, if (title.isBlank()) acTitleFromSlug(slug) else title, poster, emptyList())
    }

    // Generos por slug.
    val genreRe = Regex("\"genres\":\\[([^\\]]*)\\][^}]*?\"slug\":\"([a-z0-9-]+)\"")
    genreRe.findAll(un).forEach { m ->
        val slug = m.groupValues[2]
        val genres = m.groupValues[1].split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        val prev = merged[slug]
        merged[slug] = if (prev != null) prev.copy(genres = genres)
        else AcCatalogItem(slug, acTitleFromSlug(slug), "", genres)
    }

    return merged.values.toList()
}

class AnimeCubeProvider : MainAPI() {
    override var mainUrl = "https://animecube.live"
    override var name = "AnimeCube"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }

    // ==================== MAIN PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/##foryou" to "For You",
        "$mainUrl/##genre:Action" to "Action",
        "$mainUrl/##genre:Adventure" to "Adventure",
        "$mainUrl/##genre:Fantasy" to "Fantasy",
        "$mainUrl/##genre:Martial Arts" to "Martial Arts"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/", headers = mapOf("User-Agent" to UA)).text
        val catalog = acCatalog(html)
        val bySlug = catalog.associateBy { it.slug }

        // Secciones de genero: el sitio no expone paginas de genero, pero el payload del home
        // trae el campo "genres" de cada serie; se filtra el catalogo por genero.
        if (request.data.contains("##genre:")) {
            val want = request.data.substringAfter("##genre:").lowercase()
            val items = catalog
                .filter { it.genres.any { g -> g.lowercase() == want } }
                .sortedBy { it.title.lowercase() }
                .map { c ->
                    newAnimeSearchResponse(c.title.ifBlank { acTitleFromSlug(c.slug) }, "$mainUrl/anime/${c.slug}") {
                        this.posterUrl = c.poster
                    }
                }
            return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext = false)
        }

        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // Cards SSR del home: <a aria-label="..." href="/anime/<slug>...">
        Regex("""<a[^>]+aria-label="([^"]+)"[^>]+href="/anime/([a-z0-9-]+)[^"]*""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { m ->
                val (label, slug) = m.destructured
                if (seen.add(slug)) {
                    val c = bySlug[slug]
                    items.add(newAnimeSearchResponse(c?.title?.takeIf { it.isNotBlank() } ?: label.trim(), "$mainUrl/anime/$slug") {
                        this.posterUrl = c?.poster ?: ""
                    })
                }
            }
        // Complementar con el sitemap (catalogo completo)
        try {
            val sm = app.get("$mainUrl/sitemap.xml", headers = mapOf("User-Agent" to UA)).text
            Regex("""<loc>$mainUrl/anime/([a-z0-9-]+)</loc>""").findAll(sm).forEach { m ->
                val slug = m.groupValues[1]
                if (seen.add(slug)) {
                    val c = bySlug[slug]
                    items.add(
                        newAnimeSearchResponse(c?.title?.takeIf { it.isNotBlank() } ?: acSlugToTitle(slug), "$mainUrl/anime/$slug") {
                            this.posterUrl = c?.poster ?: ""
                        }
                    )
                }
            }
        } catch (_: Exception) {}
        return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext = false)
    }

    private fun acSlugToTitle(slug: String): String =
        slug.replace("-", " ").replace(Regex("\\b[a-z]")) { it.value.uppercase() }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase().replace(" ", "-")
        if (q.isEmpty()) return emptyList()
        val sm = app.get("$mainUrl/sitemap.xml", headers = mapOf("User-Agent" to UA)).text
        val slugs = Regex("""<loc>$mainUrl/anime/([a-z0-9-]+)</loc>""").findAll(sm)
            .map { it.groupValues[1] }
            .filter { it.contains(q) }
            .toList()
        // FIX v3: posters/titulos desde el catalogo del home (payload RSC)
        val bySlug = try {
            val html = app.get("$mainUrl/", headers = mapOf("User-Agent" to UA)).text
            acCatalog(html).associateBy { it.slug }
        } catch (_: Exception) {
            emptyMap()
        }
        return slugs.map { slug ->
            val c = bySlug[slug]
            newAnimeSearchResponse(c?.title?.takeIf { it.isNotBlank() } ?: acSlugToTitle(slug), "$mainUrl/anime/$slug") {
                this.posterUrl = c?.poster ?: ""
            }
        }
    }

    // ==================== DETAIL ====================

    private data class AcEpisode(val id: String, val number: Int, val numberDisplay: String, val title: String)
    private data class AcSeason(val id: String, val title: String, val episodes: List<AcEpisode>)

    /**
     * Parsea seasons/episodes del flight payload (regex tolerantes). Devuelve
     * las temporadas en orden de aparicion.
     */
    private fun parseRscSeasons(flight: String, slug: String): List<AcSeason> {
        // FIX v3: el grupo atomico con cuantificador perezoso (?>[^{}]*?) se congelaba
        // con 0 caracteres y el regex nunca hacia match -> 0 episodios y ficha vacia.
        // [^{}]*? plano es suficiente: entre id y title no hay llaves anidadas.
        val seasonHead = Regex("""\{"id":"(tab-[\w-]+)"[^{}]*?"title":"((?:[^"\\]|\\.)*)"""")
        val epPattern = Regex(
            """\{"id":"([\w-]+)","number":(\d+),"numberDisplay":"((?:[^"\\]|\\.)*)","title":"((?:[^"\\]|\\.)*)""""
        )
        val seasons = mutableListOf<AcSeason>()
        val seenSeasons = mutableSetOf<String>()

        var idx = 0
        while (true) {
            val seasonsKey = flight.indexOf("\"seasons\":[", idx)
            if (seasonsKey < 0) break
            val arrStart = seasonsKey + "\"seasons\":".length
            val arr = acBalancedArray(flight, arrStart)
            if (arr == null) { idx = seasonsKey + 12; continue }

            var pos = arr.indexOf("{\"id\":\"tab-")
            while (pos >= 0) {
                val m = seasonHead.find(arr, pos)
                if (m == null || m.range.first != pos) break
                val seId = m.groupValues[1]
                if (seenSeasons.add(seId)) {
                    val seTitle = acUnescJson(m.groupValues[2])
                    var eps = emptyList<AcEpisode>()
                    val epArrIdx = arr.indexOf("\"episodes\":[", m.range.last)
                    if (epArrIdx >= 0) {
                        val epArr = acBalancedArray(arr, epArrIdx + "\"episodes\":".length - 1)
                        if (epArr != null) {
                            eps = epPattern.findAll(epArr).mapNotNull { em ->
                                val id = em.groupValues[1]
                                if (!id.startsWith("$slug-")) null
                                else AcEpisode(
                                    id, em.groupValues[2].toIntOrNull() ?: 0,
                                    acUnescJson(em.groupValues[3]), acUnescJson(em.groupValues[4])
                                )
                            }.toList()
                        }
                    }
                    seasons.add(AcSeason(seId, seTitle, eps))
                }
                pos = arr.indexOf("{\"id\":\"tab-", pos + 1)
            }
            idx = seasonsKey + 12
        }
        return seasons
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val flight = app.get(
            "$mainUrl/anime/$slug?_rsc=1",
            headers = mapOf("User-Agent" to UA, "RSC" to "1"),
            timeout = 25L
        ).text

        val seasons = parseRscSeasons(flight, slug)

        // Titulo: og:title del HTML SSR ("<slug> - Watch Online | Anime Cube")
        val html = app.get("$mainUrl/anime/$slug", headers = mapOf("User-Agent" to UA)).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.removeSuffix(" - Watch Online | Anime Cube")?.trim()
            ?: acSlugToTitle(slug)
        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim() ?: ""

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        val seenNums = mutableSetOf<Int>()
        for (season in seasons) {
            // Pseudo-temporada "latest" (titulo vacio, 1 ep): suele duplicar el ultimo
            // episodio de la temporada real y NO aparece en el registro de sources ->
            // si el numero ya salio, se omite para no dejar un episodio muerto.
            val isPseudo = season.title.isBlank() && season.episodes.size <= 1
            for (ep in season.episodes) {
                if (!seen.add(ep.id)) continue
                if (isPseudo && seenNums.contains(ep.number)) continue
                seenNums.add(ep.number)
                episodes.add(
                    newEpisode(ep.id) {
                        this.name = buildString {
                            append(if (ep.title.isNotBlank()) ep.title else "Episode ${ep.numberDisplay}")
                            if (seasons.size > 1) append(" · ${season.title}")
                        }
                        this.episode = ep.number
                    }
                )
            }
        }
        val sorted = episodes.sortedBy { it.episode ?: 0 }

        // FIX v3: objeto principal de la serie en el flight (poster/rating/status/year/genres)
        val main = acMainAnimeObject(flight, slug)
        val cover = main?.let { Regex("\"coverImage\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) } ?: ""
        val rating = main?.let { Regex("\"rating\":([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val acStatus = main?.let { Regex("\"status\":\"([\\w-]+)\"").find(it)?.groupValues?.get(1) }
        val acYear = main?.let { Regex("\"year\":(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val acGenres = main?.let { m ->
            Regex("\"genres\":\\[([^\\]]*)\\]").find(m)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
        } ?: emptyList()
        val durationMin = Regex("\"duration\":(\\d+)").find(flight)?.groupValues?.get(1)?.toIntOrNull()

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
            this.plot = description
            if (cover.isNotBlank()) this.posterUrl = cover
            if (acGenres.isNotEmpty()) this.tags = acGenres
            this.showStatus = when (acStatus) {
                "ongoing" -> ShowStatus.Ongoing
                "season-completed", "completed" -> ShowStatus.Completed
                else -> null
            }
            if (acYear != null) this.year = acYear
            if (rating != null && rating > 0.0) this.score = Score.from10(rating)
            if (durationMin != null && durationMin > 0) this.duration = durationMin
            this.episodes = mutableMapOf(DubStatus.Subbed to sorted)
        }
    }

    // ==================== VERSIONES / SOURCES ====================

    private data class AcVersionEntry(val primaryTabId: String, val seasonId: String, val version: String)

    /**
     * Objeto principal de la serie dentro del flight (contiene aliases, coverImage,
     * genres, rating, slug, status, title, year, ...). Se busca la clave "slug" y
     * se retrocede hasta la llave de apertura del objeto.
     */
    private fun acMainAnimeObject(flight: String, slug: String): String? {
        val key = "\"slug\":\"$slug\""
        val i = flight.indexOf(key)
        if (i < 0) return null
        val start = flight.lastIndexOf('{', i)
        if (start < 0) return null
        return acBalancedObject(flight, start)
    }

    /** Registro de versiones: soporta respuesta plana y cifrada (con X-Obf propio). */
    private suspend fun fetchRegistry(): List<AcVersionEntry> {
        return try {
            val baseHeaders = mapOf("User-Agent" to UA, "Accept" to "application/json")
            val r = app.get("$mainUrl/api/anime-sources-versions", headers = baseHeaders, timeout = 20L)
            val body = r.text
            if (body.contains("\"bySeason\"")) {
                parseRegistryPlain(body)
            } else {
                // Cifrado: reintentar con X-Obf propio y descifrar con etag+obf
                val obf = acRandomHex()
                val resp = app.get(
                    "$mainUrl/api/anime-sources-versions",
                    headers = baseHeaders + mapOf("X-Obf" to obf), timeout = 20L
                )
                val etag = resp.headers["X-Registry-ETag"] ?: return emptyList()
                val d = resp.text.substringAfter("\"d\":\"").substringBefore("\"")
                if (d.isBlank()) return emptyList()
                acDecrypt(d, "$obf|$etag")?.let { parseRegistryPlain(it) } ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseRegistryPlain(raw: String): List<AcVersionEntry> {
        val out = mutableListOf<AcVersionEntry>()
        // {"bySeason":{"<slug>":{"<ptId>":{"<seId>":"<version>"}}}}
        val bySeason = acBalancedObject(raw, raw.indexOf("\"bySeason\"")) ?: return out
        var i = 0
        while (i < bySeason.length) {
            val slugM = Regex(""""([a-z0-9-]+)":\s*\{""").find(bySeason, i) ?: break
            val slugObj = acBalancedObject(bySeason, slugM.range.last)
            if (slugObj == null) { i = slugM.range.last + 1; continue }
            var j = 0
            while (j < slugObj.length) {
                val ptM = Regex(""""(primary-[\w-]+)":\s*\{""").find(slugObj, j) ?: break
                val ptObj = acBalancedObject(slugObj, ptM.range.last)
                if (ptObj != null) {
                    Regex(""""(tab-[\w-]+)":\s*"([^"]+)"""").findAll(ptObj).forEach { m ->
                        out.add(AcVersionEntry(ptM.groupValues[1], m.groupValues[1], m.groupValues[2]))
                    }
                }
                j = ptM.range.last + (ptObj?.length ?: 1)
            }
            i = slugM.range.last + slugObj.length
        }
        return out
    }

    /** Llama a /sources; soporta respuesta plana {"sources":[...]} y cifrada {"d":...}. */
    private suspend fun fetchSources(
        slug: String, epId: String, ptId: String, seId: String, version: String
    ): List<AcSourceEntry> {
        return try {
            val obf = acRandomHex()
            val u = "$mainUrl/api/anime/$slug/episode/$epId/sources" +
                "?v=" + java.net.URLEncoder.encode(version, "UTF-8") +
                "&primaryTabId=" + java.net.URLEncoder.encode(ptId, "UTF-8") +
                "&seasonId=" + java.net.URLEncoder.encode(seId, "UTF-8")
            val text = app.get(
                u,
                headers = mapOf("User-Agent" to UA, "Accept" to "application/json", "X-Obf" to obf),
                timeout = 20L
            ).text
            val payload = if (text.contains("\"sources\"")) text
            else {
                val d = text.substringAfter("\"d\":\"").substringBefore("\"")
                if (d.isBlank()) "" else acDecrypt(d, "$obf|$version") ?: ""
            }
            if (payload.isEmpty()) return emptyList()

            Regex(""""platform":"([\w-]+)"\s*,\s*(?:"privateId":"[^"]*"\s*,\s*)?"quality":"([^"]*)"\s*,\s*"videoId":"([\w-]+)"""")
                .findAll(payload)
                .map { AcSourceEntry(it.groupValues[1], it.groupValues[3], it.groupValues[2]) }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ==================== VIDEO EXTRACTION ====================

    override suspend fun loadLinks(
        data: String, // episode id: <slug>-<seasonId>-ep-<N>
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val m = Regex("""^(.+)-(tab-[\w-]+)-ep-(\d+(?:-\d+)?)$""").find(data) ?: return false
        val slug = m.groupValues[1]
        val seId = m.groupValues[2]

        val registry = fetchRegistry()
        val entry = registry.firstOrNull { it.seasonId == seId }
            ?: registry.firstOrNull()
            ?: return false

        val sources = fetchSources(slug, data, entry.primaryTabId, entry.seasonId, entry.version)
        if (sources.isEmpty()) return false

        val emittedSubs = mutableSetOf<String>()
        var found = false
        for (s in sources) {
            when (s.platform) {
                "dailymotion" -> {
                    val ok = extractDailymotion(
                        s.videoId, "$mainUrl/",
                        "Dailymotion${if (s.quality.isNotBlank()) " (${s.quality})" else ""}",
                        emittedSubs, subtitleCallback, callback
                    )
                    if (ok) found = true
                }
                "rumble" -> {
                    val ok = extractRumble(s.videoId, "Rumble", callback)
                    if (ok) found = true
                }
            }
        }
        return found
    }

    // ==================== EXTRACTORES ====================

    private fun dmSubtitlesBlock(json: String): String? {
        val start = json.indexOf("\"subtitles\"")
        if (start < 0) return null
        val dataIdx = json.indexOf("\"data\"", start)
        if (dataIdx < 0) return null
        val open = json.indexOf('{', dataIdx)
        if (open < 0) return null
        var depth = 0
        for (i in open until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(open, i + 1)
                }
            }
        }
        return null
    }

    private suspend fun extractDailymotion(
        vid: String,
        referer: String,
        linkName: String,
        emittedSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val metaUrl = "https://www.dailymotion.com/player/metadata/video/$vid" +
                "?embedder=" + java.net.URLEncoder.encode(referer, "UTF-8") + "&integration=inline"
            val json = app.get(metaUrl, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 20L)
                .text.replace("\\/", "/").replace("\\u0026", "&")

            dmSubtitlesBlock(json)?.let { block ->
                Regex(""""(es[a-z-]*|en[a-z-]*)":\s*\{[^{}]*?"urls":\["([^"]+)"""")
                    .findAll(block)
                    .map { it.groupValues[1] to acUnescJson(it.groupValues[2]) }
                    .distinctBy { it.second }
                    .sortedBy { (code, _) -> if (code.startsWith("es")) 0 else 1 }
                    .forEach { (code, url) ->
                        if (emittedSubs.add(url)) {
                            subtitleCallback.invoke(
                                SubtitleFile(if (code.startsWith("es")) "Spanish" else "English", url)
                            )
                        }
                    }
            }

            var found = false
            Regex(""""url":"(https://[^"]+?\.m3u8[^"]*)"""").findAll(json)
                .map { acUnescJson(it.groupValues[1]) }
                .firstOrNull()
                ?.let { hls ->
                    try {
                        generateM3u8(linkName, hls, "https://www.dailymotion.com/").forEach(callback)
                        found = true
                    } catch (_: Exception) {}
                }
            Regex(""""(\d{3,4})":\s*\[\s*\{\s*"type":\s*"video/mp4"\s*,\s*"url":\s*"([^"]+)"""")
                .findAll(json)
                .forEach { m ->
                    val h = m.groupValues[1].toIntOrNull()
                    callback(
                        newExtractorLink(source = linkName, name = linkName, url = acUnescJson(m.groupValues[2])) {
                            this.referer = "https://www.dailymotion.com/"
                            this.quality = when {
                                h == null -> Qualities.Unknown.value
                                h >= 1080 -> Qualities.P1080.value
                                h >= 720 -> Qualities.P720.value
                                h >= 480 -> Qualities.P480.value
                                else -> Qualities.P360.value
                            }
                        }
                    )
                    found = true
                }
            found
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractRumble(
        vid: String,
        linkName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embed = "https://rumble.com/embed/$vid/"
            val api = "https://rumble.com/embedJS/u3/?request=video&v=$vid" +
                "&embed=" + java.net.URLEncoder.encode(embed, "UTF-8")
            val json = app.get(api, referer = "https://rumble.com/", headers = mapOf("User-Agent" to UA), timeout = 20L)
                .text.replace("\\/", "/")
            var found = false
            Regex(""""(\d{3,4})":\["(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"""")
                .findAll(json).forEach { m ->
                    val h = m.groupValues[1].toIntOrNull()
                    callback(
                        newExtractorLink(source = linkName, name = linkName, url = m.groupValues[2]) {
                            this.referer = "https://rumble.com/"
                            this.quality = when {
                                h == null -> Qualities.Unknown.value
                                h >= 1080 -> Qualities.P1080.value
                                h >= 720 -> Qualities.P720.value
                                h >= 480 -> Qualities.P480.value
                                else -> Qualities.P360.value
                            }
                        }
                    )
                    found = true
                }
            Regex(""""hls":\{"url":"(https://rumble\.com/hls-vod/[^"]+)"""").find(json)?.let { m ->
                try {
                    generateM3u8(linkName, m.groupValues[1], embed).forEach(callback)
                    found = true
                } catch (_: Exception) {}
            }
            found
        } catch (_: Exception) {
            false
        }
    }
}
