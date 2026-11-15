package com.metallic.chiaki.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.metallic.chiaki.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer version and installs it with one confirmation,
 * so people never have to type the Downloader code again.
 */
object UpdateManager
{
	private const val PREF_LAST_CHECK = "update_last_check"
	private const val AUTO_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

	class Release(val version: String, val notes: String, val apkUrl: String)

	/** [manual] = the user pressed "check for updates": always check and always say the result. */
	fun checkAndPrompt(activity: Activity, manual: Boolean, onDone: (() -> Unit)? = null)
	{
		val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
		val now = System.currentTimeMillis()
		if(!manual && now - prefs.getLong(PREF_LAST_CHECK, 0) < AUTO_CHECK_INTERVAL_MS)
		{
			onDone?.invoke()
			return
		}
		prefs.edit().putLong(PREF_LAST_CHECK, now).apply()
		if(manual) Toast.makeText(activity, "กำลังเช็กอัปเดต…", Toast.LENGTH_SHORT).show()
		Thread {
			val release = try { fetchLatest() } catch(e: Exception) { null }
			activity.runOnUiThread {
				if(activity.isFinishing) return@runOnUiThread
				when
				{
					release == null ->
					{
						if(manual) message(activity, "เช็กอัปเดตไม่สำเร็จ ตรวจอินเทอร์เน็ตแล้วลองใหม่", onDone) else onDone?.invoke()
					}
					!isNewer(release.version, BuildConfig.VERSION_NAME) ->
					{
						if(manual) message(activity, "ใช้เวอร์ชันล่าสุดอยู่แล้ว (${BuildConfig.VERSION_NAME})", onDone) else onDone?.invoke()
					}
					else -> AlertDialog.Builder(activity)
						.setTitle("มีเวอร์ชันใหม่ ${release.version}")
						.setMessage((release.notes.ifBlank { "ปรับปรุงการทำงาน" }).take(800) +
							"\n\nเวอร์ชันที่ใช้อยู่: ${BuildConfig.VERSION_NAME}")
						.setPositiveButton("อัปเดตเลย") { _, _ -> download(activity, release, onDone) }
						.setNegativeButton("ไว้ทีหลัง") { _, _ -> onDone?.invoke() }
						.setOnCancelListener { onDone?.invoke() }
						.show()
				}
			}
		}.start()
	}

	private fun message(activity: Activity, text: String, onDone: (() -> Unit)?)
	{
		AlertDialog.Builder(activity)
			.setMessage(text)
			.setPositiveButton("ตกลง") { _, _ -> onDone?.invoke() }
			.setOnCancelListener { onDone?.invoke() }
			.show()
	}

	private fun fetchLatest(): Release?
	{
		val conn = (URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
			connectTimeout = 8000
			readTimeout = 8000
			setRequestProperty("Accept", "application/vnd.github+json")
			setRequestProperty("User-Agent", "ps5-thai-translator")
		}
		try
		{
			if(conn.responseCode != 200) return null
			val json = JSONObject(conn.inputStream.bufferedReader().readText())
			val assets = json.optJSONArray("assets") ?: return null
			var apk: String? = null
			for(i in 0 until assets.length())
			{
				val a = assets.getJSONObject(i)
				if(a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
			}
			return Release(json.optString("tag_name").removePrefix("v"), json.optString("body"), apk ?: return null)
		}
		finally { conn.disconnect() }
	}

	/** Compares dotted version numbers, e.g. 1.0.10 > 1.0.9. */
	fun isNewer(remote: String, local: String): Boolean
	{
		val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
		val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
		for(i in 0 until maxOf(r.size, l.size))
		{
			val a = r.getOrElse(i) { 0 }
			val b = l.getOrElse(i) { 0 }
			if(a != b) return a > b
		}
		return false
	}

	private fun download(activity: Activity, release: Release, onDone: (() -> Unit)?)
	{
		val dialog = AlertDialog.Builder(activity)
			.setTitle("กำลังดาวน์โหลด ${release.version}")
			.setMessage("0%")
			.setCancelable(false)
			.show()
		Thread {
			val file = File(File(activity.cacheDir, "updates").apply { mkdirs() }, "update.apk")
			val ok = try
			{
				var url = URL(release.apkUrl)
				var conn: HttpURLConnection
				var hops = 0
				while(true)
				{
					conn = (url.openConnection() as HttpURLConnection).apply {
						instanceFollowRedirects = false
						connectTimeout = 15000
						readTimeout = 30000
						setRequestProperty("User-Agent", "ps5-thai-translator")
					}
					val code = conn.responseCode
					if(code in 300..399 && hops++ < 5)
					{
						url = URL(url, conn.getHeaderField("Location"))
						conn.disconnect()
						continue
					}
					break
				}
				if(conn.responseCode != 200) false
				else
				{
					val total = conn.contentLengthLong
					conn.inputStream.use { input ->
						file.outputStream().use { out ->
							val buf = ByteArray(64 * 1024)
							var done = 0L
							var lastPct = -1
							while(true)
							{
								val n = input.read(buf)
								if(n < 0) break
								out.write(buf, 0, n)
								done += n
								if(total > 0)
								{
									val pct = (done * 100 / total).toInt()
									if(pct != lastPct)
									{
										lastPct = pct
										activity.runOnUiThread { dialog.setMessage("$pct%") }
									}
								}
							}
						}
					}
					conn.disconnect()
					true
				}
			}
			catch(e: Exception) { false }
			activity.runOnUiThread {
				dialog.dismiss()
				if(ok) install(activity, file, onDone)
				else message(activity, "ดาวน์โหลดไม่สำเร็จ ลองใหม่อีกครั้ง", onDone)
			}
		}.start()
	}

	private fun install(activity: Activity, file: File, onDone: (() -> Unit)?)
	{
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls())
		{
			AlertDialog.Builder(activity)
				.setMessage("ต้องอนุญาตให้แอปนี้ติดตั้งอัปเดตก่อน (ทำครั้งเดียว)\nกด \"ไปที่ตั้งค่า\" แล้วเปิดอนุญาต จากนั้นกลับมากดอัปเดตอีกครั้ง")
				.setPositiveButton("ไปที่ตั้งค่า") { _, _ ->
					try
					{
						activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
							Uri.parse("package:" + activity.packageName)))
					}
					catch(e: Exception)
					{
						Toast.makeText(activity, "เปิดเองที่ ตั้งค่า > แอป > ความปลอดภัย > แหล่งที่ไม่รู้จัก", Toast.LENGTH_LONG).show()
					}
					onDone?.invoke()
				}
				.setNegativeButton("ยกเลิก") { _, _ -> onDone?.invoke() }
				.show()
			return
		}
		val uri = FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".fileprovider", file)
		val intent = Intent(Intent.ACTION_VIEW)
			.setDataAndType(uri, "application/vnd.android.package-archive")
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
		try { activity.startActivity(intent) }
		catch(e: Exception) { Toast.makeText(activity, "เปิดตัวติดตั้งไม่ได้", Toast.LENGTH_LONG).show() }
		onDone?.invoke()
	}
}

/** Opened from Settings > "เช็กอัปเดต". */
class UpdateCheckActivity : Activity()
{
	override fun onCreate(savedInstanceState: android.os.Bundle?)
	{
		super.onCreate(savedInstanceState)
		UpdateManager.checkAndPrompt(this, manual = true) { finish() }
	}
}
