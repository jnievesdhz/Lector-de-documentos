package com.jnieves.lector

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Un archivo del historial (recientes y favoritos). */
class Entry(
    val id: String,
    var uri: String,
    /** Ruta de la copia privada dentro de la app (relativa a filesDir), si el archivo llegó sin permiso duradero. */
    var copyPath: String?,
    var name: String,
    var mime: String,
    var size: Long,
    /** Última vez que se abrió (0 = agregado a favoritos sin abrirlo). */
    var opened: Long,
    var fav: Boolean
) {
    val kind: Kind get() = Docs.kindFor(mime, name)
}

/** Una carpeta que el usuario agregó (con permiso de lectura guardado). */
class Folder(val uri: String, var name: String)

/**
 * Historial de archivos, favoritos y carpetas, guardado en un archivo JSON dentro de la app.
 * Nada sale del teléfono.
 */
object Library {

    private const val FILE = "biblioteca.json"
    private const val MAX_ENTRIES = 200
    private const val MAX_COPIES = 60

    private var loaded = false
    private val entries = ArrayList<Entry>()
    private val folders = ArrayList<Folder>()

    // ------------------------------------------------------------ lectura

    @Synchronized
    fun entries(ctx: Context): List<Entry> {
        ensure(ctx)
        return entries.sortedByDescending { it.opened }.map { clone(it) }
    }

    @Synchronized
    fun find(ctx: Context, id: String): Entry? {
        ensure(ctx)
        return entries.firstOrNull { it.id == id }?.let { clone(it) }
    }

    @Synchronized
    fun folders(ctx: Context): List<Folder> {
        ensure(ctx)
        return folders.map { Folder(it.uri, it.name) }
    }

    /** Dirección para abrir o compartir el archivo: la copia privada si existe, si no el original. */
    fun uriFor(ctx: Context, e: Entry): Uri {
        val c = e.copyPath
        if (c != null) {
            val f = File(ctx.filesDir, c)
            if (f.exists()) return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        }
        return Uri.parse(e.uri)
    }

    fun thumbFile(ctx: Context, id: String): File = File(File(ctx.filesDir, "miniaturas"), "$id.jpg")

    // ------------------------------------------------------------ cambios

    /**
     * Anota que se abrió un archivo. [local] es la copia temporal que hizo el visor.
     * Devuelve el id de la entrada.
     */
    @Synchronized
    fun record(ctx: Context, source: Uri, name: String, mime: String, local: File, knownId: String?): String {
        ensure(ctx)
        val now = System.currentTimeMillis()
        val size = local.length()
        if (knownId != null) {
            val e = entries.firstOrNull { it.id == knownId }
            if (e != null) {
                e.opened = now
                e.size = size
                save(ctx)
                return e.id
            }
        }
        val src = source.toString()
        var e = entries.firstOrNull { it.uri == src }
            ?: entries.firstOrNull { it.name == name && it.size == size }
        val lasting = isLasting(ctx, source)
        if (e == null) {
            e = Entry(newId(), src, null, name, mime, size, now, false)
            entries.add(e)
        } else {
            e.name = name
            e.mime = mime
            e.size = size
            e.opened = now
        }
        if (lasting) {
            e.uri = src
            deleteCopy(ctx, e)
        } else if (e.copyPath == null && e.uri != src && hasPersisted(ctx, Uri.parse(e.uri))) {
            // Es el mismo archivo que ya teníamos con un enlace duradero: se conserva ese enlace.
        } else {
            val copyOk = e.copyPath?.let { File(ctx.filesDir, it).exists() } == true
            if (!copyOk || e.uri != src) {
                deleteCopy(ctx, e)
                val rel = "copias/" + e.id + "/" + Docs.safeFileName(name, mime)
                val dest = File(ctx.filesDir, rel)
                dest.parentFile?.mkdirs()
                local.copyTo(dest, overwrite = true)
                e.copyPath = rel
            }
            e.uri = src
        }
        trim(ctx)
        save(ctx)
        return e.id
    }

    /** Agrega a favoritos un archivo de una carpeta que todavía no está en el historial. */
    @Synchronized
    fun addFavorite(ctx: Context, uri: Uri, name: String, mime: String, size: Long): String {
        ensure(ctx)
        val src = uri.toString()
        val e = entries.firstOrNull { it.uri == src }
            ?: Entry(newId(), src, null, name, mime, size, 0L, false).also { entries.add(it) }
        e.fav = true
        save(ctx)
        return e.id
    }

    @Synchronized
    fun setFavorite(ctx: Context, id: String, fav: Boolean) {
        ensure(ctx)
        val e = entries.firstOrNull { it.id == id } ?: return
        e.fav = fav
        // Un favorito que nunca se abrió no tiene por qué quedarse en la lista
        if (!fav && e.opened == 0L) removeEntry(ctx, e)
        save(ctx)
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        ensure(ctx)
        val e = entries.firstOrNull { it.id == id } ?: return
        removeEntry(ctx, e)
        save(ctx)
    }

