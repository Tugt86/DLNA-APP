package com.mycast.dlna

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** نافذة اختيار سرعة التشغيل (تُستعمل من المشغّل ومن المتصفح). */
object SpeedDialog {
    private val main = Handler(Looper.getMainLooper())

    fun show(act: AppCompatActivity) {
        if (!CastSession.casting) {
            Toast.makeText(act, "تعمل السرعة أثناء البث فقط", Toast.LENGTH_SHORT).show()
            return
        }
        if (CastSession.phoneAudioWanted) {
            Toast.makeText(act, "أوقف «الصوت على الهاتف» أولًا: تغيير السرعة يفسد مزامنة الصوت", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val opts = CastSession.speedOptions()
            val advertised = CastSession.speedAdvertised()
            main.post { if (!act.isFinishing) open(act, opts, advertised) }
        }.start()
    }

    private fun open(act: AppCompatActivity, opts: List<String>, advertised: Boolean) {
        val cur = CastSession.speed
        val items = opts.map {
            (if (Controller.speedValue(it) == Controller.speedValue(cur)) "✓  " else "") + Controller.speedLabel(it)
        }.toTypedArray()
        val b = MaterialAlertDialogBuilder(act)
            .setTitle("سرعة التشغيل")
            .setItems(items) { _, which ->
                val sp = opts[which]
                CastSession.setSpeed(sp) { ok ->
                    Toast.makeText(
                        act,
                        if (ok) "السرعة: ${Controller.speedLabel(sp)}"
                        else "رفض التلفاز هذه السرعة. بعض التلفازات لا تدعم تغيير السرعة عبر DLNA",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("إلغاء", null)
        if (!advertised) b.setMessage("هذا التلفاز لم يُعلن عن سرعات مدعومة؛ نجرّب القيم الشائعة وقد يتجاهلها.")
        val dlg = b.create()
        dlg.show()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
}
