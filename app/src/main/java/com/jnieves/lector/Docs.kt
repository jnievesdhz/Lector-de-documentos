package com.jnieves.lector

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/** Tipos de archivo que la app reconoce. */
enum class Kind { PDF, DOCX, XLSX, PPTX, CSV, OLD_OFFICE, IMAGE, TEXT, OTHER }

/** Funciones de ayuda para nombres, tipos y archivos temporales. */
object Docs {

    const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    /** Tipos que se ofrecen al tocar "Abrir archivo". */
    val SUPPORTED_MIME = arrayOf("application/pdf", DOCX_MIME, XLSX_MIME, PPTX_MIME, "image/*", "text/*")

    private val IMAGE_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg"
    )
    private val TEXT_EXT = setOf(
        "txt", "md", "log", "json", "xml", "html", "htm",
        "ini", "cfg", "conf", "yaml", "yml", "srt", "r", "py", "kt", "java", "js", "css"
    )

    fun kindFor(mime: String?, name: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = mime?.lowercase() ?: ""
        return when {
            m == "application/pdf" || ext == "pdf" -> Kind.PDF
            ext in setOf("docx", "docm", "dotx") || m.contains("wordprocessingml") -> Kind.DOCX
            ext in setOf("xlsx", "xlsm", "xltx") || m.contains("spreadsheetml") -> Kind.XLSX
            ext in setOf("pptx", "ppsx", "potx") || m.contains("presentationml") -> Kind.PPTX
            ext in setOf("csv", "tsv") || m == "text/csv" || m == "text/comma-separated-values" ||
                m == "text/tab-separated-values" -> Kind.CSV
            ext in setOf("doc", "xls", "ppt") || m == "application/msword" ||
                m == "application/vnd.ms-excel" || m == "application/vnd.ms-powerpoint" -> Kind.OLD_OFFICE
            m.startsWith("image/") || ext in IMAGE_EXT -> Kind.IMAGE
            m.startsWith("text/") || m == "application/json" || ext in TEXT_EXT -> Kind.TEXT
            else -> Kind.OTHER
        }
    }

    fun isHeif(mime: String, name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = mime.lowercase()
        return ext == "heic" || ext == "heif" || m.contains("heic") || m.contains("heif")
    }

    /** Nombre visible del archivo (por ejemplo "Informe final.pdf"). */
    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                ctx.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx)
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        return name?.takeIf { it.isNotBlank() } ?: "archivo"
    }

    fun mimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** Nombre seguro para guardar la copia temporal, conservando la extensión. */
    fun safeFileName(name: String, mime: String): String {
        var n = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
        if (n.isEmpty() || n.startsWith(".")) n = "archivo$n"
        if (!n.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n = "$n.$it" }
        }
        if (n.length > 120) {
            val ext = n.substringAfterLast('.', "")
            n = n.take(110) + (if (ext.isNotEmpty() && ext.length <= 8) ".$ext" else "")
        }
        if (n == "vista.html" || n == "pages" || n == "imagen.jpg") n = "_$n"
        return n
    }

    /** Carpeta temporal nueva para un documento abierto. */
    fun newDocDir(ctx: Context): File {
        val d = File(File(ctx.cacheDir, "docs"), System.currentTimeMillis().toString())
        d.mkdirs()
        return d
    }

    /** Borra copias temporales de más de un día. */
    fun cleanOld(ctx: Context) {
        try {
            val limit = System.currentTimeMillis() - 24L * 3600L * 1000L
            File(ctx.cacheDir, "docs").listFiles()?.forEach {
                if (it.lastModified() < limit) it.deleteRecursively()
            }
        } catch (_: Exception) {
        }
    }

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