    /** Quita un archivo de Recientes. Si es favorito, sigue en Favoritos. */
    @Synchronized
    fun removeFromRecents(ctx: Context, id: String) {
        ensure(ctx)
        val e = entries.firstOrNull { it.id == id } ?: return
        if (e.fav) e.opened = 0L else removeEntry(ctx, e)
        save(ctx)
    }

    /** Borra el historial pero conserva los favoritos. */
    @Synchronized
    fun clearRecents(ctx: Context) {
        ensure(ctx)
        entries.filter { !it.fav }.forEach { removeEntry(ctx, it) }
        entries.forEach { it.opened = 0L }
        save(ctx)
    }

    @Synchronized
    fun addFolder(ctx: Context, uri: Uri, name: String) {
        ensure(ctx)
        val s = uri.toString()
        val f = folders.firstOrNull { it.uri == s }
        if (f != null) f.name = name else folders.add(Folder(s, name))
        save(ctx)
    }

    @Synchronized
    fun removeFolder(ctx: Context, uri: String) {
        ensure(ctx)
        folders.removeAll { it.uri == uri }
        save(ctx)
    }

    fun saveThumb(ctx: Context, id: String, bmp: Bitmap) {
        try {
            val f = thumbFile(ctx, id)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(f)
        } catch (_: Exception) {
        } finally {
            bmp.recycle()
        }
    }

    // ------------------------------------------------------------ ayuda

    private fun clone(e: Entry) = Entry(e.id, e.uri, e.copyPath, e.name, e.mime, e.size, e.opened, e.fav)

    private fun newId(): String =
        java.lang.Long.toString(System.currentTimeMillis(), 36) +
            java.lang.Long.toString((Math.random() * 46656).toLong(), 36)

    private fun hasPersisted(ctx: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val s = uri.toString()
        return try {
            ctx.contentResolver.persistedUriPermissions.any {
                it.isReadPermission && (it.uri == uri || s.startsWith(it.uri.toString() + "/document/"))
            }
        } catch (_: Exception) {
            false
        }
    }

    /** ¿Se podrá volver a abrir este enlace más adelante, sin copia? */
    private fun isLasting(ctx: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        if (uri.authority == ctx.packageName + ".files") return false
        if (hasPersisted(ctx, uri)) return true
        return try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteCopy(ctx: Context, e: Entry) {
        val c = e.copyPath ?: return
        try {
            File(ctx.filesDir, c).parentFile?.deleteRecursively()
        } catch (_: Exception) {
        }
        e.copyPath = null
    }

    private fun removeEntry(ctx: Context, e: Entry) {
        deleteCopy(ctx, e)
        thumbFile(ctx, e.id).delete()
        entries.remove(e)
    }

    /** Mantiene el historial y las copias dentro de un límite (los favoritos no se tocan). */
    private fun trim(ctx: Context) {
        val old = entries.filter { !it.fav }.sortedByDescending { it.opened }
        if (old.size > MAX_ENTRIES) old.drop(MAX_ENTRIES).forEach { removeEntry(ctx, it) }
        val copies = entries.filter { !it.fav && it.copyPath != null }.sortedByDescending { it.opened }
        if (copies.size > MAX_COPIES) copies.drop(MAX_COPIES).forEach { removeEntry(ctx, it) }
    }

    private fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val a = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                entries.add(
                    Entry(
                        j.getString("id"),
                        j.getString("uri"),
                        if (j.has("copy")) j.getString("copy") else null,
                        j.optString("name", "archivo"),
                        j.optString("mime", "application/octet-stream"),
                        j.optLong("size", -1),
                        j.optLong("opened", 0),
                        j.optBoolean("fav", false)
                    )
                )
            }
            val fs = o.optJSONArray("folders") ?: JSONArray()
            for (i in 0 until fs.length()) {
                val j = fs.getJSONObject(i)
                folders.add(Folder(j.getString("uri"), j.optString("name", "Carpeta")))
            }
        } catch (_: Exception) {
        }
    }

    private fun save(ctx: Context) {
        try {
            val a = JSONArray()
            for (e in entries) {
                val j = JSONObject()
                j.put("id", e.id)
                j.put("uri", e.uri)
                e.copyPath?.let { j.put("copy", it) }
                j.put("name", e.name)
                j.put("mime", e.mime)
                j.put("size", e.size)
                j.put("opened", e.opened)
                j.put("fav", e.fav)
                a.put(j)
            }
            val fs = JSONArray()
            for (f in folders) fs.put(JSONObject().put("uri", f.uri).put("name", f.name))
            val o = JSONObject().put("entries", a).put("folders", fs)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(File(ctx.filesDir, FILE))
        } catch (_: Exception) {
        }
    }
}
