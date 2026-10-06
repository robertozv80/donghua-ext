package com.donghuaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import android.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList

// v22.2: extractores propios para servidores que CloudStream no trae integrados
// (vgembed y bysekoze no existen en la lista de extractores del APK).
// Ambos sitios son variantes de la familia StreamWish/JWPlayer, así que el
// extractor genérico de StreamWish (unpack + jwplayer setup) funciona para ellos.
private val mdVgExtractor = object : StreamWishExtractor() {
    override var name = "Vg"
    override var mainUrl = "https://vgembed.com"
}

private val mdFmoonExtractor = object : StreamWishExtractor() {
    override var name = "Fmoon"
    override var mainUrl = "https://bysekoze.com"
}

// v24.14: extractor para el servidor "Fm" (tab fmoon) de MundoDonghua.
// bysekoze.com dejó de servir un player clásico: ahora es una SPA ("Byse Frontend")
// que consume una API propia con gate de Proof-of-Work y playback cifrado con AES-GCM:
//   1) POST /api/videos/{code}/embed/captcha -> {pow_nonce, pow_difficulty, pow_token}
//   2) PoW: hallar N tal que el hash custom de "nonce:N" tenga >= dificultad bits en
//      cero a la izquierda (dificultad observada: 16 bits; solución típica ~15-30k)
//   3) POST /api/videos/{code}/embed/captcha/verify {pow_token, solution} -> {token}
//   4) POST /api/videos/{code}/embed/playback {fingerprint} + X-Captcha-Token
//      -> {playback:{algorithm, iv, payload, key_parts[], version}}
//   5) key = b64url(key_parts[version-1]) + b64url(key_parts[30-version]) y
//      AES-256-GCM(iv, payload) -> {sources:[{url, mime_type, label, height}]}
// La API valida el dominio del embed (Origin/Referer/X-Embed-* de mundodonghua.com);
// el fingerprint solo necesita existir como objeto (no valida attestation).
private object mdFmoonByseExtractor {
    private const val API_BASE = "https://bysekoze.com"
    private const val EMBED_ORIGIN = "https://www.mundodonghua.com"
    private const val EMBED_REFERER = "https://www.mundodonghua.com/"
    private const val PLAYER_REFERER = "https://bysekoze.com/"

