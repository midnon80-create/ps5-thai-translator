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

		fun canDrawOverlays(c: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c)

		fun start(c: Context, info: ConnectInfo)
		{
			lastProblem = null
			val i = Intent(c, HdmiModeService::class.java).putExtra(EXTRA_CONNECT_INFO, info)
			// Play build: a plain service (started while the app is on screen); the Thai overlay window keeps it alive
			if(com.metallic.chiaki.store.Store.IS_PLAY) c.startService(i) else ContextCompat.startForegroundService(c, i)
		}

		@Volatile private var instance: HdmiModeService? = null

		/** Stops 4K mode: straight to the running service (asking Android to start it with "stop" can be refused). */
		fun stop(c: Context)
		{
			val s = instance
			if(s != null) { s.main.post { s.shutDown() }; return }
			try { c.startService(Intent(c, HdmiModeService::class.java).setAction(ACTION_STOP)) } catch(_: Throwable) { }
		}
	}

	private val main = Handler(Looper.getMainLooper())
	private var session: Session? = null
	private var reader: ImageReader? = null
	private var readerThread: HandlerThread? = null
	private var overlay: View? = null
	private var ui: TranslatorOverlay? = null
	private var translator: TranslatorController? = null
	private var startedAt = 0L

	// newest picture from the hidden stream, as a small grey image for the text reader
	private val frameLock = Any()
	private var frame: Bitmap? = null
	private var frameAt = 0L
	private var takenAt = 0L
	private var pixels: IntArray? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() { super.onCreate(); instance = this }

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
			// focusable: a controller connected to the TV plays the PS5 through this window (otherwise the TV takes it
			// as a remote: L2/R2 = volume, touchpad = mouse arrow)
			WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT)
		wm.addView(root, lp)
		overlay = root
		setUpController(root)
		val overlayUi = TranslatorOverlay(root, root.findViewById(R.id.hdmiVideo))
		ui = overlayUi
		overlayUi.showStatus("กำลังเชื่อมต่อ PS5 เพื่ออ่านซับ…")

		// 2. hidden stream decoded into an image buffer (nothing on screen)
		val rt = HandlerThread("hdmi-frames").also { it.start() }
		readerThread = rt
		val ir = ImageReader.newInstance(profile.width, profile.height, ImageFormat.YUV_420_888, 3)
		ir.setOnImageAvailableListener({ r -> onImage(r) }, Handler(rt.looper))
		reader = ir

		Session.setAudioMuted(true)
		val s = Session(info, null, false)
		input.controllerStateChangedCallback = { st -> try { session?.setControllerState(st) } catch(_: Throwable) { } }
		s.eventCallback = { ev -> main.post { onEvent(ev) } }
		s.start()
		s.setSurface(ir.surface)
		session = s

		// 3. the same subtitle reader / translator as the normal mode, fed from the hidden stream
		val tr = TranslatorController(this, null, overlayUi) { latestFrame() }
		translator = tr
		tr.start()
		startedAt = SystemClock.elapsedRealtime()
		main.postDelayed(idleCheck, 60_000L)
	}

	private fun onEvent(ev: Event)
	{
		when(ev)
		{
			is ConnectedEvent -> { translator?.setStreamConnected(true); connectedAt = SystemClock.elapsedRealtime()
				ui?.showStatus("✔ ซับไทยพร้อม · จอยที่ต่อทีวีใช้เล่นได้เลย · เลิกเล่น: กดย้อนกลับที่รีโมท 2 ครั้ง")
				main.postDelayed({ ui?.showStatus(null) }, 9000) }
			// PS5 gone / asks for a PIN: everything stops at once (no message left on the TV, no reconnecting);
			// the reason is shown next time the app is opened
			is LoginPinRequestEvent -> { lastProblem = "PS5 ขอรหัส PIN ผู้ใช้ · ปิดรหัสล็อกผู้ใช้ใน PS5 หรือใช้โหมดเล่นผ่านแอป"; shutDown() }
			is QuitEvent -> { lastProblem = "การเชื่อมต่อกับ PS5 หลุด" + (ev.reasonString?.let { " ($it)" } ?: ""); shutDown() }
			else -> {}
		}
	}

	// ---- controller: same handling as app mode (StreamInput: button fixes, triggers, sticks)
	private val input by lazy { com.metallic.chiaki.session.StreamInput(this, com.metallic.chiaki.common.Preferences(this)) }
	private var backAt = 0L

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
				if(now - backAt < 2500L) { lastProblem = null; shutDown() }
				else { backAt = now; ui?.showStatus("กดย้อนกลับอีกครั้งเพื่อเลิกเล่น"); main.postDelayed({ ui?.showStatus(null) }, 2500) }
			}
			true
		}
		root.setOnGenericMotionListener { _, e -> input.onGenericMotionEvent(e) || true }
		// mouse / touchpad pointer over the window: swallowed (no arrow, nothing clicked behind)
		root.setOnHoverListener { _, _ -> true }
		root.post { root.requestFocus() }
	}

	/** Ends 4K mode from inside the service (works in the background too). */
	private fun shutDown()
	{
		try { input.releaseAll() } catch(_: Throwable) { }
		main.removeCallbacks(idleCheck)
		stopEverything()
		stopSelf()
	}

	/** The app was closed from the recent-apps list: 4K mode ends with it. */
	override fun onTaskRemoved(rootIntent: Intent?)
	{
		lastProblem = null
		shutDown()
		super.onTaskRemoved(rootIntent)
	}

	// no Thai line for a long time (PS5 left on its home screen, TV switched to something else): stop by itself
	private var connectedAt = 0L
	private var noSubTipShown = false

	private val idleCheck = object : Runnable { override fun run() {
		val tr = translator ?: return
		// connected 2 minutes and not one Thai line yet: almost always the game's own subtitles are off
		if(!noSubTipShown && connectedAt > 0 && tr.lastShownAt == 0L && SystemClock.elapsedRealtime() - connectedAt > 120_000L)
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
	private fun onImage(r: ImageReader)
	{
		val img = try { r.acquireLatestImage() } catch(_: Throwable) { null } ?: return
		try
		{
			val now = SystemClock.elapsedRealtime()
			if(now - frameAt < 200) return // at most 5 a second: the reader takes 4
			val plane = img.planes[0]
			val buf = plane.buffer
			val rs = plane.rowStride; val ps = plane.pixelStride
			val sw = img.width; val sh = img.height
			val dw = 960; val dh = 540
			val px = pixels ?: IntArray(dw * dh).also { pixels = it }
			val lim = buf.limit()
			for(y in 0 until dh)
			{
				val r0 = (y * sh / dh) * rs
				val r1 = minOf(sh - 1, y * sh / dh + 1) * rs
				for(x in 0 until dw)
				{
					val c0 = (x * sw / dw) * ps
					val c1 = minOf(sw - 1, x * sw / dw + 1) * ps
					fun at(i: Int) = if(i < lim) buf.get(i).toInt() and 0xff else 0
					val v = (at(r0 + c0) + at(r0 + c1) + at(r1 + c0) + at(r1 + c1)) shr 2
					px[y * dw + x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
				}
			}
			synchronized(frameLock)
			{
				val bmp = frame ?: Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888).also { frame = it }
				bmp.setPixels(px, 0, dw, 0, 0, dw, dh)
				frameAt = now
			}
		}
		catch(_: Throwable) { }
		finally { img.close() }
	}

	/** A copy of the newest picture, or null when nothing new arrived since the last call. */
	private fun latestFrame(): Bitmap?
	{
		synchronized(frameLock)
		{
			val f = frame ?: return null
			if(frameAt == takenAt) return null
			takenAt = frameAt
			return f.copy(Bitmap.Config.ARGB_8888, false)
		}
	}

	private fun stopEverything()
	{
		running = false
		try
		{
			val stats = translator?.stats()
			if(stats != null && startedAt > 0)
				com.metallic.chiaki.translate.SessionReport.send(this, "hdmi", (SystemClock.elapsedRealtime() - startedAt) / 1000, stats, null, lastProblem)
		}
		catch(_: Throwable) { }
		startedAt = 0
		try { translator?.stop() } catch(_: Throwable) { }
		translator = null
		try { session?.stop(); session?.dispose() } catch(_: Throwable) { }
		session = null
		Session.setAudioMuted(false)
		try { reader?.close() } catch(_: Throwable) { }
		reader = null
		readerThread?.quitSafely(); readerThread = null
		overlay?.let { v -> try { getSystemService(WindowManager::class.java).removeView(v) } catch(_: Throwable) { } }
		overlay = null; ui = null
		if(!com.metallic.chiaki.store.Store.IS_PLAY)
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
	}

	override fun onDestroy()
	{
		if(instance === this) instance = null
		stopEverything()
		super.onDestroy()
	}
}
