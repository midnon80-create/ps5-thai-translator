// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.graphics.Matrix
import android.os.*
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import com.metallic.chiaki.translate.TranslatorController
import com.metallic.chiaki.translate.TranslatorPrefs
import com.metallic.chiaki.translate.SubtitleTextView
import android.graphics.Color
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
		private const val MAX_AUTO_RETRIES = 4
		private const val AUTO_RETRY_DELAY_MS = 6000L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var insetsController: WindowInsetsControllerCompat

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())

	private var translator: TranslatorController? = null

	private fun setupTranslator()
	{
		// Full brightness while playing on TVs only (eco modes dim apps). On a phone / tablet this locked the screen at
		// full brightness and the player could not turn it down: there the system brightness stays in charge.
		val tv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
			(getSystemService(android.app.UiModeManager::class.java)?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
		if(tv) window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
		val ui = com.metallic.chiaki.translate.TranslatorOverlay(binding.root, binding.surfaceView)
		translator = TranslatorController(this, binding.surfaceView, ui)
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = IntentCompat.getParcelableExtra(intent, EXTRA_CONNECT_INFO, ConnectInfo::class.java)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		// phone volume keys change the game sound, and say so when the media volume is at zero ("ไม่มีเสียง")
		volumeControlStream = android.media.AudioManager.STREAM_MUSIC
		// TVs / boxes: game sound through the TV speakers / HDMI ARC from the start (not only the headphone jack)
		try { com.metallic.chiaki.lib.Session.setAudioTv(packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
			!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) } catch(_: Throwable) { }
		try
		{
			val am = getSystemService(android.media.AudioManager::class.java)
			if(am != null && am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0)
				android.widget.Toast.makeText(this, "เสียงสื่อของเครื่องปิดอยู่ (0) · กดปุ่มเพิ่มเสียงเพื่อฟังเสียงเกม", android.widget.Toast.LENGTH_LONG).show()
		}
		catch(_: Throwable) { }

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		// no mouse arrow over the game (the controller touchpad can act as a mouse on some TVs)
		if(Build.VERSION.SDK_INT >= 24) try {
			val none = android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL)
			binding.root.pointerIcon = none; binding.surfaceView.pointerIcon = none; window.decorView.pointerIcon = none
		} catch(_: Throwable) { }

		WindowCompat.setDecorFitsSystemWindows(window, false)
		insetsController = WindowCompat.getInsetsController(window, window.decorView)
		insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
			val systemBars = insets.isVisible(WindowInsetsCompat.Type.systemBars())
			if(systemBars)
				showOverlay()
			else
				hideOverlay()
			insets
		}

		var syncingSwitch = false
		viewModel.onScreenControlsEnabled.observe(this, Observer {
			// hidden because a controller connected: show it on the switch, but keep the player's own choice saved
			syncingSwitch = true
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
			syncingSwitch = false
			if(binding.onScreenControlsSwitch.isChecked)
				binding.touchpadOnlySwitch.isChecked = false
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			if(syncingSwitch) return@setOnCheckedChangeListener
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}

		// real controller connected -> no on-screen buttons; unplugged -> they come back
		fun checkPads()
		{
			val has = android.view.InputDevice.getDeviceIds().any { id ->
				val d = android.view.InputDevice.getDevice(id) ?: return@any false
				!d.isVirtual && (d.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD &&
					d.hasKeys(android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_BUTTON_B).any { it }
			}
			viewModel.setPadConnected(has)
		}
		checkPads()
		val im = getSystemService(android.hardware.input.InputManager::class.java)
		val padListener = object : android.hardware.input.InputManager.InputDeviceListener {
			override fun onInputDeviceAdded(id: Int) = checkPads()
			override fun onInputDeviceRemoved(id: Int) { viewModel.input.releaseAll(); checkPads() }
			override fun onInputDeviceChanged(id: Int) = checkPads()
		}
		im?.registerInputDeviceListener(padListener, null)
		lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
			override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { im?.unregisterInputDeviceListener(padListener) }
		})

		viewModel.touchpadOnlyEnabled.observe(this, Observer {
			if(binding.touchpadOnlySwitch.isChecked != it)
				binding.touchpadOnlySwitch.isChecked = it
			if(binding.touchpadOnlySwitch.isChecked)
				binding.onScreenControlsSwitch.isChecked = false
		})
		binding.touchpadOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setTouchpadOnlyEnabled(isChecked)
			showOverlay()
		}

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}

		//viewModel.session.attachToTextureView(textureView)
		viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()
		setupTranslator()

		if(Preferences(this).rumbleEnabled)
		{
			val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
			viewModel.session.rumbleState.observe(this, Observer {
				val amplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
				vibrator.cancel()
				if(amplitude == 0)
					return@Observer
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
					vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
				else
					vibrator.vibrate(1000)
			})
		}
	}

	private var controlsJob: Job? = null

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			controlsJob?.cancel()
			controlsJob = fragment.controllerState
				.onEach { viewModel.input.touchControllerState = it }
				.launchIn(lifecycleScope)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
		}
	}

	private var resumedAt = 0L
	private var lastQuit: String? = null

	override fun onResume()
	{
		super.onResume()
		resumedAt = android.os.SystemClock.elapsedRealtime()
		hideSystemUI()
		viewModel.session.resume()
		translator?.start()
	}

	override fun onPause()
	{
		super.onPause()
		viewModel.input.releaseAll() // nothing stays held down ("จอยค้าง") when the app leaves the screen
		val stats = try { translator?.stats() } catch(_: Throwable) { null }
		translator?.stop()
		// auto picture: a game whose picture kept breaking up plays one step lighter next time
		val played = (android.os.SystemClock.elapsedRealtime() - resumedAt) / 1000
		val log = viewModel.session.logFile
		// end-of-game report for the owner's Devices page (TV model, subtitle / picture counters, no personal data)
		try { com.metallic.chiaki.translate.SessionReport.send(this, "stream", played, stats, log, lastQuit) } catch(_: Throwable) { }
		lastQuit = null
		viewModel.session.pause()
		val ctx = applicationContext
		Thread { try { com.metallic.chiaki.tv.DeviceCaps.learnFromSession(ctx, log, played) } catch(_: Throwable) { } }.start()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		controlsJob?.cancel()
	}

	private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private var autoRetries = 0
	private var padWarned = false
	private var connectedAt = 0L
	private var shutdownRetries = 0
	/** Mid-game drops (Wi-Fi lost packets until the link died) reconnected by themselves; reset after 2 min of play. */
	private var dropRetries = 0

	/** Sends the end of this session's log to the server, so connection problems on customers' TVs can be seen. */
	private val NL = System.lineSeparator()

	private fun reportSession(what: String)
	{
		try
		{
			val tail = viewModel.session.logFile?.takeIf { it.exists() }?.readLines()?.takeLast(80)?.joinToString(NL) ?: ""
			com.metallic.chiaki.crash.CrashReport.upload(this, "ประเภท: การเชื่อมต่อ" + NL + what + NL + NL + tail)
		}
		catch(_: Throwable) { }
	}

	/** Session-start errors that usually clear up by themselves within seconds. */
	private fun isRetryable(reason: String) =
		reason.contains("already in use", true) || reason.contains("Unknown Session Request", true) ||
			reason.contains("Connection Refused in Session Request", true) || reason.contains("Ctrl failed to connect", true)

	/** What went wrong and what to do, in plain Thai (the PS5's own reasons are English and technical). */
	private fun thaiQuitHelp(reason: String): String = when
	{
		reason.contains("already in use", true) ->
			"PS5 กำลังเชื่อมต่อกับอุปกรณ์อื่นอยู่ หรือยังไม่ปิดการเชื่อมต่อครั้งก่อน\n" +
			"• ปิด Remote Play ที่มือถือ คอม หรือ PS Portal\n• รอ 20 วินาที แล้วกด \"เชื่อมต่อใหม่\""
		reason.contains("Session Request", true) || reason.contains("Ctrl", true) ->
			"PS5 ไม่ยอมเริ่มการเชื่อมต่อ\n" +
			"• เปิด PS5 ให้อยู่หน้าหลัก (ไม่ใช่โหมดพัก) และเลือกผู้ใช้บัญชีเดียวกับที่จับคู่\n" +
			"• ปิด Remote Play ที่อุปกรณ์อื่น\n" +
			"• ยังไม่ได้: ปิด PS5 ให้สนิท ถอดปลั๊กเราเตอร์ 10 วินาที แล้วลองใหม่"
		reason.contains("crash", true) || reason.contains("Version", true) ->
			"PS5 มีปัญหาที่ระบบ Remote Play\n• ปิด-เปิด PS5 ใหม่ และอัปเดตซอฟต์แวร์ PS5 ให้ล่าสุด"
		reason.contains("Stream Connection", true) ->
			"การเชื่อมต่อกับ PS5 หลุด (เน็ตบ้านไม่เสถียร หรือ PS5 ปิดไป)\n" +
			"• ใช้สาย LAN หรือ Wi-Fi 5GHz ทั้งทีวีและ PS5\n• กด \"เชื่อมต่อใหม่\""
		reason.contains("Registration", true) ->
			"การจับคู่กับ PS5 ใช้ไม่ได้แล้ว\n• ลบ PS5 ในตั้งค่าแอป แล้วจับคู่ใหม่ (ชื่อออนไลน์ไอดี + รหัส 8 หลัก)"
		else -> "การเชื่อมต่อกับ PS5 หยุดลง\n• ตรวจว่า PS5 เปิดอยู่ และทีวีกับ PS5 ใช้เน็ตบ้านเดียวกัน แล้วกด \"เชื่อมต่อใหม่\""
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

	private fun showOverlay()
	{
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		insetsController.hide(WindowInsetsCompat.Type.systemBars())
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE
		translator?.setStreamConnected(state == StreamStateConnected)
		if(state == StreamStateConnected)
		{
			retryBox?.dismiss(); retryBox = null
			autoRetries = 0
			connectedAt = System.currentTimeMillis()
			if(!padWarned && com.metallic.chiaki.translate.Diag.gamepads().isEmpty() &&
				!androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean(com.metallic.chiaki.tv.TvActivity.KEY_HIDE_TIPS, false))
			{
				padWarned = true
				com.metallic.chiaki.translate.Diag.mark(this, com.metallic.chiaki.translate.Diag.NO_PAD_AT)
				MaterialAlertDialogBuilder(this)
					.setTitle("ยังไม่มีจอยต่อกับทีวี")
					.setMessage("จอยที่ต่อกับ PS5 ใช้ในแอปไม่ได้ (และถ้ากดปุ่ม PS ที่จอยนั้น PS5 จะตัดการเชื่อมต่อ)\n\n" +
						"วิธีแก้ (เลือกอย่างใดอย่างหนึ่ง):\n" +
						"• เสียบสาย USB จากจอยเข้าช่อง USB ของทีวี\n" +
						"• หรือจับคู่บลูทูธ: ตั้งค่าทีวี › รีโมทและอุปกรณ์เสริม › เพิ่มอุปกรณ์เสริม › ที่จอยกดปุ่ม Create + ปุ่ม PS ค้างจนไฟกะพริบ › เลือก Wireless Controller\n\n" +
						"ต่อแล้วจอยใช้ได้ทันที ไม่ต้องออกจากเกม")
					.setPositiveButton("เข้าใจแล้ว", null)
					.show()
			}
		}

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val reasonText = state.reason.toString()
						lastQuit = reasonText + (if(reasonStr != null) " · $reasonStr" else "")
						// The PS5 keeps the last session for a few seconds after a drop (or is still waking up):
						// wait and reconnect by itself a few times before bothering the player.
						if(autoRetries == 0) reportSession("หลุด: $reasonText · ${reasonStr ?: ""}")
						if(userQuit) { finish(); return }
						viewModel.input.releaseAll() // the PS5 must not keep the last stick / button held while we reconnect
						// dropped in the middle of a game (home Wi-Fi lost packets): reconnect by itself, up to 3 times
						val midGame = connectedAt > 0 && reasonText.contains("Stream Connection", true)
						if(midGame && System.currentTimeMillis() - connectedAt > 120_000L) dropRetries = 0
						if(midGame && dropRetries < 3)
						{
							dropRetries++
							dialog?.dismiss()
							showRetrying("เน็ตกระตุก การเชื่อมต่อหลุด กำลังเชื่อมต่อใหม่ให้อัตโนมัติ… ($dropRetries/3)\nแนะนำ: ใช้สาย LAN หรือ Wi-Fi 5GHz")
							binding.progressBar.visibility = View.VISIBLE
							retryHandler.postDelayed({ if(!isFinishing && !userQuit) reconnect() }, 2500L)
							return
						}
						if(isRetryable(reasonText) && autoRetries < MAX_AUTO_RETRIES)
						{
							autoRetries++
							showRetrying("PS5 ยังไม่พร้อม กำลังเชื่อมต่อใหม่ให้อัตโนมัติ… ($autoRetries/$MAX_AUTO_RETRIES)")
							binding.progressBar.visibility = View.VISIBLE
							retryHandler.postDelayed({ if(!isFinishing && !userQuit) reconnect() }, AUTO_RETRY_DELAY_MS)
							return
						}
						autoRetries = 0
						val dialog = MaterialAlertDialogBuilder(this)
							.setTitle("เชื่อมต่อ PS5 ไม่สำเร็จ")
							.setMessage(thaiQuitHelp(reasonText)
									+ "\n\n(รายละเอียด: " + reasonText + (if(reasonStr != null) " · $reasonStr" else "") + ")")
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else if(!isFinishing && state.reason.toString().contains("shut down", true))
					{
						// The PS5 itself ended the stream (e.g. right after waking from rest, or after picking a
						// user profile): it accepts a new connection a few seconds later - reconnect instead of
						// dropping the player back to the PS5 list.
						if(System.currentTimeMillis() - connectedAt > 120_000L) shutdownRetries = 0
						if(shutdownRetries == 0) reportSession("PS5 ตัดการเชื่อมต่อ: ${state.reason} · ${state.reasonString ?: ""}")
						if(userQuit) finish()
						else if(shutdownRetries < 3)
						{
							shutdownRetries++
							dialog?.dismiss()
							showRetrying("PS5 รีเซ็ตการเชื่อมต่อ กำลังเชื่อมต่อใหม่ให้อัตโนมัติ… ($shutdownRetries/3)")
							binding.progressBar.visibility = View.VISIBLE
							retryHandler.postDelayed({ if(!isFinishing && !userQuit) reconnect() }, AUTO_RETRY_DELAY_MS)
						}
						else
						{
							shutdownRetries = 0
							val d = MaterialAlertDialogBuilder(this)
								.setTitle("PS5 ตัดการเชื่อมต่อซ้ำหลายครั้ง")
								.setMessage("• ที่ PS5 เลือกผู้ใช้ (บัญชีเดียวกับที่จับคู่) ให้อยู่หน้าหลักก่อน\n" +
									"• ปิด Remote Play ที่มือถือ คอม หรือ PS Portal\n" +
									"• ห้ามกดปุ่ม PS ที่จอยที่ต่อกับ PS5\n" +
									"แล้วกด \"เชื่อมต่อใหม่\"")
								.setPositiveButton(R.string.action_reconnect) { _, _ -> dialog = null; reconnect() }
								.setNegativeButton(R.string.action_quit_session) { _, _ -> dialog = null; finish() }
								.setOnCancelListener { dialog = null; finish() }
								.create()
							dialogContents = StreamQuitDialog
							d.show()
						}
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			else ->{}
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(event.keyCode == KeyEvent.KEYCODE_BACK && !com.metallic.chiaki.session.PadConfig.fromPad(event))
		{
			if(event.action == KeyEvent.ACTION_UP) showQuitMenu()
			return true
		}
		return viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	}

	// ---- quitting: the player decides, and nothing reconnects by itself afterwards
	private var userQuit = false
	private var retryBox: androidx.appcompat.app.AlertDialog? = null

	/** Reconnect-in-progress box with a "เลิกเล่น" button, so a player who is done is never pulled back in. */
	private fun showRetrying(message: String)
	{
		retryBox?.dismiss()
		retryBox = MaterialAlertDialogBuilder(this)
			.setTitle("กำลังเชื่อมต่อใหม่")
			.setMessage(message + "\n\nเลิกเล่นแล้ว? กด \"เลิกเล่น\" แอปจะตัดการเชื่อมต่อและไม่ต่อใหม่อีก")
			.setNegativeButton("เลิกเล่น") { _, _ -> quitPlaying(false) }
			.setCancelable(false)
			.create()
			.also { d -> d.show(); d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.requestFocus() }
	}

	private fun showQuitMenu()
	{
		val d = MaterialAlertDialogBuilder(this)
			.setTitle("เลิกเล่น?")
			.setPositiveButton("เลิกเล่น") { _, _ -> quitPlaying(false) }
			.setNegativeButton("เล่นต่อ", null)
			.create()
		d.show()
		d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
	}

	/** Disconnects from the PS5 for good (no auto-reconnect), then home screen or out of the app. */
	private fun quitPlaying(exitApp: Boolean)
	{
		userQuit = true
		retryHandler.removeCallbacksAndMessages(null)
		retryBox?.dismiss(); retryBox = null
		dialog?.dismiss()
		try { translator?.stop() } catch(_: Throwable) { }
		try { viewModel.session.shutdown() } catch(_: Throwable) { }
		androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit()
			.putBoolean(com.metallic.chiaki.tv.TvActivity.KEY_SHOW_PAD_OFF, true).apply()
		if(exitApp) finishAffinity() else finish()
	}
	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

	// the DualSense touchpad click arrives as a pointer click: it goes to the game as the touchpad button
	override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
		if(viewModel.input.onPointerEvent(ev)) true else super.dispatchTouchEvent(ev)

	override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
		if(viewModel.input.onPointerEvent(ev)) true else super.dispatchGenericMotionEvent(ev)
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