    private fun b64UrlDecode(s: String): ByteArray {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    // ===== port del hash custom del PoW (bundle JS de bysekoze) =====
    private fun rotl32(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun mixRound(t: IntArray) {
        t[0] += t[1]; t[3] = rotl32(t[3] xor t[0], 16)
        t[2] += t[3]; t[1] = rotl32(t[1] xor t[2], 12)
        t[0] += t[1]; t[3] = rotl32(t[3] xor t[0], 8)
        t[2] += t[3]; t[1] = rotl32(t[1] xor t[2], 7)
    }

    private fun powHash(data: ByteArray): IntArray {
        val e = intArrayOf(1779033703, 3144134277L.toInt(), 1013904242, 2773480762L.toInt())
        for (b in data) {
            e[0] += b.toInt() and 0xFF
            e[0] = rotl32(e[0], 7)
            mixRound(e)
        }
        repeat(8) { mixRound(e) }
        val size = 512
        val mask = size - 1
        // 2654435761 y 2246822519 no caben en Int: toInt() da el mismo patrón de bits
        // que el uint32 del JS, y la multiplicación de Int ya envuelve mod 2^32.
        val lr = 2654435761L.toInt()
        val hr = 2246822519L.toInt()
        val table = IntArray(size)
        for (i in 0 until size) {
            mixRound(e)
            table[i] = e[0] xor e[2]
        }
        repeat(2) {
            for (s in 0 until size) {
                var c = table[s] + table[table[s] and mask]
                c = rotl32(c, 13)
                c = c xor (table[(s + 1) and mask] * lr)
                table[s] = c
                e[0] = e[0] xor c
                mixRound(e)
            }
        }
        val out = IntArray(8)
        val group = size / 8
        for (i in 0 until 8) {
            mixRound(e)
            var acc = e[0]
            val base = i * group
            for (j in 0 until group) {
                val d = table[base + j]
                acc += d
                acc = rotl32(acc, 5)
                acc = acc xor (d * hr)
            }
            out[i] = acc xor e[2]
        }
        return out
    }

    private fun leadingZeroBits(words: IntArray): Int {
        var total = 0
        for (w in words) {
            if (w == 0) { total += 32; continue }
            total += Integer.numberOfLeadingZeros(w)
            break
        }
        return total
    }

    private fun solvePow(nonce: String, difficulty: Int): String? {
        if (difficulty <= 0) return "0"
        val prefix = "$nonce:".toByteArray()
        val startedAt = System.currentTimeMillis()
        var counter = 0L
        while (true) {
            val input = prefix + counter.toString().toByteArray()
            if (leadingZeroBits(powHash(input)) >= difficulty) return counter.toString()
            counter++
            if (counter % 2048L == 0L && System.currentTimeMillis() - startedAt > 45_000L) return null
        }
    }

    // DTOs del playback cifrado (mismo estilo que SsrInit en AnimeGratis)
    private data class BysePlaybackEnc(
        val algorithm: String? = null,
        val iv: String? = null,
        val payload: String? = null,
        val key_parts: List<String>? = null,
        val version: Any? = null,
    ) {
        val versionNumber: Int? get() = when (val v = version) {
            is Number -> v.toInt()
            is String -> Regex("\\d+").find(v)?.value?.toIntOrNull()
            else -> null
        }
    }

    // v24.15: la API publica GET /api/videos/{code} devuelve el MISMO playback
    // cifrado (con key_parts y version) SIN captcha ni PoW, por lo que el camino
    // normal ya no necesita resolver el reto (la dificultad subia: 16 bits en
    // septiembre, 17-19 en la auditoria de octubre). El flujo con PoW queda como
    // respaldo por si el sitio vuelve a cerrar el endpoint publico.
    private data class ByseTrack(
        val lang: String? = null,
        val language: String? = null,
        val label: String? = null,
        val name: String? = null,
        val url: String? = null,
        val src: String? = null,
    ) {
        val fileUrl: String? get() = (url ?: src)?.trim()?.takeIf { it.isNotBlank() }
    }

    private data class ByseSource(
        val url: String? = null,
        val mime_type: String? = null,
        val label: String? = null,
        val height: Int? = null,
    )

    private data class BysePlaybackDec(val sources: List<ByseSource>? = null)

    private data class ByseVideo(
        val playback: BysePlaybackEnc? = null,
        val tracks: List<ByseTrack>? = null,
    )

    private fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Origin" to EMBED_ORIGIN,
        "Referer" to EMBED_REFERER,
        "X-Embed-Origin" to EMBED_ORIGIN,
        "X-Embed-Referer" to EMBED_REFERER,
        "X-Embed-Parent" to EMBED_REFERER,
    )

    // Camino 1: API publica (1 sola peticion).
    private suspend fun fetchPublicVideo(code: String): ByseVideo? = try {
        val txt = app.get(
            "$API_BASE/api/videos/$code",
            headers = apiHeaders(), timeout = 15L
        ).text
        if (txt.contains("\"playback\"")) parseJson<ByseVideo>(txt) else null
    } catch (_: Exception) { null }

    // Camino 2 (respaldo): captcha + PoW + playback, como en v24.14.
    private suspend fun fetchViaCaptcha(code: String): ByseVideo? = try {
        val headers = apiHeaders() + mapOf("Content-Type" to "application/json")
        val captchaResp = app.post(
            "$API_BASE/api/videos/$code/embed/captcha",
            json = mapOf<String, Any>(), headers = headers, timeout = 20L
        ).text
        val nonce = Regex("\"pow_nonce\"\\s*:\\s*\"([^\"]+)\"").find(captchaResp)?.groupValues?.get(1) ?: return null
        val difficulty = Regex("\"pow_difficulty\"\\s*:\\s*(\\d+)").find(captchaResp)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val powToken = Regex("\"pow_token\"\\s*:\\s*\"([^\"]+)\"").find(captchaResp)?.groupValues?.get(1) ?: return null
        val solution = solvePow(nonce, difficulty) ?: return null
        val verifyResp = app.post(
            "$API_BASE/api/videos/$code/embed/captcha/verify",
            json = mapOf<String, Any>("pow_token" to powToken, "solution" to solution),
            headers = headers, timeout = 20L
        ).text
        val captchaToken = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(verifyResp)?.groupValues?.get(1) ?: return null
        val fingerprint = mapOf<String, Any>(
            "token" to captchaToken,
            "viewer_id" to UUID.randomUUID().toString(),
            "device_id" to UUID.randomUUID().toString(),
            "confidence" to 0.9,
        )
        val pbResp = app.post(
            "$API_BASE/api/videos/$code/embed/playback",
            json = mapOf<String, Any>("fingerprint" to fingerprint),
            headers = headers + mapOf("X-Captcha-Token" to captchaToken),
            timeout = 30L
        ).text
        parseJson<ByseVideo>(pbResp)
    } catch (_: Exception) { null }

    /**
     * v24.15: descifrado AES-256-GCM del playback. El payload trae la etiqueta
     * GCM pegada al final (16 ultimos bytes); se separa y se pasa explicita a
     * doFinal, que en JCE es exactamente lo mismo que enviarlo entero pero deja
     * el paso a la vista y evita depender del reparto interno del provider.
     */
    private fun decryptPayload(enc: BysePlaybackEnc): BysePlaybackDec? {
        val keyParts = enc.key_parts ?: return null
        val version = enc.versionNumber ?: return null
        // port de ws() del bundle: indices [version, 31-version] (base 1) y, si
        // alguno no existe, TODAS las partes concatenadas
        val selected = ArrayList<String>()
        for (idx in intArrayOf(version, 31 - version)) {
            val part = if (idx in 1..keyParts.size) keyParts[idx - 1] else null
            if (!part.isNullOrBlank()) selected.add(part)
        }
        if (selected.isEmpty()) selected.addAll(keyParts)
        val key = selected.map { b64UrlDecode(it) }.reduce { acc, bytes -> acc + bytes }
        if (key.size !in intArrayOf(16, 24, 32)) return null
        val iv = b64UrlDecode(enc.iv ?: return null)
        val payload = b64UrlDecode(enc.payload ?: return null)
        if (payload.size <= 16) return null
        val tag = payload.copyOfRange(payload.size - 16, payload.size)
        val body = payload.copyOfRange(0, payload.size - 16)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(body + tag)
        } catch (_: Exception) { null }
        return plain?.let { parseJson<BysePlaybackDec>(String(it, Charsets.UTF_8)) }
    }

    /** Quita cabecera WEBVTT, NOTE y numeracion de un segmento para poder concatenarlo. */
    private fun mergeVttSegment(raw: String): String {
        var v = raw.replace("\r", "")
        v = Regex("^WEBVTT[^\n]*\n").replace(v, "")
        v = Regex("^NOTE[^\n]*\n", RegexOption.MULTILINE).replace(v, "")
        v = Regex("^\\d+[ \\t]*\n", RegexOption.MULTILINE).replace(v, "")
        return v.trim() + "\n"
    }

