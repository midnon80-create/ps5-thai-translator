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

	// every full-screen page and pop-up panel on the stage. Animations do not run while the TV shows another app
	// (4K mode switches to the HDMI input), so "remove when the fade ends" never happened and pages piled up.
	private val pages = ArrayList<View>()
	private val openPanels = LinkedHashMap<View, () -> Unit>()

	private fun tipsHidden() = sp.getBoolean(KEY_HIDE_TIPS, false)

	private fun closePanels() { openPanels.values.toList().forEach { try { it() } catch(_: Throwable) { } }; openPanels.clear() }

	/** Removes a panel now when nothing is drawn, else after a short fade. */
	private fun dropPanel(panel: View, ms: Long)
	{
		openPanels.remove(panel)
		if(!panel.isShown || !hasWindowFocus()) { panel.animate().cancel(); root.removeView(panel); return }
		panel.animate().alpha(0f).setDuration(ms).withEndAction { root.removeView(panel) }.start()
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		stage = Stage(this)
		Sfx.init(this)
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
		if(sp.getBoolean(KEY_SHOW_PAD_OFF, false))
		{
			sp.edit().remove(KEY_SHOW_PAD_OFF).apply()
			main.postDelayed({ showPadOff { } }, 400)
		}
		com.metallic.chiaki.hdmi.HdmiModeService.lastProblem?.let { p ->
			com.metallic.chiaki.hdmi.HdmiModeService.lastProblem = null
			main.postDelayed({ explain4kStop(p) }, 600)
		}
		// just paired a PS5: go straight on to the pre-game checklist for it (the next step)
		sp.getString(KEY_JUST_PAIRED, null)?.let { addr ->
			sp.edit().remove(KEY_JUST_PAIRED).apply()
			main.postDelayed({
				val h = vm.displayHosts.value?.firstOrNull { it.host == addr && it.registeredHost != null }
					?: vm.displayHosts.value?.firstOrNull { it.registeredHost != null }
				if(h != null) preflight(h, h.registeredHost) else toast("จับคู่ PS5 สำเร็จ ✔ กดการ์ด PS5 เพื่อเล่น")
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
		// anything older than the page being replaced goes now
		pages.filter { it !== old && it !== v }.forEach { it.animate().cancel(); root.removeView(it) }
		pages.clear(); if(old != null) pages.add(old); pages.add(v)
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
			if(t == Trans.NONE || !root.isShown || !hasWindowFocus()) { old.animate().cancel(); root.removeView(old); pages.remove(old) }
			else old.animate().alpha(0f).translationX(if(t == Trans.RIGHT) 110f else -110f).setDuration(300)
				.setInterpolator(Ui.ease()).withEndAction { root.removeView(old); pages.remove(old) }.start()
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

	// ------------------------------------------------------------------ game picker (cover row, like the PS5 library)

	private enum class GameState { NEW, AVAILABLE, SOON }
	/** [poster] 0 = no bundled cover art: a generic purple/black cover with the name is drawn instead. */
	/** [date] = release day of a "coming soon" game, shown on its label (Thai date, Buddhist year). */
	private class Game(val id: String, val title: String, val poster: Int, val state: GameState, val date: String? = null)

	/** Bundled cover art (official key art, high resolution) for game ids the server may send (anything else gets the generic cover). */
	private val bundledPosters = mapOf("gta6" to R.drawable.poster_gta6, "aot3" to R.drawable.poster_aot3,
		"tombraider" to R.drawable.poster_tombraider, "ff7" to R.drawable.poster_ff7, "edge" to R.drawable.poster_edge,
		"007" to R.drawable.poster_007, "requiem" to R.drawable.poster_requiem, "mw4" to R.drawable.poster_mw4,
		"dawnwalker" to R.drawable.poster_dawnwalker, "crimson" to R.drawable.poster_crimson,
		"blackflag" to R.drawable.poster_blackflag, "onimusha" to R.drawable.poster_onimusha, "rdr2" to R.drawable.poster_rdr2)

	/** Wide art without the logo, behind the ซับไทย card on the home page (the picked game). */
	private val heroArt = mapOf("gta6" to R.drawable.hero_gta6, "007" to R.drawable.hero_007, "requiem" to R.drawable.hero_requiem,
		"mw4" to R.drawable.hero_mw4, "dawnwalker" to R.drawable.hero_dawnwalker, "crimson" to R.drawable.hero_crimson,
		"blackflag" to R.drawable.hero_blackflag, "onimusha" to R.drawable.hero_onimusha, "rdr2" to R.drawable.hero_rdr2)

	/** "Coming soon" teasers shown while the server doesn't list them as playable (soonest first). */
	private val soonGames = listOf(
		Game("edge", "Edge of Memories", R.drawable.poster_edge, GameState.SOON, "3 ธ.ค. 2569"),
		Game("aot3", "Attack on Titan 3", R.drawable.poster_aot3, GameState.SOON, "10 ธ.ค. 2569"),
		Game("tombraider", "Tomb Raider: Legacy of Atlantis", R.drawable.poster_tombraider, GameState.SOON, "12 ก.พ. 2570"),
		Game("ff7", "Final Fantasy VII Revelation", R.drawable.poster_ff7, GameState.SOON, "8 เม.ย. 2570"))

	/** The game picked in the picker (GTA VI if none / no longer listed). */
	private fun pickedGame(): Game = games.firstOrNull { it.id == sp.getString(KEY_GAME, DEFAULT_GAME) && it.state != GameState.SOON } ?: games.first()

	private val artCache = HashMap<String, android.graphics.Bitmap>()

	/**
	 * Big cover art decoded once, scaled to about [designW] (in the 1920-wide layout) times the real screen size:
	 * sharp on 4K screens, and light on memory for weak TVs (the full pictures are 1000+ px).
	 */
	private fun art(res: Int, designW: Int): android.graphics.Bitmap?
	{
		val px = (designW * resources.displayMetrics.widthPixels / 1920f * 1.15f).toInt().coerceIn(200, 1600)
		val key = "$res:$px"
		artCache[key]?.let { return it }
		return try
		{
			val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
			android.graphics.BitmapFactory.decodeResource(resources, res, o)
			var s = 1
			while(o.outWidth / (s * 2) >= px) s *= 2
			val full = android.graphics.BitmapFactory.decodeResource(resources, res, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
			val b = if(full.width > px) android.graphics.Bitmap.createScaledBitmap(full, px, (full.height * px.toFloat() / full.width).toInt(), true)
				.also { if(it !== full) full.recycle() } else full
			artCache[key] = b
			b
		}
		catch(_: Throwable) { null }
	}

	private fun artView(res: Int, designW: Int): android.widget.ImageView = android.widget.ImageView(this).apply {
		val b = art(res, designW)
		if(b != null) setImageBitmap(b) else setImageResource(res)
		scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
	}

	/**
	 * Order on the row: new releases first, then games available now, then coming soon.
	 * GTA VI is built in (works offline); the other playable games come from the server's GET /v1/games
	 * (last answer cached in [KEY_GAMES_CACHE]), so games the owner adds in the admin page show without an update.
	 */
	private fun buildGames(server: List<Pair<String, String>>): List<Game>
	{
		val extra = server.filter { it.first != DEFAULT_GAME }
		val ids = extra.map { it.first }.toSet()
		return listOf(Game(DEFAULT_GAME, "Grand Theft Auto VI", R.drawable.poster_gta6, GameState.NEW)) +
			extra.map { (id, name) -> Game(id, name, bundledPosters[id] ?: 0, GameState.AVAILABLE) } +
			soonGames.filter { it.id !in ids }
	}

	private fun cachedServerGames(): List<Pair<String, String>> = try
	{
		val arr = org.json.JSONArray(sp.getString(KEY_GAMES_CACHE, "[]"))
		(0 until arr.length()).map { i -> arr.getJSONObject(i).let { it.getString("id") to it.getString("name") } }
	}
	catch(_: Exception) { emptyList() }

	private var gameList: List<Game>? = null
	private val games: List<Game> get() = gameList ?: buildGames(cachedServerGames()).also { gameList = it }

	/** The picker on screen right now (to redraw it when a fresher list arrives) and whether it is the first-launch one. */
	private var gamesPage: View? = null
	private var gamesPageOnboarding = false
	@Volatile private var gamesFetchedAt = 0L

	/** Asks the server for its game list (at most once a minute); redraws the picker if the list changed. */
	private fun refreshGames()
	{
		val now = System.currentTimeMillis()
		if(now - gamesFetchedAt < 60_000) return
		gamesFetchedAt = now
		val url = TranslatorPrefs(this).serverUrl
		Thread {
			val list = CloudTranslator.games(url)
			// offline: keep the cached list / GTA VI, try again next time; an empty answer may be a server hiccup
			if(list.isNullOrEmpty()) { gamesFetchedAt = 0L; return@Thread }
			main.post {
				if(isFinishing || isDestroyed) return@post
				val arr = org.json.JSONArray()
				list.forEach { (id, name) -> arr.put(org.json.JSONObject().put("id", id).put("name", name)) }
				sp.edit().putString(KEY_GAMES_CACHE, arr.toString()).apply()
				// the picked game was switched off by the owner: back to GTA VI (the server would block it from AI)
				val picked = sp.getString(KEY_GAME, DEFAULT_GAME).orEmpty()
				if(picked != DEFAULT_GAME && list.none { it.first == picked }) sp.edit().putString(KEY_GAME, DEFAULT_GAME).apply()
				val fresh = buildGames(list)
				val changed = fresh.map { Triple(it.id, it.title, it.state) } != games.map { Triple(it.id, it.title, it.state) }
				gameList = fresh
				val gp = gamesPage
				if(changed && gp != null && gp.isAttachedToWindow && (page === gp || gp.parent === page))
				{
					if(gamesPageOnboarding) showGamePicker(Trans.NONE) else showTab(3, Trans.NONE)
				}
			}
		}.start()
	}

	/** Cover art: the bundled poster, or a generic purple/black cover with the game's name. */
	private fun posterArt(g: Game): View
	{
		if(g.poster != 0) return artView(g.poster, 440)
		val f = FrameLayout(this)
		f.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF3B1E78.toInt(), 0xFF1A0B2E.toInt(), Ui.BG))
		// soft violet glow behind the name
		f.addView(View(this).apply { background = GradientDrawable().apply {
			gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 260f
			colors = intArrayOf(0x668B5CF6, 0x008B5CF6) } }, FrameLayout.LayoutParams(MATCH, MATCH))
		val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(30, 70, 30, 30) }
		col.addView(text(this, g.title, 44f, Ui.TEXT, W.BOLD, Gravity.CENTER).apply {
			maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
			setShadowLayer(18f, 0f, 0f, Ui.GLOW) }, LinearLayout.LayoutParams(MATCH, WRAP))
		col.addView(View(this).apply { background = Ui.gradient(intArrayOf(Ui.VIOLET, Ui.GLOW), 3f) },
			LinearLayout.LayoutParams(120, 6).apply { topMargin = 26; gravity = Gravity.CENTER_HORIZONTAL })
		col.addView(text(this, "ซับไทย", 26f, Ui.VIOLET2, W.MEDIUM, Gravity.CENTER), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 18 })
		f.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
		return f
	}

	private fun ribbonText(g: Game) = when(g.state)
	{
		GameState.NEW -> "NEW RELEASE"
		GameState.AVAILABLE -> "พร้อมเล่น"
		GameState.SOON -> if(g.date != null) "เข้า ${g.date}" else "COMING SOON"
	}

	/** Height of the label row above each cover. */
	private val ribbonH = 62

	/** Status label (NEW RELEASE / พร้อมเล่น / release date) ABOVE the cover, never on top of the art. [tag] = the cover. */
	private fun posterCard(g: Game): FrameLayout
	{
		val f = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
		val cover = FrameLayout(this)
		cover.addView(posterArt(g), FrameLayout.LayoutParams(MATCH, MATCH))
		if(g.state == GameState.SOON) cover.addView(View(this).apply { setBackgroundColor(0x4D000000) }, FrameLayout.LayoutParams(MATCH, MATCH))
		cover.foreground = Ui.round(0, 18f, 3, 0xFF4338CA.toInt())
		Ui.clipRound(cover, 18f)
		f.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = ribbonH })
		val soon = g.state == GameState.SOON
		val ribbon = text(this, ribbonText(g), 23f, if(soon) 0xFFE9E3FF.toInt() else 0xFF1A0B2E.toInt(), W.BOLD, Gravity.CENTER).apply {
			background = if(soon) Ui.round(0xFF2A1F4D.toInt(), 24f, 2, 0xFF7C6BE0.toInt())
				else Ui.gradient(intArrayOf(0xFFFFE14D.toInt(), 0xFFFFB020.toInt()), 24f)
			setPadding(30, 0, 30, 0) }
		f.addView(ribbon, FrameLayout.LayoutParams(WRAP, 48, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
		f.tag = cover
		return f
	}

	/** Mirror of the cover under it, fading out (the "floor" of the PS5 library). */
	private fun reflection(g: Game): View
	{
		val f = FrameLayout(this)
		f.addView(posterArt(g).apply { scaleY = -1f; alpha = 0.32f }, FrameLayout.LayoutParams(MATCH, MATCH))
		f.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
			intArrayOf(0x3307050C, 0xF207050C.toInt())) }, FrameLayout.LayoutParams(MATCH, MATCH))
		return f
	}

	private fun gamesContent(onboarding: Boolean): View
	{
		val v = FrameLayout(this)
		gamesPage = v; gamesPageOnboarding = onboarding
		refreshGames()
		val games = games // one list for this screen (a fresher list redraws the screen)
		val top = if(onboarding) 140 else 120
		v.addView(text(this, "เลือกเกม", 44f, Ui.TEXT, W.BOLD), lp(WRAP, WRAP, 90, top))
		// three sections on one row; the buttons jump to each part
		val sections = listOf(GameState.NEW to "ใหม่ล่าสุด", GameState.AVAILABLE to "มีให้เล่นแล้ว", GameState.SOON to "เร็ว ๆ นี้")
		val chips = Ui.row(this)
		// start on the game picked last time
		var focus = games.indexOfFirst { it.id == sp.getString(KEY_GAME, DEFAULT_GAME) }.coerceAtLeast(0)
		lateinit var layoutRow: (Boolean) -> Unit
		val chipCards = ArrayList<Card>()
		sections.forEachIndexed { k, (state, label) ->
			val c = Card(this, 26f) {
				val g = games[focus].state
				state == g || (state == GameState.AVAILABLE && g == GameState.NEW && games.none { it.state == GameState.AVAILABLE })
			}
			c.grow = 1.05f
			c.addView(text(this, label, 24f, Ui.TEXT, W.MEDIUM, Gravity.CENTER), FrameLayout.LayoutParams(MATCH, MATCH))
			c.setOnClickListener {
				val i = games.indexOfFirst { it.state == state }.let { if(it < 0) 0 else it }
				focus = i; layoutRow(true)
			}
			chips.addView(c, llp(220, 60, l = if(k == 0) 0 else 16))
			chipCards += c
		}
		v.addView(chips, lp(WRAP, WRAP, 1920 - 90 - 3 * 220 - 2 * 16, top + 8))

		// the row
		val stage = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
		val cw = 400; val ch = 500 + ribbonH // cover 400x500 + its label row above
		val cards = games.map { g -> posterCard(g) }
		val refl = games.map { g -> reflection(g) }
		val rowTop = top + 104
		games.indices.forEach { i ->
			stage.addView(refl[i], FrameLayout.LayoutParams(cw, 120).apply { topMargin = rowTop + ch + 10 })
			stage.addView(cards[i], FrameLayout.LayoutParams(cw, ch).apply { topMargin = rowTop })
			cards[i].cameraDistance = 9000f * resources.displayMetrics.density
			refl[i].cameraDistance = 9000f * resources.displayMetrics.density
		}
		v.addView(stage, FrameLayout.LayoutParams(1920, 1080))
		val title = text(this, "", 40f, Ui.TEXT, W.BOLD, Gravity.CENTER)
		val sub = text(this, "", 26f, Ui.MUTED, gravity = Gravity.CENTER)
		v.addView(title, lp(1920, WRAP, 0, rowTop + ch + 36))
		v.addView(sub, lp(1920, WRAP, 0, rowTop + ch + 92))

		layoutRow = { animate ->
			games.indices.forEach { i ->
				val d = i - focus
				val sel = d == 0
				val x = 960 - cw / 2 + d * 330 + (if(d > 0) 90 else if(d < 0) -90 else 0)
				val rot = if(sel) 0f else if(d > 0) -38f else 38f
				val sc = if(sel) 1.08f else 0.86f
				val a = if(kotlin.math.abs(d) > 3) 0f else 1f
				for((view, extraY) in listOf(cards[i] to 0f, refl[i] to 0f))
				{
					view.pivotX = cw / 2f; view.pivotY = if(view === cards[i]) ch / 2f else 0f
					if(animate) view.animate().translationX(x.toFloat()).rotationY(rot).scaleX(sc).scaleY(if(view === cards[i]) sc else sc).alpha(a)
						.translationY(extraY).setDuration(320).setInterpolator(Ui.ease()).start()
					else { view.translationX = x.toFloat(); view.rotationY = rot; view.scaleX = sc; view.scaleY = sc; view.alpha = a }
				}
				cards[i].elevation = if(sel) 40f else 10f - kotlin.math.abs(d)
				(cards[i].tag as View).foreground = Ui.round(0, 18f, if(sel) 6 else 3, if(sel) 0xFFC4B5FD.toInt() else 0xFF4338CA.toInt())
			}
			val g = games[focus]
			title.text = g.title
			sub.text = when(g.state) { GameState.SOON -> "เข้าวันที่ ${g.date ?: "เร็ว ๆ นี้"} · ซับไทยพร้อมตั้งแต่วันเกมออก"; else -> "ซับไทยพร้อม ✔ · กด ✕ เพื่อเลือก" }
			sub.setTextColor(if(g.state == GameState.SOON) Ui.MUTED else Ui.OK)
			chipCards.forEach { it.refresh() }
			if(animate) Sfx.play(Sfx.S.TICK)
		}
		layoutRow(false)

		fun pick()
		{
			val g = games[focus]
			if(g.state == GameState.SOON) { Sfx.play(Sfx.S.WARN); toast("${g.title} · เข้า ${g.date ?: "เร็ว ๆ นี้"}"); return }
			sp.edit().putString(KEY_GAME, g.id).putString(KEY_GAME_NAME, g.title).apply()
			Sfx.play(Sfx.S.CONFIRM)
			if(onboarding) { sp.edit().putBoolean(KEY_ONBOARDED, true).apply(); showTab(0, Trans.LEFT) }
			else { toast("เลือก ${g.title} แล้ว ✔"); showTab(0, Trans.RIGHT) }
		}
		// one focusable strip: ◀ ▶ move, ✕ picks, ▲ goes to the section buttons, ▼ to the tab bar
		val focusStrip = View(this).apply { isFocusable = true; isClickable = true }
		v.addView(focusStrip, lp(cw, ch, 960 - cw / 2, rowTop))
		focusStrip.setOnClickListener { pick() }
		focusStrip.setOnKeyListener { _, k, e ->
			if(e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
			when(k)
			{
				KeyEvent.KEYCODE_DPAD_LEFT -> { if(focus > 0) { focus--; layoutRow(true) }; true }
				KeyEvent.KEYCODE_DPAD_RIGHT -> { if(focus < games.size - 1) { focus++; layoutRow(true) }; true }
				KeyEvent.KEYCODE_DPAD_UP -> { chipCards[sections.indexOfFirst { it.first == games[focus].state }.coerceAtLeast(0)].requestFocus(); true }
				KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> { pick(); true }
				else -> false
			}
		}
		// mouse wheel / touch: tap a side cover to bring it to the front
		cards.forEachIndexed { i, c -> c.setOnClickListener { if(i == focus) pick() else { focus = i; layoutRow(true) } } }
		v.post { focusStrip.requestFocus() }
		return v
	}

	private fun showGamePicker(t: Trans)
	{
		keyCatcher = null
		val v = gamesContent(true) as FrameLayout
		v.addView(Ui.brand(this), lp(WRAP, WRAP, 64, 44))
		v.addView(Ui.hint(this, listOf("◀ ▶" to "เลื่อน", "✕" to "เลือก")), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 42 })
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
		closePanels()
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
		// from the content, "down" always reaches the tab bar; from the tab bar "up" goes back into the content
		navCards.forEach { it.nextFocusUpId = View.NO_ID }
		tab = i
		if(t == Trans.LEFT || t == Trans.RIGHT) Sfx.play(Sfx.S.PAGE)
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
		// the picked game's own art (sharp, high resolution) instead of one GTA shot for every game
		val picked = pickedGame()
		sp.edit().putString(KEY_GAME_NAME, picked.title).apply()
		val heroRes = heroArt[picked.id]
		val img: View = when
		{
			heroRes != null -> artView(heroRes, 940)
			picked.poster != 0 -> artView(picked.poster, 940)
			else -> posterArt(picked)
		}
		val imgBox = FrameLayout(this); imgBox.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
		imgBox.alpha = 0.9f
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
		// which game the subtitles are for (change it in เลือกเกม)
		val gameTag = text(this, "เกม · " + picked.title, 28f, Ui.TEXT, W.SEMIBOLD).apply {
			maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
			background = Ui.round(0xC0120A22.toInt(), 30f, 2, Ui.VIOLET2); setPadding(28, 12, 28, 14) }
		toggle.addView(gameTag, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(50, 60, 270, 0) })
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
			Sfx.play(if(on) Sfx.S.ON else Sfx.S.OFF)
		}
		v.addView(toggle, lp(939, 790, 90, 150))

		// right column: PS5, status, note
		val col = Ui.col(this)
		val hostsBox = Ui.col(this)
		col.addView(hostsBox, llp(MATCH, WRAP))
		val status = Ui.col(this)
		val tiles = HashMap<String, TextView>()
		val subs = HashMap<String, TextView>()
		var checkCard: Card? = null
		for(r in 0 until 2)
		{
			val row = Ui.row(this)
			for(k in 0 until 2)
			{
				val key = listOf("check", "ai", "pic", "band")[r * 2 + k]
				val inner = Ui.col(this).apply { setPadding(26, 20, 26, 20) }
				val t1 = text(this, "", 26f, Ui.TEXT); val t2 = text(this, "", 21f, Ui.MUTED)
				inner.addView(t1); inner.addView(t2, llp(WRAP, WRAP, t = 2))
				tiles[key] = t1; subs[key] = t2
				val cell: View = if(key == "check")
				{
					// "เช็กก่อนเข้าเกม": everything the game needs, in one place (shakes when something is missing)
					Card(this, 22f, Ui.PANEL).apply {
						grow = 1.04f
						addView(inner, FrameLayout.LayoutParams(MATCH, MATCH))
						setOnClickListener { openChecklist() }
					}.also { checkCard = it }
				}
				else inner.apply { background = Ui.round(Ui.PANEL, 22f, 2, Ui.LINE) }
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
		quit.addView(text(this, "เลิกเล่น", 34f, Ui.TEXT, W.BOLD, Gravity.CENTER), FrameLayout.LayoutParams(MATCH, MATCH))
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
			aiCheck = Pair(System.currentTimeMillis(), pr)
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
		var lastShake = 0L
		val tick = object : Runnable { override fun run() {
			val checks = quickChecks()
			val bad = checks.values.count { it == false }
			if(bad == 0) setTile("check", true, "เช็กครบ พร้อมเล่น", if(checks.values.any { it == null }) "กำลังตรวจ… · กดดูรายการ" else "เช็กก่อนเข้าเกม · กดดูรายการ")
			else
			{
				setTile("check", false, "ยังไม่ครบ $bad ข้อ", "เช็กก่อนเข้าเกม · กดดูและแก้")
				// a little shake every few seconds so nobody misses it
				val now = System.currentTimeMillis()
				val c = checkCard
				if(c != null && now - lastShake > 4000 && c.isShown)
				{
					lastShake = now
					Sfx.play(Sfx.S.WARN)
					android.animation.ObjectAnimator.ofFloat(c, "translationX", 0f, -16f, 16f, -12f, 12f, -6f, 6f, 0f).setDuration(560).start()
				}
			}
			main.postDelayed(this, 1500) } }
		startRefresh(tick)
		return v
	}

	/** Stops everything that talks to the PS5 (HDMI mode too) and closes the app. */
	private fun quitApp()
	{
		try { if(com.metallic.chiaki.hdmi.HdmiModeService.running) com.metallic.chiaki.hdmi.HdmiModeService.stop(this, "user:เลิกเล่น") } catch(_: Throwable) { }
		try { vm.discoveryManager.active = false } catch(_: Throwable) { }
		// Android does not let apps switch a Bluetooth controller off: tell the player the one-button way, and to keep
		// the TV's Bluetooth on (turning it off also cuts the TV remote)
		showPadOff { finishAffinity() }
	}

	/** "เลิกเล่นแล้ว" box: how to switch the controller off without touching the TV's Bluetooth. */
	private fun showPadOff(done: () -> Unit)
	{
		if(tipsHidden()) { toast("เลิกเล่นแล้ว ✔"); main.postDelayed({ done() }, 900); return }
		if(Diag.gamepads().isEmpty()) { toast("เลิกเล่นแล้ว ✔"); main.postDelayed({ done() }, 900); return }
		val d = android.app.AlertDialog.Builder(this)
			.setTitle("เลิกเล่นแล้ว ✔")
			.setMessage("1. ปิดจอย: กดปุ่ม PS (ปุ่มกลางจอย) ค้างไว้ประมาณ 10 วินาที จนไฟที่จอยดับ\n\n" +
				"2. ห้ามปิดบลูทูธของทีวี: รีโมททีวีใช้บลูทูธเหมือนกัน ถ้าปิด รีโมทจะใช้ไม่ได้\n\n" +
				"ถ้าเผลอปิดบลูทูธไปแล้ว: ใช้แอป Google TV ในมือถือ (Wi-Fi เดียวกับทีวี) เป็นรีโมท › ตั้งค่า › รีโมทและอุปกรณ์เสริม › เปิดบลูทูธ")
			.setPositiveButton("ตกลง") { _, _ -> done() }
			.setOnCancelListener { done() }
			.show()
		try
		{
			d.findViewById<TextView>(android.R.id.message)?.apply { textSize = 19f; setLineSpacing(0f, 1.25f) }
			d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply { textSize = 20f; isAllCaps = false; requestFocus() }
		}
		catch(_: Throwable) { }
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
			if(sp.getString(KEY_PLAY_MODE, "stream") == "hdmi") { goToPs5Input(); return }
			// the player switched to playing through the app (1080p): 4K mode must not keep pulling the TV to HDMI
			com.metallic.chiaki.hdmi.HdmiModeService.stop(this, "user:เปลี่ยนเป็นเล่นผ่านแอป")
			toast("หยุดโหมด 4K แล้ว · กำลังเตรียมเล่นผ่านแอป")
		}

		play(host, registeredHost)
	}

	/** Name of the picked game, for "connecting to …" messages. */
	private fun gameName() = sp.getString(KEY_GAME_NAME, null) ?: "Grand Theft Auto VI"

	/**
	 * PS5 card pressed: straight into the game, no checklist and no HDMI question (the "เช็กก่อนเข้าเกม" tile on the
	 * home page holds those, and shakes when something is missing). Only what cannot work stops here.
	 */
	private fun play(host: DisplayHost, registeredHost: RegisteredHost)
	{
		val hdmi = sp.getString(KEY_PLAY_MODE, "stream") == "hdmi"
		if(hdmi && !com.metallic.chiaki.hdmi.HdmiModeService.canDrawOverlays(this))
		{
			toast("โหมด 4K ยังไม่ได้อนุญาตแสดงทับ · แก้ตามรายการนี้ก่อน")
			preflight(host, registeredHost); return
		}
		fun go(h: DisplayHost)
		{
			toast("กำลังเชื่อมต่อ PS5 · เกม " + gameName())
			Sfx.play(Sfx.S.CONFIRM)
			if(hdmi) startHdmi(h, registeredHost) else connect(h, registeredHost)
		}
		if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
		{
			// asleep: wake it, then go in by itself once it answers
			vm.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
			toast("กำลังปลุก PS5 · รอสักครู่ แล้วจะเข้าเกมเอง")
			val started = System.currentTimeMillis()
			main.postDelayed(object : Runnable { override fun run() {
				if(isFinishing || isDestroyed) return
				val cur = vm.displayHosts.value?.firstOrNull { it.host == host.host && it.registeredHost != null }
				if(cur is DiscoveredDisplayHost && cur.discoveredHost.state == DiscoveryHost.State.READY) { go(cur); return }
				if(System.currentTimeMillis() - started > 40_000) { toast("ปลุก PS5 ไม่สำเร็จ · ดูวิธีแก้ในรายการ"); preflight(host, registeredHost); return }
				main.postDelayed(this, 1000)
			} }, 1500)
			return
		}
		go(host)
	}

	/** Home tile "เช็กก่อนเข้าเกม": the full checklist for the PS5 that is paired (or what is missing). */
	private fun openChecklist()
	{
		val h = vm.displayHosts.value?.firstOrNull { it.registeredHost != null }
		preflight(h, h?.registeredHost)
	}

	/** Quick version of the checklist for the home tile. null = not known yet (still checking). */
	private fun quickChecks(): Map<String, Boolean?>
	{
		val hdmi = sp.getString(KEY_PLAY_MODE, "stream") == "hdmi"
		val m = LinkedHashMap<String, Boolean?>()
		m["pad"] = if(hdmi) com.metallic.chiaki.hdmi.HdmiModeService.canDrawOverlays(this) else Diag.gamepads().isNotEmpty()
		m["subs"] = TranslatorPrefs(this).enabled
		m["ai"] = aiCheck?.second?.let { it == CloudTranslator.Problem.NONE || it == CloudTranslator.Problem.DAILY_LIMIT || it == CloudTranslator.Problem.POOL_EMPTY }
		val h = vm.displayHosts.value?.firstOrNull { it.registeredHost != null }
		m["ps5"] = when(h)
		{
			null -> false
			is DiscoveredDisplayHost -> h.discoveredHost.state == DiscoveryHost.State.READY
			is ManualDisplayHost -> true
			else -> false
		}
		if(hdmi) m["hdmi"] = hdmiInputs().let { ins -> ins.isEmpty() || ins.any { it.id == sp.getString(KEY_PS5_INPUT, null) } }
		return m
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
		toast("เริ่มโหมด 4K แล้ว ✔ เกม ${gameName()} · กำลังเปลี่ยนไปช่อง HDMI ของ PS5…")
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

	/**
	 * 4K mode started (or already running): straight to the PS5's HDMI input picked before (in the checklist).
	 * Only asks when none was picked yet, or the TV does not let apps switch the input (then it says how).
	 */
	private fun goToPs5Input()
	{
		val saved = sp.getString(KEY_PS5_INPUT, null)
		val ins = hdmiInputs()
		if(saved != null && ins.any { it.id == saved } && openHdmiInput(saved)) return
		hdmiPicker()
	}

	/**
	 * Full-screen page: "which HDMI input is the PS5 on?". Every input is a big card (signal / last used / sound-bar port
	 * marked), with ย้อนกลับ and หยุดโหมด 4K buttons. Nothing is picked automatically.
	 */
	private fun hdmiPicker(chooseOnly: Boolean = false, onClose: (() -> Unit)? = null)
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
		fun close() { if(closed) return; closed = true; Sfx.play(Sfx.S.CLOSE); keyCatcher = null; dropPanel(panel, 160); onClose?.invoke() }
		openPanels[panel] = { close() }
		var focus: View? = null
		var row: LinearLayout? = null
		inputs.forEachIndexed { k, inp ->
			if(k % 3 == 0) { row = Ui.row(this); box.addView(row, llp(1200, WRAP, t = if(k == 0) 26 else 16)) }
			val card = Card(this, 24f, Ui.PANEL) { inp.id == saved }
			card.grow = 1.05f
			val c = Ui.col(this).apply { setPadding(26, 16, 22, 16); gravity = Gravity.CENTER_VERTICAL }
			// long input names ("HDMI 3 (eARC/ARC)") shrink to fit one line instead of wrapping out of the card
			val name = text(this, inp.label, 34f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER_VERTICAL).apply { maxLines = 1; includeFontPadding = false }
			androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(name, 20, 34, 1, android.util.TypedValue.COMPLEX_UNIT_PX)
			c.addView(name, llp(MATCH, 50))
			// status as small coloured chips on one row under the name
			val tags = ArrayList<Pair<String, Int>>()
			tags += if(inp.live) "● มีสัญญาณ" to Ui.OK else "○ ไม่มีสัญญาณ" to Ui.DIM
			if(inp.id == saved) tags += "ใช้ครั้งก่อน" to Ui.VIOLET2
			if(inp.arc) tags += "ช่องลำโพง?" to Ui.WARN
			val chips = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
			tags.forEachIndexed { i, (t, col) ->
				chips.addView(text(this, t, 19f, col, W.MEDIUM, Gravity.CENTER).apply {
					maxLines = 1; includeFontPadding = false
					background = Ui.round((col and 0x00FFFFFF) or 0x24000000, 16f, 2, (col and 0x00FFFFFF) or 0x66000000.toInt())
					setPadding(14, 0, 14, 0) }, llp(WRAP, 32, l = if(i == 0) 0 else 8))
			}
			c.addView(chips, llp(MATCH, WRAP, t = 8))
			card.addView(c, FrameLayout.LayoutParams(MATCH, MATCH))
			card.setOnClickListener {
				sp.edit().putString(KEY_PS5_INPUT, inp.id).apply()
				close()
				if(chooseOnly) toast("บันทึกแล้ว ✔ PS5 อยู่ช่อง ${inp.label} · ครั้งต่อไปเข้าเกมได้เลย")
				else if(!openHdmiInput(inp.id)) toast("เปิดช่องนี้ไม่ได้ · ใช้ปุ่ม Input ที่รีโมททีวีแทน")
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
		if(com.metallic.chiaki.hdmi.HdmiModeService.running) buttons.addView(button("หยุดโหมด 4K", false) {
			close(); com.metallic.chiaki.hdmi.HdmiModeService.stop(this, "user:กดหยุดโหมด 4K"); toast("หยุดโหมด 4K แล้ว · เล่นผ่านแอปได้ตามปกติ") }, llp(0, 92, 1f, l = 18))
		box.addView(buttons, llp(1200, WRAP, t = 30))
		box.addView(text(this, "เล่นด้วยจอยที่ต่อ PS5 · ซับไทยขึ้นทับภาพเอง (เปิด Subtitles ในเกม) · เลิกเล่น: กลับมาที่แอป › การ์ด \"เลิกเล่น\"",
			21f, Ui.DIM, gravity = Gravity.CENTER), llp(1200, WRAP, t = 16))
		panel.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
		root.addView(panel, FrameLayout.LayoutParams(1920, 1080))
		panel.alpha = 0f; panel.animate().alpha(1f).setDuration(200).start()
		Sfx.play(Sfx.S.OPEN)
		keyCatcher = { e -> if((e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B) && !PadConfig.fromPad(e)) { if(e.action == KeyEvent.ACTION_UP) close(); true } else false }
		val f = focus ?: back
		main.post { f.requestFocus() }
	}

	/** Why 4K mode stopped, and what to do (shown when the player comes back to the app). */
	private fun explain4kStop(p: String)
	{
		val (title, steps) = when
		{
			p.contains("PIN") -> "PS5 ขอรหัส PIN ผู้ใช้" to listOf(
				"ที่ PS5: ตั้งค่า › ผู้ใช้และบัญชี › การตั้งค่าการเข้าสู่ระบบ › ปิด \"รหัสผ่านเข้าสู่ระบบ PS5\"",
				"กลับมาที่แอป กดการ์ด PS5 เพื่อเริ่มใหม่")
			p.contains("หลุด") -> "การเชื่อมต่อกับ PS5 หลุด" to listOf(
				"PS5 ต้องเปิดอยู่ (ไม่ใช่โหมดพักหรือปิดเครื่อง)",
				"ปิด Remote Play ในมือถือ คอม หรือ PS Portal ที่อาจเปิดค้างอยู่ (PS5 ต่อได้ทีละเครื่อง)",
				"ทีวีกับ PS5 ต้องใช้เน็ตบ้านวงเดียวกัน แนะนำ PS5 ต่อสาย LAN",
				"กดการ์ด PS5 เพื่อเริ่มโหมด 4K ใหม่")
			p.contains("อัตโนมัติ") -> "โหมด 4K หยุดเอง" to listOf(
				"ไม่มีซับเกมนานเกินไป (เช่น เปิดทิ้งไว้ที่หน้าโฮม PS5 หรือไปดูช่องอื่น) แอปเลยหยุดให้เพื่อไม่ให้มีอะไรค้างบนจอ",
				"เล่นต่อ: กดการ์ด PS5 อีกครั้ง")
			p.contains("แสดงทับ") -> "ยังไม่ได้อนุญาตให้แสดงทับแอปอื่น" to listOf(
				"ตั้งค่าทีวี › แอป › สิทธิ์เข้าถึงพิเศษของแอป › แสดงทับแอปอื่น › เปิดให้ Eyesmaxxing",
				"ทีวีบางรุ่นไม่มีเมนูนี้ = ใช้โหมด 4K ไม่ได้ ให้เล่นแบบผ่านแอป 1080p")
			else -> "โหมด 4K หยุดทำงาน" to listOf(p, "กดการ์ด PS5 เพื่อเริ่มใหม่ · ถ้ายังไม่ได้ ปิด-เปิดแอปหนึ่งครั้ง")
		}
		guide(title, steps, null) { }
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
	private fun preflight(host0: DisplayHost?, registeredHost0: RegisteredHost?)
	{
		val panel = FrameLayout(this).apply { setBackgroundColor(0xEE07050C.toInt()); isClickable = true }
		val box = Ui.col(this).apply { background = Ui.round(Ui.PANEL2, 36f, 3, Ui.VIOLET2); setPadding(64, 48, 64, 48) }
		box.addView(text(this, "เช็กก่อนเข้าเกม", 46f, Ui.TEXT, W.BOLD))
		val intro = text(this, "ต้องผ่านครบ 4 ข้อ ซับไทยถึงจะขึ้น · ข้อไหนยังไม่ผ่าน กดที่ข้อนั้นเพื่อแก้", 24f, Ui.MUTED)
		box.addView(intro, llp(WRAP, WRAP, t = 6))
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
		fun currentHost(): DisplayHost? = vm.displayHosts.value?.firstOrNull { (host0 == null || it.host == host0.host) && it.registeredHost != null } ?: host0
		fun reg(): RegisteredHost? = registeredHost0 ?: currentHost()?.registeredHost
		val fixes = HashMap<String, () -> Unit>()
		for(key in listOf("pad", "subs", "ai", "ps5", "hdmi"))
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
			box.addView(card, llp(1100, 94, t = 12))
			rows[key] = Row(card, mark, t1, t2)
		}
		val go = Card(this, 30f); go.grow = 1.04f
		val goText = text(this, "", 32f, Ui.TEXT, W.BOLD, Gravity.CENTER)
		go.addView(goText, FrameLayout.LayoutParams(MATCH, MATCH))
		val goRow = Ui.row(this)
		val cancel = Card(this, 30f); cancel.grow = 1.04f
		cancel.addView(text(this, "← ย้อนกลับ", 30f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER), FrameLayout.LayoutParams(MATCH, MATCH))
		goRow.addView(cancel, llp(260, 100))
		val tipsBtn = Card(this, 30f); tipsBtn.grow = 1.04f
		val tipsText = text(this, "", 24f, Ui.MUTED, W.MEDIUM, Gravity.CENTER)
		tipsBtn.addView(tipsText, FrameLayout.LayoutParams(MATCH, MATCH))
		fun paintTips() { tipsText.text = if(tipsHidden()) "แสดงคำแนะนำ" else "ซ่อนคำแนะนำ" }
		paintTips()
		goRow.addView(tipsBtn, llp(260, 100, l = 20))
		goRow.addView(go, llp(0, 100, 1f, l = 20))
		box.addView(goRow, llp(1100, WRAP, t = 22))
		// the PS5 draws this banner itself (on its HDMI picture), the app cannot remove it: the PS5 setting can
		box.addView(text(this, "แถบ \"เชื่อมต่อกับการเล่นระยะไกลแล้ว\" ค้างบนจอ = ข้อความของ PS5 เอง · ปิดได้ที่ PS5: ตั้งค่า › ระบบ › การเล่นระยะไกล › เปิด \"ซ่อนป๊อปอัปสถานะการเชื่อมต่อการเล่นระยะไกล\"",
			20f, Ui.DIM).apply { setLineSpacing(0f, 1.2f) }, llp(1100, WRAP, t = 14))
		panel.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
		root.addView(panel, FrameLayout.LayoutParams(1920, 1080))
		panel.alpha = 0f; panel.animate().alpha(1f).setDuration(220).start()
		Sfx.play(Sfx.S.OPEN)

		var closed = false
		lateinit var tick: Runnable
		fun close() { if(closed) return; closed = true; Sfx.play(Sfx.S.CLOSE); main.removeCallbacks(tick); keyCatcher = null; dropPanel(panel, 180) }
		openPanels[panel] = { close() }
		fun set(key: String, good: Boolean?, title: String, sub: String)
		{
			val r = rows[key] ?: return
			r.mark.text = when(good) { true -> "✔"; false -> "✕"; null -> "…" }
			r.mark.setTextColor(when(good) { true -> Ui.OK; false -> Ui.WARN; null -> Ui.MUTED })
			r.title.text = title; r.sub.text = sub
			// tips hidden: only lines that still need fixing keep their explanation
			r.sub.visibility = if(tipsHidden() && good != false) View.GONE else View.VISIBLE
			intro.visibility = if(tipsHidden()) View.GONE else View.VISIBLE
		}
		var allOk = false
		cancel.setOnClickListener { close() }
		tipsBtn.setOnClickListener { sp.edit().putBoolean(KEY_HIDE_TIPS, !tipsHidden()).apply(); paintTips(); tick.run() }
		go.setOnClickListener {
			if(!allOk) { toast("ยังไม่ครบ · กดที่ข้อที่มี ✕ เพื่อแก้ก่อน"); return@setOnClickListener }
			val h = currentHost(); val r = reg()
			close()
			if(h == null || r == null) return@setOnClickListener
			toast("กำลังเชื่อมต่อ PS5 · เกม " + gameName())
			if(hdmi()) startHdmi(h, r) else connect(h, r)
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
		lateinit var myKeys: (KeyEvent) -> Boolean
		fixes["hdmi"] = { hdmiPicker(chooseOnly = true) { keyCatcher = myKeys; rows["hdmi"]?.card?.requestFocus(); tick.run() } }
		fixes["ps5"] = {
			val h = currentHost()
			val r = reg()
			when
			{
				h == null || r == null -> guide("ยังไม่ได้จับคู่ PS5", listOf(
					"เปิด PS5 ให้อยู่หน้าหลัก และต่อเน็ตบ้านวงเดียวกับทีวี",
					"กด \"จับคู่ PS5\" ด้านล่าง แล้วทำตามขั้นตอน (ทำครั้งเดียว)"), "จับคู่ PS5") {
					startActivity(Intent(this, RegistActivity::class.java)) }
				ready("ps5") -> toast("PS5 พร้อมแล้ว ✔")
				h is DiscoveredDisplayHost && h.discoveredHost.state == DiscoveryHost.State.STANDBY -> guide("PS5 อยู่โหมดพัก", listOf(
					"กด \"ปลุก PS5\" ด้านล่าง แล้วรอประมาณ 10 วินาที",
					"ถ้าปลุกไม่ได้: ที่ PS5 ไปที่ ตั้งค่า › ระบบ › ประหยัดพลังงาน › ฟีเจอร์ในโหมดพัก › เปิด \"คงการเชื่อมต่ออินเทอร์เน็ต\" และ \"เปิด PS5 จากเครือข่าย\"",
					"หรือกดปุ่มเปิดที่ตัว PS5 / ปุ่ม PS ที่จอยที่ต่อกับ PS5"),
					"ปลุก PS5") { vm.discoveryManager.sendWakeup(h.host, r.rpRegistKey, r.target.isPS5); wakeSent = true; tick.run() }
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
				h == null -> set("ps5", false, "ยังไม่ได้จับคู่ PS5", "กดที่นี่เพื่อจับคู่ (ทำครั้งเดียว)")
				h is DiscoveredDisplayHost && h.discoveredHost.state == DiscoveryHost.State.READY ->
					set("ps5", true, "เจอ " + (h.name ?: "PS5") + " แล้ว", "เปิดอยู่ · อยู่เน็ตบ้านเดียวกับทีวี")
				h is DiscoveredDisplayHost && h.discoveredHost.state == DiscoveryHost.State.STANDBY ->
					set("ps5", false, if(wakeSent) "กำลังปลุก PS5…" else "PS5 อยู่โหมดพัก", if(wakeSent) "รอประมาณ 10 วินาที" else "กดที่นี่เพื่อปลุก PS5")
				// added by IP address: discovery cannot see it (other subnet), so it cannot be checked here
				h is ManualDisplayHost -> set("ps5", true, (h.name ?: "PS5") + " (เพิ่มด้วย IP)", "ตรวจสถานะล่วงหน้าไม่ได้ · ให้ PS5 เปิดอยู่หน้าหลัก")
				else -> set("ps5", false, "ยังไม่เจอ PS5", "เปิด PS5 ให้อยู่หน้าหลัก · ทีวีกับ PS5 ต้องใช้เน็ตบ้านวงเดียวกัน")
			}
			// 5. HDMI 4K mode: which HDMI input the PS5 is on (asked here once, not before every game)
			val hr = rows["hdmi"]!!
			if(hdmi())
			{
				hr.card.visibility = View.VISIBLE
				val ins = hdmiInputs()
				val m = ins.firstOrNull { it.id == sp.getString(KEY_PS5_INPUT, null) }
				when
				{
					ins.isEmpty() -> set("hdmi", true, "เปลี่ยนไปช่อง PS5 ด้วยรีโมททีวี", "ทีวีนี้ไม่ให้แอปเปลี่ยนช่องเอง · กด Input / Source แล้วเลือกช่องที่เสียบ PS5")
					m != null -> set("hdmi", true, "PS5 อยู่ช่อง " + m.label, "กดที่นี่ถ้าต้องการเปลี่ยนช่อง")
					else -> set("hdmi", false, "ยังไม่ได้เลือกช่อง HDMI ของ PS5", "กดที่นี่แล้วเลือกช่องที่เสียบ PS5 (เลือกครั้งเดียว)")
				}
			}
			else { hr.card.visibility = View.GONE; hr.mark.text = "✔" }
			allOk = rows.values.all { it.mark.text == "✔" } && currentHost() != null
			goText.text = if(allOk) "เริ่มเล่น ▶" else "ยังไม่ครบ ${rows.values.count { it.mark.text != "✔" }} ข้อ"
			go.alpha = if(allOk) 1f else 0.55f
			main.postDelayed(this, 1500)
		} }
		myKeys = { e -> if(e.keyCode == KeyEvent.KEYCODE_BACK && !PadConfig.fromPad(e)) { if(e.action == KeyEvent.ACTION_UP) close(); true } else false }
		keyCatcher = myKeys
		tick.run()
		main.post { (rows.values.firstOrNull { it.mark.text == "✕" }?.card ?: go).requestFocus() }
	}

	private fun connect(host: DisplayHost, registeredHost: RegisteredHost)
	{
		val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
		startActivity(Intent(this, StreamActivity::class.java).putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo))
	}

	// ---- tab 1: ตั้งค่าซับ
	// Left: the topics (yellow headings, current choice beside them). Pressing a topic opens its choices in the same
	// place (← กลับ to go back). Right: only the preview; it follows the choice the remote is on before it is picked.
	private class Opt(val label: String, val sub: String?, val value: String)
	private class Topic(val title: String, val key: String, val def: String, val opts: List<Opt>, val about: String)

	private fun settingsContent(): View
	{
		val v = FrameLayout(this)
		val draft = HashMap<String, String>() // choice the remote is on (preview only)
		fun cur(t: Topic) = draft[t.key] ?: when(t.key)
		{
			TranslatorPrefs.KEY_SHOW_PREVIOUS -> if(sp.getBoolean(t.key, true)) "1" else "0"
			TranslatorPrefs.KEY_MODE -> when(TranslatorPrefs(this).mode) { TranslatorPrefs.Mode.BOTH -> "both"; TranslatorPrefs.Mode.SPEECH -> "speech"; else -> "screen" }
			KEY_PLAY_MODE -> sp.getString(KEY_PLAY_MODE, "stream") ?: "stream"
			else -> sp.getString(t.key, t.def) ?: t.def
		}
		val topics = listOf(
			Topic("แปลจาก", TranslatorPrefs.KEY_MODE, "screen", listOf(
				Opt("อ่านซับบนจอ", "แนะนำ · ลื่นสุด · ต้องเปิด Subtitles ในเกม", "screen"),
				Opt("ฟังเสียง + อ่านซับ", "ได้ทุกบทพูด · ใช้เครื่องหนักขึ้น", "both"),
				Opt("ฟังเสียงอย่างเดียว", "ไม่ต้องเปิดซับในเกม", "speech")), "แอปเอาภาษาอังกฤษมาจากไหน"),
			Topic("ขนาดตัวอักษร", "tr_text_size", "medium", listOf(
				Opt("เท่าซับอังกฤษ", "เล็กที่สุด", "english"), Opt("เล็ก", null, "small"), Opt("กลาง", null, "medium"),
				Opt("ใหญ่", null, "large"), Opt("ใหญ่มาก", null, "xlarge")), "ขนาดซับไทยบนจอ"),
			Topic("สีตัวอักษร", TranslatorPrefs.KEY_COLOR, "white", listOf(
				Opt("ขาว ขอบดำ", null, "white"), Opt("เหลือง ขอบดำ", null, "yellow"), Opt("เขียวสว่าง ขอบดำ", null, "green")), "สีซับไทย"),
			Topic("แถบดำด้านหลังซับ", TranslatorPrefs.KEY_BG, "medium", listOf(
				Opt("ปิด", "ตัวหนังสืออย่างเดียว", "off"), Opt("จาง", null, "light"), Opt("ปานกลาง", null, "medium")), "พื้นหลังใต้ซับไทย"),
			Topic("ตำแหน่งซับไทย", TranslatorPrefs.KEY_POSITION, "above", listOf(
				Opt("เหนือซับอังกฤษ", null, "above"), Opt("ใต้ซับอังกฤษ", "ไม่ทับกัน", "below"), Opt("บนสุดของจอ", null, "top")), "ซับไทยอยู่ตรงไหนของจอ"),
			Topic("ประโยคก่อนหน้า", TranslatorPrefs.KEY_SHOW_PREVIOUS, "1", listOf(
				Opt("แสดง", "ประโยคเก่าสีฟ้าอยู่หน้าประโยคใหม่สักครู่", "1"), Opt("ไม่แสดง", "เฉพาะประโยคล่าสุด", "0")), "ประโยคที่อ่านไม่ทัน"),
			Topic("ฟอนต์", "tr_font", "kanit", TranslatorPrefs.Font.values().filter { it != TranslatorPrefs.Font.NOTO }.map { Opt(it.title, null, it.value) }, "แบบตัวอักษร"),
			Topic("เล่นแบบ", KEY_PLAY_MODE, "stream", listOf(
				Opt("ผ่านแอป · 1080p", "ทีวีทุกรุ่น กล่อง ดองเกิล · ปรับภาพอัตโนมัติ", "stream"),
				Opt("4K · HDMI", "PS5 เสียบทีวีเครื่องนี้ · ภาพ 4K จาก PS5 ตรง ๆ", "hdmi")), "ภาพเกมมาจากไหน"),
			Topic("เสียงเมนู", Sfx.KEY, "on", listOf(
				Opt("เปิด", "เสียงตอนเลื่อน เลือกเกม และเปลี่ยนหน้า", "on"),
				Opt("ปิด", "ไม่มีเสียงในเมนู", "off")), "เสียงในแอป"))
		fun labelOf(t: Topic) = t.opts.firstOrNull { it.value == cur(t) }?.label ?: ""

		fun save(t: Topic, value: String)
		{
			when(t.key)
			{
				TranslatorPrefs.KEY_MODE -> { TranslatorPrefs(this).setMode(when(value) { "both" -> TranslatorPrefs.Mode.BOTH; "speech" -> TranslatorPrefs.Mode.SPEECH; else -> TranslatorPrefs.Mode.SCREEN }); clearAutoMode() }
				TranslatorPrefs.KEY_SHOW_PREVIOUS -> sp.edit().putBoolean(t.key, value == "1").apply()
				KEY_PLAY_MODE -> {
					sp.edit().putString(KEY_PLAY_MODE, value).apply()
					if(value == "stream") { DeviceCaps.apply(this, "auto"); if(com.metallic.chiaki.hdmi.HdmiModeService.running) com.metallic.chiaki.hdmi.HdmiModeService.stop(this) }
					else toast("โหมด 4K: กดการ์ด PS5 ในหน้าเล่น แล้วเลือกช่อง HDMI ที่เสียบ PS5")
				}
				else -> sp.edit().putString(t.key, value).apply()
			}
		}

		// ---- right: the preview (and what the choice the remote is on means)
		v.addView(text(this, "ตัวอย่างบนจอ", 26f, Ui.MUTED, W.MEDIUM), lp(WRAP, WRAP, 1040, 136))
		val prev = FrameLayout(this).apply { background = Ui.round(0, 26f, 2, Ui.LINE) }
		prev.addView(Ui.topImage(this, R.drawable.gta_shot3, 0.7f), FrameLayout.LayoutParams(MATCH, MATCH))
		Ui.clipRound(prev, 26f)
		val prevW = 790; val prevH = 444
		val thai = com.metallic.chiaki.translate.SubtitleTextView(this).apply { gravity = Gravity.CENTER; setPadding(14, 2, 14, 4) }
		val eng = text(this, "Okay, you're wound up a little tight. What's up?", 17f, 0xFFDDDDDD.toInt(), gravity = Gravity.CENTER).apply {
			typeface = android.graphics.Typeface.SANS_SERIF; setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt()) }
		prev.addView(eng, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 18 })
		prev.addView(thai, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 50 })
		val badge = text(this, "", 22f, Ui.TEXT, W.MEDIUM, Gravity.CENTER).apply {
			background = Ui.round(0xCC07050C.toInt(), 18f, 2, Ui.VIOLET2); setPadding(20, 8, 20, 8) }
		prev.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(18, 18, 0, 0) })
		v.addView(prev, lp(prevW, prevH, 1040, 186))
		val about = text(this, "", 26f, Ui.TEXT, W.MEDIUM).apply { setLineSpacing(0f, 1.25f) }
		v.addView(about, lp(prevW, WRAP, 1040, 186 + prevH + 26))
		fun preview()
		{
			fun c(key: String, def: String) = topics.first { it.key == key }.let { cur(it) }.ifEmpty { def }
			val size = when(c("tr_text_size", "medium")) { "english" -> 0.034f; "small" -> 0.040f; "large" -> 0.052f; "xlarge" -> 0.060f; else -> 0.046f }
			val color = when(c(TranslatorPrefs.KEY_COLOR, "white")) { "yellow" -> 0xFFFFE14D.toInt(); "green" -> 0xFF5CFF7A.toInt(); else -> 0xFFFFFFFF.toInt() }
			val bgA = when(c(TranslatorPrefs.KEY_BG, "medium")) { "off" -> 0; "light" -> 0x40; else -> 0x73 }
			val font = TranslatorPrefs.Font.values().firstOrNull { it.value == c("tr_font", "kanit") } ?: TranslatorPrefs.Font.KANIT
			val px = size * prevH * 1.45f
			thai.typeface = ResourcesCompat.getFont(this, font.res)
			thai.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
			thai.setTextColor(color)
			thai.outlineColor = 0xFF000000.toInt()
			thai.outlineWidthPx = px * (if(bgA == 0) 0.11f else 0.07f)
			thai.background = if(bgA == 0) null else android.graphics.drawable.ColorDrawable(bgA shl 24)
			thai.text = if(c(TranslatorPrefs.KEY_SHOW_PREVIOUS, "1") == "1") android.text.SpannableStringBuilder("ใจเย็น ๆ  ›  ").apply {
				setSpan(com.metallic.chiaki.translate.FillColorSpan(0xFF9FB4FF.toInt()), 0, length, 0); append("เครียดเกินไปหรือเปล่า มีอะไรหรือเปล่า") }
				else "เครียดเกินไปหรือเปล่า มีอะไรหรือเปล่า"
			val pos = c(TranslatorPrefs.KEY_POSITION, "above")
			val tl = thai.layoutParams as FrameLayout.LayoutParams
			when(pos)
			{
				"top" -> { tl.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; tl.topMargin = 70; tl.bottomMargin = 0 }
				"below" -> { tl.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; tl.topMargin = 0; tl.bottomMargin = 8 }
				else -> { tl.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; tl.topMargin = 0; tl.bottomMargin = 50 }
			}
			(eng.layoutParams as FrameLayout.LayoutParams).bottomMargin = if(pos == "below") (px * 1.6f).toInt() + 12 else 18
			thai.layoutParams = tl; eng.requestLayout()
			badge.text = when
			{
				c(KEY_PLAY_MODE, "stream") == "hdmi" -> "4K · HDMI"
				else -> "ผ่านแอป · 1080p"
			} + " · " + when(c(TranslatorPrefs.KEY_MODE, "screen")) { "both" -> "ฟังเสียง + อ่านซับ"; "speech" -> "ฟังเสียง"; else -> "อ่านซับบนจอ" }
		}

		// ---- left: topics / the choices of one topic
		val left = FrameLayout(this)
		v.addView(left, lp(900, 820, 90, 120))
		fun heading(s: String, size: Float) = com.metallic.chiaki.translate.SubtitleTextView(this).apply {
			text = s; setTextColor(0xFFFFE14D.toInt()); outlineColor = 0xFF000000.toInt(); outlineWidthPx = size * 0.16f
			setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size); typeface = ResourcesCompat.getFont(this@TvActivity, R.font.kanit_bold) }
		lateinit var showTopics: (Int) -> Unit

		fun showChoices(ti: Int)
		{
			val t = topics[ti]
			left.removeAllViews()
			val col = Ui.col(this)
			col.addView(heading(t.title, 46f))
			col.addView(text(this, t.about, 24f, Ui.MUTED), llp(WRAP, WRAP, t = 2))
			var focus: View? = null
			t.opts.forEachIndexed { k, o ->
				val card = Card(this, 20f, Ui.PANEL) { cur(t) == o.value && draft[t.key] == null }
				card.grow = 1.03f
				val r = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(28, 0, 28, 0) }
				val cc = Ui.col(this)
				cc.addView(text(this, o.label, 30f, Ui.TEXT, W.SEMIBOLD).apply {
					if(t.key == "tr_font") typeface = ResourcesCompat.getFont(this@TvActivity, TranslatorPrefs.Font.values().first { it.value == o.value }.res) })
				if(o.sub != null) cc.addView(text(this, o.sub, 20f, Ui.MUTED), llp(WRAP, WRAP, t = 2))
				r.addView(cc, llp(0, WRAP, 1f))
				r.addView(text(this, if(cur(t) == o.value) "✔" else "", 32f, Ui.OK, W.BOLD))
				card.addView(r, FrameLayout.LayoutParams(MATCH, MATCH))
				card.setOnFocusChangeListener { _, f ->
					card.refresh(); Ui.glow(card, f)
					card.animate().scaleX(if(f) card.grow else 1f).scaleY(if(f) card.grow else 1f).setDuration(200).start()
					if(f) { draft[t.key] = o.value; about.text = o.label + (o.sub?.let { " · $it" } ?: ""); preview() }
				}
				card.setOnClickListener { draft.clear(); save(t, o.value); toast("${t.title}: ${o.label} ✔"); showTopics(ti) }
				col.addView(card, llp(MATCH, if(o.sub != null) 92 else 76, t = if(k == 0) 22 else 12))
				if(cur(t) == o.value && focus == null) focus = card
			}
			val back = Card(this, 20f); back.grow = 1.03f
			back.addView(text(this, "← กลับ", 26f, Ui.MUTED, W.MEDIUM, Gravity.CENTER), FrameLayout.LayoutParams(MATCH, MATCH))
			back.setOnClickListener { draft.clear(); preview(); showTopics(ti) }
			col.addView(back, llp(260, 70, t = 20))
			left.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
			keyCatcher = { e -> if((e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B) && !PadConfig.fromPad(e)) {
				if(e.action == KeyEvent.ACTION_UP) { draft.clear(); preview(); showTopics(ti) }; true } else false }
			val f = focus ?: col.getChildAt(2)
			left.post { f?.requestFocus() }
		}

		showTopics = { focusTopic ->
			keyCatcher = null
			left.removeAllViews()
			val col = Ui.col(this)
			col.addView(text(this, "ตั้งค่าซับไทย", 40f, Ui.TEXT, W.BOLD))
			var focus: View? = null
			topics.forEachIndexed { k, t ->
				val card = Card(this, 18f, Ui.PANEL); card.grow = 1.03f
				val r = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(26, 0, 26, 0) }
				r.addView(heading(t.title, 32f), llp(0, WRAP, 1f))
				r.addView(text(this, labelOf(t) + "  ›", 24f, Ui.TEXT, W.MEDIUM))
				card.addView(r, FrameLayout.LayoutParams(MATCH, MATCH))
				card.setOnFocusChangeListener { _, f ->
					card.refresh(); Ui.glow(card, f)
					card.animate().scaleX(if(f) card.grow else 1f).scaleY(if(f) card.grow else 1f).setDuration(200).start()
					if(f) about.text = t.about + " · ตอนนี้: " + labelOf(t)
				}
				card.setOnClickListener { showChoices(k) }
				col.addView(card, llp(MATCH, 74, t = if(k == 0) 16 else 10))
				if(k == focusTopic) focus = card
			}
			left.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
			preview()
			focus?.let { f -> left.post { f.requestFocus() } }
		}
		showTopics(-1)
		about.text = "เลือกหัวข้อด้านซ้าย · ภาพตัวอย่างจะเปลี่ยนตามตัวเลือกที่เลื่อนไป"
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
		fun close() { keyCatcher = null; dropPanel(panel, 200) }
		openPanels[panel] = { close() }
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
			add(Triple("คำแนะนำบนจอ", if(tipsHidden()) "ซ่อนอยู่ · กดเพื่อแสดง" else "แสดงอยู่ · กดเพื่อซ่อน (สำหรับคนที่รู้วิธีแล้ว)") {
				sp.edit().putBoolean(KEY_HIDE_TIPS, !tipsHidden()).apply()
				toast(if(tipsHidden()) "ซ่อนคำแนะนำแล้ว" else "แสดงคำแนะนำแล้ว"); showTab(4, Trans.NONE) })
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
		if(event.action == KeyEvent.ACTION_DOWN) Sfx.lastKeyAt = android.os.SystemClock.elapsedRealtime()
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
		// "back" on the home page while 4K mode runs: the player is going to the TV / the PS5 input, not quitting.
		// Closing the app here ended 4K mode, so the game "kicked out" (TVs that do not let apps switch the input make
		// the player leave the app to switch it). Keep 4K running: leave the app in the background. เลิกเล่น = quit card.
		if(event.keyCode == KeyEvent.KEYCODE_BACK && tab == 0 && openPanels.isEmpty() && keyCatcher == null &&
			com.metallic.chiaki.hdmi.HdmiModeService.running && !PadConfig.fromPad(event))
		{
			if(event.action == KeyEvent.ACTION_UP) { toast("โหมด 4K ยังทำงานอยู่ · เลิกเล่น: เปิดแอปแล้วกดการ์ด \"เลิกเล่น\""); moveTaskToBack(true) }
			return true
		}
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
		if(isFinishing) try { com.metallic.chiaki.hdmi.HdmiModeService.stop(this, "user:ปิดแอป") } catch(_: Throwable) { }
		super.onDestroy()
	}

	companion object
	{
		const val KEY_ONBOARDED = "tv_onboarded"
		const val KEY_GAME = "tv_game"
		/** Display name of the picked game ("connecting to …" messages, the in-game status line). */
		const val KEY_GAME_NAME = "tv_game_name"
		/** Built-in game (works offline); the id the server knows GTA VI by. */
		const val DEFAULT_GAME = "gta6"
		/** Last game list from the server's GET /v1/games, JSON [{id,name}]. */
		const val KEY_GAMES_CACHE = "tv_games_cache"
		/** "ซ่อนคำแนะนำ": the player knows the steps; explanations under each line / on the game picture are hidden. */
		const val KEY_HIDE_TIPS = "tv_hide_tips"
		/** Set by the game screen after "เลิกเล่น": the home screen shows how to switch the controller off. */
		const val KEY_SHOW_PAD_OFF = "tv_show_pad_off"
		/** Set by the pairing screen: address of the PS5 just paired, so the next step (checklist) opens by itself. */
		const val KEY_JUST_PAIRED = "tv_just_paired"
		/** TV input id of the HDMI port the PS5 is plugged into (HDMI 4K mode), picked once. */
		const val KEY_PS5_INPUT = "tv_ps5_input"
		/** "stream" = play through the app, "hdmi" = PS5 on the TV's HDMI input with Thai drawn on top (link build). */
		const val KEY_PLAY_MODE = "tv_play_mode"
	}
}
