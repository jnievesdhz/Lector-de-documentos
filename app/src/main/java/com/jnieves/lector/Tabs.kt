package com.jnieves.lector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una pestaña del visor: un documento abierto con su copia ya convertida y dónde ibas. */
class TabInfo(
    val id: String,
    var name: String,
    var mime: String,
    /** Dirección para volver a leer el original (enlace duradero o copia de Recientes). */
    var src: String,
    /** Dirección con la que llegó el archivo (para no abrirlo dos veces). */
    var orig: String,
    var entry: String?,
    /** Carpeta de la caché con la copia y la conversión (vista.html, páginas, imágenes). */
    var dir: String,
    var file: String,
    var srcSize: Long,
    var frac: Float,
    var page: Int,
    var scale: Float,
    var used: Long
) {
    val kind: Kind get() = Docs.kindFor(mime, name)
}

/**
 * Pestañas abiertas, guardadas en un archivo de la app para que sigan ahí
 * aunque se cierre la app. Las copias convertidas viven en la caché.
 */
object Tabs {

    const val MAX = 5
    private const val FILE = "pestanas.json"
    /** Tope de espacio para las páginas ya dibujadas de los PDF abiertos. */
    private const val CACHE_LIMIT = 400L * 1024 * 1024

    private var loaded = false
    private val tabs = ArrayList<TabInfo>()
    private var activeId: String? = null

    @Synchronized
    fun all(ctx: Context): List<TabInfo> {
        ensure(ctx)
        return tabs.map { copy(it) }
    }

    @Synchronized
    fun count(ctx: Context): Int {
        ensure(ctx)
        return tabs.size
    }

    @Synchronized
    fun find(ctx: Context, id: String): TabInfo? {
        ensure(ctx)
        return tabs.firstOrNull { it.id == id }?.let { copy(it) }
    }

    /** La pestaña activa, o la usada más recientemente. */
    @Synchronized
    fun active(ctx: Context): TabInfo? {
        ensure(ctx)
        val t = tabs.firstOrNull { it.id == activeId } ?: tabs.maxByOrNull { it.used }
        return t?.let { copy(it) }
    }

    /** ¿Ya hay una pestaña con este documento? */
    @Synchronized
    fun findSame(ctx: Context, uri: String?, entry: String?, name: String? = null, size: Long = -1): TabInfo? {
        ensure(ctx)
        val t = tabs.firstOrNull { entry != null && it.entry == entry }
            ?: tabs.firstOrNull { uri != null && (it.orig == uri || it.src == uri) }
            ?: tabs.firstOrNull { name != null && size >= 0 && it.name == name && it.srcSize == size }
        return t?.let { copy(it) }
    }

    @Synchronized
    fun dirs(ctx: Context): Set<String> {
        ensure(ctx)
        return tabs.map { File(it.dir).name }.toSet()
    }

    /** Agrega o actualiza una pestaña y la deja activa. */
    @Synchronized
    fun put(ctx: Context, t: TabInfo) {
        ensure(ctx)
        val i = tabs.indexOfFirst { it.id == t.id }
        t.used = System.currentTimeMillis()
        if (i >= 0) tabs[i] = copy(t) else tabs.add(copy(t))
        activeId = t.id
        save(ctx)
    }

    @Synchronized
    fun setActive(ctx: Context, id: String) {
        ensure(ctx)
        val t = tabs.firstOrNull { it.id == id } ?: return
        t.used = System.currentTimeMillis()
        activeId = id
        save(ctx)
    }

    /** Guarda dónde ibas en una pestaña. */
    @Synchronized
    fun saveState(ctx: Context, id: String, frac: Float, page: Int, scale: Float) {
        ensure(ctx)
        val t = tabs.firstOrNull { it.id == id } ?: return
        t.frac = frac
        t.page = page
        t.scale = scale
        save(ctx)
    }

