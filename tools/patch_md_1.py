import io, sys

P = "MundoDonghuaProvider/src/main/kotlin/com/donghuaext/MundoDonghuaProvider.kt"
s = io.open(P, encoding="utf-8", newline="").read().replace("\r\n", "\n")

def sub(old, new, n=1):
    global s
    c = s.count(old)
    assert c == n, "esperaba %d ocurrencias, hay %d para: %r" % (n, c, old[:90])
    s = s.replace(old, new)

# ---------------------------------------------------------------- 1) NOTA sobre el PoW
# El port del PoW (powHash/mixRound/tabla) se verifico byte a byte contra el
# bundle assets/pow-*.js del embed y es CORRECTO: no se toca. Los "pow_failed"
# que aparecian en las sondas de Node venian de una transc erronea de las sondas,
# no del provider.

# ---------------------------------------------------------------- 2) DTOs + API publica + subtitulos
old_start = s.index("    // DTOs del playback cifrado (mismo estilo que SsrInit en AnimeGratis)")
old_end = s.index("\n/**\n * v22.2: resolver un embed de forma SERIE")
new_block = '''    // DTOs del playback cifrado (mismo estilo que SsrInit en AnimeGratis)
    private data class BysePlaybackEnc(
        val algorithm: String? = null,
        val iv: String? = null,
        val payload: String? = null,
        val key_parts: List<String>? = null,
        val version: Any? = null,
    ) {
        val versionNumber: Int? get() = when (val v = version) {
            is Number -> v.toInt()
            is String -> Regex("\\\\d+").find(v)?.value?.toIntOrNull()
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
        if (txt.contains("\\"playback\\"")) parseJson<ByseVideo>(txt) else null
    } catch (_: Exception) { null }

    // Camino 2 (respaldo): captcha + PoW + playback, como en v24.14.
    private suspend fun fetchViaCaptcha(code: String): ByseVideo? = try {
        val headers = apiHeaders() + mapOf("Content-Type" to "application/json")
        val captchaResp = app.post(
            "$API_BASE/api/videos/$code/embed/captcha",
            json = mapOf<String, Any>(), headers = headers, timeout = 20L
        ).text
        val nonce = Regex("\\"pow_nonce\\"\\\\s*:\\\\s*\\"([^\\"]+)\\"").find(captchaResp)?.groupValues?.get(1) ?: return null
        val difficulty = Regex("\\"pow_difficulty\\"\\\\s*:\\\\s*(\\\\d+)").find(captchaResp)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val powToken = Regex("\\"pow_token\\"\\\\s*:\\\\s*\\"([^\\"]+)\\"").find(captchaResp)?.groupValues?.get(1) ?: return null
        val solution = solvePow(nonce, difficulty) ?: return null
        val verifyResp = app.post(
            "$API_BASE/api/videos/$code/embed/captcha/verify",
            json = mapOf<String, Any>("pow_token" to powToken, "solution" to solution),
            headers = headers, timeout = 20L
        ).text
        val captchaToken = Regex("\\"token\\"\\\\s*:\\\\s*\\"([^\\"]+)\\"").find(verifyResp)?.groupValues?.get(1) ?: return null
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
     * v24.15: el playback se descifra partiendo la etiqueta GCM a mano
     * (16 ultimos bytes). Con decryptAndFinal sobre el payload entero la
     * autenticacion falla en algunas implementaciones de la JVM.
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
        var v = raw.replace("\\r", "")
        v = Regex("^WEBVTT[^\\n]*\\n").replace(v, "")
        v = Regex("^NOTE[^\\n]*\\n", RegexOption.MULTILINE).replace(v, "")
        v = Regex("^\\\\d+[ \\\\t]*\\n", RegexOption.MULTILINE).replace(v, "")
        return v.trim() + "\\n"
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
        val segments = Regex("""^(?!#)([^\\r\\n]+)$""", RegexOption.MULTILINE)
            .findAll(playlist).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }
            .take(80).toList()
        if (segments.isEmpty()) return
        val sb = StringBuilder("WEBVTT\\n\\n")
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
        for (line in Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^\\n]*""").findAll(master).map { it.value }) {
            val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1) ?: continue
            val name = Regex("""NAME="([^"]*)"""").find(line)?.groupValues?.get(1) ?: ""
            val lang = if (name.contains("es", ignoreCase = true)) "Spanish" else name.ifBlank { "Spanish" }
            val abs = try { java.net.URI(m).resolve(uri).toString() } catch (_: Exception) { continue }
            if (!emitted.add(abs)) continue
            emitMergedSubtitle(abs, lang, subtitleCallback)
        }
    }

    suspend fun getUrl(embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val code = Regex("bysekoze\\\\.com/[ev]/([a-zA-Z0-9]+)").find(embedUrl)?.groupValues?.get(1) ?: return
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
                if (masterForSubs == null && url.endsWith("master.m3u8")) masterForSubs = url
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

'''
s = s[:old_start] + new_block + s[old_end + 1:]

# ---------------------------------------------------------------- 3) llamada al extractor
sub("""            embedUrl.contains("bysekoze.com") -> mdFmoonByseExtractor.getUrl(embedUrl, callback)""",
    """            embedUrl.contains("bysekoze.com") ->
                mdFmoonByseExtractor.getUrl(embedUrl, subtitleCallback, callback)""")

io.open(P, "w", encoding="utf-8", newline="").write(s.replace("\n", "\r\n"))
print("MundoDonghuaProvider.kt parcheado")