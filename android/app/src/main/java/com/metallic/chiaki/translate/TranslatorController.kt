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
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Runs the live translator while a stream is on screen:
 *  - every [FRAME_INTERVAL_MS] copies the current video frame, reads English text from it, translates new text;
 *  - in parallel listens to the game audio and translates finished spoken sentences.
 * Results go to [ui] on the main thread.
 */
class TranslatorController(
	private val context: Context,
	private val surfaceView: SurfaceView?,
	private val ui: Ui,
	/** HDMI mode: frames come from the hidden stream instead of a SurfaceView on screen (null = nothing new yet). */
	private val frameSource: (() -> Bitmap?)? = null)
{
	interface Ui
	{
		/** [subtitleTop] = top edge of the English subtitle as a fraction of the video height, or null if unknown. */
		fun showScreenText(thai: String, english: String, subtitleTop: Float?, subtitleBottom: Float? = null)
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
	private val cloudPool = java.util.concurrent.ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS,
		java.util.concurrent.LinkedBlockingQueue(), lowPriority("translator-cloud")).apply { allowCoreThreadTimeOut(true) }
	private val recentEnglish = ArrayDeque<String>()
	private val screenSeq = AtomicInteger()
	// the newest line whose Thai is on screen, and the line whose English is on screen now (0 = none): a Thai
	// answer that comes back after its English left is still shown (for its reading time), never thrown away
	private val shownSeq = AtomicInteger()
	@Volatile private var onScreenSeq = 0
	@Volatile private var lineChangedAt = 0L
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
		// the game picked in the app (GTA VI): subtitles from the first line of the game, no waiting to "see" it
		pickedGame = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getString("tv_game", "gta6") ?: "gta6"
		cloud?.game = pickedGame
		// the game's translation library onto the TV (only what is new since last time): known lines show at once
		cloudPool.execute { GameLibrary.prepare(context, pickedGame, cloud) }
		cloud?.let { c ->
			cloudPool.execute {
				val p = c.check()
				if(running.get())
				{
					// one plain line in Thai; nothing technical before the game
					if(p == CloudTranslator.Problem.NONE || p == CloudTranslator.Problem.OFFLINE)
					{
						status(if(p == CloudTranslator.Problem.NONE) "✔ พร้อมแปล · ซับไทยจะขึ้นเองเมื่อเข้าเกม (เปิด Subtitles ในเกม)"
							else "ต่ออินเทอร์เน็ตไม่ได้ · ตรวจเน็ตของทีวี")
						main.postDelayed({ if(running.get()) ui.showStatus(null) }, 6000)
					}
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
		if(mode != TranslatorPrefs.Mode.SPEECH && com.metallic.chiaki.crash.CrashReport.disabled(ScreenReader.MARK_TESS))
		{
			// both text readers crashed this TV: keep the game playable, without reading subtitles
			status("ทีวีรุ่นนี้อ่านซับบนจอไม่ได้ (ชิปไม่รองรับตัวอ่านข้อความ) · เล่นเกมได้ปกติ")
			main.postDelayed({ if(running.get()) ui.showStatus(null) }, 10000)
		}
		else if(mode != TranslatorPrefs.Mode.SPEECH)
		{
			screenReader = ScreenReader(context).also { engineSeen = it.engineName }
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

	// ---- play summary: what the reader saw, so "no subtitles" on a customer's TV can be explained ----
	private val st = AtomicIntegerArray(7) // grabs, copy failed, black frames, OCR runs, OCR saw text, subtitles shown, sent to AI
	@Volatile private var lastCopyResult = 0
	@Volatile private var connectedSince = 0L
	@Volatile private var summarySent = false

	private fun maybeSummary()
	{
		if(summarySent || connectedSince == 0L || st.get(5) > 0) return
		if(SystemClock.elapsedRealtime() - connectedSince < 90_000L) return
		summarySent = true
		val grabs = st.get(0); val fail = st.get(1); val black = st.get(2); val ocr = st.get(3); val saw = st.get(4)
		val why = when
		{
			grabs == 0 -> "แอปยังไม่ได้เริ่มอ่านภาพ"
			fail * 2 > grabs -> "ทีวีไม่ให้แอปจับภาพเกม (รหัส $lastCopyResult)"
			black * 2 > grabs - fail -> "ภาพที่แอปจับได้เป็นสีดำ"
			saw == 0 -> "อ่านภาพได้ แต่ไม่เจอตัวหนังสือเลย"
			else -> "เจอตัวหนังสือ แต่ไม่ใช่ซับบทพูด"
		}
		val code = "G$grabs F$fail B$black O$ocr T$saw R${if(prefs.region == TranslatorPrefs.Region.FULL) "full" else "bottom"} ${screenReader?.engineName ?: ""}"
		val nl = System.lineSeparator()
		val seen = screenReader?.lastTexts?.take(8)?.joinToString(" | ") ?: ""
		com.metallic.chiaki.crash.CrashReport.upload(context,
			"ประเภท: สรุปการเล่น (ยังไม่มีซับไทยใน 90 วิ)" + nl + why + nl + code + nl + "ตัวอย่างข้อความที่อ่านได้: " + seen + nl + "ข้อผิดพลาดล่าสุด: " + (screenReader?.lastError ?: "-"))
		// on screen only once in the game, as a plain tip (the details go to the owner's report above)
		if((cloud?.gameOkUntil ?: 0L) > System.currentTimeMillis())
		{
			status("ยังไม่เห็นซับอังกฤษ · เปิดซับในเกม: Settings › Subtitles › On")
			main.postDelayed({ if(running.get()) ui.showStatus(null) }, 8000)
		}
	}

	@Volatile private var engineSeen = ""

	/** Last time a Thai subtitle was shown (elapsedRealtime), for 4K mode's auto-stop. */
	@Volatile var lastShownAt = 0L
		private set

	// the PS5's own screens (home, library, store, settings): never "translated" as subtitles
	private val ps5MenuSigns = Regex("""\b(game library|playstation store|playstation plus|game base|all games|media gallery|remastered|ps4\s*&\s*ps5|ps5\s*&\s*ps4)\b""", RegexOption.IGNORE_CASE)
	@Volatile private var menuUntil = 0L

	// controller prompts / menu bars read in the subtitle area ("Photo Studio  Select  Back ○",
	// "Hook fish R2 Lure fish ○ Reset cast"): not dialogue, never translated as subtitles
	private val buttonTokens = setOf("r1", "r2", "l1", "l2", "r3", "l3", "o", "x", "△", "□", "○", "✕", "×", "options", "touchpad")
	private val menuWords = setOf("select", "back", "confirm", "cancel", "skip", "continue", "hold", "press", "tap", "zoom", "rotate", "exit")
	// words of real sentences: a line with these is dialogue or an instruction ("Hold on, think about this",
	// "Press ○ to threaten the teller", "Hold L2 to aim"), never a menu bar, even with a stray "o" read off an icon
	private val sentenceWords = setOf("to", "the", "you", "your", "i", "im", "we", "is", "it", "on", "in", "me", "my", "he",
		"she", "they", "him", "her", "what", "are", "this", "that", "of", "for", "and", "with", "dont", "cant", "lets", "now",
		"here", "there", "be", "just", "got", "gotta", "know", "when", "like", "about", "minute", "if", "do", "can", "will",
		"was", "not", "no", "yeah", "hey", "oh", "man", "us", "our", "at", "all", "need", "want", "come", "look", "see")
	private fun isButtonPrompt(text: String): Boolean
	{
		val words = text.lowercase().replace("'", "").replace("’", "").split(Regex("""[\s,.:;!?()\[\]"]+""")).filter { it.isNotEmpty() }
			.let { w -> // a lone "o"/"x" at either end is usually an icon or OCR speck beside the subtitle, not a button
				var a = 0; var b = w.size
				while(a < b && w[a] in setOf("o", "x")) a++
				while(b > a && w[b - 1] in setOf("o", "x")) b--
				w.subList(a, b)
			}
		if(words.isEmpty()) return false
		val sentence = words.count { it in sentenceWords }
		if(sentence >= 2) return false
		if(sentence >= 1 && words.first() in setOf("press", "hold", "use", "tap", "release")) return false // a how-to line: translate
		if(words.first() == "hold" && words.getOrNull(1) in setOf("up", "on", "it", "still", "tight")) return false
		val buttons = words.count { it in buttonTokens }
		val menu = words.count { it in menuWords }
		if(buttons >= 1 && words.size <= 14) return true                 // a button symbol in a short line
		if(menu >= 2 && words.size <= 8) return true                     // "Select Back", "Skip Hold"
		if(words.first() in setOf("press", "hold", "tap") && words.size <= 10) return true
		return false
	}

	// ---- in the game yet? Thai shows only after GTA VI is recognised (Rockstar / GTA screens, GTA place names,
	// or the server already knows this TV is in GTA). Fallback for a player who resumes straight into the game:
	// subtitle-like lines in the subtitle band keep coming for 25 s after the stream started.
	@Volatile private var subtitleLikeSince = 0L
	@Volatile private var subtitleLikeCount = 0

	@Volatile private var pickedGame = ""

	private fun inGame(now: Long): Boolean
	{
		if(pickedGame.isNotEmpty() && connectedSince != 0L) return true
		if((cloud?.gameOkUntil ?: 0L) > System.currentTimeMillis()) return true
		if(connectedSince == 0L || now - connectedSince < 20_000L) return false
		if(subtitleLikeSince == 0L) subtitleLikeSince = now
		subtitleLikeCount++
		return subtitleLikeCount >= 4 && now - subtitleLikeSince >= 25_000L
	}

	/** Counters for the end-of-game report since the last call (take it before [stop]). */
	fun stats(): SessionReport.Stats
	{
		val s = SessionReport.Stats(
			reads = st.getAndSet(3, 0), saw = st.getAndSet(4, 0), shown = st.get(5), ai = st.getAndSet(6, 0), copyFailed = st.getAndSet(1, 0),
			readMs = readMs.toInt(), engine = screenReader?.engineName ?: engineSeen)
		shownBase.let { b -> shownBase = s.shown; return s.copy(shown = s.shown - b) }
	}
	@Volatile private var shownBase = 0 // st[5] stays (it tells the 90-second check that subtitles already showed)

	fun setStreamConnected(connected: Boolean)
	{
		streamConnected = connected
		if(connected && connectedSince == 0L) connectedSince = SystemClock.elapsedRealtime()
		if(connected) Diag.mark(context, Diag.STREAM_AT)
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
			maybeSummary()
			grabFrame()
		}
	}

	// ---- subtitle band: where this game draws its subtitles, learned from the lines read so far ----
	private val bandTops = ArrayDeque<Int>()
	private val bandBottoms = ArrayDeque<Int>()
	private var bandReads = 0

	private fun learnBand(top: Int, bottom: Int, h: Int)
	{
		if(bottom - top < h / 60) return
		synchronized(bandTops) {
			bandTops.addLast(top); bandBottoms.addLast(bottom)
			while(bandTops.size > 12) { bandTops.removeFirst(); bandBottoms.removeFirst() }
		}
	}

	/** Rows [top, bottom) to read: the learned band (plus room for more lines), else the lower 45 %. */
	@Volatile private var bandMissed = false
	private fun subtitleBand(h: Int): IntArray
	{
		val wide = intArrayOf((h * 0.55f).toInt(), h)
		bandReads++
		val tops: List<Int>; val bottoms: List<Int>
		synchronized(bandTops) { tops = bandTops.sorted(); bottoms = bandBottoms.sorted() }
		// the whole lower part again every 3rd read, and right after the band came up empty: GTA switches between
		// big one-line and small two-line subtitles at another height (they were missed for seconds before)
		if(tops.size < 4 || bandReads % 3 == 0 || bandMissed) return wide
		val top = tops[tops.size / 2]
		val bottom = bottoms[bottoms.size / 2]
		val line = maxOf(h / 30, (bottom - top) / 2)
		return intArrayOf(maxOf(wide[0], top - line * 3), minOf(h, bottom + line * 2))
	}

	private fun grabFrame()
	{
		val src = frameSource
		if(src != null)
		{
			busy.set(true)
			st.incrementAndGet(0)
			worker.execute {
				val b = try { src() } catch(_: Throwable) { null }
				if(b != null && running.get()) processFrame(b) else { if(b == null) st.incrementAndGet(1); busy.set(false) }
			}
			return
		}
		val surfaceView = surfaceView ?: return
		val handlerThread = copyThread ?: return
		val holderSurface = surfaceView.holder.surface
		if(holderSurface == null || !holderSurface.isValid || Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
			return
		// subtitles only: the lower half at the picture's own sharpness (up to 1920 wide). Before, the whole picture
		// was shrunk to 960x540 and thin gameplay subtitles were too small to read.
		val sw = surfaceView.width; val sh = surfaceView.height
		val half = prefs.region != TranslatorPrefs.Region.FULL && sw > 0 && sh > 0
		val tw = if(half) minOf(sw, 1920) else FRAME_W
		val th = if(half) maxOf(1, (sh / 2) * tw / sw) else FRAME_H
		val bmp = frame?.takeIf { it.width == tw && it.height == th } ?: Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).also { frame = it }
		busy.set(true)
		st.incrementAndGet(0)
		try
		{
			val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
				if(result == PixelCopy.SUCCESS && running.get())
					worker.execute { processFrame(bmp) }
				else
				{
					if(result != PixelCopy.SUCCESS) { st.incrementAndGet(1); lastCopyResult = result }
					busy.set(false)
				}
			}
			if(half) PixelCopy.request(surfaceView, android.graphics.Rect(0, sh - sh / 2, sw, sh), bmp, listener, Handler(handlerThread.looper))
			else PixelCopy.request(surfaceView, bmp, listener, Handler(handlerThread.looper))
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
			// the picture source may hand over only the lower half of the game picture, at full resolution
			// (a 16:9 picture's lower half is twice as wide as it is tall): rows are counted in the whole picture
			val lower = !full && bmp.width > bmp.height * 2.5f
			val H = if(lower) bmp.height * 2 else bmp.height
			val off = H - bmp.height
			val band = if(full) intArrayOf(0, H) else subtitleBand(H)
			val b0 = maxOf(band[0], off)
			val yOffset = b0
			val region = if(b0 - off > 0 || band[1] - off < bmp.height)
				Bitmap.createBitmap(bmp, 0, b0 - off, bmp.width, minOf(bmp.height, band[1] - off) - (b0 - off))
			else bmp
			// Cheap check first: if the picture looks the same as last time, reuse the last result
			// instead of running text recognition again (most of the CPU cost).
			val sig = signature(region)
			if(sig.all { it < 12 }) st.incrementAndGet(2)
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
				val read = readRegion(reader, region, full) { b -> (b.box.centerY() + yOffset).toFloat() / H >= SUBTITLE_MIN_CENTER }
				readMs = readMs * 0.7 + (SystemClock.elapsedRealtime() - t0) * 0.3
				st.incrementAndGet(3)
				if(reader.lastTexts.isNotEmpty()) st.incrementAndGet(4)
				maybeUnlockGame(reader.lastTexts)
				read.also { lastBlocks = it }
			}
			lastSignature = sig
			if(!full) maybePeekWholeScreen(reader, bmp)
			val rw = region.width
			val rh = region.height
			// do not recycle the cropped copy: on slow TVs ML Kit may still be reading it after a time-out
			// (recycling it crashed 32-bit TVs); the garbage collector frees it safely
			if(reader.timedOut) { frame = null; slowReads++ } // the shared frame may still be in use too: take a fresh one next time
			// subtitles sit low on the screen; text around the middle (the in-game phone, menus) is not a subtitle:
			// it moved the Thai line up to the middle of the screen and taught the band the wrong height
			val dialogue = blocks.firstOrNull { it.kind == TextKind.DIALOGUE &&
				(it.box.centerY() + yOffset).toFloat() / H >= SUBTITLE_MIN_CENTER }
			val readWide = b0 <= (H * 0.56f).toInt()
			if(!full) bandMissed = dialogue == null && !readWide
			if(!full && dialogue != null) learnBand(dialogue.box.top + yOffset, dialogue.box.bottom + yOffset, H)
			val english = dialogue?.text ?: ""
			val now = SystemClock.elapsedRealtime()
			quietFrames = if(blocks.isEmpty()) quietFrames + 1 else 0
			if(english.isNotEmpty()) lastScreenTextAt = now
			speech?.paused = prefs.mode == TranslatorPrefs.Mode.BOTH && now - lastScreenTextAt < SPEECH_SUPPRESS_MS
			when(val action = tracker.update(english, now, oneRead = readMs > 550.0))
			{
				is SubtitleTracker.Action.Show ->
				{
					// only a short line is a PS5 menu ("Game Library"); a sentence that happens to say "all games" is dialogue
					if(ps5MenuSigns.containsMatchIn(action.text) && action.text.split(' ').size <= 6) { menuUntil = now + 15_000L; main.post { ui.clearScreenText() }; return }
					if(isButtonPrompt(action.text)) return
					if(now < menuUntil || !inGame(now)) return
					lastShownAt = now
					lastScreenTextAt = now
					st.incrementAndGet(5)
					Diag.mark(context, Diag.SUBTITLE_AT)
					val top = dialogue?.let { (it.box.top + yOffset).toFloat() / H }
					val bottom = dialogue?.let { (it.box.bottom + yOffset).toFloat() / H }
					val prepared = GameText.fromScreen(action.text)
					val seq = screenSeq.incrementAndGet()
					onScreenSeq = seq
					lineChangedAt = now
					val askedAt = now
					translateWorker.execute {
						smartTranslate(listOf(prepared.text), TextKind.DIALOGUE, { running.get() && seq >= shownSeq.get() }) { thai ->
							val line = prepared.withSpeaker(thai)
							main.post {
								if(!running.get() || seq < shownSeq.get()) return@post // a newer line is already up
								val gone = seq != onScreenSeq
								val t = SystemClock.elapsedRealtime()
								if(gone && t - askedAt > LATE_MAX_MS) return@post
								// never under someone else's line for long: the Thai must match the English on screen
								if(gone && onScreenSeq > seq && t - lineChangedAt > LATE_OVER_NEWER_MS) return@post
								shownSeq.set(seq)
								ui.showScreenText(line, action.text, top, bottom)
								// its English already left (fast talk): it stays its reading time, then goes
								if(gone) ui.clearScreenText()
							}
						}
					}
				}
				SubtitleTracker.Action.Clear -> { onScreenSeq = 0; main.post { ui.clearScreenText() } }
				SubtitleTracker.Action.None -> {}
			}
			// prompts, hints, documents (only when the player turned it on: by default subtitles only)
			if(full && prefs.translateUi) for(ev in items.update(blocks.filter { it.kind != TextKind.DIALOGUE }, rw, rh, now)) when(ev)
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

	// the last time the cleaned-up picture (TextMask) found the subtitle: busy gameplay scenes read it first
	@Volatile private var maskWonAt = 0L

	/**
	 * Reads the subtitle area. Slow TVs read a smaller copy (still sharper than before). When the plain picture
	 * gives no subtitle, the same area is read again with only the subtitle letters kept (TextMask): white
	 * gameplay subtitles over a bright, busy scene. Boxes come back in [region] pixels.
	 */
	private fun readRegion(reader: ScreenReader, region: Bitmap, full: Boolean, isSubtitle: (ScreenReader.Block) -> Boolean): List<ScreenReader.Block>
	{
		val scale = if(region.width > 1280 && readMs > 650.0) 1280f / region.width else 1f
		val img = if(scale < 1f) Bitmap.createScaledBitmap(region, 1280, maxOf(1, (region.height * scale).toInt()), true) else region
		fun read(b: Bitmap) = if(full) reader.readAll(b)
			else reader.find(b)?.let { listOf(ScreenReader.Block(it.text, it.box, 1, TextKind.DIALOGUE)) } ?: emptyList()
		fun back(list: List<ScreenReader.Block>) = if(scale == 1f) list else list.map { b ->
			ScreenReader.Block(b.text, android.graphics.Rect((b.box.left / scale).toInt(), (b.box.top / scale).toInt(),
				(b.box.right / scale).toInt(), (b.box.bottom / scale).toInt()), b.lines, b.kind) }
		fun found(list: List<ScreenReader.Block>) = list.any { it.kind == TextKind.DIALOGUE && isSubtitle(it) }
		val now = SystemClock.elapsedRealtime()
		val maskFirst = !full && now - maskWonAt < 8000L
		if(maskFirst)
		{
			val m = back(read(TextMask.make(img)))
			if(found(m)) { maskWonAt = now; return m }
			return back(read(img))
		}
		val plain = back(read(img))
		if(full || found(plain) || reader.timedOut) return plain
		val m = back(read(TextMask.make(img)))
		if(found(m)) { maskWonAt = now; return m }
		return plain
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
		// this TV crashed while listening before: keep speech off here, subtitles still translate
		if(com.metallic.chiaki.crash.CrashReport.disabled("speech")) { status("ทีวีนี้ปิดการฟังเสียงไว้ (เคยทำให้แอปเด้ง) · แปลซับตามปกติ"); return }
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
		// speech before the game (PS5 menus, videos) is not translated either
		if(pickedGame.isEmpty() && (cloud?.gameOkUntil ?: 0L) <= System.currentTimeMillis() && subtitleLikeCount < 4) return
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
		// phrase bank, then the game's library on this TV (no network): every subtitle anyone has seen before
		val known = lines.map { tr.best(it, kind) ?: if(kind == TextKind.DIALOGUE) GameLibrary.find(it) else null }
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
				val hints = src.map { l -> if(kind == TextKind.DIALOGUE) SlangRewriter.rewrite(l).takeIf { it != l } else null }
				val first = c.translate(src, ctx, kind, hints)
				// a failed answer (busy network, server hiccup) is asked once more instead of leaving the line blank
				if((first == null || first.all { it == null }) && c.problem != CloudTranslator.Problem.OFFLINE && isCurrent())
				{ Thread.sleep(300); c.translate(src, ctx, kind, hints) ?: first } else first
			}, cloudPool)
		else null
		// offline text only when it is really needed (no server, or the server failed this line)
		val offline by lazy { tr.translateEach(lines, kind) }
		fun merge(fromCloud: List<String?>?): String?
		{
			val out = lines.indices.map { i ->
				known[i] ?: fromCloud?.getOrNull(missing.indexOf(i))?.also { tr.remember(lines[i], it, kind); if(kind == TextKind.DIALOGUE) GameLibrary.put(lines[i], it) } ?: offline.getOrNull(i)
			}.filterNotNull()
			return if(out.isEmpty()) null else out.joinToString(" ")
		}
		if(future == null)
		{
			Diag.mark(context, Diag.OFFLINE_AT, Diag.OFFLINE_WHY to (c?.problem?.name ?: "NO_SERVER"))
			merge(null)?.let(show); return
		}
		// Fast lane (dialogue, launch week - the server decides): the small model answers in ~1.5 s and is shown
		// first; the stronger model's line replaces it when it arrives. Fast text is never cached or remembered.
		val mainDone = java.util.concurrent.atomic.AtomicBoolean(false)
		val fastShown = java.util.concurrent.atomic.AtomicBoolean(false)
		if(FAST_LANE && kind == TextKind.DIALOGUE && c != null)
			CompletableFuture.supplyAsync({
				val src = missing.map { lines[it] }
				c.translate(src, ctx, kind, src.map { l -> SlangRewriter.rewrite(l).takeIf { it != l } }, fast = true)
			}, cloudPool).whenComplete { fromFast, _ ->
				if(mainDone.get() || !running.get() || !isCurrent() || fromFast == null || fromFast.all { it == null }) return@whenComplete
				val text = lines.indices.mapNotNull { i -> known[i] ?: fromFast.getOrNull(missing.indexOf(i)) }
				if(text.size == lines.size && !mainDone.get()) { fastShown.set(true); show(text.joinToString(" ")) }
			}
		// AI only: while the server is reachable the player sees Gemini text, never the stiff offline line.
		// The offline translator is used only when the server fails for this line (network, quota, error)
		// and no fast text is on screen already.
		future.whenComplete { fromCloud, _ ->
			mainDone.set(true)
			if(!running.get() || !isCurrent()) return@whenComplete
			val ok = fromCloud != null && !fromCloud.all { it == null }
			if(ok) { Diag.mark(context, Diag.AI_AT); st.incrementAndGet(6) }
			else Diag.mark(context, Diag.OFFLINE_AT, Diag.OFFLINE_WHY to (c?.problem?.name ?: "SERVER_FAILED"))
			if(!ok && fastShown.get()) return@whenComplete
			if(!ok && c != null && c.problem != CloudTranslator.Problem.OFFLINE)
			{
				// what is already known (shared library / phrase bank) is still shown
				if(known.all { it != null }) show(known.filterNotNull().joinToString(" "))
				return@whenComplete
			}
			merge(fromCloud)?.let(show)
		}
	}

	private fun status(msg: String?) = main.post { ui.showStatus(msg) }

	/**
	 * GTA-only AI: when the screen shows GTA VI (Rockstar / "Grand Theft Auto" start screens, the game tile on the
	 * PS5 home screen, GTA place or character names) tell the server, which then opens AI for this TV for hours.
	 */
	private val gtaSigns = Regex("""\b(rockstar|grand\s*theft\s*auto|gta\s*(vi|6)|vice\s*city|vice\s*beach|leonida|port\s*gellhorn|grassrivers|ambrosia|mount\s*kalaga|lucia)\b""", RegexOption.IGNORE_CASE)
	@Volatile private var unlockAskedAt = 0L
	@Volatile private var lastPeekAt = 0L
	@Volatile private var slowReads = 0

	private fun maybePeekWholeScreen(reader: ScreenReader, frame: Bitmap)
	{
		val c = cloud ?: return
		// the game was picked in the app: no extra whole-screen reads to spot it (half the work on slow TVs)
		if(pickedGame.isNotEmpty()) return
		val now = SystemClock.elapsedRealtime()
		if(c.gameOkUntil > System.currentTimeMillis() + 600_000L || now - lastPeekAt < 3000L) return
		// slow TV (reads take long, or timed out): skip the extra whole-screen read, the game comes first
		if(readMs > 700.0 || slowReads > 0 || android.os.Build.SUPPORTED_64_BIT_ABIS.isEmpty() && readMs > 400.0) return
		lastPeekAt = now
		reader.find(frame) // whole picture once: only to spot GTA VI title / loading screens
		maybeUnlockGame(reader.lastTexts)
	}

	private fun maybeUnlockGame(texts: List<String>)
	{
		val c = cloud ?: return
		val now = System.currentTimeMillis()
		if(c.gameOkUntil > now + 600_000L || now - unlockAskedAt < 20_000L) return
		if(texts.none { gtaSigns.containsMatchIn(it) }) return
		unlockAskedAt = now
		cloudPool.execute { c.unlock(texts) }
	}

	companion object
	{
		private const val TAG = "Translator"
		private const val FRAME_INTERVAL_MS = 250L // 4 reads a second
		private const val FRAME_INTERVAL_IDLE_MS = 600L
		/** A subtitle's centre is below this fraction of the picture (GTA: ~0.8-0.93; the in-game phone: ~0.55-0.75). */
		private const val SUBTITLE_MIN_CENTER = 0.70f
		private const val SIG_W = 64
		private const val SIG_H = 16
		private const val MAX_SKIPS = 6 // re-read at least every ~3 s anyway
		private const val FRAME_W = 960
		private const val FRAME_H = 540
		private const val SPEECH_SUPPRESS_MS = 3000L
		private const val FAST_LANE = false // the small model's quick text was often literal/wrong: one good answer only
		/** A late Thai line is still shown while a newer English line is up only this soon after the change. */
		private const val LATE_OVER_NEWER_MS = 2500L
		private const val LATE_MAX_MS = 9000L // a Thai line that comes back later than this after its English is dropped
		private const val SPEECH_SHOW_MS = 6000L

	}
}
