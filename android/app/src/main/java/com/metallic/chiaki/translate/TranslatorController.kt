package com.metallic.chiaki.translate

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the live translator while a stream is on screen:
 *  - every [FRAME_INTERVAL_MS] copies the current video frame, reads English text from it, translates new text;
 *  - in parallel listens to the game audio and translates finished spoken sentences.
 * Results go to [ui] on the main thread.
 */
class TranslatorController(
	private val context: Context,
	private val surfaceView: SurfaceView,
	private val ui: Ui)
{
	interface Ui
	{
		/** [subtitleTop] = top edge of the English subtitle as a fraction of the video height, or null if unknown. */
		fun showScreenText(thai: String, english: String, subtitleTop: Float?)
		fun clearScreenText()
		fun showSpeechText(thai: String, english: String)
		fun clearSpeechText()
		fun showStatus(message: String?)
	}

	private val prefs = TranslatorPrefs(context)
	private val main = Handler(Looper.getMainLooper())
	private val worker = Executors.newSingleThreadExecutor { Thread(it, "translator-ocr") }
	private val translateWorker = Executors.newSingleThreadExecutor { Thread(it, "translator-text") }
	private var copyThread: HandlerThread? = null

	private var screenReader: ScreenReader? = null
	private var speech: SpeechEngine? = null
	private var translator: TextTranslator? = null
	private val tracker = SubtitleTracker()

	private val running = AtomicBoolean(false)
	private val busy = AtomicBoolean(false)
	@Volatile private var streamConnected = false
	@Volatile private var lastScreenTextAt = 0L
	private var frame: Bitmap? = null

	private val speechClear = Runnable { ui.clearSpeechText() }

	fun start()
	{
		if(!prefs.enabled || running.getAndSet(true))
			return
		val mode = prefs.mode
		val tr = TextTranslator(prefs.geminiKey, prefs.geminiModel)
		translator = tr
		translateWorker.execute {
			if(!tr.offlineReady)
			{
				status("กำลังเตรียมตัวแปลภาษาไทย… (ครั้งแรกต้องต่อเน็ต)")
				if(!tr.prepare() && prefs.geminiKey.isEmpty())
				{
					status("โหลดคลังคำแปลไม่สำเร็จ ตรวจอินเทอร์เน็ตแล้วเปิดใหม่")
					return@execute
				}
			}
			status(null)
		}
		if(mode != TranslatorPrefs.Mode.SPEECH)
		{
			screenReader = ScreenReader()
			copyThread = HandlerThread("translator-copy").also { it.start() }
			main.postDelayed(grabRunnable, FRAME_INTERVAL_MS)
		}
		if(mode != TranslatorPrefs.Mode.SCREEN)
		{
			speech = SpeechEngine(context, ::onSpeech) { msg -> status(msg) }.also { it.start() }
		}
	}

	fun stop()
	{
		if(!running.getAndSet(false))
			return
		main.removeCallbacks(grabRunnable)
		main.removeCallbacks(speechClear)
		speech?.stop()
		speech = null
		worker.execute {
			screenReader?.close()
			screenReader = null
		}
		translateWorker.execute {
			translator?.close()
			translator = null
		}
		copyThread?.quitSafely()
		copyThread = null
		tracker.reset()
		ui.clearScreenText()
		ui.clearSpeechText()
		ui.showStatus(null)
	}

	fun setStreamConnected(connected: Boolean)
	{
		streamConnected = connected
	}

	private val grabRunnable = object : Runnable
	{
		override fun run()
		{
			if(!running.get()) return
			main.postDelayed(this, FRAME_INTERVAL_MS)
			if(!streamConnected || busy.get()) return
			grabFrame()
		}
	}

	private fun grabFrame()
	{
		val handlerThread = copyThread ?: return
		val holderSurface = surfaceView.holder.surface
		if(holderSurface == null || !holderSurface.isValid || Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
			return
		val bmp = frame ?: Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888).also { frame = it }
		busy.set(true)
		try
		{
			PixelCopy.request(surfaceView, bmp, { result ->
				if(result == PixelCopy.SUCCESS && running.get())
					worker.execute { processFrame(bmp) }
				else
					busy.set(false)
			}, Handler(handlerThread.looper))
		}
		catch(e: Exception)
		{
			busy.set(false)
			Log.w(TAG, "PixelCopy failed", e)
		}
	}

	private fun processFrame(bmp: Bitmap)
	{
		try
		{
			val reader = screenReader ?: return
			val yOffset = if(prefs.region == TranslatorPrefs.Region.BOTTOM) (bmp.height * 0.55f).toInt() else 0
			val region = if(yOffset > 0)
				Bitmap.createBitmap(bmp, 0, yOffset, bmp.width, bmp.height - yOffset)
			else bmp
			val found = reader.find(region)
			if(region !== bmp) region.recycle()
			val english = found?.text ?: ""
			val now = SystemClock.elapsedRealtime()
			when(val action = tracker.update(english, now))
			{
				is SubtitleTracker.Action.Show ->
				{
					lastScreenTextAt = now
					val top = found?.let { (it.box.top + yOffset).toFloat() / bmp.height }
					val prepared = GameText.fromScreen(action.text)
					translateWorker.execute {
						val thai = translator?.translate(prepared.text) ?: return@execute
						val line = prepared.withSpeaker(thai)
						if(running.get()) main.post { ui.showScreenText(line, action.text, top) }
					}
				}
				SubtitleTracker.Action.Clear -> main.post { ui.clearScreenText() }
				SubtitleTracker.Action.None -> if(english.isNotEmpty()) lastScreenTextAt = now
			}
		}
		catch(e: Exception)
		{
			Log.w(TAG, "frame processing failed", e)
		}
		finally
		{
			busy.set(false)
		}
	}

	private fun onSpeech(english: String)
	{
		// When the game already shows subtitles, the screen translation covers it; skip the duplicate.
		if(prefs.mode == TranslatorPrefs.Mode.BOTH &&
			SystemClock.elapsedRealtime() - lastScreenTextAt < SPEECH_SUPPRESS_MS)
			return
		val prepared = GameText.fromSpeech(english)
		translateWorker.execute {
			val thai = translator?.translate(prepared.text) ?: return@execute
			if(!running.get()) return@execute
			main.post {
				ui.showSpeechText(thai, english)
				main.removeCallbacks(speechClear)
				main.postDelayed(speechClear, SPEECH_SHOW_MS)
			}
		}
	}

	private fun status(msg: String?) = main.post { ui.showStatus(msg) }

	companion object
	{
		private const val TAG = "Translator"
		private const val FRAME_INTERVAL_MS = 450L
		private const val FRAME_W = 1280
		private const val FRAME_H = 720
		private const val SPEECH_SUPPRESS_MS = 3000L
		private const val SPEECH_SHOW_MS = 6000L
	}
}
