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
	private val onSentence: (String) -> Unit,
	private val onError: (String) -> Unit)
{
	@Volatile private var running = false
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
		val model = try { Model(ModelAssets.ensure(context).absolutePath) }
		catch(e: Throwable)
		{
			Log.e(TAG, "Vosk model load failed", e)
			onError("โหลดระบบฟังเสียงไม่สำเร็จ")
			running = false
			return
		}
		val recognizer = Recognizer(model, AudioTap.SAMPLE_RATE.toFloat())
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
		val text = try { JSONObject(json).optString("text") } catch(e: Exception) { "" }.trim()
		// One-word results are mostly noise from music and sound effects.
		if(text.split(' ').count { it.isNotBlank() } >= 2 && text != "the")
			onSentence(text)
	}

	companion object
	{
		private const val TAG = "TranslatorSpeech"
		private const val MAX_UTTERANCE_MS = 8000L
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
