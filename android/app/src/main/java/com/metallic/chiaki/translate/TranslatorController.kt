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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
		/** Prompt / hint / document translation; [box] = where the English is, as fractions of the video (0..1). */
		fun showLabel(id: Int, kind: TextKind, thai: String, box: android.graphics.RectF)
		fun removeLabel(id: Int)
		fun clearLabels()
	}

	private val prefs = TranslatorPrefs(context)
	private val main = Handler(Looper.getMainLooper())
	// translator work runs at background priority so the game picture always comes first
	private fun lowPriority(name: String) = java.util.concurrent.ThreadFactory { r ->
		Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, name)
	}
	private val worker = Executors.newSingleThreadExecutor(lowPriority("translator-ocr"))
	private val translateWorker = Executors.newSingleThreadExecutor(lowPriority("translator-text"))
	@Volatile private var quietFrames = 0
	private var copyThread: HandlerThread? = null

	private var screenReader: ScreenReader? = null
	private var speech: SpeechEngine? = null
	private var translator: TextTranslator? = null
	private val tracker = SubtitleTracker()
	private val items = ItemTracker()
	// how long one read of the screen takes on this TV (smoothed); sets the reading pace
	@Volatile private var readMs = 150.0
	private var cloud: CloudTranslator? = null
	// network calls; idle threads end after 30 s so a closed stream leaves nothing running
	private val cloudPool = java.util.concurrent.ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
		java.util.concurrent.LinkedBlockingQueue(), lowPriority("translator-cloud")).apply { allowCoreThreadTimeOut(true) }
	private val recentEnglish = ArrayDeque<String>()
	private val screenSeq = AtomicInteger()
	private val speechSeq = AtomicInteger()

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
		cloud = prefs.serverUrl.takeIf { it.isNotBlank() }?.let { CloudTranslator(it, prefs.cloudCode, CloudTranslator.deviceId(context)) }
		cloud?.let { c ->
			cloudPool.execute {
				val p = c.check()
				if(p != CloudTranslator.Problem.NONE && running.get())
				{
					status(CloudTranslator.problemText(p))
					main.postDelayed({ if(running.get()) ui.showStatus(null) }, 7000)
				}
			}
		}
		translateWorker.execute { PhraseBank.load(context); SlangRewriter.load(context); ThaiPhrases.load(context) }
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
			startSpeech(prefs.speechFast)
		}
	}

	fun stop()
	{
		if(!running.getAndSet(false))
			return
		main.removeCallbacks(grabRunnable)
		main.removeCallbacks(speechClear)
		cloud = null
		screenSeq.incrementAndGet()
		speechSeq.incrementAndGet()
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
		items.reset()
		ui.clearLabels()
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
			// 4 reads a second like a live reader, but never faster than this TV can read (the game comes first),
			// and slower while nothing new appears on screen
			val pace = maxOf(FRAME_INTERVAL_MS, (readMs * 1.25).toLong()).coerceAtMost(1200L)
			main.postDelayed(this, if(quietFrames >= 4) maxOf(pace, FRAME_INTERVAL_IDLE_MS) else pace)
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
			val full = prefs.region == TranslatorPrefs.Region.FULL
			val yOffset = if(full) 0 else (bmp.height * 0.55f).toInt()
			val region = if(yOffset > 0)
				Bitmap.createBitmap(bmp, 0, yOffset, bmp.width, bmp.height - yOffset)
			else bmp
			// Cheap check first: if the picture looks the same as last time, reuse the last result
			// instead of running text recognition again (most of the CPU cost).
			val sig = signature(region)
			val prev = lastSignature
			val blocks = if(prev != null && sameImage(prev, sig) && skippedInARow < MAX_SKIPS)
			{
				skippedInARow++
				lastBlocks
			}
			else
			{
				skippedInARow = 0
				val t0 = SystemClock.elapsedRealtime()
				val read = if(full) reader.readAll(region)
					else reader.find(region)?.let { listOf(ScreenReader.Block(it.text, it.box, 1, TextKind.DIALOGUE)) } ?: emptyList()
				readMs = readMs * 0.7 + (SystemClock.elapsedRealtime() - t0) * 0.3
				read.also { lastBlocks = it }
			}
			lastSignature = sig
			val rw = region.width
			val rh = region.height
			if(region !== bmp) region.recycle()
			val dialogue = blocks.firstOrNull { it.kind == TextKind.DIALOGUE }
			val english = dialogue?.text ?: ""
			val now = SystemClock.elapsedRealtime()
			quietFrames = if(blocks.isEmpty()) quietFrames + 1 else 0
			if(english.isNotEmpty()) lastScreenTextAt = now
			speech?.paused = prefs.mode == TranslatorPrefs.Mode.BOTH && now - lastScreenTextAt < SPEECH_SUPPRESS_MS
			when(val action = tracker.update(english, now))
			{
				is SubtitleTracker.Action.Show ->
				{
					lastScreenTextAt = now
					val top = dialogue?.let { (it.box.top + yOffset).toFloat() / bmp.height }
					val prepared = GameText.fromScreen(action.text)
					val seq = screenSeq.incrementAndGet()
					translateWorker.execute {
						smartTranslate(listOf(prepared.text), TextKind.DIALOGUE, { seq == screenSeq.get() }) { thai ->
							val line = prepared.withSpeaker(thai)
							main.post { if(running.get() && seq == screenSeq.get()) ui.showScreenText(line, action.text, top) }
						}
					}
				}
				SubtitleTracker.Action.Clear -> { screenSeq.incrementAndGet(); main.post { ui.clearScreenText() } }
				SubtitleTracker.Action.None -> {}
			}
			// prompts, hints, documents
			if(full) for(ev in items.update(blocks.filter { it.kind != TextKind.DIALOGUE }, rw, rh, now)) when(ev)
			{
				is ItemTracker.Event.Show ->
				{
					val item = ev.item
					val text = GameText.fromScreen(item.text).text
					val box = android.graphics.RectF(item.box)
					translateWorker.execute {
						smartTranslate(listOf(text), item.kind, { item.shown }) { thai ->
							main.post { if(running.get() && item.shown) ui.showLabel(item.id, item.kind, thai, box) }
						}
					}
				}
				is ItemTracker.Event.Remove -> main.post { ui.removeLabel(ev.id) }
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

	private var lastSignature: IntArray? = null
	private var lastBlocks: List<ScreenReader.Block> = emptyList()
	private var skippedInARow = 0

	/** 64x16 grayscale thumbnail of the area being read. */
	private fun signature(region: Bitmap): IntArray
	{
		val small = Bitmap.createScaledBitmap(region, SIG_W, SIG_H, true)
		val px = IntArray(SIG_W * SIG_H)
		small.getPixels(px, 0, SIG_W, 0, 0, SIG_W, SIG_H)
		small.recycle()
		for(i in px.indices)
		{
			val c = px[i]
			px[i] = ((c shr 16 and 0xff) * 3 + (c shr 8 and 0xff) * 6 + (c and 0xff)) / 10
		}
		return px
	}

	/**
	 * Same picture only if nothing changed anywhere: a new subtitle line on a still scene barely moves the
	 * average but makes a few cells jump by 100+ (measured on real footage), so the largest change decides.
	 */
	private fun sameImage(a: IntArray, b: IntArray): Boolean
	{
		var sum = 0L
		var max = 0
		for(i in a.indices)
		{
			val d = kotlin.math.abs(a[i] - b[i])
			sum += d
			if(d > max) max = d
		}
		return max < 30 && sum.toDouble() / a.size < 1.0
	}

	private fun startSpeech(@Suppress("UNUSED_PARAMETER") fast: Boolean)
	{
		if(SpeechModel.isReady(context))
		{
			speech = SpeechEngine(context, ::onSpeech, { msg -> status(msg) }, ::onSpeechTooSlow).also { it.start() }
			return
		}
		// first use: fetch the speech model once (about 100 MB), then start listening
		Thread {
			val ok = SpeechModel.ensure(context) { p -> if(p % 5 == 0) status("กำลังโหลดระบบฟังเสียง (ครั้งแรกครั้งเดียว) $p%") }
			main.post {
				ui.showStatus(null)
				if(!running.get()) return@post
				if(ok) speech = SpeechEngine(context, ::onSpeech, { msg -> status(msg) }, ::onSpeechTooSlow).also { it.start() }
				else status("โหลดระบบฟังเสียงไม่สำเร็จ ตรวจอินเทอร์เน็ต (แปลซับบนจอได้ตามปกติ)")
			}
		}.start()
	}

	/** Live watchdog: speech can't keep up (e.g. a heavy game scene): pause it for this session. */
	private fun onSpeechTooSlow()
	{
		main.post {
			if(!running.get()) return@post
			val old = speech
			speech = null
			Thread {
				old?.stop() // the engine thread is already ending; wait so the audio tap is released first
				main.post {
					if(!running.get()) return@post
					prefs.setSpeechSlow(true)
					if(prefs.modeIsAutomatic) prefs.setModeAutomatically(TranslatorPrefs.Mode.SCREEN)
					ui.showStatus("ทีวีแปลเสียงพูดไม่ทัน ปิดชั่วคราว — แปลซับบนจอต่อ")
					main.postDelayed({ ui.showStatus(null) }, 6000)
				}
			}.start()
		}
	}

	private fun onSpeech(sentences: List<String>)
	{
		// When the game already shows subtitles, the screen translation covers it; skip the duplicate.
		if(prefs.mode == TranslatorPrefs.Mode.BOTH &&
			SystemClock.elapsedRealtime() - lastScreenTextAt < SPEECH_SUPPRESS_MS)
			return
		val prepared = sentences.map { GameText.fromSpeech(it).text }
		val english = prepared.joinToString(" ")
		val seq = speechSeq.incrementAndGet()
		translateWorker.execute {
			// each sentence separately (phrase bank first), then joined: reads far better than one run-on line
			smartTranslate(prepared, TextKind.DIALOGUE, { seq == speechSeq.get() }) { thai ->
				main.post {
					if(!running.get() || seq != speechSeq.get()) return@post
					ui.showSpeechText(thai, english)
					main.removeCallbacks(speechClear)
					main.postDelayed(speechClear, SPEECH_SHOW_MS)
				}
			}
		}
	}

	/**
	 * Best translation without making the player wait:
	 *  1. hand-written bank / earlier Gemini line -> shown at once, no server call;
	 *  2. otherwise wait for the shared server (cache or Gemini) and show only that text
	 *     (while that subtitle is still the current one);
	 *  3. the offline translator is used only without a server/licence, or when the server fails a line.
	 * Runs on translateWorker; [show] is called with the Thai text (it posts to the UI itself).
	 */
	private fun smartTranslate(lines: List<String>, kind: TextKind, isCurrent: () -> Boolean, show: (String) -> Unit)
	{
		val tr = translator ?: return
		if(lines.isEmpty()) return
		val known = lines.map { tr.best(it, kind) }
		val missing = lines.indices.filter { known[it] == null }
		val ctx = if(kind != TextKind.DIALOGUE) emptyList() else synchronized(recentEnglish) {
			val c = recentEnglish.toList()
			lines.forEach { recentEnglish.addLast(it) }
			while(recentEnglish.size > 3) recentEnglish.removeFirst()
			c
		}
		if(missing.isEmpty()) { show(known.filterNotNull().joinToString(" ")); return }
		val c = cloud
		val future: CompletableFuture<List<String?>?>? = if(c != null && c.usable)
			CompletableFuture.supplyAsync({
				// plain-English "hint" for slangy lines helps Gemini get the meaning right
				val src = missing.map { lines[it] }
				c.translate(src, ctx, kind, src.map { l -> if(kind == TextKind.DIALOGUE) SlangRewriter.rewrite(l).takeIf { it != l } else null })
			}, cloudPool)
		else null
		// offline text only when it is really needed (no server, or the server failed this line)
		val offline by lazy { tr.translateEach(lines, kind) }
		fun merge(fromCloud: List<String?>?): String?
		{
			val out = lines.indices.map { i ->
				known[i] ?: fromCloud?.getOrNull(missing.indexOf(i))?.also { tr.remember(lines[i], it, kind) } ?: offline.getOrNull(i)
			}.filterNotNull()
			return if(out.isEmpty()) null else out.joinToString(" ")
		}
		if(future == null) { merge(null)?.let(show); return }
		// AI only: while the server is reachable the player sees Gemini text, never the stiff offline line.
		// The offline translator is used only when the server fails for this line (network, quota, error).
		future.whenComplete { fromCloud, _ ->
			if(!running.get() || !isCurrent()) return@whenComplete
			merge(fromCloud)?.let(show)
		}
	}

	private fun status(msg: String?) = main.post { ui.showStatus(msg) }

	companion object
	{
		private const val TAG = "Translator"
		private const val FRAME_INTERVAL_MS = 250L // 4 reads a second
		private const val FRAME_INTERVAL_IDLE_MS = 600L
		private const val SIG_W = 64
		private const val SIG_H = 16
		private const val MAX_SKIPS = 6 // re-read at least every ~3 s anyway
		private const val FRAME_W = 960
		private const val FRAME_H = 540
		private const val SPEECH_SUPPRESS_MS = 3000L
		private const val SPEECH_SHOW_MS = 6000L

	}
}
