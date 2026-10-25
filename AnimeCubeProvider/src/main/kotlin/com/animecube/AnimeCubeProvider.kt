package com.animecube

import com.lagradost.cloudstream3.*
import com.donghuaext.common.CommonExtractors as CE
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.jsoup.Jsoup
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
/** Item del catalogo del home (payload RSC): slug, titulo, poster, generos y rating. */
private data class AcCatalogItem(
    val slug: String,
    val title: String,
    val poster: String,
    val genres: List<String>,
    val rating: Double? = null
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
    // FIX v4 (2026-10): el flight de Next.js reparte el payload en chunks
    // (self.__next_f.push) y una cadena larga puede quedar partida a media URL:
    //   ..."coverImage":"https://images.animecube.live/assets/images/dongh"
    //   ])</script><script>self.__next_f.push([1,"ua/series/perfect-world/...
    // El poster quedaba truncado (grid "For You": Perfect World, Soul Land 2...).
    // Se unen los chunks ANTES de desescapar y parsear.
    val joined = html.replace("\"]</script><script>self.__next_f.push([1,\"", "")
    val un = joined.replace("\\\"", "\"").replace("\\u0026", "&")
    val merged = LinkedHashMap<String, AcCatalogItem>()

    // Metadatos: poster + slug + titulo. NO se exige "genres" entre medias: hay
    // series sin generos en el payload (p.ej. soul-land-2-the-peerless-tang-clan)
    // y el poster quedaba sin recoger -> imagen vacia en el grid.
    val metaRe = Regex(
        "\"coverImage\":\"([^\"]+)\"[^}]*?\"slug\":\"([a-z0-9-]+)\"[^}]*?\"title\":\"((?:[^\"\\\\]|\\\\.)*)\""
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

    // Rating por slug ("rating":9.2, ... "slug":...). Se usa para la etiqueta
    // de calificacion sobre el poster en el grid.
    Regex("\"rating\":([0-9.]+)[^}]*?\"slug\":\"([a-z0-9-]+)\"").findAll(un).forEach { m ->
        val slug = m.groupValues[2]
        val rating = m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@forEach
        val prev = merged[slug]
        merged[slug] = if (prev != null) prev.copy(rating = rating)
        else AcCatalogItem(slug, acTitleFromSlug(slug), "", emptyList(), rating)
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

        private const val AC_HTML_TTL_MS = 90_000L
        private val acHtmlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
        private val acHtmlMutex = Mutex()

        private fun acContext(): Context? {
            // Mismo patron de 3 niveles que DWW: el helper de la API no es
            // visible desde el classloader del plugin, y AcraApplication
            // cambia de nombre de campo entre builds.
            try {
                val m = Class.forName("com.lagradost.api.ContextHelper_jvmKt")
                    .declaredMethods.firstOrNull { it.name == "getContext" }
                (m?.invoke(null) as? Context)?.let { return it }
            } catch (_: Throwable) {}
            try {
                val f = Class.forName("com.lagradost.cloudstream3.AcraApplication")
                    .getDeclaredField("context")
                f.isAccessible = true
                (f.get(null) as? Context)?.let { return it }
            } catch (_: Throwable) {}
            return try {
                val m = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication")
                m.isAccessible = true
                m.invoke(null) as? Context
            } catch (_: Throwable) {
                null
            }
        }

        /** HTML fresco via WebView: el Chromium del app pasa el fingerprint que
         *  Cloudflare/Next.js le exige; el flight embebido en el SSR trae TODAS
         *  las temporadas y los titulos actuales ("Season N Episode M").
         *  Los puentes OkHttp (app.get y cliente propio) reciben variantes
         *  viejas cacheadas (sin Season 4, titulos "Episode N") que ni el
         *  cache-buster ni los headers logran esquivar. */
        private suspend fun acWebViewHtml(url: String): String? = withContext(Dispatchers.Main) {
            val now = System.currentTimeMillis()
            acHtmlCache[url]?.let { (ts, h) -> if (now - ts < AC_HTML_TTL_MS) return@withContext h }
            val ctx = acContext()
            if (ctx == null) return@withContext null
            acHtmlMutex.withLock {
                val t2 = System.currentTimeMillis()
                acHtmlCache[url]?.let { (ts, h) -> if (t2 - ts < AC_HTML_TTL_MS) return@withContext h }
                val html = suspendCancellableCoroutine { cont ->
                    var webView: WebView? = null
                    // Los postDelayed de un WebView sin adjuntar a ventana NUNCA
                    // corren -> programar con Handler propio.
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    var done = false
                    var result: String? = null
                    fun finish() {
                        if (done) return
                        done = true
                        mainHandler.removeCallbacksAndMessages(null)
                        try { webView?.stopLoading() } catch (_: Throwable) {}
                        try { webView?.destroy() } catch (_: Throwable) {}
                        if (cont.isActive) cont.resume(result) {}
                    }
                    fun unjson(s: String?): String? = try {
                        org.json.JSONTokener(s ?: "\"\"").nextValue() as? String
                    } catch (_: Throwable) {
                        null
                    }
                    fun goodHtml(h: String?): Boolean {
                        if (h.isNullOrBlank() || h.length < 20000) return false
                        val lower = h.lowercase()
                        return !lower.contains("just a moment") &&
                            !lower.contains("un momento") &&
                            !h.contains("_cf_chl_opt")
                    }
                    var attempts = 0
                    fun checkPage(view: WebView) {
                        view.evaluateJavascript("document.documentElement.outerHTML") { htmlJson ->
                            val h = unjson(htmlJson)
                            if (goodHtml(h)) {
                                result = h
                                finish()
                            } else if (++attempts <= 20) {
                                mainHandler.postDelayed({ checkPage(view) }, 3000L)
                            } else {
                                finish()
                            }
                        }
                    }
                    try {
                        CookieManager.getInstance().setAcceptCookie(true)
                        val wv = WebView(ctx)
                        webView = wv
                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        wv.settings.setSupportMultipleWindows(false)
                        wv.settings.javaScriptCanOpenWindowsAutomatically = false
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, vurl: String) {
                                checkPage(view)
                            }
                        }
                        wv.loadUrl(url)
                        mainHandler.postDelayed({ finish() }, 90_000L)
                    } catch (_: Throwable) {
                        finish()
                    }
                    cont.invokeOnCancellation { finish() }
                }
                if (html != null) {
                    acHtmlCache[url] = System.currentTimeMillis() to html
                }
                html
            }
        }

        /** Palabras vacias para la similitud de nombres de las recomendaciones. */
        private val AC_STOPWORDS = setOf(
            "the", "and", "of", "a", "an", "to", "in", "for", "with", "its", "is", "are"
        )
    }

    // ==================== MAIN PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/##foryou" to "For You",
        "$mainUrl/##genre:Action" to "Action",
        "$mainUrl/##genre:Adventure" to "Adventure",
        "$mainUrl/##genre:Fantasy" to "Fantasy",
        "$mainUrl/##genre:Martial Arts" to "Martial Arts",
        "$mainUrl/##movies" to "Movies",
        "$mainUrl/##moviesRec" to "Recommended"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/", headers = mapOf("User-Agent" to UA)).text
        val catalog = acCatalog(html)
        val bySlug = catalog.associateBy { it.slug }

        // Secciones de genero: el sitio no expone paginas de genero, pero el payload del home
        // trae el campo "genres" de cada serie; se filtra el catalogo por genero.
        // v6: seccion "Movies" + subseccion "Recomendado" con etiqueta de rating.
        if (request.data.contains("##movies")) {
            val isRec = request.data.contains("##moviesRec")
            val items = catalog
                .filter { it.slug.contains("movie") }
                .let { list ->
                    if (isRec) list.sortedByDescending { it.rating ?: 0.0 } else list
                }
                .map { c ->
                    newAnimeSearchResponse(c.title.ifBlank { acTitleFromSlug(c.slug) }, "$mainUrl/anime/${c.slug}") {
                        this.posterUrl = c.poster
                        if (c.rating != null) this.score = Score.from10(c.rating)
                    }
                }
            return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext = false)
        }
        if (request.data.contains("##genre:")) {
            val want = request.data.substringAfter("##genre:").lowercase()
            val items = catalog
                .filter { it.genres.any { g -> g.lowercase() == want } }
                .sortedBy { it.title.lowercase() }
                .map { c ->
                    newAnimeSearchResponse(c.title.ifBlank { acTitleFromSlug(c.slug) }, "$mainUrl/anime/${c.slug}") {
                        this.posterUrl = c.poster
                        // v4: etiqueta de calificacion sobre el poster (en vez de "Subbed")
                        if (c.rating != null) this.score = Score.from10(c.rating)
                    }
                }
            return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext = false)
        }

        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // Cards SSR del home: <a aria-label="..." href="/anime/<slug>..."> y tambien
        // las cards de Movies que van SIN aria-label (v6): <a class=... href="/anime/<slug>?from=home">
        Regex("""<a[^>]+href="/anime/([a-z0-9-]+)[^"]*""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { m -> m.groupValues[1] }
            .forEach { slug ->
                if (seen.add(slug)) {
                    val c = bySlug[slug]
                    items.add(newAnimeSearchResponse(c?.title?.takeIf { it.isNotBlank() } ?: acSlugToTitle(slug), "$mainUrl/anime/$slug") {
                        this.posterUrl = c?.poster ?: ""
                        if (c?.rating != null) this.score = Score.from10(c.rating)
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
                            if (c?.rating != null) this.score = Score.from10(c.rating)
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
                if (c?.rating != null) this.score = Score.from10(c.rating)
            }
            // v6: evitar resultados REPETIDOS con el mismo titulo (mismo anime,
            // distinto slug de poster/temporada en el sitemap).
        }.distinctBy { (it.name ?: "").lowercase() }
    }

    // ==================== DETAIL ====================

    private data class AcEpisode(val id: String, val number: Int, val numberDisplay: String, val title: String)
    private data class AcSeason(val id: String, val title: String, val episodes: List<AcEpisode>)

    /**
     * v6 (2026-10): temporada(s)/episodes del flight con el formato actual del
     * sitio (primaryTabs -> seasons -> episodes). El parse por regex de seasons
     * fallaba (ficha vacia) porque en posts nuevos el esquema añade
     * "customName"/"type":"movie" y el seasonHead dejaba de matchear. Ahora los
     * EPISODES se extraen directamente del array "episodes" (estable en todos
     * los posts) y las temporadas solo aportan el nombre.
     */
    private fun acParseEpisodes(flight: String, slug: String): List<AcSeason> {
        val seasons = LinkedHashMap<String, MutableAcSeason>()
        // v7 (2026-10-20 FIX "Enlaces no encontrados"): el patron anterior exigia
        // `","number"` INMEDIATAMENTE despues de los digitos del id, pero el payload
        // real termina el valor con LA COMILLA DE CIERRE ("id":"...-ep-1","number":1
        // -> falta el ") y no matcheaba NUNCA -> 0 episodios y ficha/links vacios.
        // Patrón tolerante: id + comilla de cierre opcional + campos number/numberDisplay/title.
        val epPattern = Regex("\"id\":\"([^\"]+)-(tab-[\\w-]+)-ep-(\\d+(?:-\\d+)?)\"?,\"number\":(\\d+),\"numberDisplay\":\"([^\"]*)\",\"title\":\"([^\"]*)\"")
        epPattern.findAll(flight).forEach { em ->
            val seId = em.groupValues[2]
            val id = em.groupValues[1] + "-" + seId + "-ep-" + em.groupValues[3]
            if (em.groupValues[1] != slug) return@forEach
            val ep = AcEpisode(
                id,
                em.groupValues[4].toIntOrNull() ?: 0,
                acUnescJson(em.groupValues[5]),
                acUnescJson(em.groupValues[6])
            )
            seasons.getOrPut(seId) { MutableAcSeason(seId, "") }.episodes.add(ep)
        }
        // Titulo de temporada: "id":"tab-...","number":N,"title":"..."
        Regex(""""id":"(tab-[\w-]+)","number":\d+,"title":"((?:[^"\\\\]|\\\\.)*)"""").findAll(flight).forEach { sm ->
            seasons[sm.groupValues[1]]?.let { s ->
                if (s.title.isBlank()) s.title = acUnescJson(sm.groupValues[2])
            }
        }
        return seasons.values.map { AcSeason(it.id, it.title, it.episodes.toList()) }
    }

    private class MutableAcSeason(val id: String, var title: String) {
        val episodes = ArrayList<AcEpisode>()
    }

    /**
     * v8: SINOPSIS REAL del anime. En el flight hay varias "description": la
     * del sitio es boilerplate y la del anime es la mas larga. Se elige la
     * mas larga (>= 80 chars) que no sea marketing.
     */
    private fun acSynopsis(flight: String): String {
        // v7: filtros del boilerplate del sitio (meta description actual).
        val bad = listOf(
            "stay updated", "stream quality", "premium anime", "watch donghua online",
            "watch anime online in high quality", "donate feedback", "contact us"
        )
        var best = ""
        // v8: el RSC trae comillas normales y el HTML las trae escapadas. Se
        // escanean ambas variantes y se elige la sinopsis real MAS LARGA (antes
        // se exigian 200 chars y se devolvia la PRIMERA: si el payload cambia
        // de orden o acorta el texto, la ficha quedaba en "Trama no encontrada").
        for (text in listOf(flight, flight.replace("\\\"", "\""))) {
            Regex(""""description":"((?:[^"\\\\]|\\\\.)*)"""").findAll(text).forEach { m ->
                val d = acUnescJson(m.groupValues[1]).trim()
                val dl = d.lowercase()
                if (d.length < 80) return@forEach
                if (bad.any { dl.startsWith(it) || dl.contains(it) }) return@forEach
                if (d.length > best.length) best = d
            }
        }
        return best
    }

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
        // FIX v15 (2026-10): desde el app, Cloudflare/Next.js entrega variantes
        // VIEJAS del flight/HTML (sin Season 4, titulos "Episode N") a cualquier
        // puente OkHttp — ni el cache-buster, los headers (Vary: RSC,
        // Next-Router-*) ni un cliente propio lo evitan; es el fingerprint TLS.
        // El HTML se pide ahora via WebView (mismo truco que DonghuaWorld) y el
        // RSC via app.get como ruta rapida paralela: se parsean y fusionan ambos.
        val noCache = mapOf(
            "Cache-Control" to "no-cache", "Pragma" to "no-cache",
            "Accept-Encoding" to "identity"
        )
        val ts = System.currentTimeMillis()
        val pageUrl = "$mainUrl/anime/$slug"
        val (flight, html) = coroutineScope {
            val f = async {
                runCatching {
                    app.get(
                        "$pageUrl?_rsc=1&_t=$ts",
                        headers = mapOf("User-Agent" to UA, "RSC" to "1") + noCache,
                        timeout = 25L
                    ).text
                }.getOrDefault("")
            }
            val h = async {
                try {
                    acWebViewHtml(pageUrl)
                        ?: app.get("$pageUrl?_t=$ts", headers = mapOf("User-Agent" to UA) + noCache, timeout = 25L).text
                } catch (_: Throwable) {
                    app.get("$pageUrl?_t=$ts", headers = mapOf("User-Agent" to UA) + noCache, timeout = 25L).text
                }
            }
            f.await() to h.await()
        }
        // El HTML trae el mismo flight escapado: respaldo si el RSC llega
        // corto o sin los campos nuevos.
        val htmlFlight = html.replace("\\\"", "\"")
        val bySeason = LinkedHashMap<String, AcSeason>()
        for (s in acParseEpisodes(flight, slug) + acParseEpisodes(htmlFlight, slug)) {
            val prev = bySeason[s.id]
            bySeason[s.id] = if (prev == null) s
            else prev.copy(episodes = (prev.episodes + s.episodes).distinctBy { it.id })
        }
        val seasons = bySeason.values.toList()
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.removeSuffix(" - Watch Online | Anime Cube")?.trim()
            ?: acSlugToTitle(slug)
        val description = run {
            val meta = doc.selectFirst("meta[name=description]")?.attr("content")?.trim() ?: ""
            val fromMeta = if (meta.isBlank() || meta.endsWith("Watch anime online in high quality.")) "" else meta
            if (fromMeta.isNotBlank()) fromMeta
            // v8: si el RSC no trae la descripcion, el flight del HTML
            // (desescapado) la contiene.
            else acSynopsis(flight).ifBlank { acSynopsis(htmlFlight) }
        }

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        val seenNums = mutableSetOf<Int>()
        for (season in seasons) {
            // Pseudo-temporada "latest" (titulo vacio, 1 ep): suele duplicar el ultimo
            // episodio de la temporada real y NO aparece en el registro de sources ->
            // si el numero ya salio, se omite para no dejar un episodio muerto.
            val isPseudo = season.title.isBlank() && season.episodes.size <= 1
            // FIX v13 (2026-10): establecer el campo season de CS3. Sin el, episodios
            // de temporadas distintas comparten numero (S3E1..26 y S4E1..12 -> dos
            // "Episode 1") y la app solo mostraba los de la primera temporada. El
            // numero sale del titulo ("Season 3" -> 3); si no hay, ordinal.
            val seasonNum = Regex("""(\d+)""").find(season.title)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: (seasons.indexOf(season) + 1)
            for (ep in season.episodes) {
                if (!seen.add(ep.id)) continue
                if (isPseudo && seenNums.contains(ep.number)) continue
                seenNums.add(ep.number)
                // El titulo del sitio es "Season N Episode M" (a veces con nombre
                // real detras): se quita el prefijo; si queda vacio, name = null
                // para que CS3 muestre "Episode N" agrupado por temporada, sin
                // repetir "Season 3" dos veces.
                val cleanTitle = ep.title.ifBlank { ep.numberDisplay }
                    .replace(Regex("""^Season\s*\d+\s*Episode\s*[\d-]+\s*""", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .trimStart('-', '–')
                    .trim()
                episodes.add(
                    newEpisode(ep.id) {
                        this.season = seasonNum
                        this.episode = ep.number
                        if (cleanTitle.isNotBlank()) this.name = cleanTitle
                    }
                )
            }
        }
        val sorted = episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        // FIX v3: objeto principal de la serie en el flight (poster/rating/status/year/genres)
        val main = acMainAnimeObject(flight, slug) ?: acMainAnimeObject(htmlFlight, slug)
        val cover = main?.let { Regex("\"coverImage\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) } ?: ""
        val rating = main?.let { Regex("\"rating\":([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val acStatus = main?.let { Regex("\"status\":\"([\\w-]+)\"").find(it)?.groupValues?.get(1) }
        val acYear = main?.let { Regex("\"year\":(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val acGenres = main?.let { m ->
            Regex("\"genres\":\\[([^\\]]*)\\]").find(m)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
        } ?: emptyList()
        val durationMin = Regex("\"duration\":(\\d+)").find(flight)?.groupValues?.get(1)?.toIntOrNull()

        // v4: recomendaciones (similitud de nombre, hasta 16) desde el catalogo del home.
        val recommendations = acRecommendations(title, slug)

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
            if (recommendations.isNotEmpty()) this.recommendations = recommendations
        }
    }

    /**
     * v4: recomendaciones "como en los primeros providers" — similitud de nombre
     * (hasta 16 resultados). El sitio no expone un bloque de relacionados, asi que
     * se usa el catalogo del home (mismo payload que el grid "For You"):
     *  1) Otras temporadas/peliculas de la misma serie (misma base de slug);
     *  2) El resto por SIMILITUD DE NOMBRE (solapamiento de palabras del titulo);
     *  3) Si aun faltan, relleno aleatorio del catalogo.
     */
    private suspend fun acRecommendations(seriesTitle: String, slug: String): List<SearchResponse> {
        return try {
            val html = app.get("$mainUrl/", headers = mapOf("User-Agent" to UA), timeout = 25L).text
            val catalog = acCatalog(html).filter { it.slug != slug }
            if (catalog.isEmpty()) return emptyList()

            val baseSlug = acBaseSlug(slug)
            val ownTokens = acTokens(seriesTitle)

            fun toResp(c: AcCatalogItem) = newAnimeSearchResponse(
                c.title.ifBlank { acTitleFromSlug(c.slug) }, "$mainUrl/anime/${c.slug}"
            ) {
                this.posterUrl = c.poster
                if (c.rating != null) this.score = Score.from10(c.rating)
            }

            // 1) Temporadas / partes de la misma serie, en orden numerico
            val seasons = catalog.filter { c ->
                baseSlug.isNotBlank() && acBaseSlug(c.slug) == baseSlug
            }.sortedBy { c ->
                Regex("""(?:-season)?-(\d+)$""").find(c.slug)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            val used = seasons.mapTo(HashSet()) { it.slug }

            // 2) Similitud de nombre (solapamiento de palabras)
            val ranked = catalog.filter { it.slug !in used }.map { c ->
                val t = c.title.ifBlank { acTitleFromSlug(c.slug) }
                val overlap = acTokens(t).intersect(ownTokens).size
                val sameBase = baseSlug.isNotBlank() && acBaseSlug(c.slug) == baseSlug
                Triple(c, overlap + if (sameBase) 3 else 0, acTokens(t).size)
            }.sortedWith(
                compareByDescending<Triple<AcCatalogItem, Int, Int>> { it.second }
                    .thenByDescending { it.third }
            )

            val out = ArrayList<SearchResponse>()
            seasons.forEach { out.add(toResp(it)) }
            ranked.filter { it.second > 0 }.forEach { if (out.size < 16) out.add(toResp(it.first)) }
            ranked.filter { it.second <= 0 }.map { it.first }.shuffled()
                .forEach { if (out.size < 16) out.add(toResp(it)) }
            out.take(16)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Base de slug para agrupar temporadas: "x-season-4" / "x-2" -> "x". */
    private fun acBaseSlug(slug: String): String =
        Regex("""-season-\d+$""").replace(slug, "").replace(Regex("""-\d+$"""), "")

    /** Palabras significativas de un titulo (minusculas, sin acentos, len >= 3). */
    private fun acTokens(title: String): Set<String> =
        java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .split(" ")
            .filter { it.length >= 3 && it !in AC_STOPWORDS }
            .toSet()

    // ==================== VERSIONES / SOURCES ====================

    private data class AcVersionEntry(
        val slug: String,
        val primaryTabId: String,
        val seasonId: String,
        val version: String
    )

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
            val baseHeaders = mapOf(
                "User-Agent" to UA,
                "Accept" to "application/json",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
            // Cache-buster: el http_cache de OkHttp puede devolver un registro
            // antiguo (versiones de /sources caducadas -> 403/sin fuentes).
            val u = "$mainUrl/api/anime-sources-versions?_t=${System.currentTimeMillis()}"
            val body = app.get(u, headers = baseHeaders, timeout = 20L).text
            if (body.contains("\"bySeason\"")) {
                parseRegistryPlain(body)
            } else {
                // Cifrado: reintentar con X-Obf propio y descifrar con etag+obf
                val obf = acRandomHex()
                val resp = app.get(u, headers = baseHeaders + mapOf("X-Obf" to obf), timeout = 20L)
                val etag = resp.headers["X-Registry-ETag"]
                val d = resp.text.substringAfter("\"d\":\"", "").substringBefore("\"")
                if (d.isBlank()) emptyList()
                else {
                    val key = if (etag.isNullOrBlank()) obf else "$obf|$etag"
                    val plain = acDecrypt(d, key) ?: acDecrypt(d, obf)
                    if (plain == null) android.util.Log.d("AnimeCube", "fetchRegistry: descifrado fallo (etag=$etag len=${d.length})")
                    plain?.let { parseRegistryPlain(it) } ?: emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseRegistryPlain(raw: String): List<AcVersionEntry> {
        val out = mutableListOf<AcVersionEntry>()
        try {
            val bySeason = org.json.JSONObject(raw).optJSONObject("bySeason") ?: return out
            for (slug in bySeason.keys()) {
                val perSlug = bySeason.optJSONObject(slug) ?: continue
                val keys = perSlug.keys().asSequence().toList()
                val hasPrimary = keys.any { it.startsWith("primary-") }
                if (hasPrimary) {
                    for (pt in keys) {
                        val perPt = perSlug.optJSONObject(pt) ?: continue
                        for (se in perPt.keys()) {
                            val ver = perPt.optString(se)
                            if (se.startsWith("tab-") && ver.isNotBlank())
                                out.add(AcVersionEntry(slug, pt, se, ver))
                        }
                    }
                } else {
                    // Shape plano {"tab-1":"ver"} de series nuevas.
                    for (se in keys) {
                        val ver = perSlug.optString(se)
                        if (se.startsWith("tab-") && ver.isNotBlank())
                            out.add(AcVersionEntry(slug, "primary-1", se, ver))
                    }
                }
            }
        } catch (_: Exception) {
            out.clear()
        }
        return out
    }

    /**
     * v8 (2026-10): /sources se parsea con org.json. El regex anterior exigia
     * objetos PLANOS (`{[^{}]*"platform":...}`) y el sitio añadio
     * `"skipTiming":{"intro":24}` DENTRO de cada source: ningun objeto
     * matcheaba -> sources vacio -> "Enlaces no encontrados".
     */
    private suspend fun fetchSources(
        slug: String, epId: String, ptId: String, seId: String, version: String
    ): List<AcSourceEntry> {
        val obf = acRandomHex()
        // Cache-buster: el movil puede conservar respuestas viejas en el
        // http_cache de OkHttp y la lista de fuentes se queda desactualizada.
        val u = "$mainUrl/api/anime/$slug/episode/$epId/sources" +
            "?v=" + java.net.URLEncoder.encode(version, "UTF-8") +
            "&primaryTabId=" + java.net.URLEncoder.encode(ptId, "UTF-8") +
            "&seasonId=" + java.net.URLEncoder.encode(seId, "UTF-8") +
            "&_t=" + System.currentTimeMillis()
        val baseHeaders = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

        fun parsePayload(payload: String): List<AcSourceEntry> = try {
            val arr = org.json.JSONObject(payload).optJSONArray("sources") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val platform = o.optString("platform").trim()
                // La metadata DM necesita el id LARGO (privateId); el corto
                // responde "Contenido privado".
                val id = o.optString("privateId").trim()
                    .ifBlank { o.optString("videoId").trim() }
                if (platform.isBlank() || id.isBlank()) null
                else AcSourceEntry(platform, id, o.optString("quality").trim())
            }.distinctBy { "${it.platform}|${it.videoId}" }
        } catch (_: Exception) {
            emptyList()
        }

        return try {
            val text = app.get(
                u,
                headers = baseHeaders + mapOf("X-Obf" to obf),
                timeout = 20L
            ).text
            val payload = if (text.contains("\"sources\"")) text
            else {
                // v9 FIX: probar TODAS las combinaciones de clave GCM que usa el
                // sitio (feedback del/a tester). Si ninguna abre, queda "" y el
                // fallback dispara la peticion SIN X-Obf.
                val d = text.substringAfter("\"d\":\"", "").substringBefore("\"")
                var decrypted = ""
                if (d.isNotBlank()) {
                    for (keyMaterial in listOf("$obf|$version", "$obf", "$version", "$version|$obf")) {
                        val p = acDecrypt(d, keyMaterial)
                        if (p != null) { decrypted = p; break }
                    }
                }
                if (decrypted.isBlank()) {
                    android.util.Log.d("AnimeCube", "fetchSources: ninguna clave GCM abrio el payload (len=${d.length}) vetr=$version")
                }
                decrypted
            }
            val encrypted = parsePayload(payload)
            if (encrypted.isNotEmpty()) encrypted
            else {
                // Fallback: sin X-Obf la API responde JSON plano. Evita
                // depender del cifrado GCM si el sitio cambia la clave.
                parsePayload(app.get(u, headers = baseHeaders, timeout = 20L).text)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ==================== VIDEO EXTRACTION ====================

    override suspend fun loadLinks(
        data: String, // <slug>-<seasonId>-ep-<N>; la API de CloudStream pasa el dato
        // por fixUrl() al crear el Episode, asi que llega como URL absoluta del mainUrl.
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val m = Regex("""^(?:https?://[^/]+/)?(.+)-(tab-[\w-]+)-ep-(\d+(?:-\d+)?)$""").find(data) ?: return false
        val slug = m.groupValues[1]
        val seId = m.groupValues[2]
        // Id desnudo para /sources: con el dato fixedUrl() la URL del endpoint
        // saldria con "https://..." dentro del path y el sitio responde 404 HTML.
        val epId = "${slug}-$seId-ep-${m.groupValues[3]}"

        // v8: el dedupe DM es POR EPISODIO. Al ser un campo de instancia, sin
        // limpiarlo el segundo loadLinks del mismo episodio devolvia true sin
        // emitir ningun enlace (fuentes que "desaparecen" al reabrir).
        emittedDmVids.clear()

        val registry = fetchRegistry()
        // v8: probar las entradas de la serie, priorizando la temporada exacta.
        // Si el registro cambio de shape/version, el episodio no queda muerto.
        val entries = registry.filter { it.slug == slug }
            .sortedBy { if (it.seasonId == seId) 0 else 1 }
        if (entries.isEmpty()) {
            android.util.Log.d("AnimeCube", "loadLinks $data registry sin entradas")
            return false
        }

        var sources = emptyList<AcSourceEntry>()
        var used: AcVersionEntry? = null
        for (entry in entries.take(3)) {
            sources = fetchSources(slug, epId, entry.primaryTabId, entry.seasonId, entry.version)
            if (sources.isNotEmpty()) { used = entry; break }
        }
        if (sources.isEmpty()) {
            android.util.Log.d("AnimeCube", "loadLinks $data fuentes vacias (registro ok, /sources sin data)")
            return false
        }
        android.util.Log.d(
            "AnimeCube",
            "loadLinks $data sources=${sources.map { it.platform }} " +
                "entry=${used?.primaryTabId}/${used?.seasonId}"
        )

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
                    android.util.Log.d("AnimeCube", "DM ${s.videoId} q=${s.quality} ok=$ok")
                    if (ok) found = true
                }
                "rumble" -> {
                    val ok = extractRumble(s.videoId, "Rumble", callback)
                    android.util.Log.d("AnimeCube", "Rumble ${s.videoId} ok=$ok")
                    if (ok) found = true
                }
                else -> android.util.Log.d("AnimeCube", "platform no soportada: ${s.platform}")
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

    /**
     * v5: Dailymotion delega en el extractor común. Aporta:
     *  - dedupe por video (evita la "misma fuente" repetida por mirror);
     *  - variantes h264 primero y AV1 descartada cuando hay h264
     *    (la AV1 dariapantalla negra/sin audio en devices sin decodificador);
     *  - subs es/en con el mismo orden de prioridad de siempre.
     */
    private suspend fun extractDailymotion(
        vid: String,
        referer: String,
        linkName: String,
        emittedSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return CE.extractDailymotion(
            "https://www.dailymotion.com/embed/video/$vid",
            referer,
            linkName,
            UA,
            emittedSubs,
            emittedDmVids,
            subtitleCallback,
            callback
        )
    }

    /** Dedupe por videoId entre sources del mismo episodio (o re-entradas). */
    private val emittedDmVids = mutableSetOf<String>()

    /**
     * v5: Rumble delega en el extractor común (doble intento embedJS + visita
     * previa al embed para el reto de Cloudflare), conservando el fallback HLS.
     */
    private suspend fun extractRumble(
        vid: String,
        linkName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = "https://rumble.com/embed/$vid/"
        val ok = CE.extractRumble(embed, "$mainUrl/", linkName, UA, callback)
        if (ok) return true
        // Fallback HLS que ya tenia el modulo (mismo endpoint embedJS).
        return try {
            val api = "https://rumble.com/embedJS/u3/?request=video&v=$vid" +
                "&embed=" + java.net.URLEncoder.encode(embed, "UTF-8")
            val json = app.get(api, referer = embed, headers = mapOf("User-Agent" to UA), timeout = 20L)
                .text.replace("\\/", "/")
            Regex(""""hls":\{"url":"(https://rumble\.com/hls-vod/[^"]+)"""").find(json)?.let { m ->
                try {
                    generateM3u8(linkName, m.groupValues[1], embed).forEach(callback)
                } catch (_: Exception) {}
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
