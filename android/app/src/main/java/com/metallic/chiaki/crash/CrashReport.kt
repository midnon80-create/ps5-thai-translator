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
}

object CrashReport
{
	private const val FILE = "last_crash.txt"
	private const val PREF = "crash_report"
	private const val KEY_SEEN_EXIT = "seen_exit_ts"

	fun install(context: Context)
	{
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
		"PS5 แปลไทย ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
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
					trace = exit.traceInputStream?.bufferedReader()?.readText()?.take(4000) ?: ""
				return header() + "ประเภท: $kind\nรายละเอียด: ${exit.description}\nสถานะ: ${exit.status}\n\n$trace"
			}
			catch(_: Throwable) { }
		}
		return null
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
			setTextIsSelectable(true)
		}
		val title = TextView(this).apply {
			setTextColor(Color.rgb(255, 184, 77))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
			this.text = "แอปปิดไปเองเมื่อครั้งที่แล้ว\nกรุณาถ่ายรูปหน้านี้ส่งให้ผู้ขาย แล้วกด \"เปิดแอปต่อ\""
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
		setContentView(root)
		open.requestFocus()
	}

	private fun openMain()
	{
		startActivity(Intent(this, MainActivity::class.java))
		finish()
	}
}