    /**
     * v24.15: CloudStream solo puede reproducir un subtitulo como FICHERO UNICO
     * (lo envuelve en un SingleSampleMediaSource), asi que una pista de
     * subtitulos HLS segmentada hay que fusionarla. Se descarga el playlist,
     * se concatenan los .vtt y se entrega como data-URI (DefaultDataSource
     * enruta el esquema data: a DataSchemeDataSource).
     */
    private suspend fun emitMergedSubtitle(
        playlistUrl: String, lang: String, subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val playlist = try {
            app.get(playlistUrl, referer = PLAYER_REFERER, timeout = 15L).text
        } catch (_: Exception) { return }
        val segments = Regex("""^(?!#)([^\r\n]+)$""", RegexOption.MULTILINE)
            .findAll(playlist).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }
            .take(80).toList()
        if (segments.isEmpty()) return
        val sb = StringBuilder("WEBVTT\n\n")
        var merged = 0
        for (seg in segments) {
            val abs = try {
                java.net.URI(playlistUrl).resolve(seg).toString()
            } catch (_: Exception) { continue }
            val body = try {
                app.get(abs, referer = PLAYER_REFERER, timeout = 10L).text
            } catch (_: Exception) { continue }
            if (body.isBlank()) continue
            sb.append(mergeVttSegment(body))
            merged++
        }
        if (merged == 0) return
        val dataUri = "data:text/vtt;base64," +
            Base64.encodeToString(sb.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        subtitleCallback(SubtitleFile(lang, dataUri))
    }

