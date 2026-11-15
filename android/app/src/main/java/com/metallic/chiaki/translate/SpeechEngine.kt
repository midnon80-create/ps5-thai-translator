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
	private val onError: (String) -> Unit,
	/** true = lighter decoding settings (about 1.7x faster, slightly less accurate) for slow TVs */
	private val fast: Boolean = false,
	/** called (on the speech thread, which then stops) when recognition can't keep up with real time */
	private val onTooSlow: (() -> Unit)? = null)
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
		val model = try { Model(ModelAssets.ensure(context, fast).absolutePath) }
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
		// watchdog: CPU time spent recognising vs. seconds of audio, in 10 s windows
		var windowAudio = 0.0
		var windowBusyNs = 0L
		var slowWindows = 0
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
				val t0 = System.nanoTime()
				val endOfSentence = recognizer.acceptWaveForm(buf, n)
				windowBusyNs += System.nanoTime() - t0
				windowAudio += n / AudioTap.SAMPLE_RATE.toDouble()
				if(windowAudio >= WATCH_WINDOW_S)
				{
					val ratio = windowBusyNs / 1e9 / windowAudio
					slowWindows = if(ratio > 1.0) slowWindows + 1 else 0
					windowAudio = 0.0
					windowBusyNs = 0L
					if(slowWindows >= 2 && onTooSlow != null)
					{
						Log.w(TAG, "speech recognition slower than real time (x%.2f), giving up".format(ratio))
						running = false
						onTooSlow.invoke()
						break
					}
				}
				if(endOfSentence)
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
		private const val WATCH_WINDOW_S = 10.0
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

	fun ensure(context: Context, fast: Boolean = false): File
	{
		val target = File(context.filesDir, "$ASSET_DIR-$VERSION")
		val marker = File(target, ".complete")
		if(!marker.exists())
		{
			target.deleteRecursively()
			copyDir(context, ASSET_DIR, target)
			marker.createNewFile()
		}
		// Same model files either way; only the search width differs (read by Vosk when the Model is created).
		val conf = File(target, "conf/model.conf")
		val wanted = if(fast) FAST_CONF else NORMAL_CONF
		if(!conf.exists() || conf.readText() != wanted) conf.writeText(wanted)
		return target
	}

	private const val COMMON =
		"--min-active=200\n--acoustic-scale=1.0\n--frame-subsampling-factor=3\n" +
		"--endpoint.silence-phones=1:2:3:4:5:6:7:8:9:10\n--endpoint.rule2.min-trailing-silence=0.5\n" +
		"--endpoint.rule3.min-trailing-silence=0.75\n--endpoint.rule4.min-trailing-silence=1.0\n"
	// the model's own settings
	private const val NORMAL_CONF = "--max-active=3000\n--beam=10.0\n--lattice-beam=2.0\n" + COMMON
	// measured on real game dialogue: ~1.7x faster, word error 55% -> 58%
	private const val FAST_CONF = "--max-active=2000\n--beam=8.0\n--lattice-beam=2.0\n" + COMMON

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
