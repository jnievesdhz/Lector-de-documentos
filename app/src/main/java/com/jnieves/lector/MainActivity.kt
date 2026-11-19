package com.jnieves.lector

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Pantalla de inicio: archivos recientes, favoritos y carpetas. */
class MainActivity : Activity() {

    private enum class Tab(val label: String) { RECENT("Recientes"), FAV("Favoritos"), FOLDERS("Carpetas") }

    /** Una carpeta abierta dentro del explorador. */
    private class Level(val tree: Uri, val docId: String, val name: String)

    /** Un archivo en la lista: del historial o de una carpeta. */
    private class Item(
        val entry: Entry?,
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long,
        val date: Long
    ) {
        val kind: Kind get() = Docs.kindFor(mime, name)
    }

    private val filters = listOf("Todos", "PDF", "Word", "Excel", "PowerPoint", "Imágenes", "Texto")

    private val io = Executors.newFixedThreadPool(2)
    private val thumbs = LruCache<String, Bitmap>(40)
    private lateinit var prefs: SharedPreferences

    private var tab = Tab.RECENT
    private var filter = 0
    private var grid = false
    private var query = ""
    private val stack = ArrayList<Level>()
    private var folderData: Pair<List<Level>, List<Item>>? = null
    private var loadToken = 0

    private lateinit var searchRow: LinearLayout
    private lateinit var searchField: EditText
    private val tabViews = ArrayList<TextView>()
    private lateinit var folderBar: LinearLayout
    private lateinit var folderTitle: TextView
    private lateinit var toolsRow: LinearLayout
    private val chips = ArrayList<TextView>()
    private lateinit var btnList: ImageView
    private lateinit var btnGrid: ImageView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Docs.cleanOld(this)
        prefs = getSharedPreferences("ajustes", Context.MODE_PRIVATE)
        grid = prefs.getBoolean("cuadricula", false)
        tab = Tab.values().firstOrNull { it.name == prefs.getString("pestana", null) } ?: Tab.RECENT

