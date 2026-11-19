package com.metallic.chiaki.translate

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Offline English speech recognition on the game audio:
 * Silero VAD finds each spoken phrase (so sentences are cut at the speaker's pauses), then
 * Moonshine Tiny transcribes it with punctuation. Measured on PC: about 4-6x faster than the
 * previous Vosk engine, so slow TVs can keep up.
 * Calls [onSentence] with the sentences of each phrase, on its own thread.
 */
class SpeechEngine(
	private val context: Context,
	private val onSentence: (List<String>) -> Unit,
	private val onError: (String) -> Unit,
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
		com.metallic.chiaki.crash.CrashReport.enter("speech")
		thread = Thread({ loop() }, "translator-speech").also { it.start() }
	}

	fun stop()
	{
		running = false
		AudioTap.nativeSetEnabled(false)
		thread?.join(1500)
		thread = null
		com.metallic.chiaki.crash.CrashReport.leave("speech")
	}

	private fun loop()
	{
		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
		val asr = try { Speech.open(context) } catch(e: Throwable)
		{
			Log.e(TAG, "speech engine load failed", e)
			onError("โหลดระบบฟังเสียงไม่สำเร็จ")
			running = false
			return
		}
		AudioTap.nativeSetEnabled(true)
		val buf = ShortArray(1600) // 100 ms
		// watchdog: CPU time spent on speech vs. seconds of audio, in 10 s windows
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
				if(paused)
				{
					asr.vad.clear() // keep draining the audio but skip the work
					continue
				}
				val t0 = System.nanoTime()
				val phrases = asr.feed(buf, n)
				windowBusyNs += System.nanoTime() - t0
				windowAudio += n / AudioTap.SAMPLE_RATE.toDouble()
				for(text in phrases) emit(text)
				if(windowAudio >= WATCH_WINDOW_S)
				{
					val ratio = windowBusyNs / 1e9 / windowAudio
					slowWindows = if(ratio > 1.0) slowWindows + 1 else 0
					windowAudio = 0.0
					windowBusyNs = 0L
					if(slowWindows >= 2 && onTooSlow != null)
					{
						Log.w(TAG, "speech slower than real time (x%.2f), stopping".format(ratio))
						running = false
						onTooSlow.invoke()
						break
					}
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
			asr.release()
		}
	}

	private fun emit(text: String)
	{
		val sentences = SpeechSegments.split(text)
		// one-word results are mostly noise from music and sound effects
		if(sentences.sumOf { it.split(' ').count { w -> w.isNotBlank() } } >= 2)
			onSentence(sentences)
	}

	companion object
	{
		private const val TAG = "TranslatorSpeech"
		private const val WATCH_WINDOW_S = 10.0
	}
}

/** Moonshine already punctuates: split its text into sentences so each is translated on its own. */
object SpeechSegments
{
	fun split(text: String): List<String> =
		text.trim().split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
}

/** VAD + recogniser pair. Not thread-safe: one per thread. */
class Speech private constructor(val vad: Vad, private val recognizer: OfflineRecognizer)
{
	private val window = FloatArray(WINDOW)
	private var filled = 0

	/** Feed 16 kHz mono PCM; returns the text of every phrase that just ended. */
	fun feed(pcm: ShortArray, n: Int): List<String>
	{
		val out = ArrayList<String>(0)
		for(i in 0 until n)
		{
			window[filled++] = pcm[i] / 32768f
			if(filled == WINDOW)
			{
				vad.acceptWaveform(window.copyOf())
				filled = 0
				drain(out)
			}
		}
		return out
	}

	/** End of input (tests): finish the last phrase. */
	fun finish(): List<String>
	{
		val out = ArrayList<String>()
		vad.flush()
		drain(out)
		return out
	}

	private fun drain(out: MutableList<String>)
	{
		while(!vad.empty())
		{
			val seg = vad.front()
			vad.pop()
			val stream = recognizer.createStream()
			stream.acceptWaveform(seg.samples, AudioTap.SAMPLE_RATE)
			recognizer.decode(stream)
			val text = recognizer.getResult(stream).text.trim()
			stream.release()
			if(text.isNotEmpty()) out.add(text)
		}
	}

