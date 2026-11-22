package com.metallic.chiaki.tv

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.main.MainViewModel
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.session.PadConfig
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.store.Store
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.translate.CloudTranslator
import com.metallic.chiaki.translate.Diag
import com.metallic.chiaki.translate.TranslatorPrefs
import com.metallic.chiaki.translate.TranslatorTestActivity
import com.metallic.chiaki.tv.Ui.MATCH
import com.metallic.chiaki.tv.Ui.WRAP
import com.metallic.chiaki.tv.Ui.W
import com.metallic.chiaki.tv.Ui.llp
import com.metallic.chiaki.tv.Ui.lp
import com.metallic.chiaki.tv.Ui.text

/**
 * The TV interface: first-launch welcome (fade in once per install, then slides that move right-to-left),
 * packages, a device check, and the main screen with five tabs (เล่น / ตั้งค่าซับ / จอย / แพ็กเกจ / ช่วยเหลือ).
 * Everything works with the TV remote, a game controller or touch.
 */
class TvActivity : AppCompatActivity()
{
	private lateinit var stage: Stage
	private val root get() = stage.canvas
	private var page: View? = null
	private lateinit var vm: MainViewModel
	private val sp by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
	private val main = Handler(Looper.getMainLooper())

	/** Takes key presses first (welcome slides, remap wizard). Returns true when it used the key. */
	private var keyCatcher: ((KeyEvent) -> Boolean)? = null
	private var padView: PadView? = null
	private var padCard: View? = null
	private var tab = -1
	private var storeStarted = false
	private var firstResume = true
	private var refresher: Runnable? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		stage = Stage(this)
		setContentView(stage)
		hideCursor(window.decorView)
		WindowCompat.setDecorFitsSystemWindows(window, false)
		WindowInsetsControllerCompat(window, stage).let {
			it.hide(WindowInsetsCompat.Type.systemBars())
			it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}
		vm = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this)) })[MainViewModel::class.java]
		vm.discoveryManager.active = true
		// picture settings that suit this TV (unless the player picked them)
		// (the old "smooth" 720p choice is gone: it becomes automatic, which is always 1080p now)
		if(DeviceCaps.quality(this) != "sharp") try { DeviceCaps.apply(this, "auto") } catch(_: Throwable) {}
		// clear button fixes from older versions once: the controller layout is now detected automatically
		if(PadConfig.migrateOnce(this)) toast("อัปเดตการตั้งค่าจอยใหม่แล้ว ✔ ปุ่มจอย PS4/PS5 ถูกต้องอัตโนมัติ")

		if(!sp.getBoolean(KEY_ONBOARDED, false)) showSplash()
		else showTab(0, Trans.FADE)
	}

	override fun onStart()
	{
		super.onStart()
		try { vm.discoveryManager.resume() } catch(_: Throwable) {}
	}

	override fun onStop()
	{
		super.onStop()
		vm.discoveryManager.pause()
	}

	override fun onResume()
	{
		super.onResume()
		if(!firstResume && tab >= 0) showTab(tab, Trans.NONE) // fresh status after a game / settings
		firstResume = false
		// just paired a PS5: go straight on to the pre-game checklist for it (the next step)
		sp.getString(KEY_JUST_PAIRED, null)?.let { addr ->
			sp.edit().remove(KEY_JUST_PAIRED).apply()
			main.postDelayed({
				val h = vm.displayHosts.value?.firstOrNull { it.host == addr && it.registeredHost != null }
					?: vm.displayHosts.value?.firstOrNull { it.registeredHost != null }
				if(h != null) hostTriggered(h) else toast("จับคู่ PS5 สำเร็จ ✔ กดการ์ด PS5 เพื่อเล่น")
			}, 700)
		}
	}

	private fun startStoreOnce()
	{
		if(storeStarted) return
		storeStarted = true
		try { Store.onAppStart(this) } catch(e: Throwable) { android.util.Log.e("Eyesmaxxing", "store start failed", e) }
	}

	/** The PS5 controller's touchpad can act as a mouse on some TVs: never show its arrow over the app. */
	private fun hideCursor(v: View)
	{
		if(android.os.Build.VERSION.SDK_INT >= 24) try { v.pointerIcon = android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL) } catch(_: Throwable) { }
	}

	override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean
	{
		padView?.let { if(PadConfig.fromPad(ev)) it.motion(ev) }
		// mouse / touchpad pointer moves: swallowed, so no cursor appears and nothing gets clicked by accident
		if(ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && !ev.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) return true
		return super.dispatchGenericMotionEvent(ev)
	}

	// ------------------------------------------------------------------ transitions

	private enum class Trans { FADE, LEFT, RIGHT, NONE }

	/** Shows a full-screen page: FADE (first launch), LEFT (next: comes in from the right), RIGHT (back). */
	private fun go(v: View, t: Trans, focus: View? = null)
	{
		val old = page
		page = v
		stopRefresh()
		root.addView(v, FrameLayout.LayoutParams(1920, 1080))
		when(t)
		{
			Trans.FADE -> { v.alpha = 0f; v.scaleX = 0.97f; v.scaleY = 0.97f
				v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1100).setInterpolator(Ui.ease()).start() }
			Trans.LEFT, Trans.RIGHT -> { val d = if(t == Trans.LEFT) 1 else -1
				v.alpha = 0f; v.translationX = 150f * d
				v.animate().alpha(1f).translationX(0f).setDuration(460).setInterpolator(Ui.ease()).start() }
			Trans.NONE -> {}
		}
		if(old != null)
		{
			if(t == Trans.NONE) root.removeView(old)
			else old.animate().alpha(0f).translationX(if(t == Trans.RIGHT) 110f else -110f).setDuration(300)
				.setInterpolator(Ui.ease()).withEndAction { root.removeView(old) }.start()
		}
		(focus ?: firstFocusable(v))?.let { f -> v.post { f.requestFocus() } }
	}

	private fun firstFocusable(v: View): View? = v.getFocusables(View.FOCUS_FORWARD).firstOrNull()

	private fun toast(msg: String)
	{
		val t = text(this, msg, 26f, Ui.TEXT, W.MEDIUM, Gravity.CENTER).apply {
			background = Ui.round(0xE6120D1F.toInt(), 40f, 2, 0x99A78BFA.toInt())
			setPadding(36, 14, 36, 14)
		}
		root.addView(t, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 40 })
		t.alpha = 0f; t.translationY = -20f
		t.animate().alpha(1f).translationY(0f).setDuration(250).start()
		main.postDelayed({ t.animate().alpha(0f).setDuration(300).withEndAction { root.removeView(t) }.start() }, 3200)
	}

	// ------------------------------------------------------------------ welcome (first launch only)

	private fun showSplash()
	{
		val v = FrameLayout(this)
		val c = Ui.col(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
		val logo = Ui.image(this, R.drawable.eyes_logo, android.widget.ImageView.ScaleType.FIT_CENTER)
		Ui.clipRound(logo, 70f)
		c.addView(logo, llp(330, 330))
		c.addView(text(this, "Eyesmaxxing", 84f, Ui.TEXT, W.BOLD, Gravity.CENTER), llp(WRAP, WRAP, t = 34))
		c.addView(text(this, "ซับไทยสด ๆ บนเกม PS5 · ผ่านทีวีของคุณ", 32f, Ui.MUTED, gravity = Gravity.CENTER), llp(WRAP, WRAP, t = 10))
		v.addView(c, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { topMargin = -40 })
		v.addView(Ui.hint(this, listOf("✕" to "กดปุ่มใดก็ได้เพื่อเริ่ม")), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 42 })
		v.setOnClickListener { showFeature(0, Trans.LEFT) }
		go(v, Trans.FADE)
		keyCatcher = { e -> if(e.action == KeyEvent.ACTION_UP) { showFeature(0, Trans.LEFT) }; true }
	}

	private val features = listOf(
		Triple(R.drawable.gta_shot1, arrayOf("ซับไทยขึ้น", "ทันที", "\nเหนือซับอังกฤษ"),
			"เล่นเกมผ่านทีวีตามปกติ แอปอ่านซับอังกฤษของเกมแล้วขึ้นคำแปลไทยให้เอง ไม่บังภาพ ไม่ต้องกดอะไรเพิ่ม"),
		Triple(R.drawable.gta_shot2, arrayOf("แปลแบบ", "วัยรุ่นไทย", "\nได้อารมณ์ ไม่แข็งทื่อ"),
			"AI เลือกคำให้เข้ากับฉาก ด่าก็ด่าตามต้นฉบับ สุภาพก็สุภาพ อ่านแล้วอินเหมือนดูหนังพากย์ไทย"),
		Triple(R.drawable.gta_shot3, arrayOf("ลื่น เบาเครื่อง\nจอยใช้ได้", "ปกติ", ""),
			"อ่านเฉพาะแถบซับด้านล่างจอ ใช้เน็ตและสเปกทีวีน้อย ภาพคมชัดปรับให้เหมาะกับทีวีแต่ละเครื่องอัตโนมัติ"))

	private fun showFeature(i: Int, t: Trans)
	{
		val (img, title, body) = features[i]
		val v = FrameLayout(this)
		v.addView(Ui.brand(this), lp(WRAP, WRAP, 64, 44))
		val shot = Ui.topImage(this, img, 1f)
		val shotBox = FrameLayout(this).apply { background = Ui.round(0, 28f, 2, Ui.LINE); elevation = 30f }
		shotBox.addView(shot, FrameLayout.LayoutParams(MATCH, MATCH))
		Ui.clipRound(shotBox, 28f)
		v.addView(shotBox, lp(1080, 690, 90, 185))
		(shot as android.widget.ImageView).scaleType = android.widget.ImageView.ScaleType.CENTER_CROP

		val c = Ui.col(this)
		c.addView(text(this, "ฟีเจอร์ ${i + 1}/3", 24f, Ui.GLOW).apply {
			background = Ui.round(0, 30f, 2, 0x80C084FC.toInt()); setPadding(20, 6, 20, 6) }, llp(WRAP, WRAP))
		val h = android.text.SpannableStringBuilder().apply {
			append(title[0])
			val s = length; append(title[1]); setSpan(android.text.style.ForegroundColorSpan(Ui.VIOLET2), s, length, 0)
			append(title[2])
		}
		c.addView(text(this, h, 64f, Ui.TEXT, W.BOLD).apply { setLineSpacing(0f, 1.12f) }, llp(WRAP, WRAP, t = 26))
		c.addView(text(this, body, 30f, Ui.MUTED).apply { setLineSpacing(0f, 1.3f) }, llp(MATCH, WRAP, t = 22))
		c.addView(note(), llp(MATCH, WRAP, t = 40))
		v.addView(c, lp(590, WRAP, 1240, 240))

		v.addView(Ui.dots(this, 4, i), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 110 })
		val hints = if(i == 0) listOf("▶" to "ถัดไป", "○" to "ข้าม") else listOf("◀" to "ก่อนหน้า", "▶" to "ถัดไป", "○" to "ข้าม")
		v.addView(Ui.hint(this, hints), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 42 })
		v.setOnClickListener { if(i < 2) showFeature(i + 1, Trans.LEFT) else showFree(Trans.LEFT) }
		go(v, t)
		keyCatcher = { e ->
			if(e.action == KeyEvent.ACTION_UP) when(e.keyCode)
			{
				KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
					if(i < 2) showFeature(i + 1, Trans.LEFT) else showFree(Trans.LEFT)
				KeyEvent.KEYCODE_DPAD_LEFT -> if(i > 0) showFeature(i - 1, Trans.RIGHT)
				KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> showFree(Trans.LEFT)
			}
			true
		}
	}

	/** Bold "GTA 6 only for now" note shown on every slide. */
	private fun note(): View
	{
		val n = Ui.col(this).apply {
			background = Ui.gradient(intArrayOf(0x388B5CF6, 0x148B5CF6), 20f, stroke = 2, strokeColor = 0x99A78BFA.toInt())
			setPadding(28, 22, 28, 22)
		}
		n.addView(text(this, "⚠ ตอนนี้แปลได้เฉพาะ GTA 6 เท่านั้น", 31f, Ui.TEXT, W.EXTRABOLD))
		n.addView(text(this, "เกมอื่นจะทยอยเพิ่มเข้ามาเรื่อย ๆ ในอนาคต", 24f, Ui.VIOLET2), llp(WRAP, WRAP, t = 4))
		return n
	}

	private fun pill(label: String, onClick: () -> Unit): Card = Card(this, 60f).apply {
		val t = text(context, label, 34f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER)
		addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
		grow = 1.07f
		setOnClickListener { onClick() }
	}.also { card ->
		// purple button look (ring on focus comes from Card)
		card.setOnFocusChangeListener { _, f ->
			card.background = Ui.gradient(intArrayOf(Ui.VIOLET, Ui.PURPLE), 60f, stroke = if(f) 5 else 0, strokeColor = Ui.RING)
			card.animate().scaleX(if(f) 1.07f else 1f).scaleY(if(f) 1.07f else 1f).setDuration(200).setInterpolator(Ui.ease()).start()
			Ui.glow(card, f)
		}
		card.background = Ui.gradient(intArrayOf(Ui.VIOLET, Ui.PURPLE), 60f)
	}

	/** "เล่นฟรี": the app costs nothing. */
	private fun showFree(t: Trans)
	{
		keyCatcher = null
		val v = FrameLayout(this)
		v.addView(Ui.brand(this), lp(WRAP, WRAP, 64, 44))
		val c = Ui.col(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
		c.addView(text(this, "ไม่มีค่าใช้จ่าย", 26f, Ui.VIOLET2).apply {
			background = Ui.round(Ui.PANEL2, 40f, 2, Ui.LINE); setPadding(26, 10, 26, 10) })
		c.addView(text(this, "เล่นฟรี", 170f, Ui.TEXT, W.EXTRABOLD, Gravity.CENTER).apply {
			paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, 190f, 0xFFFFFFFF.toInt(), 0xFFC4B5FD.toInt(), android.graphics.Shader.TileMode.CLAMP)
			setShadowLayer(60f, 0f, 0f, 0x668B5CF6)
		}, llp(WRAP, WRAP, t = 10))
		c.addView(text(this, "ซับไทยครบทุกฟีเจอร์", 56f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER), llp(WRAP, WRAP, t = 6))
		c.addView(text(this, "ไม่ต้องสมัคร · ไม่ต้องใส่รหัส · ไม่ต้องผูกบัตร", 30f, Ui.MUTED, gravity = Gravity.CENTER), llp(WRAP, WRAP, t = 18))
		val cta = pill("เริ่มเลย") { showDeviceCheck(Trans.LEFT) }
		c.addView(cta, llp(440, 100, t = 40))
		v.addView(c, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { topMargin = -30 })
		v.addView(Ui.dots(this, 4, 3), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 110 })
		v.addView(Ui.hint(this, listOf("✕" to "เริ่ม", "◀" to "ก่อนหน้า")), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 42 })
		go(v, t, cta)
		keyCatcher = { e ->
			if(e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B)
			{ if(e.action == KeyEvent.ACTION_UP) showFeature(2, Trans.RIGHT); true } else false
		}
	}

	/** Small "เล่นฟรี" banner on the main screen. */
	private fun freeBadge(): View = text(this, "เล่นฟรี", 26f, Ui.TEXT, W.BOLD, Gravity.CENTER).apply {
		background = Ui.gradient(intArrayOf(Ui.VIOLET, Ui.GLOW), 30f); setPadding(28, 6, 28, 8) }

	// ------------------------------------------------------------------ game picker

	/** GTA VI cover drawn by the app (game screenshot + our own lettering, not the official logo). */
	private fun gtaCover(): View
	{
		val f = FrameLayout(this)
		val img = Ui.topImage(this, R.drawable.gta_shot2, 1f)
		(img as? android.widget.ImageView)?.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
		f.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
		f.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
			intArrayOf(0x22000000, 0x55100018, 0xF0100018.toInt())) }, FrameLayout.LayoutParams(MATCH, MATCH))
		val c = Ui.col(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
		c.addView(text(this, "GTA", 140f, 0xFFFFFFFF.toInt(), W.EXTRABOLD, Gravity.CENTER).apply {
			setShadowLayer(40f, 0f, 0f, 0xCCFF4FA3.toInt()); includeFontPadding = false })
		c.addView(text(this, "VI", 180f, 0xFFFFFFFF.toInt(), W.EXTRABOLD, Gravity.CENTER).apply {
			includeFontPadding = false
			paint.shader = android.graphics.LinearGradient(0f, 20f, 0f, 200f, 0xFFFFC46B.toInt(), 0xFFFF4FA3.toInt(), android.graphics.Shader.TileMode.CLAMP)
			setShadowLayer(50f, 0f, 0f, 0x99FF4FA3.toInt()) })
		f.addView(c, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { topMargin = -40 })
		return f
	}

	private fun gameCard(onPick: () -> Unit): Card
	{
		val card = Card(this, 30f, Ui.PANEL) { true }
		card.grow = 1.05f
		val cover = gtaCover()
		Ui.clipRound(cover, 28f)
		card.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(4, 4, 4, 4) })
		val c = Ui.col(this)
		c.addView(text(this, "Grand Theft Auto VI", 34f, Ui.TEXT, W.BOLD))
		c.addView(text(this, "ซับไทยพร้อม ✔", 24f, Ui.OK, W.MEDIUM), llp(WRAP, WRAP, t = 2))
		card.addView(c, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(34, 0, 34, 30) })
		card.setOnClickListener { onPick() }
		return card
	}

	private fun gamesContent(onboarding: Boolean): View
	{
		val v = FrameLayout(this)
		val top = if(onboarding) 150 else 140
		v.addView(text(this, "เลือกเกม", 46f, Ui.TEXT, W.BOLD), lp(WRAP, WRAP, 90, top))
		v.addView(text(this, "เกมแรกที่แปลไทยได้ · เกมอื่นจะเพิ่มเข้ามาเรื่อย ๆ", 27f, Ui.MUTED), lp(WRAP, WRAP, 90, top + 66))
		val card = gameCard {
			sp.edit().putString(KEY_GAME, "gta6").apply()
			if(onboarding) { sp.edit().putBoolean(KEY_ONBOARDED, true).apply(); showTab(0, Trans.LEFT) }
			else { toast("เลือก GTA VI แล้ว ✔"); showTab(0, Trans.RIGHT) }
		}
		v.addView(card, lp(480, 640, 90, top + 140))
		// the next games: "coming soon", not selectable
		for(k in 0 until 2)
		{
			val soon = FrameLayout(this).apply { background = Ui.round(Ui.PANEL, 30f, 2, Ui.LINE); alpha = 0.5f }
			soon.addView(text(this, "เกมถัดไป\nเร็ว ๆ นี้", 32f, Ui.MUTED, W.SEMIBOLD, Gravity.CENTER), FrameLayout.LayoutParams(MATCH, MATCH))
			v.addView(soon, lp(480, 640, 90 + (k + 1) * 530, top + 140))
		}
		return v
	}

	private fun showGamePicker(t: Trans)
	{
		keyCatcher = null
		val v = gamesContent(true) as FrameLayout
		v.addView(Ui.brand(this), lp(WRAP, WRAP, 64, 44))
		v.addView(Ui.hint(this, listOf("✕" to "เลือก")), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 42 })
		go(v, t)
		keyCatcher = { e ->
			if(e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B)
			{ if(e.action == KeyEvent.ACTION_UP) showDeviceCheck(Trans.RIGHT); true } else false
		}
	}

	// ------------------------------------------------------------------ device check (first launch)

	private fun showDeviceCheck(t: Trans)
	{
		keyCatcher = null
		val caps = DeviceCaps(this)
		val v = FrameLayout(this)
		v.addView(Ui.brand(this), lp(WRAP, WRAP, 64, 44))
		val c = Ui.col(this)
		c.addView(text(this, "ตรวจความพร้อมทีวีของคุณ", 54f, Ui.TEXT, W.BOLD))
		c.addView(text(this, "แอปตรวจให้อัตโนมัติ และตั้งค่าภาพให้เหมาะกับเครื่องนี้แล้ว", 27f, Ui.MUTED), llp(WRAP, WRAP, t = 6))
		val grid = Ui.col(this).apply { background = Ui.round(Ui.PANEL, 26f, 2, Ui.LINE); setPadding(34, 26, 34, 26) }
		val pads = Diag.gamepads()
		val items = listOf(
			Triple(caps.sdk >= 26, "Android ${caps.android} · ชิป ${if(caps.is64) "64" else "32"} บิต",
				if(caps.sdk >= 28 && caps.is64) "รองรับเต็มที่" else if(caps.sdk >= 24) "ใช้ได้ · ทีวีรุ่นนี้อาจช้ากว่ารุ่นใหม่" else "เก่าเกินไป"),
			Triple(caps.hevc1080, if(caps.hevc1080) "ถอดรหัสภาพ HEVC ได้" else "ไม่มีตัวถอดรหัส HEVC",
				"ตั้งภาพ ${caps.autoTitle(this)} ให้อัตโนมัติ"),
			Triple(caps.wired || caps.wifiGhz == 5.0, if(caps.wired) "ต่อสาย LAN" else if(caps.wifiGhz == 5.0) "Wi-Fi 5GHz" else if(caps.wifiGhz == 2.4) "Wi-Fi 2.4GHz" else "อินเทอร์เน็ต",
				if(caps.wired || caps.wifiGhz == 5.0) "เหมาะกับภาพคมชัดสูง" else "แนะนำ Wi-Fi 5GHz หรือสาย LAN เพื่อภาพลื่น"),
			Triple(pads.isNotEmpty(), if(pads.isNotEmpty()) "จอยต่อกับทีวีแล้ว" else "ยังไม่พบจอยที่ต่อกับทีวี",
				if(pads.isNotEmpty()) pads.first() else "กด \"จับคู่จอย\" ด้านล่าง ทำครั้งเดียว"))
		for(r in 0 until 2)
		{
			val row = Ui.row(this)
			for(k in 0 until 2)
			{
				val (ok, title, sub) = items[r * 2 + k]
				val cell = Ui.row(this)
				cell.addView(text(this, if(ok) "✔" else "!", 34f, if(ok) Ui.OK else Ui.WARN, W.BOLD, Gravity.CENTER), llp(46, WRAP))
				val tc = Ui.col(this)
				tc.addView(text(this, title, 28f, Ui.TEXT))
				tc.addView(text(this, sub, 22f, Ui.MUTED), llp(WRAP, WRAP, t = 2))
				cell.addView(tc, llp(WRAP, WRAP, l = 16))
				row.addView(cell, llp(0, WRAP, 1f))
			}
			grid.addView(row, llp(MATCH, WRAP, t = if(r == 0) 0 else 22))
		}
		c.addView(grid, llp(MATCH, WRAP, t = 30))

		val padRow = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL; background = Ui.round(Ui.PANEL, 26f, 2, Ui.LINE); setPadding(34, 26, 34, 26) }
		val pt = Ui.col(this)
		pt.addView(text(this, "จอยต้องต่อกับทีวี (ไม่ใช่กับ PS5)", 32f, Ui.TEXT, W.SEMIBOLD))
		pt.addView(text(this, "Sony กำหนดให้การเล่นผ่านทีวีรับปุ่มจากจอยที่ต่อกับทีวีเท่านั้น · เสียบสาย USB เข้าทีวีก็ได้ หรือกดจับคู่บลูทูธ (ที่จอยกด Create + PS ค้างจนไฟกะพริบ)",
			24f, Ui.MUTED).apply { setLineSpacing(0f, 1.3f) }, llp(MATCH, WRAP, t = 6))
		padRow.addView(pt, llp(0, WRAP, 1f))
		val pair = Card(this, 50f).apply {
			addView(text(context, "จับคู่จอย", 28f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER), FrameLayout.LayoutParams(MATCH, MATCH))
			setOnClickListener { openBluetooth() } }
		padRow.addView(pair, llp(260, 84, l = 30))
		c.addView(padRow, llp(MATCH, WRAP, t = 26))

		val info = text(this, "สิทธิ์ที่แอปใช้: อินเทอร์เน็ต (แปลภาษา) และเครือข่ายในบ้าน (หา PS5) · ไม่ต้องกดอนุญาตอะไรเพิ่ม · แอปไม่เก็บภาพหรือเสียงไว้ในเครื่อง",
			23f, Ui.MUTED).apply { setLineSpacing(0f, 1.3f) }
		c.addView(info, llp(MATCH, WRAP, t = 22))
		v.addView(c, lp(1740, WRAP, 90, 150))

		val start = pill("ถัดไป ▶") { showGamePicker(Trans.LEFT) }
		v.addView(start, lp(400, 96, 760, 930))
		go(v, t, start)
		keyCatcher = { e ->
			if(e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B)
			{ if(e.action == KeyEvent.ACTION_UP) showFree(Trans.RIGHT); true } else false
		}
	}

	private fun openBluetooth()
	{
		for(a in listOf(Settings.ACTION_BLUETOOTH_SETTINGS, "android.settings.ADD_ACCESSORY_SETTINGS", Settings.ACTION_SETTINGS))
			try { startActivity(Intent(a)); return } catch(_: Throwable) {}
		toast("เปิดหน้าตั้งค่าบลูทูธไม่ได้ · เข้าที่ ตั้งค่าทีวี › รีโมทและอุปกรณ์เสริม")
	}

	// ------------------------------------------------------------------ main screen with tabs

	private val tabs = listOf("เล่น", "ตั้งค่าซับ", "จอย", "เลือกเกม", "ช่วยเหลือ")

	private fun showTab(i: Int, t: Trans)
	{
		keyCatcher = null
		padView = null; padCard = null
		val v = FrameLayout(this)
		v.addView(Ui.brand(this), lp(WRAP, WRAP, 64, 44))
		val navCards = ArrayList<Card>()
		val nav = Ui.row(this)
		tabs.forEachIndexed { k, name ->
			val card = Card(this, 20f) { k == i }
			card.grow = 1.04f
			card.addView(text(this, name, 27f, if(k == i) Ui.TEXT else Ui.MUTED, if(k == i) W.MEDIUM else W.REGULAR, Gravity.CENTER),
				FrameLayout.LayoutParams(MATCH, MATCH))
			card.setOnClickListener { if(k != tab) showTab(k, if(k > tab) Trans.LEFT else Trans.RIGHT) }
			nav.addView(card, llp(0, 74, 1f, l = if(k == 0) 0 else 22))
			navCards += card
		}
		v.addView(nav, lp(1740, 74, 90, 966))
		val content = when(i)
		{
			0 -> homeContent()
			1 -> settingsContent()
			2 -> padContent()
			3 -> gamesContent(false)
			else -> helpContent()
		}
		v.addView(content, 0, FrameLayout.LayoutParams(1920, 1080))
		v.addView(freeBadge(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = 50; rightMargin = 90 })
		// from the content, "down" always reaches the tab bar; from the tab bar "up" goes back into the content
		navCards.forEach { it.nextFocusUpId = View.NO_ID }
		tab = i
		go(v, t, firstFocusable(content) ?: navCards[i])
		if(i == 0) startStoreOnce()
		if(sp.getBoolean(KEY_ONBOARDED, false).not()) sp.edit().putBoolean(KEY_ONBOARDED, true).apply()
	}

	// ---- tab 0: เล่น
	private fun homeContent(): View
	{
		val v = FrameLayout(this)
		val prefs = TranslatorPrefs(this)
		vm.displayHosts.removeObservers(this) // the page is rebuilt on every return: one observer at a time
		// big ซับไทย on/off
		val toggle = Card(this, 40f, Ui.PANEL)
		toggle.grow = 1.03f
		val img = Ui.topImage(this, R.drawable.gta_shot1, 0.66f)
		val imgBox = FrameLayout(this); imgBox.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
		imgBox.alpha = 0.55f
		Ui.clipRound(imgBox, 38f)
		toggle.addView(imgBox, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(4, 4, 4, 4) })
		val shade = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0xEB07050C.toInt())).apply { cornerRadius = 38f } }
		toggle.addView(shade, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(4, 4, 4, 4) })
		val sw = FrameLayout(this)
		val knob = View(this).apply { background = Ui.round(0xFFFFFFFF.toInt(), 38f) }
		val swText = text(this, "", 34f, Ui.TEXT, W.BOLD, Gravity.CENTER_VERTICAL)
		sw.addView(swText, FrameLayout.LayoutParams(WRAP, MATCH).apply { leftMargin = 30 })
		sw.addView(knob, FrameLayout.LayoutParams(76, 76).apply { topMargin = 10; leftMargin = 10 })
		toggle.addView(sw, FrameLayout.LayoutParams(190, 96, Gravity.TOP or Gravity.END).apply { setMargins(0, 50, 50, 0) })
		val tc = Ui.col(this)
		val big = text(this, "ซับไทย", 92f, Ui.TEXT, W.EXTRABOLD)
		val sub = text(this, "", 30f, 0xFFE7DEFD.toInt())
		tc.addView(big); tc.addView(sub, llp(WRAP, WRAP, t = 12))
		toggle.addView(tc, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(56, 0, 56, 56) })
		fun paint(on: Boolean, animate: Boolean)
		{
			sw.background = if(on) Ui.gradient(intArrayOf(Ui.VIOLET, Ui.GLOW), 48f) else Ui.round(0xFF2C2147.toInt(), 48f)
			swText.text = if(on) "เปิด" else "ปิด"
			(swText.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = if(on) 30 else 104 }; swText.requestLayout()
			val to = if(on) 104f else 0f
			if(animate) animateFloat(knob.translationX, to, 260) { knob.translationX = it } else knob.translationX = to
			sub.text = if(on) "เปิดอยู่ · ซับไทยจะขึ้นเองเมื่อเข้าเกม" else "ปิดอยู่ · กดเพื่อเปิดซับไทย"
		}
		paint(prefs.enabled, false)
		toggle.setOnClickListener {
			val on = !TranslatorPrefs(this).enabled
			sp.edit().putBoolean("tr_enabled", on).apply()
			paint(on, true)
		}
		v.addView(toggle, lp(939, 790, 90, 150))

		// right column: PS5, status, note
		val col = Ui.col(this)
		val hostsBox = Ui.col(this)
		col.addView(hostsBox, llp(MATCH, WRAP))
		val status = Ui.col(this)
		val tiles = HashMap<String, TextView>()
		val subs = HashMap<String, TextView>()
		for(r in 0 until 2)
		{
			val row = Ui.row(this)
			for(k in 0 until 2)
			{
				val key = listOf("pad", "ai", "pic", "band")[r * 2 + k]
				val cell = Ui.col(this).apply { background = Ui.round(Ui.PANEL, 22f, 2, Ui.LINE); setPadding(26, 20, 26, 20) }
				val t1 = text(this, "", 26f, Ui.TEXT); val t2 = text(this, "", 21f, Ui.MUTED)
				cell.addView(t1); cell.addView(t2, llp(WRAP, WRAP, t = 2))
				tiles[key] = t1; subs[key] = t2
				row.addView(cell, llp(0, 120, 1f, l = if(k == 0) 0 else 20))
			}
			status.addView(row, llp(MATCH, WRAP, t = 20))
		}
		col.addView(status, llp(MATCH, WRAP, t = 6))
		// play mode: 4K over HDMI (TV with the PS5 plugged in) or through the app (any TV / box / dongle)
		val hdmiAvailable = true
		fun isHdmi() = hdmiAvailable && sp.getString(KEY_PLAY_MODE, "stream") == "hdmi"
		val modeRow = Ui.row(this)
		val modeCards = ArrayList<Card>()
		fun modeCard(title: String, sub: String, hdmiCard: Boolean): Card
		{
			val card = Card(this, 22f, Ui.PANEL) { isHdmi() == hdmiCard }
			card.grow = 1.03f
			val c = Ui.col(this).apply { setPadding(24, 14, 24, 14); gravity = Gravity.CENTER_VERTICAL }
			c.addView(text(this, title, 27f, Ui.TEXT, W.SEMIBOLD))
			c.addView(text(this, sub, 20f, Ui.MUTED).apply { maxLines = 2 }, llp(WRAP, WRAP, t = 2))
			card.addView(c, FrameLayout.LayoutParams(MATCH, MATCH))
			card.setOnClickListener {
				sp.edit().putString(KEY_PLAY_MODE, if(hdmiCard) "hdmi" else "stream").apply()
				modeCards.forEach { it.refresh() }
				toast(if(hdmiCard) "โหมด 4K: เสียบ PS5 เข้าทีวีเครื่องนี้ แล้วกดการ์ด PS5" else "โหมดเล่นผ่านแอป: ต่อจอยกับทีวี แล้วกดการ์ด PS5")
			}
			modeCards.add(card)
			return card
		}
		modeRow.addView(modeCard("โหมด 4K · HDMI", "ภาพ 4K ไม่ดรอป · จอยต่อ PS5", true), llp(0, 112, 1f))
		modeRow.addView(modeCard("โหมดเล่นผ่านแอป", "ทีวีทุกรุ่น · กล่อง · ดองเกิล", false), llp(0, 112, 1f, l = 20))
		col.addView(modeRow, llp(MATCH, WRAP, t = 22))
		// "เลิกเล่น": disconnect from the PS5 (HDMI mode included) and close the app, no auto-reconnect
		val quit = Card(this, 22f, Ui.PANEL); quit.grow = 1.03f
		val qr = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(30, 0, 30, 0) }
		qr.addView(text(this, "⏻", 40f, Ui.WARN, W.BOLD, Gravity.CENTER), llp(60, WRAP))
		val qc = Ui.col(this)
		qc.addView(text(this, "เลิกเล่น", 28f, Ui.TEXT, W.SEMIBOLD))
		qc.addView(text(this, "ตัดการเชื่อมต่อ PS5 และออกจากแอป", 20f, Ui.MUTED), llp(WRAP, WRAP, t = 2))
		qr.addView(qc, llp(0, WRAP, 1f, l = 14))
		quit.addView(qr, FrameLayout.LayoutParams(MATCH, MATCH))
		quit.setOnClickListener { quitApp() }
		col.addView(quit, llp(MATCH, 104, t = 22))
		v.addView(col, lp(751, WRAP, 1079, 150))

		fun ok(b: Boolean) = if(b) "✔ " else "! "
		fun setTile(k: String, good: Boolean, title: String, s: String)
		{
			tiles[k]?.text = android.text.SpannableStringBuilder(ok(good)).apply {
				setSpan(android.text.style.ForegroundColorSpan(if(good) Ui.OK else Ui.WARN), 0, length, 0); append(title) }
			subs[k]?.text = s
		}
		val caps = DeviceCaps(this)
		val p = Preferences(this)
		setTile("pic", true, "ภาพ ${p.resolution.value} ${p.fps.value}", if(DeviceCaps.quality(this) == "auto") "ปรับอัตโนมัติ" else "ตั้งเอง")
		setTile("band", prefs.mode == TranslatorPrefs.Mode.SCREEN, if(prefs.mode == TranslatorPrefs.Mode.SCREEN) "อ่านแค่แถบซับ" else "ฟังเสียง + อ่านซับ",
			if(prefs.mode == TranslatorPrefs.Mode.SCREEN) "ลื่นสุด" else "ได้ทุกบทพูด")
		setTile("ai", true, "AI", "กำลังตรวจ…")
		Thread {
			val cloud = CloudTranslator(prefs.serverUrl, prefs.cloudCode, CloudTranslator.deviceId(this))
			val pr = cloud.check()
			main.post {
				when(pr)
				{
					CloudTranslator.Problem.NONE, CloudTranslator.Problem.DAILY_LIMIT, CloudTranslator.Problem.POOL_EMPTY ->
						setTile("ai", true, "AI พร้อม", "เล่นฟรี")
					CloudTranslator.Problem.NOT_STARTED -> setTile("ai", false, "AI ยังไม่เปิด", CloudTranslator.aiStartText())
					CloudTranslator.Problem.OFFLINE -> setTile("ai", false, "ไม่มีอินเทอร์เน็ต", "ตรวจเน็ตของทีวี")
					else -> setTile("ai", false, "AI ยังไม่พร้อม", "ลองใหม่อีกครั้งในไม่กี่นาที")
				}
			}
		}.start()

		// live: controllers and PS5 list
		var lastHosts: List<DisplayHost>? = null
		fun renderHosts(list: List<DisplayHost>)
		{
			hostsBox.removeAllViews()
			val shown = list.sortedByDescending { it.registeredHost != null }.take(2)
			if(shown.isEmpty())
			{
				val card = hostCard("กำลังค้นหา PS5…", "เปิด PS5 และต่อเน็ตบ้านเดียวกับทีวี · กดเพื่อเพิ่มเอง", "PS5") {
					startActivity(Intent(this, RegistActivity::class.java))
				}
				hostsBox.addView(card, llp(MATCH, 180))
			}
			shown.forEachIndexed { k, h ->
				val ready = h.registeredHost != null
				val standby = h is DiscoveredDisplayHost && h.discoveredHost.state == DiscoveryHost.State.STANDBY
				val sub = when { !ready -> "ยังไม่ได้จับคู่ · กดเพื่อจับคู่ (ทำครั้งเดียว)"; standby -> "โหมดพัก · กด ✕ เพื่อปลุกและเล่น"; else -> "พร้อมเชื่อมต่อ · กด ✕ เพื่อเล่น" }
				hostsBox.addView(hostCard(h.name ?: "PS5", sub, if(h.isPS5) "PS5" else "PS4") { hostTriggered(h) },
					llp(MATCH, if(shown.size > 1) 150 else 180, t = if(k == 0) 0 else 16))
			}
		}
		vm.displayHosts.value?.let { lastHosts = it; renderHosts(it) } ?: renderHosts(emptyList())
		vm.displayHosts.observe(this) { list -> if(tab == 0 && list.map { it.host + it.registeredHost?.id } != lastHosts?.map { it.host + it.registeredHost?.id }) { lastHosts = list; renderHosts(list) } }
		val tick = object : Runnable { override fun run() {
			if(isHdmi())
			{
				val okOv = com.metallic.chiaki.hdmi.HdmiModeService.canDrawOverlays(this@TvActivity)
				setTile("pad", okOv, if(com.metallic.chiaki.hdmi.HdmiModeService.running) "โหมด 4K ทำงานอยู่" else if(okOv) "โหมด 4K พร้อม" else "ยังไม่อนุญาตแสดงทับ",
					if(okOv) "จอยต่อ PS5 ตามปกติ" else "กดการ์ด PS5 แล้วทำตามข้อแรก")
			}
			else
			{
				val pads = Diag.gamepads()
				setTile("pad", pads.isNotEmpty(), if(pads.isNotEmpty()) "จอยพร้อม" else "ยังไม่มีจอย", if(pads.isNotEmpty()) pads.first().take(24) else "ไปที่แท็บ จอย เพื่อจับคู่")
			}
			main.postDelayed(this, 1500) } }
		startRefresh(tick)
		return v
	}

	/** Stops everything that talks to the PS5 (HDMI mode too) and closes the app. */
	private fun quitApp()
	{
		try { if(com.metallic.chiaki.hdmi.HdmiModeService.running) com.metallic.chiaki.hdmi.HdmiModeService.stop(this) } catch(_: Throwable) { }
		try { vm.discoveryManager.active = false } catch(_: Throwable) { }
		toast("เลิกเล่นแล้ว · ตัดการเชื่อมต่อ PS5 เรียบร้อย")
		main.postDelayed({ finishAffinity() }, 600)
	}

	private fun hostCard(name: String, sub: String, badge: String, onClick: () -> Unit): View
	{
		val card = Card(this, 26f)
		card.grow = 1.03f
		val r = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(34, 0, 34, 0) }
		r.addView(text(this, badge, 34f, 0xFF2E1065.toInt(), W.EXTRABOLD, Gravity.CENTER).apply {
			background = Ui.gradient(intArrayOf(0xFFF5F3FF.toInt(), 0xFFC4B5FD.toInt()), 24f) }, llp(112, 112))
		val c = Ui.col(this)
		c.addView(text(this, name, 36f, Ui.TEXT, W.SEMIBOLD).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
		c.addView(text(this, sub, 23f, Ui.MUTED).apply { maxLines = 2 }, llp(WRAP, WRAP, t = 2))
		r.addView(c, llp(0, WRAP, 1f, l = 28))
		card.addView(r, FrameLayout.LayoutParams(MATCH, MATCH))
		card.setOnClickListener { onClick() }
		return card
	}

	private fun hostTriggered(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(registeredHost == null)
		{
			startActivity(Intent(this, RegistActivity::class.java).apply {
				putExtra(RegistActivity.EXTRA_HOST, host.host)
				putExtra(RegistActivity.EXTRA_BROADCAST, false)
				if(host is ManualDisplayHost) putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
			})
			return
		}
		if(com.metallic.chiaki.hdmi.HdmiModeService.running)
		{
			if(sp.getString(KEY_PLAY_MODE, "stream") == "hdmi") { hdmiPicker(); return }
			// the player switched to playing through the app (1080p): 4K mode must not keep pulling the TV to HDMI
			com.metallic.chiaki.hdmi.HdmiModeService.stop(this)
			toast("หยุดโหมด 4K แล้ว · กำลังเตรียมเล่นผ่านแอป")
		}
		com.metallic.chiaki.hdmi.HdmiModeService.lastProblem?.let { p ->
			com.metallic.chiaki.hdmi.HdmiModeService.lastProblem = null
			toast("โหมด HDMI ครั้งก่อน: $p")
		}
		preflight(host, registeredHost)
	}

	private fun modeLabel(hdmi: Boolean) =
		if(hdmi) "โหมด: เล่นผ่าน HDMI (ภาพ 4K · จอยต่อ PS5) · กดเพื่อเปลี่ยน" else "โหมด: เล่นผ่านแอป (จอยต่อทีวี) · กดเพื่อเปลี่ยนเป็นเล่นผ่าน HDMI"

	private fun openOverlaySettings()
	{
		val tries = listOf(
			Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")),
			Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
			Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
		for(i in tries) try { startActivity(i); return } catch(_: Throwable) { }
		toast("เปิดหน้าตั้งค่าไม่ได้ · ทีวีนี้อาจไม่รองรับโหมด HDMI")
	}

	/** HDMI mode: start the hidden stream + overlay, then tell the player to switch the TV to the PS5's HDMI input. */
	private fun startHdmi(host: DisplayHost, registeredHost: RegisteredHost)
	{
		val info = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
		com.metallic.chiaki.hdmi.HdmiModeService.start(this, info)
		toast("เริ่มโหมด 4K แล้ว ✔ กำลังเปลี่ยนไปช่อง HDMI ของ PS5…")
		// give the hidden reader a moment to start, then show the PS5's own 4K picture
		main.postDelayed({ goToPs5Input() }, 1500)
	}

	/** One HDMI input of this TV. [arc] = labelled ARC / eARC (usually the sound bar / speaker port, not the PS5). */
	private class HdmiIn(val label: String, val id: String, val live: Boolean, val arc: Boolean)

	/** HDMI inputs of this TV (Android TV input framework). Empty on boxes / dongles / TVs that hide them. */
	private fun hdmiInputs(): List<HdmiIn> = try
	{
		val tim = getSystemService(android.media.tv.TvInputManager::class.java)
		fun on(id: String) = try { tim?.getInputState(id) == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED } catch(_: Throwable) { false }
		tim?.tvInputList.orEmpty().filter { it.type == android.media.tv.TvInputInfo.TYPE_HDMI && !it.isHidden(this) }.map {
			val label = (it.loadCustomLabel(this) ?: it.loadLabel(this)).toString()
			HdmiIn(label, it.id, on(it.id), Regex("""\b(e?arc)\b""", RegexOption.IGNORE_CASE).containsMatchIn(label))
		}
	}
	catch(_: Throwable) { emptyList() }

	/** Switches the TV picture to that HDMI input (the Thai overlay stays on top). */
	private fun openHdmiInput(id: String): Boolean = try
	{
		startActivity(Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(id))
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
	}
	catch(_: Throwable) { false }

	/** 4K mode started (or already running): the player picks the PS5's HDMI input, every time, so a wrong pick is easy to fix. */
	private fun goToPs5Input() = hdmiPicker()

	/**
	 * Full-screen page: "which HDMI input is the PS5 on?". Every input is a big card (signal / last used / sound-bar port
	 * marked), with ย้อนกลับ and หยุดโหมด 4K buttons. Nothing is picked automatically.
	 */
	private fun hdmiPicker()
	{
		val inputs = hdmiInputs()
		val saved = sp.getString(KEY_PS5_INPUT, null)
		val panel = FrameLayout(this).apply { setBackgroundColor(0xF007050C.toInt()); isClickable = true }
		val box = Ui.col(this).apply { background = Ui.round(Ui.PANEL2, 36f, 3, Ui.VIOLET2); setPadding(64, 48, 64, 44) }
		box.addView(text(this, "PS5 เสียบอยู่ช่อง HDMI ไหน?", 46f, Ui.TEXT, W.BOLD))
		box.addView(text(this, if(inputs.isEmpty()) "ทีวีนี้ไม่ให้แอปเปลี่ยนช่องเอง · ใช้รีโมททีวีกดปุ่ม Input / Source แล้วเลือกช่อง HDMI ที่เสียบ PS5"
			else "เลือกช่องที่เสียบสาย HDMI จาก PS5 · ไม่ใช่ช่อง eARC / ARC ที่ต่อลำโพงหรือซาวด์บาร์ · เลือกผิด กลับมาเลือกใหม่ได้ทุกครั้ง",
			24f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, llp(1200, WRAP, t = 8))
		var closed = false
		fun close() { if(closed) return; closed = true; keyCatcher = null
			panel.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(panel) }.start() }
		var focus: View? = null
		var row: LinearLayout? = null
		inputs.forEachIndexed { k, inp ->
			if(k % 3 == 0) { row = Ui.row(this); box.addView(row, llp(1200, WRAP, t = if(k == 0) 26 else 16)) }
			val card = Card(this, 24f, Ui.PANEL) { inp.id == saved }
			card.grow = 1.05f
			val c = Ui.col(this).apply { setPadding(26, 18, 26, 18); gravity = Gravity.CENTER_VERTICAL }
			c.addView(text(this, inp.label, 34f, Ui.TEXT, W.SEMIBOLD).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
			val tags = ArrayList<Pair<String, Int>>()
			tags += if(inp.live) "● มีสัญญาณ" to Ui.OK else "○ ไม่มีสัญญาณ" to Ui.DIM
			if(inp.id == saved) tags += "ใช้ครั้งก่อน" to Ui.VIOLET2
			if(inp.arc) tags += "ช่องลำโพง?" to Ui.WARN
			c.addView(text(this, android.text.SpannableStringBuilder().apply {
				tags.forEachIndexed { i, (t, col) -> if(i > 0) append("  ·  "); val s = length; append(t)
					setSpan(android.text.style.ForegroundColorSpan(col), s, length, 0) } }, 21f, Ui.MUTED), llp(WRAP, WRAP, t = 4))
			card.addView(c, FrameLayout.LayoutParams(MATCH, MATCH))
			card.setOnClickListener {
				sp.edit().putString(KEY_PS5_INPUT, inp.id).apply()
				close()
				if(!openHdmiInput(inp.id)) toast("เปิดช่องนี้ไม่ได้ · ใช้ปุ่ม Input ที่รีโมททีวีแทน")
			}
			row!!.addView(card, llp(0, 130, 1f, l = if(k % 3 == 0) 0 else 18))
			if(focus == null && inp.id == saved) focus = card
		}
		// fill the last row so the cards keep their size
		if(inputs.isNotEmpty()) repeat((3 - inputs.size % 3) % 3) { row!!.addView(View(this), llp(0, 130, 1f, l = 18)) }
		if(focus == null) focus = box.getFocusables(View.FOCUS_FORWARD).firstOrNull()

		val buttons = Ui.row(this)
		fun button(label: String, primary: Boolean, onClick: () -> Unit): Card = Card(this, 30f).apply {
			grow = 1.05f
			addView(text(context, label, 28f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER), FrameLayout.LayoutParams(MATCH, MATCH))
			if(primary) background = Ui.gradient(intArrayOf(Ui.VIOLET, Ui.PURPLE), 30f)
			setOnClickListener { onClick() }
		}
		val back = button("← ย้อนกลับ", inputs.isEmpty()) { close() }
		buttons.addView(back, llp(0, 92, 1f))
		buttons.addView(button("หยุดโหมด 4K", false) {
			close(); com.metallic.chiaki.hdmi.HdmiModeService.stop(this); toast("หยุดโหมด 4K แล้ว · เล่นผ่านแอปได้ตามปกติ") }, llp(0, 92, 1f, l = 18))
		box.addView(buttons, llp(1200, WRAP, t = 30))
		box.addView(text(this, "เล่นด้วยจอยที่ต่อ PS5 · ซับไทยขึ้นทับภาพเอง (เปิด Subtitles ในเกม) · เลิกเล่น: กลับมาที่แอป › การ์ด \"เลิกเล่น\"",
			21f, Ui.DIM, gravity = Gravity.CENTER), llp(1200, WRAP, t = 16))
		panel.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
		root.addView(panel, FrameLayout.LayoutParams(1920, 1080))
		panel.alpha = 0f; panel.animate().alpha(1f).setDuration(200).start()
		keyCatcher = { e -> if((e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B) && !PadConfig.fromPad(e)) { if(e.action == KeyEvent.ACTION_UP) close(); true } else false }
		val f = focus ?: back
		main.post { f.requestFocus() }
	}

	/** Step-by-step help for one checklist line, with an optional button that does the fix. */
	private fun guide(title: String, steps: List<String>, action: String?, doIt: () -> Unit)
	{
		val body = steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n\n")
		val b = android.app.AlertDialog.Builder(this).setTitle("วิธีแก้: $title").setMessage(body)
		if(action != null) b.setPositiveButton(action) { _, _ -> doIt() }
		b.setNegativeButton("ปิด", null)
		val d = b.show()
		try
		{
			d.findViewById<TextView>(android.R.id.message)?.apply { textSize = 18f; setLineSpacing(0f, 1.25f) }
			for(w in listOf(android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE))
				d.getButton(w)?.apply { textSize = 19f; isAllCaps = false }
			d.getButton(if(action != null) android.app.AlertDialog.BUTTON_POSITIVE else android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
		}
		catch(_: Throwable) { }
	}

	/** AI check result shared by the checklist (the server is asked at most every 30 s). */
	@Volatile private var aiCheck: Pair<Long, CloudTranslator.Problem>? = null

	/**
	 * Checklist before every game: nothing starts until the controller, Thai subtitles, AI and the PS5 are all ready,
	 * so nobody sits in a game wondering why there are no subtitles. Each line says what is wrong and fixes it on ✕.
	 */
	private fun preflight(host0: DisplayHost, registeredHost: RegisteredHost)
	{
		val panel = FrameLayout(this).apply { setBackgroundColor(0xEE07050C.toInt()); isClickable = true }
		val box = Ui.col(this).apply { background = Ui.round(Ui.PANEL2, 36f, 3, Ui.VIOLET2); setPadding(64, 48, 64, 48) }
		box.addView(text(this, "เช็กก่อนเข้าเกม", 46f, Ui.TEXT, W.BOLD))
		box.addView(text(this, "ต้องผ่านครบ 4 ข้อ ซับไทยถึงจะขึ้น · ข้อไหนยังไม่ผ่าน กดที่ข้อนั้นเพื่อแก้", 24f, Ui.MUTED), llp(WRAP, WRAP, t = 6))
		// play mode (link build): through the app (stream) or on the TV's HDMI input with Thai drawn on top
		val hdmiAllowed = true
		fun hdmi() = hdmiAllowed && sp.getString(KEY_PLAY_MODE, "stream") == "hdmi"
		var modeText: TextView? = null
		if(hdmiAllowed)
		{
			val mode = Card(this, 22f); mode.grow = 1.02f
			val mt = text(this, "", 26f, Ui.TEXT, W.MEDIUM, Gravity.CENTER_VERTICAL).apply { setPadding(30, 0, 30, 0) }
			mode.addView(mt, FrameLayout.LayoutParams(MATCH, MATCH))
			modeText = mt
			mode.setOnClickListener {
				if(hdmi()) sp.edit().putString(KEY_PLAY_MODE, "stream").apply()
				else guide("เล่นผ่าน HDMI (ทดลอง)", listOf(
					"ภาพเกมมาจาก HDMI ของ PS5 ตรง ๆ: ภาพ 4K ไม่หน่วง จอยต่อ PS5 ตามปกติ ไม่ต้องต่อทีวี",
					"แอปจะต่อ PS5 เบื้องหลังเพื่ออ่านซับอย่างเดียว แล้ววาดซับไทยทับภาพ HDMI",
					"ใช้ได้เฉพาะทีวี Android TV / Google TV ที่ PS5 เสียบอยู่ (กล่องหรือดองเกิลใช้โหมดนี้ไม่ได้)",
					"ต้องอนุญาต \"แสดงทับแอปอื่น\" ให้แอปนี้ (ข้อแรกในรายการจะพาไป)",
					"ทีวีบางรุ่นปิดแอปเบื้องหลังหรือไม่ให้แสดงทับช่อง HDMI: ถ้าไม่เห็นซับ ให้กลับมาใช้โหมดเล่นผ่านแอป"),
					"ใช้โหมด HDMI") { sp.edit().putString(KEY_PLAY_MODE, "hdmi").apply() }
				main.postDelayed({ modeText?.text = modeLabel(hdmi()) }, 300)
			}
			box.addView(mode, llp(1100, 84, t = 16))
			mt.text = modeLabel(hdmi())
		}
		data class Row(val card: Card, val mark: TextView, val title: TextView, val sub: TextView)
		val rows = HashMap<String, Row>()
		var wakeSent = false
		fun currentHost(): DisplayHost = vm.displayHosts.value?.firstOrNull { it.host == host0.host && it.registeredHost != null } ?: host0
		val fixes = HashMap<String, () -> Unit>()
		for(key in listOf("pad", "subs", "ai", "ps5"))
		{
			val card = Card(this, 22f); card.grow = 1.02f
			val r = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(30, 0, 30, 0) }
			val mark = text(this, "…", 40f, Ui.MUTED, W.BOLD, Gravity.CENTER)
			val c = Ui.col(this)
			val t1 = text(this, "", 30f, Ui.TEXT, W.SEMIBOLD); val t2 = text(this, "", 22f, Ui.MUTED)
			c.addView(t1); c.addView(t2, llp(WRAP, WRAP, t = 2))
			r.addView(mark, llp(70, WRAP)); r.addView(c, llp(0, WRAP, 1f, l = 10))
			card.addView(r, FrameLayout.LayoutParams(MATCH, MATCH))
			card.setOnClickListener { fixes[key]?.invoke() }
			box.addView(card, llp(1100, 112, t = 18))
			rows[key] = Row(card, mark, t1, t2)
		}
		val go = Card(this, 30f); go.grow = 1.04f
		val goText = text(this, "", 32f, Ui.TEXT, W.BOLD, Gravity.CENTER)
		go.addView(goText, FrameLayout.LayoutParams(MATCH, MATCH))
		val goRow = Ui.row(this)
		val cancel = Card(this, 30f); cancel.grow = 1.04f
		cancel.addView(text(this, "← ย้อนกลับ", 30f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER), FrameLayout.LayoutParams(MATCH, MATCH))
		goRow.addView(cancel, llp(320, 100))
		goRow.addView(go, llp(0, 100, 1f, l = 20))
		box.addView(goRow, llp(1100, WRAP, t = 26))
		panel.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
		root.addView(panel, FrameLayout.LayoutParams(1920, 1080))
		panel.alpha = 0f; panel.animate().alpha(1f).setDuration(220).start()

		var closed = false
		lateinit var tick: Runnable
		fun close() { if(closed) return; closed = true; main.removeCallbacks(tick); keyCatcher = null
			panel.animate().alpha(0f).setDuration(180).withEndAction { root.removeView(panel) }.start() }
		fun set(key: String, good: Boolean?, title: String, sub: String)
		{
			val r = rows[key] ?: return
			r.mark.text = when(good) { true -> "✔"; false -> "✕"; null -> "…" }
			r.mark.setTextColor(when(good) { true -> Ui.OK; false -> Ui.WARN; null -> Ui.MUTED })
			r.title.text = title; r.sub.text = sub
		}
		var allOk = false
		cancel.setOnClickListener { close() }
		go.setOnClickListener {
			if(!allOk) { toast("ยังไม่ครบ · กดที่ข้อที่มี ✕ เพื่อแก้ก่อน"); return@setOnClickListener }
			close()
			if(hdmi()) startHdmi(currentHost(), registeredHost) else connect(currentHost(), registeredHost)
		}
		// every line that is not ready opens step-by-step help with a button that does the fix
		fun ready(key: String) = rows[key]?.mark?.text == "✔"
		fixes["pad"] = {
			if(hdmi())
			{
				if(ready("pad")) toast("อนุญาตแล้ว ✔")
				else guide("อนุญาตให้แอปแสดงซับทับภาพ HDMI", listOf(
					"กด \"ไปที่การตั้งค่า\" ด้านล่าง",
					"หาแอป Eyesmaxxing แล้วเปิด \"อนุญาตให้แสดงทับแอปอื่น\" (Display over other apps)",
					"ถ้าไม่เจอเมนูนี้: ตั้งค่าทีวี › แอป › สิทธิ์เข้าถึงพิเศษของแอป › แสดงทับแอปอื่น",
					"ทีวีบางรุ่นไม่มีเมนูนี้เลย = ใช้โหมด HDMI ไม่ได้ ให้กดเปลี่ยนกลับเป็นโหมดเล่นผ่านแอป",
					"กดย้อนกลับมาหน้านี้ ข้อนี้จะขึ้น ✔ เอง"),
					"ไปที่การตั้งค่า") { openOverlaySettings() }
			}
			else if(ready("pad")) toast("จอยพร้อมแล้ว ✔")
			else guide("ต่อจอยกับทีวี", listOf(
				"จอยต้องต่อกับทีวี ไม่ใช่กับ PS5 (จอยที่ต่อ PS5 ใช้ในแอปไม่ได้)",
				"ทางที่ง่ายสุด: เสียบสาย USB จากจอยเข้าทีวีตรง ๆ (ต้องเป็นสายที่ส่งข้อมูลได้ ไม่ใช่สายชาร์จอย่างเดียว)",
				"หรือจับคู่บลูทูธ: กดปุ่ม \"จับคู่บลูทูธ\" ด้านล่าง แล้วที่จอยกด Create + PS ค้างไว้จนไฟกะพริบถี่ ๆ",
				"เลือกชื่อจอย (เช่น DualSense / Wireless Controller) ในรายการของทีวี",
				"กลับมาหน้านี้ ข้อนี้จะขึ้น ✔ เอง",
				"ปุ่มเพี้ยนหรือค้าง: ไปที่แท็บ จอย › แก้ปุ่มสลับ / แก้จอยค้าง"),
				"จับคู่บลูทูธ") { openBluetooth() }
		}
		fixes["subs"] = {
			if(ready("subs")) toast("ซับไทยเปิดอยู่แล้ว ✔")
			else guide("เปิดซับไทย", listOf(
				"กดปุ่ม \"เปิดซับไทยเลย\" ด้านล่าง",
				"ในเกม GTA 6 ต้องเปิดซับไตเติลด้วย: ตั้งค่า › ภาษา/ซับไตเติล › ซับไตเติล › เปิด (แอปอ่านซับอังกฤษของเกมแล้วแปล)"),
				"เปิดซับไทยเลย") { sp.edit().putBoolean("tr_enabled", true).apply(); tick.run() }
		}
		fixes["ai"] = {
			val p = aiCheck?.second
			when
			{
				ready("ai") -> toast("AI พร้อมแล้ว ✔")
				p == CloudTranslator.Problem.OFFLINE -> guide("ทีวีไม่มีอินเทอร์เน็ต", listOf(
					"เปิด YouTube ในทีวีดูว่าเล่นได้ไหม",
					"ถ้าไม่ได้: ตั้งค่าทีวี › เครือข่าย › ต่อ Wi-Fi ใหม่ (แนะนำ 5GHz) หรือเสียบสาย LAN",
					"ปิด-เปิดเราเตอร์ 1 ครั้งถ้ายังไม่ได้",
					"กลับมาหน้านี้ รอประมาณ 30 วินาที ข้อนี้จะตรวจใหม่เอง"), "ตรวจใหม่") { aiCheck = null; tick.run() }
				p == CloudTranslator.Problem.NOT_STARTED -> guide("AI ยังไม่เปิด", listOf(
					"แปลด้วย AI จะเปิดวันที่ " + CloudTranslator.aiStartText(),
					"ระหว่างนี้จับคู่ PS5 และต่อจอยให้เรียบร้อยไว้ก่อนได้"), null) { }
				else -> guide("AI ยังไม่พร้อม", listOf(
					"กด \"ตรวจใหม่\" ด้านล่าง",
					"ถ้ายังไม่ได้: ปิดแอปแล้วเปิดใหม่ หรือรอ 2–3 นาที",
					"ตรวจว่าทีวีต่ออินเทอร์เน็ตอยู่ (ลองเปิด YouTube)"), "ตรวจใหม่") { aiCheck = null; tick.run() }
			}
		}
		fixes["ps5"] = {
			val h = currentHost()
			when
			{
				ready("ps5") -> toast("PS5 พร้อมแล้ว ✔")
				h is DiscoveredDisplayHost && h.discoveredHost.state == DiscoveryHost.State.STANDBY -> guide("PS5 อยู่โหมดพัก", listOf(
					"กด \"ปลุก PS5\" ด้านล่าง แล้วรอประมาณ 10 วินาที",
					"ถ้าปลุกไม่ได้: ที่ PS5 ไปที่ ตั้งค่า › ระบบ › ประหยัดพลังงาน › ฟีเจอร์ในโหมดพัก › เปิด \"คงการเชื่อมต่ออินเทอร์เน็ต\" และ \"เปิด PS5 จากเครือข่าย\"",
					"หรือกดปุ่มเปิดที่ตัว PS5 / ปุ่ม PS ที่จอยที่ต่อกับ PS5"),
					"ปลุก PS5") { vm.discoveryManager.sendWakeup(h.host, registeredHost.rpRegistKey, registeredHost.target.isPS5); wakeSent = true; tick.run() }
				else -> guide("ยังไม่เจอ PS5", listOf(
					"เปิด PS5 ให้อยู่หน้าหลัก (ไม่ใช่ปิดเครื่องสนิท)",
					"ทีวีกับ PS5 ต้องต่อเน็ตบ้านวงเดียวกัน (เราเตอร์ตัวเดียวกัน) · แนะนำ PS5 ต่อสาย LAN",
					"ที่ PS5: ตั้งค่า › ระบบ › การเล่นระยะไกล › เปิด \"เปิดใช้การเล่นระยะไกล\"",
					"ยังไม่เจอ: ปิด-เปิดเราเตอร์ แล้วรอ 1 นาที ข้อนี้จะเปลี่ยนเป็น ✔ เอง"), null) { }
			}
		}

		tick = object : Runnable { override fun run() {
			if(closed) return
			// 1. controller on the TV (app mode) / permission to draw over the HDMI picture (HDMI mode)
			if(hdmi())
			{
				if(com.metallic.chiaki.hdmi.HdmiModeService.canDrawOverlays(this@TvActivity))
					set("pad", true, "แสดงซับทับภาพ HDMI ได้", "จอยต่อ PS5 ตามปกติ ไม่ต้องต่อทีวี")
				else set("pad", false, "ยังไม่ได้อนุญาตให้แสดงทับแอปอื่น", "กดที่นี่ แล้วเปิด \"แสดงทับแอปอื่น\" ให้ Eyesmaxxing")
			}
			else
			{
				val pads = Diag.gamepads()
				if(pads.isNotEmpty()) set("pad", true, "จอยต่อกับทีวีแล้ว", pads.first().take(40))
				else set("pad", false, "ยังไม่ได้ต่อจอยกับทีวี", "จอยต้องต่อกับทีวี (ไม่ใช่ PS5) · เสียบสาย USB หรือกดที่นี่เพื่อจับคู่บลูทูธ (Create + PS ค้าง)")
			}
			modeText?.text = modeLabel(hdmi())
			// 2. Thai subtitles switched on
			if(TranslatorPrefs(this@TvActivity).enabled) set("subs", true, "เปิดซับไทยแล้ว", "ซับไทยจะขึ้นเองเมื่อเข้าเกม")
			else set("subs", false, "ซับไทยปิดอยู่", "กดที่นี่เพื่อเปิด")
			// 3. internet + AI (asked from the server at most every 30 s)
			val ai = aiCheck
			if(ai == null || System.currentTimeMillis() - ai.first > 30_000)
			{
				if(ai == null) set("ai", null, "กำลังตรวจ AI แปลภาษา…", "ต้องต่ออินเทอร์เน็ต")
				aiCheck = Pair(System.currentTimeMillis(), ai?.second ?: CloudTranslator.Problem.OFFLINE)
				Thread {
					val prefs = TranslatorPrefs(this@TvActivity)
					val p = CloudTranslator(prefs.serverUrl, prefs.cloudCode, CloudTranslator.deviceId(this@TvActivity)).check()
					aiCheck = Pair(System.currentTimeMillis(), p)
					main.post { if(!closed) run() }
				}.start()
			}
			else when(ai.second)
			{
				CloudTranslator.Problem.NONE, CloudTranslator.Problem.DAILY_LIMIT, CloudTranslator.Problem.POOL_EMPTY ->
					set("ai", true, "AI แปลภาษาพร้อม", "ต่ออินเทอร์เน็ตแล้ว")
				CloudTranslator.Problem.OFFLINE -> set("ai", false, "ทีวีไม่มีอินเทอร์เน็ต", "เช็ก Wi-Fi หรือสาย LAN ของทีวี (ลองเปิด YouTube) แล้วรอสักครู่")
				CloudTranslator.Problem.NOT_STARTED -> set("ai", false, "AI ยังไม่เปิด", "เปิดวันที่ " + CloudTranslator.aiStartText())
				else -> set("ai", false, "AI ยังไม่พร้อม", "กดที่นี่เพื่อตรวจใหม่")
			}
			// 4. PS5 on the home network and awake
			val h = currentHost()
			when
			{
				h is DiscoveredDisplayHost && h.discoveredHost.state == DiscoveryHost.State.READY ->
					set("ps5", true, "เจอ " + (h.name ?: "PS5") + " แล้ว", "เปิดอยู่ · อยู่เน็ตบ้านเดียวกับทีวี")
				h is DiscoveredDisplayHost && h.discoveredHost.state == DiscoveryHost.State.STANDBY ->
					set("ps5", false, if(wakeSent) "กำลังปลุก PS5…" else "PS5 อยู่โหมดพัก", if(wakeSent) "รอประมาณ 10 วินาที" else "กดที่นี่เพื่อปลุก PS5")
				// added by IP address: discovery cannot see it (other subnet), so it cannot be checked here
				h is ManualDisplayHost -> set("ps5", true, (h.name ?: "PS5") + " (เพิ่มด้วย IP)", "ตรวจสถานะล่วงหน้าไม่ได้ · ให้ PS5 เปิดอยู่หน้าหลัก")
				else -> set("ps5", false, "ยังไม่เจอ PS5", "เปิด PS5 ให้อยู่หน้าหลัก · ทีวีกับ PS5 ต้องใช้เน็ตบ้านวงเดียวกัน")
			}
			allOk = rows.values.all { it.mark.text == "✔" }
			goText.text = if(allOk) "เริ่มเล่น ▶" else "ยังไม่ครบ ${rows.values.count { it.mark.text != "✔" }} ข้อ"
			go.alpha = if(allOk) 1f else 0.55f
			main.postDelayed(this, 1500)
		} }
		keyCatcher = { e -> if(e.keyCode == KeyEvent.KEYCODE_BACK && !PadConfig.fromPad(e)) { if(e.action == KeyEvent.ACTION_UP) close(); true } else false }
		tick.run()
		main.post { (rows.values.firstOrNull { it.mark.text == "✕" }?.card ?: go).requestFocus() }
	}

	private fun connect(host: DisplayHost, registeredHost: RegisteredHost)
	{
		val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
		startActivity(Intent(this, StreamActivity::class.java).putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo))
	}

	// ---- tab 1: ตั้งค่าซับ
	// Two columns: left = how the Thai looks (size, colour, band, font); right = live preview, where it sits,
	// the line before, and how to play (through the app / 4K HDMI). Everything else is in ตั้งค่าขั้นสูง.
	private fun settingsContent(): View
	{
		val v = FrameLayout(this)
		val groups = ArrayList<Card>()
		lateinit var refreshPreview: () -> Unit
		fun option(title: CharSequence, sub: String?, isSel: () -> Boolean, pick: () -> Unit, font: android.graphics.Typeface? = null, size: Float = 24f,
			color: Int = Ui.TEXT): Card
		{
			val card = Card(this, 18f, Ui.PANEL, isSel)
			card.grow = 1.05f
			val c = Ui.col(this).apply { setPadding(12, 6, 12, 6); gravity = Gravity.CENTER }
			c.addView(text(this, title, size, color, W.MEDIUM, Gravity.CENTER).apply { if(font != null) typeface = font; maxLines = 1 })
			if(sub != null) c.addView(text(this, sub, 17f, Ui.MUTED, gravity = Gravity.CENTER).apply { maxLines = 1 }, llp(WRAP, WRAP, t = 1))
			card.addView(c, FrameLayout.LayoutParams(MATCH, MATCH))
			card.setOnClickListener { pick(); groups.forEach { it.refresh() }; refreshPreview() }
			groups += card
			return card
		}
		fun section(parent: LinearLayout, title: String, cells: List<Card>, h: Int, top: Int = 22)
		{
			parent.addView(text(this, title, 24f, Ui.VIOLET2, W.MEDIUM), llp(WRAP, WRAP, t = top))
			val r = Ui.row(this)
			cells.forEachIndexed { k, c -> r.addView(c, llp(0, h, 1f, l = if(k == 0) 0 else 12)) }
			parent.addView(r, llp(MATCH, WRAP, t = 8))
		}
		val prefs = { TranslatorPrefs(this) }
		fun str(k: String, d: String) = sp.getString(k, d) ?: d
		fun put(k: String, value: String) = sp.edit().putString(k, value).apply()

		// ---- left: the look
		val left = Ui.col(this)
		left.addView(text(this, "ตั้งค่าซับไทย", 44f, Ui.TEXT, W.BOLD))
		left.addView(text(this, "เลือกแล้วดูผลได้ทันทีในภาพตัวอย่างด้านขวา", 22f, Ui.MUTED), llp(WRAP, WRAP, t = 2))
		section(left, "ขนาดตัวอักษร", listOf("english" to "เท่าซับอังกฤษ", "small" to "เล็ก", "medium" to "กลาง", "large" to "ใหญ่", "xlarge" to "ใหญ่มาก").map { (value, label) ->
			option(label, null, { str("tr_text_size", "medium") == value }, { put("tr_text_size", value) }, size = 22f) }, 66)
		section(left, "สีตัวอักษร (ขอบดำทุกสี)", listOf(Triple("white", "ขาว", 0xFFFFFFFF.toInt()), Triple("yellow", "เหลือง", 0xFFFFE14D.toInt()),
			Triple("green", "เขียวสว่าง", 0xFF5CFF7A.toInt())).map { (value, label, col) ->
			option(label, null, { str(TranslatorPrefs.KEY_COLOR, "white") == value }, { put(TranslatorPrefs.KEY_COLOR, value) }, size = 26f, color = col) }, 66)
		section(left, "แถบดำด้านหลังซับ", listOf("off" to "ปิด", "light" to "จาง", "medium" to "ปานกลาง").map { (value, label) ->
			option(label, null, { str(TranslatorPrefs.KEY_BG, "medium") == value }, { put(TranslatorPrefs.KEY_BG, value) }, size = 24f) }, 66)
		section(left, "ฟอนต์", TranslatorPrefs.Font.values().filter { it != TranslatorPrefs.Font.NOTO }.map { f ->
			option("สวัสดี", f.title, { prefs().font == f }, { put("tr_font", f.value) }, ResourcesCompat.getFont(this, f.res), 26f) }, 84)
		v.addView(left, lp(940, WRAP, 90, 128))

		// ---- right: preview + placement + how to play
		val right = Ui.col(this)
		right.addView(text(this, "ตัวอย่างบนจอ", 24f, Ui.VIOLET2, W.MEDIUM), llp(WRAP, WRAP, t = 12))
		val prev = FrameLayout(this).apply { background = Ui.round(0, 26f, 2, Ui.LINE) }
		prev.addView(Ui.topImage(this, R.drawable.gta_shot3, 0.7f), FrameLayout.LayoutParams(MATCH, MATCH))
		Ui.clipRound(prev, 26f)
		val prevH = 280
		val thai = com.metallic.chiaki.translate.SubtitleTextView(this).apply { gravity = Gravity.CENTER; setPadding(14, 2, 14, 4) }
		val eng = text(this, "Okay, you're wound up a little tight. What's up?", 15f, 0xFFDDDDDD.toInt(), gravity = Gravity.CENTER).apply {
			typeface = android.graphics.Typeface.SANS_SERIF; setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt()) }
		prev.addView(eng, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 16 })
		prev.addView(thai, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 44 })
		right.addView(prev, llp(MATCH, prevH, t = 8))
		refreshPreview = {
			val p = prefs()
			thai.typeface = ResourcesCompat.getFont(this, p.font.res)
			val px = p.textHeightFraction * prevH * 1.45f
			thai.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
			thai.setTextColor(p.textColor)
			thai.outlineColor = 0xFF000000.toInt()
			thai.outlineWidthPx = px * (if(p.backgroundAlpha == 0) 0.11f else 0.07f)
			thai.background = if(p.backgroundAlpha == 0) null else android.graphics.drawable.ColorDrawable(p.backgroundAlpha shl 24)
			thai.text = if(p.showPrevious) android.text.SpannableStringBuilder("ใจเย็น ๆ  ›  ").apply {
				setSpan(com.metallic.chiaki.translate.FillColorSpan(0xFF9FB4FF.toInt()), 0, length, 0); append("เครียดเกินไปหรือเปล่า มีอะไรหรือเปล่า") }
				else "เครียดเกินไปหรือเปล่า มีอะไรหรือเปล่า"
			val tl = thai.layoutParams as FrameLayout.LayoutParams
			when(p.position)
			{
				TranslatorPrefs.Position.TOP -> { tl.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; tl.topMargin = 14; tl.bottomMargin = 0 }
				TranslatorPrefs.Position.BELOW_SUBTITLE -> { tl.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; tl.topMargin = 0; tl.bottomMargin = 6 }
				else -> { tl.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; tl.topMargin = 0; tl.bottomMargin = 44 }
			}
			(eng.layoutParams as FrameLayout.LayoutParams).bottomMargin = if(p.position == TranslatorPrefs.Position.BELOW_SUBTITLE) (px * 1.6f).toInt() + 10 else 16
			thai.layoutParams = tl; eng.requestLayout()
		}
		section(right, "ตำแหน่งซับไทย", listOf("above" to "เหนือซับอังกฤษ", "below" to "ใต้ซับอังกฤษ", "top" to "บนสุดของจอ").map { (value, label) ->
			option(label, null, { str(TranslatorPrefs.KEY_POSITION, "above") == value }, { put(TranslatorPrefs.KEY_POSITION, value) }, size = 22f) }, 66)
		fun prevOn() = sp.getBoolean(TranslatorPrefs.KEY_SHOW_PREVIOUS, true)
		section(right, "ประโยคก่อนหน้า (สีฟ้า)", listOf(
			option("แสดง", null, { prevOn() }, { sp.edit().putBoolean(TranslatorPrefs.KEY_SHOW_PREVIOUS, true).apply() }, size = 22f),
			option("ไม่แสดง · เฉพาะประโยคล่าสุด", null, { !prevOn() }, { sp.edit().putBoolean(TranslatorPrefs.KEY_SHOW_PREVIOUS, false).apply() }, size = 22f)), 66)
		fun hdmiMode() = sp.getString(KEY_PLAY_MODE, "stream") == "hdmi"
		section(right, "เล่นแบบ", listOf(
			option("ผ่านแอป · 1080p", "ทีวีทุกรุ่น · ปรับภาพอัตโนมัติ", { !hdmiMode() }, {
				sp.edit().putString(KEY_PLAY_MODE, "stream").apply(); DeviceCaps.apply(this, "auto")
				if(com.metallic.chiaki.hdmi.HdmiModeService.running) { com.metallic.chiaki.hdmi.HdmiModeService.stop(this); toast("หยุดโหมด 4K แล้ว") } }),
			option("4K · HDMI", "PS5 เสียบทีวีเครื่องนี้", { hdmiMode() },
				{ sp.edit().putString(KEY_PLAY_MODE, "hdmi").apply(); toast("โหมด 4K: กดการ์ด PS5 ในหน้าเล่น แล้วเลือกช่อง HDMI ที่เสียบ PS5") })), 84)
		v.addView(right, lp(760, WRAP, 1070, 128))
		refreshPreview()
		return v
	}

	private fun clearAutoMode() = sp.edit().putBoolean(TranslatorPrefs.KEY_MODE_AUTO, false).apply()

	// ---- tab 2: จอย
	private fun padContent(): View
	{
		val v = FrameLayout(this)
		v.addView(text(this, "จอย", 46f, Ui.TEXT, W.BOLD), lp(WRAP, WRAP, 90, 140))
		v.addView(text(this, "กดปุ่มที่จอยเพื่อทดสอบ · ปุ่มไหนสว่างผิดที่ ใช้ \"แก้ปุ่มสลับ\"", 27f, Ui.MUTED), lp(WRAP, WRAP, 90, 206))
		val padBox = Card(this, 26f)
		padBox.grow = 1.02f
		val pv = PadView(this)
		padBox.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(30, 30, 30, 30) })
		padBox.addView(text(this, "เลือกช่องนี้แล้วกดปุ่มจอยได้เลย", 21f, Ui.DIM, gravity = Gravity.CENTER), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 16 })
		v.addView(padBox, lp(820, 640, 90, 280))
		padView = pv; padCard = padBox

		val list = Ui.col(this)
		val status = Ui.col(this).apply { background = Ui.round(Ui.PANEL, 22f, 2, Ui.LINE); setPadding(30, 18, 30, 18) }
		val st1 = text(this, "", 27f, Ui.TEXT); val st2 = text(this, "", 22f, Ui.MUTED)
		status.addView(st1); status.addView(st2, llp(WRAP, WRAP, t = 2))
		list.addView(status, llp(MATCH, WRAP))
		fun item(title: String, sub: () -> String, go: String, action: (TextView) -> Unit)
		{
			val card = Card(this, 22f)
			card.grow = 1.03f
			val r = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(30, 0, 30, 0) }
			val c = Ui.col(this)
			val s = text(this, sub(), 21f, Ui.MUTED)
			c.addView(text(this, title, 28f, Ui.TEXT)); c.addView(s)
			r.addView(c, llp(0, WRAP, 1f))
			r.addView(text(this, "$go ›", 24f, Ui.VIOLET2))
			card.addView(r, FrameLayout.LayoutParams(MATCH, MATCH))
			card.setOnClickListener { action(s); s.text = sub() }
			list.addView(card, llp(MATCH, 104, t = 14))
		}
		item("แก้ปุ่มสลับ", { if(sp.getString(PadConfig.KEY_REMAP, "").isNullOrEmpty()) "กดตามที่แอปบอกทีละปุ่ม (ปุ่มที่ถูกอยู่แล้วกด ▶ ข้าม)" else "ตั้งค่าปุ่มเองแล้ว · กดเพื่อทำใหม่" }, "เริ่ม") { remapWizard() }
		item("แก้จอยค้าง / ขยับเอง", { "รีเซ็ตปุ่ม และไม่สนการขยับเล็ก ๆ ของอนาล็อก" }, "แก้เลย") {
			PadConfig.resetStuck(this); toast("รีเซ็ตจอยแล้ว ✔ ถ้ายังค้าง ปิด-เปิดจอยหนึ่งครั้ง") }
		item("เชื่อมจอยใหม่", { "DualSense · DualShock 4 · Xbox · จอยทั่วไป" }, "เชื่อม") { openBluetooth() }
		item("ใช้จอยผ่านกล่อง/ตัวแปลง", {
			"แกนอนาล็อกขวา: " + when(PadConfig(this).sticks) { PadConfig.Sticks.AUTO -> "อัตโนมัติ"; PadConfig.Sticks.Z_RZ -> "แบบ 1 (Z)"; PadConfig.Sticks.RX_RY -> "แบบ 2 (RX)" } +
				" · สลับ ✕/○: " + if(Preferences(this).swapCrossMoon) "เปิด" else "ปิด" }, "เปลี่ยน") {
			// cycle: auto -> Z -> RX -> auto + swap ✕/○ -> auto
			val pc = PadConfig(this); val pr = Preferences(this)
			when
			{
				pr.swapCrossMoon -> { pr.swapCrossMoon = false; PadConfig.setSticks(this, PadConfig.Sticks.AUTO) }
				pc.sticks == PadConfig.Sticks.AUTO -> PadConfig.setSticks(this, PadConfig.Sticks.Z_RZ)
				pc.sticks == PadConfig.Sticks.Z_RZ -> PadConfig.setSticks(this, PadConfig.Sticks.RX_RY)
				else -> { PadConfig.setSticks(this, PadConfig.Sticks.AUTO); pr.swapCrossMoon = true }
			}
		}
		v.addView(list, lp(870, WRAP, 960, 280))

		val tick = object : Runnable { override fun run() {
			val pads = Diag.gamepads()
			st1.text = android.text.SpannableStringBuilder(if(pads.isNotEmpty()) "✔ " else "! ").apply {
				setSpan(android.text.style.ForegroundColorSpan(if(pads.isNotEmpty()) Ui.OK else Ui.WARN), 0, length, 0)
				append(if(pads.isNotEmpty()) pads.joinToString(" · ").take(40) else "ยังไม่มีจอยต่อกับทีวี") }
			st2.text = if(pads.isNotEmpty()) "ต่อกับทีวีแล้ว · กด ✕ ต้องสว่างตรงตำแหน่ง ✕" else "กด \"เชื่อมจอยใหม่\" หรือเสียบสาย USB เข้าทีวี"
			main.postDelayed(this, 1500) } }
		tick.run(); main.removeCallbacks(tick)   // fill the status card right away, not after the first refresh
		startRefresh(tick)
		return v
	}

	/** Asks for ✕ ○ □ △ in turn and remembers which physical buttons they are. */
	private fun remapWizard()
	{
		// every button a player can press; any that is already right (or missing) is skipped with D-pad ▶
		val names = listOf("✕" to "กากบาท", "○" to "วงกลม", "□" to "สี่เหลี่ยม", "△" to "สามเหลี่ยม",
			"L1" to "ไหล่ซ้าย", "R1" to "ไหล่ขวา", "L2" to "ไกซ้าย", "R2" to "ไกขวา",
			"≡" to "Options (ขวาบน)", "⋯" to "Create / Share (ซ้ายบน)", "L3" to "กดอนาล็อกซ้ายลงไป", "R3" to "กดอนาล็อกขวาลงไป")
		val logical = intArrayOf(*PadConfig.FACE, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
			KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
		val got = ArrayList<Int>()          // physical code per step, -1 = skipped
		val panel = FrameLayout(this).apply { setBackgroundColor(0xE607050C.toInt()); isClickable = true }
		val box = Ui.col(this).apply { gravity = Gravity.CENTER_HORIZONTAL; background = Ui.round(Ui.PANEL2, 36f, 3, Ui.VIOLET2); setPadding(80, 60, 80, 60) }
		val step = text(this, "", 28f, Ui.VIOLET2, gravity = Gravity.CENTER)
		val glyph = text(this, "", 150f, Ui.TEXT, W.BOLD, Gravity.CENTER)
		val ask = text(this, "", 40f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER)
		box.addView(step); box.addView(glyph, llp(WRAP, WRAP, t = 10)); box.addView(ask, llp(WRAP, WRAP, t = 10))
		box.addView(text(this, "กดที่จอยที่ต่อกับทีวี · ปุ่มไหนถูกอยู่แล้วหรือไม่มี กดลูกศร ▶ ที่จอยเพื่อข้าม\nรีโมททีวีกดย้อนกลับเพื่อยกเลิก", 24f, Ui.MUTED, gravity = Gravity.CENTER), llp(WRAP, WRAP, t = 24))
		panel.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
		root.addView(panel, FrameLayout.LayoutParams(1920, 1080))
		panel.alpha = 0f; panel.animate().alpha(1f).setDuration(250).start()
		val total = names.size
		fun show() { val (g, n) = names[got.size]; step.text = "ขั้นที่ ${got.size + 1}/$total"; glyph.text = g; ask.text = "กดปุ่ม $g ($n)" }
		fun close() { keyCatcher = null; panel.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(panel) }.start() }
		fun next()
		{
			glyph.animate().scaleX(1.15f).scaleY(1.15f).setDuration(90).withEndAction { glyph.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
			if(got.size < total) { show(); return }
			val map = HashMap<Int, Int>()
			got.forEachIndexed { k, phys -> if(phys >= 0) map[phys] = logical[k] }
			PadConfig.saveRemap(this, map)
			Preferences(this).swapCrossMoon = false
			close(); toast("บันทึกปุ่มแล้ว ✔ ใช้ตอนเล่นเกมทันที · ลองกดในหน้านี้ ปุ่มบนจอต้องสว่างตรงกัน")
			showTab(2, Trans.NONE)
		}
		show()
		keyCatcher = { e ->
			if(e.keyCode == KeyEvent.KEYCODE_BACK && !PadConfig.fromPad(e)) { if(e.action == KeyEvent.ACTION_UP) close(); true }
			else if(PadConfig.fromPad(e) && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
			{ got += -1; next(); true }          // skip: this button is fine / not on this pad
			else if(PadConfig.fromPad(e) && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && !KeyEvent.isModifierKey(e.keyCode) &&
				e.keyCode !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT))
			{
				// some pads send BACK for ○ / ENTER for ✕: those count as buttons here
				val code = e.keyCode
				if(code !in got) { got += code; next() }
				true
			}
			else true
		}
	}

	// ---- tab 4: ช่วยเหลือ
	private fun helpContent(): View
	{
		val v = FrameLayout(this)
		v.addView(text(this, "ช่วยเหลือ", 46f, Ui.TEXT, W.BOLD), lp(WRAP, WRAP, 90, 140))
		val grid = Ui.col(this)
		val items = ArrayList<Triple<String, String, () -> Unit>>().apply {
			add(Triple("ตรวจปัญหา", "บอกทีละขั้นว่าติดตรงไหน พร้อมวิธีแก้") { startActivity(Intent(this@TvActivity, TranslatorTestActivity::class.java)) })
			add(Triple("จับคู่ PS5 ใหม่", "พิมพ์ชื่อออนไลน์ไอดี แล้วใส่รหัส 8 หลักจาก PS5") { startActivity(Intent(this@TvActivity, RegistActivity::class.java)) })
			add(Triple("เพิ่ม PS5 ด้วยเลข IP", "ถ้าแอปหา PS5 ไม่เจอเอง") { startActivity(Intent(this@TvActivity, EditManualConsoleActivity::class.java)) })
			add(Triple("ตั้งค่าขั้นสูง", "ภาพ เสียง และตัวเลือกทั้งหมด") { startActivity(Intent(this@TvActivity, SettingsActivity::class.java)) })
			add(Triple("ดูหน้าแนะนำอีกครั้ง", "ฟีเจอร์ และวิธีเริ่มใช้งาน") { showFeature(0, Trans.LEFT); tab = -1 })
		}
		var row: LinearLayout? = null
		items.forEachIndexed { k, (title, sub, action) ->
			if(k % 2 == 0) { row = Ui.row(this); grid.addView(row, llp(MATCH, WRAP, t = if(k == 0) 0 else 20)) }
			val card = Card(this, 24f)
			card.grow = 1.03f
			val c = Ui.col(this).apply { setPadding(34, 0, 34, 0); gravity = Gravity.CENTER_VERTICAL }
			c.addView(text(this, title, 32f, Ui.TEXT, W.SEMIBOLD)); c.addView(text(this, sub, 23f, Ui.MUTED), llp(WRAP, WRAP, t = 4))
			card.addView(c, FrameLayout.LayoutParams(MATCH, MATCH))
			card.setOnClickListener { action() }
			row!!.addView(card, llp(0, 140, 1f, l = if(k % 2 == 0) 0 else 24))
		}
		v.addView(grid, lp(1740, WRAP, 90, 230))
		v.addView(text(this, "Eyesmaxxing ${BuildConfig.VERSION_NAME} · ต้องใช้ทีวี/กล่อง Android 7 ขึ้นไป (แนะนำ Android 9+, แรม 2GB, Wi-Fi 5GHz หรือสาย LAN)",
			22f, Ui.DIM), lp(WRAP, WRAP, 90, 900))
		return v
	}

	// ------------------------------------------------------------------ live refresh, keys

	private fun startRefresh(r: Runnable) { stopRefresh(); refresher = r; main.post(r) }
	private fun stopRefresh() { refresher?.let { main.removeCallbacks(it) }; refresher = null }

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		keyCatcher?.let { if(it(event)) return true }
		val pv = padView
		if(pv != null && PadConfig.fromPad(event))
		{
			// light the button the game will get (after "แก้ปุ่มสลับ"), so the test shows the fix worked
			pv.key(PadConfig(this).mapKey(event.keyCode, event.device), event.action == KeyEvent.ACTION_DOWN)
			// while the controller picture is chosen, buttons only light up (except moving away from it)
			if(padCard?.isFocused == true && event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT && event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN)
				return true
		}
		if(event.action == KeyEvent.ACTION_UP && (event.keyCode == KeyEvent.KEYCODE_BACK) && tab > 0)
		{ showTab(0, Trans.RIGHT); return true }
		// menus: give Android the corrected button (on generic-driver PlayStation pads ✕ arrives as B = "back")
		if(PadConfig.fromPad(event))
		{
			val code = PadConfig(this).mapKey(event.keyCode, event.device)
			if(code != event.keyCode)
				return super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount,
					event.metaState, event.deviceId, event.scanCode, event.flags, event.source))
		}
		return super.dispatchKeyEvent(event)
	}


	override fun onDestroy()
	{
		stopRefresh()
		// the player closed the app (back out of the home screen / closed it): 4K mode and every connection end
		// with it; nothing reconnects until the app is opened again
		if(isFinishing) try { com.metallic.chiaki.hdmi.HdmiModeService.stop(this) } catch(_: Throwable) { }
		super.onDestroy()
	}

	companion object
	{
		const val KEY_ONBOARDED = "tv_onboarded"
		const val KEY_GAME = "tv_game"
		/** Set by the pairing screen: address of the PS5 just paired, so the next step (checklist) opens by itself. */
		const val KEY_JUST_PAIRED = "tv_just_paired"
		/** TV input id of the HDMI port the PS5 is plugged into (HDMI 4K mode), picked once. */
		const val KEY_PS5_INPUT = "tv_ps5_input"
		/** "stream" = play through the app, "hdmi" = PS5 on the TV's HDMI input with Thai drawn on top (link build). */
		const val KEY_PLAY_MODE = "tv_play_mode"
	}
}
