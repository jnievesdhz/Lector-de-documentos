package com.jnieves.lector

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Colores, medidas y piezas de interfaz compartidas (estética grafito y lavanda). */
object Ui {
    const val BG = 0xFF0F0F12.toInt()
    const val SURFACE = 0xFF18181C.toInt()
    const val TILE = 0xFF1A1A1F.toInt()
    const val FIELD = 0xFF111114.toInt()
    const val BORDER = 0xFF2A2A30.toInt()
    const val BORDER_SOFT = 0xFF26262C.toInt()
    const val DIVIDER = 0xFF24242A.toInt()
    const val TEXT = 0xFFEEEEF2.toInt()
    const val TEXT_SOFT = 0xFFD4D4DA.toInt()
    const val ICON = 0xFFC9C9D1.toInt()
    const val MUTED = 0xFF9E9EA8.toInt()
    const val FAINT = 0xFF6E6E78.toInt()
    const val ACCENT = 0xFFC3B4FF.toInt()
    const val ACTIVE_BG = 0xFF26232F.toInt()
    const val ACTIVE_TEXT = 0xFFDCD3FF.toInt()
    const val ACTIVE_BORDER = 0xFF3D3850.toInt()
    const val FOLDER = 0xFFB7A6FF.toInt()
    const val DANGER = 0xFFFF9AA8.toInt()

    /** Pesos de letra explícitos: la fuente del sistema de Xiaomi ignora la negrita simple. */
    val REGULAR: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)

    /** Línea de 2 dp pegada al borde de abajo (indicador de la pestaña activa). */
    fun underline(ctx: Context, color: Int): Drawable {
        val line = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, 1f).toFloat()
        }
        return LayerDrawable(arrayOf<Drawable>(line)).apply {
            setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(0, dp(ctx, 2f))
        }
    }

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    fun round(
        ctx: Context,
        color: Int,
        radiusDp: Float,
        strokeColor: Int = 0,
        strokeDp: Float = 0f,
        dashed: Boolean = false
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeDp > 0f) {
            if (dashed) setStroke(dp(ctx, strokeDp), strokeColor, dp(ctx, 6f).toFloat(), dp(ctx, 4f).toFloat())
            else setStroke(dp(ctx, strokeDp), strokeColor)
        }
    }

    /** Fondo que se ilumina al tocar, con esquinas redondeadas. */
    fun ripple(ctx: Context, content: Drawable?, radiusDp: Float = 12f): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, mask)
    }

    /** Ripple circular para botones de ícono. */
    fun borderlessRipple(): RippleDrawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)

    fun text(ctx: Context, s: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = if (bold) BOLD else REGULAR
            includeFontPadding = true
        }

    fun singleLine(t: TextView): TextView {
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    fun icon(ctx: Context, res: Int, color: Int, sizeDp: Int): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        }

    /** Botón de ícono de 44 dp. */
    fun iconButton(ctx: Context, res: Int, desc: String, color: Int = ICON, onClick: (View) -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(color)
            contentDescription = desc
            val p = dp(ctx, 11)
            setPadding(p, p, p, p)
            background = borderlessRipple()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(it) }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44))
        }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    // ---------------------------------------------------------- tipos de archivo

    fun kindLabel(k: Kind): String = when (k) {
        Kind.PDF -> "PDF"
        Kind.DOCX -> "Word"
        Kind.XLSX -> "Excel"
        Kind.PPTX -> "PowerPoint"
        Kind.CSV -> "CSV"
        Kind.IMAGE -> "Imagen"
        Kind.TEXT -> "Texto"
        Kind.OLD_OFFICE -> "Office antiguo"
        Kind.OTHER -> "Archivo"
    }

    fun kindColor(k: Kind): Int = when (k) {
        Kind.PDF -> 0xFFFF8A9A.toInt()
        Kind.DOCX -> 0xFF8DB4FF.toInt()
        Kind.XLSX -> 0xFF7BDDA0.toInt()
        Kind.CSV -> 0xFF6FD8CF.toInt()
        Kind.PPTX -> 0xFFFFB37A.toInt()
        Kind.IMAGE -> 0xFFC7B9FF.toInt()
        Kind.TEXT -> ICON
        Kind.OLD_OFFICE, Kind.OTHER -> MUTED
    }

    fun kindIcon(k: Kind): Int = when (k) {
        Kind.PDF -> R.drawable.ic_file_pdf
        Kind.DOCX -> R.drawable.ic_file_doc
        Kind.XLSX, Kind.CSV -> R.drawable.ic_file_xls
        Kind.PPTX -> R.drawable.ic_file_ppt
        Kind.IMAGE -> R.drawable.ic_file_img
        else -> R.drawable.ic_file_txt
    }

    // ---------------------------------------------------------- fechas y tamaños

    private val MONTHS = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    private val DAYS = arrayOf("domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado")

    /** Días completos entre la fecha y hoy (0 = hoy, 1 = ayer…). */
    fun daysAgo(time: Long): Int {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (time >= today) return 0
        return (((today - time) / 86_400_000L) + 1).toInt()
    }

    fun shortDate(time: Long): String {
        if (time <= 0) return ""
        val c = Calendar.getInstance().apply { timeInMillis = time }
        val d = daysAgo(time)
        return when {
            d == 0 -> String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            d == 1 -> "ayer"
            d < 7 -> DAYS[c.get(Calendar.DAY_OF_WEEK) - 1]
            c.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR) ->
                "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
            else -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
        }
    }

    fun longDate(time: Long): String {
        if (time <= 0) return "—"
        val c = Calendar.getInstance().apply { timeInMillis = time }
        return String.format(
            Locale.US, "%d %s %d, %02d:%02d",
            c.get(Calendar.DAY_OF_MONTH), MONTHS[c.get(Calendar.MONTH)], c.get(Calendar.YEAR),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }

    fun size(bytes: Long): String {
        if (bytes < 0) return ""
        val es = Locale.forLanguageTag("es-CO")
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> "${(bytes / 1024.0).roundToInt()} KB"
            bytes < 1024L * 1024 * 1024 -> String.format(es, "%.1f MB", bytes / 1048576.0)
            else -> String.format(es, "%.1f GB", bytes / 1073741824.0)
        }
    }
}