    private suspend fun emitSubtitles(
        tracks: List<ByseTrack>?, masterUrl: String?, subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val emitted = HashSet<String>()
        for (t in tracks.orEmpty()) {
            val u = t.fileUrl ?: continue
            if (!emitted.add(u)) continue
            val raw = (t.lang ?: t.language ?: t.label ?: t.name ?: "es")
            val lang = if (raw.contains("es", ignoreCase = true)) "Spanish" else raw
            subtitleCallback(SubtitleFile(lang, u))
        }
        val m = masterUrl ?: return
        val master = try {
            app.get(m, referer = PLAYER_REFERER, timeout = 12L).text
        } catch (_: Exception) { return }
        if (!master.contains("#EXT-X-MEDIA")) return
        for (line in Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*""").findAll(master).map { it.value }) {
            val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1) ?: continue
            val name = Regex("""NAME="([^"]*)"""").find(line)?.groupValues?.get(1) ?: ""
            val lang = if (name.contains("es", ignoreCase = true)) "Spanish" else name.ifBlank { "Spanish" }
            val abs = try { java.net.URI(m).resolve(uri).toString() } catch (_: Exception) { continue }
            if (!emitted.add(abs)) continue
            emitMergedSubtitle(abs, lang, subtitleCallback)
        }
    }

    suspend fun getUrl(embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val code = Regex("bysekoze\\.com/[ev]/([a-zA-Z0-9]+)").find(embedUrl)?.groupValues?.get(1) ?: return
        val video = fetchPublicVideo(code) ?: fetchViaCaptcha(code) ?: return
        val enc = video.playback ?: return
        val dec = decryptPayload(enc) ?: return

        var masterForSubs: String? = null
        for (src in dec.sources.orEmpty()) {
            val url = src.url?.trim() ?: continue
            if (url.isBlank()) continue
            val height = src.height ?: 0
            val isHls = src.mime_type?.contains("mpegurl", ignoreCase = true) == true || url.contains(".m3u8")
            if (isHls) {
                // ojo: la URL del master lleva token (?t=...&s=...), hay que cortar la query
                if (masterForSubs == null && url.substringBefore("?").endsWith("master.m3u8")) masterForSubs = url
                try { generateM3u8("Fm", url, PLAYER_REFERER).forEach(callback) } catch (_: Exception) {}
            } else {
                callback(newExtractorLink(source = "Fm", name = "Fm", url = url) {
                    this.referer = PLAYER_REFERER
                    this.quality = when {
                        height >= 1080 -> Qualities.P1080.value
                        height >= 720 -> Qualities.P720.value
                        height >= 480 -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                })
            }
        }
        // v24.15: pistas de subtitulos declaradas en la API o en el master HLS
        if (video.tracks.isNullOrEmpty() && masterForSubs == null) return
        emitSubtitles(video.tracks, masterForSubs, subtitleCallback)
    }
}

/**
 * v22.2: resolver un embed de forma SERIE (como en v21, que reproducía bien).
 * La carga en paralelo con coroutineScope+async provocó una regresión (ningún
 * video reproducía), así que cada extractor se ejecuta inmediatamente al
 * detectarlo, con dedup por URL.
 */
private suspend fun mdHandleEmbed(
    embedUrl: String,
    seen: MutableSet<String>,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    if (!seen.add(embedUrl)) return
    try {
        when {
            embedUrl.contains("vgembed.com") -> mdVgExtractor.getUrl(embedUrl, referer, subtitleCallback, callback)
            // v24.14: bysekoze.com es una SPA con API propia (PoW + AES-GCM): extractor
            // dedicado. Solo filemoon.to sigue perteneciendo a la familia StreamWish.
            embedUrl.contains("bysekoze.com") ->
                mdFmoonByseExtractor.getUrl(embedUrl, subtitleCallback, callback)
            embedUrl.contains("filemoon") ->
                mdFmoonExtractor.getUrl(embedUrl, referer, subtitleCallback, callback)
            else -> loadExtractor(embedUrl, referer, subtitleCallback, callback)
        }
    } catch (_: Exception) {}
}

// FIX v22.1: embeds conocidos para el fallback de HTML crudo (por si cambia el empaquetado)
private val MD_RAW_EMBED_REGEXES = listOf(
    Regex("https?://[^\\s\"'<>]*vidhidepro\\.com/[ve]/[a-zA-Z0-9]+"),
    Regex("https?://[^\\s\"'<>]*vgembed\\.com/e/[a-zA-Z0-9]+"),
    Regex("https?://[^\\s\"'<>]*embedwish\\.com/e/[a-zA-Z0-9]+"),
    Regex("https?://[^\\s\"'<>]*streamwish\\.to/e/[a-zA-Z0-9]+"),
    Regex("https?://[^\\s\"'<>]*bysekoze\\.com/e/[a-zA-Z0-9]+"),
    Regex("https?://[^\\s\"'<>]*filemoon\\.to/e/[a-zA-Z0-9]+"),
    Regex("https?://voe\\.sx/e/[a-zA-Z0-9]+"),
)

class MundoDonghuaProvider : MainAPI() {

    override var mainUrl = "https://www.mundodonghua.com"
    override var name = "MundoDonghua"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.OVA,
        TvType.AnimeMovie,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Nuevos Episodios",
        "$mainUrl/lista-donghuas" to "Donghuas",
        "$mainUrl/lista-donghuas-emision" to "En Emisión",
        "$mainUrl/lista-donghuas-finalizados" to "Finalizados",
    )

    /**
     * v24.15: prioridad de emision por servidor (auditoria en vivo, loadLinks).
     * Mas alto = se emite antes. Los servidores muertos se conservan (pueden
     * revivir y no todas las series usan los mismos), solo van al final.
     */
    private fun mdServerPriority(embedUrl: String): Int {
        val u = embedUrl.lowercase()
        return when {
            u.contains("bysekoze.com") -> 4
            u.contains("embedwish.com") || u.contains("streamwish.to") -> 3
            u.contains("vidhidepro.com") -> 2
            else -> 0
        }
    }

    private fun resolveUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            url.isNotBlank() -> "$mainUrl/$url"
            else -> ""
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == "$mainUrl/"
        val url = if (isHomePage) {
            request.data
        } else {
            if (page > 1) "${request.data}/$page" else request.data
        }
        val doc = app.get(url, timeout = 120).document

        val home = if (isHomePage) {
            // Página principal: sección "Nuevos Episodios"
            // FIX: Buscar imágenes también en data-src y usar resolveUrl para URLs relativas
            val episodeCards = doc.select("div#nuevos-episodios-grid div.md-card")
            if (episodeCards.isNotEmpty()) {
                episodeCards.mapNotNull { card ->
                    parseEpisodeCard(card)
                }
            } else {
                // Fallback: buscar todas las md-card que link a /ver/
                doc.select("div.md-card").mapNotNull { card ->
                    val href = card.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    if (href.contains("/ver/")) {
                        parseEpisodeCard(card)
                    } else {
                        null
                    }
                }
            }
        } else {
            // Listas de donghuas: link a /donghua/
            doc.select("div.md-card").mapNotNull { card ->
                val title = card.selectFirst("h3.md-card-title")?.text() ?: return@mapNotNull null
                val poster = card.selectFirst("div.md-card-img img")?.let { getBestImgSrc(it) }
                val href = card.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                newAnimeSearchResponse(title, resolveUrl(href)) {
                    this.posterUrl = resolveUrl(poster ?: "")
                    addDubStatus(dubstat)
                }
            }
        }

        val hasNext = if (isHomePage) {
            doc.select("#episodios-load-more, a.md-load-more").isNotEmpty()
        } else {
            doc.select("nav.md-pagination a, ul.pagination a").isNotEmpty()
        }

        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    /**
     * Obtiene la mejor URL de imagen de un elemento img.
     * Prioriza data-src (lazy loading) sobre src, y maneja URLs relativas.
     */
    private fun getBestImgSrc(imgEl: org.jsoup.nodes.Element): String {
        val dataSrc = imgEl.attr("data-src")?.trim()
        if (!dataSrc.isNullOrEmpty() && dataSrc.startsWith("http")) return dataSrc
        if (!dataSrc.isNullOrEmpty() && dataSrc.startsWith("/")) return dataSrc

        val src = imgEl.attr("src")?.trim()
        if (!src.isNullOrEmpty() && !src.contains("data:image")) return src

        // Último intento: noscript fallback
        val noscriptSrc = imgEl.parent()?.selectFirst("noscript img")?.attr("src")?.trim()
        if (!noscriptSrc.isNullOrEmpty()) return noscriptSrc

        return src ?: ""
    }

    /**
     * Parsea una tarjeta de episodio del homepage
     * Las tarjetas link a /ver/{slug}/{ep_num} - convertir a /donghua/{slug}
     */
    private fun parseEpisodeCard(card: org.jsoup.nodes.Element): SearchResponse? {
        val title = card.selectFirst("h3.md-card-title")?.text() ?: return null
        val poster = card.selectFirst("div.md-card-img img")?.let { getBestImgSrc(it) }
        val href = card.selectFirst("a")?.attr("href") ?: return null

        // Saltar episodios limitados/VIP
        val isLimited = card.hasClass("limited") || card.selectFirst("i.fa-lock") != null
            || card.selectFirst(".md-card-meta span")?.text()?.contains("Limitado") == true

        // Convertir URL de episodio a URL de donghua
        // /ver/{slug}/{ep_num} → /donghua/{slug}
        val donghuaUrl = convertEpisodeToDonghuaUrl(href)

        // Extraer número de episodio del título o URL
        val epNum = Regex("Episodio\\s*(\\d+)").find(title)?.destructured?.component1()?.toIntOrNull()
            ?: Regex("/(\\d+)/?$").find(href)?.destructured?.component1()?.toIntOrNull()

        // Limpiar título (quitar "Episodio X")
        val cleanTitle = title.replace(Regex("\\s*Episodio\\s*\\d+"), "").trim()
        val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed

        return newAnimeSearchResponse(cleanTitle, donghuaUrl) {
            this.posterUrl = resolveUrl(poster ?: "")
            addDubStatus(dubstat, epNum)
        }
    }

    /**
     * Convierte URL de episodio a URL de donghua
     * /ver/{slug}/{ep_num} → /donghua/{slug}
     * /ver/{slug}/{ep_num}/{token} → /donghua/{slug} (episodios limitados)
     */
    private fun convertEpisodeToDonghuaUrl(href: String): String {
        val fullUrl = resolveUrl(href)
        // Patrón: /ver/{slug}/{ep_num} o /ver/{slug}/{ep_num}/{token}
        val regex = Regex("/ver/([^/]+)")
        val match = regex.find(fullUrl)
        return if (match != null) {
            val slug = match.destructured.component1()
            "$mainUrl/donghua/$slug"
        } else {
            fullUrl
        }
    }

    /**
     * FIX CRÍTICO: La búsqueda usa /busquedas/{query} (path segment)
     * NO /busquedas/?donghua={query} (query parameter) que devuelve TODO sin filtrar
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/busquedas/${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(searchUrl, timeout = 120).document

        return doc.select("div.md-card").mapNotNull { card ->
            val title = card.selectFirst("h5.md-card-title")?.text()
                ?: card.selectFirst("h3.md-card-title")?.text()
                ?: card.selectFirst(".md-card-title")?.text()
                ?: return@mapNotNull null
            val href = card.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = card.selectFirst("div.md-card-img img")?.let { getBestImgSrc(it) }
            val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
            newAnimeSearchResponse(title, resolveUrl(href), TvType.Anime) {
                this.posterUrl = resolveUrl(image ?: "")
                addDubStatus(dubstat)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // Si la URL es de un episodio, convertir a página de donghua
        val donghuaUrl = if (url.contains("/ver/")) {
            convertEpisodeToDonghuaUrl(url)
        } else {
            url
        }

        val doc = app.get(donghuaUrl, timeout = 120).document
        // FIX: Usar getBestImgSrc para manejar lazy loading de imágenes
        val poster = doc.selectFirst("div.md-detail-poster > img")?.let { getBestImgSrc(it) }
            ?: doc.selectFirst("div.md-detail-banner-bg > img")?.let { getBestImgSrc(it) }
            ?: doc.selectFirst("head meta[property=og:image]")?.attr("content")
            ?: ""
        val resolvedPoster = resolveUrl(poster)
        val title = doc.selectFirst("h1.md-detail-title")?.text() ?: ""
        val description = doc.selectFirst("p.md-detail-synopsis")?.text() ?: ""
        val genres = doc.select("a.md-genre-tag").map { it.text() }

        val status = when (doc.selectFirst("span.md-emision-badge")?.text()?.trim()) {
            "En Emisión" -> ShowStatus.Ongoing
            "Finalizada" -> ShowStatus.Completed
            else -> null
        }

        val episodes = ArrayList<Episode>()
        // Todos los episodios están renderizados en la página (sin paginación)
        doc.select("li.md-episode-item").map { epItem ->
            val link = epItem.selectFirst("a.md-ep-link")?.attr("href") ?: return@map
            val epNum = epItem.attr("data-ep")?.toIntOrNull()
                ?: Regex("/(\\d+)/?$").find(link)?.destructured?.component1()?.toIntOrNull()
            episodes.add(
                newEpisode(resolveUrl(link)) {
                    this.episode = epNum
                }
            )
        }

        val typeBadge = doc.selectFirst("span.md-card-badge")?.text()?.lowercase()
            ?: doc.selectFirst("span.md-badge-static")?.text()?.lowercase()
            ?: ""
        val tvType = when {
            typeBadge.contains("película") || typeBadge.contains("pelicula") -> TvType.AnimeMovie
            typeBadge.contains("ova") || typeBadge.contains("especial") -> TvType.OVA
            else -> TvType.Anime
        }

        // ===== v22 Recomendaciones =====
        // (implementadas en fetchMundoRecommendations más abajo)
        val recommendations = fetchMundoRecommendations(donghuaUrl, title)

        // Para películas sin episodios, devolver respuesta de película
        if (episodes.isEmpty() && tvType == TvType.AnimeMovie) {
            return newMovieLoadResponse(title, donghuaUrl, TvType.AnimeMovie, donghuaUrl) {
                posterUrl = resolvedPoster
                plot = description
                tags = genres
                if (recommendations.isNotEmpty()) this.recommendations = recommendations
            }
        }

        // Si no hay episodios en la página de detalle, intentar construir URLs
        if (episodes.isEmpty()) {
            val slug = donghuaUrl.substringAfter("/donghua/")
            // Buscar el número total de episodios en la información de la página
            val epCountText = doc.select("span.md-detail-meta-stat, p.md-info-item")
                .map { it.text() }.find { it.contains("episodio") || it.contains("Episodios") }
            val totalEps = Regex("(\\d+)").find(epCountText ?: "")?.value?.toIntOrNull() ?: 0

            for (ep in 1..totalEps) {
                episodes.add(
                    newEpisode("$mainUrl/ver/$slug/$ep") {
                        this.episode = ep
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, donghuaUrl, tvType) {
            posterUrl = resolvedPoster
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
            showStatus = status
            plot = description
            tags = genres
            if (recommendations.isNotEmpty()) this.recommendations = recommendations
        }
    }

    /**
     * v22.2: recomendaciones con la NOTA del usuario:
     * 1) primero las otras temporadas de la misma serie (mismo slug base,
     *    p.ej. "jade-dynasty-4" -> base "jade-dynasty"), en orden numérico;
     * 2) el resto: títulos del directorio general (aleatorio), excluyendo la
     *    serie actual y las temporadas ya agregadas. Máximo 10.
     */
    private suspend fun fetchMundoRecommendations(donghuaUrl: String, seriesTitle: String): List<SearchResponse> {
        return try {
            val all = ArrayList<SearchResponse>()

            // 1) Otras temporadas: v22.5 FIX — usar la BÚSQUEDA del sitio con el término
            // base (sin número de temporada). El listado general (lista-donghuas) es
            // paginado y no contiene la mayoría de temporadas (ej. Tales of Demons and
            // Gods 2..9 solo aparecen vía /busquedas?donghua=tales+of+demons+and+gods).
            // Término base del slug: tales-of-demons-and-gods-10 -> "tales-of-demons-and-gods"
            val baseLower = Regex("\\d+$").replace(donghuaUrl.substringAfterLast("/").substringBeforeLast("-"), "").trimEnd('-')
            if (baseLower.isNotBlank()) {
                val seenSeasons = HashSet<String>()
                // Término de búsqueda: desde el título propio sin el número final
                // ("Tales of Demons and Gods Season10" -> "tales of demons and gods")
                val searchTerm = Regex("\\s*(temporada|season|s)\\s*\\d+$", RegexOption.IGNORE_CASE)
                    .replace(seriesTitle, "").trim()
                    .ifBlank { Regex("\\s+\\d+$").replace(seriesTitle, "").trim() }
                val queries = LinkedHashSet<String>()
                if (searchTerm.isNotBlank()) queries.add(searchTerm)
                queries.add(baseLower.replace("-", " "))
                queryLoop@ for (q in queries) {
                    try {
                        val searchDoc = app.get("$mainUrl/busquedas?donghua=" + java.net.URLEncoder.encode(q, "UTF-8"), timeout = 60).document
                        val found = ArrayList<Triple<String, String, String>>() // slug, title, poster
                        searchDoc.select("a[href*='/donghua/']").forEach { a ->
                            val href = a.attr("href")
                            val slug = href.substringAfterLast("/")
                            if (!slug.startsWith(baseLower) || slug in seenSeasons) return@forEach
                            if ("$mainUrl/donghua/$slug" == donghuaUrl) { seenSeasons.add(slug); return@forEach }
                            val t = a.selectFirst("h5.md-card-title")?.text()?.trim()
                                ?: a.selectFirst("h3.md-card-title")?.text()?.trim()
                                ?: a.attr("alt").ifBlank { slug }
                            val poster = a.selectFirst("img")?.let { getBestImgSrc(it) }
                            seenSeasons.add(slug)
                            found.add(Triple(slug, t, poster ?: ""))
                        }
                        if (found.isNotEmpty()) {
                            found.sortBy { rec ->
                                Regex("(\\d+)$").find(rec.first)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            }
                            found.forEach { (slug, t, poster) ->
                                all.add(newAnimeSearchResponse(t, "$mainUrl/donghua/$slug") {
                                    this.posterUrl = resolveUrl(poster)
                                })
                            }
                            break@queryLoop
                        }
                    } catch (_: Exception) {}
                }
            }

            // 2) Similares aleatorios del directorio general
            val seen = all.mapTo(HashSet()) { it.url }
            seen.add(donghuaUrl)
            val pool = ArrayList<SearchResponse>()
            val listing = app.get("$mainUrl/lista-donghuas", timeout = 120).document
            listing.select("div.md-card").forEach { card ->
                if (pool.size >= 40) return@forEach
                val a = card.selectFirst("a[href*='/donghua/']") ?: return@forEach
                val href = a.attr("href")
                val fullUrl = resolveUrl(href)
                if (fullUrl in seen) return@forEach
                val t = card.selectFirst("h3.md-card-title")?.text()?.trim()
                    ?: card.selectFirst("h5.md-card-title")?.text()?.trim()
                    ?: return@forEach
                val poster = card.selectFirst("div.md-card-img img")?.let { getBestImgSrc(it) }
                seen.add(fullUrl)
                pool.add(newAnimeSearchResponse(t, fullUrl) {
                    this.posterUrl = resolveUrl(poster ?: "")
                })
            }
            pool.shuffle()
            all.addAll(pool)
            all.take(16)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 120).document
        val rawHtml = doc.html()
        val datafix = data.replace("ñ", "%C3%B1")
        // v22.1: recolectar embeds (dedup) y cargarlos AL FINAL en paralelo; antes
        // cada extractor corría en serie y el total tardaba mucho.
        val seenEmbeds = LinkedHashSet<String>()
        // v24.15: los embeds se RECOLECTAN primero y se procesan al final ordenados
        // por prioridad de servidor (auditoria en vivo, ver mdServerPriority), y
        // los enlaces directos de los servidores caidos (Asura/Tamamo, que
        // dependen de www.mdnemonicplayer.xyz) se emiten en el ultimo lugar.
        val embedsFound = LinkedHashSet<String>()
        val deadLinks = ArrayList<ExtractorLink>()

        val reqHEAD = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to datafix,
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "TE" to "trailers"
        )

        // Detectar qué pestañas de servidores existen
        val serverTabs = doc.select("button.md-server-tab[data-target]").map {
            it.attr("data-target") to it.text()?.trim()
        }
        val hasTamamo = serverTabs.any { it.first == "tamamo" }
        val hasAsura = serverTabs.any { it.first == "asura" }
        val hasFmoon = serverTabs.any { it.first == "fmoon" }
        val hasVhide = serverTabs.any { it.first == "vhide" }
        val hasKaga = serverTabs.any { it.first == "kaga" }
        val hasSwish = serverTabs.any { it.first == "swish" }

        // Extraer videos desde el JavaScript packed (Dean Edwards packing)
        // FIX v22.1: TODOS los servers van como llamadas eval() separadas dentro
        // del mismo <script> (vhide, kaga/Vg, swish/Sw, ...). Antes un regex codicioso
        // las unía en un solo bloque y el unpacker decodificaba todo con la tabla de
        // símbolos del último eval: solo salía un server y de forma poco fiable.
        // Ahora se separa por el terminador ",0,{}))" y cada eval se decodifica
        // con su propia tabla de símbolos.
        for (script in doc.select("script")) {
            val scriptData = script.data()
            if (scriptData.contains("eval(function(p,a,c,k,e")) {
                // FIX v22.3: cada eval() es una LÍNEA completa del script. El regex
                // interno de getAndUnpack es eval\(function\(p,a,c,k,e,.*\)\) y REQUIERE
                // el terminador "))"; el split anterior por ",0,{}))" lo eliminaba y
                // getAndUnpack devolvía el string aún empaquetado -> 0 enlaces.
                // Ahora se captura cada línea completa conservando su terminador.
                val packedList = Regex("eval\\(function\\(p,a,c,k,e,[^\\r\\n]+")
                    .findAll(scriptData).map { it.value }.toList()
                for (packed in packedList) {
                    try {
                        val unpack = getAndUnpack(packed)
                        if (unpack.isNullOrEmpty() || unpack.contains("eval(function(p,a,c,k,e")) continue

                        // ===== Asura (HLS m3u8) - Servidor principal sin anuncios =====
                        if (unpack.contains("asura_player") || unpack.contains("redirector")) {
                            // Extraer slug del redirector
                            val redirectorRegex = Regex("redirector\\.php\\?slug=([A-Za-z0-9+/=]+)")
                            val asuraSlug = redirectorRegex.find(unpack)?.destructured?.component1()
                            if (!asuraSlug.isNullOrEmpty()) {
                                // v22.5 FIX: el sitio movió el reproductor de mdplayer.xyz a
                                // mdnemonicplayer.xyz (verificado en vivo: el dominio nuevo
                                // devuelve un M3U8 válido; el viejo responde basura de 25 bytes).
                                try {
                                    val m3u8Url = "https://www.mdnemonicplayer.xyz/nemonicplayer/redirector.php?slug=$asuraSlug"
                                    deadLinks.addAll(generateM3u8("Asura", m3u8Url, datafix))
                                } catch (_: Exception) {
                                    // Fallback: intentar generar M3U8 sin verificar
                                    try {
                                        val m3u8Url = "https://www.mdnemonicplayer.xyz/nemonicplayer/redirector.php?slug=$asuraSlug"
                                        deadLinks.addAll(generateM3u8("Asura", m3u8Url, datafix))
                                    } catch (_: Exception) {}
                                }
                            }

                            // También buscar URL m3u8 directa en el unpack
                            val fileRegex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                            val fileUrl = fileRegex.find(unpack)?.destructured?.component1()
                            if (!fileUrl.isNullOrEmpty()) {
                                try {
                                    deadLinks.addAll(generateM3u8("Asura", fileUrl, datafix))
                                } catch (_: Exception) {}
                            }
                        }

                        // ===== Tamamo / Protea (Dailymotion vía API) =====
                        if (unpack.contains("protea_tab") || unpack.contains("tamamo") || unpack.contains("api_donghua")) {
                            // Extraer el slug para la API
                            val slugPatterns = listOf(
                                Regex("""slug["']?\s*:\s*["']([A-Za-z0-9+/=]+)["']"""),
                                Regex("""data\s*:\s*\{\s*["']slug["']\s*:\s*["']([A-Za-z0-9+/=]+)["']"""),
                            )
                            for (pattern in slugPatterns) {
                                val slug = pattern.find(unpack)?.destructured?.component1()
                                if (!slug.isNullOrEmpty()) {
                                    try {
                                        // Paso 1: Llamar a la API
                                        val apiResp = app.get("$mainUrl/api_donghua.php?slug=$slug", headers = reqHEAD, timeout = 15).text
                                        // Paso 2: Parsear respuesta JSON: [{"url":"BASE64_KEY"}]
                                        val keyRegex = Regex("""\"url\"\s*:\s*\"([^\"]+)\"""")
                                        val apiKey = keyRegex.find(apiResp)?.destructured?.component1()
                                        if (!apiKey.isNullOrEmpty()) {
                                            // Paso 3: Obtener página del reproductor Dailymotion
                                            val playerUrl = "https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=$apiKey"
                                            val playerResp = app.get(playerUrl, headers = reqHEAD, timeout = 15).text
                                            // Paso 4: Extraer ID del video Dailymotion
                                            val dmIdPatterns = listOf(
                                                Regex("""video\s*:\s*["']([A-Za-z0-9]+)["']"""),
                                                Regex("""DM\.player\([^,]+,\s*\{[^}]*video\s*:\s*["']([A-Za-z0-9]+)["']"""),
                                                Regex("""video["']\s*:\s*["']([A-Za-z0-9]+)["']"""),
                                            )
                                            for (dmPattern in dmIdPatterns) {
                                                val vidID = dmPattern.find(playerResp)?.destructured?.component1()
                                                if (!vidID.isNullOrEmpty()) {
                                                    // Intentar extracción directa via API
                                                    try {
                                                        val apiUrl = "https://www.dailymotion.com/player/metadata/video/$vidID"
                                                        val jsonText = app.get(apiUrl,
                                                            referer = "https://www.dailymotion.com/embed/video/$vidID",
                                                            headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                                                            timeout = 15L).text
                                                        for (m in Regex("""(https?://[^"'\s<>]+\.m3u8[^\s"'<>]*)""").findAll(jsonText)) {
                                                            try { deadLinks.addAll(generateM3u8("Tamamo", m.value, "https://www.dailymotion.com")); break } catch (_: Exception) {}
                                                        }
                                                        val mp4Urls = Regex("""(https?://[^"'\s<>]+\.mp4[^\s"'<>]*)""").findAll(jsonText).map { it.value }.distinct().toList()
                                                        if (mp4Urls.isNotEmpty()) {
                                                            for (mp4Url in mp4Urls) {
                                                                val q = when {
                                                                    mp4Url.contains("1080") -> Qualities.P1080.value
                                                                    mp4Url.contains("720") -> Qualities.P720.value
                                                                    mp4Url.contains("480") -> Qualities.P480.value
                                                                    else -> Qualities.Unknown.value
                                                                }
                                                                deadLinks.add(newExtractorLink(source = "Tamamo", name = "Tamamo ${q/1000}p", url = mp4Url) {
                                                                    this.referer = "https://www.dailymotion.com"
                                                                    this.quality = q
                                                                })
                                                            }
                                                        }
                                                    } catch (_: Exception) {}
                                                    // Fallback: loadExtractor
                                                    val dmUrl = "https://www.dailymotion.com/embed/video/$vidID"
                                                    try { loadExtractor(dmUrl, data, subtitleCallback, callback) } catch (_: Exception) {}
                                                    break
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                    break
                                }
                            }
                        }

                        // ===== Fmoon / FileMoon (bysekoze.com) =====
                        val fmPatterns = listOf(
                            Regex("bysekoze\\.com/e/([a-zA-Z0-9]+)"),
                            Regex("filemoon\\.to/e/([a-zA-Z0-9]+)"),
                            Regex("fmoonplay.*?src=['\"]([^'\"]+)['\"]"),
                        )
                        for (fmPattern in fmPatterns) {
                            val fmMatch = fmPattern.find(unpack)
                            if (fmMatch != null) {
                                val fmUrl = if (fmPattern.pattern.contains("src=")) {
                                    fmMatch.destructured.component1()
                                } else {
                                    "https://bysekoze.com/e/${fmMatch.destructured.component1()}"
                                }
                                embedsFound.add(fmUrl)
                                break
                            }
                        }

                        // ===== Voe (voe.sx) =====
                        val voeRegex = Regex("voe\\.sx/e/([a-zA-Z0-9]+)")
                        val voeId = voeRegex.find(unpack)?.destructured?.component1()
                        if (!voeId.isNullOrEmpty()) {
                            embedsFound.add("https://voe.sx/e/$voeId")
                        }

                        // ===== Vhide / VidHide (vidhidepro.com) =====
                        val vhPatterns = listOf(
                            Regex("vidhidepro\\.com/v/([a-zA-Z0-9]+)"),
                            Regex("vidhidepro\\.com/e/([a-zA-Z0-9]+)"),
                        )
                        for (vhPattern in vhPatterns) {
                            val vhId = vhPattern.find(unpack)?.destructured?.component1()
                            if (!vhId.isNullOrEmpty()) {
                                val prefix = if (vhPattern.pattern.contains("/v/")) "v" else "e"
                                embedsFound.add("https://vidhidepro.com/$prefix/$vhId")
                                break
                            }
                        }

                        // ===== Kaga / VgEmbed (vgembed.com) ===== NEW SERVER
                        val kagaPatterns = listOf(
                            Regex("vgembed\\.com/e/([a-zA-Z0-9]+)"),
                            Regex("vgembed\\.com/v/([a-zA-Z0-9]+)"),
                        )
                        for (kagaPattern in kagaPatterns) {
                            val kagaId = kagaPattern.find(unpack)?.destructured?.component1()
                            if (!kagaId.isNullOrEmpty()) {
                                embedsFound.add("https://vgembed.com/e/$kagaId")
                                break
                            }
                        }

                        // ===== Swish / StreamWish (embedwish.com) =====
                        val swPatterns = listOf(
                            Regex("embedwish\\.com/e/([a-zA-Z0-9]+)"),
                            Regex("streamwish\\.to/e/([a-zA-Z0-9]+)"),
                        )
                        for (swPattern in swPatterns) {
                            val swId = swPattern.find(unpack)?.destructured?.component1()
                            if (!swId.isNullOrEmpty()) {
                                embedsFound.add("https://embedwish.com/e/$swId")
                                break
                            }
                        }

                        // ===== Fallback: buscar URLs m3u8/mp4 en el unpack =====
                        val urlRegex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
                        for (match in urlRegex.findAll(unpack)) {
                            val foundUrl = match.value
                            try {
                                if (foundUrl.contains(".m3u8")) {
                                    deadLinks.addAll(generateM3u8("Server", foundUrl, datafix))
                                } else {
                                    deadLinks.add(newExtractorLink(source = "Server", name = "Server", url = foundUrl) {
                                        this.referer = datafix
                                        this.quality = Qualities.Unknown.value
                                    })
                                }
                            } catch (_: Exception) {}
                        }

                        // ===== Buscar URLs de iframes conocidos en el unpack =====
                        val iframeRegexes = listOf(
                            Regex("""(https?://voe\.sx/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*bysekoze\.com/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*embedwish\.com/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*vidhidepro\.com/[ve]/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*vgembed\.com/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*streamwish\.to/e/[^\s"'<>]+)"""),
                            Regex("""(https?://[^\s"'<>]*filemoon\.to/e/[^\s"'<>]+)"""),
                        )
                        for (regex in iframeRegexes) {
                            for (match in regex.findAll(unpack)) {
                                embedsFound.add(match.value)
                            }
                        }

                    } catch (_: Exception) {}
                }
            }
        }

        // Fallback v22.1: buscar embeds directos en el HTML crudo (por si el sitio
        // deja de empaquetar los players). Los ya procesados no se repiten.
        for (regex in MD_RAW_EMBED_REGEXES) {
            for (match in regex.findAll(rawHtml)) {
                embedsFound.add(match.value)
            }
        }

        // v24.15: AUDITORIA DE SERVIDORES en vivo (20 series x 1 episodio, tools/_md_audit7.log):
        //   Fm/bysekoze  20/20 vivos (ademas solo hay 1 fuente y trae subtitulos)
        //   Sw/embedwish  5/12 vivos (los otros responden 200 con 426 B "no longer available")
        //   Vh/vidhidepro 7/17 vivos (los otros 200 con 419 B "no longer available")
        //   Vg/vgembed   0/7  (404 "no longer available")   -> no se elimina, puede revivir
        //   Voe          0/17 (404)                          -> no se elimina
        //   Asura/Tamamo 0/12 -> ambos cuelgan de www.mdnemonicplayer.xyz, que
        //     responde HTTP 522 (Cloudflare sin origen) -> se emiten los ultimos.
        // sortedByDescending es estable: dentro de cada grupo se conserva el
        // orden del propio sitio.
        embedsFound.sortedByDescending { mdServerPriority(it) }
            .forEach { mdHandleEmbed(it, seenEmbeds, data, subtitleCallback, callback) }
        deadLinks.forEach(callback)

        return true
    }
}