    /** Cierra una pestaña y borra su copia convertida. */
    @Synchronized
    fun remove(ctx: Context, id: String) {
        ensure(ctx)
        val t = tabs.firstOrNull { it.id == id } ?: return
        tabs.remove(t)
        if (activeId == id) activeId = tabs.maxByOrNull { it.used }?.id
        deleteDir(t.dir)
        save(ctx)
    }

    /** Si hay más de [MAX] pestañas, cierra las que llevan más tiempo sin usarse. Devuelve sus nombres. */
    @Synchronized
    fun enforceLimit(ctx: Context, keepId: String): List<String> {
        ensure(ctx)
        val closed = ArrayList<String>()
        while (tabs.size > MAX) {
            val old = tabs.filter { it.id != keepId }.minByOrNull { it.used } ?: break
            tabs.remove(old)
            deleteDir(old.dir)
            closed.add(old.name)
        }
        if (closed.isNotEmpty()) save(ctx)
        return closed
    }

    /** Si las copias ocupan demasiado, borra las páginas dibujadas de los PDF menos usados (se vuelven a dibujar). */
    @Synchronized
    fun trimCache(ctx: Context, activeTab: String?) {
        ensure(ctx)
        try {
            var total = tabs.sumOf { sizeOf(File(it.dir)) }
            if (total <= CACHE_LIMIT) return
            for (t in tabs.filter { it.id != activeTab }.sortedBy { it.used }) {
                val pages = File(t.dir, "pages")
                if (!pages.exists()) continue
                total -= sizeOf(pages)
                pages.deleteRecursively()
                if (total <= CACHE_LIMIT) break
            }
        } catch (_: Exception) {
        }
    }

    fun newId(): String =
        java.lang.Long.toString(System.currentTimeMillis(), 36) +
            java.lang.Long.toString((Math.random() * 46656).toLong(), 36)

    // ------------------------------------------------------------ ayuda

    private fun copy(t: TabInfo) = TabInfo(
        t.id, t.name, t.mime, t.src, t.orig, t.entry, t.dir, t.file, t.srcSize, t.frac, t.page, t.scale, t.used
    )

    private fun sizeOf(f: File): Long =
        if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { sizeOf(it) } else f.length()

    private fun deleteDir(path: String) {
        try {
            File(path).deleteRecursively()
        } catch (_: Exception) {
        }
    }

    private fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            activeId = if (o.has("active")) o.getString("active") else null
            val a = o.optJSONArray("tabs") ?: JSONArray()
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                tabs.add(
                    TabInfo(
                        j.getString("id"),
                        j.optString("name", "archivo"),
                        j.optString("mime", "application/octet-stream"),
                        j.optString("src", ""),
                        j.optString("orig", ""),
                        if (j.has("entry")) j.getString("entry") else null,
                        j.getString("dir"),
                        j.getString("file"),
                        j.optLong("srcSize", -1),
                        j.optDouble("frac", 0.0).toFloat(),
                        j.optInt("page", 1),
                        j.optDouble("scale", 1.0).toFloat(),
                        j.optLong("used", 0)
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun save(ctx: Context) {
        try {
            val a = JSONArray()
            for (t in tabs) {
                val j = JSONObject()
                j.put("id", t.id)
                j.put("name", t.name)
                j.put("mime", t.mime)
                j.put("src", t.src)
                j.put("orig", t.orig)
                t.entry?.let { j.put("entry", it) }
                j.put("dir", t.dir)
                j.put("file", t.file)
                j.put("srcSize", t.srcSize)
                j.put("frac", t.frac.toDouble())
                j.put("page", t.page)
                j.put("scale", t.scale.toDouble())
                j.put("used", t.used)
                a.put(j)
            }
            val o = JSONObject().put("tabs", a)
            activeId?.let { o.put("active", it) }
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(File(ctx.filesDir, FILE))
        } catch (_: Exception) {
        }
    }
}
