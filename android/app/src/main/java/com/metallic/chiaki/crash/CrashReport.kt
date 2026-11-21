package com.metallic.chiaki.crash

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.main.MainActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves any crash to a file, so the next launch can show it on screen in Thai.
 * Customers can then photograph it: we have no other way to see what happened on their TV.
 */
class PS5TranslateApp : Application()
{
	override fun attachBaseContext(base: Context)
	{
		super.attachBaseContext(base)
		// installed this early so crashes in library start-up code (content providers) are caught too
		CrashReport.install(base)
	}

	override fun onCreate()
	{
		super.onCreate()
		CrashReport.startLog(this)
	}
}

object CrashReport
{
	private const val FILE = "last_crash.txt"
	private const val PREF = "crash_report"
	private const val KEY_SEEN_EXIT = "seen_exit_ts"

	private const val LOG_DIR = "applog"
	private const val KEY_LAST_INFLIGHT = "last_inflight"

	@Volatile private var appContext: Context? = null

	/**
	 * Marks that an optional native part ("offline" translation, "speech") is running. If the app dies in a
	 * native crash while it is marked, that part is switched off on this TV from then on (see [disabled]).
	 */
	fun enter(what: String) { try { File(appContext!!.filesDir, "inflight_$what").writeText("1") } catch(_: Throwable) { } }
	fun leave(what: String) { try { File(appContext!!.filesDir, "inflight_$what").delete() } catch(_: Throwable) { } }
	fun disabled(what: String) =
		appContext?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.getBoolean("off_$what", false) == true

	/**
	 * Keeps this app's own log in a small file while it runs (an app may read its own log lines).
	 * After a native crash, the previous run's file holds "Fatal signal ... (thread name)" and the
	 * backtrace, which tell which part crashed. Android 11 records nothing else for native crashes.
	 */
	fun startLog(context: Context)
	{
		try
		{
			// parts that were running when the last run ended (checked against the exit reason later)
			val inflight = context.filesDir.listFiles()?.filter { it.name.startsWith("inflight_") } ?: emptyList()
			context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
				.putString(KEY_LAST_INFLIGHT, inflight.joinToString(",") { it.name.removePrefix("inflight_") }).commit()
			inflight.forEach { it.delete() }
			// once in 1.8.0: older versions switched the good screen reader off after unrelated crashes,
			// leaving those TVs on the weak backup reader (subtitles came and went). Give it one fresh try.
			val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
			if(!p.getBoolean("ocr_retry_180", false))
				p.edit().remove("off_ocr").putBoolean("ocr_retry_180", true).commit()
		}
		catch(_: Throwable) { }
		try
		{
			val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
			val prev = File(dir, "prev.log")
			val cur = listOf(File(dir, "run.log.1"), File(dir, "run.log")).filter { it.exists() }
			if(cur.isNotEmpty())
			{
				prev.writeText(cur.joinToString("") { it.readText() })
				cur.forEach { it.delete() }
			}
			Runtime.getRuntime().exec(arrayOf("logcat", "-b", "main,crash", "-v", "threadtime",
				"-f", File(dir, "run.log").absolutePath, "-r", "400", "-n", "1"))
		}
		catch(_: Throwable) { }
	}

	/** The crash lines from the previous run's log, or "" if none were recorded. */
	private fun previousCrashLog(context: Context): String
	{
		return try
		{
			val lines = File(File(context.filesDir, LOG_DIR), "prev.log").takeIf { it.exists() }?.readLines() ?: return ""
			val i = lines.indexOfLast { it.contains("Fatal signal") }
			if(i < 0) return "(ไม่พบบรรทัด Fatal signal ใน log)\n" + lines.takeLast(40).joinToString("\n")
			lines.subList(maxOf(0, i - 30), minOf(lines.size, i + 70)).joinToString("\n")
		}
		catch(_: Throwable) { "" }
	}

