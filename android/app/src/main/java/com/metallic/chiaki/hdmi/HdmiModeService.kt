package com.metallic.chiaki.hdmi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.*
import com.metallic.chiaki.translate.TranslatorController
import com.metallic.chiaki.translate.TranslatorOverlay

/**
 * HDMI mode: the player watches the PS5 on the TV's HDMI input (full 4K, no lag, controller on the PS5) while this
 * service keeps a silent, hidden Remote Play stream open only to read the English subtitles, and draws the Thai line
 * in a transparent window on top of the HDMI picture.
 *
 * Needs: an Android / Google TV that the PS5 is plugged into (a box cannot draw over another HDMI input),
 * "display over other apps" for this app, and the TV keeping this app running in the background.
 */
class HdmiModeService : Service()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val IDLE_STOP_MS = 20L * 60 * 1000
		private const val ACTION_STOP = "com.metallic.chiaki.hdmi.STOP"
		private const val CHANNEL = "hdmi_mode"
		private const val NOTE_ID = 7301

		@Volatile var running = false
			private set
		/** Last thing that went wrong, shown on the home screen. */
		@Volatile var lastProblem: String? = null
		/** Why 4K mode ended, for the end-of-game report ("user:..." = the player did it, "system:..." = the TV did). */
		@Volatile var stopReason: String? = null

		fun canDrawOverlays(c: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c)

		fun start(c: Context, info: ConnectInfo)
		{
			lastProblem = null
			stopReason = null
			val i = Intent(c, HdmiModeService::class.java).putExtra(EXTRA_CONNECT_INFO, info)
			// Play build: a plain service (started while the app is on screen); the Thai overlay window keeps it alive
			if(com.metallic.chiaki.store.Store.IS_PLAY) c.startService(i) else ContextCompat.startForegroundService(c, i)
		}

		@Volatile private var instance: HdmiModeService? = null
		private const val WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
		private const val PASS_THROUGH = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

		/** Our app's pages are on screen (true) or the TV shows the PS5 / anything else (false). */
		@Volatile var appInFront = true
			private set

		fun setAppInFront(front: Boolean)
		{
			appInFront = front
			instance?.let { s -> s.main.post { s.applyFocus() } }
		}

		/** Stops 4K mode: straight to the running service (asking Android to start it with "stop" can be refused). */
		fun stop(c: Context, reason: String = "user:หยุดจากแอป")
		{
			stopReason = reason
			val s = instance
			if(s != null) { s.main.post { s.shutDown() }; return }
			try { c.startService(Intent(c, HdmiModeService::class.java).setAction(ACTION_STOP)) } catch(_: Throwable) { }
		}
	}

	private val main = Handler(Looper.getMainLooper())
	private var session: Session? = null
	private var connInfo: ConnectInfo? = null
	/** Mid-game drops (home Wi-Fi lost packets) reconnected by themselves; reset after 2 min of play. */
	private var dropRetries = 0
	private var reader: ImageReader? = null
	private var readerThread: HandlerThread? = null
	private var overlay: View? = null
	private var ui: TranslatorOverlay? = null
	/** Hides the PS5's own "connected to Remote Play" box with the same spot of the stream picture (see BannerCover). */
	@Volatile private var cover: BannerCover? = null
	private var translator: TranslatorController? = null
	private var startedAt = 0L

	// newest picture from the hidden stream, as a small grey image for the text reader
	private val frameLock = Any()
	private var frame: Bitmap? = null
	private var frameAt = 0L
	private var takenAt = 0L
	private var pixels: IntArray? = null

	override fun onBind(intent: Intent?): IBinder? = null

	// any screen of this app in front = the 4K window lets keys through; none (the TV shows the PS5) = it takes them
	private var resumedScreens = 1 // started from the app's home screen, which is in front
	private val screens = object : android.app.Application.ActivityLifecycleCallbacks {
		override fun onActivityResumed(a: android.app.Activity) { resumedScreens++; setAppInFront(true) }
		override fun onActivityPaused(a: android.app.Activity)
		{
			resumedScreens = maxOf(0, resumedScreens - 1)
			// switching between two of our screens resumes the next one right away
			main.postDelayed({ if(resumedScreens == 0) setAppInFront(false) }, 600)
		}
		override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
		override fun onActivityStarted(a: android.app.Activity) {}
		override fun onActivityStopped(a: android.app.Activity) {}
		override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
		override fun onActivityDestroyed(a: android.app.Activity) {}
	}

	override fun onCreate()
	{
		super.onCreate()
		instance = this
		appInFront = true
		application.registerActivityLifecycleCallbacks(screens)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		if(intent?.action == ACTION_STOP) { stopEverything(); stopSelf(); return START_NOT_STICKY }
		val info = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_CONNECT_INFO, ConnectInfo::class.java) }
		goForeground()
		if(info == null || running) return START_NOT_STICKY
		if(!canDrawOverlays(this))
		{
			lastProblem = "ยังไม่ได้อนุญาตให้แอปแสดงทับแอปอื่น"
			stopSelf(); return START_NOT_STICKY
		}
		running = true
		try { begin(info) }
		catch(t: Throwable)
		{
			lastProblem = "เริ่มโหมด HDMI ไม่สำเร็จ: " + (t.message ?: t.javaClass.simpleName)
			stopEverything(); stopSelf()
		}
		return START_NOT_STICKY
	}

	private fun goForeground()
	{
		if(com.metallic.chiaki.store.Store.IS_PLAY) return // no foreground service in the Play build (see its manifest)
		val nm = getSystemService(NotificationManager::class.java)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm?.getNotificationChannel(CHANNEL) == null)
			nm?.createNotificationChannel(NotificationChannel(CHANNEL, "ซับไทยโหมด HDMI", NotificationManager.IMPORTANCE_LOW))
		val stop = PendingIntent.getService(this, 1, Intent(this, HdmiModeService::class.java).setAction(ACTION_STOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		val b = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
		val note = b.setSmallIcon(R.mipmap.ic_launcher)
			.setContentTitle("ซับไทยโหมด HDMI ทำงานอยู่")
			.setContentText("สลับทีวีไปช่อง HDMI ที่ต่อ PS5 ได้เลย")
			.setOngoing(true)
			.addAction(Notification.Action.Builder(null, "หยุด", stop).build())
			.build()
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
		else startForeground(NOTE_ID, note)
	}

	private fun begin(info0: ConnectInfo)
	{
		// the hidden stream is only read, never watched, but it must be as sharp as app mode for the text reader:
		// 1080p (30 frames is plenty for reading) at a good bit rate. 720p at 6 Mbit/s garbled small subtitles.
		val p = info0.videoProfile
		val profile = ConnectVideoProfile(1920, 1080, 30, 12000, p.codec)
		val info = ConnectInfo(info0.ps5, info0.host, info0.registKey, info0.morning, profile)

		// 1. transparent window over everything (the HDMI picture too)
		val wm = getSystemService(WindowManager::class.java)
		val root = LayoutInflater.from(this).inflate(R.layout.hdmi_overlay, null)
		val lp = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
			else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
			// takes keys only while the TV shows the PS5 (see setAppInFront): then a controller connected to the TV plays
			// the PS5 through this window (otherwise the TV takes it as a remote: L2/R2 = volume, touchpad = mouse arrow).
			// While our own app is on screen it must let every key through, or the app's pages cannot be used.
			WINDOW_FLAGS or (if(appInFront) PASS_THROUGH else 0),
			PixelFormat.TRANSLUCENT)
		// under the Thai text and status (index 1, right after the picture stand-in)
		val bc = BannerCover(this).also { it.showing = !appInFront }
		(root as android.view.ViewGroup).addView(bc, 1, android.view.ViewGroup.LayoutParams(
			android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
		cover = bc
		wm.addView(root, lp)
		overlay = root
		overlayLp = lp
		setUpController(root)
		// a controller that disconnects never leaves a button held down on the PS5
		val im = getSystemService(android.hardware.input.InputManager::class.java)
		padListener = object : android.hardware.input.InputManager.InputDeviceListener {
			override fun onInputDeviceAdded(id: Int) {}
			override fun onInputDeviceRemoved(id: Int) { try { input.releaseAll() } catch(_: Throwable) { } }
			override fun onInputDeviceChanged(id: Int) {}
		}.also { im?.registerInputDeviceListener(it, main) }
		val overlayUi = TranslatorOverlay(root, root.findViewById(R.id.hdmiVideo))
		ui = overlayUi
		val gameName = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getString("tv_game_name", null)
		overlayUi.showStatus("กำลังเชื่อมต่อ PS5" + (if(gameName != null) " · เกม $gameName" else "") + " เพื่ออ่านซับ…")

		// 2. hidden stream decoded into an image buffer (nothing on screen)
		val rt = HandlerThread("hdmi-frames").also { it.start() }
		readerThread = rt
		val ir = ImageReader.newInstance(profile.width, profile.height, ImageFormat.YUV_420_888, 3)
		ir.setOnImageAvailableListener({ r -> onImage(r) }, Handler(rt.looper))
		reader = ir

		Session.setAudioMuted(true)
		connInfo = info
		input.controllerStateChangedCallback = { st -> try { session?.setControllerState(st) } catch(_: Throwable) { } }
		openSession()

		// 3. the same subtitle reader / translator as the normal mode, fed from the hidden stream
		val tr = TranslatorController(this, null, overlayUi) { latestFrame() }
		translator = tr
		tr.start()
		startedAt = SystemClock.elapsedRealtime()
		main.postDelayed(idleCheck, 60_000L)
	}

	private fun openSession()
	{
		val info = connInfo ?: return
		val ir = reader ?: return
		val s = Session(info, null, false)
		s.eventCallback = { ev -> if(session === s) main.post { if(session === s) onEvent(ev) } }
		s.start()
		s.setSurface(ir.surface)
		session = s
	}

	private fun onEvent(ev: Event)
	{
		when(ev)
		{
			is ConnectedEvent -> { translator?.setStreamConnected(true); connectedAt = SystemClock.elapsedRealtime()
				ui?.showStatus(if(tipsHidden()) "✔ ซับไทยพร้อม" else "✔ ซับไทยพร้อม · จอยที่ต่อทีวีใช้เล่นได้เลย · เลิกเล่น: กดย้อนกลับที่รีโมท 2 ครั้ง")
				main.postDelayed({ ui?.showStatus(null) }, 9000) }
			// PS5 gone / asks for a PIN: everything stops at once (no message left on the TV, no reconnecting);
			// the reason is shown next time the app is opened
			is LoginPinRequestEvent -> { lastProblem = "PS5 ขอรหัส PIN ผู้ใช้ · ปิดรหัสล็อกผู้ใช้ใน PS5 หรือใช้โหมดเล่นผ่านแอป"; shutDown() }
			is QuitEvent -> {
				// dropped in the middle of a game (Wi-Fi lost packets / PS5 reset the stream): reconnect by itself,
				// up to 3 times, instead of ending 4K mode. The PS5 must not keep the last stick held meanwhile.
				try { input.releaseAll() } catch(_: Throwable) { }
				val why = ev.reason.toString()
				val midGame = connectedAt > 0 && (why.contains("Stream Connection", true) || why.contains("shut down", true))
				if(midGame && SystemClock.elapsedRealtime() - connectedAt > 120_000L) dropRetries = 0
				if(midGame && dropRetries < 3 && session != null)
				{
					dropRetries++
					translator?.setStreamConnected(false)
					ui?.showStatus("เน็ตกระตุก การเชื่อมต่อหลุด · กำลังต่อใหม่อัตโนมัติ ($dropRetries/3)")
					val old = session
					session = null
					Thread { try { old?.stop(); old?.dispose() } catch(_: Throwable) { } }.start()
					main.postDelayed({ if(connInfo != null && reader != null && session == null) try { openSession() } catch(_: Throwable) { shutDown() } }, 2500L)
				}
				else { lastProblem = "การเชื่อมต่อกับ PS5 หลุด" + (ev.reasonString?.let { " ($it)" } ?: ""); shutDown() }
			}
			else -> {}
		}
	}

	// ---- controller: same handling as app mode (StreamInput: button fixes, triggers, sticks)
	private val input by lazy { com.metallic.chiaki.session.StreamInput(this, com.metallic.chiaki.common.Preferences(this)) }
	private var backAt = 0L
	private var overlayLp: WindowManager.LayoutParams? = null

	/** Lets keys through to the app while it is in front; takes them (controller -> PS5) otherwise. */
	private fun applyFocus()
	{
		val v = overlay ?: return
		val lp = overlayLp ?: return
		lp.flags = WINDOW_FLAGS or (if(appInFront) PASS_THROUGH else 0)
		try { getSystemService(WindowManager::class.java).updateViewLayout(v, lp) } catch(_: Throwable) { }
		if(!appInFront) v.post { v.requestFocus() }
		cover?.showing = !appInFront
	}
	private var padListener: android.hardware.input.InputManager.InputDeviceListener? = null

	private fun setUpController(root: View)
	{
		root.isFocusable = true
		root.isFocusableInTouchMode = true
		if(Build.VERSION.SDK_INT >= 24) try { root.pointerIcon = android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL) } catch(_: Throwable) { }
		root.setOnKeyListener { _, _, e ->
			val k = e.keyCode
			// volume / mute stay with the TV
			if(k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || k == android.view.KeyEvent.KEYCODE_VOLUME_MUTE)
				return@setOnKeyListener false
			if(com.metallic.chiaki.session.PadConfig.fromPad(e)) return@setOnKeyListener input.dispatchKeyEvent(e) || true
			// TV remote: "back" twice = เลิกเล่น
			if(k == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP)
			{
				val now = SystemClock.elapsedRealtime()
				if(now - backAt < 2500L) {
					lastProblem = null
					stopReason = "user:กดย้อนกลับ 2 ครั้ง"
					androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit()
						.putBoolean(com.metallic.chiaki.tv.TvActivity.KEY_SHOW_PAD_OFF, true).apply()
					shutDown()
					ui?.let { } // window is gone; the home screen shows how to switch the controller off next time
				}
				else { backAt = now; ui?.showStatus("กดย้อนกลับอีกครั้งเพื่อเลิกเล่น"); main.postDelayed({ ui?.showStatus(null) }, 2500) }
			}
			true
		}
		root.setOnGenericMotionListener { _, e -> input.onGenericMotionEvent(e) || true }
		// DualSense touchpad click (a pointer click on Android TV) = PS5 touchpad button
		root.setOnTouchListener { _, e -> input.onPointerEvent(e); true }
		// mouse / touchpad pointer over the window: swallowed (no arrow, nothing clicked behind)
		root.setOnHoverListener { _, _ -> true }
		root.post { root.requestFocus() }
	}

	/** Ends 4K mode from inside the service (works in the background too). */
	private fun shutDown()
	{
		try { input.releaseAll() } catch(_: Throwable) { }
		padListener?.let { l -> try { getSystemService(android.hardware.input.InputManager::class.java)?.unregisterInputDeviceListener(l) } catch(_: Throwable) { } }
		padListener = null
		main.removeCallbacks(idleCheck)
		stopEverything()
		stopSelf()
	}

	/** The app was closed from the recent-apps list: 4K mode ends with it. */
	override fun onTaskRemoved(rootIntent: Intent?)
	{
		lastProblem = null
		stopReason = "user:ปัดแอปทิ้งจากรายการแอป"
		shutDown()
		super.onTaskRemoved(rootIntent)
	}

	// no Thai line for a long time (PS5 left on its home screen, TV switched to something else): stop by itself
	private var connectedAt = 0L
	private fun tipsHidden() = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
		.getBoolean(com.metallic.chiaki.tv.TvActivity.KEY_HIDE_TIPS, false)
	private var noSubTipShown = false

	private val idleCheck = object : Runnable { override fun run() {
		val tr = translator ?: return
		// connected 2 minutes and not one Thai line yet: almost always the game's own subtitles are off
		if(!noSubTipShown && !tipsHidden() && connectedAt > 0 && tr.lastShownAt == 0L && SystemClock.elapsedRealtime() - connectedAt > 120_000L)
		{
			noSubTipShown = true
			ui?.showStatus("ยังไม่เห็นซับอังกฤษในเกม · เปิดในเกม: Settings › Subtitles › On (ซับไทยจะขึ้นเอง)")
			main.postDelayed({ ui?.showStatus(null) }, 12_000L)
		}
		val quietSince = maxOf(startedAt, tr.lastShownAt)
		if(startedAt > 0 && SystemClock.elapsedRealtime() - quietSince > IDLE_STOP_MS)
		{
			lastProblem = "หยุดโหมด 4K อัตโนมัติ (ไม่มีซับเกม " + IDLE_STOP_MS / 60000 + " นาที)"
			shutDown(); return
		}
		main.postDelayed(this, 60_000L)
	} }

	/** Keeps the newest picture as a 960x540 grey bitmap (only the brightness plane: all the text reader needs). */
	// newest brightness plane, copied in one go (memcpy) while the decoder's image is held as briefly as possible;
	// it is turned into the reader's 960x540 picture only when the reader asks for it (on its own thread).
	// Converting every frame pixel by pixel from the decoder buffer was slow enough on weaker TVs to make the
	// subtitles late or skip lines in 4K mode.
	private var yBytes: ByteArray? = null
	private var lowerPixels: IntArray? = null
	private var yW = 0; private var yH = 0; private var yStride = 0; private var yPix = 1

	private fun onImage(r: ImageReader)
	{
		val img = try { r.acquireLatestImage() } catch(_: Throwable) { null } ?: return
		try
		{
			// every picture (30 a second; 20 on weaker TVs) for the patch over the PS5's box
			try { cover?.feed(img, if(com.metallic.chiaki.translate.TranslatorController.frameWidth > 960) 0L else 45L) } catch(_: Throwable) { }
			val now = SystemClock.elapsedRealtime()
			if(now - frameAt < 120) return // the reader reads at most ~5 a second; never copy more than that
			val plane = img.planes[0]
			val buf = plane.buffer.duplicate()
			buf.rewind()
			val n = buf.remaining()
			synchronized(frameLock)
			{
				val dst = yBytes?.takeIf { it.size >= n } ?: ByteArray(n).also { yBytes = it }
				buf.get(dst, 0, n)
				yW = img.width; yH = img.height; yStride = plane.rowStride; yPix = plane.pixelStride
				frameAt = now
			}
		}
		catch(_: Throwable) { }
		finally { img.close() }
	}

	/** A copy of the newest picture, or null when nothing new arrived since the last call. */
	private fun latestFrame(): Bitmap?
	{
		val dw = 960; val dh = 540
		val src: ByteArray; val sw: Int; val sh: Int; val rs: Int; val ps: Int
		synchronized(frameLock)
		{
			if(frameAt == takenAt) return null
			takenAt = frameAt
			val y = yBytes ?: return null
			src = y.copyOf(); sw = yW; sh = yH; rs = yStride; ps = yPix
		}
		val fullRegion = com.metallic.chiaki.translate.TranslatorPrefs(this).region == com.metallic.chiaki.translate.TranslatorPrefs.Region.FULL
		// weak TVs (tier 960): the averaged 960x540 picture as in 1.9.x; faster ones get the sharp lower half
		if(!fullRegion && sw > 0 && sh > 1 && com.metallic.chiaki.translate.TranslatorController.frameWidth > 960)
		{
			// subtitles only: the lower half of the picture at its own sharpness (up to 1920 wide). Shrinking the
			// whole 1080p picture to 960x540 made gameplay subtitles (thin white letters over the scene) unreadable.
			val ow = minOf(sw, com.metallic.chiaki.translate.TranslatorController.frameWidth)
			val y0 = sh / 2
			val oh = maxOf(1, (sh - y0) * ow / sw)
			val lp = lowerPixels?.takeIf { it.size == ow * oh } ?: IntArray(ow * oh).also { lowerPixels = it }
			val lim2 = src.size
			for(y in 0 until oh)
			{
				val r0 = (y0 + y * (sh - y0) / oh) * rs
				val o0 = y * ow
				for(x in 0 until ow)
				{
					val i = r0 + (x * sw / ow) * ps
					val v = if(i < lim2) src[i].toInt() and 0xff else 0
					lp[o0 + x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
				}
			}
			return Bitmap.createBitmap(lp, ow, oh, Bitmap.Config.ARGB_8888)
		}
		// 2x2 average (like the app mode's scaled copy): thin subtitle letters stay whole
		val px = pixels ?: IntArray(dw * dh).also { pixels = it }
		val lim = src.size
		// only the lower part (where subtitles are; the reader never looks above 55 %) is prepared: less work per
		// read, so weaker TVs keep up like strong ones
		val fromY = if(com.metallic.chiaki.translate.TranslatorPrefs(this).region == com.metallic.chiaki.translate.TranslatorPrefs.Region.FULL) 0 else (dh * 0.5f).toInt()
		for(y in fromY until dh)
		{
			val sy = y * sh / dh
			val r0 = sy * rs
			val r1 = minOf(sh - 1, sy + 1) * rs
			for(x in 0 until dw)
			{
				val sx = x * sw / dw
				val c0 = sx * ps
				val c1 = minOf(sw - 1, sx + 1) * ps
				val i00 = r0 + c0; val i01 = r0 + c1; val i10 = r1 + c0; val i11 = r1 + c1
				val v = ((if(i00 < lim) src[i00].toInt() and 0xff else 0) + (if(i01 < lim) src[i01].toInt() and 0xff else 0) +
					(if(i10 < lim) src[i10].toInt() and 0xff else 0) + (if(i11 < lim) src[i11].toInt() and 0xff else 0)) shr 2
				px[y * dw + x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
			}
		}
		return Bitmap.createBitmap(px, dw, dh, Bitmap.Config.ARGB_8888)
	}

	private fun stopEverything()
	{
		running = false
		try
		{
			val stats = translator?.stats()
			if(stats != null && startedAt > 0)
				com.metallic.chiaki.translate.SessionReport.send(this, "hdmi", (SystemClock.elapsedRealtime() - startedAt) / 1000, stats, null,
					lastProblem ?: stopReason ?: "system:ทีวีปิดโหมด 4K เอง (ไม่ได้สั่งจากแอป)")
		}
		catch(_: Throwable) { }
		startedAt = 0
		try { translator?.stop() } catch(_: Throwable) { }
		translator = null
		try { session?.stop(); session?.dispose() } catch(_: Throwable) { }
		session = null
		connInfo = null
		Session.setAudioMuted(false)
		try { reader?.close() } catch(_: Throwable) { }
		reader = null
		readerThread?.quitSafely(); readerThread = null
		overlay?.let { v -> try { getSystemService(WindowManager::class.java).removeView(v) } catch(_: Throwable) { } }
		overlay = null; ui = null; cover = null
		if(!com.metallic.chiaki.store.Store.IS_PLAY)
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
	}

	override fun onDestroy()
	{
		if(instance === this) instance = null
		try { application.unregisterActivityLifecycleCallbacks(screens) } catch(_: Throwable) { }
		stopEverything()
		super.onDestroy()
	}
}
