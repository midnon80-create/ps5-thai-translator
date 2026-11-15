package com.metallic.chiaki.translate

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * English → Thai. Uses ML Kit on-device translation (free, offline, unlimited).
 * If a Gemini API key is set, tries Gemini first for more natural Thai and falls back to ML Kit.
 * Blocking calls: use from a background thread.
 */
class TextTranslator(private val geminiKey: String, private val geminiModel: String)
{
	private val mlkit = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.THAI)
			.build())

	@Volatile var offlineReady = false
		private set

	private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 400
	}
	private val recent = ArrayDeque<String>()

	// Free Gemini keys allow about 15 requests a minute; stay under it and back off when told to.
	private val geminiCalls = ArrayDeque<Long>()
	private var geminiPausedUntil = 0L
	private var geminiFailStreak = 0

	private fun geminiAllowed(): Boolean
	{
		if(geminiKey.isEmpty()) return false
		val now = System.currentTimeMillis()
		if(now < geminiPausedUntil) return false
		while(geminiCalls.isNotEmpty() && now - geminiCalls.first() > 60_000) geminiCalls.removeFirst()
		if(geminiCalls.size >= GEMINI_PER_MINUTE) return false
		geminiCalls.addLast(now)
		return true
	}

	/** Downloads the Thai dictionary once (about 30 MB, needs internet the first time only). */
	fun prepare(): Boolean
	{
		return try
		{
			Tasks.await(mlkit.downloadModelIfNeeded(DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES)
			offlineReady = true
			true
		}
		catch(e: Exception)
		{
			Log.e(TAG, "ML Kit model download failed", e)
			false
		}
	}

	fun translate(english: String): String?
	{
		val src = english.trim()
		if(src.isEmpty()) return null
		synchronized(cache) { cache[src] }?.let { return it }

		var out: String? = null
		if(geminiAllowed())
		{
			out = try { gemini(src) }
			catch(e: GeminiRateLimited)
			{
				geminiPausedUntil = System.currentTimeMillis() + 60_000
				null
			}
			catch(e: Exception)
			{
				Log.w(TAG, "Gemini failed, using offline", e)
				if(++geminiFailStreak >= 3)
				{
					geminiPausedUntil = System.currentTimeMillis() + 30_000
					geminiFailStreak = 0
				}
				null
			}
			if(out != null) geminiFailStreak = 0
		}
		if(out == null && offlineReady)
		{
			out = try { Tasks.await(mlkit.translate(src), 5, TimeUnit.SECONDS) }
			catch(e: Exception) { Log.w(TAG, "ML Kit translate failed", e); null }
		}
		if(out != null)
		{
			synchronized(cache) { cache[src] = out }
			recent.addLast(src)
			while(recent.size > 3) recent.removeFirst()
		}
		return out
	}

	private fun gemini(src: String): String?
	{
		val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" +
			URLEncoder.encode(geminiModel, "UTF-8") + ":generateContent")
		val context = recent.joinToString("\n")
		val prompt = buildString {
			append("You are a professional Thai subtitle translator for video games. ")
			append("Translate the English line into natural spoken Thai, the way a good Thai fan-sub would: ")
			append("short and easy to read at a glance, keeping the speaker's tone (angry, joking, scared, formal). ")
			append("Do not add ครับ/ค่ะ unless the English is polite or formal. ")
			append("Keep character names, place names, item and skill names in English. ")
			append("The English may come from OCR or speech recognition and can contain small errors; fix them silently. ")
			append("Reply with the Thai subtitle only, no notes, no quotes, no romanisation.").append('\n')
			if(context.isNotEmpty()) append("Previous lines (context only, do not translate):\n").append(context).append('\n')
			append("Translate this:\n").append(src)
		}
		val body = JSONObject()
			.put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
			.put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 400))
		val conn = (url.openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			connectTimeout = 2500
			readTimeout = 4000
			doOutput = true
			setRequestProperty("Content-Type", "application/json")
			setRequestProperty("x-goog-api-key", geminiKey)
		}
		try
		{
			conn.outputStream.use { it.write(body.toString().toByteArray()) }
			if(conn.responseCode == 429)
				throw GeminiRateLimited()
			if(conn.responseCode != 200)
				throw RuntimeException("HTTP ${conn.responseCode}")
			val json = JSONObject(conn.inputStream.bufferedReader().readText())
			val parts = json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
			val sb = StringBuilder()
			for(i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
			return sb.toString().trim().trim('"', '“', '”').ifEmpty { null }
		}
		finally
		{
			conn.disconnect()
		}
	}

	fun close() = mlkit.close()

	private class GeminiRateLimited : Exception()

	companion object
	{
		private const val TAG = "TranslatorText"
		private const val GEMINI_PER_MINUTE = 14
	}
}
