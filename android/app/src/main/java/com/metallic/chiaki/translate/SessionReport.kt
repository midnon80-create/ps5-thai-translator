package com.metallic.chiaki.translate

import android.content.Context
import android.os.Build
import java.io.File

/**
 * One short report after every game, so the owner's Devices page shows which TVs / boxes work well and which
 * have problems (no subtitles, picture breaking up, disconnects, slow reading). No personal data: the TV model,
 * app version, mode and counters only.
 */
object SessionReport
{
	data class Stats(val reads: Int, val saw: Int, val shown: Int, val ai: Int, val copyFailed: Int, val readMs: Int, val engine: String,
		val localLines: Int = 0, val cloudLines: Int = 0, val aiLines: Int = 0, val lateLines: Int = 0, val cloudMs: Int = 0, val aiMs: Int = 0)

	fun send(context: Context, mode: String, secs: Long, stats: Stats?, log: File?, quit: String?)
	{
		if(secs < 20) return
		val app = context.applicationContext
		Thread {
			try
			{
				val prefs = TranslatorPrefs(app)
				if(prefs.serverUrl.isBlank()) return@Thread
				val broken = try { log?.takeIf { it.exists() }?.readLines()?.count {
					it.contains("missing or corrupt frame", true) || it.contains("Failed to get input buffer", true) } ?: 0 } catch(_: Throwable) { 0 }
				val p = com.metallic.chiaki.common.Preferences(app)
				val res = if(mode == "hdmi") "720p30 (อ่านซับ)" else "${p.resolution.value} ${p.codec.value} ${p.bitrate ?: "auto"}"
				val body = org.json.JSONObject()
					.put("device", CloudTranslator.deviceId(app))
					.put("model", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")")
					.put("android", Build.VERSION.SDK_INT)
					.put("version", com.metallic.chiaki.BuildConfig.VERSION_NAME + (if(com.metallic.chiaki.store.Store.IS_PLAY) "-play" else ""))
					.put("mode", mode)
					.put("secs", secs)
					.put("res", res)
					.put("engine", stats?.engine ?: "")
					.put("reads", stats?.reads ?: 0)
					.put("saw", stats?.saw ?: 0)
					.put("shown", stats?.shown ?: 0)
					.put("ai", stats?.ai ?: 0)
					.put("copy_failed", stats?.copyFailed ?: 0)
					.put("read_ms", stats?.readMs ?: 0)
					// where each subtitle's Thai came from, and how long it took (TV library = instant)
					.put("local_lines", stats?.localLines ?: 0).put("cloud_lines", stats?.cloudLines ?: 0)
					.put("ai_lines", stats?.aiLines ?: 0).put("late_lines", stats?.lateLines ?: 0)
					.put("cloud_ms", stats?.cloudMs ?: 0).put("ai_ms", stats?.aiMs ?: 0)
					.put("corrupt_pm", if(secs > 0) broken * 60.0 / secs else 0.0)
				if(!quit.isNullOrBlank()) body.put("quit", quit.take(160))
				val c = java.net.URL(prefs.serverUrl.trimEnd('/') + "/v1/session").openConnection() as java.net.HttpURLConnection
				c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true
				c.setRequestProperty("Content-Type", "application/json")
				c.outputStream.use { it.write(body.toString().toByteArray()) }
				c.inputStream.use { it.readBytes() }
				c.disconnect()
			}
			catch(_: Throwable) { }
		}.start()
	}
}
