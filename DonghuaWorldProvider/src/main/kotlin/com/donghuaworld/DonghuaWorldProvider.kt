package com.donghuaworld

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.donghuaext.common.CommonExtractors as CE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class DonghuaWorldProvider : MainAPI() {
    override var mainUrl = "https://donghuaworld.com"
    override var name = "DonghuaWorld"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "DonghuaWorld"
        private const val PLAYER_BASE = "https://player.donghuaplanet.com"

        // v23: el sitio activo el challenge de Cloudflare ("Just a moment") y las
        // peticiones de OkHttp reciben 403. Se resuelve una vez en WebView y se
        // reutiliza la cookie cf_clearance; DEBE ser la misma UA que envian las
        // peticiones porque Cloudflare la vincula al emitir la cookie.
        private const val DWW_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        private const val DWW_PAGE_TTL_MS = 120_000L
        private const val DWW_IMAGE_TTL_MS = 10 * 60_000L
        private const val DWW_IMAGE_CACHE_MAX = 600

        // v25: CS3 corta getMainPage a los 120s y el reto de Cloudflare tarda
        // ~2min en el WebView del emulador. Cache de paginas en disco (4h) para
        // que el home abra al instante en sesiones posteriores mientras un
        // prewarm en segundo plano la refresca.
        private const val DWW_DISK_TTL_MS = 4 * 60 * 60_000L
        private const val DWW_DISK_PAGE_MAX = 40
        private const val DWW_DISK_IMG_MAX_BYTES = 8_000_000L
        private const val DWW_DISK_IMG_SAVE_MIN_MS = 5_000L

        /** Regex to match episode numbers or ranges like "Episode 263" or "Episode 254-255" */
        private val EPISODE_NUM_REGEX = Regex("""Episode\s+(\d+(?:-\d+)?)""", RegexOption.IGNORE_CASE)

        /** Languages to include as subtitles (Spanish primary, English fallback) */
        private val SUBTITLE_LANGUAGES = setOf("Spanish", "English")

        /** Keywords that identify Spanish subtitle tracks (case-insensitive matching) */
        private val SPANISH_KEYWORDS = listOf("spanish", "español", "espanol", "espa\u00f1ol", "es-es", "es-419", "latino", "latam")

        /** Keywords that identify English subtitle tracks (case-insensitive matching) */
        private val ENGLISH_KEYWORDS = listOf("english", "eng", "en-us", "en-gb")
    }

    // ==================== CLOUDFLARE (v23) + cache y puente de imagenes (v24) ====================

    /** Pagina ya resuelta: HTML sin challenge + (opcional) imagenes puenteadas. */
    private class DwwPage(val html: String)

    @Volatile
    private var dwwCookies: String? = null

    @Volatile
    private var dwwUaCache: String? = null

    @Volatile
    private var dwwLastImgSave: Long = 0L

    @Volatile
    private var dwwLastImgCollectAt: Long = 0L

    private val dwwScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /** v25: al cargar el plugin resolvemos el reto y refrescamos la cache en
     *  segundo plano, para que getMainPage (tope 120s de CS3) nunca espere el
     *  reto si la cache de disco sigue valida. */
    init {
        // Los posters del disco cargan SIN delay: el home puede renderizar
        // antes de que termine el prewarm y los necesita ya puenteados.
        dwwScope.launch { dwwDiskLoadImages() }
        dwwScope.launch {
            kotlinx.coroutines.delay(3_000L)
            try {
                dwwFetch("$mainUrl/", collectImages = true)
            } catch (_: Throwable) {}
            try {
                dwwFetch("$mainUrl/anime/?status=completed&sub=&order=latest", collectImages = true)
            } catch (_: Throwable) {}
            // v26: si las paginas salieron de disco, nunca hubo WebView y el
            // puente no refresco -> forzar un pase por WebView sobre el home
            // para puentear posters y thumbs de episodios (los data URI no se
            // pueden descargar por OkHttp: Cloudflare rechaza el fingerprint).
            val bridge = synchronized(dwwImageCache) { dwwImageCache.size }
            val stale = System.currentTimeMillis() - dwwLastImgCollectAt > 30 * 60_000L
            if (bridge < 10 || stale) {
                try {
                    dwwFetch("$mainUrl/", collectImages = true, bypassCache = true)
                } catch (_: Throwable) {}
            }
            Log.i(TAG, "dwwPrewarm: listo bridge=$bridge stale=$stale")
        }
    }

    // FIX v24 (home lento): el home pide la MISMA url para 2 secciones (Latest
    // Release y Recommendation); sin cache se lanzaban 2 retos WebView completos
    // en paralelo y el home tardaba el doble. TTL corto + mutex para que la
    // segunda seccion reutilice el fetch de la primera.
    private val dwwPageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, DwwPage>>()
    private val dwwPageMutex = kotlinx.coroutines.sync.Mutex()

    // FIX v24 (posters grises): Cloudflare tambien rechaza las peticiones de
    // imagen de la app (Coil/OkHttp, mismo fingerprint TLS que el resto). La
    // unica pila que pasa es el WebView, asi que en la misma sesion se descargan
    // las imagenes de las cards via fetch() del propio WebView (con
    // cf_clearance) y se guardan como data URI para los posters.
    private val dwwImageCache = java.util.LinkedHashMap<String, Pair<Long, String>>()

    private fun dwwCacheImages(images: Map<String, String>) {
        val now = System.currentTimeMillis()
        synchronized(dwwImageCache) {
            for ((k, v) in images) dwwImageCache[k] = now to v
            dwwImageCache.entries.removeAll { now - it.value.first > DWW_IMAGE_TTL_MS }
            while (dwwImageCache.size > DWW_IMAGE_CACHE_MAX) {
                val oldest = dwwImageCache.entries.minByOrNull { it.value.first } ?: break
                dwwImageCache.remove(oldest.key)
            }
        }
        // v25: persistir el puente para que los posters sobrevivan al reinicio.
        if (now - dwwLastImgSave > DWW_DISK_IMG_SAVE_MIN_MS) {
            dwwLastImgSave = now
            dwwScope.launch { dwwDiskSaveImages() }
        }
    }

    // ==================== CACHE EN DISCO (v25) ====================

    private fun dwwDiskDir(): java.io.File? = try {
        val d = java.io.File(dwwContext()?.filesDir, "dwwcache")
        if (!d.exists()) d.mkdirs()
        if (d.isDirectory) d else null
    } catch (_: Throwable) {
        null
    }

    private fun dwwPageFile(url: String): java.io.File? =
        dwwDiskDir()?.let { java.io.File(it, "p" + Integer.toHexString(url.hashCode()) + ".html") }

    private fun dwwDiskLoadPage(url: String): String? = try {
        val f = dwwPageFile(url) ?: return null
        if (!f.isFile) return null
        val raw = f.readText()
        val nl = raw.indexOf('\n')
        val ts = if (nl > 0) raw.substring(0, nl).toLongOrNull() ?: 0L else 0L
        if (System.currentTimeMillis() - ts < DWW_DISK_TTL_MS) raw.substring(nl + 1) else null
    } catch (_: Throwable) {
        null
    }

    private fun dwwDiskSavePage(url: String, html: String) {
        dwwScope.launch {
            try {
                val f = dwwPageFile(url) ?: return@launch
                f.writeText(System.currentTimeMillis().toString() + "\n" + html)
                Log.i(TAG, "dwwDiskSavePage: ${f.name} bytes=${html.length}")
                val dir = dwwDiskDir() ?: return@launch
                val pages = dir.listFiles { n -> n.name.startsWith("p") && n.name.endsWith(".html") }
                if (pages != null && pages.size > DWW_DISK_PAGE_MAX) {
                    pages.sortedBy { it.lastModified() }
                        .take(pages.size - DWW_DISK_PAGE_MAX)
                        .forEach { it.delete() }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun dwwDiskLoadImages() {
        try {
            val f = dwwDiskDir()?.let { java.io.File(it, "img.json") } ?: return
            if (!f.isFile) return
            val obj = org.json.JSONObject(f.readText())
            val m = LinkedHashMap<String, String>()
            for (k in obj.keys()) {
                val v = obj.optString(k)
                if (v.startsWith("data:image")) m[k] = v
            }
            if (m.isNotEmpty()) {
                dwwCacheImages(m)
                Log.i(TAG, "dwwDiskLoadImages: ${m.size} posters desde disco")
            }
        } catch (_: Throwable) {}
    }

    private suspend fun dwwDiskSaveImages() {
        try {
            val snapshot = synchronized(dwwImageCache) { LinkedHashMap(dwwImageCache) }
            val obj = org.json.JSONObject()
            var total = 0L
            for ((k, v) in snapshot.entries.sortedByDescending { it.value.first }) {
                val d = v.second
                if (d.isEmpty() || d.length > 300_000) continue
                if (total + d.length > DWW_DISK_IMG_MAX_BYTES) break
                obj.put(k, d)
                total += d.length
            }
            val f = dwwDiskDir()?.let { java.io.File(it, "img.json") } ?: return
            f.writeText(obj.toString())
            Log.i(TAG, "dwwDiskSaveImages: entries=${obj.length()} bytes=$total")
        } catch (_: Throwable) {}
    }

    /** URL de poster puenteada: data URI si la descargo el WebView, si no la original. */
    private fun dwwBridgeImg(url: String?): String {
        val u = url?.trim().orEmpty()
        if (u.isEmpty() || u.startsWith("data:")) return u
        return synchronized(dwwImageCache) { dwwImageCache[u]?.second } ?: u
    }

    /** UA real del WebView: si hardcodeamos otro Chrome, el fingerprint (sec-ch-ua)
     *  no coincide y Cloudflare manda el reto en bucle sin resolverlo nunca. */
    private fun dwwUserAgent(): String {
        val cached = dwwUaCache
        if (cached != null) return cached
        val ua = try {
            android.webkit.WebSettings.getDefaultUserAgent(dwwContext()!!)
        } catch (_: Throwable) {
            DWW_UA
        }
        dwwUaCache = ua
        return ua
    }

    private fun isDwwChallenge(text: String): Boolean =
        text.contains("Just a moment", ignoreCase = true) ||
            text.contains("Un momento…") ||
            text.contains("_cf_chl_opt")

    private fun dwwContext(): Context? = try {
        Class.forName("com.lagradost.api.ContextHelper_jvmKt")
            .declaredMethods.firstOrNull { it.name == "getContext" }?.invoke(null) as? Context
    } catch (_: Throwable) {
        try {
            val f = Class.forName("com.lagradost.cloudstream3.AcraApplication").getDeclaredField("context")
            f.isAccessible = true
            f.get(null) as? Context
        } catch (_: Throwable) {
            try {
                val m = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
                m.isAccessible = true
                m.invoke(null) as? Context
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** JS que corre dentro del WebView: descarga las imagenes de las cards
     *  (mismo origen, cookies y fingerprint del navegador) y empuja cada una al
     *  puente __dwwSink (JavascriptInterface) apenas esta lista. El callback de
     *  evaluateJavascript se congela con WebViews sin adjuntar a la ventana
     *  (renderer suspendido), por eso los datos via por la interfaz JS->Java.
     *  Sin template literals ni arrow funcs para WebViews viejos. */
    private val DWW_IMAGE_JS = """
        (function() {
          var seen = {};
          var urls = [];
          var els = document.querySelectorAll('article img, .thumb img, .ts-post-image');
          for (var i = 0; i < els.length; i++) {
            var el = els[i];
            var s = el.getAttribute('data-src') || el.getAttribute('data-lazy-src') || el.currentSrc || el.src || '';
            if (s.indexOf('https://') === 0 && s.indexOf('/wp-content/uploads/') > 0 && !seen[s]) {
              seen[s] = 1; urls.push(s);
            }
          }
          urls = urls.slice(0, 90);
          var ka = 0;
          setInterval(function() { ka++; }, 1500);
          for (var j = 0; j < urls.length; j++) {
            (function(u) {
              fetch(u, { credentials: 'include' }).then(function(r) {
                if (!r.ok) return null;
                return r.blob();
              }).then(function(b) {
                if (!b || !b.size || b.size > 250000) return;
                return b.arrayBuffer().then(function(ab) {
                  var buf = new Uint8Array(ab);
                  var bin = '';
                  for (var i = 0; i < buf.length; i += 0x8000) {
                    bin += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
                  }
                  var d = 'data:' + (b.type || 'image/webp') + ';base64,' + btoa(bin);
                  try { window.__dwwSink.onImage(JSON.stringify({ u: u, d: d })); } catch (e2) {}
                });
              }).catch(function(e) {});
            })(urls[j]);
          }
        })()
    """.trimIndent()

    /** Carga url en un WebView (su Chromium pasa el fingerprint TLS/JS que Cloudflare
     *  le exige a OkHttp) y devuelve el HTML final ya sin challenge. Con
     *  collectImages ademas descarga en la MISMA sesion las imagenes de las cards
     *  y las deja en el cache-puente (Cloudflare rechaza las peticiones de
     *  imagen de la app). */
    private suspend fun dwwWebViewPage(url: String, collectImages: Boolean): DwwPage? =
        withContext(Dispatchers.Main) {
            val ctx = dwwContext()
            if (ctx == null) {
                Log.w(TAG, "dwwWebViewPage: sin Context, no se puede resolver el challenge")
                return@withContext null
            }
            suspendCancellableCoroutine { cont ->
                var webView: WebView? = null
                // v26: los postDelayed de un WebView sin adjuntar a ventana NUNCA
                // corren (View.postDelayed espera al attach) -> programar todo con
                // un Handler propio, que si funciona.
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                var done = false
                var html: String? = null
                var imagesDone = false
                var imagesStartedAt = 0L
                var imgReceived = 0
                var lastImageAt = 0L
                val imgQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
                val imgSink = object : Any() {
                    @JavascriptInterface
                    fun onImage(json: String?) {
                        if (!json.isNullOrEmpty()) imgQueue.add(json)
                    }
                }
                val startedAt = android.os.SystemClock.elapsedRealtime()
                fun finish() {
                    if (done) return
                    done = true
                    mainHandler.removeCallbacksAndMessages(null)
                    try { webView?.stopLoading() } catch (_: Throwable) {}
                    try { webView?.destroy() } catch (_: Throwable) {}
                    val h = html
                    if (h != null) {
                        Log.i(TAG, "dwwWebViewPage: OK en ${android.os.SystemClock.elapsedRealtime() - startedAt}ms imgs=$imgReceived")
                        // v25: guardar aqui y no en dwwFetch -> si CS3 cancelo al
                        // llamador (tope de 120s del home) justo al resolver, el
                        // trabajo del WebView no se pierde y el prewarm lo reutiliza.
                        dwwPageCache[url] = System.currentTimeMillis() to DwwPage(h)
                        dwwDiskSavePage(url, h)
                    } else {
                        Log.w(TAG, "dwwWebViewPage: timeout/sin HTML limpio tras ${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
                    }
                    if (cont.isActive) cont.resume(h?.let { DwwPage(it) }) {}
                }
                fun maybeFinish() {
                    if (html != null && (!collectImages || imagesDone)) finish()
                }
                fun unjson(s: String?): String? = try {
                    org.json.JSONTokener(s ?: "\"\"").nextValue() as? String
                } catch (_: Throwable) {
                    null
                }
                /** Watchdog del hilo principal: drena la cola del sink y termina la
                 *  fase de imagenes por quietud (8s sin llegar una nueva) o tope.
                 *  No depende de evaluateJavascript, cuyos callbacks se congelan. */
                fun imgWatchdog() {
                    if (done) return
                    val now = android.os.SystemClock.elapsedRealtime()
                    var fresh = 0
                    var s = imgQueue.poll()
                    while (s != null) {
                        try {
                            val obj = org.json.JSONTokener(s).nextValue() as? org.json.JSONObject
                            if (obj != null) {
                                val u = obj.optString("u")
                                val d = obj.optString("d")
                                if (u.isNotEmpty() && d.startsWith("data:image")) {
                                    dwwCacheImages(mapOf(u to d))
                                    fresh++
                                }
                            }
                        } catch (_: Throwable) {}
                        s = imgQueue.poll()
                    }
                    if (fresh > 0) {
                        imgReceived += fresh
                        lastImageAt = now
                    }
                    val inPhase = now - imagesStartedAt
                    val finished = (imgReceived > 0 && now - lastImageAt > 8000L) ||
                        (imgReceived == 0 && inPhase > 45_000L) || inPhase > 120_000L
                    if (finished) {
                        Log.i(TAG, "dwwWebViewPage: imagenes=$imgReceived en ${now - startedAt}ms")
                        if (imgReceived > 0) dwwLastImgCollectAt = System.currentTimeMillis()
                        imagesDone = true
                        maybeFinish()
                    } else {
                        mainHandler.postDelayed({ imgWatchdog() }, 2000L)
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
                    try { wv.addJavascriptInterface(imgSink, "__dwwSink") } catch (_: Throwable) {}
                    var attempts = 0
                    var imagesStarted = false
                    fun checkPage(view: WebView) {
                        view.evaluateJavascript("document.documentElement.outerHTML") { htmlJson ->
                            val h = unjson(htmlJson)
                            if (!h.isNullOrBlank() && !isDwwChallenge(h)) {
                                val ck = CookieManager.getInstance().getCookie(mainUrl) ?: ""
                                if (ck.isNotBlank()) dwwCookies = ck
                                html = h
                                if (collectImages) {
                                    // Un solo arranque: onPageFinished puede dispararse
                                    // varias veces y un reinicio perderia lo recibido.
                                    if (!imagesStarted) {
                                        imagesStarted = true
                                        imagesStartedAt = android.os.SystemClock.elapsedRealtime()
                                        lastImageAt = imagesStartedAt
                                        view.evaluateJavascript(DWW_IMAGE_JS, null)
                                        mainHandler.postDelayed({ imgWatchdog() }, 2000L)
                                    }
                                } else {
                                    maybeFinish()
                                }
                            } else if (++attempts <= 30) {
                                // El reto (Just a moment / Un momento…) puede tardar ~70s en
                                // resolver solo; sondear el HTML en vez de fiarse del titulo
                                // (viene localizado).
                                mainHandler.postDelayed({ checkPage(view) }, 4000L)
                            } else {
                                finish()
                            }
                        }
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, vurl: String) {
                            checkPage(view)
                        }
                    }
                    wv.loadUrl(url)
                    mainHandler.postDelayed({ finish() }, 150_000L)
                } catch (t: Throwable) {
                    Log.w(TAG, "dwwWebViewPage: ${t.message}")
                    finish()
                }
                cont.invokeOnCancellation { finish() }
            }
        }

    /** HTML (+ imagenes si se piden) de una pagina: cache TTL -> OkHttp rapido
     *  y, si el sitio exige el reto de Cloudflare (403 / "Just a moment"),
     *  WebView. El mutex evita que dos secciones del home lancen el mismo reto
     *  en paralelo. */
    private suspend fun dwwFetch(
        url: String,
        referer: String? = null,
        collectImages: Boolean = false,
        bypassCache: Boolean = false
    ): DwwPage {
        val now = System.currentTimeMillis()
        if (!bypassCache) {
            dwwPageCache[url]?.let { (ts, p) -> if (now - ts < DWW_PAGE_TTL_MS) return p }
            // v26: cache de disco FUERA del mutex -> si un load() de arranque
            // esta resolviendo el reto (~2min), el home igual abre al instante
            // desde disco sin esperar el mutex.
            dwwDiskLoadPage(url)?.let { html ->
                Log.i(TAG, "dwwFetch: cache disco para $url")
                val p = DwwPage(html)
                dwwPageCache[url] = System.currentTimeMillis() to p
                return p
            }
        }
        return dwwPageMutex.withLock {
            val t2 = System.currentTimeMillis()
            if (!bypassCache) {
                dwwPageCache[url]?.let { (ts, p) -> if (t2 - ts < DWW_PAGE_TTL_MS) return p }
                dwwDiskLoadPage(url)?.let { html ->
                    val p = DwwPage(html)
                    dwwPageCache[url] = System.currentTimeMillis() to p
                    return p
                }
            }
            fun headers(): Map<String, String> {
                val h = mutableMapOf("User-Agent" to dwwUserAgent())
                dwwCookies?.let { h["Cookie"] = it }
                return h
            }
            var page: DwwPage? = null
            try {
                val resp = app.get(url, headers = headers(), referer = referer, timeout = 25L)
                if (resp.code != 403 && !isDwwChallenge(resp.text)) {
                    page = DwwPage(resp.text)
                }
            } catch (_: Throwable) {}
            if (page == null) {
                Log.i(TAG, "dwwFetch: challenge Cloudflare en $url -> WebView")
                page = dwwWebViewPage(url, collectImages)
            }
            page?.let {
                dwwPageCache[url] = System.currentTimeMillis() to it
                dwwDiskSavePage(url, it.html)
            }
            page ?: DwwPage("")
        }
    }

    /** Document con baseUri=url para que abs:href resuelva bien. */
    private suspend fun dwwGet(
        url: String,
        referer: String? = null,
        collectImages: Boolean = false
    ): Document = Jsoup.parse(dwwFetch(url, referer, collectImages).html, url)

    // ==================== MAIN PAGE ====================
    // FIX 2026-08-11: Refactorizado para usar selectores CSS basados en clases en lugar
    // de matching por texto de heading. Mas robusto y menos propenso a romperse si el
    // sitio cambia el texto del heading (ej: "Series Update" → "Hot Series Update").
    //
    // Ademas, agrega la nueva seccion "Completed" que viene de la URL
    // /anime/?status=completed&sub=&order=latest (con paginacion via ?page=N).
    //
    // Estructura HTML del homepage (verificada con BS4):
    //   <div class="releases hothome"><h3>...Series Update...</h3></div>
    //   <div class="listupd popularslider">...<article>...</article>...</div>   ← Hot Series Update (5 articles)
    //   <div class="releases latesthome"><h3>Latest Release</h3></div>
    //   <div class="listupd normal">...<article>...</article>...</div>           ← Latest Release (~30 articles)
    //   <div class="releases"><h3>Recommendation</h3></div>
    //   <div class="series-gen">...<article>...</article>...</div>              ← Recommendation (25 articles)
    //
    // Para "Completed" (separada): GET /anime/?status=completed&sub=&order=latest
    //   <div class="listupd">...<article>...</article>...</div>                  ← 20 series articles
    //   Paginacion: ?page=N&status=completed&sub=&order=latest
    //
    // v22: se ELIMINA la seccion "Hot Series Update" (solicitado por el usuario);
    // permanecen Latest Release, Recommendation y Completed.

    override val mainPage = mainPageOf(
        "$mainUrl/##latest" to "Latest Release",
        "$mainUrl/##recommendation" to "Recommendation",
        "$mainUrl/anime/?status=completed&sub=&order=latest" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionName = request.name
        val sectionUrl = request.data

        // === Completed section: separate page with its own pagination ===
        if (sectionName == "Completed") {
            // URL: /anime/?status=completed&sub=&order=latest
            // Page N: ?page=N&status=completed&sub=&order=latest
            val url = if (page > 1) {
                "$mainUrl/anime/?page=$page&status=completed&sub=&order=latest"
            } else {
                sectionUrl
            }
            // v24: collectImages puentea las imagenes de las cards por el WebView.
            val document = dwwGet(url, collectImages = true)
            val items = document.select(".listupd article").mapNotNull { art ->
                parseArticleCard(art)
            }
            // Paginacion: si la pagina actual tiene 20 articulos, hay mas paginas
            val hasNext = items.size >= 20
            return newHomePageResponse(
                listOf(HomePageList(sectionName, items)),
                hasNext = hasNext
            )
        }

        // === Homepage sections (Latest/Recommendation; "Hot Series Update" eliminada en v22) ===
        // Solo la primera pagina trae el HTML del homepage; a partir de pagina 2
        // cargamos /page/N/ para la seccion Latest Release.
        // v24: ambas secciones piden la MISMA url -> el cache TTL de dwwFetch
        // resuelve el reto una sola vez. collectImages puentea los posters.
        val document = if (page == 1) {
            dwwGet(sectionUrl.substringBefore("##"), collectImages = true)
        } else {
            if (sectionUrl.contains("latest")) {
                dwwGet("$mainUrl/page/$page/", collectImages = true)
            } else {
                return newHomePageResponse(emptyList(), hasNext = false)
            }
        }

        val items = when {
            sectionName == "Latest Release" -> document.select(".listupd.normal article").mapNotNull { parseArticleCard(it) }
            sectionName == "Recommendation" -> document.select(".series-gen article").mapNotNull { parseArticleCard(it) }
            else -> emptyList()
        }

        return newHomePageResponse(
            listOf(HomePageList(sectionName, items)),
            hasNext = sectionName == "Latest Release" && items.isNotEmpty()
        )
    }

    /**
     * Parse a single article card into a SearchResponse.
     * FIX 2026-07-29: La home de donghuaworld.com lista URLs de EPISODIOS
     * (https://donghuaworld.com/martial-master-episode-678-...), no URLs de series.
     * Pasamos la URL de episodio tal cual a load(); load() la resuelve via
     * resolveSeriesUrlFromEpisode() leyendo el breadcrumb de la pagina de episodio.
     * Esto es 1 HTTP request extra pero es 100% confiable: el slug del episodio
     * a veces NO coincide con el slug de la serie (ej: "swallowed-star-warrior-of-the-galaxy"
     * → serie real "/anime/swallowed-the-universe-warrior-of-galaxy/").
     * La heuristica anterior (convertEpisodeUrlToSeriesUrl) producia 404 en esos casos.
     */
    private fun parseArticleCard(article: org.jsoup.nodes.Element): SearchResponse? {
        val linkEl = article.selectFirst("a[href]") ?: return null
        val episodeUrl = linkEl.attr("abs:href")
        if (episodeUrl.isEmpty()) return null

        val title = linkEl.attr("title").takeIf { it.isNotEmpty() }
            ?: linkEl.selectFirst(".eggtitle")?.text()?.trim()
            ?: linkEl.selectFirst("h2")?.text()?.trim()
            ?: return null

        val img = linkEl.selectFirst("img")?.let { imgEl ->
            imgEl.attr("data-src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: imgEl.attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: imgEl.attr("src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
        } ?: ""

        // FIX: Extraer número de episodio del título o del badge
        val epNum = EPISODE_NUM_REGEX.find(title)?.groupValues?.get(1)?.let { numStr ->
            numStr.substringBefore("-").toIntOrNull()
        } ?: article.selectFirst(".epx")?.text()?.let { epText ->
            Regex("""Ep\s+(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.destructured?.component1()?.toIntOrNull()
        }

        // Extraer nombre de serie limpio (sin "Episode X" ni indicadores de calidad)
        val cleanTitle = title.replace(Regex("""\s*Episode\s+\d+(?:-\d+)?[^|]*$""", RegexOption.IGNORE_CASE), "").trim()

        // Pasamos la URL de episodio; load() la resuelve via breadcrumb (ver resolveSeriesUrlFromEpisode)
        return newAnimeSearchResponse(cleanTitle, episodeUrl) {
            // v24: si el WebView puentéo la imagen (Cloudflare la bloquea para Coil),
            // servimos el data URI; si no, la URL original.
            this.posterUrl = dwwBridgeImg(img)
            addDubStatus(DubStatus.Subbed, epNum)
        }
    }

    /**
     * FIX 2026-07-28: Convierte una URL de episodio a su URL de serie.
     * Ej: /martial-master-episode-678-4k-multi-subtitles/ → /anime/martial-master/
     * Si la URL no contiene "-episode-" se devuelve tal cual (ya es URL de serie).
     */
    private fun convertEpisodeUrlToSeriesUrl(episodeUrl: String): String {
        return try {
            val uri = java.net.URI(episodeUrl)
            val path = uri.path ?: return episodeUrl
            // Extraer el slug: tomar el último segmento del path
            val segments = path.split("/").filter { it.isNotEmpty() }
            val slug = segments.lastOrNull() ?: return episodeUrl
            // Si no parece URL de episodio, devolver tal cual
            val lowerSlug = slug.lowercase()
            val epIdx = lowerSlug.indexOf("-episode-")
            if (epIdx < 0) return episodeUrl
            // Tomar todo antes de "-episode-" (case-insensitive, preservando el case original)
            val seriesSlug = slug.substring(0, epIdx)
            if (seriesSlug.isEmpty()) return episodeUrl
            // Construir URL canónica /anime/<seriesSlug>/
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return episodeUrl
            "$scheme://$host/anime/$seriesSlug/"
        } catch (_: Exception) {
            episodeUrl
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val document = dwwGet("$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}", collectImages = true)
        val items = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        document.select("article").forEach { article ->
            val linkEl = article.selectFirst("a[href]") ?: return@forEach
            val url = linkEl.attr("abs:href")

            if (!url.contains("/anime/")) return@forEach
            if (!seenUrls.add(url)) return@forEach

            val title = linkEl.attr("title").takeIf { it.isNotEmpty() }
                ?: article.selectFirst("h2")?.text()?.trim()
                ?: return@forEach

            val img = article.selectFirst("img")?.let { imgEl ->
                imgEl.attr("data-src").takeIf { it.isNotEmpty() && !it.startsWith("data:") } ?: imgEl.attr("src")
            } ?: ""

            // Extraer número de episodio si está en el título
            val epNum = EPISODE_NUM_REGEX.find(title)?.groupValues?.get(1)?.let { numStr ->
                numStr.substringBefore("-").toIntOrNull()
            }

            val cleanTitle = title.replace(Regex("""\s*Episode\s+\d+(?:-\d+)?[^|]*$""", RegexOption.IGNORE_CASE), "").trim()

            items.add(newAnimeSearchResponse(cleanTitle, url) {
                this.posterUrl = dwwBridgeImg(img)
                addDubStatus(DubStatus.Subbed, epNum)
            })
        }
        return items
    }

    // ==================== DETAIL ====================

    override suspend fun load(url: String): LoadResponse {
        // If URL is an episode page (not /anime/), extract the series URL from breadcrumb
        val seriesUrl = if (url.contains("/anime/")) {
            url
        } else {
            resolveSeriesUrlFromEpisode(url)
        }

        // v24: collectImages puentea el poster de la ficha por el WebView.
        val document = dwwGet(seriesUrl, collectImages = true)

        // Extract title
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        // Extract poster image
        val poster = dwwBridgeImg(
            document.selectFirst(".thumb img")?.let { getImgSrc(it) }
                ?: document.selectFirst(".bigcontent .ts-post-image")?.let { getImgSrc(it) }
                ?: document.selectFirst(".ts-post-image")?.let { getImgSrc(it) }
        )

        // FIX 2026-07-29: Extraer la SINOPSIS real desde .bixbox.synp .entry-content.
        // Antes se usaba ".mindes, .alldes, .entry-content, .desc" pero:
        //  - ".mindes" NO existe (la clase real es ".mindesc" con 'c' al final)
        //  - ".desc" contiene el boilerplate SEO ("Watch streaming <title> English Subbed...")
        //  - ".entry-content" dentro de .bixbox.synp contiene la SINOPSIS narrativa
        // Ademas, el usuario pidio ELIMINAR el primer parrafo (boilerplate) y AGREGAR la sinopsis.
        // Solucion: tomar todos los <p> del .bixbox.synp .entry-content y unirlos con \n.
        // Si no hay synopsis, caer a los selectores legacy (que devuelven el boilerplate).
        val description = run {
            val synopsisBox = document.selectFirst(".bixbox.synp .entry-content")
                ?: document.selectFirst(".synp .entry-content")
                ?: document.selectFirst("h2:contains(Synopsis)")?.parent()?.selectFirst(".entry-content")
            if (synopsisBox != null) {
                val paragraphs = synopsisBox.select("p")
                if (paragraphs.isNotEmpty()) {
                    paragraphs.joinToString("\n\n") { p ->
                        // Salto de <br> tambien como salto de linea
                        p.html().replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                            .replace(Regex("""<[^>]+>"""), "")
                            .replace("&amp;", "&").replace("&#8217;", "'")
                            .replace("&#8230;", "...").replace("&quot;", "\"")
                            .replace("&nbsp;", " ").trim()
                    }.trim()
                } else {
                    synopsisBox.text().trim()
                }
            } else {
                // Fallback: selectores legacy (incluye el boilerplate .desc)
                document.selectFirst(".mindesc, .alldes, .entry-content, .desc")?.text()?.trim() ?: ""
            }
        }

        // Extract genres
        val genres = document.select(".genxed a, .series-gen a").mapNotNull { it.text().trim() }

        // Extract status
        val showStatus = document.selectFirst(".spe span:contains(Status)")?.nextElementSibling()?.text()?.trim()
            ?.let { stat ->
                when {
                    stat.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
                    stat.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
                    else -> null
                }
            }

        // Extract year from "Released:" field
        val year = document.selectFirst(".spe span:contains(Released)")?.nextElementSibling()?.text()?.trim()
            ?.take(4)?.toIntOrNull()

        // ===== v22 Metadatos adicionales: puntuación, duración, tipo y títulos alternativos =====
        // Rating: <div class="rating"><strong>Rating 9.2</strong>...<meta itemprop="ratingValue" content="9.2">
        val scoreText = document.selectFirst(".rating strong")?.text()?.trim()
            ?.substringAfter("Rating")?.trim()
            ?: document.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.trim()
        val score = scoreText?.toDoubleOrNull()

        // Duration: <b>Duration:</b> 7 min. per. ep
        val durationRaw = document.selectFirst(".spe span:contains(Duration)")?.text()?.trim()
        val durationMinutes = durationRaw?.let { parseDwwDuration(it) }

        // Type: <b>Type:</b> Donghua
        val typeText = document.selectFirst(".spe span:contains(Type)")?.text()?.trim()
            ?.substringAfter(":")?.trim() ?: ""

        // Títulos alternativos: <span class="alter">斗破苍穹 第二季, 斗破苍穹 第一季</span>
        val altTitle = document.selectFirst("span.alter")?.text()?.trim() ?: ""
        val plotPrefix = if (altTitle.isNotBlank()) {
            "Títulos alternativos: $altTitle\n\n"
        } else ""

        // Extract episodes from the episode list
        val episodes = mutableListOf<Episode>()
        val seenUrls = mutableSetOf<String>()

        // FIX 2026-07-28: Usar el contenedor .eplister (selector correcto del tema actual).
        // Antes se buscaba en .bxcl/.epl/.episodelist pero la clase real es .eplister.
        // Además, hay que evitar la zona .lastend (que contiene "First Episode"/"New Episode"
        // con texto confuso) y leer número/título desde .epl-num y .epl-title en lugar de
        // linkEl.text() que concatenaba todo y daba "New Episode Episode 678 (4K)".
        // FIX 2026-08-11: Admitir URLs que contengan "-movie-" ademas de "episode".
        // Series como "Perfect World Movie - Ninefold The Burning Sky" tienen episodios
        // cuyas URLs son /perfect-world-movie-ninefold-the-burning-sky-part-2-movie-4k-multi-subtitles/
        // (no contienen "episode"). Con el selector anterior, estos episodios no se
        // encontraban y la app mostraba "se ve la descripcion pero no hay video para reproducir".
        document.select(".eplister li a[href*=episode], .eplister li a[href*=-movie-]").forEach { linkEl ->
            val epUrl = linkEl.attr("abs:href")
            // Validar que la URL sea de episodio o de movie (no filtros)
            val isEpisodeUrl = epUrl.contains("episode", ignoreCase = true) || epUrl.contains("-movie-", ignoreCase = true)
            if (epUrl.isEmpty() || !isEpisodeUrl) return@forEach
            if (!seenUrls.add(epUrl)) return@forEach

            // Priorizar .epl-num y .epl-title (estructura actual del tema)
            val numText = linkEl.selectFirst(".epl-num")?.text()?.trim()
            val titleText = linkEl.selectFirst(".epl-title")?.text()?.trim()
            // FIX 2026-07-29: Si .epl-num no tiene numero (ej: "Series Haitus"),
            // caer a titleText que normalmente contiene "Episode X".
            // FIX 2026-08-11: extractEpisodeNumber ahora tambien soporta "Part X"
            // para movies (ej: "Perfect World Movie Part 2" → epNum=2).
            val epNum = numText?.let { extractEpisodeNumber(it) }
                ?: titleText?.let { extractEpisodeNumber(it) }
            val name = titleText ?: numText

            episodes.add(newEpisode(epUrl) {
                this.name = name?.takeIf { it.isNotEmpty() }
                this.episode = epNum
            })
        }

        // Method 2: Selectores legacy (.bxcl, .epl) por si el tema cambia de vuelta
        if (episodes.isEmpty()) {
            document.select(".bxcl a[href*=episode], .epl a[href*=episode], .episodelist a[href*=episode], .bxcl a[href*=-movie-], .epl a[href*=-movie-], .episodelist a[href*=-movie-]").forEach { linkEl ->
                val epUrl = linkEl.attr("abs:href")
                val isEpisodeUrl = epUrl.contains("episode", ignoreCase = true) || epUrl.contains("-movie-", ignoreCase = true)
                if (epUrl.isEmpty() || !isEpisodeUrl) return@forEach
                if (!seenUrls.add(epUrl)) return@forEach

                val epText = linkEl.text().trim()
                val epNum = extractEpisodeNumber(epText)

                episodes.add(newEpisode(epUrl) {
                    this.name = epText.takeIf { it.isNotEmpty() }
                    this.episode = epNum
                })
            }
        }

        // Method 3: Fallback - cualquier link de episodio o movie, EXCLUYENDO los de .lastend
        // (que tienen "New Episode"/"First Episode" y rompen la secuencia)
        if (episodes.isEmpty()) {
            document.select("a[href*=episode], a[href*=-movie-]").forEach { linkEl ->
                // Saltar si está dentro de .lastend (botón "New Episode"/"First Episode")
                if (linkEl.parents().any { it.hasClass("lastend") }) return@forEach

                val epUrl = linkEl.attr("abs:href")
                if (epUrl.isEmpty()) return@forEach
                if (!seenUrls.add(epUrl)) return@forEach

                val epText = linkEl.text().trim()
                // Limpiar prefijos "New Episode"/"First Episode" si aun llegaran
                val cleanText = epText
                    .replace(Regex("""^New\s+Episode\s*""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""^First\s+Episode\s*""", RegexOption.IGNORE_CASE), "")
                    .trim()
                val epNum = extractEpisodeNumber(cleanText)

                episodes.add(newEpisode(epUrl) {
                    this.name = cleanText.takeIf { it.isNotEmpty() }
                    this.episode = epNum
                })
            }
        }

        // ===== v22.2 Recomendaciones: temporadas del mismo nombre + similares =====
        val recommendations = extractDwwRecommendations(document, seriesUrl, title)

        // Sort episodes by number (ascending)
        val sortedEpisodes = episodes.sortedBy { it.episode ?: 0 }

        return newAnimeLoadResponse(title, seriesUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plotPrefix + description
            this.tags = genres
            this.showStatus = showStatus
            this.year = year
            this.score = Score.from10(score)
            if (durationMinutes != null && durationMinutes > 0) this.duration = durationMinutes
            this.episodes = mutableMapOf(DubStatus.Subbed to sortedEpisodes)
            if (recommendations.isNotEmpty()) this.recommendations = recommendations
        }
    }

    /**
     * v22: convierte "7 min. per. ep", "24 Min. Per Ep." a minutos.
     * Devuelve null si no es parseable (ej: texto vacío).
     */
    private fun parseDwwDuration(text: String): Int? {
        val t = text.trim()
        Regex("""(\d+)\s*(?:h|hr|hour)""", RegexOption.IGNORE_CASE).find(t)?.destructured?.component1()?.toIntOrNull()
            ?.let { hours ->
                val mins = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(t)?.destructured?.component1()?.toIntOrNull() ?: 0
                return hours * 60 + mins
            }
        return Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(t)?.destructured?.component1()?.toIntOrNull()
    }

    /** v22.2: normaliza un slug/título para comparar bases. */
    private fun dwwNormalize(t: String): String = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * v22.2: recomendaciones con la NOTA del usuario:
     * 1) Otras temporadas del mismo nombre (misma base de slug, p.ej.
     *    "jade-dynasty-season-4" -> base "jade-dynasty") usando el buscador ?s=;
     * 2) El resto: "Recommended Series" de la ficha + resultados del buscador,
     *    en orden aleatorio. Máximo 10 resultados.
     */
    private suspend fun extractDwwRecommendations(
        document: org.jsoup.nodes.Document,
        seriesUrl: String,
        seriesTitle: String
    ): List<SearchResponse> {
        val all = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        seen.add(seriesUrl)

        // Base del slug: /anime/jade-dynasty-season-4/ -> "jade-dynasty"
        val selfSlug = seriesUrl.trimEnd('/').substringAfterLast("/")
        val baseSlug = selfSlug
            .replace(Regex("-season-\\d+$"), "")
            .replace(Regex("-\\d+$"), "")
        val norm = dwwNormalize(seriesTitle)
        val baseNorm = Regex("\\s+(?:season\\s+)?\\d+$").replace(norm, "").trim()

        // 1) Temporadas del mismo nombre vía buscador
        val candidatePool = ArrayList<SearchResponse>()
        if (baseSlug.isNotBlank() && baseSlug != selfSlug) {
            try {
                val q = java.net.URLEncoder.encode(baseSlug.replace("-", " "), "UTF-8")
                val searchDoc = dwwGet("$mainUrl/?s=$q")
                data class SeasonCandidate(val url: String, val response: SearchResponse, val seasonNum: Int)
                val seasons = ArrayList<SeasonCandidate>()
                searchDoc.select("article").forEach { art ->
                    val parsed = parseArticleCard(art) ?: return@forEach
                    val slug = parsed.url.trimEnd('/').substringAfterLast("/")
                    if (slug in seen || !slug.startsWith(baseSlug)) return@forEach
                    seen.add(slug)
                    val num = Regex("(?:-season)?-(\\d+)$").find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    seasons.add(SeasonCandidate(slug, parsed, num))
                }
                seasons.sortedBy { it.seasonNum }.forEach { all.add(it.response) }
            } catch (_: Exception) {}
        }

        // 2) "Recommended Series" de la ficha (fuente de similares)
        val recommendedAnchor = document.select("h2, h3, .releases h3").firstOrNull {
            it.text().contains("Recommended", ignoreCase = true)
        }
        if (recommendedAnchor != null) {
            var container: org.jsoup.nodes.Element? = recommendedAnchor.parent()
            while (container != null && container.select("article").isEmpty()) {
                container = container.nextElementSibling()
            }
            container?.select("article")?.forEach { art ->
                val parsed = parseArticleCard(art) ?: return@forEach
                val key = parsed.url.trimEnd('/').substringAfterLast("/")
                if (key in seen) return@forEach
                seen.add(key)
                candidatePool.add(parsed)
            }
        }

        // 3) Si faltan, completar con resultados del buscador que no sean temporadas
        if (candidatePool.size < 16 && baseSlug.isNotBlank()) {
            try {
                val q = java.net.URLEncoder.encode(
                    (baseNorm.ifBlank { baseSlug.replace("-", " ") }).take(60), "UTF-8"
                )
                val searchDoc = dwwGet("$mainUrl/?s=$q")
                searchDoc.select("article").forEach { art ->
                    if (candidatePool.size >= 60) return@forEach
                    val parsed = parseArticleCard(art) ?: return@forEach
                    val slug = parsed.url.trimEnd('/').substringAfterLast("/")
                    if (slug in seen) return@forEach
                    seen.add(slug)
                    candidatePool.add(parsed)
                }
            } catch (_: Exception) {}
        }
        // v22.6: "Recommended Series" + buscador suelen dar < 16 => rellenar con una
        // página ALEATORIA del catálogo paginado /anime/page/N/ (misma mecánica que
        // DonghuaLife usa con /donghuas?page=N). Así las recomendaciones varían cada vez.
        if (candidatePool.size < 16) {
            try {
                val catDoc = dwwGet("$mainUrl/anime/")
                val lastPage = catDoc.select("a[href*=/anime/page/]")
                    .mapNotNull {
                        Regex("""/anime/page/(\d+)/""").find(it.attr("href"))
                            ?.groupValues?.get(1)?.toIntOrNull()
                    }
                    .maxOrNull() ?: 0
                val top = if (lastPage >= 2) minOf(lastPage, 80) else 30
                val rnd = java.util.Random()
                for (attempt in 1..3) {
                    if (candidatePool.size >= 16) break
                    val p = 2 + rnd.nextInt(maxOf(1, top - 1))
                    val pageDoc = dwwGet("$mainUrl/anime/page/$p/")
                    pageDoc.select("article").forEach { art ->
                        if (candidatePool.size >= 16) return@forEach
                        val parsed = parseArticleCard(art) ?: return@forEach
                        val slug = parsed.url.trimEnd('/').substringAfterLast("/")
                        if (slug in seen) return@forEach
                        seen.add(slug)
                        candidatePool.add(parsed)
                    }
                }
            } catch (_: Exception) {}
        }
        candidatePool.shuffle()
        all.addAll(candidatePool)
        return all.take(16)
    }

    /**
     * Given an episode page URL, load it and extract the series URL from the breadcrumb.
     * FIX 2026-07-28: Excluir URLs de filtro como /anime/?status=ongoing que aparecen
     * antes que la URL de la serie en el HTML. Solo aceptar URLs /anime/<slug>/.
     */
    private suspend fun resolveSeriesUrlFromEpisode(episodeUrl: String): String {
        return try {
            val epDoc = dwwGet(episodeUrl)
            // Buscar specifically /anime/<slug>/ (con path, no query string)
            epDoc.select("a[href*=/anime/]").firstOrNull { el ->
                val href = el.attr("abs:href")
                // Debe ser /anime/<slug>/, NO /anime/?status=... ni /anime/#...
                href.contains(Regex("""/anime/[a-z0-9][a-z0-9-]*/""", RegexOption.IGNORE_CASE))
            }?.attr("abs:href")?.takeIf { it.isNotEmpty() }
                ?: epDoc.select(".ts-breadcrumb a, .breadcrumb a, .breadcrumbs a").firstOrNull {
                    it.attr("abs:href").contains(Regex("""/anime/[a-z0-9][a-z0-9-]*/""", RegexOption.IGNORE_CASE))
                }?.attr("abs:href")
                ?: episodeUrl
        } catch (_: Exception) {
            episodeUrl
        }
    }

    /**
     * Extract episode number from text like "Episode 263" or "Episode 254-255".
     * FIX 2026-07-29: Para textos como "678 (4K)" o "2 (4K)" (sin "Episode"),
     * el fallback de digitos ahora toma el PRIMER match (no el ultimo).
     * Antes: "2 (4K)" → lastOrNull = "4" → epNum=4 (todos los episodios quedaban
     * con numero 4, CloudStream los deduplicaba y solo mostraba 1).
     * Ahora: "2 (4K)" → firstOrNull = "2" → epNum=2.
     *
     * FIX 2026-08-11: Soporte para "Part X" en movies.
     * Series como "Perfect World Movie - Ninefold The Burning Sky" tienen episodios
     * con .epl-num="Movie (4K)" y .epl-title="Perfect World Movie - ... Part 2 Movie (4K)".
     * El regex de "Episode" no hace match, y el fallback de digitos tomaría "4" (de "4K").
     * Ahora: priorizamos "Part X" antes del fallback de digitos, y eliminamos "4K"/"8K"
     * del texto antes de buscar digitos para evitar falsos positivos.
     */
    private fun extractEpisodeNumber(text: String): Int? {
        // 1. "Episode X" o "Episode X-Y"
        EPISODE_NUM_REGEX.find(text)?.groupValues?.get(1)?.let { numStr ->
            return numStr.substringBefore("-").toIntOrNull()
        }
        // 2. "Part X" (para movies divididos en partes)
        Regex("""Part\s+(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.let { partStr ->
            return partStr.toIntOrNull()
        }
        // 3. Fallback: primer digito, PERO primero eliminamos "4K"/"8K"/"2K" para evitar
        //    falsos positivos en textos como "Movie (4K)" → sin este paso, devolvería 4.
        val cleanedText = text.replace(Regex("""\d+[Kk]"""), "")
        return Regex("""(\d+)""").findAll(cleanedText).firstOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Get image src, handling lazy loading (data-src attribute).
     * FIX: Priorizar data-src sobre src (que puede ser placeholder base64)
     */
    private fun getImgSrc(imgEl: org.jsoup.nodes.Element): String {
        val dataSrc = imgEl.attr("data-src").trim()
        if (dataSrc.isNotEmpty() && !dataSrc.startsWith("data:")) return dataSrc
        return imgEl.attr("src").trim()
    }

    // ==================== VIDEO EXTRACTION ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = dwwGet(data)
        // v8: dedupe DM por episodio (el set es de instancia).
        dwEmittedDmVids.clear()

        val serverButtons = document.select("a[data-hash], .btn[data-hash], .server-item[data-hash]")
        if (serverButtons.isEmpty()) return false

        var darkServerUrl: String? = null
        var engSubUrl: String? = null

        for (btn in serverButtons) {
            val hash = btn.attr("data-hash") ?: continue
            val serverName = btn.text().trim()

            try {
                val decoded = String(Base64.decode(hash, Base64.DEFAULT), Charsets.UTF_8)
                val srcMatch = Regex("""src=["']([^"']+)["']""").find(decoded)
                val iframeSrc = srcMatch?.groupValues?.get(1) ?: continue

                when {
                    serverName.contains("Dark", ignoreCase = true) -> darkServerUrl = iframeSrc
                    serverName.contains("Eng-Sub", ignoreCase = true) ||
                        serverName.contains("Dailymotion", ignoreCase = true) ||
                        serverName.contains("DM Player", ignoreCase = true) ||
                        serverName.contains("DM", ignoreCase = true) -> engSubUrl = iframeSrc
                    // El texto del boton no es fiable (el sitio agreg6 "4K Player");
                    // si el iframe decodificado es de Dailymotion se pierde el
                    // mirror solo por no conocer la etiqueta.
                    iframeSrc.contains("dailymotion", ignoreCase = true) -> engSubUrl = iframeSrc
                }
            } catch (_: Exception) {
                // Skip invalid base64
            }
        }

        // Priority 1: Dark Server (Rumble CDN with multi-language subtitles including Spanish)
        if (darkServerUrl != null) {
            extractDarkServer(darkServerUrl, subtitleCallback, callback)
        }

        // Priority 2: Eng-Sub Player (Dailymotion fallback)
        if (engSubUrl != null) {
            extractDailymotion(engSubUrl, callback)
        }

        return darkServerUrl != null || engSubUrl != null
    }

    /**
     * Extract video and subtitles from the Dark Server (player.donghuaplanet.com).
     */
    private suspend fun extractDarkServer(
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(TAG, "extractDarkServer: fetching $playerUrl")
            val playerHtml = dwwFetch(playerUrl, referer = "$mainUrl/").html
            Log.d(TAG, "extractDarkServer: got ${playerHtml.length} chars")

            // === Extract Subtitle Tracks ===
            extractAndParseTracks(playerHtml, subtitleCallback)

            // Log if no subtitles were found for debugging
            // (subtitleCallback invocations are counted inside extractAndParseTracks)

            // === Extract Video Sources ===
            extractAndParseSources(playerHtml, callback)

        } catch (e: Exception) {
            Log.w(TAG, "extractDarkServer failed for $playerUrl: ${e.message}")
        }
    }

    /**
     * Extract and parse subtitle tracks from the player HTML.
     * FIX 2026-08-22: Rewritten for robustness.
     *   - Multiple regex patterns to find the tracks array (not just const/var/let tracks =)
     *   - Flexible track object parsing (any property order, extra properties allowed)
     *   - Expanded language matching ("Español", "es", "Spanish", "English", etc.)
     *   - Extensive logging for debugging
     */
    private fun extractAndParseTracks(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        var emittedCount = 0

        // --- Step 1: Find the tracks array using multiple patterns ---
        val tracksStr = findTracksArray(html)
        if (tracksStr == null) {
            Log.w(TAG, "extractAndParseTracks: NO tracks array found, trying fallback methods")
            // Fallback: look for <track> HTML5 tags
            emittedCount += extractHtml5Tracks(html, subtitleCallback)
            // Fallback: look for .vtt URLs directly
            emittedCount += extractVttUrls(html, subtitleCallback)
            if (emittedCount == 0) {
                // Log context around 'tracks' keyword for debugging
                val idx = html.indexOf("tracks")
                if (idx != -1) {
                    Log.d(TAG, "extractAndParseTracks: 'tracks' keyword found at pos $idx: " +
                        html.substring(maxOf(0, idx - 60), minOf(html.length, idx + 200)).replace("\n", " "))
                } else {
                    Log.d(TAG, "extractAndParseTracks: 'tracks' keyword NOT found in HTML at all")
                }
            }
            return
        }

        Log.d(TAG, "extractAndParseTracks: found tracks array, length=${tracksStr.length}")

        // --- Step 2: Parse individual track objects (flexible) ---
        // Match each {...} object in the array
        val trackObjRegex = Regex("""\{[^{}]*\}""")
        for (objMatch in trackObjRegex.findAll(tracksStr)) {
            val obj = objMatch.value

            // Extract file/src/url property
            val fileMatch = Regex(""""(?:file|src|url)"\s*:\s*"([^"]+)""").find(obj)
            // Extract label/language/lang property
            val labelMatch = Regex(""""(?:label|language|lang|name)"\s*:\s*"([^"]+)""").find(obj)

            if (fileMatch == null || labelMatch == null) {
                Log.d(TAG, "extractAndParseTracks: track object missing file or label: ${obj.take(100)}")
                continue
            }

            val file = fileMatch.groupValues[1]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
            val label = labelMatch.groupValues[1]

            Log.d(TAG, "extractAndParseTracks: found track label=\"$label\" url=\"$file\"")

            // --- Step 3: Check if label matches Spanish or English ---
            val normalizedLang = normalizeSubtitleLanguage(label)
            if (normalizedLang != null) {
                Log.d(TAG, "extractAndParseTracks: EMITTING subtitle lang=$normalizedLang url=$file")
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = normalizedLang,
                        url = file
                    )
                )
                emittedCount++
            } else {
                Log.d(TAG, "extractAndParseTracks: skipping track label=\"$label\" (not Spanish/English)")
            }
        }

        Log.d(TAG, "extractAndParseTracks: total subtitles emitted: $emittedCount")
    }

    /**
     * Try multiple patterns to find the tracks array in player HTML.
     */
    private fun findTracksArray(html: String): String? {
        // Pattern 1: const/var/let tracks = [...];  (original JavaScript format)
        Regex("""(?:const|var|let)\s+tracks\s*=\s*(\[[\s\S]*?\])\s*;""").find(html)?.let {
            Log.d(TAG, "findTracksArray: matched Pattern 1 (const/var/let tracks = [...])")
            return it.groupValues[1]
        }

        // Pattern 2: tracks = [...];  (without const/var/let keyword)
        Regex("""(?:^|[;\n\s])tracks\s*=\s*(\[[\s\S]*?\])\s*;""").find(html)?.let {
            Log.d(TAG, "findTracksArray: matched Pattern 2 (tracks = [...])")
            return it.groupValues[1]
        }

        // Pattern 3: "tracks": [...]  (JSON property format)
        Regex(""""tracks"\s*:\s*(\[[\s\S]*?\])""").find(html)?.let {
            Log.d(TAG, "findTracksArray: matched Pattern 3 (\"tracks\": [...])")
            return it.groupValues[1]
        }

        // Pattern 4: subtitleTracks / captions / textTracks (alternative variable names)
        for (varName in listOf("subtitleTracks", "captions", "textTracks", "subtitles")) {
            Regex("""(?:const|var|let)\s+${varName}\s*=\s*(\[[\s\S]*?\])\s*;""").find(html)?.let {
                Log.d(TAG, "findTracksArray: matched Pattern 4 ($varName = [...])")
                return it.groupValues[1]
            }
        }

        return null
    }

    /**
     * Fallback: extract subtitles from HTML5 <track> tags.
     */
    private fun extractHtml5Tracks(html: String, subtitleCallback: (SubtitleFile) -> Unit): Int {
        var count = 0
        val trackTagRegex = Regex(
            """<track[^>]+src=["']([^"']+)["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        // Also try reversed attribute order: label before src
        val trackTagRegex2 = Regex(
            """<track[^>]+label=["']([^"']+)["'][^>]+src=["']([^"']+)["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )

        // Try label-then-src order
        for (match in trackTagRegex2.findAll(html)) {
            val label = match.groupValues[1]
            val src = match.groupValues[2].replace("&amp;", "&")
            val lang = normalizeSubtitleLanguage(label)
            if (lang != null) {
                Log.d(TAG, "extractHtml5Tracks: EMITTING from <track> tag lang=$lang url=$src")
                subtitleCallback.invoke(SubtitleFile(lang = lang, url = src))
                count++
            }
        }

        // Try src-then-label order (only if no tracks found with the other pattern)
        if (count == 0) {
            for (match in trackTagRegex.findAll(html)) {
                val src = match.groupValues[1].replace("&amp;", "&")
                // Extract label from the same tag
                val labelMatch = Regex("""label=["']([^"']+)['"]""", RegexOption.IGNORE_CASE).find(match.value)
                val label = labelMatch?.groupValues?.get(1) ?: continue
                val lang = normalizeSubtitleLanguage(label)
                if (lang != null) {
                    Log.d(TAG, "extractHtml5Tracks: EMITTING from <track> tag (src-first) lang=$lang url=$src")
                    subtitleCallback.invoke(SubtitleFile(lang = lang, url = src))
                    count++
                }
            }
        }

        if (count > 0) Log.d(TAG, "extractHtml5Tracks: found $count subtitle tracks from <track> tags")
        return count
    }

    /**
     * Fallback: find .vtt subtitle URLs directly in the HTML.
     */
    private fun extractVttUrls(html: String, subtitleCallback: (SubtitleFile) -> Unit): Int {
        var count = 0
        val vttRegex = Regex("""["'](https?://[^"']*\.(?:vtt|srt)[^"']*)["']""")
        for (match in vttRegex.findAll(html)) {
            val url = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
            // Try to guess language from URL path (e.g., /es/ or /spanish/ or ?lang=es)
            val lang = guessLanguageFromUrl(url)
            Log.d(TAG, "extractVttUrls: found VTT/SRT url=$url guessedLang=$lang")
            if (lang != null) {
                subtitleCallback.invoke(SubtitleFile(lang = lang, url = url))
                count++
            } else {
                // If we can't guess the language, include it as Spanish (primary)
                // since this is a Spanish-subtitle focused extension
                Log.d(TAG, "extractVttUrls: including unknown-language VTT as Spanish: $url")
                subtitleCallback.invoke(SubtitleFile(lang = "Spanish", url = url))
                count++
            }
        }
        if (count > 0) Log.d(TAG, "extractVttUrls: found $count VTT/SRT subtitle URLs")
        return count
    }

    /**
     * Normalize a subtitle label to a standard language name.
     * Returns null if the label doesn't match Spanish or English.
     */
    private fun normalizeSubtitleLanguage(label: String): String? {
        val lower = label.lowercase().trim()

        // Check Spanish
        if (lower in SPANISH_KEYWORDS ||
            SPANISH_KEYWORDS.any { lower.contains(it) } ||
            lower == "es" || lower.startsWith("es-")
        ) {
            return "Spanish"
        }

        // Check English
        if (lower in ENGLISH_KEYWORDS ||
            ENGLISH_KEYWORDS.any { lower.contains(it) } ||
            lower == "en" || lower.startsWith("en-")
        ) {
            return "English"
        }

        // Exact match with original set (backward compat)
        if (label in SUBTITLE_LANGUAGES) return label

        return null
    }

    /**
     * Try to guess the language from a subtitle URL path/query.
     */
    private fun guessLanguageFromUrl(url: String): String? {
        val lower = url.lowercase()
        // Check path segments
        val spanishPath = listOf("/es/", "/esp/", "/spanish/", "/espanol/", "/espa\u00f1ol/", "/latino/", "/latam/")
        val englishPath = listOf("/en/", "/eng/", "/english/")

        for (seg in spanishPath) { if (seg in lower) return "Spanish" }
        for (seg in englishPath) { if (seg in lower) return "English" }

        // Check query parameters
        val langMatch = Regex("""[?&](?:lang|language|l)\s*=\s*([^&\s]+)""").find(url)
        if (langMatch != null) {
            val langVal = langMatch.groupValues[1].lowercase()
            if (langVal.startsWith("es") || langVal.contains("spanish")) return "Spanish"
            if (langVal.startsWith("en") || langVal.contains("english")) return "English"
        }

        return null
    }

    /**
     * Extract and parse video sources from the player HTML.
     * FIX 2026-07-28: Marcar como M3U8/HLS los archivos .tar?r_file=chunklist.m3u8.
     * FIX 2026-08-21: El player ahora usa JavaScript (const sources = [...]) en vez de
     * formato JSON (sources: [...]). Ademas, tracks se declara ANTES que sources.
     * Nuevo regex acepta tanto : como = y no depende del orden con tracks.
     */
    private suspend fun extractAndParseSources(html: String, callback: (ExtractorLink) -> Unit) {
        // FIX: El player usa "const sources = [...]" (JavaScript) no "sources: [...]" (JSON).
        // Ademas, tracks puede declararse antes o despues de sources.
        val sourcesMatch = Regex("""sources\s*[:=]\s*(\[[\s\S]*?\])\s*[;,]""").find(html)
        if (sourcesMatch != null) {
            Log.d(TAG, "extractAndParseSources: found sources block")
        } else {
            Log.w(TAG, "extractAndParseSources: NO sources block found")
            return
        }

        val sourcesStr = sourcesMatch.groupValues[1]

        val sourcePattern = Regex(
            """\{\s*"file"\s*:\s*"([^"]+)"\s*,\s*"type"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"\s*\}"""
        )

        var hasHlsMaster = false

        sourcePattern.findAll(sourcesStr).forEach { match ->
            val file = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
            val type = match.groupValues[2]
                .replace("\\/", "/")
            val label = match.groupValues[3]

            when {
                // HLS master playlist (type=application/x-mpegURL)
                type.contains("mpegURL", ignoreCase = true) -> {
                    hasHlsMaster = true
                    callback.invoke(
                        newExtractorLink(
                            source = "Dark Server",
                            name = "Dark Server (Auto)",
                            url = file,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$PLAYER_BASE/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                // FIX: Rumble CDN quality-specific HLS streams (.tar?r_file=chunklist.m3u8)
                // NO son MP4 reales: son contenedores TAR con un chunklist HLS dentro.
                // Marcarlos como M3U8 evita el error "parsing_container_unsupported(3003)".
                file.contains("r_file=") || file.contains("chunklist.m3u8") -> {
                    val quality = parseQualityFromLabel(label)
                    callback.invoke(
                        newExtractorLink(
                            source = "Dark Server",
                            name = "Dark Server ($label)",
                            url = file,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$PLAYER_BASE/"
                            this.quality = quality
                        }
                    )
                }
                // Regular MP4 direct links
                type.contains("mp4", ignoreCase = true) -> {
                    val quality = parseQualityFromLabel(label)
                    callback.invoke(
                        newExtractorLink(
                            source = "Dark Server",
                            name = "Dark Server ($label)",
                            url = file,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$PLAYER_BASE/"
                            this.quality = quality
                        }
                    )
                }
            }
        }

        // Fallback: if no sources parsed, try to find HLS URL directly
        if (!hasHlsMaster) {
            val hlsMatch = Regex("""["'](https://rumble\.com/hls-vod/[^"']+\.m3u8[^"']*)["']""").find(html)
                ?: Regex("""["'](https://[^"']*\.m3u8[^"']*)["']""").find(html)

            if (hlsMatch != null) {
                val hlsUrl = hlsMatch.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")

                callback.invoke(
                    newExtractorLink(
                        source = "Dark Server",
                        name = "Dark Server",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$PLAYER_BASE/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }

    /**
     * Parse quality label string to quality integer value.
     */
    private fun parseQualityFromLabel(label: String): Int {
        return when {
            label.contains("4K", ignoreCase = true) ||
                label.contains("2160", ignoreCase = true) -> Qualities.P2160.value
            label.contains("1440", ignoreCase = true) -> Qualities.P1440.value
            label.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            label.contains("720", ignoreCase = true) -> Qualities.P720.value
            label.contains("480", ignoreCase = true) -> Qualities.P480.value
            label.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /**
     * Extract video from Dailymotion embed URL (Eng-Sub Player).
     */
    /**
     * Extract video from Dailymotion embed URL (Eng-Sub Player).
     *
     * v27: delega en el extractor COMÚN (fixes de audio + dedupe + subs).
     */
    private suspend fun extractDailymotion(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val videoId = embedUrl.substringAfter("video=", "").substringBefore("&").substringBefore("#")
            if (videoId.isNotEmpty()) {
                CE.extractDailymotion(
                    "https://www.dailymotion.com/embed/video/$videoId",
                    "$mainUrl/",
                    "Eng-Sub Player",
                    null,
                    dwEmittedDmSubs,
                    dwEmittedDmVids,
                    { },
                    callback
                )
            }
        } catch (_: Exception) {
            // Dailymotion extraction failed
        }
    }

    /** Dedupe de videos/subs de DM entre todos los embeds del episodio. */
    private val dwEmittedDmSubs = mutableSetOf<String>()
    private val dwEmittedDmVids = mutableSetOf<String>()
}
