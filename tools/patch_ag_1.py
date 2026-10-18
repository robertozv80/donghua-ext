import io

P = "AnimeGratisProvider/src/main/kotlin/com/donghuaext/AnimeGratisProvider.kt"
s = io.open(P, encoding="utf-8", newline="").read().replace("\r\n", "\n")

def sub(old, new, n=1):
    global s
    c = s.count(old)
    assert c == n, "esperaba %d, hay %d para: %r" % (n, c, old[:100])
    s = s.replace(old, new)

sub("""    // Helper para obtener la mejor URL de imagen de un Element?
    // Prioriza src (siempre funciona en el navegador) sobre data-fallback/data-fb2
    private fun org.jsoup.nodes.Element?.bestImageUrl(): String {
        if (this == null) return ""
        // Probar múltiples atributos de imagen
        val candidates = mutableListOf<String>()""",
"""    /**
     * v24.15: mejor variante declarada en `srcset` (mayor ancho `Nw`; si el
     * descriptor es `Nx` se usa la densidad). Se descartan los srcset de /icons/
     * (el logo del sitio es el unico srcset que sirve hoy animegratis.net).
     *
     * MEDIDO en v24.15: las cards de catálogo NO llevan srcset - el unico srcset
     * del HTML es el del logo (/icons/logo-animegratis-*.webp) y el `src` de las
     * pósters ya apunta al ORIGINAL del CDN
     * (cdn.animegratis.net/<tipo>/<slug>/images/cover.webp|.jpg, ~50 KB): no
     * existen variantes (cover-large.webp, cover@2x.webp, cover_1080.webp,
     * original.* -> 404) ni parámetros (?w=, ?width=, ?quality= devuelven el
     * mismo byte a byte). O sea: hoy no se gana calidad, pero el helper queda
     * preparado por si el sitio empieza a generar srcset en las cards.
     */
    private fun org.jsoup.nodes.Element?.bestSrcsetUrl(): String? {
        val raw = this?.attr("srcset")?.trim().orEmpty()
        if (raw.isBlank()) return null
        var bestUrl: String? = null
        var bestScore = -1L
        for (candidate in raw.split(',')) {
            val bits = candidate.trim().split(Regex("\\\\s+"))
            val url = bits.firstOrNull()?.trim().orEmpty()
            if (url.isBlank() || url.startsWith("data:")) continue
            val desc = bits.getOrNull(1)?.trim().orEmpty()
            val width = desc.removeSuffix("w").toIntOrNull() ?: -1
            val density = if (desc.endsWith("x")) desc.dropLast(1).toFloatOrNull() ?: 1f else 1f
            val score = if (width > 0) width * 1000L + (density * 10).toLong()
            else (density * 1000).toLong()
            if (score > bestScore) { bestScore = score; bestUrl = url }
        }
        val u = bestUrl ?: return null
        // el logo del sitio no es un póster
        if (u.contains("/icons/") || u.contains("/images/placeholder")) return null
        return when {
            u.startsWith("http") -> u
            u.startsWith("/") -> "$mainUrl$u"
            else -> "$mainUrl/$u"
        }
    }

    // Helper para obtener la mejor URL de imagen de un Element?
    // Prioriza srcset (mayor variante) y luego src, que siempre funciona en el
    // navegador, sobre data-fallback/data-fb2
    private fun org.jsoup.nodes.Element?.bestImageUrl(): String {
        if (this == null) return ""
        // srcset con contenido de verdad: gana la variante de mayor resolución
        bestSrcsetUrl()?.let { return it }
        // Probar múltiples atributos de imagen
        val candidates = mutableListOf<String>()""")

io.open(P, "w", encoding="utf-8", newline="").write(s.replace("\n", "\r\n"))
print("srcset aplicado en AnimeGratis")