        if (savedInstanceState != null) {
            tab = Tab.values().firstOrNull { it.name == savedInstanceState.getString("tab") } ?: tab
            filter = savedInstanceState.getInt("filter", 0)
            val trees = savedInstanceState.getStringArrayList("trees")
            val ids = savedInstanceState.getStringArrayList("ids")
            val names = savedInstanceState.getStringArrayList("names")
            if (trees != null && ids != null && names != null) {
                for (i in trees.indices) stack.add(Level(Uri.parse(trees[i]), ids[i], names[i]))
            }
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
        outState.putInt("filter", filter)
        outState.putStringArrayList("trees", ArrayList(stack.map { it.tree.toString() }))
        outState.putStringArrayList("ids", ArrayList(stack.map { it.docId }))
        outState.putStringArrayList("names", ArrayList(stack.map { it.name }))
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            searchRow.visibility == View.VISIBLE -> closeSearch()
            tab == Tab.FOLDERS && stack.isNotEmpty() -> popFolder()
            else -> super.onBackPressed()
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    // ------------------------------------------------------------ interfaz

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        val col = Ui.column(this)

        // Barra superior: logo, nombre, buscar y más opciones
        val header = Ui.row(this)
        header.setPadding(dp(20), dp(10), dp(8), 0)
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_logo) }
        header.addView(logo, Ui.lp(dp(30), dp(30)))
        val title = Ui.text(this, "Lector", 20f, Ui.TEXT, bold = true)
        header.addView(title, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = dp(10) })
        header.addView(Ui.iconButton(this, R.drawable.ic_search, "Buscar archivos") { openSearch() })
        header.addView(Ui.iconButton(this, R.drawable.ic_more, "Más opciones") { showMore(it) })
        col.addView(header, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Buscador (oculto hasta tocar la lupa)
        searchRow = Ui.row(this).apply {
            background = Ui.round(this@MainActivity, Ui.FIELD, 12f, Ui.ACTIVE_BORDER, 1f)
            setPadding(dp(12), 0, dp(2), 0)
            visibility = View.GONE
        }
        searchRow.addView(Ui.icon(this, R.drawable.ic_search, Ui.MUTED, 18))
        searchField = EditText(this).apply {
            background = null
            hint = "Buscar por nombre"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(10), 0, dp(4), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim() ?: ""
                    if (q != query) {
                        query = q
                        refresh()
                    }
                }
            })
        }
        searchRow.addView(searchField, Ui.lp(0, dp(44), 1f))
        searchRow.addView(Ui.iconButton(this, R.drawable.ic_close, "Cerrar búsqueda", Ui.MUTED) { closeSearch() })
        col.addView(searchRow, Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            setMargins(dp(16), dp(8), dp(16), 0)
        })

        // Pestañas
        val tabs = Ui.row(this)
        tabs.setPadding(dp(20), dp(6), dp(20), 0)
        tabs.gravity = Gravity.BOTTOM
        Tab.values().forEachIndexed { i, t ->
            // La línea lavanda de la pestaña activa se dibuja como fondo del texto
            val tv = Ui.text(this, t.label, 15f, Ui.MUTED).apply {
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { setTab(t) }
            }
            tabs.addView(tv, Ui.lp(Ui.WRAP, Ui.WRAP).apply { if (i > 0) marginStart = dp(26) })
            tabViews.add(tv)
        }
        col.addView(tabs, Ui.lp(Ui.MATCH, Ui.WRAP))
        col.addView(View(this).apply { setBackgroundColor(Ui.DIVIDER) }, Ui.lp(Ui.MATCH, dp(1)))

        // Barra de la carpeta abierta (volver + nombre)
        folderBar = Ui.row(this).apply { setPadding(dp(8), dp(6), dp(16), 0) }
        folderBar.addView(Ui.iconButton(this, R.drawable.ic_back, "Volver a la carpeta anterior", Ui.TEXT_SOFT) { popFolder() })
        folderTitle = Ui.singleLine(Ui.text(this, "", 16f, Ui.TEXT, bold = true))
        folderBar.addView(folderTitle, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = dp(4) })
        col.addView(folderBar, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Filtros por tipo y selector de lista o cuadrícula
        toolsRow = Ui.row(this).apply { setPadding(0, dp(12), dp(12), dp(4)) }
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(40))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val chipRow = Ui.row(this).apply { setPadding(dp(20), 0, dp(16), 0) }
        filters.forEachIndexed { i, label ->
            val c = Ui.text(this, label, 14f, Ui.TEXT_SOFT).apply {
                gravity = Gravity.CENTER
                setPadding(dp(13), 0, dp(13), 0)
                isClickable = true
                setOnClickListener {
                    filter = i
                    refresh()
                }
            }
            chipRow.addView(c, Ui.lp(Ui.WRAP, dp(36)).apply { if (i > 0) marginStart = dp(8) })
            chips.add(c)
        }
        hs.addView(chipRow)
        toolsRow.addView(hs, Ui.lp(0, Ui.WRAP, 1f))

        val toggle = Ui.row(this).apply {
            background = Ui.round(this@MainActivity, 0xFF16161A.toInt(), 12f, Ui.BORDER, 1f)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        btnList = toggleButton(R.drawable.ic_list, "Vista de lista") { setGrid(false) }
        btnGrid = toggleButton(R.drawable.ic_grid, "Vista de cuadrícula") { setGrid(true) }
        toggle.addView(btnList)
        toggle.addView(btnGrid)
        toolsRow.addView(toggle, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(4) })
        col.addView(toolsRow, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Contenido
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        list = Ui.column(this).apply { setPadding(dp(8), dp(4), dp(8), dp(112)) }
        scroll.addView(list, ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP))
        col.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))

        root.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        // Botón flotante "Abrir"
        val fab = Ui.row(this).apply {
            background = Ui.ripple(this@MainActivity, Ui.round(this@MainActivity, Ui.ACCENT, 16f), 16f)
            elevation = dp(8).toFloat()
            setPadding(dp(16), 0, dp(20), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Abrir un archivo"
            setOnClickListener { pickFile() }
        }
        fab.addView(Ui.icon(this, R.drawable.ic_plus, Ui.BG, 20))
        fab.addView(Ui.text(this, "Abrir", 15f, Ui.BG, bold = true), Ui.lp(Ui.WRAP, Ui.WRAP).apply {
            marginStart = dp(8)
        })
        root.addView(fab, FrameLayout.LayoutParams(Ui.WRAP, dp(54), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(20), dp(24))
        })

        setContentView(root)
    }

    private fun toggleButton(res: Int, desc: String, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(res)
            contentDescription = desc
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
        }

    private fun styleChip(c: TextView, on: Boolean) {
        c.background = if (on) Ui.round(this, Ui.ACTIVE_BG, 10f, Ui.ACTIVE_BORDER, 1f)
        else Ui.round(this, Color.TRANSPARENT, 10f, Ui.BORDER, 1f)
        c.setTextColor(if (on) Ui.ACTIVE_TEXT else Ui.TEXT_SOFT)
        c.typeface = if (on) Ui.BOLD else Ui.REGULAR
    }

    private fun styleToggle() {
        for ((b, on) in listOf(btnList to !grid, btnGrid to grid)) {
            b.background = if (on) Ui.round(this, Ui.ACTIVE_BG, 10f) else null
            b.imageTintList = ColorStateList.valueOf(if (on) Ui.ACTIVE_TEXT else Ui.MUTED)
        }
    }

    // ------------------------------------------------------------ estado

    private fun setTab(t: Tab) {
        if (t == Tab.FOLDERS && tab == Tab.FOLDERS && stack.isNotEmpty()) {
            stack.clear()
            folderData = null
        }
        tab = t
        prefs.edit().putString("pestana", t.name).apply()
        scroll.scrollTo(0, 0)
        refresh()
    }

    private fun setGrid(g: Boolean) {
        grid = g
        prefs.edit().putBoolean("cuadricula", g).apply()
        refresh()
    }

    private fun matchesFilter(k: Kind): Boolean = when (filter) {
        1 -> k == Kind.PDF
        2 -> k == Kind.DOCX
        3 -> k == Kind.XLSX || k == Kind.CSV
        4 -> k == Kind.PPTX
        5 -> k == Kind.IMAGE
        6 -> k == Kind.TEXT
        else -> true
    }

    private fun visible(it: Item): Boolean =
        matchesFilter(it.kind) && (query.isEmpty() || it.name.contains(query, ignoreCase = true))

    private fun refresh() {
        Tab.values().forEachIndexed { i, t ->
            val tv = tabViews[i]
            val on = t == tab
            tv.setTextColor(if (on) Ui.TEXT else Ui.MUTED)
            tv.typeface = if (on) Ui.BOLD else Ui.REGULAR
            tv.background = Ui.ripple(this, if (on) Ui.underline(this, Ui.ACCENT) else null, 8f)
        }
        val inFolder = tab == Tab.FOLDERS && stack.isNotEmpty()
        folderBar.visibility = if (inFolder) View.VISIBLE else View.GONE
        if (inFolder) folderTitle.text = stack.last().name
        toolsRow.visibility = if (tab == Tab.FOLDERS && !inFolder) View.GONE else View.VISIBLE
        chips.forEachIndexed { i, c -> styleChip(c, i == filter) }
        styleToggle()

        list.removeAllViews()
        when (tab) {
            Tab.RECENT -> showEntries(Library.entries(this).filter { it.opened > 0 }, grouped = true)
            Tab.FAV -> showEntries(Library.entries(this).filter { it.fav }, grouped = false)
            Tab.FOLDERS -> if (inFolder) showFolderContent() else showFolders()
        }
    }

    // ------------------------------------------------------------ recientes y favoritos

    private fun showEntries(entries: List<Entry>, grouped: Boolean) {
        val items = entries
            .map { Item(it, Library.uriFor(this, it), it.name, it.mime, it.size, it.opened) }
            .filter { visible(it) }
        if (items.isEmpty()) {
            when {
                query.isNotEmpty() || filter != 0 ->
                    empty("No hay coincidencias", "Prueba con otro nombre u otro tipo de archivo.")
                tab == Tab.FAV ->
                    empty("Todavía no hay favoritos", "Mantén presionado un archivo y escoge «Agregar a favoritos».")
                else ->
                    empty("Todavía no has abierto archivos", "Toca «Abrir» para escoger uno, o usa «Abrir con» desde otra app.")
            }
            return
        }
        if (!grouped) {
            addItems(items)
            return
        }
        val groups = LinkedHashMap<String, ArrayList<Item>>()
        for (it in items) {
            val d = Ui.daysAgo(it.date)
            val g = when {
                d == 0 -> "Hoy"
                d == 1 -> "Ayer"
                d < 7 -> "Esta semana"
                else -> "Antes"
            }
            groups.getOrPut(g) { ArrayList() }.add(it)
        }
        for ((g, its) in groups) {
            list.addView(sectionLabel(g))
            addItems(its)
        }
    }

    private fun addItems(items: List<Item>) {
        if (!grid) {
            items.forEach { list.addView(fileRow(it)) }
            return
        }
        var i = 0
        while (i < items.size) {
            val r = Ui.row(this).apply {
                gravity = Gravity.TOP
                setPadding(dp(12), dp(6), dp(12), dp(10))
            }
            r.addView(fileCard(items[i]), Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = dp(12) })
            if (i + 1 < items.size) r.addView(fileCard(items[i + 1]), Ui.lp(0, Ui.WRAP, 1f))
            else r.addView(View(this), Ui.lp(0, 0, 1f))
            list.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP))
            i += 2
        }
    }

    private fun sectionLabel(s: String): TextView =
        Ui.text(this, s, 13f, Ui.MUTED, bold = true).apply { setPadding(dp(12), dp(14), dp(12), dp(4)) }

    private fun empty(title: String, body: String) {
        val box = Ui.column(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(72), dp(32), 0)
        }
        box.addView(Ui.text(this, title, 17f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER })
        box.addView(Ui.text(this, body, 14f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(0f, 1.25f)
        })
        list.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun meta(item: Item, withSize: Boolean): String =
        listOf(Ui.kindLabel(item.kind), Ui.shortDate(item.date), if (withSize) Ui.size(item.size) else "")
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    private fun typeTile(kind: Kind, sizeDp: Int, iconDp: Int): FrameLayout {
        val tile = FrameLayout(this).apply {
            background = Ui.round(this@MainActivity, Ui.TILE, 10f, Ui.BORDER_SOFT, 1f)
        }
        val ic = ImageView(this).apply {
            setImageResource(Ui.kindIcon(kind))
            imageTintList = ColorStateList.valueOf(Ui.kindColor(kind))
        }
        tile.addView(ic, FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        tile.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return tile
    }

    private fun fileRow(item: Item): View {
        val r = Ui.row(this).apply {
            setPadding(dp(12), dp(8), 0, dp(8))
            background = Ui.ripple(this@MainActivity, null, 14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { openItem(item) }
            setOnLongClickListener {
                showSheet(item)
                true
            }
        }
        r.addView(typeTile(item.kind, 44, 22))
        val texts = Ui.column(this)
        val nameRow = Ui.row(this)
        nameRow.addView(Ui.singleLine(Ui.text(this, item.name, 15f, Ui.TEXT, bold = true)), Ui.lp(0, Ui.WRAP, 1f))
        if (item.entry?.fav == true) {
            nameRow.addView(Ui.icon(this, R.drawable.ic_star, Ui.ACCENT, 14).apply {
                contentDescription = "Favorito"
            }, Ui.lp(dp(14), dp(14)).apply { marginStart = dp(6) })
        }
        texts.addView(nameRow, Ui.lp(Ui.MATCH, Ui.WRAP))
        texts.addView(Ui.singleLine(Ui.text(this, meta(item, true), 13f, Ui.MUTED)), Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            topMargin = dp(3)
        })
        r.addView(texts, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = dp(14) })
        r.addView(Ui.iconButton(this, R.drawable.ic_more, "Más opciones", Ui.MUTED) { showSheet(item) })
        return r
    }

    private fun fileCard(item: Item): View {
        val card = Ui.column(this).apply {
            background = Ui.ripple(this@MainActivity, null, 14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { openItem(item) }
            setOnLongClickListener {
                showSheet(item)
                true
            }
        }
        val frame = FrameLayout(this).apply {
            background = Ui.round(this@MainActivity, Ui.TILE, 12f, Ui.BORDER_SOFT, 1f)
            clipToOutline = true
        }
        val big = ImageView(this).apply {
            setImageResource(Ui.kindIcon(item.kind))
            imageTintList = ColorStateList.valueOf(Ui.kindColor(item.kind))
        }
        frame.addView(big, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        val thumb = ImageView(this).apply { visibility = View.GONE }
        frame.addView(thumb, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        if (item.entry?.fav == true) {
            frame.addView(Ui.icon(this, R.drawable.ic_star, Ui.ACCENT, 16).apply {
                contentDescription = "Favorito"
            }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(8), dp(8), 0)
            })
        }
        card.addView(frame, Ui.lp(Ui.MATCH, dp(120)))
        card.addView(Ui.singleLine(Ui.text(this, item.name, 14f, Ui.TEXT, bold = true)), Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            topMargin = dp(8)
            marginStart = dp(2)
        })
        card.addView(Ui.singleLine(Ui.text(this, meta(item, false), 12.5f, Ui.MUTED)), Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            topMargin = dp(2)
            marginStart = dp(2)
            bottomMargin = dp(4)
        })
        item.entry?.let { loadThumb(it.id, item.kind, thumb, big) }
        return card
    }

    private fun loadThumb(id: String, kind: Kind, iv: ImageView, big: ImageView) {
        val f = Library.thumbFile(this, id)
        if (!f.exists()) return
        val key = id + ":" + f.lastModified()
        iv.tag = key
        if (kind == Kind.PDF) {
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(dp(10), dp(8), dp(10), dp(8))
        } else {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val cached = thumbs.get(key)
        if (cached != null) {
            showThumb(iv, big, cached)
            return
        }
        try {
            io.execute {
                val b = try {
                    BitmapFactory.decodeFile(f.path)
                } catch (_: Throwable) {
                    null
                }
                if (b != null) {
                    thumbs.put(key, b)
                    runOnUiThread { if (!isDestroyed && iv.tag == key) showThumb(iv, big, b) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showThumb(iv: ImageView, big: ImageView, b: Bitmap) {
        iv.setImageBitmap(b)
        iv.visibility = View.VISIBLE
        big.visibility = View.GONE
    }

    // ------------------------------------------------------------ carpetas

    private fun showFolders() {
        val fs = Library.folders(this)
        for (f in fs) {
            list.addView(folderRow(f.name, folderSubtitle(f.uri), {
                try {
                    val tree = Uri.parse(f.uri)
                    stack.add(Level(tree, DocumentsContract.getTreeDocumentId(tree), f.name))
                    folderData = null
                    scroll.scrollTo(0, 0)
                    refresh()
                } catch (_: Exception) {
                    toast("No se pudo abrir la carpeta")
                }
            }, { confirmRemoveFolder(f) }))
        }
        val add = Ui.row(this).apply {
            gravity = Gravity.CENTER
            background = Ui.ripple(
                this@MainActivity,
                Ui.round(this@MainActivity, Color.TRANSPARENT, 12f, Ui.ACTIVE_BORDER, 1f, dashed = true)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { pickFolder() }
        }
        add.addView(Ui.icon(this, R.drawable.ic_plus, Ui.ACTIVE_TEXT, 20))
        add.addView(Ui.text(this, "Agregar carpeta", 15f, Ui.ACTIVE_TEXT, bold = true), Ui.lp(Ui.WRAP, Ui.WRAP).apply {
            marginStart = dp(10)
        })
        list.addView(add, Ui.lp(Ui.MATCH, dp(52)).apply { setMargins(dp(12), dp(14), dp(12), 0) })
        list.addView(
            Ui.text(
                this,
                "La app solo ve las carpetas que tú le das permiso de abrir. " +
                    "Para quitar una, mantenla presionada.",
                13f, Ui.MUTED
            ).apply {
                setPadding(dp(14), dp(12), dp(14), 0)
                setLineSpacing(0f, 1.25f)
            },
            Ui.lp(Ui.MATCH, Ui.WRAP)
        )
    }

    private fun folderSubtitle(uri: String): String = try {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri))
        val path = id.substringAfter(':', "")
        if (path.isBlank()) "Almacenamiento interno" else path.replace("/", " › ")
    } catch (_: Exception) {
        ""
    }

    private fun folderRow(name: String, subtitle: String?, onClick: () -> Unit, onLong: (() -> Unit)?): View {
        val r = Ui.row(this).apply {
            setPadding(dp(12), dp(10), dp(4), dp(10))
            background = Ui.ripple(this@MainActivity, null, 14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener {
                onLong()
                true
            }
        }
        val tile = FrameLayout(this).apply {
            background = Ui.round(this@MainActivity, Ui.TILE, 10f, Ui.BORDER_SOFT, 1f)
        }
        tile.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_folder)
            imageTintList = ColorStateList.valueOf(Ui.FOLDER)
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        r.addView(tile, Ui.lp(dp(44), dp(44)))
        val texts = Ui.column(this)
        texts.addView(Ui.singleLine(Ui.text(this, name, 15f, Ui.TEXT, bold = true)))
        if (!subtitle.isNullOrBlank()) {
            texts.addView(Ui.singleLine(Ui.text(this, subtitle, 13f, Ui.MUTED)), Ui.lp(Ui.MATCH, Ui.WRAP).apply {
                topMargin = dp(3)
            })
        }
        r.addView(texts, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = dp(14) })
        r.addView(Ui.icon(this, R.drawable.ic_chevron, Ui.FAINT, 20), Ui.lp(dp(20), dp(20)).apply {
            marginEnd = dp(12)
        })
        return r
    }

    private fun showFolderContent() {
        val data = folderData
        if (data == null) {
            list.addView(Ui.text(this, "Cargando…", 14f, Ui.MUTED).apply {
                setPadding(dp(14), dp(20), dp(14), 0)
            })
            loadFolder()
            return
        }
        val dirs = data.first.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        val files = data.second.filter { visible(it) }
        if (dirs.isEmpty() && files.isEmpty()) {
            if (query.isNotEmpty() || filter != 0) empty("No hay coincidencias", "Prueba con otro nombre u otro tipo de archivo.")
            else empty("No hay documentos aquí", "Esta carpeta no tiene archivos que la app pueda abrir.")
            return
        }
        for (d in dirs) {
            list.addView(folderRow(d.name, null, {
                stack.add(d)
                folderData = null
                scroll.scrollTo(0, 0)
                refresh()
            }, null))
        }
        addItems(files)
    }

    private fun loadFolder() {
        val level = stack.lastOrNull() ?: return
        val token = ++loadToken
        try {
            io.execute {
                val res = try {
                    queryChildren(level)
                } catch (_: Exception) {
                    null
                }
                runOnUiThread {
                    if (isDestroyed || token != loadToken) return@runOnUiThread
                    if (res == null) toast("No se pudo leer la carpeta. Puede que ya no exista o que se haya quitado el permiso.")
                    folderData = res ?: Pair(emptyList(), emptyList())
                    refresh()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun queryChildren(level: Level): Pair<List<Level>, List<Item>> {
        val known = Library.entries(this).associateBy { it.uri }
        val dirs = ArrayList<Level>()
        val files = ArrayList<Item>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(level.tree, level.docId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
        contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = c.getString(2) ?: ""
                if (mime == Document.MIME_TYPE_DIR) {
                    dirs.add(Level(level.tree, id, name))
                } else {
                    val m = if (mime.isEmpty() || mime == "application/octet-stream") Docs.mimeFromName(name) else mime
                    if (Docs.kindFor(m, name) == Kind.OTHER) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(level.tree, id)
                    files.add(
                        Item(
                            known[uri.toString()], uri, name, m,
                            if (c.isNull(3)) -1L else c.getLong(3),
                            if (c.isNull(4)) 0L else c.getLong(4)
                        )
                    )
                }
            }
        }
        dirs.sortBy { it.name.lowercase() }
        files.sortByDescending { it.date }
        return Pair(dirs, files)
    }

    private fun popFolder() {
        if (stack.isEmpty()) return
        stack.removeAt(stack.size - 1)
        folderData = null
        refresh()
    }

    private fun confirmRemoveFolder(f: Folder) {
        AlertDialog.Builder(this, DIALOG)
            .setTitle("Quitar «${f.name}»")
            .setMessage("La carpeta deja de aparecer aquí. Los archivos no se borran del celular.")
            .setPositiveButton("Quitar") { _, _ ->
                try {
                    contentResolver.releasePersistableUriPermission(Uri.parse(f.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                Library.removeFolder(this, f.uri)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------ abrir archivos

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, Docs.SUPPORTED_MIME)
        }
        try {
            startActivityForResult(intent, REQ_OPEN)
        } catch (e: ActivityNotFoundException) {
            toast("No se encontró el selector de archivos")
        }
    }

    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        } catch (e: ActivityNotFoundException) {
            toast("No se encontró el selector de carpetas")
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_OPEN -> {
                try {
                    // Guarda el permiso de lectura para poder reabrirlo desde Recientes
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                launchViewer(uri, contentResolver.getType(uri), null)
            }
            REQ_TREE -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    toast("No se pudo guardar el permiso de la carpeta")
                    return
                }
                var name = "Carpeta"
                try {
                    val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    contentResolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0)?.let { n -> name = n }
                    }
                } catch (_: Exception) {
                }
                Library.addFolder(this, uri, name)
                tab = Tab.FOLDERS
                stack.clear()
                folderData = null
                prefs.edit().putString("pestana", tab.name).apply()
                refresh()
            }
        }
    }

    private fun openItem(item: Item) {
        try {
            io.execute {
                val ok = try {
                    contentResolver.openAssetFileDescriptor(item.uri, "r")?.use { true } ?: false
                } catch (_: Exception) {
                    false
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    when {
                        ok -> launchViewer(item.uri, item.mime, item.entry?.id)
                        item.entry != null -> unavailable(item.entry)
                        else -> toast("No se pudo abrir el archivo")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun launchViewer(uri: Uri, mime: String?, entryId: String?) {
        val view = Intent(this, ViewerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (entryId != null) putExtra(ViewerActivity.EXTRA_ENTRY, entryId)
        }
        startActivity(view)
    }

    private fun unavailable(e: Entry) {
        AlertDialog.Builder(this, DIALOG)
            .setTitle("Ya no se puede abrir")
            .setMessage(
                "«${e.name}» se movió, se borró o la app de donde vino ya no da permiso de leerlo. " +
                    "Puedes volver a abrirlo desde su ubicación."
            )
            .setPositiveButton("Quitar de la lista") { _, _ ->
                Library.remove(this, e.id)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------ menú de un archivo

    private fun showSheet(item: Item) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = Ui.column(this).apply {
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                val r = dp(20).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(12), dp(10), dp(12), dp(20))
        }
        box.addView(View(this).apply {
            background = Ui.round(this@MainActivity, 0xFF3A3A42.toInt(), 2f)
        }, Ui.lp(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        val head = Ui.row(this).apply { setPadding(dp(12), dp(4), dp(12), dp(16)) }
        head.addView(typeTile(item.kind, 48, 24))
        val texts = Ui.column(this)
        texts.addView(Ui.text(this, item.name, 16f, Ui.TEXT, bold = true).apply { maxLines = 2 })
        texts.addView(Ui.text(this, meta(item, true), 13f, Ui.MUTED), Ui.lp(Ui.WRAP, Ui.WRAP).apply {
            topMargin = dp(3)
        })
        head.addView(texts, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = dp(14) })
        box.addView(head)
        box.addView(View(this).apply { setBackgroundColor(Ui.BORDER_SOFT) }, Ui.lp(Ui.MATCH, dp(1)))

        fun action(label: String, icon: Int, color: Int, iconColor: Int, fn: () -> Unit) {
            val r = Ui.row(this).apply {
                setPadding(dp(12), 0, dp(12), 0)
                background = Ui.ripple(this@MainActivity, null, 12f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    d.dismiss()
                    fn()
                }
            }
            r.addView(Ui.icon(this, icon, iconColor, 22))
            r.addView(Ui.text(this, label, 16f, color), Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = dp(16) })
            box.addView(r, Ui.lp(Ui.MATCH, dp(54)))
        }

        box.addView(View(this), Ui.lp(Ui.MATCH, dp(6)))
        val e = item.entry
        action("Abrir", R.drawable.ic_eye, Ui.TEXT, Ui.FOLDER) { openItem(item) }
        if (e != null && e.fav) {
            action("Quitar de favoritos", R.drawable.ic_star, Ui.TEXT, Ui.FOLDER) {
                Library.setFavorite(this, e.id, false)
                refresh()
            }
        } else {
            action("Agregar a favoritos", R.drawable.ic_star_line, Ui.TEXT, Ui.FOLDER) {
                if (e != null) Library.setFavorite(this, e.id, true)
                else Library.addFavorite(this, item.uri, item.name, item.mime, item.size)
                if (tab == Tab.FOLDERS) folderData = null
                refresh()
            }
        }
        action("Compartir", R.drawable.ic_share_line, Ui.TEXT, Ui.FOLDER) { share(item) }
        action("Abrir con otra app", R.drawable.ic_open_ext, Ui.TEXT, Ui.FOLDER) { openWith(item) }
        action("Detalles del archivo", R.drawable.ic_info, Ui.TEXT, Ui.FOLDER) { details(item) }
        if (e != null && e.opened > 0) {
            action("Quitar de recientes", R.drawable.ic_close, Ui.DANGER, Ui.DANGER) {
                Library.removeFromRecents(this, e.id)
                refresh()
            }
            box.addView(Ui.text(this, "Quitarlo de la lista no borra el archivo del celular.", 13f, Ui.MUTED).apply {
                setPadding(dp(12), dp(6), dp(12), 0)
            })
        }

        d.setContentView(box)
        d.show()
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.6f)
            decorView.setPadding(0, 0, 0, 0)
        }
    }

    private fun excludeSelf(chooser: Intent) {
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, ViewerActivity::class.java)))
    }

    private fun share(item: Item) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            clipData = ClipData.newRawUri(item.name, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Compartir «${item.name}»")
        excludeSelf(chooser)
        try {
            startActivity(chooser)
        } catch (_: Exception) {
            toast("No se pudo compartir el archivo")
        }
    }

    private fun openWith(item: Item) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mime)
            clipData = ClipData.newRawUri(item.name, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, "Abrir con")
        excludeSelf(chooser)
        try {
            startActivity(chooser)
        } catch (_: Exception) {
            toast("No hay otra app que abra este archivo")
        }
    }

    private fun details(item: Item) {
        val e = item.entry
        val lines = ArrayList<String>()
        lines.add("Nombre: ${item.name}")
        lines.add("Tipo: ${Ui.kindLabel(item.kind)}")
        if (item.size >= 0) lines.add("Tamaño: ${Ui.size(item.size)}")
        if (e != null && e.opened > 0) lines.add("Abierto por última vez: ${Ui.longDate(e.opened)}")
        else if (e == null && item.date > 0) lines.add("Modificado: ${Ui.longDate(item.date)}")
        lines.add(
            "Guardado como: " + when {
                e?.copyPath != null -> "copia dentro de la app (llegó desde otra app)"
                else -> "enlace al archivo original"
            }
        )
        AlertDialog.Builder(this, DIALOG)
            .setTitle("Detalles")
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton("Cerrar", null)
            .show()
    }

    // ------------------------------------------------------------ buscar y más opciones

    private fun openSearch() {
        searchRow.visibility = View.VISIBLE
        searchField.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.setText("")
        searchRow.visibility = View.GONE
        if (query.isNotEmpty()) {
            query = ""
            refresh()
        }
    }

    private fun showMore(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Borrar historial de recientes")
        pm.menu.add(0, 2, 1, "Acerca de Lector")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> AlertDialog.Builder(this, DIALOG)
                    .setTitle("Borrar historial")
                    .setMessage("Se vacía la lista de Recientes. Tus favoritos y tus carpetas se mantienen, y no se borra ningún archivo del celular.")
                    .setPositiveButton("Borrar") { _, _ ->
                        Library.clearRecents(this)
                        refresh()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                2 -> {
                    val version = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    AlertDialog.Builder(this, DIALOG)
                        .setTitle("Lector $version")
                        .setMessage(
                            "Lee PDF, Word, Excel, PowerPoint, CSV, imágenes y texto.\n\n" +
                                "Sin anuncios y sin internet: tus archivos no salen del celular."
                        )
                        .setPositiveButton("Cerrar", null)
                        .show()
                }
            }
            true
        }
        pm.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_OPEN = 10
        private const val REQ_TREE = 11
        private const val DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert
    }
}
