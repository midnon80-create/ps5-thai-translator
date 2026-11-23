package com.metallic.chiaki.translate

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Client for the Eyesmaxxing server: one shared cache of Gemini translations for every customer.
 * Lines someone already met come back from the cache (free, ~0.1-0.3 s); new lines are translated by
 * Gemini on the server and paid from the pool. The Gemini key is only on the server.
 * Blocking: call from a background thread. Returns null whenever the cloud can't help, so the
 * caller simply keeps the offline translation.
 */
class CloudTranslator(private val serverUrl: String, private val licence: String, private val device: String)
{
	enum class Problem { NONE, NO_LICENCE, NEED_BUY, NOT_STARTED, LICENCE_INVALID, OTHER_DEVICE, EXPIRED, TRIAL_OVER, POOL_EMPTY, DAILY_LIMIT, OFFLINE }

	@Volatile var problem = if(licence.isBlank()) Problem.NO_LICENCE else Problem.NONE
		private set

	/** False after a licence error (for the rest of the session) or while the network is failing. */
	val usable get() = serverUrl.isNotBlank() && problem != Problem.NO_LICENCE &&
		problem != Problem.LICENCE_INVALID && problem != Problem.OTHER_DEVICE && problem != Problem.EXPIRED &&
		problem != Problem.TRIAL_OVER && problem != Problem.NEED_BUY && problem != Problem.NOT_STARTED &&
		System.currentTimeMillis() >= pausedUntil

	@Volatile private var pausedUntil = 0L
	/** From the last check: "trial" / "year" / "life" and the end date (ms, 0 = never). */
	var plan = ""
		private set
	var expires = 0L
		private set
	private var failStreak = 0

	/** One result per line, null where the cloud had nothing (e.g. pool empty and line not cached). */
	fun translate(lines: List<String>, context: List<String>, kind: TextKind = TextKind.DIALOGUE, hints: List<String?> = emptyList(), fast: Boolean = false): List<String?>?
	{
		if(!usable || lines.isEmpty()) return null
		val body = JSONObject()
			.put("code", licence).put("device", device)
			.put("lines", JSONArray(lines)).put("context", JSONArray(context))
			.put("kind", kind.name.lowercase())
			.put("hints", JSONArray(hints.map { it ?: JSONObject.NULL }))
		if(fast) body.put("fast", true)
		if(game.isNotEmpty()) body.put("game", game)
		val res = post("/v1/translate", body) ?: return null
		if(fast) // quick first text only: never changes the licence/pool state
		{
			val arr = res.optJSONArray("th") ?: return null
			return List(lines.size) { i -> if(i < arr.length() && !arr.isNull(i)) arr.optString(i).trim().ifEmpty { null } else null }
		}
		if(res.has("error")) { onError(res.optString("error")); return null }
		problem = when(res.optString("note")) { "pool_empty" -> Problem.POOL_EMPTY; "daily_limit" -> Problem.DAILY_LIMIT; else -> Problem.NONE }
		val arr = res.optJSONArray("th") ?: return null
		return List(lines.size) { i -> if(i < arr.length() && !arr.isNull(i)) arr.optString(i).trim().ifEmpty { null } else null }
	}

	/** AI is open for this TV (GTA-only lock): set by check() and by a successful unlock(). */
	@Volatile var gameOkUntil = 0L

	/** Game picked in the app's game list (e.g. "gta6"): the server translates it from the first line. */
	@Volatile var game = ""

	/** The screen showed GTA VI (Rockstar/title screens, GTA names): ask the server to open AI for this TV. */
	fun unlock(texts: List<String>): Boolean
	{
		if(serverUrl.isBlank() || licence.isBlank()) return false
		val res = post("/v1/unlock", JSONObject().put("code", licence).put("device", device).put("texts", JSONArray(texts))) ?: return false
		if(!res.optBoolean("ok")) return false
		gameOkUntil = res.optLong("until", System.currentTimeMillis() + 3_600_000L)
		return true
	}

	/** For the test screen: licence OK and money left in the pool? */
	fun check(): Problem
	{
		if(serverUrl.isBlank()) return Problem.OFFLINE
		if(licence.isBlank()) return Problem.NO_LICENCE
		val res = post("/v1/check", JSONObject().put("code", licence).put("device", device)) ?: return Problem.OFFLINE
		plan = res.optString("plan"); expires = res.optLong("expires", 0L)
		res.optLong("start", 0L).takeIf { it > 0 }?.let { aiStart = it }
		if(res.optBoolean("game_ok", false)) gameOkUntil = System.currentTimeMillis() + 3_600_000L
		if(!res.optBoolean("ok")) { onError(res.optString("error")); return problem }
		problem = if(res.optBoolean("pool_ok")) Problem.NONE else Problem.POOL_EMPTY
		return problem
	}

