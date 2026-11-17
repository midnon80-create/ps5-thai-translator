package com.metallic.chiaki.translate

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager

/**
 * Welcome box on app start while no licence code is set (GitHub build):
 * the customer types the code from the seller right away, and the app checks it on the spot.
 * "ภายหลัง" closes it; it comes back on the next start until a code is set.
 */
object LicencePrompt
{
	private var shownThisRun = false

	fun maybeShow(activity: Activity)
	{
		if(com.metallic.chiaki.store.Store.IS_PLAY || shownThisRun || activity.isFinishing) return
		val prefs = TranslatorPrefs(activity)
		if(prefs.licence.isNotBlank() || prefs.serverUrl.isBlank()) return
		shownThisRun = true
		show(activity, null)
	}

	private fun show(activity: Activity, error: String?)
	{
		val pad = dp(activity, 22)
		val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(activity, 8), pad, 0) }
		col.addView(TextView(activity).apply {
			text = "ใส่รหัสที่ได้รับจากผู้ขาย (เช่น EYE-AB12-CD34) เพื่อเปิดการแปลด้วย AI\n" +
				"ยังไม่มีรหัส? ทดลอง 1 วัน 29 บาท · รายปี 329 บาท · ตลอดชีพ 759 บาท"
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
		})
		val input = EditText(activity).apply {
			hint = "EYE-XXXX-XXXX"
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
			filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(40))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
			isSingleLine = true
		}
		col.addView(input)
		if(error != null) col.addView(TextView(activity).apply {
			text = error; setTextColor(Color.rgb(252, 211, 77)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
		})
		AlertDialog.Builder(activity)
			.setTitle("ยินดีต้อนรับสู่ Eyesmaxxing")
			.setView(col)
			.setPositiveButton("ใช้รหัสนี้") { _, _ -> apply(activity, input.text.toString()) }
			.setNegativeButton("ภายหลัง", null)
			.show()
		input.requestFocus()
	}

	private fun apply(activity: Activity, raw: String)
	{
		val code = raw.trim().uppercase().replace(" ", "")
		if(code.length < 6) { show(activity, "พิมพ์รหัสให้ครบก่อนนะ"); return }
		val prefs = TranslatorPrefs(activity)
		val main = Handler(Looper.getMainLooper())
		Thread {
			val cloud = CloudTranslator(prefs.serverUrl, code, CloudTranslator.deviceId(activity))
			val p = cloud.check()
			main.post {
				if(activity.isFinishing) return@post
				when(p)
				{
					CloudTranslator.Problem.NONE, CloudTranslator.Problem.POOL_EMPTY, CloudTranslator.Problem.DAILY_LIMIT -> {
						PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(TranslatorPrefs.KEY_LICENCE, code).apply()
						val until = if(cloud.expires > 0) "\nใช้ได้ถึง " + java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale("th", "TH")).format(java.util.Date(cloud.expires)) else ""
						AlertDialog.Builder(activity)
							.setTitle("พร้อมใช้งาน ✔")
							.setMessage("แปลด้วย AI เปิดแล้ว$until\n\nกดการ์ด PS5 เพื่อเริ่มเล่น ซับไทยจะขึ้นเอง\n(ยังไม่ได้จับคู่ PS5? กดการ์ดแล้วทำตามขั้นตอนบนจอ)")
							.setPositiveButton("ตกลง", null)
							.show()
					}
					CloudTranslator.Problem.OFFLINE -> show(activity, "ต่ออินเทอร์เน็ตไม่ได้ ตรวจเน็ตแล้วลองอีกครั้ง")
					else -> show(activity, CloudTranslator.problemText(p, cloud.plan))
				}
			}
		}.start()
	}

	private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
