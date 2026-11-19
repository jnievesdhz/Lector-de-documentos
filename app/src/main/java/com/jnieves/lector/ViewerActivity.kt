package com.jnieves.lector

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import kotlin.math.min
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

        webView = WebView(this)
        configureWebView(webView)

        pageLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xAA000000.toInt())
            }
            alpha = 0f
        }

        val root = FrameLayout(this)
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
        setContentView(root)
        actionBar?.setDisplayHomeAsUpEnabled(true)

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
        outState.putString("title", title?.toString())
        outState.putInt("rw", renderWidth)
        outState.putFloat("frac", scrollFrac)
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
        title = b.getString("title") ?: docName
        invalidateOptionsMenu()
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
            runOnUiThread {
                if (!isDestroyed) {
                    title = name
                    invalidateOptionsMenu()
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
            when (kind) {
                Kind.PDF -> openPdf(file, dir)
                Kind.DOCX -> writeAndLoad(dir, Html.page(DocxToHtml.convert(file, dir), "", "docx"))
                Kind.XLSX -> writeAndLoad(
                    dir, Html.page(XlsxToHtml.convert(file), Html.SHEET_SCRIPT, "sheet", "0.25")
                )
                Kind.CSV -> writeAndLoad(
                    dir, Html.page(CsvToHtml.convert(readText(file).first), Html.SHEET_SCRIPT, "sheet", "0.25")
                )
                Kind.PPTX -> writeAndLoad(
                    dir, Html.page(PptxToHtml.convert(file, dir), Html.SLIDES_SCRIPT, "pptx")
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
        writeAndLoad(dir, Html.page(sb.toString(), Html.PDF_SCRIPT, "pdf"))
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

    private fun showPageNumber(n: Int) {
        if (pageCount <= 1) return
        pageLabel.text = "$n / $pageCount"
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
        val body = "<div class=\"imgwrap\"><img src=\"" + Uri.encode(shown.name) + "\" alt=\"\"></div>"
        writeAndLoad(dir, Html.page(body, "", "img"))
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
        writeAndLoad(dir, Html.page("<pre>" + Docs.esc(text) + "</pre>" + note, "", "txt"))
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
            override fun onPageFinished(view: WebView, url: String?) {
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
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        w.setBackgroundColor(if (night) Color.rgb(22, 22, 22) else Color.rgb(244, 244, 244))
    }

    private fun writeAndLoad(dir: File, html: String) {
        val f = File(dir, "vista.html")
        f.writeText(html)
        runOnUiThread { if (!isDestroyed) webView.loadUrl(Uri.fromFile(f).toString()) }
    }

    private fun showMessage(html: String) {
        webView.loadDataWithBaseURL(null, Html.message(html), "text/html", "utf-8", null)
    }

    private fun showMessageAsync(html: String) {
        runOnUiThread { if (!isDestroyed) showMessage(html) }
    }

    // --------------------------------------------------------------- menú

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SHARE, 0, "Compartir")
            .setIcon(R.drawable.ic_share)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_OPEN_WITH, 1, "Abrir con otra app")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val ready = docFile != null
        menu.findItem(MENU_SHARE)?.isVisible = ready
        menu.findItem(MENU_OPEN_WITH)?.isVisible = ready
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_SHARE -> {
            share()
            true
        }
        MENU_OPEN_WITH -> {
            openWith()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

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
        private const val MENU_SHARE = 1
        private const val MENU_OPEN_WITH = 2
    }
}
