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
import com.metallic.chiaki.translate.LicencePrompt
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

	private val trialDays = 15 // closed test (server PLAY_TRIAL_DAYS); 2 at the public launch

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		stage = Stage(this)
		setContentView(stage)
		WindowCompat.setDecorFitsSystemWindows(window, false)
		WindowInsetsControllerCompat(window, stage).let {
			it.hide(WindowInsetsCompat.Type.systemBars())
			it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}
		vm = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this)) })[MainViewModel::class.java]
		vm.discoveryManager.active = true
		// picture settings that suit this TV (unless the player picked them)
		if(DeviceCaps.quality(this) == "auto") try { DeviceCaps.apply(this, "auto") } catch(_: Throwable) {}
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
		if(!firstResume && sp.getBoolean(KEY_ONBOARDED, false)) LicencePrompt.maybeShow(this)
		firstResume = false
	}

	private fun startStoreOnce()
	{
		if(storeStarted) return
		storeStarted = true
		try { Store.onAppStart(this) } catch(e: Throwable) { android.util.Log.e("Eyesmaxxing", "store start failed", e) }
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
		v.setOnClickListener { if(i < 2) showFeature(i + 1, Trans.LEFT) else showTrial(Trans.LEFT) }
		go(v, t)
		keyCatcher = { e ->
			if(e.action == KeyEvent.ACTION_UP) when(e.keyCode)
			{
				KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
					if(i < 2) showFeature(i + 1, Trans.LEFT) else showTrial(Trans.LEFT)
				KeyEvent.KEYCODE_DPAD_LEFT -> if(i > 0) showFeature(i - 1, Trans.RIGHT)
				KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> showTrial(Trans.LEFT)
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

	private fun showTrial(t: Trans)
	{
		keyCatcher = null
		val v = FrameLayout(this)
		v.addView(Ui.brand(this), lp(WRAP, WRAP, 64, 44))
		val c = Ui.col(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
		val play = Store.IS_PLAY
		c.addView(text(this, if(play) "ช่วงทดสอบระบบปิด" else "เริ่มใช้งาน", 26f, Ui.VIOLET2).apply {
			background = Ui.round(Ui.PANEL2, 40f, 2, Ui.LINE); setPadding(26, 10, 26, 10) })
		c.addView(text(this, if(play) "ฟรี $trialDays วัน" else "ใส่รหัสแล้วเล่นได้เลย", if(play) 150f else 96f, Ui.TEXT, W.EXTRABOLD, Gravity.CENTER).apply {
			paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, if(play) 170f else 110f, 0xFFFFFFFF.toInt(), 0xFFC4B5FD.toInt(), android.graphics.Shader.TileMode.CLAMP)
			setShadowLayer(60f, 0f, 0f, 0x668B5CF6)
		}, llp(WRAP, WRAP, t = 10))
		c.addView(text(this, if(play) "ทดลองใช้ซับไทยครบทุกฟีเจอร์" else "รหัสซื้อได้จากผู้ขายทาง LINE", 56f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER), llp(WRAP, WRAP, t = 6))
		c.addView(text(this, if(play) "ไม่ต้องผูกบัตร · ไม่ต้องใส่รหัส · กดเริ่มแล้วใช้ได้ทันที" else "ทุกแพ็กเกจใช้ได้ครบทุกฟีเจอร์", 30f, Ui.MUTED, gravity = Gravity.CENTER), llp(WRAP, WRAP, t = 18))
		if(play) c.addView(text(this, "หลังเปิดตัวจริงบน Google Play: ทดลองฟรี 2 วัน", 24f, Ui.WARN, gravity = Gravity.CENTER), llp(WRAP, WRAP, t = 14))
		val cta = pill(if(play) "เริ่มทดลองฟรี" else "ดูแพ็กเกจ") { showPackages(true, Trans.LEFT) }
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

	// ------------------------------------------------------------------ packages

	private class Pack(val name: String, val len: String, val old: String?, val price: String, val unit: String,
		val per: String, val badge: String?, val free: Boolean = false, val recommended: Boolean = false, val product: String? = null)

	private val packs = listOf(
		Pack("ทดลองฟรี", "ช่วงทดสอบ $trialDays วัน", null, "ฟรี", "", "ใช้ครบทุกฟีเจอร์\nไม่ผูกบัตร", "เริ่มที่นี่", free = true),
		Pack("7 วัน", "เล่นต่อเนื่องทั้งสัปดาห์", "79 บาท", "49", "บาท", "ตกวันละ 7 บาท", null, product = Store.ONE_TIME_WEEK),
		Pack("รายเดือน", "30 วัน", "299 บาท", "189", "บาท", "คุ้มสุดสำหรับเล่นจบเนื้อเรื่อง", "แนะนำ", recommended = true, product = Store.SUB_MONTHLY),
		Pack("รายปี", "365 วัน", "999 บาท", "759", "บาท", "ตกเดือนละ 63 บาท", null, product = Store.SUB_YEARLY),
		Pack("ตลอดชีพ", "จ่ายครั้งเดียว", "1,299 บาท", "1,099", "บาท", "รวมเกมที่จะเพิ่มในอนาคต", null, product = Store.ONE_TIME_LIFETIME))

	private fun packagesView(onboarding: Boolean): Pair<FrameLayout, View>
	{
		val v = FrameLayout(this)
		val head = Ui.col(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
		head.addView(text(this, "เลือกแพ็กเกจ", 58f, Ui.TEXT, W.BOLD, Gravity.CENTER))
		head.addView(text(this, if(Store.IS_PLAY) "ราคาพิเศษช่วงเปิดตัว · ช่วงทดสอบใช้ฟรีทั้งหมด" else "ราคาพิเศษช่วงเปิดตัว · ซื้อรหัสได้จากผู้ขายทาง LINE",
			28f, Ui.MUTED, gravity = Gravity.CENTER), llp(WRAP, WRAP, t = 8))
		v.addView(head, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = if(onboarding) 150 else 120 })
		var first: View? = null
		val top = if(onboarding) 330 else 300
		packs.forEachIndexed { i, p ->
			val card = Card(this, 30f, if(p.free) 0xFF1B1435.toInt() else Ui.PANEL) { p.recommended }
			card.grow = 1.06f
			val c = Ui.col(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(24, 44, 24, 34) }
			c.addView(text(this, p.name, 32f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER))
			c.addView(text(this, p.len, 24f, Ui.MUTED, gravity = Gravity.CENTER), llp(WRAP, WRAP, t = 4))
			c.addView(text(this, p.old ?: "", 30f, Ui.DIM, gravity = Gravity.CENTER).apply {
				paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG }, llp(WRAP, 46, t = 34))
			val price = android.text.SpannableStringBuilder(p.price).apply {
				if(p.unit.isNotEmpty()) { val s = length; append(" " + p.unit)
					setSpan(android.text.style.AbsoluteSizeSpan(30), s, length, 0) }
			}
			c.addView(text(this, price, 84f, Ui.TEXT, W.EXTRABOLD, Gravity.CENTER).apply { setLineSpacing(0f, 1f) }, llp(WRAP, WRAP))
			if(p.free && Store.IS_PLAY)
				c.addView(text(this, "หลังเปิดตัวจริง: ทดลองฟรี 2 วัน", 21f, Ui.WARN, gravity = Gravity.CENTER), llp(WRAP, WRAP, t = 10))
			c.addView(View(this), llp(1, 0, 1f))
			c.addView(text(this, p.per, 23f, Ui.MUTED, gravity = Gravity.CENTER).apply { setLineSpacing(0f, 1.3f) }, llp(WRAP, WRAP))
			card.addView(c, FrameLayout.LayoutParams(MATCH, MATCH))
			val x = 80 + i * (330 + 28)
			v.addView(card, lp(330, 520, x, top))
			p.badge?.let { b ->
				val badge = text(this, b, 22f, Ui.TEXT, W.SEMIBOLD, Gravity.CENTER).apply {
					background = Ui.gradient(intArrayOf(Ui.VIOLET, Ui.GLOW), 30f); setPadding(22, 4, 22, 4); elevation = 60f }
				v.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = x; topMargin = top - 22 })
				badge.post { badge.translationX = (330 - badge.width) / 2f }
			}
			card.setOnClickListener { choosePack(p, onboarding) }
			if(i == 0) first = card
		}
		v.addView(text(this, if(Store.IS_PLAY) "ช่วงทดสอบยังไม่เก็บเงินจริง · ชำระผ่าน Google Play เมื่อเปิดขาย" else "ส่งชื่อแพ็กเกจให้ผู้ขายทาง LINE แล้วใส่รหัสที่ได้รับ",
			24f, Ui.MUTED, gravity = Gravity.CENTER), FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = top + 560 })
		return v to first!!
	}

	private fun showPackages(onboarding: Boolean, t: Trans)
	{
		keyCatcher = null
		val (v, first) = packagesView(onboarding)
		v.addView(Ui.brand(this), lp(WRAP, WRAP, 64, 44))
		v.addView(Ui.hint(this, listOf("◀" to "", "▶" to "เลือก", "✕" to "ยืนยัน")), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 42 })
		go(v, t, first)
		keyCatcher = { e ->
			if(e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B)
			{ if(e.action == KeyEvent.ACTION_UP) showTrial(Trans.RIGHT); true } else false
		}
	}

	private fun choosePack(p: Pack, onboarding: Boolean)
	{
		if(p.free)
		{
			if(!Store.IS_PLAY) { if(onboarding) showDeviceCheck(Trans.LEFT); LicencePrompt.ask(this); return }
			toast("กำลังเริ่มทดลองฟรี…")
			val prefs = TranslatorPrefs(this)
			Thread {
				val cloud = CloudTranslator(prefs.serverUrl, prefs.cloudCode, CloudTranslator.deviceId(this))
				val prob = cloud.check()
				main.post {
					when(prob)
					{
						CloudTranslator.Problem.NONE, CloudTranslator.Problem.DAILY_LIMIT, CloudTranslator.Problem.POOL_EMPTY ->
							toast("เริ่มทดลองฟรีแล้ว ✔" + daysLeft(cloud.expires))
						CloudTranslator.Problem.OFFLINE -> toast("ต่ออินเทอร์เน็ตไม่ได้ · จะเริ่มทดลองให้เมื่อทีวีต่อเน็ต")
						else -> toast(CloudTranslator.problemText(prob, cloud.plan))
					}
					if(onboarding) showDeviceCheck(Trans.LEFT)
				}
			}.start()
			return
		}
		if(Store.IS_PLAY) Store.openPurchase(this, p.product)
		else android.app.AlertDialog.Builder(this)
			.setTitle("แพ็กเกจ ${p.name} · ${p.price} บาท")
			.setMessage("ส่งชื่อแพ็กเกจนี้ให้ผู้ขายทาง LINE เพื่อรับรหัส แล้วกด \"ใส่รหัส\"")
			.setPositiveButton("ใส่รหัส") { _, _ -> LicencePrompt.ask(this) }
			.setNegativeButton("ภายหลัง", null)
			.show()
	}

	private fun planName(plan: String) = when(plan)
	{
		"trial" -> "ทดลองฟรี"; "day" -> "แพ็ก 1 วัน"; "week" -> "แพ็ก 7 วัน"; "month" -> "รายเดือน"
		"year" -> "รายปี"; "life" -> "ตลอดชีพ"; else -> "ใช้งานได้"
	}

	private fun daysLeft(expires: Long): String
	{
		if(expires <= 0) return ""
		val d = ((expires - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
		return " · เหลือ $d วัน"
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
				"ตั้งภาพ ${caps.autoTitle()} ให้อัตโนมัติ"),
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

		val start = pill("เริ่มใช้งาน") {
			sp.edit().putBoolean(KEY_ONBOARDED, true).apply()
			showTab(0, Trans.LEFT)
		}
		v.addView(start, lp(400, 96, 760, 930))
		go(v, t, start)
		keyCatcher = { e ->
			if(e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B)
			{ if(e.action == KeyEvent.ACTION_UP) showPackages(true, Trans.RIGHT); true } else false
		}
	}

	private fun openBluetooth()
	{
		for(a in listOf(Settings.ACTION_BLUETOOTH_SETTINGS, "android.settings.ADD_ACCESSORY_SETTINGS", Settings.ACTION_SETTINGS))
			try { startActivity(Intent(a)); return } catch(_: Throwable) {}
		toast("เปิดหน้าตั้งค่าบลูทูธไม่ได้ · เข้าที่ ตั้งค่าทีวี › รีโมทและอุปกรณ์เสริม")
	}

	// ------------------------------------------------------------------ main screen with tabs

	private val tabs = listOf("เล่น", "ตั้งค่าซับ", "จอย", "แพ็กเกจ", "ช่วยเหลือ")

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
			3 -> packagesView(false).first
			else -> helpContent()
		}
		v.addView(content, 0, FrameLayout.LayoutParams(1920, 1080))
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
		val noteCard = text(this, "", 24f, Ui.MUTED).apply {
			background = Ui.round(Ui.PANEL, 26f, 2, Ui.LINE); setPadding(34, 26, 34, 26)
			text = android.text.SpannableStringBuilder("แปลเฉพาะ ").apply {
				val s = length; append("GTA 6"); setSpan(android.text.style.ForegroundColorSpan(Ui.TEXT), s, length, 0)
				setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, length, 0)
				append(" · เกมอื่นทยอยเพิ่มเร็ว ๆ นี้") }
		}
		col.addView(noteCard, llp(MATCH, WRAP, t = 26))
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
						setTile("ai", true, "AI พร้อม", planName(cloud.plan) + daysLeft(cloud.expires))
					CloudTranslator.Problem.NOT_STARTED -> setTile("ai", false, "AI ยังไม่เปิด", CloudTranslator.aiStartText())
					CloudTranslator.Problem.OFFLINE -> setTile("ai", false, "ไม่มีอินเทอร์เน็ต", "ตรวจเน็ตของทีวี")
					else -> setTile("ai", false, "ยังไม่ได้ใช้ AI", if(Store.IS_PLAY) "ดูแพ็กเกจ" else "ใส่รหัสในหน้าช่วยเหลือ")
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
			val pads = Diag.gamepads()
			setTile("pad", pads.isNotEmpty(), if(pads.isNotEmpty()) "จอยพร้อม" else "ยังไม่มีจอย", if(pads.isNotEmpty()) pads.first().take(24) else "ไปที่แท็บ จอย เพื่อจับคู่")
			main.postDelayed(this, 1500) } }
		startRefresh(tick)
		return v
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
		if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
		{
			// wake the PS5 from rest mode, then connect once it is up
			vm.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
			toast("กำลังปลุก PS5… รอสักครู่แล้วจะเชื่อมต่อให้เอง")
			main.postDelayed({ connect(host, registeredHost) }, 9000)
			return
		}
		connect(host, registeredHost)
	}

	private fun connect(host: DisplayHost, registeredHost: RegisteredHost)
	{
		val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
		startActivity(Intent(this, StreamActivity::class.java).putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo))
	}

	// ---- tab 1: ตั้งค่าซับ
	private fun settingsContent(): View
	{
		val v = FrameLayout(this)
		val left = Ui.col(this)
		left.addView(text(this, "ตั้งค่าซับไทย", 46f, Ui.TEXT, W.BOLD))
		val groups = ArrayList<Card>()
		lateinit var refreshPreview: () -> Unit
		fun option(title: String, sub: String?, isSel: () -> Boolean, pick: () -> Unit, font: android.graphics.Typeface? = null, size: Float = 26f, center: Boolean = false): Card
		{
			val card = Card(this, 20f, Ui.PANEL, isSel)
			card.grow = 1.04f
			val c = Ui.col(this).apply { setPadding(22, 14, 22, 14); gravity = if(center) Gravity.CENTER else Gravity.CENTER_VERTICAL }
			c.addView(text(this, title, size, Ui.TEXT, W.REGULAR, if(center) Gravity.CENTER else Gravity.START).apply { if(font != null) typeface = font })
			if(sub != null) c.addView(text(this, sub, 20f, Ui.MUTED, gravity = if(center) Gravity.CENTER else Gravity.START), llp(WRAP, WRAP, t = 2))
			card.addView(c, FrameLayout.LayoutParams(MATCH, MATCH))
			card.setOnClickListener { pick(); groups.forEach { it.refresh() }; refreshPreview() }
			groups += card
			return card
		}
		fun head(s: String) = text(this, s, 28f, Ui.VIOLET2, W.MEDIUM)
		val prefs = { TranslatorPrefs(this) }

		left.addView(head("แปลจาก"), llp(WRAP, WRAP, t = 22))
		val seg = Ui.row(this)
		seg.addView(option("อ่านซับอย่างเดียว", "ลื่นที่สุด แนะนำ", { prefs().mode == TranslatorPrefs.Mode.SCREEN }, { prefs().setMode(TranslatorPrefs.Mode.SCREEN); clearAutoMode() }), llp(0, 104, 1f))
		seg.addView(option("ฟังเสียง + อ่านซับ", "ได้ทุกบทพูด ใช้เครื่องหนักขึ้น", { prefs().mode == TranslatorPrefs.Mode.BOTH }, { prefs().setMode(TranslatorPrefs.Mode.BOTH); clearAutoMode() }), llp(0, 104, 1f, l = 16))
		left.addView(seg, llp(MATCH, WRAP, t = 12))

		left.addView(head("ฟอนต์"), llp(WRAP, WRAP, t = 26))
		val fonts = TranslatorPrefs.Font.values()
		for(r in 0 until 2)
		{
			val row = Ui.row(this)
			for(k in 0 until 3)
			{
				val f = fonts[r * 3 + k]
				row.addView(option("สวัสดี GTA", f.title, { prefs().font == f }, { sp.edit().putString("tr_font", f.value).apply() },
					ResourcesCompat.getFont(this, f.res), 30f, true), llp(0, 104, 1f, l = if(k == 0) 0 else 14))
			}
			left.addView(row, llp(MATCH, WRAP, t = if(r == 0) 12 else 14))
		}

		left.addView(head("ขนาดตัวอักษร"), llp(WRAP, WRAP, t = 26))
		val sizes = listOf("small" to "เล็ก", "medium" to "กลาง", "large" to "ใหญ่", "xlarge" to "ใหญ่มาก")
		val srow = Ui.row(this)
		sizes.forEachIndexed { k, (value, label) ->
			srow.addView(option(label, null, { (sp.getString("tr_text_size", "medium") ?: "medium") == value }, { sp.edit().putString("tr_text_size", value).apply() },
				size = 24f, center = true), llp(0, 66, 1f, l = if(k == 0) 0 else 14))
		}
		left.addView(srow, llp(MATCH, WRAP, t = 12))
		v.addView(left, lp(934, WRAP, 90, 140))

		// right: live preview + picture
		val right = Ui.col(this)
		right.addView(head("ตัวอย่างบนจอ"))
		val prev = FrameLayout(this).apply { background = Ui.round(0, 26f, 2, Ui.LINE) }
		prev.addView(Ui.topImage(this, R.drawable.gta_shot3, 0.7f), FrameLayout.LayoutParams(MATCH, MATCH))
		Ui.clipRound(prev, 26f)
		val thai = text(this, "เครียดเกินไปหรือเปล่า มีอะไรหรือเปล่า", 30f, 0xFFFFFFFF.toInt(), W.BOLD, Gravity.CENTER).apply {
			setBackgroundColor(0x73000000); setPadding(18, 2, 18, 4) }
		val eng = text(this, "Okay, you're wound up a little tight. What's up?", 22f, 0xFFDDDDDD.toInt(), gravity = Gravity.CENTER).apply {
			typeface = android.graphics.Typeface.SANS_SERIF; setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt()) }
		prev.addView(eng, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = 20 })
		prev.addView(thai, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 60 })
		right.addView(prev, llp(MATCH, 430, t = 12))
		refreshPreview = {
			val p = prefs()
			thai.typeface = ResourcesCompat.getFont(this, p.font.res)
			thai.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, p.textHeightFraction * 430f * 1.45f)
		}
		refreshPreview()

		right.addView(head("ภาพ"), llp(WRAP, WRAP, t = 26))
		val caps = DeviceCaps(this)
		val q = Ui.row(this)
		val qs = listOf(Triple("auto", "อัตโนมัติ", caps.autoTitle() + " (เครื่องนี้)"), Triple("smooth", "ลื่นสุด", "720p 60 เฟรม"), Triple("sharp", "คมสุด", "1080p 60 · จอใหญ่"))
		qs.forEachIndexed { k, (value, label, s) ->
			q.addView(option(label, s, { DeviceCaps.quality(this) == value }, { DeviceCaps.apply(this, value) }, size = 26f), llp(0, 104, 1f, l = if(k == 0) 0 else 14))
		}
		right.addView(q, llp(MATCH, WRAP, t = 12))
		v.addView(right, lp(760, WRAP, 1070, 150))
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
			add(Triple("จับคู่ PS5 ใหม่", "พิมพ์ชื่อ PSN แล้วใส่รหัส 8 หลักจาก PS5 (ไม่ต้องใช้รหัสผ่าน)") { startActivity(Intent(this@TvActivity, RegistActivity::class.java)) })
			add(Triple("เพิ่ม PS5 ด้วยเลข IP", "ถ้าแอปหา PS5 ไม่เจอเอง") { startActivity(Intent(this@TvActivity, EditManualConsoleActivity::class.java)) })
			if(!Store.IS_PLAY) add(Triple("ใส่รหัสไลเซนส์", TranslatorPrefs(this@TvActivity).licence.ifBlank { "ยังไม่ได้ใส่รหัส" }) {
				LicencePrompt.ask(this@TvActivity) })
			if(Store.IS_PLAY) add(Triple("กู้คืนการซื้อ", "ซื้อแล้วแต่ยังไม่ขึ้น หรือย้ายมาทีวีเครื่องใหม่") {
				toast("กำลังตรวจการซื้อกับ Google Play…"); Store.refresh(this@TvActivity) { msg -> toast(msg) } })
			add(Triple("ตั้งค่าขั้นสูง", "ภาพ เสียง และตัวเลือกทั้งหมด") { startActivity(Intent(this@TvActivity, SettingsActivity::class.java)) })
			add(Triple("ดูหน้าแนะนำอีกครั้ง", "ฟีเจอร์ ทดลองฟรี และแพ็กเกจ") { showFeature(0, Trans.LEFT); tab = -1 })
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

	override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean
	{
		padView?.let { if(PadConfig.fromPad(ev)) it.motion(ev) }
		return super.dispatchGenericMotionEvent(ev)
	}

	override fun onDestroy()
	{
		stopRefresh()
		super.onDestroy()
	}

	companion object
	{
		const val KEY_ONBOARDED = "tv_onboarded"
	}
}
