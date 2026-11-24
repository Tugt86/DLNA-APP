package com.mycast.dlna

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * نافذة اختيار الجودة (تُستعمل من شاشة يوتيوب ومن المشغّل أثناء المشاهدة).
 * كل خيار يظهر في بطاقة مستقلة، وبداخلها حقول معنونة: النوع، الجودة، السيرفر، الحجم، التشغيل.
 */
object QualityDialog {

    private fun dp(act: AppCompatActivity, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics
        ).toInt()

    private fun hostOf(url: String): String =
        try {
            (Uri.parse(url).host ?: "").removePrefix("www.")
        } catch (e: Exception) {
            ""
        }

    /** نوع الملف كما يفهمه المستخدم. */
    private fun typeOf(o: YouTubeResolver.Option): String {
        val c = o.custom
        return when {
            o.phoneAudio -> "يوتيوب (صورة فقط)"
            c != null && c.contains("يوتيوب") -> "يوتيوب"
            o.hls -> if (c != null && c.startsWith("تلقائي")) "HLS (تلقائي)" else "HLS"
            c == null -> "يوتيوب"
            else -> (o.ext ?: "mp4").uppercase()
        }
    }

    /** الجودة: الدقة إن عُرفت، وإلا معدل البت أو «غير معروفة». */
    private fun qualityOf(o: YouTubeResolver.Option): String {
        val c = o.custom
        if (o.phoneAudio && o.height > 0) return "${o.height}p" + (if (o.fps > 30) " ${o.fps}fps" else "")
        if (c != null && c.startsWith("تلقائي")) return "تلقائي (حسب الشبكة)"
        if (o.height > 0) return "${o.height}p" + (if (o.fps > 30) " ${o.fps}fps" else "")
        if (o.hls && o.bandwidth > 0) return "${o.bandwidth / 1000} kbps"
        return "غير معروفة"
    }

    private fun serverOf(o: YouTubeResolver.Option): String {
        val c = o.custom
        if (o.phoneAudio || c == null || c.contains("يوتيوب")) return "youtube.com"
        return hostOf(o.videoUrl)
    }

    fun show(
        act: AppCompatActivity,
        src: YouTubeResolver.Source,
        current: Int,
        title: String = "اختر الجودة",
        onPick: (Int) -> Unit
    ) {
        val cPrimary = ContextCompat.getColor(act, R.color.accent)
        val cCard = ContextCompat.getColor(act, R.color.surface_high)
        val cStroke = ContextCompat.getColor(act, R.color.stroke)
        val cText = ContextCompat.getColor(act, R.color.text_primary)
        val cSub = ContextCompat.getColor(act, R.color.text_secondary)
        val cLive = ContextCompat.getColor(act, R.color.live)

        val list = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 8))
        }
        val sv = ScrollView(act).apply { addView(list) }

        var dlgRef: androidx.appcompat.app.AlertDialog? = null

        fun field(parent: LinearLayout, label: String, value: String, valueColor: Int = cText, bold: Boolean = false) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(0, dp(act, 2), 0, dp(act, 2))
            }
            row.addView(TextView(act).apply {
                text = "$label :"
                setTextColor(cSub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                minWidth = dp(act, 62)
            })
            row.addView(TextView(act).apply {
                text = value
                setTextColor(valueColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                textDirection = View.TEXT_DIRECTION_LTR
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(act, 8)
                }
            })
            parent.addView(row)
        }

        src.options.forEachIndexed { i, o ->
            val selected = i == current
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(act, 14).toFloat()
                    setColor(cCard)
                    setStroke(dp(act, if (selected) 2 else 1), if (selected) cPrimary else cStroke)
                }
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(act, 10) }
                setOnClickListener {
                    dlgRef?.dismiss()
                    onPick(i)
                }
            }

            if (selected) {
                card.addView(TextView(act).apply {
                    text = "✓ الخيار الحالي / آخر اختيار"
                    setTextColor(cPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }
            field(card, "النوع", typeOf(o), bold = true)
            field(card, "الجودة", qualityOf(o), cPrimary, bold = true)
            val server = serverOf(o)
            if (server.isNotEmpty()) field(card, "السيرفر", server)
            if (o.sizeBytes > 0) field(card, "الحجم", fmtSize(o.sizeBytes))
            if (o.custom?.contains("مباشر") == true) field(card, "الحالة", "بث مباشر", cLive)
            if (o.phoneAudio) {
                field(card, "الصوت", "من الهاتف (إضافي)", cLive)
                field(card, "التشغيل", "يبدأ فورًا بلا تجهيز")
            } else {
                field(card, "التشغيل", if (o.muxed) "يبدأ فورًا" else "يحتاج تجهيزًا")
            }
            list.addView(card)
        }

        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setView(sv)
            .setNegativeButton("إلغاء", null)
            .create()
        dlgRef = dlg
        dlg.show()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
}