	fun release()
	{
		try { vad.release() } catch(_: Throwable) { }
		try { recognizer.release() } catch(_: Throwable) { }
	}

	companion object
	{
		private const val WINDOW = 512 // Silero VAD window at 16 kHz

		/** Needs the model on the TV first (see [SpeechModel.ensure]). */
		fun open(context: Context, threads: Int = 2): Speech
		{
			val dir = SpeechModel.dir(context)
			require(SpeechModel.isReady(context)) { "speech model not downloaded" }
			val vad = Vad(context.assets, VadModelConfig(
				sileroVadModelConfig = SileroVadModelConfig(
					model = "silero_vad.onnx", threshold = 0.5f,
					minSilenceDuration = 0.25f, minSpeechDuration = 0.25f,
					windowSize = WINDOW, maxSpeechDuration = 8.0f),
				sampleRate = AudioTap.SAMPLE_RATE, numThreads = 1))
			val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
				modelConfig = OfflineModelConfig(
					moonshine = OfflineMoonshineModelConfig(
						preprocessor = File(dir, "preprocess.onnx").path,
						encoder = File(dir, "encode.int8.onnx").path,
						uncachedDecoder = File(dir, "uncached_decode.int8.onnx").path,
						cachedDecoder = File(dir, "cached_decode.int8.onnx").path),
					tokens = File(dir, "tokens.txt").path,
					numThreads = threads)))
			return Speech(vad, recognizer)
		}
	}
}

/**
 * The speech model (~100 MB) is downloaded once from the app's GitHub releases instead of being
 * inside the APK, so app updates stay small. Checked with SHA-256, then kept on the TV.
 */
object SpeechModel
{
	private const val NAME = "moonshine-tiny-en-int8"
	private const val URL_ZIP = "https://github.com/midnon80-create/ps5-thai-translator/releases/download/speech-model-1/moonshine-tiny-en-int8.zip"
	private const val SHA256 = "504aae319a2856e4b92b40ed3b6fa9e5886150d02b06c9762869ddc4dfa933c8"
	const val SIZE_MB = 100

	fun dir(context: Context) = File(context.filesDir, NAME)
	fun isReady(context: Context) = File(dir(context), ".complete").exists()

	/** Blocking. Downloads and unpacks the model if needed. [progress] gets 0..100. */
	fun ensure(context: Context, progress: (Int) -> Unit = {}): Boolean
	{
		if(isReady(context)) return true
		val zip = File(context.cacheDir, "$NAME.zip")
		try
		{
			var url = URL(URL_ZIP)
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
			if(conn.responseCode != 200) return false
			val total = conn.contentLengthLong
			val md = MessageDigest.getInstance("SHA-256")
			conn.inputStream.use { input ->
				zip.outputStream().use { out ->
					val b = ByteArray(64 * 1024)
					var done = 0L
					var last = -1
					while(true)
					{
						val r = input.read(b)
						if(r < 0) break
						out.write(b, 0, r)
						md.update(b, 0, r)
						done += r
						if(total > 0) { val p = (done * 100 / total).toInt(); if(p != last) { last = p; progress(p) } }
					}
				}
			}
			conn.disconnect()
			val hash = md.digest().joinToString("") { "%02x".format(it) }
			if(hash != SHA256) { Log.e("SpeechModel", "bad checksum $hash"); return false }
			val target = dir(context)
			val tmp = File(context.filesDir, "$NAME.tmp")
			tmp.deleteRecursively(); tmp.mkdirs()
			ZipInputStream(zip.inputStream()).use { zin ->
				while(true)
				{
					val e = zin.nextEntry ?: break
					val f = File(tmp, e.name)
					if(!f.canonicalPath.startsWith(tmp.canonicalPath)) continue // no path tricks
					if(e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
				}
			}
			target.deleteRecursively()
			if(!tmp.renameTo(target)) return false
			File(target, ".complete").createNewFile()
			return true
		}
		catch(e: Exception)
		{
			Log.e("SpeechModel", "download failed", e)
			return false
		}
		finally
		{
			zip.delete()
		}
	}
}
