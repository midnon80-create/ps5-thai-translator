package com.metallic.chiaki.translate

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline English speech recognition (Vosk) on the game audio.
 * Calls [onSentence] with each finished English sentence, on its own thread.
 */
class SpeechEngine(
	private val context: Context,
	private val onSentence: (List<String>) -> Unit,
	private val onError: (String) -> Unit)
{
	@Volatile private var running = false
	/** True while the game shows subtitles: those get translated from the screen, so listening is wasted CPU. */
	@Volatile var paused = false
	private var thread: Thread? = null

	fun start()
	{
		if(running) return
		running = true
		thread = Thread({ loop() }, "translator-speech").also { it.start() }
	}

	fun stop()
	{
		running = false
		AudioTap.nativeSetEnabled(false)
		thread?.join(1500)
		thread = null
	}

	private fun loop()
	{
		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
		val model = try { Model(ModelAssets.ensure(context).absolutePath) }
		catch(e: Throwable)
		{
			Log.e(TAG, "Vosk model load failed", e)
			onError("โหลดระบบฟังเสียงไม่สำเร็จ")
			running = false
			return
		}
		val recognizer = Recognizer(model, AudioTap.SAMPLE_RATE.toFloat())
		recognizer.setWords(true) // word timings, used to cut sentences at pauses
		AudioTap.nativeSetEnabled(true)
		val buf = ShortArray(1600) // 100 ms
		var utteranceStart = 0L
		try
		{
			while(running)
			{
				val n = AudioTap.nativeRead(buf, buf.size)
				if(n <= 0)
				{
					Thread.sleep(25)
					continue
				}
				val now = SystemClock.elapsedRealtime()
				if(paused)
				{
					// keep draining the audio so nothing old piles up, but skip the expensive recognition
					if(utteranceStart != 0L) { recognizer.reset(); utteranceStart = 0L }
					continue
				}
				if(utteranceStart == 0L) utteranceStart = now
				if(recognizer.acceptWaveForm(buf, n))
				{
					emit(recognizer.result)
					utteranceStart = 0L
				}
				else if(now - utteranceStart > MAX_UTTERANCE_MS)
				{
					// Music or constant noise can keep Vosk from ever ending a sentence; cut it.
					val partial = JSONObject(recognizer.partialResult).optString("partial")
					if(partial.isNotBlank())
						emit(recognizer.finalResult)
					utteranceStart = 0L
				}
			}
		}
		catch(e: Throwable)
		{
			Log.e(TAG, "speech loop failed", e)
		}
		finally
		{
			AudioTap.nativeSetEnabled(false)
			recognizer.close()
			model.close()
		}
	}

	private fun emit(json: String)
	{
		val sentences = SpeechSegments.split(json)
		// One-word results are mostly noise from music and sound effects.
		if(sentences.sumOf { it.split(' ').size } >= 2 && sentences.joinToString(" ") != "the")
			onSentence(sentences)
	}

	companion object
	{
		private const val TAG = "TranslatorSpeech"
		private const val MAX_UTTERANCE_MS = 8000L
	}
}

/**
 * Vosk returns one run of lowercase words with no punctuation. Cut it into sentences wherever the
 * speaker paused (gap between words > [GAP_S]), using the word timings from setWords(true).
 * Without timings, the whole final result is one sentence.
 */
object SpeechSegments
{
	const val GAP_S = 0.35

	fun split(json: String): List<String>
	{
		val obj = try { JSONObject(json) } catch(e: Exception) { return emptyList() }
		val words = obj.optJSONArray("result")
		if(words == null || words.length() == 0)
			return obj.optString("text").trim().let { if(it.isEmpty()) emptyList() else listOf(it) }
		val out = mutableListOf<String>()
		val cur = StringBuilder()
		var lastEnd = -1.0
		for(i in 0 until words.length())
		{
			val w = words.optJSONObject(i) ?: continue
			val word = w.optString("word")
			if(word.isEmpty()) continue
			val start = w.optDouble("start", lastEnd)
			if(cur.isNotEmpty() && lastEnd >= 0 && start - lastEnd > GAP_S)
			{
				out.add(cur.toString())
				cur.setLength(0)
			}
			if(cur.isNotEmpty()) cur.append(' ')
			cur.append(word)
			lastEnd = w.optDouble("end", start)
		}
		if(cur.isNotEmpty()) out.add(cur.toString())
		return out
	}
}

/** Copies the bundled Vosk model out of the APK assets once, so Vosk can read it as files. */
object ModelAssets
{
	private const val ASSET_DIR = "vosk-en"
	private const val VERSION = "small-en-us-0.15"

	fun ensure(context: Context): File
	{
		val target = File(context.filesDir, "$ASSET_DIR-$VERSION")
		val marker = File(target, ".complete")
		if(marker.exists())
			return target
		target.deleteRecursively()
		copyDir(context, ASSET_DIR, target)
		marker.createNewFile()
		return target
	}

	private fun copyDir(context: Context, assetPath: String, dest: File)
	{
		val children = context.assets.list(assetPath) ?: emptyArray()
		if(children.isEmpty())
		{
			dest.parentFile?.mkdirs()
			context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
			return
		}
		dest.mkdirs()
		for(child in children)
			copyDir(context, "$assetPath/$child", File(dest, child))
	}
}
