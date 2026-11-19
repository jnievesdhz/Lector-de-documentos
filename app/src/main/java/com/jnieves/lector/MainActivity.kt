package com.jnieves.lector

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Pantalla de inicio: botón para escoger un archivo. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Docs.cleanOld(this)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        val icon = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        root.addView(icon, LinearLayout.LayoutParams(dp(96), dp(96)))

        val title = TextView(this).apply {
            text = "Lector de documentos"
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "PDF, Word, Excel, PowerPoint, CSV,\nimágenes y texto. Sin anuncios."
            textSize = 15f
            gravity = Gravity.CENTER
            alpha = 0.75f
        }
        root.addView(subtitle)

        val open = Button(this).apply {
            text = "Abrir archivo"
            textSize = 17f
            setOnClickListener { pickFile() }
        }
        root.addView(
            open,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(32) }
        )

        setContentView(root)
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, Docs.SUPPORTED_MIME)
        }
        try {
            startActivityForResult(intent, REQ_OPEN)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No se encontró el selector de archivos", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            // Guarda el permiso de lectura (servirá para "archivos recientes").
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val view = Intent(this, ViewerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, contentResolver.getType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(view)
    }

    companion object {
        private const val REQ_OPEN = 10
    }
}
