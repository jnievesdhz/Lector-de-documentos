package com.jnieves.lector

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.jnieves.lector.office.CsvToHtml
import com.jnieves.lector.office.DocxToHtml
import com.jnieves.lector.office.PptxToHtml
import com.jnieves.lector.office.XlsxToHtml
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Muestra un documento. Recibe archivos desde la pantalla de inicio, "Abrir con" o "Compartir". */
class ViewerActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var pageLabel: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideLabel = Runnable { pageLabel.animate().alpha(0f).setDuration(250).start() }

    // Copia local del documento abierto
    @Volatile private var docDir: File? = null
    @Volatile private var docFile: File? = null
    @Volatile private var docName: String = "archivo"
    @Volatile private var docMime: String = "application/octet-stream"
    @Volatile private var docKind: Kind? = null

    // Entrada del historial (recientes) y miniatura
    @Volatile private var entryId: String? = null
    @Volatile private var wantCapture = false

    // Barras del visor
    private lateinit var topBox: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var moonBtn: ImageView
    private lateinit var shareBtn: ImageView
    private lateinit var moreBtn: ImageView
    private lateinit var bottomBar: LinearLayout
    private lateinit var zoomLabel: TextView
    private lateinit var pageBtnText: TextView
    private val pageViews = ArrayList<View>()
    private var barsShown = true
    private var dark = true
    @Volatile private var displayName = ""
    @Volatile private var pagesTotal = 0
    private var currentPage = 1

    // Posición de lectura (0 = inicio, 1 = final), para volver al mismo punto al girar el teléfono
    @Volatile private var scrollFrac = 0f
    private var restoreFrac: Float? = null

    // PDF
    private val pdfLock = Any()
    private var pdfFd: ParcelFileDescriptor? = null
    private var pdf: PdfRenderer? = null
    @Volatile private var pageCount = 0
    @Volatile private var renderWidth = 1600
    @Volatile private var closed = false
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = ArrayDeque<Int>()
    private val requested = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Docs.cleanOld(this)
        dark = getSharedPreferences("ajustes", MODE_PRIVATE).getBoolean("docs_oscuros", true)

        webView = WebView(this)
        configureWebView(webView)

        pageLabel = TextView(this).apply {
            setTextColor(Ui.TEXT)
            textSize = 14f
            typeface = Ui.BOLD
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = Ui.round(this@ViewerActivity, Ui.SURFACE, 12f, Ui.BORDER, 1f)
            alpha = 0f
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            pageLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(24) }
        )
        buildTopBar(root)
        buildBottomBar(root)
        setContentView(root)

        // Al girar el teléfono la pantalla se vuelve a crear: se reutiliza lo ya convertido
        if (savedInstanceState != null && restore(savedInstanceState)) return

        val uri = incomingUri(intent)
        if (uri == null) {
            showMessage("No se recibió ningún archivo.")
            return
        }
        showMessage("Abriendo…")
        Thread { load(uri) }.start()
    }

    // ------------------------------------------------------- girar pantalla

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val dir = docDir ?: return
        val file = docFile ?: return
        val kind = docKind ?: return
        outState.putString("dir", dir.path)
        outState.putString("file", file.path)
        outState.putString("name", docName)
        outState.putString("mime", docMime)
        outState.putString("kind", kind.name)
        outState.putString("title", displayName)
        outState.putInt("pages", pagesTotal)
        outState.putBoolean("bars", barsShown)
        outState.putInt("rw", renderWidth)
        outState.putFloat("frac", scrollFrac)
        outState.putString("entry", entryId)
    }

    private fun restore(b: Bundle): Boolean {
        val dir = b.getString("dir")?.let { File(it) } ?: return false
        val file = b.getString("file")?.let { File(it) } ?: return false
        val html = File(dir, "vista.html")
        if (!html.exists() || !file.exists()) return false
        val kind = b.getString("kind")?.let { k -> Kind.values().firstOrNull { it.name == k } } ?: return false
        docDir = dir
        docFile = file
        docName = b.getString("name") ?: file.name
        docMime = b.getString("mime") ?: docMime
        docKind = kind
        renderWidth = b.getInt("rw", renderWidth)
        restoreFrac = b.getFloat("frac", 0f)
        entryId = b.getString("entry")
        displayName = b.getString("title") ?: docName
        pagesTotal = b.getInt("pages", 0)
        barsShown = b.getBoolean("bars", true)
        if (!barsShown) {
            topBox.post { applyBars(false, false) }
        }
        updateTopBar()
        if (kind == Kind.PDF) {
            Thread {
                val ok = try {
                    openRenderer(file) != null
                } catch (e: Throwable) {
                    false
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        if (ok) webView.loadUrl(Uri.fromFile(html).toString())
                        else showMessage("No se pudo abrir el archivo.")
                    }
                }
            }.start()
        } else {
            webView.loadUrl(Uri.fromFile(html).toString())
        }
        return true
    }

    // ---------------------------------------------------------------- carga

    private fun incomingUri(i: Intent): Uri? {
        if (i.action == Intent.ACTION_SEND) {
            streamExtra(i)?.let { return it }
            val clip = i.clipData
            if (clip != null && clip.itemCount > 0) return clip.getItemAt(0).uri
            return null
        }
        return i.data
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    private fun load(uri: Uri) {
        try {
            val name = Docs.displayName(this, uri)
            var mime = contentResolver.getType(uri) ?: Docs.mimeFromName(name)
            if (mime == "application/octet-stream") mime = Docs.mimeFromName(name)

            val dir = Docs.newDocDir(this)
            val file = File(dir, Docs.safeFileName(name, mime))
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            } ?: throw IOException("No se pudo leer el archivo")

            docDir = dir
            docFile = file
            docName = file.name
            docMime = mime
            displayName = name
            docKind = Docs.kindFor(mime, file.name)
            // Anota el archivo en Recientes (si llegó desde otra app, guarda una copia para reabrirlo)
            entryId = try {
                Library.record(this, uri, name, mime, file, intent.getStringExtra(EXTRA_ENTRY))
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (!isDestroyed) {
                    displayName = name
                    updateTopBar()
                }
            }

            val kind = Docs.kindFor(mime, file.name)
            docKind = kind
            if ((kind == Kind.DOCX || kind == Kind.XLSX || kind == Kind.PPTX) && isOleFile(file)) {
                showMessageAsync(
                    "Este archivo está protegido con contraseña o usa un formato antiguo.<br><br>" +
                        "Por ahora la app no puede abrirlo. Puedes compartirlo o abrirlo con otra app desde el menú."
                )
                return
            }
            wantCapture = entryId != null && kind in CAPTURE_KINDS
            when (kind) {
                Kind.PDF -> openPdf(file, dir)
                Kind.DOCX -> writeAndLoad(dir, page(DocxToHtml.convert(file, dir), "", "docx"))
                Kind.XLSX -> writeAndLoad(
                    dir, page(XlsxToHtml.convert(file), Html.SHEET_SCRIPT, "sheet", "0.25")
                )
                Kind.CSV -> writeAndLoad(
                    dir, page(CsvToHtml.convert(readText(file).first), Html.SHEET_SCRIPT, "sheet", "0.25")
                )
                Kind.PPTX -> writeAndLoad(
                    dir, page(PptxToHtml.convert(file, dir), Html.SLIDES_SCRIPT, "pptx")
                )
                Kind.OLD_OFFICE -> showMessageAsync(
                    "Este es un formato antiguo de Office (.doc, .xls o .ppt) que la app no puede leer.<br><br>" +
                        "Si puedes, guárdalo como .docx, .xlsx o .pptx. También puedes abrirlo con otra app desde el menú."
                )
                Kind.IMAGE -> openImage(file, dir)
                Kind.TEXT -> openText(file, dir)
                Kind.OTHER -> showMessageAsync(
                    "Este tipo de archivo todavía no se puede ver aquí.<br><br>" +
                        "Puedes compartirlo o abrirlo con otra app desde el menú de arriba."
                )
            }
        } catch (e: SecurityException) {
            showMessageAsync("No hay permiso para leer este archivo.")
        } catch (e: Throwable) {
            showMessageAsync(
                "No se pudo abrir el archivo.<br><br><small>" +
                    Docs.esc(e.message ?: e.javaClass.simpleName) + "</small>"
            )
        }
    }

    // ------------------------------------------------------------------ PDF

    /** Abre el PDF y devuelve el tamaño de cada página (null si la pantalla ya se cerró). */
    private fun openRenderer(file: File): List<IntArray>? {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(fd)
        } catch (e: Exception) {
            fd.close()
            throw e
        }
        val sizes = ArrayList<IntArray>()
        synchronized(pdfLock) {
            if (closed) {
                renderer.close()
                fd.close()
                return null
            }
            pdf = renderer
            pdfFd = fd
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { p -> sizes.add(intArrayOf(p.width, p.height)) }
            }
            pageCount = renderer.pageCount
            pagesTotal = renderer.pageCount
        }
        return sizes
    }

    private fun openPdf(file: File, dir: File) {
        val sizes = try {
            openRenderer(file)
        } catch (e: SecurityException) {
            showMessageAsync(
                "Este PDF está protegido con contraseña.<br>" +
                    "Por ahora la app no puede abrirlo."
            )
            return
        } catch (e: IOException) {
            showMessageAsync("El PDF parece estar dañado y no se pudo abrir.")
            return
        } ?: return
        if (sizes.isEmpty()) {
            showMessageAsync("El PDF no tiene páginas.")
            return
        }

        entryId?.let { makePdfThumb(it) }

        val dm = resources.displayMetrics
        renderWidth = (min(dm.widthPixels, dm.heightPixels) * 2).coerceIn(1200, 2200)

        val sb = StringBuilder()
        sizes.forEachIndexed { i, s ->
            sb.append("<div class=\"pg\" id=\"p").append(i)
                .append("\" data-i=\"").append(i)
                .append("\" style=\"aspect-ratio:").append(s[0].coerceAtLeast(1))
                .append('/').append(s[1].coerceAtLeast(1))
                .append("\"><img alt=\"\"></div>")
        }
        writeAndLoad(dir, page(sb.toString(), Html.PDF_SCRIPT, "pdf"))
    }

    /** Puente que el JavaScript de la página usa para pedir páginas. */
    inner class Bridge {
        @JavascriptInterface
        fun render(i: Int) {
            synchronized(pending) {
                if (!requested.add(i)) return
                pending.addLast(i)
            }
            try {
                worker.execute { renderNext() }
            } catch (_: RejectedExecutionException) {
            }
        }

        @JavascriptInterface
        fun onScrollFrac(f: Float) {
            scrollFrac = f
        }

        @JavascriptInterface
        fun onPage(n: Int) {
            runOnUiThread { if (!isDestroyed) showPageNumber(n) }
        }

        @JavascriptInterface
        fun setPageCount(n: Int) {
            runOnUiThread {
                if (!isDestroyed) {
                    pagesTotal = n
                    updateTopBar()
                }
            }
        }

        /** 1 = mostrar barras (se llegó arriba), 0 = esconderlas (se está bajando). */
        @JavascriptInterface
        fun onBars(v: Int) {
            runOnUiThread { if (!isDestroyed) applyBars(v == 1, true) }
        }

        @JavascriptInterface
        fun onTap() {
            runOnUiThread { if (!isDestroyed) applyBars(!barsShown, true) }
        }
    }

    /** Dibuja primero la página pedida más recientemente (la que el usuario está viendo). */
    private fun renderNext() {
        val i = synchronized(pending) { pending.removeLastOrNull() } ?: return
        val dir = docDir ?: return
        val pagesDir = File(dir, "pages")
        pagesDir.mkdirs()
        val out = File(pagesDir, "p$i.jpg")
        val ok = out.exists() || renderPage(i, out)
        if (!ok) synchronized(pending) { requested.remove(i) }
        val js = if (ok) "pageReady($i,'pages/p$i.jpg')" else "pageFailed($i)"
        runOnUiThread { if (!isDestroyed) webView.evaluateJavascript(js, null) }
    }

    private fun renderPage(i: Int, out: File): Boolean = synchronized(pdfLock) {
        val r = pdf
        if (r == null || closed || i < 0 || i >= r.pageCount) {
            false
        } else {
            try {
                r.openPage(i).use { page ->
                    var w = renderWidth
                    var h = (page.height.toLong() * w / page.width.coerceAtLeast(1))
                        .toInt().coerceAtLeast(1)
                    val maxPixels = 9_000_000L
                    if (w.toLong() * h > maxPixels) {
                        val s = sqrt(maxPixels.toDouble() / (w.toLong() * h))
                        w = (w * s).toInt().coerceAtLeast(1)
                        h = (h * s).toInt().coerceAtLeast(1)
                    }
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val tmp = File(out.path + ".tmp")
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    bmp.recycle()
                    tmp.renameTo(out)
                }
            } catch (e: Throwable) {
                false
            }
        }
    }

    /** Miniatura de la primera página para la cuadrícula de Recientes. */
    private fun makePdfThumb(id: String) {
        try {
            synchronized(pdfLock) {
                val r = pdf ?: return
                if (closed || r.pageCount == 0) return
                r.openPage(0).use { p ->
                    val h = 300
                    val w = (p.width.toLong() * h / p.height.coerceAtLeast(1)).toInt().coerceIn(1, 600)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    Library.saveThumb(this, id, bmp)
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Miniatura de imagen pequeña. */
    private fun makeImageThumb(id: String, file: File) {
        try {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val s = info.size
                val scale = 400f / max(s.width, s.height).coerceAtLeast(1)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (s.width * scale).toInt().coerceAtLeast(1),
                        (s.height * scale).toInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            Library.saveThumb(this, id, bmp)
        } catch (_: Throwable) {
        }
    }

    /** Toma una foto de la parte de arriba del documento ya dibujado (Word, Excel, PowerPoint, texto). */
    private fun captureThumb() {
        val id = entryId ?: return
        if (isDestroyed || isFinishing) return
        val w = webView.width
        val h = min(webView.height, (w * 0.72f).toInt())
        if (w <= 0 || h <= 0) return
        val loc = IntArray(2)
        webView.getLocationInWindow(loc)
        val outW = 400
        val outH = (outW * h / w.toFloat()).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                window,
                Rect(loc[0], loc[1], loc[0] + w, loc[1] + h),
                bmp,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        Thread { Library.saveThumb(this, id, bmp) }.start()
                    } else {
                        bmp.recycle()
                    }
                },
                handler
            )
        } catch (_: Throwable) {
            bmp.recycle()
        }
    }

    private fun showPageNumber(n: Int) {
        currentPage = n
        if (pagesTotal <= 1) return
        pageBtnText.text = "$n / $pagesTotal"
        // Con las barras visibles el número ya está en la barra de abajo
        if (barsShown) return
        pageLabel.text = "$n / $pagesTotal"
        pageLabel.animate().cancel()
        pageLabel.alpha = 1f
        handler.removeCallbacks(hideLabel)
        handler.postDelayed(hideLabel, 1500)
    }

    // ------------------------------------------------------ imagen y texto

    private fun openImage(file: File, dir: File) {
        var shown = file
        // El WebView no sabe mostrar HEIC (fotos de algunos celulares): se convierte a JPG.
        if (Docs.isHeif(docMime, file.name)) {
            val converted = File(dir, "imagen.jpg")
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            FileOutputStream(converted).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            shown = converted
        }
        entryId?.let { makeImageThumb(it, shown) }
        val body = "<div class=\"imgwrap\"><img src=\"" + Uri.encode(shown.name) + "\" alt=\"\"></div>"
        writeAndLoad(dir, page(body, "", "img"))
    }

    /** Archivos de Office cifrados con contraseña (y los .doc/.xls viejos) empiezan con estos bytes. */
    private fun isOleFile(file: File): Boolean {
        val head = ByteArray(4)
        val n = file.inputStream().use { it.read(head) }
        return n == 4 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() &&
            head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()
    }

    private fun openText(file: File, dir: File) {
        val (text, truncated) = readText(file)
        val note = if (truncated) {
            "<div class=\"msg\">El archivo es muy grande. Se muestran los primeros 8 MB.</div>"
        } else ""
        writeAndLoad(dir, page("<pre>" + Docs.esc(text) + "</pre>" + note, "", "txt"))
    }

    /** Lee hasta 8 MB de texto, en UTF-8 o, si no cuadra, en la codificación de Windows. */
    private fun readText(file: File): Pair<String, Boolean> {
        val max = 8L * 1024 * 1024
        val size = min(file.length(), max).toInt()
        val bytes = ByteArray(size)
        var read = 0
        file.inputStream().use { input ->
            while (read < size) {
                val n = input.read(bytes, read, size - read)
                if (n < 0) break
                read += n
            }
        }
        val data = if (read < size) bytes.copyOf(read) else bytes
        var text = String(data, Charsets.UTF_8)
        if (text.contains('\uFFFD')) text = String(data, Charset.forName("windows-1252"))
        text = text.removePrefix("\uFEFF")
        return Pair(text, file.length() > max)
    }

    // ------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            // Permite que las páginas locales carguen las fuentes incluidas en la app
            allowFileAccessFromFileURLs = true
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            blockNetworkLoads = true
            textZoom = 100
        }
        w.addJavascriptInterface(Bridge(), "Android")
        w.webViewClient = object : WebViewClient() {
            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                val pct = (newScale / resources.displayMetrics.density * 100f).roundToInt()
                zoomLabel.text = "$pct %"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && url.startsWith("file:")) {
                    // Al volver a cargar (por ejemplo al girar) se aplica el estado actual
                    view.evaluateJavascript(
                        "window.setDark&&setDark(" + (if (dark) 1 else 0) + ");" +
                            "window.setBars&&setBars(" + (if (barsShown) 1 else 0) + ");", null
                    )
                    bottomBar.visibility = View.VISIBLE
                    applyWebBg()
                }
                if (wantCapture && url != null && url.startsWith("file:")) {
                    wantCapture = false
                    view.postDelayed({ captureThumb() }, 1500)
                }
                val f = restoreFrac
                if (f != null && url != null && url.startsWith("file:")) {
                    restoreFrac = null
                    view.postDelayed({
                        if (!isDestroyed) {
                            view.evaluateJavascript(
                                "(function(){var h=document.documentElement.scrollHeight-window.innerHeight;" +
                                    "window.scrollTo(0,Math.max(0,h*" + f + "));})()", null
                            )
                        }
                    }, 150)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "file") return false
                // Los enlaces de los documentos se abren en el navegador del teléfono
                val scheme = url.scheme ?: ""
                if (scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (_: Exception) {
                        Toast.makeText(this@ViewerActivity, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
        }
        w.setBackgroundColor(Ui.BG)
    }

    private fun writeAndLoad(dir: File, html: String) {
        val f = File(dir, "vista.html")
        f.writeText(html)
        runOnUiThread {
            if (!isDestroyed) {
                applyWebBg()
                updateTopBar()
                webView.loadUrl(Uri.fromFile(f).toString())
            }
        }
    }

    /** Página HTML del documento con las clases del visor (barras y modo oscuro). */
    private fun page(body: String, script: String, cls: String, minScale: String = "1"): String {
        var c = "$cls bars"
        if (dark && cls in DARK_CLASSES) c += " dark"
        return Html.page(body, script, c, minScale)
    }

    private fun showMessage(html: String) {
        bottomBar.visibility = View.GONE
        applyBars(true, false)
        webView.setBackgroundColor(Ui.BG)
        webView.loadDataWithBaseURL(null, Html.message(html), "text/html", "utf-8", null)
    }

    // ------------------------------------------------------------ barras

    private fun buildTopBar(root: FrameLayout) {
        topBox = Ui.column(this).apply { setBackgroundColor(Ui.SURFACE) }
        val bar = Ui.row(this).apply { setPadding(dp(4), dp(6), dp(4), dp(6)) }
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, "Volver", Ui.TEXT_SOFT) { finish() })
        val texts = Ui.column(this)
        titleView = Ui.singleLine(Ui.text(this, "Abriendo…", 15f, Ui.TEXT, bold = true))
        subtitleView = Ui.singleLine(Ui.text(this, "", 12f, Ui.MUTED))
        texts.addView(titleView, Ui.lp(Ui.MATCH, Ui.WRAP))
        texts.addView(subtitleView, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(1) })
        bar.addView(texts, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = dp(4) })
        moonBtn = Ui.iconButton(this, R.drawable.ic_moon, "Modo oscuro del documento") { toggleDark() }
        shareBtn = Ui.iconButton(this, R.drawable.ic_share_line, "Compartir") { share() }
        moreBtn = Ui.iconButton(this, R.drawable.ic_more, "Más opciones") { showMore(it) }
        moonBtn.visibility = View.GONE
        shareBtn.visibility = View.GONE
        moreBtn.visibility = View.GONE
        bar.addView(moonBtn)
        bar.addView(shareBtn)
        bar.addView(moreBtn)
        topBox.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        topBox.addView(View(this).apply { setBackgroundColor(Ui.BORDER) }, Ui.lp(Ui.MATCH, dp(1)))
        root.addView(topBox, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))
    }

    private fun buildBottomBar(root: FrameLayout) {
        bottomBar = Ui.row(this).apply {
            background = Ui.round(this@ViewerActivity, Ui.SURFACE, 16f, Ui.BORDER, 1f)
            elevation = dp(8).toFloat()
            setPadding(dp(4), 0, dp(6), 0)
            visibility = View.GONE
            isClickable = true
        }
        fun spacer() = View(this).also { bottomBar.addView(it, Ui.lp(0, 1, 1f)) }
        fun divider() = View(this).apply { setBackgroundColor(0xFF2E2E35.toInt()) }
            .also { bottomBar.addView(it, Ui.lp(dp(1), dp(24))) }

        bottomBar.addView(Ui.iconButton(this, R.drawable.ic_minus, "Alejar") { webView.zoomBy(0.8f) })
        zoomLabel = Ui.text(this, "100 %", 13f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        bottomBar.addView(zoomLabel, Ui.lp(dp(52), Ui.WRAP))
        bottomBar.addView(Ui.iconButton(this, R.drawable.ic_plus, "Acercar") { webView.zoomBy(1.25f) })
        spacer()
        divider()
        spacer()
        bottomBar.addView(Ui.iconButton(this, R.drawable.ic_rotate, "Girar la pantalla") { rotate() })

        pageViews.add(spacer())
        pageViews.add(divider())
        pageViews.add(spacer())
        val pageBtn = Ui.row(this).apply {
            background = Ui.ripple(this@ViewerActivity, Ui.round(this@ViewerActivity, Ui.ACTIVE_BG, 10f), 10f)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Ir a una página"
            setOnClickListener { goToPageDialog() }
        }
        pageBtn.addView(Ui.icon(this, R.drawable.ic_grid, Ui.ACTIVE_TEXT, 16))
        pageBtnText = Ui.text(this, "1 / 1", 13f, Ui.ACTIVE_TEXT, bold = true)
        pageBtn.addView(pageBtnText, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(8) })
        bottomBar.addView(pageBtn, Ui.lp(Ui.WRAP, dp(40)))
        pageViews.add(pageBtn)
        pageViews.forEach { it.visibility = View.GONE }

        root.addView(bottomBar, FrameLayout.LayoutParams(Ui.MATCH, dp(56), Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(16))
        })
    }

    /** Muestra u oculta las barras de arriba y de abajo. */
    private fun applyBars(show: Boolean, animate: Boolean) {
        if (show == barsShown && animate) return
        barsShown = show
        val up = if (show) 0f else -(topBox.height + dp(4)).toFloat()
        val down = if (show) 0f else (bottomBar.height + dp(24)).toFloat()
        if (animate) {
            topBox.animate().translationY(up).setDuration(180).start()
            bottomBar.animate().translationY(down).setDuration(180).start()
        } else {
            topBox.translationY = up
            bottomBar.translationY = down
        }
        if (show) pageLabel.alpha = 0f
        webView.evaluateJavascript("window.setBars&&setBars(" + (if (show) 1 else 0) + ")", null)
    }

    private fun updateTopBar() {
        titleView.text = displayName.ifBlank { "Abriendo…" }
        val k = docKind
        val parts = ArrayList<String>()
        if (k != null) parts.add(Ui.kindLabel(k))
        if (pagesTotal > 0 && (k == Kind.PDF || k == Kind.PPTX)) {
            parts.add(
                if (k == Kind.PPTX) (if (pagesTotal == 1) "1 diapositiva" else "$pagesTotal diapositivas")
                else (if (pagesTotal == 1) "1 página" else "$pagesTotal páginas")
            )
        }
        docFile?.let { parts.add(Ui.size(it.length())) }
        subtitleView.text = parts.joinToString(" · ")
        val ready = docFile != null
        shareBtn.visibility = if (ready) View.VISIBLE else View.GONE
        moreBtn.visibility = if (ready) View.VISIBLE else View.GONE
        moonBtn.visibility = if (ready && k in DARK_KINDS) View.VISIBLE else View.GONE
        styleMoon()
        val showPages = pagesTotal > 1
        pageViews.forEach { it.visibility = if (showPages) View.VISIBLE else View.GONE }
        if (showPages) pageBtnText.text = "$currentPage / $pagesTotal"
    }

    private fun styleMoon() {
        moonBtn.background = if (dark) Ui.round(this, Ui.ACTIVE_BG, 12f) else Ui.borderlessRipple()
        moonBtn.imageTintList = ColorStateList.valueOf(if (dark) Ui.ACTIVE_TEXT else Ui.ICON)
        moonBtn.contentDescription = if (dark) "Modo oscuro activado" else "Modo oscuro desactivado"
    }

    /** Color de fondo del WebView mientras carga, para que no haya destellos. */
    private fun applyWebBg() {
        val k = docKind
        val c = when {
            k == null -> Ui.BG
            k in DARK_KINDS && !dark -> when (k) {
                Kind.DOCX -> 0xFFE4E4E4.toInt()
                Kind.TEXT -> 0xFFF4F4F4.toInt()
                else -> Color.WHITE
            }
            k in DARK_KINDS -> Ui.BG
            else -> 0xFF0B0B0E.toInt()
        }
        webView.setBackgroundColor(c)
    }

    private fun toggleDark() {
        dark = !dark
        getSharedPreferences("ajustes", MODE_PRIVATE).edit().putBoolean("docs_oscuros", dark).apply()
        webView.evaluateJavascript("window.setDark&&setDark(" + (if (dark) 1 else 0) + ")", null)
        applyWebBg()
        styleMoon()
    }

    private fun rotate() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun goToPageDialog() {
        if (pagesTotal < 2) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 a $pagesTotal"
            setText(currentPage.toString())
            selectAll()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val label = if (docKind == Kind.PPTX) "Ir a la diapositiva" else "Ir a la página"
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(label)
            .setView(box)
            .setPositiveButton("Ir") { _, _ ->
                val n = input.text.toString().trim().toIntOrNull()
                if (n != null) {
                    val p = n.coerceIn(1, pagesTotal)
                    webView.evaluateJavascript("window.goPage&&goPage($p)", null)
                }
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    private fun showMore(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val id = entryId
        val fav = if (id != null) Library.find(this, id)?.fav == true else false
        if (id != null) pm.menu.add(0, 1, 0, if (fav) "Quitar de favoritos" else "Agregar a favoritos")
        pm.menu.add(0, 2, 1, "Abrir con otra app")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> if (id != null) {
                    Library.setFavorite(this, id, !fav)
                    Toast.makeText(
                        this, if (fav) "Quitado de favoritos" else "Agregado a favoritos", Toast.LENGTH_SHORT
                    ).show()
                }
                2 -> openWith()
            }
            true
        }
        pm.show()
    }

    private fun showMessageAsync(html: String) {
        runOnUiThread { if (!isDestroyed) showMessage(html) }
    }

    // ------------------------------------------------------- compartir

    private fun contentUri(): Uri? {
        val f = docFile ?: return null
        return FileProvider.getUriForFile(this, "$packageName.files", f)
    }

    private fun excludeSelf(chooser: Intent) {
        chooser.putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(ComponentName(this, ViewerActivity::class.java))
        )
    }

    private fun share() {
        val uri = contentUri() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = docMime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(docName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Compartir \"$docName\"")
        excludeSelf(chooser)
        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No hay apps para compartir este archivo", Toast.LENGTH_LONG).show()
        }
    }

    private fun openWith() {
        val uri = contentUri() ?: return
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, docMime)
            clipData = ClipData.newRawUri(docName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, "Abrir con")
        excludeSelf(chooser)
        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No hay otra app que abra este archivo", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------- cierre

    override fun onDestroy() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        synchronized(pdfLock) {
            try {
                pdf?.close()
            } catch (_: Exception) {
            }
            try {
                pdfFd?.close()
            } catch (_: Exception) {
            }
            pdf = null
            pdfFd = null
        }
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Id de la entrada de Recientes cuando el archivo se abre desde la pantalla de inicio. */
        const val EXTRA_ENTRY = "com.jnieves.lector.ENTRY"
        private val CAPTURE_KINDS = setOf(Kind.DOCX, Kind.XLSX, Kind.PPTX, Kind.CSV, Kind.TEXT)
        /** Tipos que cambian con el modo oscuro. Los PDF, PowerPoint e imágenes siempre se ven como el original. */
        private val DARK_KINDS = setOf(Kind.DOCX, Kind.XLSX, Kind.CSV, Kind.TEXT)
        private val DARK_CLASSES = setOf("docx", "sheet", "txt")
    }
}