	private fun onError(e: String)
	{
		problem = when(e)
		{
			"licence_invalid", "licence_missing" -> Problem.LICENCE_INVALID
			"licence_other_device" -> Problem.OTHER_DEVICE
			"licence_expired" -> Problem.EXPIRED
			"ai_not_started" -> Problem.NOT_STARTED
			// no expiry date = this TV never had a free trial (free trials are off): it needs a bought code
			"trial_over" -> if(expires > 0L) Problem.TRIAL_OVER else Problem.NEED_BUY
			"daily_limit" -> Problem.DAILY_LIMIT
			else -> problem
		}
		if(problem == Problem.DAILY_LIMIT) pausedUntil = System.currentTimeMillis() + 10 * 60_000
	}

	private fun post(path: String, body: JSONObject): JSONObject?
	{
		var conn: HttpURLConnection? = null
		return try
		{
			conn = (URL(serverUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
				requestMethod = "POST"
				connectTimeout = 2500
				readTimeout = 6000
				doOutput = true
				setRequestProperty("Content-Type", "application/json")
			}
			val bytes = body.toString().toByteArray(Charsets.UTF_8)
			AppSig.headers(body.optString("device"), bytes).forEach { (k, v) -> conn.setRequestProperty(k, v) }
			conn.outputStream.use { it.write(bytes) }
			val code = conn.responseCode
			val stream = if(code in 200..299) conn.inputStream else conn.errorStream
			val text = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
			failStreak = 0
			if(problem == Problem.OFFLINE) problem = Problem.NONE
			if(text.isEmpty()) null else JSONObject(text)
		}
		catch(e: Exception)
		{
			Log.w(TAG, "cloud request failed", e)
			// network trouble: stop asking for a while so the offline path isn't slowed down
			if(++failStreak >= 2) { pausedUntil = System.currentTimeMillis() + 30_000; failStreak = 0; problem = Problem.OFFLINE }
			null
		}
		finally { conn?.disconnect() }
	}

	companion object
	{
		private const val TAG = "CloudTranslator"

		/** Stable per TV (and per app signing key); hashed so the raw Android id never leaves the TV. */
		@SuppressLint("HardwareIds")
		fun deviceId(context: Context): String
		{
			val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
			val d = MessageDigest.getInstance("SHA-256").digest(("eyesmaxxing:" + raw).toByteArray())
			return d.take(12).joinToString("") { "%02x".format(it) }
		}


		/** When AI translation opens (server lock until the game is out), ms; 0 = unknown. */
		@Volatile var aiStart = 0L
		fun aiStartText() = if(aiStart > 0) java.text.SimpleDateFormat("d MMM yyyy HH:mm น.", java.util.Locale("th", "TH")).format(java.util.Date(aiStart)) else "วันที่เกมออก"

		fun problemText(p: Problem, plan: String = "") = when(p)
		{
			Problem.NOT_STARTED -> "แปลด้วย AI จะเปิดวันที่ GTA 6 ออก (${aiStartText()}) — ตอนนี้ใช้ตัวแปลออฟไลน์"
			Problem.NONE -> "แปลด้วย Gemini พร้อมใช้"
			// the app is free now: these only happen for a moment (server busy / an old code); never ask to buy
			Problem.NO_LICENCE, Problem.NEED_BUY, Problem.EXPIRED, Problem.LICENCE_INVALID, Problem.OTHER_DEVICE, Problem.TRIAL_OVER ->
				"AI ยังไม่พร้อมชั่วคราว ลองออกแล้วเข้าใหม่อีกครั้ง"
			Problem.POOL_EMPTY -> "โควตาแปล Gemini เต็มชั่วคราว ประโยคที่เคยแปลแล้วยังใช้ได้ ที่เหลือใช้ตัวแปลออฟไลน์"
			Problem.DAILY_LIMIT -> "วันนี้แปลประโยคใหม่ครบโควตาแล้ว — ใช้ตัวแปลออฟไลน์ถึงพรุ่งนี้"
			Problem.OFFLINE -> "ต่อเซิร์ฟเวอร์แปลไม่ได้ — ใช้ตัวแปลออฟไลน์"
		}
	}
}
