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
	enum class Problem { NONE, NO_LICENCE, NEED_BUY, LICENCE_INVALID, OTHER_DEVICE, EXPIRED, TRIAL_OVER, POOL_EMPTY, DAILY_LIMIT, OFFLINE }

	@Volatile var problem = if(licence.isBlank()) Problem.NO_LICENCE else Problem.NONE
		private set

	/** False after a licence error (for the rest of the session) or while the network is failing. */
	val usable get() = serverUrl.isNotBlank() && problem != Problem.NO_LICENCE &&
		problem != Problem.LICENCE_INVALID && problem != Problem.OTHER_DEVICE && problem != Problem.EXPIRED &&
		problem != Problem.TRIAL_OVER && problem != Problem.NEED_BUY &&
		System.currentTimeMillis() >= pausedUntil

	@Volatile private var pausedUntil = 0L
	/** From the last check: "trial" / "year" / "life" and the end date (ms, 0 = never). */
	var plan = ""
		private set
	var expires = 0L
		private set
	private var failStreak = 0

	/** One result per line, null where the cloud had nothing (e.g. pool empty and line not cached). */
	fun translate(lines: List<String>, context: List<String>, kind: TextKind = TextKind.DIALOGUE, hints: List<String?> = emptyList()): List<String?>?
	{
		if(!usable || lines.isEmpty()) return null
		val body = JSONObject()
			.put("code", licence).put("device", device)
			.put("lines", JSONArray(lines)).put("context", JSONArray(context))
			.put("kind", kind.name.lowercase())
			.put("hints", JSONArray(hints.map { it ?: JSONObject.NULL }))
		val res = post("/v1/translate", body) ?: return null
		if(res.has("error")) { onError(res.optString("error")); return null }
		problem = when(res.optString("note")) { "pool_empty" -> Problem.POOL_EMPTY; "daily_limit" -> Problem.DAILY_LIMIT; else -> Problem.NONE }
		val arr = res.optJSONArray("th") ?: return null
		return List(lines.size) { i -> if(i < arr.length() && !arr.isNull(i)) arr.optString(i).trim().ifEmpty { null } else null }
	}

	/** For the test screen: licence OK and money left in the pool? */
	fun check(): Problem
	{
		if(serverUrl.isBlank()) return Problem.OFFLINE
		if(licence.isBlank()) return Problem.NO_LICENCE
		val res = post("/v1/check", JSONObject().put("code", licence).put("device", device)) ?: return Problem.OFFLINE
		plan = res.optString("plan"); expires = res.optLong("expires", 0L)
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
			conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
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

		private val BUY = if(com.metallic.chiaki.store.Store.IS_PLAY) "กดสมัครในตั้งค่า" else "ซื้อรหัสจากผู้ขาย"
		const val PRICES = "ทดลอง 1 วัน 29 บาท · รายปี 329 บาท · ตลอดชีพ 759 บาท"

		fun problemText(p: Problem, plan: String = "") = when(p)
		{
			Problem.NONE -> "แปลด้วย Gemini พร้อมใช้"
			Problem.NO_LICENCE, Problem.NEED_BUY -> "แปลด้วย AI ต้องใช้รหัส ($PRICES) $BUY — ตอนนี้ใช้ตัวแปลออฟไลน์ฟรี"
			Problem.EXPIRED -> if(plan == "day") "ครบ 1 วันแล้ว ถ้าพอใจ อัปเกรดเป็นรายปี 329 บาท หรือตลอดชีพ 759 บาท ($BUY) — ตอนนี้ใช้ตัวแปลออฟไลน์"
				else "รหัสหมดอายุแล้ว ต่ออายุ ($BUY) เพื่อใช้ AI ต่อ — ตอนนี้ใช้ตัวแปลออฟไลน์"
			Problem.LICENCE_INVALID -> "รหัสไลเซนส์ไม่ถูกต้อง ตรวจรหัสอีกครั้ง — ใช้ตัวแปลออฟไลน์"
			Problem.OTHER_DEVICE -> "รหัสนี้ใช้กับทีวีเครื่องอื่นไปแล้ว ติดต่อผู้ขาย — ใช้ตัวแปลออฟไลน์"
			Problem.TRIAL_OVER -> "ช่วงทดลองฟรีครบแล้ว $BUY ($PRICES) — ยังแปลออฟไลน์ได้ฟรี"
			Problem.POOL_EMPTY -> "โควตาแปล Gemini เต็มชั่วคราว ประโยคที่เคยแปลแล้วยังใช้ได้ ที่เหลือใช้ตัวแปลออฟไลน์"
			Problem.DAILY_LIMIT -> "วันนี้แปลประโยคใหม่ครบโควตาแล้ว — ใช้ตัวแปลออฟไลน์ถึงพรุ่งนี้"
			Problem.OFFLINE -> "ต่อเซิร์ฟเวอร์แปลไม่ได้ — ใช้ตัวแปลออฟไลน์"
		}
	}
}
