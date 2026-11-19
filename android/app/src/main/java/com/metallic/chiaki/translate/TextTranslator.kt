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
	// Gemini lines from the shared server cache: preferred over offline output for the rest of the session
	private val cloudCache = object : LinkedHashMap<String, String>(256, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2000
	}

	fun remember(src: String, thai: String) = synchronized(cloudCache) { cloudCache[src.trim()] = thai }

	/** Hand-written bank line or an earlier Gemini line: already the best we have, no need to ask the server. */
	fun best(src: String): String? = PhraseBank.lookup(src) ?: synchronized(cloudCache) { cloudCache[src.trim()] }


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

	/** Several sentences (e.g. speech cut at pauses), each translated on its own, joined with spaces. */
	fun translateAll(sentences: List<String>, kind: TextKind = TextKind.DIALOGUE): String?
	{
		val ok = translateEach(sentences.map { it.trim() }.filter { it.isNotEmpty() }, kind).filterNotNull()
		return if(ok.isEmpty()) null else ok.joinToString(" ")
	}

	/** One Thai line per input (null where nothing worked). */
	fun translateEach(parts: List<String>, kind: TextKind = TextKind.DIALOGUE): List<String?> = parts.map { translate(it, kind) }

	fun translate(english: String, kind: TextKind = TextKind.DIALOGUE): String?
	{
		val src = english.trim()
		if(src.isEmpty()) return null
		if(kind == TextKind.DIALOGUE) best(src)?.let { return it }
		else synchronized(cloudCache) { cloudCache[kind.name + ":" + src] }?.let { return it }
		val key = kind.name + ":" + src
		synchronized(cache) { cache[key] }?.let { return it }
		val out = when
		{
			geminiKey.isNotEmpty() && kind == TextKind.DIALOGUE -> translateOne(src) ?: offline(src, kind)
			kind == TextKind.PROMPT -> UiText.prompt(src) { offline(it, TextKind.HINT) } ?: offline(src, TextKind.HINT)
			else -> offline(src, kind)
		}
		if(out != null) synchronized(cache) { cache[key] = out }
		return out
	}

	/** Gemini line for a non-dialogue kind, kept apart from dialogue (different style). */
	fun remember(src: String, thai: String, kind: TextKind) =
		if(kind == TextKind.DIALOGUE) remember(src, thai) else synchronized(cloudCache) { cloudCache[kind.name + ":" + src.trim()] = thai }

	/** Already have the best possible line for this kind (bank / earlier Gemini)? */
	fun best(src: String, kind: TextKind): String? =
		if(kind == TextKind.DIALOGUE) best(src) else synchronized(cloudCache) { cloudCache[kind.name + ":" + src.trim()] }

	/**
	 * Offline: sentences one by one; each goes hand-written bank -> fixed-Thai phrases masked ->
	 * slang to plain English -> ML Kit -> phrases put back -> spoken Thai (+ speaker's tone for dialogue).
	 */
	private fun offline(src: String, kind: TextKind): String?
	{
		val sentences = splitSentences(src)
		if(sentences.size > 1)
		{
			val ok = sentences.mapNotNull { offlineSentence(it, kind) }
			return if(ok.isEmpty()) null else ok.joinToString(" ")
		}
		return offlineSentence(src, kind)
	}

	private fun offlineSentence(src: String, kind: TextKind): String?
	{
		PhraseBank.lookup(src)?.let { return it }
		// intensifiers would split known phrases ("deep fucking shit"); the tone still comes from src
		val masked = ThaiPhrases.mask(src.replace(INTENSIFIERS, ""))
		val thai: String? = if(masked.onlyPhrases) ThaiPhrases.unmask(masked.text, masked)
		else
		{
			val plain = if(kind == TextKind.DIALOGUE) SlangRewriter.rewrite(masked.text) else masked.text
			if(!masked.any) PhraseBank.lookup(plain)?.let { return it }
			val mt = machine(plain) ?: return null
			if(!masked.any) mt
			else ThaiPhrases.unmask(mt, masked)
				// the translator mangled a placeholder: translate the pieces between the phrases instead
				?: ThaiPhrases.joinParts(masked.parts().map { p ->
					val piece = p.trim().trim(',', ' ')
					if(piece.none { it.isLetter() }) null
					else machine(if(kind == TextKind.DIALOGUE) SlangRewriter.rewrite(piece) else piece)
				}, masked)
		}
		thai ?: return null
		return if(kind == TextKind.DIALOGUE) ThaiTone.apply(src, thai) else ThaiTone.polish(thai)
	}

	private fun machine(text: String): String?
	{
		if(!offlineReady || text.isBlank()) return null
		// this TV crashed inside the offline translator before: skip it (AI still works)
		if(com.metallic.chiaki.crash.CrashReport.disabled("offline")) return null
		com.metallic.chiaki.crash.CrashReport.enter("offline")
		return try { Tasks.await(mlkit.translate(text), 5, TimeUnit.SECONDS) }
		catch(e: Exception) { Log.w(TAG, "ML Kit translate failed", e); null }
		finally { com.metallic.chiaki.crash.CrashReport.leave("offline") }
	}

	/** Sentence ends, plus ", they/we/you..." where a comma starts a new clause (reads better in Thai). */
	private fun splitSentences(s: String): List<String> =
		s.split(Regex("""(?<=[.!?;])\s+|,\s+(?=(?:the|they|we|you|i|he|she|it|so|but|and)\b)""", RegexOption.IGNORE_CASE))
			.map { it.trim() }.filter { it.any { c -> c.isLetter() } }

	/** BYO Gemini key (old setting, hidden now): dialogue only. */
	private fun translateOne(src: String): String?
	{
		if(!geminiAllowed()) return null
		val out = try { gemini(src) }
		catch(e: GeminiRateLimited) { geminiPausedUntil = System.currentTimeMillis() + 60_000; null }
		catch(e: Exception)
		{
			Log.w(TAG, "Gemini failed, using offline", e)
			if(++geminiFailStreak >= 3) { geminiPausedUntil = System.currentTimeMillis() + 30_000; geminiFailStreak = 0 }
			null
		}
		if(out != null)
		{
			geminiFailStreak = 0
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
		private val INTENSIFIERS = Regex("""(?i)\b(fucking|fuckin'?|freaking|freakin'?|goddamn|goddamned)\s+""")
		private const val TAG = "TranslatorText"
		private const val GEMINI_PER_MINUTE = 14
	}
}
