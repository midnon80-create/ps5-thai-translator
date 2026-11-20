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
	@Volatile private var showing = false
	@Volatile private var checking = false

	fun maybeShow(activity: Activity)
	{
		if(com.metallic.chiaki.store.Store.IS_PLAY || showing || checking || activity.isFinishing) return
		val prefs = TranslatorPrefs(activity)
		if(prefs.licence.isNotBlank() || prefs.serverUrl.isBlank()) return
		show(activity, null)
	}

	/** Code box on request (help screen / packages), even when a code is already saved: the old one stays until a new one checks out. */
	fun ask(activity: Activity)
	{
		if(com.metallic.chiaki.store.Store.IS_PLAY || showing || activity.isFinishing) return
		show(activity, null)
	}

	private fun show(activity: Activity, error: String?)
	{
		val pad = dp(activity, 22)
		val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(activity, 8), pad, 0) }
		col.addView(TextView(activity).apply {
			text = "ใส่รหัสที่ได้รับจากผู้ขาย (เช่น EYE-AB12-CD34) เพื่อเปิดการแปลด้วย AI\n" +
				"ยังไม่มีรหัส? ทดลอง 1 วัน 29 บาท · รายเดือน 149 บาท · รายปี 329 บาท · ตลอดชีพ 759 บาท"
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
		})
		val input = EditText(activity).apply {
			hint = "พิมพ์รหัส เช่น EYE-AB12-CD34"
			setHintTextColor(Color.argb(110, 255, 255, 255))
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
			filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(40))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
			isSingleLine = true
		}
		col.addView(input)
		if(error != null) col.addView(TextView(activity).apply {
			text = error; setTextColor(Color.rgb(252, 211, 77)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
		})
		// keep what was typed if the box has to come back (wrong code, no internet)
		draft?.let { input.setText(it); input.setSelection(it.length) }
		showing = true
		val dialog = AlertDialog.Builder(activity)
			.setTitle("ยินดีต้อนรับสู่ Eyesmaxxing")
			.setView(col)
			.setPositiveButton("ใช้รหัสนี้") { _, _ -> apply(activity, input.text.toString()) }
			.setNegativeButton("ภายหลัง") { _, _ -> draft = null }
			.setOnDismissListener { showing = false }
			// on a TV remote, Back closes the keyboard and then the box: the box must survive Back
			.setCancelable(false)
			.create()
		dialog.setCanceledOnTouchOutside(false)
		// the keyboard's ✓ / Enter key sends the code right away
		input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
		input.setOnEditorActionListener { _, actionId, event ->
			val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP
			if(actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
				actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO || enter)
			{
				if(input.text.isNotBlank()) { dialog.dismiss(); apply(activity, input.text.toString()) }
				true
			}
			else false
		}
		input.addTextChangedListener(object : android.text.TextWatcher {
			override fun afterTextChanged(s: android.text.Editable?) { draft = s?.toString() }
			override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
			override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
		})
		dialog.show()
		// big, bright buttons that are easy to reach with the remote
		for(which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE))
			dialog.getButton(which)?.apply {
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
				setTextColor(if(which == AlertDialog.BUTTON_POSITIVE) Color.rgb(216, 180, 254) else Color.rgb(200, 200, 210))
				isAllCaps = false
			}
		input.requestFocus()
	}

	@Volatile private var draft: String? = null

	/** Checks the code with the server and always tells the result (also used by Settings › รหัสไลเซนส์). */
	fun apply(activity: Activity, raw: String)
	{
		val code = raw.trim().uppercase().replace(" ", "")
		if(code.length < 6) { show(activity, "พิมพ์รหัสให้ครบก่อนนะ"); return }
		val prefs = TranslatorPrefs(activity)
		val main = Handler(Looper.getMainLooper())
		// visible feedback at once: on slow internet the check takes seconds and players thought nothing happened
		val wait = try
		{
			AlertDialog.Builder(activity).setTitle("กำลังตรวจรหัส…").setMessage("รอสักครู่ (ต้องต่ออินเทอร์เน็ต)")
				.setCancelable(false).show()
		}
		catch(_: Throwable) { null }
		checking = true
		Thread {
			val cloud = CloudTranslator(prefs.serverUrl, code, CloudTranslator.deviceId(activity))
			val p = cloud.check()
			main.post {
				try { wait?.dismiss() } catch(_: Throwable) { }
				checking = false
				if(activity.isFinishing || activity.isDestroyed) return@post
				when(p)
				{
					CloudTranslator.Problem.NONE, CloudTranslator.Problem.POOL_EMPTY, CloudTranslator.Problem.DAILY_LIMIT -> {
						PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(TranslatorPrefs.KEY_LICENCE, code).apply(); draft = null
						val until = if(cloud.expires > 0) "\nใช้ได้ถึง " + java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale("th", "TH")).format(java.util.Date(cloud.expires)) else ""
						AlertDialog.Builder(activity)
							.setTitle("พร้อมใช้งาน ✔")
							.setMessage("แปลด้วย AI เปิดแล้ว$until\n\nกดการ์ด PS5 เพื่อเริ่มเล่น ซับไทยจะขึ้นเอง\n(ยังไม่ได้จับคู่ PS5? กดการ์ดแล้วทำตามขั้นตอนบนจอ)")
							.setPositiveButton("ตกลง", null)
							.show()
					}
					CloudTranslator.Problem.NOT_STARTED -> {
						PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(TranslatorPrefs.KEY_LICENCE, code).apply(); draft = null
						AlertDialog.Builder(activity)
							.setTitle("บันทึกรหัสแล้ว ✔")
							.setMessage("แปลด้วย AI จะเปิดวันที่ GTA 6 ออก (${CloudTranslator.aiStartText()})\n" +
								"เวลาของแพ็กเกจยังไม่เริ่มนับ จะเริ่มตอนเล่นครั้งแรกหลังเปิด\n\nระหว่างนี้จับคู่ PS5 ให้เรียบร้อยได้เลย (กดการ์ด PS5)")
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