	fun install(context: Context)
	{
		appContext = context
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			try
			{
				val sw = StringWriter()
				error.printStackTrace(PrintWriter(sw))
				File(context.filesDir, FILE).writeText(header() + "Thread: ${thread.name}\n\n" + sw.toString().take(6000))
			}
			catch(_: Throwable) { }
			previous?.uncaughtException(thread, error)
		}
	}

	private fun header() =
		"Eyesmaxxing ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
		"เครื่อง: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n" +
		"Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · CPU ${Build.SUPPORTED_ABIS.joinToString()}\n" +
		"เวลา: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n"

	/** A Java crash saved by the handler, or a native crash Android recorded (Android 11+). */
	fun pending(context: Context): String?
	{
		val f = File(context.filesDir, FILE)
		if(f.exists()) return f.readText()
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
		{
			try
			{
				val am = context.getSystemService(ActivityManager::class.java)
				val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
				val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
				if(exit.timestamp <= prefs.getLong(KEY_SEEN_EXIT, 0)) return null
				if(exit.reason != ApplicationExitInfo.REASON_CRASH_NATIVE && exit.reason != ApplicationExitInfo.REASON_ANR &&
					exit.reason != ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
					return null
				val kind = when(exit.reason) {
					ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
					ApplicationExitInfo.REASON_ANR -> "ค้าง (ANR)"
					else -> "เริ่มต้นไม่สำเร็จ"
				}
				var trace = ""
				if(exit.reason == ApplicationExitInfo.REASON_ANR)
				{
					val full = exit.traceInputStream?.bufferedReader()?.readText()?.take(400_000) ?: ""
					val i = full.indexOf("\"main\"")
					trace = if(i >= 0) full.substring(i).take(6000) else full.take(6000)
				}
				else if(exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
				{
					val inflight = prefs.getString(KEY_LAST_INFLIGHT, "") ?: ""
					val log = previousCrashLog(context)
					if(inflight.isNotEmpty())
					{
						// Screen reading runs all game long, so it is "in flight" during almost every crash.
						// Switch a part off only when the crash itself points at it (its library in the backtrace);
						// before, any crash (video, network, the TV's own driver) turned the good reader off for good.
						val lower = log.lowercase()
						val noEvidence = !log.contains("Fatal signal")   // log missing: stay on the safe side as before
						val blamed = inflight.split(",").filter { it.isNotBlank() }.filter { part -> noEvidence ||
							when(part)
							{
								"ocr" -> "mlkit" in lower || "ocr_pipeline" in lower || "text-recognition" in lower
								"ocr_tess" -> "tess" in lower || "lept" in lower
								else -> true
							}
						}
						if(blamed.isNotEmpty())
						{
							val e = prefs.edit()
							blamed.forEach { e.putBoolean("off_$it", true) }
							e.apply()
						}
						trace = "ทำงานอยู่ตอนแครช: $inflight · ปิดในทีวีนี้: ${blamed.joinToString(",").ifEmpty { "-" }}\n\n"
					}
					trace += log.take(7000)
				}
				return header() + "ประเภท: $kind\nรายละเอียด: ${exit.description}\nสถานะ: ${exit.status}\n\n$trace"
			}
			catch(_: Throwable) { }
		}
		return null
	}

	/** Sends the report to the Eyesmaxxing server (background, best effort). */
	fun upload(context: Context, report: String)
	{
		Thread {
			try
			{
				val url = com.metallic.chiaki.translate.TranslatorPrefs(context).serverUrl
				if(url.isBlank()) return@Thread
				val conn = java.net.URL(url.trimEnd('/') + "/v1/crash").openConnection() as java.net.HttpURLConnection
				conn.requestMethod = "POST"; conn.connectTimeout = 5000; conn.readTimeout = 8000; conn.doOutput = true
				conn.setRequestProperty("Content-Type", "application/json")
				val body = org.json.JSONObject()
					.put("version", BuildConfig.VERSION_NAME)
					.put("device", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")")
					.put("android", Build.VERSION.SDK_INT)
					.put("report", report.take(12000))
				conn.outputStream.use { it.write(body.toString().toByteArray()) }
				conn.responseCode
				conn.disconnect()
			}
			catch(_: Throwable) { }
		}.start()
	}

	fun clear(context: Context)
	{
		File(context.filesDir, FILE).delete()
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
		{
			try
			{
				val am = context.getSystemService(ActivityManager::class.java)
				val ts = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.timestamp ?: 0
				context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong(KEY_SEEN_EXIT, ts).apply()
			}
			catch(_: Throwable) { }
		}
	}
}

/**
 * Launcher entry point. Shows the last crash (if any) before opening the main screen,
 * so the report is visible even when the main screen itself is what crashes.
 */
class StartActivity : Activity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val report = try { CrashReport.pending(this) } catch(_: Throwable) { null }
		if(report == null)
		{
			openMain()
			return
		}
		val dp = resources.displayMetrics.density
		val details = TextView(this).apply {
			setTextColor(Color.WHITE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			typeface = android.graphics.Typeface.MONOSPACE
			this.text = report
			isFocusable = false
		}
		CrashReport.upload(this, report)
		val title = TextView(this).apply {
			setTextColor(Color.rgb(255, 184, 77))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
			this.text = "แอปปิดไปเองเมื่อครั้งที่แล้ว (ส่งรายงานให้ผู้ขายอัตโนมัติแล้ว)\nกด \"เปิดแอปต่อ\" หรือรอ 15 วินาที แอปจะเปิดเอง"
		}
		val open = Button(this).apply {
			text = "เปิดแอปต่อ"
			setOnClickListener { CrashReport.clear(this@StartActivity); openMain() }
		}
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setBackgroundColor(Color.rgb(7, 11, 22))
			setPadding((32 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt(), (24 * dp).toInt())
			addView(title)
			addView(open, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (12 * dp).toInt(); gravity = Gravity.START })
			addView(ScrollView(this@StartActivity).apply { addView(details) },
				LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (12 * dp).toInt() })
		}
		root.fitsSystemWindows = true
		setContentView(root)
		open.isFocusable = true
		open.isFocusableInTouchMode = true
		open.requestFocus()
		root.postDelayed({ if(!isFinishing) { CrashReport.clear(this); openMain() } }, 15_000)
	}

	// any remote key opens the app (some TVs never move focus onto the button)
	override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean
	{
		if(keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
			keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A || keyCode == android.view.KeyEvent.KEYCODE_BACK)
		{
			if(!isFinishing) { CrashReport.clear(this); openMain() }
			return true
		}
		return super.onKeyUp(keyCode, event)
	}

	private fun openMain()
	{
		startActivity(Intent(this, com.metallic.chiaki.tv.TvActivity::class.java))
		finish()
	}
}
