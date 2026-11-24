package com.mycast.dlna

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * شاشة سجل المشاهدة: كل فيديو مع مصدره (الهاتف أو موقع) وآخر دقيقة وصلت إليها.
 * الضغط على عنصر يرجع بمعرّفه إلى الشاشة الرئيسية لتستأنف التشغيل.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        CastSession.init(this)
        list = findViewById(R.id.histList)
        empty = findViewById(R.id.histEmpty)
        findViewById<ImageButton>(R.id.btnHistBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnHistClear).setOnClickListener { confirmClear() }
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        val items = History.all()
        list.removeAllViews()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnHistClear).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        for (it in items) list.addView(row(it))
    }

    private fun row(h: History.Item): View {
        val local = h.type == History.LOCAL
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_tile)
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(12), dp(6), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener { pick(h) }
        }

        // أيقونة المصدر
        val iconBox = FrameLayout(this).apply {
            background = getDrawable(R.drawable.bg_chip_icon)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        iconBox.addView(
            ImageView(this).apply {
                setImageResource(if (local) R.drawable.ic_movie else R.drawable.ic_globe)
                imageTintList = ColorStateList.valueOf(getColor(if (local) R.color.accent else R.color.accent2))
            },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        )
        card.addView(iconBox)

        // النصوص
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(6)
            }
        }
        col.addView(TextView(this).apply {
            text = h.title.ifBlank { if (local) "فيديو من الهاتف" else h.sourceLabel }
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = (if (local) "📱 من الهاتف" else "🌐 ${h.sourceLabel}") + " · " + History.ago(h.ts)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.accent2))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = when {
                h.done -> "شوهد بالكامل · سيبدأ من البداية"
                h.dur > 0 -> "توقفت عند ${fmtTime(h.pos)} من ${fmtTime(h.dur)}"
                else -> "توقفت عند ${fmtTime(h.pos)}"
            }
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        if (!local) {
            col.addView(TextView(this).apply {
                text = "يُعاد التشغيل من نفس الرابط، وقد يفشل إن تغيّر"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
                alpha = 0.8f
            })
        }
        if (h.dur > 0 && !h.done) {
            col.addView(
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = h.dur
                    progress = h.pos.coerceIn(0, h.dur)
                    progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
                    progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.stroke))
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(8) }
            )
        }
        card.addView(col)

        // حذف
        card.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
                background = this@HistoryActivity.getDrawable(R.drawable.bg_icon_btn_flat)
                contentDescription = "حذف من السجل"
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setOnClickListener { _ ->
                    History.remove(h.id)
                    refresh()
                }
            },
            LinearLayout.LayoutParams(dp(38), dp(38))
        )
        return card
    }

    private fun pick(h: History.Item) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, h.id))
        finish()
    }

    private fun confirmClear() {
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("مسح سجل المشاهدة؟")
            .setMessage("سيُحذف كل السجل ومواضع المتابعة المحفوظة فيه.")
            .setPositiveButton("مسح") { _, _ ->
                History.clear()
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .create()
        d.show()
        d.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    companion object {
        const val EXTRA_ID = "history_id"
    }
}
